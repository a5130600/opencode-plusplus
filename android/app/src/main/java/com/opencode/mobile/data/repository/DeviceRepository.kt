package com.opencode.mobile.data.repository

import com.opencode.mobile.core.AppResult
import com.opencode.mobile.core.IoDispatcher
import com.opencode.mobile.data.local.DeviceDao
import com.opencode.mobile.data.local.DeviceEntity
import com.opencode.mobile.data.local.SecureStore
import com.opencode.mobile.data.remote.ActiveEndpoint
import com.opencode.mobile.data.remote.ActiveEndpointHolder
import com.opencode.mobile.data.remote.OpenCodeApi
import com.opencode.mobile.data.storage.CacheEvictor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/** 添加/编辑设备时用的草稿。密码单独存进 SecureStore，不落 Room。 */
data class DeviceDraft(
    val name: String,
    val scheme: String = "http",
    val host: String,
    val port: Int = 4096,
    val username: String = "opencode",
    val password: String,
    val transport: String = "tailscale",
)

@Singleton
class DeviceRepository @Inject constructor(
    private val dao: DeviceDao,
    private val secure: SecureStore,
    private val holder: ActiveEndpointHolder,
    private val api: OpenCodeApi,
    private val cacheEvictor: CacheEvictor,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    fun observeAll(): Flow<List<DeviceEntity>> = dao.observeAll()

    suspend fun byId(deviceId: String): DeviceEntity? = withContext(io) { dao.find(deviceId) }

    /** 首次启动时用来挑一个默认设备。 */
    suspend fun firstDeviceId(): String? = withContext(io) { dao.all().firstOrNull()?.id }

    suspend fun add(draft: DeviceDraft): String = withContext(io) {
        val id = UUID.randomUUID().toString()
        dao.upsert(
            DeviceEntity(
                id = id,
                name = draft.name.ifBlank { draft.host },
                scheme = draft.scheme,
                host = draft.host,
                port = draft.port,
                username = draft.username,
                transport = draft.transport,
            )
        )
        secure.putCredential(id, draft.username, draft.password)
        id
    }

    suspend fun update(deviceId: String, draft: DeviceDraft) = withContext(io) {
        val existing = dao.find(deviceId) ?: return@withContext
        dao.upsert(
            existing.copy(
                name = draft.name.ifBlank { draft.host },
                scheme = draft.scheme,
                host = draft.host,
                port = draft.port,
                username = draft.username,
                transport = draft.transport,
            )
        )
        secure.putCredential(deviceId, draft.username, draft.password)
        // 如果改的是当前生效设备，立刻让新配置生效
        if (secure.activeDeviceId == deviceId) activate(deviceId)
    }

    /**
     * 删除设备是一条链式操作：缓存目录 → 会话与消息 → 工作区 → 设备行 → 凭据。
     * 少删任何一环都会留下孤儿数据，久了就会变成查不出来的诡异 bug。
     */
    suspend fun remove(deviceId: String) = withContext(io) {
        cacheEvictor.clearDevice(deviceId)
        dao.deleteMessagesOf(deviceId)
        dao.deleteSessionsOf(deviceId)
        dao.deleteWorkspacesOf(deviceId)
        dao.delete(deviceId)
        secure.removeCredential(deviceId)
        if (secure.activeDeviceId == deviceId) {
            secure.activeDeviceId = null
            holder.set(null)
        }
    }

    /** 把某台设备设为当前生效端点。SSE、REST 全部读 [ActiveEndpointHolder]。 */
    suspend fun activate(deviceId: String): AppResult<ActiveEndpoint> = withContext(io) {
        val device = dao.find(deviceId)
            ?: return@withContext AppResult.Err("设备不存在")
        val credential = secure.credential(deviceId)
        val endpoint = ActiveEndpoint(
            deviceId = device.id,
            scheme = device.scheme,
            host = device.host,
            port = device.port,
            username = credential?.username ?: device.username,
            password = credential?.password ?: "",
        )
        holder.set(endpoint)
        secure.activeDeviceId = deviceId
        AppResult.Ok(endpoint)
    }

    /** App 启动时恢复上次使用的设备。 */
    suspend fun restoreActive(): AppResult<ActiveEndpoint>? = withContext(io) {
        val id = secure.activeDeviceId ?: return@withContext null
        activate(id)
    }

    /**
     * 探活一个指定的设备（用于"测试连接"这类一次性动作）。
     * 会临时切换端点，探完还原，避免"只是测一下"就把当前设备换掉。
     */
    suspend fun probe(deviceId: String): AppResult<Unit> = withContext(io) {
        val wasActive = holder.endpoint?.deviceId
        activate(deviceId)
        val result = reachable()
        if (result is AppResult.Ok) {
            // 服务端不提供版本号（原以为的 /global/health 不存在），
            // 所以 remoteVersion 只能留空 —— 能连上就是能连上，不编造版本。
            dao.markSeen(deviceId, System.currentTimeMillis(), null)
        }
        // 探活后把端点还原
        if (wasActive != null && wasActive != deviceId) activate(wasActive)
        result
    }

    /** 探活当前生效设备（前台服务的看门狗周期调用）。 */
    suspend fun probeActive(): AppResult<Unit> = withContext(io) {
        val id = holder.endpoint?.deviceId ?: return@withContext AppResult.Err("未选择设备")
        val result = reachable()
        if (result is AppResult.Ok) {
            dao.markSeen(id, System.currentTimeMillis(), null)
        }
        result
    }

    /**
     * 可达性判定。**只回答"电脑上的 opencode 有没有在应答"这一个问题。**
     *
     * 这里踩过一个坑，值得记下来：原来打的是 `/global/health`，而**该端点并不存在**
     * （官方 types.gen.ts 里 67 个端点，/global/ 下只有 /global/event）。
     * 结果 404 → HttpException → 被判成"离线" → 看门狗每 2 分钟就把一条
     * 完全健康的 SSE 掐断重建，无限循环。症状是连接状态反复跳"重连中"、白耗电，
     * 而根因只是一个不存在的 URL —— 极难从现象反推。
     *
     * 所以规则定得比"某个端点返回了什么"更硬：
     *
     *   1. 打一个**确认存在**的轻端点，且**不解析响应体**（见 OpenCodeApi.ping()）；
     *   2. **只要拿到了 HTTP 响应就算可达** —— 404 / 500 同样证明对端活着并在应答。
     *      真正不可达只会表现为 IOException（连接被拒 / 超时 / DNS 失败）。
     *
     * 这样即使将来路由改名或返回结构变化，最坏也只是"探活不再敏锐"，
     * 而不会退化成"健康的链路被反复掐断"。
     *
     * 唯一的例外是 401/403：那说明地址对但凭据错，是必须要让用户看见的问题，
     * 不能算健康 —— 否则他会以为连上了，实际每个业务请求都在被拒。
     */
    private suspend fun reachable(): AppResult<Unit> = try {
        api.ping()
        AppResult.Ok(Unit)
    } catch (ce: CancellationException) {
        // 取消必须穿透，否则协程取消失效
        throw ce
    } catch (he: HttpException) {
        if (he.code() == 401 || he.code() == 403) {
            AppResult.Err("认证被拒（HTTP ${he.code()}）：检查用户名与 OPENCODE_SERVER_PASSWORD")
        } else {
            // 有响应就说明对端活着。404/500 都不代表"连不上"。
            AppResult.Ok(Unit)
        }
    } catch (t: Throwable) {
        AppResult.Err(t.message ?: "连不上电脑")
    }

    suspend fun credentialFor(deviceId: String) = withContext(io) { secure.credential(deviceId) }
}
