package com.opencode.mobile.data.storage

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 文件类别。决定"怎么渲染"，而不只是"能不能打开"。
 *
 * 关键区分：真正需要**转换**的只有 Office 三家族。
 * 文本 / 图片 / PDF / CSV 都是端上原生能力，零依赖、零延迟。
 */
enum class FileKind {
    TEXT, MARKDOWN, IMAGE, PDF, SHEET, CSV,
    OFFICE_DOC, OFFICE_SHEET, OFFICE_SLIDE,
    ARCHIVE, BINARY;

    /** 需要走转换链路（端上 WebView 渲染，或电脑端转 PDF）。 */
    val needsConversion: Boolean
        get() = this == OFFICE_DOC || this == OFFICE_SHEET || this == OFFICE_SLIDE

    companion object {
        // ⚠️ 这三个集合的名字必须带 _EXT 后缀，不能叫 TEXT / IMAGE / ARCHIVE。
        //
        // 它们和枚举项 FileKind.TEXT / IMAGE / ARCHIVE **同名**时，在 of() 里写
        //     in TEXT -> TEXT
        // 第二个 TEXT 会解析到这个 Set 而不是枚举项，于是 when 的公共类型被推成 Any。
        // 结果就是函数报 "Return type mismatch: expected FileKind, actual Any" ——
        // 报错指向函数签名，一眼看不出是重名造成的，很费时间。
        private val TEXT_EXT = setOf(
            "ts", "tsx", "js", "jsx", "mjs", "cjs", "py", "go", "rs", "java", "kt", "kts",
            "swift", "c", "h", "cpp", "hpp", "cs", "rb", "php", "sh", "bash", "zsh", "ps1",
            "json", "jsonc", "yaml", "yml", "toml", "ini", "properties", "env",
            "sql", "graphql", "proto", "xml", "html", "htm", "css", "scss", "less", "vue",
            "svelte", "log", "txt", "diff", "patch", "gradle", "lock",
        )
        private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "svg", "ico", "heic")
        private val ARCHIVE_EXT = setOf("zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar")

        fun extensionOf(fileName: String): String =
            fileName.substringAfterLast('.', "").lowercase()

        fun of(fileName: String): FileKind = when (extensionOf(fileName)) {
            "md", "markdown", "mdx" -> MARKDOWN
            "pdf" -> PDF
            "csv" -> CSV
            "xlsx", "xlsm", "xls", "ods" -> OFFICE_SHEET
            "docx", "doc", "odt", "rtf" -> OFFICE_DOC
            "pptx", "ppt", "odp" -> OFFICE_SLIDE
            in IMAGE_EXT -> IMAGE
            in ARCHIVE_EXT -> ARCHIVE
            in TEXT_EXT -> TEXT
            else -> BINARY
        }
    }
}

/**
 * 缓存身份。
 *
 * blobId = sha256(deviceId | remotePath)
 *
 * ⚠️ 这里是**核对接口之后改过的设计**，值得记一笔。
 *
 * 最初打算做内容指纹：`blobId = sha256(deviceId|path|size|mtime)`，
 * 好处是"电脑上文件一改，blobId 就变，自动失效重建，永远读不到过期内容"。
 *
 * 但 opencode 的 `GET /file` 返回的 FileNode 只有
 * `{ name, path, absolute, type, ignored }` —— **没有 size，也没有 mtime**。
 * 服务端根本不提供任何版本标识，所以那个指纹里有两个字段是编出来的。
 * 编出来的精度比没有精度更危险：它会让"永远读不到过期内容"这句话变成假的。
 *
 * 于是拆成三个各司其职的机制：
 *   1. **身份**：路径。同一条远端路径 = 同一个 blobId，跨会话天然复用。
 *   2. **完整性**：下载完成后算 sha256 存进 content_hash，
 *      用来校验写入是否完整、以及下次重取时判断"内容到底变了没有"。
 *   3. **新鲜度**：`fetched_at` + TTL，外加 SSE 的 `file.edited` 事件主动失效，
 *      以及查看器里的手动刷新。没有假精度，失效规则是明说的。
 *
 * deviceId 必须参与计算：支持多台电脑，两台机器上都有 src/index.ts，
 * 不隔离就会互相覆盖。
 */
object BlobId {

    fun of(deviceId: String, remotePath: String): String =
        sha256Hex("$deviceId|$remotePath".toByteArray(Charsets.UTF_8))

    fun sha256Hex(bytes: ByteArray): String =
        hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** 供"边下载边算哈希"用。 */
    fun hex(digest: ByteArray): String {
        val out = CharArray(digest.size * 2)
        val chars = "0123456789abcdef"
        digest.forEachIndexed { i, b ->
            val v = b.toInt() and 0xFF
            out[i * 2] = chars[v ushr 4]
            out[i * 2 + 1] = chars[v and 0x0F]
        }
        return String(out)
    }

    /**
     * 扩展名白名单。只允许字母数字，最长 8 位。
     * 扩展名必须保留 —— 很多解码器靠它判类型；但绝不能原样信任远端输入。
     */
    fun safeExtension(fileName: String): String {
        val ext = FileKind.extensionOf(fileName)
        val cleaned = ext.filter { it.isLetterOrDigit() }.take(8)
        return if (cleaned.isEmpty()) "bin" else cleaned
    }

    /**
     * 展示用文件名的清洗。**仅用于分享暂存**，因为那里需要真实文件名。
     * 仍然剥掉所有路径分隔符，防止 "..%2F..%2Fetc%2Fpasswd" 这类构造。
     */
    fun safeDisplayName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(120)
        return cleaned.trim('.').ifBlank { "file" }
    }
}

/**
 * 三层存储的物理布局。
 *
 * ┌─ cacheDir/ ─────────── 易失：系统可清、App 可 LRU 淘汰
 * │    raw/    临时预览的原始字节
 * │    render/ 转换产物（pdf / html）—— 可再生产物，不配进持久层
 * │    thumb/  缩略图
 * │    tmp/    下载中的 .part 分片
 * └─ filesDir/ ────────── 持久：只有用户显式"离线保存"的才进来
 *      offline/  离线文件
 *      share/    分享暂存（FileProvider 只暴露这里）
 *
 * cacheDir 与 filesDir 在**同一个文件系统**上，
 * 所以"离线保存" = rename，零拷贝、原子完成。
 */
@Singleton
class StorageLayout @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val cacheRoot: File get() = File(context.cacheDir, "oc")
    private val offlineRoot: File get() = File(context.filesDir, "offline")
    private val shareRoot: File get() = File(context.filesDir, "share")

    val rawDir: File get() = File(cacheRoot, "raw")
    val renderDir: File get() = File(cacheRoot, "render")
    val thumbDir: File get() = File(cacheRoot, "thumb")
    val tmpDir: File get() = File(cacheRoot, "tmp")

    fun ensureDirs() {
        listOf(rawDir, renderDir, thumbDir, tmpDir, offlineRoot, shareRoot)
            .forEach { if (!it.exists()) it.mkdirs() }
    }

    fun rawFile(deviceId: String, blobId: String, ext: String): File =
        File(File(rawDir, sanitizeSegment(deviceId)), "$blobId.$ext")

    /** 删除某台设备时用：它在本机占用的两个目录。 */
    fun deviceDirs(deviceId: String): List<File> {
        val segment = sanitizeSegment(deviceId)
        return listOf(
            File(rawDir, segment),
            File(offlineRoot, segment),
        )
    }

    fun offlineFile(deviceId: String, sessionId: String?, blobId: String, ext: String): File {
        val sessionSegment = sessionId?.let { sanitizeSegment(it) } ?: "_detached"
        return File(
            File(File(offlineRoot, sanitizeSegment(deviceId)), sessionSegment),
            "$blobId.$ext",
        )
    }

    fun renderFile(blobId: String, renderExt: String): File =
        File(renderDir, "$blobId.$renderExt")

    fun thumbFile(blobId: String): File =
        File(File(thumbDir, blobId.take(2)), "$blobId.webp")

    fun tmpFile(blobId: String): File = File(tmpDir, "$blobId.part")

    /** 分享暂存：用真实文件名，让系统分享面板显示得像样。 */
    fun shareFile(displayName: String): File =
        File(shareRoot, BlobId.safeDisplayName(displayName))

    fun clearShareStaging() {
        shareRoot.listFiles()?.forEach { it.delete() }
    }

    /** 目录名也必须是安全字符 —— sessionId / deviceId 理论上来自远端。 */
    private fun sanitizeSegment(value: String): String {
        val cleaned = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
        return cleaned.ifBlank { "_" }
    }
}
