package com.opencode.mobile.data.storage

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.opencode.mobile.core.AppResult
import com.opencode.mobile.core.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * 分享暂存。
 *
 * 为什么需要这一层：
 * 本地文件是以 blobId（sha256 指纹）命名的，直接分享出去，接收方看到的文件名
 * 会是一串哈希 —— 完全不可用。
 * 所以在分享前，把文件以**真实文件名**落一份到 filesDir/share/，
 * 再从那里生成 content:// URI。
 *
 * FileProvider 也只暴露 share/ 这一个子目录，绝不暴露 filesDir 根。
 */
@Singleton
class ShareStaging @Inject constructor(
    @ApplicationContext private val context: Context,
    private val layout: StorageLayout,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    suspend fun stage(localFile: File, displayName: String): AppResult<Uri> = withContext(io) {
        layout.ensureDirs()
        val target = layout.shareFile(displayName)
        try {
            if (target.exists()) target.delete()

            // 同一文件系统上优先建硬链接：零拷贝、零额外空间。
            // 不支持（或跨文件系统）就退化成拷贝 —— 不能因为省空间把功能做没了。
            try {
                Files.createLink(target.toPath(), localFile.toPath())
            } catch (_: Throwable) {
                localFile.copyTo(target, overwrite = true)
            }

            val authority = "${context.packageName}.fileprovider"
            AppResult.Ok(FileProvider.getUriForFile(context, authority, target))
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            AppResult.Err(t.message ?: "生成分享文件失败", t)
        }
    }

    /** App 回到前台或退出分享后调用，清掉暂存，避免持久层被临时文件污染。 */
    suspend fun cleanup() = withContext(io) { layout.clearShareStaging() }
}
