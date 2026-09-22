package com.opencode.mobile.ui.artifacts

import android.util.Base64
import com.opencode.mobile.core.IoDispatcher
import com.opencode.mobile.core.attachmentUri
import com.opencode.mobile.core.decodeTextSmart
import com.opencode.mobile.data.remote.dto.FileAttachmentDto
import com.opencode.mobile.data.repository.ArtifactRepository
import com.opencode.mobile.data.storage.FileKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把"让 AI 读"的目标文件变成 prompt 附件。
 *
 * ## 为什么不能只给路径
 *
 * opencode 受理附件时会**在服务端把文件读一遍**，而这次读取同样受 location 沙箱管辖
 * （和 `/api/fs/read` 是同一套校验，日志 "Path escapes the location"）。
 * 也就是说：**文件不在会话工作区里，光给 `file://` 路径是 400**，
 * 表现就是"桌面上的文件能读，别的盘一律 400 未带上文件"。
 *
 * ## 所以这里走 data URL 内联
 *
 * 文件在手机上已经缓存好了，直接把内容 base64 塞进 `uri` ——
 * 服务端不再需要去读磁盘，沙箱校验根本不发生。**这是唯一不依赖服务端路径解析的做法。**
 *
 * 官方对内联附件的限制：解码后 ≤ 20MB；类型按**字节内容**判定，
 * 只有 UTF-8 文本与 png/jpg/gif/webp 能真正给到模型，PDF 与其它二进制不行。
 * 所以 docx / xlsx 先在这里提取成文本再内联（顺带省掉大量无用 OOXML）；
 * 实在内联不了的（PDF / 压缩包 / 超大文件）才退回 `file://` 路径碰碰运气。
 */
@Singleton
class AttachmentFactory @Inject constructor(
    private val artifactRepository: ArtifactRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    companion object {
        /**
         * 内联的体积上限。远低于官方的 20MB：
         * 手机端要同时拿着字节数组和 base64 字符串，且这么大的东西喂给模型本身也不合理。
         */
        private const val MAX_INLINE_BYTES = 4L * 1024 * 1024

        private val INLINE_IMAGE_EXT = mapOf(
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "webp" to "image/webp",
        )
    }

    suspend fun build(
        deviceId: String,
        sessionId: String?,
        remotePath: String,
        displayName: String,
        baseDir: String? = null,
    ): FileAttachmentDto = withContext(io) {
        val file = when (val result = artifactRepository.openPath(
            deviceId = deviceId,
            sessionId = sessionId,
            remotePath = remotePath,
            displayName = displayName,
            baseDir = baseDir,
        )) {
            is com.opencode.mobile.core.AppResult.Ok -> result.value
            is com.opencode.mobile.core.AppResult.Err ->
                // 取不回来（比如被沙箱拦了）也照样返回一个路径附件 ——
                // 失败原因由发送那一步报错，用户能看到，比这里静默丢掉强。
                return@withContext FileAttachmentDto(
                    uri = attachmentUri(remotePath, baseDir),
                    name = displayName,
                )
        }

        inline(file, displayName)
            ?: FileAttachmentDto(uri = attachmentUri(remotePath, baseDir), name = displayName)
    }

    /** 能把内容内联就内联，不能就返回 null 交给调用方退回路径形式。 */
    private fun inline(file: File, displayName: String): FileAttachmentDto? {
        if (file.length() <= 0 || file.length() > MAX_INLINE_BYTES) return null
        val ext = FileKind.extensionOf(displayName)

        val mime = when {
            ext in INLINE_IMAGE_EXT -> INLINE_IMAGE_EXT[ext]!!
            else -> "text/plain"
        }

        val payload: ByteArray = when (FileKind.of(displayName)) {
            FileKind.OFFICE_DOC ->
                OfficeExtract.text(file)?.toByteArray(Charsets.UTF_8) ?: return null

            FileKind.OFFICE_SHEET -> {
                val grid = OfficeExtract.grid(file) ?: return null
                gridToCsv(grid).toByteArray(Charsets.UTF_8)
            }

            // 图片按原始字节内联（服务端按字节判类型，声明的 mime 只是参考）
            FileKind.IMAGE -> file.readBytes()

            FileKind.TEXT, FileKind.MARKDOWN, FileKind.CSV, FileKind.SHEET ->
                decodeTextSmart(file.readBytes()).toByteArray(Charsets.UTF_8)

            else -> return null
        }

        // svg 是文本，按文本内联；其余图片类型服务端不认，直接放弃
        if (FileKind.of(displayName) == FileKind.IMAGE && ext !in INLINE_IMAGE_EXT && ext != "svg") {
            return null
        }

        return FileAttachmentDto(
            uri = "data:$mime;base64," + Base64.encodeToString(payload, Base64.NO_WRAP),
            name = displayName,
        )
    }

    private fun gridToCsv(grid: List<List<String>>): String =
        grid.joinToString("\n") { row ->
            row.joinToString(",") { cell ->
                val needsQuote = cell.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
                if (needsQuote) "\"${cell.replace("\"", "\"\"")}\"" else cell
            }
        }
}
