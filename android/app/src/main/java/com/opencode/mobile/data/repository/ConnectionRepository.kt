package com.opencode.mobile.data.repository

import com.opencode.mobile.core.ConnectionStatus
import com.opencode.mobile.core.IoDispatcher
import com.opencode.mobile.data.local.SessionDao
import com.opencode.mobile.data.remote.SseClient
import com.opencode.mobile.data.remote.dto.ErrorEventData
import com.opencode.mobile.data.remote.dto.EventDto
import com.opencode.mobile.data.remote.dto.ModelRefDto
import com.opencode.mobile.data.remote.dto.SessionCreatedData
import com.opencode.mobile.data.remote.dto.SessionIdData
import com.opencode.mobile.data.remote.dto.StepData
import com.opencode.mobile.data.remote.dto.StructuredErrorDto
import com.opencode.mobile.data.remote.dto.TokenUsageDto
import com.opencode.mobile.data.remote.dto.UsageEventData
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min

/**
 * 从 SSE 归一化出来的事件。v2 的事件名与 v1 完全不同（见 skill `opencode-desktop-api`）。
 *
 * 名字取自实测到的完整生命周期：
 *   server.connected
 *   session.created / session.execution.started / session.execution.succeeded|failed
 *   session.step.started / session.text.started|delta|ended / session.step.streamed|ended
 *   session.usage.updated / session.instructions.updated / session.inbox.*
 *   provider.updated / model.updated
 *
 * ⚠️ 已知缺口：**权限请求的事件名没观察到**（测的那句没触发审批）。
 * 所以审批不靠事件，改成执行期间轮询 `GET /api/permission/request` —— 见 ChatViewModel。
 */
sealed interface ConnectionEvent {
    data object ServerConnected : ConnectionEvent

    data class SessionCreated(
        val sessionId: String,
        val title: String,
        val directory: String?,
    ) : ConnectionEvent

    data class ExecutionStarted(val sessionId: String) : ConnectionEvent

    data class ExecutionFinished(
        val sessionId: String,
        val ok: Boolean,
        val error: StructuredErrorDto?,
    ) : ConnectionEvent

    data class StepStarted(
        val sessionId: String,
        val assistantMessageId: String?,
        val agent: String?,
        val model: ModelRefDto?,
    ) : ConnectionEvent

    /** 流式文本增量。真正让界面"边跑边出字"的就是它。 */
    data class TextDelta(
        val sessionId: String,
        val messageId: String,
        val ordinal: Int,
        val delta: String,
    ) : ConnectionEvent

    /** 一段文本结束，带完整文本（权威值）。 */
    data class TextEnded(
        val sessionId: String,
        val messageId: String,
        val ordinal: Int,
        val text: String,
    ) : ConnectionEvent

    data class StepEnded(
        val sessionId: String,
        val cost: Double?,
        val tokens: TokenUsageDto?,
    ) : ConnectionEvent

    data class UsageUpdated(
        val sessionId: String,
        val cost: Double?,
        val tokens: TokenUsageDto?,
    ) : ConnectionEvent

    /** provider / model 列表有变化，界面该重拉。 */
    data object CatalogUpdated : ConnectionEvent

    data class Unknown(val type: String) : ConnectionEvent
}

/**
 * 与电脑端的长连接。
 *
 * 三件事必须做对：
 *   1. 重连走指数退避（上限 30s）—— 固定间隔在电脑关机时会把手机电量刷干；
 *   2. 另起协程周期探活 —— SSE 是静默长连接，对端悄悄死了本端不会知道；
 *   3. **SSE 不是真相源** —— 后台期间事件会丢，回到前台必须用 REST 校准（SessionRepository.refreshStatus）。
 */
@Singleton
class ConnectionRepository @Inject constructor(
    private val sse: SseClient,
    private val json: Json,
    private val sessionDao: SessionDao,
    private val deviceRepository: DeviceRepository,
    @Named("application_scope") private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    private companion object {
        const val MAX_BACKOFF_MS = 30_000L
        const val PROBE_INTERVAL_MS = 60_000L
        const val PROBE_FAILURES_BEFORE_RECONNECT = 2
    }

    private val _status = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    /**
     * 最近一次连接失败的原因。
     *
     * 加它是被逼的：SSE 挂掉时原来只把状态设成"重连中"，真正的原因
     * （路径 404？认证 401？连不上？）全被吞在重试循环里 ——
     * 界面上只看到"重连中"，根本没法判断是网络问题还是接口写错了。
     */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _events = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<ConnectionEvent> = _events.asSharedFlow()

    private var streamJob: Job? = null
    private var watchdogJob: Job? = null

    fun start() {
        if (streamJob?.isActive == true) return
        streamJob = scope.launch { streamLoop() }
        watchdogJob = scope.launch { watchdogLoop() }
    }

    fun stop() {
        streamJob?.cancel()
        watchdogJob?.cancel()
        streamJob = null
        watchdogJob = null
        _status.value = ConnectionStatus.DISCONNECTED
    }

    fun restart() {
        stop()
        start()
    }

    private suspend fun streamLoop() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            try {
                _status.value =
                    if (attempt == 0) ConnectionStatus.CONNECTING else ConnectionStatus.RECONNECTING

                sse.events().collect { raw ->
                    _status.value = ConnectionStatus.CONNECTED
                    _lastError.value = null
                    attempt = 0
                    val event = decode(raw.data) ?: return@collect
                    applyEvent(event)
                    _events.tryEmit(event)
                }

                throw IOException("SSE 流已结束")
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _status.value = ConnectionStatus.RECONNECTING
                _lastError.value = t.message ?: t::class.java.simpleName
                attempt++
                delay(backoffMs(attempt))
            }
        }
    }

    private suspend fun watchdogLoop() {
        var consecutiveFailures = 0
        while (currentCoroutineContext().isActive) {
            delay(PROBE_INTERVAL_MS)
            val healthy = deviceRepository.probeActive().let { it is com.opencode.mobile.core.AppResult.Ok }
            if (healthy) {
                consecutiveFailures = 0
                if (_status.value == ConnectionStatus.RECONNECTING) restart()
            } else {
                consecutiveFailures++
                if (consecutiveFailures >= PROBE_FAILURES_BEFORE_RECONNECT) {
                    consecutiveFailures = 0
                    _status.value = ConnectionStatus.RECONNECTING
                    streamJob?.cancel()
                    streamJob = scope.launch { streamLoop() }
                }
            }
        }
    }

    private fun backoffMs(attempt: Int): Long =
        min(MAX_BACKOFF_MS, 1_000L * (1L shl min(attempt, 5)))

    /**
     * v2 事件信封：`{id, created, type, location, metadata, data, durable}`。
     * 负载字段是 **data**（v1 叫 properties）。
     */
    private fun decode(payload: String): ConnectionEvent? {
        val dto = runCatching { json.decodeFromString(EventDto.serializer(), payload) }.getOrNull()
            ?: return null
        val data = dto.data ?: return ConnectionEvent.Unknown(dto.type)

        return when (dto.type) {
            "server.connected" -> ConnectionEvent.ServerConnected

            "session.created" ->
                parse(data, SessionCreatedData.serializer(), dto.type) {
                    if (it.sessionID.isBlank()) ConnectionEvent.Unknown(dto.type)
                    else ConnectionEvent.SessionCreated(it.sessionID, it.title, it.location?.directory)
                }

            "session.execution.started" ->
                parse(data, SessionIdData.serializer(), dto.type) {
                    ConnectionEvent.ExecutionStarted(it.sessionID)
                }

            "session.execution.succeeded" ->
                parse(data, SessionIdData.serializer(), dto.type) {
                    ConnectionEvent.ExecutionFinished(it.sessionID, ok = true, error = null)
                }

            "session.execution.failed" ->
                parse(data, ErrorEventData.serializer(), dto.type) {
                    ConnectionEvent.ExecutionFinished(it.sessionID, ok = false, error = it.error)
                }

            "session.step.started" ->
                parse(data, StepData.serializer(), dto.type) {
                    ConnectionEvent.StepStarted(it.sessionID, it.assistantMessageID, it.agent, it.model)
                }

            "session.text.delta" ->
                parse(data, StepData.serializer(), dto.type) {
                    ConnectionEvent.TextDelta(
                        it.sessionID, it.assistantMessageID.orEmpty(), it.ordinal ?: 0, it.delta.orEmpty(),
                    )
                }

            "session.text.ended" ->
                parse(data, StepData.serializer(), dto.type) {
                    ConnectionEvent.TextEnded(
                        it.sessionID, it.assistantMessageID.orEmpty(), it.ordinal ?: 0, it.text.orEmpty(),
                    )
                }

            "session.step.ended" ->
                parse(data, StepData.serializer(), dto.type) {
                    ConnectionEvent.StepEnded(it.sessionID, it.cost, it.tokens)
                }

            "session.usage.updated" ->
                parse(data, UsageEventData.serializer(), dto.type) {
                    ConnectionEvent.UsageUpdated(it.sessionID, it.cost, it.tokens)
                }

            "provider.updated", "model.updated" -> ConnectionEvent.CatalogUpdated

            // step.streamed / instructions.updated / inbox.* / compaction 等目前不需要处理，
            // 落到这里不影响任何状态；execution 的起止才是界面关心的信号。
            else -> ConnectionEvent.Unknown(dto.type)
        }
    }

    /** 解不出来就退化成 Unknown —— 服务端加事件类型不该让连接断掉。 */
    private fun <T> parse(
        data: JsonElement,
        serializer: KSerializer<T>,
        type: String,
        map: (T) -> ConnectionEvent,
    ): ConnectionEvent = runCatching {
        map(json.decodeFromJsonElement(serializer, data))
    }.getOrElse { ConnectionEvent.Unknown(type) }

    /** 只做最轻的落库，让不订阅事件的界面也能看到状态变化。 */
    private suspend fun applyEvent(event: ConnectionEvent) = withContext(io) {
        when (event) {
            is ConnectionEvent.ExecutionStarted ->
                sessionDao.updateStatus(event.sessionId, "busy")

            is ConnectionEvent.ExecutionFinished ->
                sessionDao.updateStatus(event.sessionId, "idle")

            is ConnectionEvent.SessionCreated ->
                sessionDao.find(event.sessionId)?.let { existing ->
                    if (existing.title.isBlank() || existing.title == "未命名会话") {
                        sessionDao.upsert(existing.copy(title = event.title.ifBlank { existing.title }))
                    }
                }

            else -> Unit
        }
    }
}
