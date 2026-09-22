package com.opencode.mobile.ui.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.mobile.core.AppResult
import com.opencode.mobile.core.ConnectionStatus
import com.opencode.mobile.core.getOrNull
import com.opencode.mobile.data.local.DeviceEntity
import com.opencode.mobile.data.local.SessionEntity
import com.opencode.mobile.data.local.WorkspaceEntity
import com.opencode.mobile.data.repository.ConnectionEvent
import com.opencode.mobile.data.repository.ConnectionRepository
import com.opencode.mobile.data.repository.DeviceDraft
import com.opencode.mobile.data.repository.DeviceRepository
import com.opencode.mobile.data.remote.dto.FileAttachmentDto
import com.opencode.mobile.data.remote.dto.FormDto
import com.opencode.mobile.data.remote.dto.PermissionDto
import kotlinx.serialization.json.JsonElement
import com.opencode.mobile.data.repository.ModelOption
import com.opencode.mobile.data.repository.ModelRepository
import com.opencode.mobile.data.repository.PendingRepository
import com.opencode.mobile.data.repository.SessionRepository
import com.opencode.mobile.data.repository.WorkspaceRepository
import com.opencode.mobile.data.repository.ArtifactRepository
import com.opencode.mobile.data.storage.CacheUsage
import com.opencode.mobile.ui.artifacts.AttachmentFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AppViewModel @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val modelRepository: ModelRepository,
    private val connectionRepository: ConnectionRepository,
    private val artifactRepository: ArtifactRepository,
    private val sessionRepository: SessionRepository,
    private val pendingRepository: PendingRepository,
    private val attachmentFactory: AttachmentFactory,
) : ViewModel() {

    val status: StateFlow<ConnectionStatus> = connectionRepository.status

    /** 连接失败的原因，供连接条直接显示 —— 否则界面只有"重连中"，没法排查。 */
    val lastError: StateFlow<String?> = connectionRepository.lastError

    private val activeDeviceId = MutableStateFlow<String?>(null)
    private val modelOptions = MutableStateFlow<List<ModelOption>>(emptyList())
    /** 显式记录用户选过的模型，用来在 modelOptions 重建时重新定位选中项。 */
    private val selectedModelKey = MutableStateFlow<String?>(null)
    private val selectedWorkspacePath = MutableStateFlow<String?>(null)

    val devices: StateFlow<List<DeviceEntity>> = deviceRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeDevice: StateFlow<DeviceEntity?> = combine(devices, activeDeviceId) { list, id ->
        list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val workspaces: StateFlow<List<WorkspaceEntity>> = activeDeviceId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else workspaceRepository.observe(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val currentWorkspace: StateFlow<WorkspaceEntity?> =
        combine(workspaces, selectedWorkspacePath) { list, path ->
            list.firstOrNull { it.path == path }
                ?: list.firstOrNull { it.isCurrent }
                ?: list.firstOrNull()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 会话列表。AppRoot 需要它做"把这个文件 @ 到哪个对话"的选择器 ——
     * 和会话页列表共用同一个仓库流，新建完会话会自动出现在这里，不用手动再拉一次。
     */
    val sessions: StateFlow<List<SessionEntity>> = activeDeviceId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else sessionRepository.observeSessions(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val models: StateFlow<List<ModelOption>> = modelOptions

    val selectedModel: StateFlow<ModelOption?> =
        combine(modelOptions, selectedModelKey) { options, _ ->
            modelRepository.selectedOption(options)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 缓存用量。
     *
     * 走**观察流**而不是"进页面算一次"：以前只有启动和手动清理时会重算，
     * 于是下载完文件、清完缓存之后数字都不动，用户得杀掉 App 重进才看得到。
     * 缓存表一变这里就更新，界面什么都不用做。
     */
    val usage: StateFlow<CacheUsage?> = artifactRepository.observeUsage()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 全局待审批。
     *
     * 它**不属于任何会话页** —— 电脑上弹确认框时人可能在任何 Tab，
     * 所以这份数据挂在 AppRoot 这一层，由它弹出全局审批框。
     * 轮询由前台服务驱动的数据源统一做，这里只订阅。
     */
    val pendingPermissions: StateFlow<List<PermissionDto>> = pendingRepository.permissions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 全局待回答的提问（form）。
     *
     * 和审批同理：agent 问你的时候人可能在任何页面，
     * 不答复它就一直停在那一句上，所以这也是全局弹窗，不是会话页里的东西。
     */
    val pendingForms: StateFlow<List<FormDto>> = pendingRepository.forms
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 审批。返回 null = 成功；非 null = 要显示在弹窗里的错误文案。
     *
     * 失败时那条会**留**在待审批列表里（PermissionRepository 刻意这么做），
     * 弹窗因此不会消失，用户可以就地重试 —— 而不是点了没反应还以为批过了。
     */
    suspend fun decidePermission(permission: PermissionDto, decision: String): String? =
        when (val result = pendingRepository.replyPermission(permission, decision)) {
            is AppResult.Ok -> null
            is AppResult.Err -> result.message
        }

    /** 提交提问的答案。返回 null = 成功；非 null = 错误文案（这条会留着让用户重试）。 */
    suspend fun answerForm(form: FormDto, answer: Map<String, JsonElement>): String? =
        when (val result = pendingRepository.replyForm(form.sessionID, form.id, answer)) {
            is AppResult.Ok -> null
            is AppResult.Err -> result.message
        }

    /**
     * 跳过这一题。
     *
     * 必须真的通知服务端取消：只是关掉弹窗的话，那个 form 在电脑端还是 pending，
     * agent 会一直等 —— 用户以为跳过了，实际卡死了。
     */
    suspend fun skipForm(form: FormDto): String? =
        when (val result = pendingRepository.cancelForm(form.sessionID, form.id)) {
            is AppResult.Ok -> null
            is AppResult.Err -> result.message
        }

    /** 通知深链用：只知道 sessionId 时把标题查出来，避免跳进去顶栏空一拍。 */
    suspend fun titleOf(sessionId: String): String =
        sessionRepository.findSession(sessionId)?.title ?: ""

    init {
        viewModelScope.launch {
            selectedModelKey.value = modelRepository.selectedKey
        }
        viewModelScope.launch {
            // 恢复上次使用的设备；没有就退到第一台，再没有就等用户在设置里添加
            val restored = deviceRepository.restoreActive()
            activeDeviceId.value = restored?.getOrNull()?.deviceId
                ?: deviceRepository.firstDeviceId()
            if (activeDeviceId.value != null) {
                deviceRepository.activate(activeDeviceId.value!!)
                connectionRepository.start()
            }
            reloadForActiveDevice()
        }

        // 电脑端在动的时候，会话列表得跟着动 —— 否则要退出重进才看得到新会话。
        // 这里只订阅"会改变列表"的几类事件，别的（流式增量）由 ChatViewModel 管。
        viewModelScope.launch {
            connectionRepository.events.collect { event ->
                val deviceId = activeDeviceId.value ?: return@collect
                when (event) {
                    // 断线重连后必须重拉一次：断线期间的事件全丢了
                    is ConnectionEvent.ServerConnected -> {
                        sessionRepository.refreshSessions(deviceId)
                        workspaceRepository.sync(deviceId)
                    }

                    // 电脑上新建了会话（或手机端建的）
                    is ConnectionEvent.SessionCreated -> {
                        sessionRepository.refreshSessions(deviceId)
                    }

                    // 一轮跑完，标题/耗时/状态都可能变
                    is ConnectionEvent.ExecutionFinished -> {
                        sessionRepository.refreshSessions(deviceId)
                    }

                    else -> Unit
                }
            }
        }
    }

    /** 从后台回到前台时校准一次 —— 后台期间 SSE 事件会丢。 */
    fun onForeground() {
        viewModelScope.launch {
            val deviceId = activeDeviceId.value ?: return@launch
            reloadForActiveDevice()
            sessionRepository.refreshSessions(deviceId)
            sessionRepository.refreshStatus()
        }
    }

    /**
     * 为"让 AI 读"构造附件。
     *
     * 内容优先内联成 data URL —— 走 `file://` 路径的话，服务端会自己读一遍文件，
     * 而这次读取受 location 沙箱管辖，工作区之外的文件一律 400。
     */
    suspend fun buildAttachment(target: FileViewTarget, baseDir: String?): FileAttachmentDto =
        attachmentFactory.build(
            deviceId = target.deviceId,
            sessionId = target.sessionId,
            remotePath = target.remotePath,
            displayName = target.displayName,
            baseDir = baseDir,
        )

    /**
     * 新建会话。
     *
     * [workspacePath] 为 null 表示"跟随电脑端当前目录"，否则指定到那个工作区 ——
     * 这是用户从手机上新建会话时唯一能决定"活干在哪个目录"的机会。
     */
    suspend fun createSession(workspacePath: String? = null): AppResult<SessionEntity> {
        val deviceId = activeDeviceId.value
            ?: return AppResult.Err("还没有连接设备，先在设置里添加一台电脑")
        return sessionRepository.createSession(deviceId, workspacePath)
    }

    /** 下拉/手动刷新。 */
    fun refreshSessions() {
        viewModelScope.launch {
            activeDeviceId.value?.let { sessionRepository.refreshSessions(it) }
        }
    }

    fun refreshAll() {
        viewModelScope.launch { reloadForActiveDevice() }
    }

    private suspend fun reloadForActiveDevice() {
        val deviceId = activeDeviceId.value ?: return
        workspaceRepository.sync(deviceId)
        loadModels()
    }

    fun syncWorkspaces() {
        viewModelScope.launch {
            activeDeviceId.value?.let { workspaceRepository.sync(it) }
        }
    }

    fun selectWorkspace(path: String) {
        viewModelScope.launch {
            val deviceId = activeDeviceId.value ?: return@launch
            workspaceRepository.select(deviceId, path)
            selectedWorkspacePath.value = path
        }
    }

    fun selectDevice(deviceId: String) {
        viewModelScope.launch {
            when (deviceRepository.activate(deviceId)) {
                is AppResult.Ok -> {
                    activeDeviceId.value = deviceId
                    // 换设备必须重启长连接，否则会一直连着旧端点
                    connectionRepository.restart()
                    reloadForActiveDevice()
                }
                is AppResult.Err -> Unit
            }
        }
    }

    fun addDevice(draft: DeviceDraft) {
        viewModelScope.launch {
            val id = deviceRepository.add(draft)
            selectDevice(id)
        }
    }

    fun removeDevice(deviceId: String) {
        viewModelScope.launch {
            deviceRepository.remove(deviceId)
            if (activeDeviceId.value == deviceId) {
                connectionRepository.stop()
                activeDeviceId.value = deviceRepository.firstDeviceId()
                activeDeviceId.value?.let { deviceRepository.activate(it) }
                if (activeDeviceId.value != null) connectionRepository.start()
            }
        }
    }

    fun selectModel(option: ModelOption) {
        modelRepository.selectedKey = option.key
        // 必须改 selectedModelKey 本身：只重设 modelOptions 不会触发发射，
        // StateFlow 用结构相等去重，同内容的 List 会被判定为"没变"。
        selectedModelKey.value = option.key
    }

    private suspend fun loadModels() {
        val result = modelRepository.list()
        if (result is AppResult.Ok) modelOptions.value = result.value
    }

    fun clearPreviewCache() {
        viewModelScope.launch { artifactRepository.clearPreviewCache() }
    }
}
