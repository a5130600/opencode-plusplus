package com.opencode.mobile.core

import android.net.Uri

/*
 * 路径工具。
 *
 * 这一组函数存在的唯一理由：**服务端的 fs 接口是 location 沙箱**。
 *
 * `/api/fs/read/{path}`、`/api/fs/list`、`/api/fs/find` 都接受一个 deepObject 形式的
 * query：`?location[directory]=<绝对路径>`。服务端会先判断目标路径是不是落在 location
 * 里面，不在就直接 500（日志里是 "Path escapes the location"）。
 *
 * 所以 location 必须跟着**目标路径**走 —— 读文件就是文件的父目录，列目录就是那个目录本身。
 * 以前不传 location，服务端就落到它自己的默认工作区：在这个子树之外的文件一律 500，
 * 表现就是"桌面上的文件能开，别的盘一律打不开"。
 */

/** 统一成正斜杠，去掉首尾空白。后面所有判断都基于这个形态。 */
private fun unify(path: String): String = path.trim().replace('\\', '/')

fun isAbsolutePath(path: String): Boolean {
    val u = unify(path)
    if (u.isEmpty()) return false
    if (u.startsWith("//")) return true                 // UNC：//server/share
    if (u.startsWith("/")) return true                  // POSIX
    return u.length >= 2 && u[0].isLetter() && u[1] == ':'   // Windows 盘符：C:/...
}

/**
 * 相对路径 → 绝对路径。
 *
 * [baseDir] 给不出（或自己也不是绝对路径）时**原样返回**，不编造：
 * 编一个看起来对的路径，比让服务端报一个明确的错更难查。
 */
fun resolveAbsolute(path: String, baseDir: String? = null): String {
    val u = unify(path)
    if (isAbsolutePath(u)) return u
    val base = baseDir?.let(::unify)?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return u
    if (!isAbsolutePath(base)) return u
    return "$base/$u"
}

/**
 * 目标路径的**父目录** —— 也就是 `fs/read` 该带的 location。
 *
 * 拿不到（相对路径且没有 base）就返回 null：调用方据此**不带** location，
 * 让服务端按它自己的默认解析，总比传一个八竿子打不着的目录强。
 */
fun parentDirectory(path: String, baseDir: String? = null): String? {
    val abs = resolveAbsolute(path, baseDir)
    if (!isAbsolutePath(abs)) return null
    val trimmed = abs.trimEnd('/')
    val idx = trimmed.lastIndexOf('/')
    if (idx < 0) return null
    if (idx == 0) return "/"
    val parent = trimmed.substring(0, idx)
    // "C:/a.py" 切出来是 "C:"，补回斜杠才是合法目录
    return if (parent.endsWith(":")) "$parent/" else parent
}

/**
 * 把路径拼成 opencode 附件认的 `file://` URL。
 *
 * 官方文档：附件 uri 必须是**绝对路径**的 file URL，形如 `file:///home/me/src/a.ts`
 * —— 盘符路径也要带前导斜杠（`file:///C:/Users/me/a.ts`），否则标准 URL 解析会把
 * `C:` 当成 host，服务端取到的路径就废了。
 *
 * 服务端受理 prompt 前会**先把文件读一遍**，读不到整条 prompt 判 400；
 * 而且这次读取同样受 location 沙箱管辖（见本文件顶部），所以工作区外的文件
 * 光靠 file URL 是送不进去的 —— 那种情况走 [AttachmentFactory] 的 data URL 内联。
 *
 * `?start=&end=` 在 opencode 里是行号区间，所以 `?` `#` 这类字符必须转义。
 */
fun attachmentUri(path: String, baseDir: String? = null): String {
    val raw = path.trim()
    if (raw.isEmpty()) return raw

    val lower = raw.lowercase()
    // 已经是绝对 URL 或内联 data URL 的，直接放行，不再二次编码
    if (lower.startsWith("file://") || lower.startsWith("data:")) return raw

    val absolute = resolveAbsolute(raw, baseDir)
    // 统一成三斜杠形态：POSIX 本来就有前导斜杠，Windows 盘符要补一个
    val rooted = if (absolute.startsWith("/")) absolute else "/$absolute"
    // 保留 `/` 与 `:`（盘符、UNC 都要靠它们成形），其余转义
    return "file://" + Uri.encode(rooted, "/:")
}
