package com.opencode.mobile.data.repository

import com.opencode.mobile.core.AppResult
import com.opencode.mobile.core.IoDispatcher
import com.opencode.mobile.core.map
import com.opencode.mobile.core.resultOf
import com.opencode.mobile.data.local.CachedFileDao
import com.opencode.mobile.data.local.CachedFileEntity
import com.opencode.mobile.data.local.SessionDao
import com.opencode.mobile.data.remote.OpenCodeApi
import com.opencode.mobile.data.remote.dto.FileEntryDto
import com.opencode.mobile.data.storage.BlobStore
import com.opencode.mobile.data.storage.CacheEvictor
import com.opencode.mobile.data.storage.CacheUsage
import com.opencode.mobile.data.storage.FileKind
import com.opencode.mobile.data.storage.FileRef
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 产物仓库。
 *
 * v2 的文件接口：
 *   - `GET /api/fs/list?path=` → `{location, data:[{path,type}]}`，path 是相对 location.directory 的路径
 *   - `GET /api/fs/read/{path}` → **原始字节**，走 BlobStore 流式下载
 *   - `GET /api/fs/find?query=` → 同上结构
 *
 * v1 的 `/session/{id}/diff` 在 v2 换成了 `/api/session/{sessionID}/diff`，
 * 但返回结构没核对过、而且 UI 里本来就没接，所以先不实现 —— 不猜。
 */
@Singleton
class ArtifactRepository @Inject constructor(
    private val api: OpenCodeApi,
    private val blobStore: BlobStore,
    private val cachedDao: CachedFileDao,
    private val sessionDao: SessionDao,
    private val evictor: CacheEvictor,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * 列目录。
     *
     * [dir] 必须是**被列的那个目录的绝对路径**，它同时充当 path 与 location ——
     * fs/list 是沙箱接口，location 用错就是 500。
     */
    suspend fun listFiles(dir: String? = null): AppResult<List<FileEntryDto>> = withContext(io) {
        val target = dir?.takeIf { it.isNotBlank() }
        resultOf { api.listFiles(path = target, location = target) }.map { it.data }
    }

    suspend fun searchFiles(query: String, dir: String? = null): AppResult<List<FileEntryDto>> =
        withContext(io) {
            resultOf { api.findFiles(query = query, location = dir?.takeIf { it.isNotBlank() }) }
                .map { it.data }
        }

    /** 当前所在目录（服务端视角）。 */
    suspend fun currentDirectory(): AppResult<String> = withContext(io) {
        resultOf { api.location() }.map { it.directory }
    }

    /**
     * 会话所属的工作目录。
     *
     * 用途只有一个：**相对路径要靠它解析成绝对路径**，进而算出 fs/read 的 location。
     * 它不是"当前选中的工作区"（那个不能拿来当 location，见 core/Paths.kt 顶部）。
     */
    suspend fun workspaceDirOf(sessionId: String?): String? = withContext(io) {
        sessionId?.let { sessionDao.find(it)?.workspacePath }?.takeIf { it.isNotBlank() }
    }

    /* ── 打开文件（缓存优先） ────────────────────────────────────────── */

    suspend fun openFile(
        deviceId: String,
        sessionId: String?,
        entry: FileEntryDto,
    ): AppResult<File> = openPath(
        deviceId = deviceId,
        sessionId = sessionId,
        remotePath = entry.path,
        displayName = entry.name,
    )

    suspend fun openPath(
        deviceId: String,
        sessionId: String?,
        remotePath: String,
        displayName: String,
        forceRefresh: Boolean = false,
        baseDir: String? = null,
    ): AppResult<File> {
        evictor.enforceQuota()
        val base = baseDir?.takeIf { it.isNotBlank() } ?: workspaceDirOf(sessionId)
        return blobStore.ensure(
            ref = FileRef(deviceId, sessionId, remotePath, displayName),
            forceRefresh = forceRefresh,
            baseDir = base,
        )
    }

    /** 用户主动拉最新内容（查看器里的刷新）。 */
    suspend fun refreshFile(
        deviceId: String,
        sessionId: String?,
        remotePath: String,
        displayName: String,
        baseDir: String? = null,
    ): AppResult<File> = openPath(
        deviceId = deviceId,
        sessionId = sessionId,
        remotePath = remotePath,
        displayName = displayName,
        forceRefresh = true,
        baseDir = baseDir,
    )

    /* ── 本地缓存 ────────────────────────────────────────────────────── */

    fun observeCached(deviceId: String): Flow<List<CachedFileEntity>> =
        cachedDao.observeByDevice(deviceId)

    fun observeCachedInSession(sessionId: String): Flow<List<CachedFileEntity>> =
        cachedDao.observeBySession(sessionId)

    fun kindOf(fileName: String): FileKind = FileKind.of(fileName)

    suspend fun setOffline(blobId: String, offline: Boolean): AppResult<File> =
        blobStore.setOffline(blobId, offline)

    /** Agent 改过某个文件 → 本地副本作废。 */
    suspend fun invalidate(deviceId: String, remotePath: String) =
        blobStore.invalidate(deviceId, remotePath)

    suspend fun localFile(blobId: String): File? = blobStore.localOrNull(blobId)

    suspend fun cachedRow(blobId: String): CachedFileEntity? = withContext(io) {
        cachedDao.find(blobId)
    }

    /* ── 配额 ────────────────────────────────────────────────────────── */

    suspend fun usage(): CacheUsage = evictor.usage()

    /** 用量观察流：缓存表一变就重算，界面不用自己记着刷新。 */
    fun observeUsage(): Flow<CacheUsage> = evictor.observeUsage()

    suspend fun clearPreviewCache(): Long = evictor.clearPreviewCache()

    suspend fun enforceQuota() = evictor.enforceQuota()
}
