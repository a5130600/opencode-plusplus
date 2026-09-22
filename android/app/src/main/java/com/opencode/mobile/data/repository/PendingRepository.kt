package com.opencode.mobile.data.repository

import com.opencode.mobile.core.AppResult
import com.opencode.mobile.core.IoDispatcher
import com.opencode.mobile.core.resultOf
import com.opencode.mobile.data.local.SessionDao
import com.opencode.mobile.data.remote.OpenCodeApi
import com.opencode.mobile.data.remote.dto.FormDto
import com.opencode.mobile.data.remote.dto.FormReplyRequest
import com.opencode.mobile.data.remote.dto.PermissionDto
import com.opencode.mobile.data.remote.dto.PermissionReplyRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 电脑端"挂起的两类交互"的**唯一数据源**：权限审批 与 agent 提问（form）。
 *
 * 为什么合成一个而不是各写一个：两者的生命周期完全一样
 * （都由前台服务驱动、都要弹全局框、都要发通知、漏一次都会让电脑端卡住），
 * 拆成两个仓库就变成两条各跑各的轮询 —— 请求量翻倍，还容易出现"权限清了、提问还在"的不一致。
 *
 * 以前这两件事有两个互不相通的副本（服务一份、会话页一份），
 * 结果就是人在别的 Tab 时 App 内完全无感知。现在服务与界面订阅同一份数据。
 */
@Singleton
class PendingRepository @Inject constructor(
    private val api: OpenCodeApi,
    private val sessionDao: SessionDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** 自己的作用域：轮询的生命周期归服务，不归任何一个界面。 */
    private val scope = CoroutineScope(SupervisorJob() + io)
    private var pollJob: Job? = null

    private val _permissions = MutableStateFlow<List<PermissionDto>>(emptyList())
    val permissions: StateFlow<List<PermissionDto>> = _permissions.asStateFlow()

    private val _forms = MutableStateFlow<List<FormDto>>(emptyList())
    val forms: StateFlow<List<FormDto>> = _forms.asStateFlow()

    fun start() {
        if (pollJob != null) return
        pollJob = scope.launch {
            while (true) {
                swallow { refresh() }
                delay(POLL_MS)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        _permissions.value = emptyList()
        _forms.value = emptyList()
    }

    /**
     * 拉一次挂起列表。
     *
     * **两条路必须一起走**（权限和提问都是这个坑）：
     *   · `/api/permission/request`、`/api/form` 是全局的，但契约里带 `location[directory]` ——
     *     不传就只拿服务端默认目录的，项目在别的盘 / 别的目录时整条都看不见；
     *   · `/api/session/{id}/permission`、`/api/session/{id}/form` 按会话问，一定准，
     *     但得先知道问哪些会话。
     *
     * 所以：全局那条打底（一次覆盖大多数情况），再用最近若干个会话补一遍，合并去重。
     * 漏掉一次 = 电脑端的任务一直停在那一步等你，所以宁可多问几次。
     */
    suspend fun refresh() {
        val permissions = LinkedHashMap<String, PermissionDto>()
        val forms = LinkedHashMap<String, FormDto>()

        swallow { api.pendingPermissions().data }?.forEach { permissions[it.id] = it }
        swallow { api.pendingForms().data }?.forEach { forms[it.id] = it }

        val cutoff = System.currentTimeMillis() - WATCH_WINDOW_MS
        val watched = swallow { sessionDao.recent(WATCH_LIMIT) }
            .orEmpty()
            .filter { (it.updatedAt ?: it.createdAt ?: 0L) >= cutoff }

        watched.forEach { session ->
            swallow { api.sessionPermissions(session.id).data }?.forEach { permissions[it.id] = it }
            swallow { api.sessionForms(session.id).data }?.forEach { forms[it.id] = it }
        }

        _permissions.value = permissions.values.toList()
        _forms.value = forms.values.toList()
    }

    /**
     * 回复一条权限审批。decision 取值见 core/Permissions.kt（once / always / reject）。
     *
     * **失败时刻意不把它从列表里摘掉**：没送到就是没送到，弹窗该继续顶着让人重试。
     * 悄悄消失会让人以为批过了 —— 而电脑端其实还卡在那一步等答复。
     */
    suspend fun replyPermission(
        sessionId: String,
        permissionId: String,
        decision: String,
    ): AppResult<Unit> = withContext(io) {
        when (val result = resultOf {
            api.replyPermission(sessionId, permissionId, PermissionReplyRequest(decision))
        }) {
            is AppResult.Ok -> {
                _permissions.update { list -> list.filterNot { it.id == permissionId } }
                AppResult.Ok(Unit)
            }

            is AppResult.Err -> AppResult.Err(result.message, result.cause)
        }
    }

    suspend fun replyPermission(permission: PermissionDto, decision: String): AppResult<Unit> =
        replyPermission(permission.sessionID, permission.id, decision)

    /** 提交提问的答案。answer = { 字段key: 值 }，值的类型随字段走。 */
    suspend fun replyForm(
        sessionId: String,
        formId: String,
        answer: Map<String, JsonElement>,
    ): AppResult<Unit> = withContext(io) {
        when (val result = resultOf { api.replyForm(sessionId, formId, FormReplyRequest(answer)) }) {
            is AppResult.Ok -> {
                _forms.update { list -> list.filterNot { it.id == formId } }
                AppResult.Ok(Unit)
            }

            is AppResult.Err -> AppResult.Err(result.message, result.cause)
        }
    }

    /**
     * 跳过这一题。
     *
     * 必须真的告诉服务端"不答了"：静默关掉弹窗的话，
     * 电脑端那个 form 会一直处于 pending，agent 永远等在那儿。
     */
    suspend fun cancelForm(sessionId: String, formId: String): AppResult<Unit> =
        withContext(io) {
            when (val result = resultOf { api.cancelForm(sessionId, formId) }) {
                is AppResult.Ok -> {
                    _forms.update { list -> list.filterNot { it.id == formId } }
                    AppResult.Ok(Unit)
                }

                is AppResult.Err -> AppResult.Err(result.message, result.cause)
            }
        }

    /** 网络错误不该打断轮询循环，但取消必须穿透 —— 否则 stop() 停不干净。 */
    private suspend fun <T> swallow(block: suspend () -> T): T? = try {
        block()
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Throwable) {
        null
    }

    companion object {
        /** 3 秒：这两类交互都会**阻塞电脑端的任务**，慢一拍就多卡一会儿。 */
        const val POLL_MS = 3_000L

        /** 只盯最近 2 小时内动过的会话，避免对陈年会话发无意义的请求。 */
        const val WATCH_WINDOW_MS = 2 * 60 * 60 * 1000L

        /** 最多盯 3 个会话：请求量是 (2 + 2×N) 一轮，再往上不划算。 */
        const val WATCH_LIMIT = 3
    }
}
