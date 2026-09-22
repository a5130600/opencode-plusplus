package com.opencode.mobile.data.storage

import com.opencode.mobile.core.AppResult
import com.opencode.mobile.core.IoDispatcher
import com.opencode.mobile.core.parentDirectory
import com.opencode.mobile.data.local.CachedFileDao
import com.opencode.mobile.data.local.CachedFileEntity
import com.opencode.mobile.data.local.CachedState
import com.opencode.mobile.data.remote.OpenCodeApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/** 一个远端文件的引用。身份完全由 deviceId + remotePath 决定。 */
data class FileRef(
    val deviceId: String,
    val sessionId: String?,
    val remotePath: String,
    val displayName: String,
)

data class CacheUsage(
    val previewBytes: Long,
    val offlineBytes: Long,
    val quotaBytes: Long,
    val offlineCount: Int,
) {
    val totalBytes: Long get() = previewBytes + offlineBytes
}

/**
 * 本地文件仓。
 *
 * v2 的 `/api/fs/read/{path}` 返回的是**原始字节流**（不是 JSON、也不是 base64），
 * 所以下载是流式的：边读边写 `.part` 边算 sha256，写完原子 rename。
 * v1 那套"整份 JSON 进内存 + base64 解码 + 64MB 上限"的问题这里都不存在了。
 *
 * 新鲜度：服务端不提供版本号，所以用 `fetched_at` + TTL，加上重取时比对内容哈希。
 */
@Singleton
class BlobStore @Inject constructor(
    private val layout: StorageLayout,
    private val dao: CachedFileDao,
    private val api: OpenCodeApi,
    private val evictor: CacheEvictor,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    companion object {
        /** 预览缓存的新鲜期。超过就重取一次并比对哈希。 */
        const val STALE_AFTER_MS = 10 * 60 * 1000L

        /** 单文件上限。流式下内存不随文件增长，这个值只是防止把缓存塞爆。 */
        const val MAX_FILE_BYTES = 256L * 1024 * 1024
    }

    /**
     * @param baseDir 远端路径是相对路径时，用它解析成绝对路径（通常是会话所属的工作目录）。
     *                解析出的绝对路径的**父目录**会作为 fs/read 的 location 一起发出去 ——
     *                少了它，服务端就会拿自己的默认工作区做沙箱校验，工作区外的文件一律 500。
     */
    suspend fun ensure(
        ref: FileRef,
        forceRefresh: Boolean = false,
        baseDir: String? = null,
    ): AppResult<File> =
        withContext(io) {
            layout.ensureDirs()
            val ext = BlobId.safeExtension(ref.displayName)
            val kind = FileKind.of(ref.displayName)
            val blobId = BlobId.of(ref.deviceId, ref.remotePath)
            val now = System.currentTimeMillis()

            val cached = dao.find(blobId)
            if (!forceRefresh && cached?.state == CachedState.READY) {
                val fresh = now - cached.fetchedAt < STALE_AFTER_MS
                val file = cached.localPath?.let(::File)
                if (fresh && file != null && file.exists() && file.length() > 0) {
                    dao.touch(blobId, now)
                    return@withContext AppResult.Ok(file)
                }
                if (file == null || !file.exists()) dao.delete(blobId)
            }

            if (dao.find(blobId) == null) {
                dao.upsert(
                    CachedFileEntity(
                        blobId = blobId,
                        deviceId = ref.deviceId,
                        sessionId = ref.sessionId,
                        remotePath = ref.remotePath,
                        displayName = ref.displayName,
                        ext = ext,
                        kind = kind.name,
                        size = 0L,
                        contentHash = null,
                        state = CachedState.DOWNLOADING,
                        pin = false,
                        fetchedAt = now,
                        accessedAt = now,
                    )
                )
            } else {
                dao.updateProgress(blobId, CachedState.DOWNLOADING)
            }

            val part = layout.tmpFile(blobId)
            try {
                part.parentFile?.mkdirs()
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L

                api.readFile(
                    path = encodePath(ref.remotePath),
                    location = parentDirectory(ref.remotePath, baseDir),
                ).use { body ->
                    body.byteStream().use { input ->
                        FileOutputStream(part).use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                total += n
                                if (total > MAX_FILE_BYTES) {
                                    throw IOException("文件超过 ${MAX_FILE_BYTES / 1024 / 1024} MB 上限")
                                }
                                digest.update(buf, 0, n)
                                out.write(buf, 0, n)
                            }
                            out.flush()
                            out.fd.sync()
                        }
                    }
                }

                val hash = BlobId.hex(digest.digest())

                // 内容没变 → 只刷新时间戳，省掉一次落盘替换
                if (cached?.state == CachedState.READY && cached.contentHash == hash) {
                    val existing = cached.localPath?.let(::File)
                    if (existing != null && existing.exists()) {
                        part.delete()
                        dao.markRefreshed(blobId, now)
                        return@withContext AppResult.Ok(existing)
                    }
                }

                evictor.enforceQuota()

                val target = layout.rawFile(ref.deviceId, blobId, ext)
                target.parentFile?.mkdirs()
                Files.move(
                    part.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
                dao.markReady(blobId, target.absolutePath, target.length(), hash, now)
                AppResult.Ok(target)
            } catch (ce: CancellationException) {
                runCatching { part.delete() }
                throw ce
            } catch (t: Throwable) {
                runCatching { part.delete() }
                dao.updateProgress(blobId, CachedState.FAILED)
                AppResult.Err(t.message ?: "取文件失败", t)
            }
        }

    /**
     * 把远端路径编成可以放进 URL 路径的形式。
     * Windows 路径的反斜杠要转正斜杠，各段再做 percent-encode；配 Retrofit 的 encoded=true 原样拼。
     */
    private fun encodePath(remotePath: String): String {
        val normalized = remotePath.replace('\\', '/')
        return normalized.split('/').joinToString("/") { segment ->
            java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
    }

    /** 离线保存 / 取消。cacheDir 与 filesDir 同文件系统，所以是 rename 而不是拷贝。 */
    suspend fun setOffline(blobId: String, offline: Boolean): AppResult<File> = withContext(io) {
        layout.ensureDirs()
        val row = dao.find(blobId) ?: return@withContext AppResult.Err("缓存记录不存在")
        if (row.state != CachedState.READY) return@withContext AppResult.Err("文件还没取回来")

        if (row.pin == offline) {
            val current = row.localPath?.let(::File)?.takeIf { it.exists() }
            return@withContext if (current != null) AppResult.Ok(current)
            else AppResult.Err("本地文件缺失")
        }

        val current = row.localPath?.let(::File)
            ?: return@withContext AppResult.Err("本地文件缺失")
        if (!current.exists()) {
            dao.delete(blobId)
            return@withContext AppResult.Err("本地文件已被系统清理，请重新打开")
        }

        val target = if (offline) {
            layout.offlineFile(row.deviceId, row.sessionId, row.blobId, row.ext)
        } else {
            layout.rawFile(row.deviceId, row.blobId, row.ext)
        }
        target.parentFile?.mkdirs()
        return@withContext try {
            Files.move(
                current.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            dao.setPin(blobId, offline, target.absolutePath)
            AppResult.Ok(target)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            AppResult.Err(t.message ?: "迁移文件失败", t)
        }
    }

    /** 已落盘的本地文件（cache 或 offline 都算）。不触发下载。 */
    suspend fun localOrNull(blobId: String): File? = withContext(io) {
        val row = dao.find(blobId) ?: return@withContext null
        if (row.state != CachedState.READY) return@withContext null
        row.localPath?.let(::File)?.takeIf { it.exists() }
    }

    suspend fun renderOrNull(blobId: String): File? = withContext(io) {
        dao.find(blobId)?.renderPath?.let(::File)?.takeIf { it.exists() }
    }

    suspend fun recordRender(blobId: String, kind: String, file: File) = withContext(io) {
        dao.setRender(blobId, kind, file.absolutePath)
    }

    /** Agent 改过这个文件 → 缓存立即作废。 */
    suspend fun invalidate(deviceId: String, remotePath: String) = withContext(io) {
        val blobId = BlobId.of(deviceId, remotePath)
        val row = dao.find(blobId) ?: return@withContext
        if (row.fetchedAt == 0L) return@withContext
        dao.markRefreshed(blobId, 0L)
    }

    suspend fun invalidateAll(deviceId: String) = withContext(io) {
        dao.rowsOfDevice(deviceId).forEach { dao.markRefreshed(it.blobId, 0L) }
    }

    suspend fun blobIdFor(ref: FileRef): String = BlobId.of(ref.deviceId, ref.remotePath)
}
