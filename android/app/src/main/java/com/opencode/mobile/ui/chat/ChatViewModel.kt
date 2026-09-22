package com.opencode.mobile.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.mobile.core.AppResult
import com.opencode.mobile.data.local.MessageEntity
import com.opencode.mobile.data.remote.JsonFactory
import com.opencode.mobile.data.remote.dto.FileAttachmentDto
import com.opencode.mobile.data.remote.dto.MessageDto
import com.opencode.mobile.data.repository.ArtifactRepository
import com.opencode.mobile.data.repository.ConnectionEvent
import com.opencode.mobile.data.repository.ConnectionRepository
import com.opencode.mobile.data.repository.ModelOption
import com.opencode.mobile.data.repository.SessionRepository
import com.opencode.mobile.ui.nav.FileViewTarget
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/** 本地发出的消息的投递状态。 */
enum class SendState { Sending, Sent, Failed }

/** 会话里一条可渲染的消息。 */
data class ChatItem(
    val id: String,
    val role: String,
    val blocks: List<ChatBlock>,
    val createdAt: Long?,
    /** 仅本地乐观消息有值；服务端回来的消息为 null。 */
    val sendState: SendState? = null,
    /** 失败后重试用的本地 id。 */
    val localId: String? = null,
) {
    val isUser: Boolean get() = role.equals("user", ignoreCase = true)
}

/** 只渲染能确认语义的三类块。 */
sealed interface ChatBlock {
    /** 正文，直接显示。 */
    data class Text(val text: String) : ChatBlock

    /** 模型的思考过程。默认折起来 —— 它很长，但通常不需要细看。 */
    data class Reasoning(val text: String) : ChatBlock

    data class Tool(
        val name: String,
        val title: String?,
        val status: String?,
        val callId: String?,
    ) : ChatBlock

    data class FileRef(val path: String, val name: String) : ChatBlock

    /** 折起来的那一类（思考 + 工具调用）。 */
    val isProcess: Boolean
        get() = this is Reasoning || this is Tool
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val artifactRepository: ArtifactRepository,
    private val connectionRepository: ConnectionRepository,
) : ViewModel() {

    private val json = JsonFactory.INSTANCE

    private val sessionId = MutableStateFlow<String?>(null)
    private var boundId: String? = null

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    /** idle | busy */
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    /**
     * 待发送的附件（"让 AI 读这个文件"带来的那一个）。
     *
     * 它是真附件：发送时进 `prompt.files`，服务端会读文件内容一起给模型。
     * 刻意**不自动发送** —— 花 token 的事要用户自己按发送键。
     */
    private val _attachment = MutableStateFlow<FileAttachmentDto?>(null)
    val attachment: StateFlow<FileAttachmentDto?> = _attachment.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    /**
     * 是否该显示"正在思考"的等待动画。
     *
     * 关键在于**点发送就立刻置位**，而不是等服务端回话：
     * 从点下到 opencode 真正开始吐字之间有一段无反馈的空窗（网络 + 模型排队），
     * 那几秒没有动画用户会以为没发出去。直到第一段文字到达才收起。
     */
    private val _thinking = MutableStateFlow(false)
    val thinking: StateFlow<Boolean> = _thinking.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _pendingOpen = MutableStateFlow<FileViewTarget?>(null)
    val pendingOpen: StateFlow<FileViewTarget?> = _pendingOpen.asStateFlow()

    /*
     * 历史消息分页。
     *
     * 会话消息是**分页**的（服务端默认只给一页），不翻页的话长会话往上翻到头就没了。
     * 这里只管"更早那一页"：进会话/事件校准拉的是最新一页，翻到顶部时再按 cursor 补。
     *
     * cursor 是服务端给的不透明串，不需要给 Compose 观察，用普通字段存即可；
     * 界面只需要知道"还能不能翻"和"正在翻"。
     */
    private var olderCursor: String? = null

    private val _hasOlder = MutableStateFlow(false)
    val hasOlder: StateFlow<Boolean> = _hasOlder.asStateFlow()

    private val _loadingOlder = MutableStateFlow(false)
    val loadingOlder: StateFlow<Boolean> = _loadingOlder.asStateFlow()

    /** 是否真的翻过更早的页 —— 没翻过就不显示"没有更早的消息了"那条提示。 */
    private val _pagedOlder = MutableStateFlow(false)
    val pagedOlder: StateFlow<Boolean> = _pagedOlder.asStateFlow()

    /** 本地乐观消息：点发送立刻上屏，不等服务端回。 */
    private data class Outgoing(
        val localId: String,
        val text: String,
        val state: SendState,
        val createdAt: Long,
    )

    private val _outgoing = MutableStateFlow<List<Outgoing>>(emptyList())

    /** 正在流式输出的文本，key 是 assistantMessageID。落库只发生在整轮结束后。 */
    private val streamed = MutableStateFlow<Map<String, String>>(emptyMap())

    val items: StateFlow<List<ChatItem>> = sessionId
        .flatMapLatest { id ->
            if (id.isNullOrBlank()) {
                flowOf(emptyList())
            } else {
                combine(
                    sessionRepository.observeMessages(id),
                    streamed,
                    _outgoing,
                ) { rows, live, outgoing ->
                    buildItems(rows, live, outgoing)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun buildItems(
        rows: List<MessageEntity>,
        live: Map<String, String>,
        outgoing: List<Outgoing>,
    ): List<ChatItem> {
        val done = rows.mapNotNull(::toChatItem)

        // 服务端已经回显的本地消息不再显示，否则会出现两条一模一样的
        val echoed = rows.filter { it.role == "user" }
            .mapNotNull { textOf(it) }
            .toSet()

        val pending = outgoing
            .filter { it.state == SendState.Failed || it.text !in echoed }
            .map {
                ChatItem(
                    id = it.localId,
                    role = "user",
                    blocks = listOf(ChatBlock.Text(it.text)),
                    createdAt = it.createdAt,
                    sendState = it.state,
                    localId = it.localId,
                )
            }

        val known = rows.map { it.id }.toSet()
        // 还没落库的流式消息，先合成一条顶上，避免"跑完了才突然出现"
        val streaming = live
            .filterKeys { it !in known && it.isNotBlank() }
            .map { (mid, text) ->
                ChatItem(mid, "assistant", listOf(ChatBlock.Text(text)), null)
            }

        return done + pending + streaming
    }

    /** 从落库的信封里取用户消息正文 —— 用户消息的正文是平铺的 `text`。 */
    private fun textOf(entity: MessageEntity): String? = runCatching {
        json.decodeFromString(MessageDto.serializer(), entity.payload).text
    }.getOrNull()

    /** 发送失败后重试同一条。 */
    fun retry(localId: String, option: ModelOption?) {
        val target = _outgoing.value.firstOrNull { it.localId == localId } ?: return
        _outgoing.update { list -> list.filterNot { it.localId == localId } }
        _draft.value = target.text
        send(option)
    }

    init {
        viewModelScope.launch {
            connectionRepository.events.collect { onConnectionEvent(it) }
        }
    }

    /**
     * 绑定会话。
     *
     * prefill / attachment 的处理**必须在 boundId 短路之前**：
     * ChatViewModel 跨路由复用（同一个会话再被打开一次时不会重建），
     * 放在短路之后的话，"让 AI 读"第二次就什么都不会发生。
     */
    fun bind(id: String, prefill: String? = null, attachment: FileAttachmentDto? = null) {
        if (!prefill.isNullOrBlank()) _draft.value = prefill
        if (attachment != null) _attachment.value = attachment
        if (boundId == id) return
        boundId = id

        /*
         * 换会话必须把上一个会话的残留清干净。
         *
         * ChatViewModel 是跨路由复用的（没有 Navigation 作用域），不清的话：
         *   - 本地乐观消息会跟着跑到下一个会话里，变成一条永远不动的幽灵气泡；
         *   - 上一个文件也会挂在输入框上，被误发给不相干的会话。
         */
        _outgoing.value = emptyList()
        _thinking.value = false
        _status.value = null
        // 分页状态同样属于"上一个会话"：不清的话新会话会拿着旧会话的 cursor 去翻页，
        // 翻出来的还是上一个会话的消息（或者干脆报 404）。
        olderCursor = null
        _hasOlder.value = false
        _loadingOlder.value = false
        _pagedOlder.value = false
        if (attachment == null) _attachment.value = null

        if (id.isBlank()) return
        sessionId.value = id
        streamed.value = emptyMap()

        viewModelScope.launch {
            sessionRepository.findSession(id)?.let { session ->
                _title.value = session.title
                _status.value = session.status
                // 进来时这轮本来就在跑（比如长时间任务），也该看到等待动画
                if (session.status != null && session.status != "idle") _thinking.value = true
            }
            refreshMessages(id)
            sessionRepository.refreshStatus()
        }
    }

    private suspend fun onConnectionEvent(event: ConnectionEvent) {
        val current = sessionId.value ?: return
        when (event) {
            is ConnectionEvent.ExecutionStarted -> {
                if (event.sessionId != current) return
                // 待审批不在这里轮询了：统一由 PermissionRepository（前台服务驱动）
                // 全局盯，弹窗挂在 AppRoot 上。会话页只管显示对话。
                _status.value = "busy"
            }

            is ConnectionEvent.ExecutionFinished -> {
                if (event.sessionId != current) return
                _status.value = "idle"
                _thinking.value = false
                streamed.value = emptyMap()
                // 事件只给了起止，真实内容用一次 REST 拿准
                refreshMessages(current)
                event.error?.let { err ->
                    _notice.value = err.message.ifBlank { "执行失败（${err.type}）" }
                }
            }

            is ConnectionEvent.TextDelta -> {
                if (event.sessionId != current || event.messageId.isBlank()) return
                _thinking.value = false      // 开始出字了，等待动画让位
                streamed.update { map ->
                    map + (event.messageId to ((map[event.messageId] ?: "") + event.delta))
                }
            }

            is ConnectionEvent.TextEnded -> {
                if (event.sessionId != current || event.messageId.isBlank()) return
                _thinking.value = false
                // text.ended 是权威值，直接覆盖增量拼出来的
                streamed.update { it + (event.messageId to event.text) }
            }

            is ConnectionEvent.StepStarted -> {
                if (event.sessionId == current) _status.value = "busy"
            }

            else -> Unit
        }
    }

    fun onDraftChange(value: String) {
        _draft.value = value
    }

    /** 用户手动摘掉附件 —— 想只发文字时用。 */
    fun removeAttachment() {
        _attachment.value = null
    }

    /** 下发指令。选了模型就先把模型设到本会话上，再发。 */
    fun send(option: ModelOption?) {
        val id = sessionId.value ?: return
        val text = _draft.value.trim()
        if (text.isEmpty()) return

        // 附件取出来就清掉：不管这次发送成功还是失败，
        // 都不该在下一条完全无关的话里悄悄再挂一遍同一个文件。
        val attached = _attachment.value
        _attachment.value = null

        _draft.value = ""
        _sending.value = true
        _thinking.value = true          // 立刻给反馈，不等服务端

        // 乐观上屏：消息先出现在对话里，投递状态随后更新，而不是发完了才冒出来
        val localId = "local_" + System.currentTimeMillis()
        _outgoing.update {
            it + Outgoing(localId, text, SendState.Sending, System.currentTimeMillis())
        }
        markOutgoing(localId, SendState.Sending)

        viewModelScope.launch {
            // 整个流程都要被 finally 兜住：任何一步抛异常（包括 Retrofit 在调用时才校验
            // 参数类型抛的 IllegalArgumentException）都会让 sending 卡在 true，
            // 表现就是"发送按钮变灰消失、再也发不出去"，且看不出原因。
            try {
                if (option != null) {
                    sessionRepository.selectModel(id, option.providerId, option.modelId)
                }
                val files = attached?.let { listOf(it) } ?: emptyList()
                when (val result = sessionRepository.sendPrompt(id, text, files)) {
                    is AppResult.Ok -> {
                        _status.value = "busy"
                        sessionRepository.setStatus(id, "busy")
                        // 服务端已经把这条 user 消息落库了，立刻拉一次取代本地副本。
                        // 只靠 SSE 的话，ExecutionFinished 没推过来就再也没人会拉。
                        refreshMessages(id)
                        markOutgoing(localId, SendState.Sent)
                        retireOutgoing(id, localId)
                    }

                    is AppResult.Err -> {
                        // 附件的路径拼不出来时服务端会 400，把原因说清楚，
                        // 否则用户只看到"发送失败"却不知道是文件挂不上。
                        _notice.value = if (attached != null) {
                            "${result.message}（附件未带上，可以只发文字重试）"
                        } else {
                            result.message
                        }
                        _thinking.value = false
                        markOutgoing(localId, SendState.Failed)
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _notice.value = t.message ?: t::class.java.simpleName
                _thinking.value = false
                markOutgoing(localId, SendState.Failed)
            } finally {
                _sending.value = false
            }
        }
    }

    private fun markOutgoing(localId: String, state: SendState) {
        _outgoing.update { list ->
            list.map { if (it.localId == localId) it.copy(state = state) else it }
        }
    }

    /**
     * 撤掉本地乐观副本。
     *
     * 它只是"先让你看见"的占位，真实消息由 REST 落库渲染。
     * 靠文本比对去重是**靠不住的** —— 带附件发出去时服务端回显的 text 未必与我们发的
     * 逐字节相同，比不上就永远匹配不掉，那条副本就一直钉在列表底部不动。
     *
     * 所以改成：再拉一次权威消息，然后无条件撤掉副本。失败的那条要留着（它是重试入口）。
     */
    private fun retireOutgoing(sessionId: String, localId: String) {
        viewModelScope.launch {
            delay(2_500)
            refreshMessages(sessionId)
            _outgoing.update { list -> list.filterNot { it.localId == localId } }
        }
    }

    fun abort() {
        val id = sessionId.value ?: return
        viewModelScope.launch {
            when (val result = sessionRepository.interrupt(id)) {
                is AppResult.Ok -> _status.value = "idle"
                is AppResult.Err -> _notice.value = result.message
            }
        }
    }

    fun openFile(path: String, displayName: String) {
        val id = sessionId.value ?: return
        viewModelScope.launch {
            val session = sessionRepository.findSession(id)
            val deviceId = session?.deviceId ?: return@launch
            _pendingOpen.value = FileViewTarget(
                deviceId = deviceId,
                sessionId = id,
                remotePath = path,
                displayName = displayName.ifBlank { path.substringAfterLast('/') },
            )
        }
    }

    fun consumeOpen() {
        _pendingOpen.value = null
    }

    fun consumeNotice() {
        _notice.value = null
    }

    fun refresh() {
        val id = sessionId.value ?: return
        viewModelScope.launch {
            refreshMessages(id)
            sessionRepository.refreshStatus()
        }
    }

    /**
     * 拉最新一页，并记下"更早那一页"的 cursor。
     *
     * 所有刷新都走这里而不是直接调仓库 —— 否则分页状态永远是第一次进会话时的那一个。
     */
    private suspend fun refreshMessages(id: String) {
        when (val result = sessionRepository.loadMessages(id)) {
            is AppResult.Ok -> {
                olderCursor = result.value.olderCursor
                _hasOlder.value = result.value.hasOlder
            }

            is AppResult.Err -> Unit   // 拉不到就保持原样，界面上的旧内容还能看
        }
    }

    /**
     * 翻一页更早的消息。由界面在"列表滚到接近顶部"时调用。
     *
     * 三个护栏：正在翻就不重复翻、没有 cursor 就不翻、已经确认到顶就不翻。
     * 仓库那边对"返回空页"也会把 hasOlder 置 false —— 实测服务端即使没内容
     * 也照样给一个非 null 的 cursor，只有真翻一次才知道是空的。
     */
    fun loadOlder() {
        val id = sessionId.value ?: return
        val cursor = olderCursor ?: return
        if (_loadingOlder.value || !_hasOlder.value) return
        _loadingOlder.value = true
        viewModelScope.launch {
            when (val result = sessionRepository.loadOlder(id, cursor)) {
                is AppResult.Ok -> {
                    olderCursor = result.value.olderCursor
                    _hasOlder.value = result.value.hasOlder
                    _pagedOlder.value = true
                }

                is AppResult.Err -> _notice.value = "更早的消息加载失败：${result.message}"
            }
            _loadingOlder.value = false
        }
    }

    /* ── 映射 ────────────────────────────────────────────────────────── */

    private fun toChatItem(entity: MessageEntity): ChatItem? {
        val msg = runCatching {
            json.decodeFromString(MessageDto.serializer(), entity.payload)
        }.getOrNull() ?: return null

        val blocks = when (msg.type) {
            "user" -> listOfNotNull(msg.text?.takeIf { it.isNotBlank() }?.let { ChatBlock.Text(it) })

            "assistant" -> msg.content.mapNotNull { block ->
                when {
                    block.type == "text" && !block.text.isNullOrBlank() ->
                        ChatBlock.Text(block.text!!)

                    block.type == "reasoning" && !block.text.isNullOrBlank() ->
                        ChatBlock.Reasoning(block.text!!)

                    block.type == "tool" -> ChatBlock.Tool(
                        name = block.name ?: "tool",
                        title = block.state?.title,
                        status = block.state?.status,
                        callId = block.id,
                    )

                    else -> null
                }
            }

            else -> emptyList()
        }

        if (blocks.isEmpty()) return null
        return ChatItem(
            id = entity.id,
            role = msg.type.ifBlank { entity.role },
            blocks = blocks,
            createdAt = msg.time?.created ?: entity.sortKey,
        )
    }
}
