package com.opencode.mobile.data.repository

import com.opencode.mobile.core.AppResult
import com.opencode.mobile.core.IoDispatcher
import com.opencode.mobile.core.PermissionResponses
import com.opencode.mobile.core.map
import com.opencode.mobile.core.resultOf
import com.opencode.mobile.data.local.MessageDao
import com.opencode.mobile.data.local.MessageEntity
import com.opencode.mobile.data.local.SessionDao
import com.opencode.mobile.data.local.SessionEntity
import com.opencode.mobile.data.remote.OpenCodeApi
import com.opencode.mobile.data.remote.dto.MessageDto
import com.opencode.mobile.data.remote.dto.FileAttachmentDto
import com.opencode.mobile.data.remote.dto.MessagePageDto
import com.opencode.mobile.data.remote.dto.PermissionReplyRequest
import com.opencode.mobile.data.remote.dto.PromptRequest
import com.opencode.mobile.data.remote.dto.SessionDto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionRepository @Inject constructor(
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
    private val api: OpenCodeApi,
    private val json: Json,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    fun observeSessions(deviceId: String): Flow<List<SessionEntity>> = sessionDao.observe(deviceId)

    fun observeMessages(sessionId: String): Flow<List<MessageEntity>> =
        messageDao.observe(sessionId)

    suspend fun findSession(sessionId: String): SessionEntity? = withContext(io) {
        sessionDao.find(sessionId)
    }

    suspend fun refreshSessions(deviceId: String): AppResult<Int> = withContext(io) {
        when (val result = resultOf { api.listSessions() }) {
            is AppResult.Err -> result
            is AppResult.Ok -> {
                val entities = result.value.data.map { it.toEntity(deviceId) }
                sessionDao.upsertAll(entities)
                AppResult.Ok(entities.size)
            }
        }
    }

    /**
     * v2 没有"会话状态"端点。
     * `/api/session/active` 空闲时返回 `{data:{}}`，推测 data 的 key 就是活跃会话 id，
     * 所以这里把 key 标成 busy、其余清空。**这个形状是推断的**，解析失败就原样保留。
     */
    suspend fun refreshStatus() = withContext(io) {
        val active = runCatching {
            val raw = api.activeSessions().data
            (raw as? JsonObject)?.keys?.toList().orEmpty()
        }.getOrElse { return@withContext }

        sessionDao.allIds().forEach { id ->
            sessionDao.updateStatus(id, if (id in active) "busy" else null)
        }
    }

    /**
     * 新建会话。
     *
     * v2 的 POST /api/session **不接受 title**（标题由服务端生成），
     * 所以这里不传标题，创建后再拉一次列表把真实标题取回来。
     *
     * [workspacePath] 不为空时带上 `location:{directory}` —— 这是 v2 里指定
     * "这个会话在哪个目录下干活"的唯一入口（body 是 additionalProperties:false，
     * 所以不能顺手塞别的键）。不传就是跟随电脑端当前目录。
     */
    suspend fun createSession(
        deviceId: String,
        workspacePath: String? = null,
    ): AppResult<SessionEntity> = withContext(io) {
        val body = buildJsonObject {
            val dir = workspacePath?.trim()?.takeIf { it.isNotEmpty() }
            if (dir != null) {
                put("location", buildJsonObject { put("directory", dir) })
            }
        }
        when (val result = resultOf { api.createSession(body) }) {
            is AppResult.Err -> result
            is AppResult.Ok -> {
                val entity = result.value.data.toEntity(deviceId)
                sessionDao.upsert(entity)
                AppResult.Ok(entity)
            }
        }
    }

    /**
     * 下发指令。
     *
     * body 只有 text —— v2 的 prompt 不接受 parts，也不接受 model；
     * 模型要用 [selectModel] 单独设。
     *
     * [files] 是可选的附件（"让 AI 读这个文件"走的这条路）。
     * 注意它是**真附件**：服务端会把文件内容读出来一起给模型，
     * 而不是只把路径当字符串贴过去。
     */
    suspend fun sendPrompt(
        sessionId: String,
        text: String,
        files: List<FileAttachmentDto> = emptyList(),
    ): AppResult<Unit> = withContext(io) {
        resultOf { api.prompt(sessionId, PromptRequest(text = text, files = files)) }.map { }
    }

    /** 指定本会话使用的模型。body 形状已核对：{model:{id,providerID,variant?}} */
    suspend fun selectModel(
        sessionId: String,
        providerId: String,
        modelId: String,
    ): AppResult<Unit> = withContext(io) {
        val body = buildJsonObject {
            put("model", buildJsonObject {
                put("id", modelId)
                put("providerID", providerId)
            })
        }
        resultOf { api.selectSessionModel(sessionId, body) }.map { }
    }

    /** 中止这一轮。 */
    suspend fun interrupt(sessionId: String): AppResult<Unit> = withContext(io) {
        resultOf { api.interrupt(sessionId) }.map { }
    }

    /**
     * 权限审批。回复体字段名是 **decision**（v1 叫 response）。
     * 判错用户就得跑到电脑前再点一次，所以枚举只在 core/Permissions.kt 定义一处。
     */
    suspend fun replyPermission(
        sessionId: String,
        requestId: String,
        allow: Boolean,
    ): AppResult<Unit> = withContext(io) {
        val decision = if (allow) PermissionResponses.ALLOW else PermissionResponses.REJECT
        resultOf {
            api.replyPermission(sessionId, requestId, PermissionReplyRequest(decision))
        }.map { }
    }

    /** 待审批列表（用于进入会话时校准，事件会丢）。 */
    suspend fun pendingPermissions(sessionId: String) = withContext(io) {
        resultOf { api.sessionPermissions(sessionId).data }
    }

    /**
     * 删除会话（电脑端同步删）。
     *
     * **远端删成功才删本地**：反过来的话，网络一断用户以为删掉了，
     * 下次刷新列表它又冒出来 —— 那种"删了又活过来"最伤信任。
     * 远端失败就把原因交回界面，让它自己决定要不要重试。
     */
    suspend fun deleteSession(sessionId: String): AppResult<Unit> = withContext(io) {
        when (val result = resultOf { api.deleteSession(sessionId) }) {
            is AppResult.Err -> result
            is AppResult.Ok -> {
                sessionDao.deleteMessages(sessionId)
                sessionDao.delete(sessionId)
                AppResult.Ok(Unit)
            }
        }
    }

    companion object {
        /** 一次拉多少条消息。接口上限是 200，取一半留余量（见 OpenCodeApi.listMessages 的注释）。 */
        const val MESSAGE_PAGE = 100
    }

    /**
     * 一页消息的拉取结果。
     *
     * [olderCursor] 是把这一页再往前（更早）翻的凭据；为 null 表示已经到顶。
     * 注意：**空页也要如实回报** —— 实测服务端即使已经没有更早的消息，
     * 仍可能返回一个非 null 的 cursor，只有真去翻一次才会知道是空的。
     */
    data class MessageSlice(
        val count: Int,
        val olderCursor: String?,
        val hasOlder: Boolean,
    )

    /**
     * 拉最新一页（进会话、事件校准、发送后都走这里）。
     *
     * 只覆盖"最近这一页"，**不会**动更早那些已经翻出来的页 —— 见 [pruneDeleted] 的说明。
     */
    suspend fun loadMessages(sessionId: String): AppResult<MessageSlice> =
        fetchPage(sessionId, cursor = null)

    /** 往前翻一页更早的消息。[cursor] 来自上一页的 [MessageSlice.olderCursor]。 */
    suspend fun loadOlder(sessionId: String, cursor: String): AppResult<MessageSlice> =
        fetchPage(sessionId, cursor = cursor)

    private suspend fun fetchPage(sessionId: String, cursor: String?): AppResult<MessageSlice> =
        withContext(io) {
            when (val result = resultOf { api.listMessages(sessionId, MESSAGE_PAGE, cursor) }) {
                is AppResult.Err -> result
                is AppResult.Ok -> {
                    val page = result.value
                    val entities = page.data.mapIndexed { index, msg ->
                        msg.toEntity(sessionId, index, page.data.size)
                    }
                    messageDao.upsertAll(entities)

                    // 只有"拉最新一页"才有资格谈清理：翻更早的页时本地已有更旧的数据，
                    // 那一页里没有它们是正常的，不能当成"服务端删了"。
                    if (cursor == null) pruneDeleted(sessionId, entities)

                    val older = page.cursor?.next
                    AppResult.Ok(
                        MessageSlice(
                            count = entities.size,
                            olderCursor = older,
                            // 空页说明真的到顶了，别再拿同一个 cursor 去翻
                            hasOlder = older != null && entities.isNotEmpty(),
                        )
                    )
                }
            }
        }

    /**
     * 清掉服务端已经删掉的消息。
     *
     * 只在这一页覆盖的区间内清理（sortKey >= 本页最早那条）:
     * 全量删的话，用户辛辛苦苦翻出来的更早几页会在下一次刷新时**全部被判定成"服务端删了"**，
     * 翻一页没一页。
     */
    private suspend fun pruneDeleted(sessionId: String, entities: List<MessageEntity>) {
        if (entities.isEmpty()) return
        val oldestInPage = entities.minOf { it.sortKey }
        val keep = entities.map { it.id }.toSet()
        messageDao.list(sessionId).forEach { row ->
            if (row.sortKey >= oldestInPage && row.id !in keep) messageDao.delete(row.id)
        }
    }

    /** 事件里带 session.created 时用来把新会话补进列表。 */
    suspend fun upsertSession(deviceId: String, session: SessionDto) = withContext(io) {
        sessionDao.upsert(session.toEntity(deviceId))
    }

    suspend fun setStatus(sessionId: String, status: String?) = withContext(io) {
        sessionDao.updateStatus(sessionId, status)
    }

    /* ── 映射 ────────────────────────────────────────────────────────── */

    private fun SessionDto.toEntity(deviceId: String) = SessionEntity(
        id = id,
        deviceId = deviceId,
        title = title.ifBlank { "未命名会话" },
        parentId = null,
        status = null,
        createdAt = time?.created,
        updatedAt = time?.updated,
        workspacePath = location?.directory?.ifBlank { null },
    )

    /**
     * 消息按原始 JSON 落库，拆字段等于每次服务端加类型就改表。
     *
     * sortKey 用 `created*1000 + 本次拉取内的序号` —— v2 的消息时间戳只到毫秒，
     * 同一毫秒内会有多条（user/assistant/idle 常常挨在一起），光靠时间戳排序不稳定。
     *
     * ⚠️ [index] 是**响应里的下标**，而接口默认是 **desc（新的在前）**，
     * 所以填进 sortKey 前要先翻成"按时间升序的序号"，否则同一毫秒内的两条会被排反。
     */
    private fun MessageDto.toEntity(sessionId: String, index: Int, size: Int): MessageEntity {
        val created = time?.created ?: 0L
        val ascIndex = size - 1 - index
        return MessageEntity(
            id = id,
            sessionId = sessionId,
            role = type,
            payload = json.encodeToString(MessageDto.serializer(), this),
            sortKey = created * 1000 + ascIndex,
        )
    }
}
