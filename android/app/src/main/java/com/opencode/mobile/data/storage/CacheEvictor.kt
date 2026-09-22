package com.opencode.mobile.data.storage

import com.opencode.mobile.core.IoDispatcher
import com.opencode.mobile.data.local.CachedFileDao
import com.opencode.mobile.data.local.CachedFileEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 缓存配额与淘汰。
 *
 * 淘汰顺序刻意固定为 **render → thumb → raw**：
 *  - render（转换产物）最便宜，重转一次几秒；
 *  - raw（原始字节）最贵，要重新下载。
 * 永远先牺牲"重建成本最低"的那一份。
 *
 * pin = 1（用户显式离线保存）**永不参与**任何自动淘汰。
 */
@Singleton
class CacheEvictor @Inject constructor(
    private val layout: StorageLayout,
    private val dao: CachedFileDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    companion object {
        /** 默认配额：只约束"预览"部分，离线文件不计入。 */
        const val DEFAULT_QUOTA_BYTES = 500L * 1024 * 1024
    }

    private fun sizeOf(entity: CachedFileEntity): Long {
        var total = 0L
        entity.localPath?.let { File(it).takeIf(File::exists)?.let { f -> total += f.length() } }
        entity.renderPath?.let { File(it).takeIf(File::exists)?.let { f -> total += f.length() } }
        return total
    }

    suspend fun usage(quotaBytes: Long = DEFAULT_QUOTA_BYTES): CacheUsage = withContext(io) {
        compute(dao.all(), quotaBytes)
    }

    /**
     * 用量观察流。
     *
     * 以前界面只能"进页面时算一次"，于是清完缓存、下载完文件之后数字都不动，
     * 用户得杀掉 App 重进才看得到 —— 那种"数据不准"比没有数据更让人不敢操作。
     * 缓存表一变这里就重算（文件长度是 IO，所以 flowOn 到 IO 线程）。
     */
    fun observeUsage(quotaBytes: Long = DEFAULT_QUOTA_BYTES): Flow<CacheUsage> =
        dao.observeAll().map { rows -> compute(rows, quotaBytes) }.flowOn(io)

    private fun compute(rows: List<CachedFileEntity>, quotaBytes: Long): CacheUsage {
        var preview = 0L
        var offline = 0L
        var pinnedCount = 0
        rows.forEach { row ->
            val size = sizeOf(row)
            if (row.pin) {
                offline += size
                pinnedCount++
            } else {
                preview += size
            }
        }
        return CacheUsage(preview, offline, quotaBytes, pinnedCount)
    }

    /** 写入前 / 回到前台时调用。幂等，超配额才会真正删东西。 */
    suspend fun enforceQuota(quotaBytes: Long = DEFAULT_QUOTA_BYTES) = withContext(io) {
        var preview = dao.previewRowsByLru()
            .filter { !it.pin }
            .sumOf { sizeOf(it) }
        if (preview <= quotaBytes) return@withContext

        // ── 第一轮：只删可再生的转换产物，索引保留 ──
        for (row in dao.previewRowsByLru()) {
            if (preview <= quotaBytes) return@withContext
            val render = row.renderPath?.let(::File)
            if (render != null && render.exists()) {
                val size = render.length()
                if (render.delete()) {
                    preview -= size
                    dao.clearRender(row.blobId)
                }
            }
        }

        // ── 第二轮：删缩略图（这里不单独建行，直接按文件名推断）──
        // 缩略图体积很小，放在 raw 之前清一次即可。
        layout.thumbDir.walkTopDown().filter { it.isFile }.forEach { it.delete() }

        // ── 第三轮：删原始缓存（整行删除）──
        for (row in dao.previewRowsByLru()) {
            if (preview <= quotaBytes) return@withContext
            val size = sizeOf(row)
            row.localPath?.let { File(it).takeIf(File::exists)?.delete() }
            row.renderPath?.let { File(it).takeIf(File::exists)?.delete() }
            dao.delete(row.blobId)
            preview -= size
        }
    }

    /** 设置页的"清理预览缓存"。返回释放的字节数。 */
    suspend fun clearPreviewCache(): Long = withContext(io) {
        var freed = 0L
        dao.previewRowsByLru().forEach { row ->
            val before = sizeOf(row)
            row.localPath?.let { File(it).takeIf(File::exists)?.delete() }
            row.renderPath?.let { File(it).takeIf(File::exists)?.delete() }
            dao.delete(row.blobId)
            freed += before
        }
        layout.thumbDir.walkTopDown().filter { it.isFile }.forEach { it.delete() }
        layout.tmpDir.walkTopDown().filter { it.isFile }.forEach { it.delete() }
        layout.clearShareStaging()
        freed
    }

    /** 退出登录 / 删除设备时用：连离线文件一起清。 */
    suspend fun clearAll(): Long = withContext(io) {
        var freed = 0L
        dao.all().forEach { row ->
            freed += sizeOf(row)
            row.localPath?.let { File(it).takeIf(File::exists)?.delete() }
            row.renderPath?.let { File(it).takeIf(File::exists)?.delete() }
            dao.delete(row.blobId)
        }
        listOf(layout.thumbDir, layout.tmpDir).forEach { dir ->
            dir.walkTopDown().filter { it.isFile }.forEach { it.delete() }
        }
        layout.clearShareStaging()
        freed
    }

    /** 删某台设备时，连带清掉它的缓存目录。 */
    suspend fun clearDevice(deviceId: String): Long = withContext(io) {
        var freed = 0L
        dao.rowsOfDevice(deviceId).forEach { row ->
            freed += sizeOf(row)
            row.localPath?.let { File(it).takeIf(File::exists)?.delete() }
            row.renderPath?.let { File(it).takeIf(File::exists)?.delete() }
        }
        dao.deleteOfDevice(deviceId)
        layout.deviceDirs(deviceId).forEach { dir ->
            if (dir.exists()) dir.deleteRecursively()
        }
        freed
    }
}
