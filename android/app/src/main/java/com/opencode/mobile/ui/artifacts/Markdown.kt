package com.opencode.mobile.ui.artifacts

/**
 * 极简 Markdown → HTML。
 *
 * 为什么不引第三方库：产物里最常见的两种文件就是 `.md` 和代码，
 * 而把 markdown-it / marked 塞进 APK（几十到几百 KB 的 JS 资产）只为渲染
 * "标题 + 列表 + 代码块 + 加粗链接"，性价比很低。
 *
 * 覆盖范围刻意限定在**技术文档真正会用到的语法**：
 * 标题、围栏代码、引用、有序/无序列表、分隔线、行内代码、粗体、斜体、链接。
 * 表格、脚注、HTML 内联这些一律不处理 —— 遇到就按普通段落显示，不会渲染成火星文。
 *
 * 安全点：**所有文本都先转义再拼标签**，用户产物里的 `<script>` 只会被显示成
 * 字面量，不可能在 WebView 里执行。
 */
object Markdown {

    fun toHtml(markdown: String, dark: Boolean): String =
        wrapStyle(dark) + "<body>" + render(markdown) + "</body>"

    private fun render(src: String): String {
        val out = StringBuilder()
        val lines = src.replace("\r\n", "\n").split('\n')
        var i = 0

        var inCode = false

        while (i < lines.size) {
            val line = lines[i]

            // ── 围栏代码块：内容整体转义，不做任何行内处理 ──
            if (line.trimStart().startsWith("```")) {
                if (!inCode) {
                    inCode = true
                    out.append("<pre><code>")
                } else {
                    inCode = false
                    out.append("</code></pre>")
                }
                i++
                continue
            }
            if (inCode) {
                out.append(escape(line)).append('\n')
                i++
                continue
            }

            when {
                line.isBlank() -> i++

                line.trimStart().startsWith("</") || line.trimStart().startsWith("<") -> {
                    // 产物里自带 HTML 的文档不少（比如导出的 md），原样跳过，
                    // 只当纯文本显示，避免把 View 结构注进来。
                    out.append("<p>").append(escape(line)).append("</p>")
                    i++
                }

                Regex("^\\s{0,3}([-*_])\\s*\\1\\s*\\1[\\s\\S]*$").matches(line) -> {
                    out.append("<hr/>")
                    i++
                }

                Regex("^\\s{0,3}#{1,6}\\s+.*$").matches(line) -> {
                    val level = line.trimStart().takeWhile { it == '#' }.length.coerceIn(1, 6)
                    val text = line.trimStart().dropWhile { it == '#' }.trim()
                    out.append("<h").append(level).append('>')
                        .append(inline(text))
                        .append("</h").append(level).append('>')
                    i++
                }

                line.trimStart().startsWith(">") -> {
                    out.append("<blockquote>")
                    while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                        out.append("<p>")
                            .append(inline(lines[i].trimStart().removePrefix(">").trim()))
                            .append("</p>")
                        i++
                    }
                    out.append("</blockquote>")
                }

                Regex("^\\s*([-*+])\\s+.*$").matches(line) -> {
                    out.append("<ul>")
                    while (i < lines.size && Regex("^\\s*([-*+])\\s+.*$").matches(lines[i])) {
                        out.append("<li>")
                            .append(inline(lines[i].trimStart().drop(2)))
                            .append("</li>")
                        i++
                    }
                    out.append("</ul>")
                }

                Regex("^\\s*\\d+[.)]\\s+.*$").matches(line) -> {
                    out.append("<ol>")
                    while (i < lines.size && Regex("^\\s*\\d+[.)]\\s+.*$").matches(lines[i])) {
                        out.append("<li>")
                            .append(inline(lines[i].trimStart().substringAfter(' ')))
                            .append("</li>")
                        i++
                    }
                    out.append("</ol>")
                }

                else -> {
                    // 连续非空行合并成一个段落 —— 这是 markdown 的软换行语义
                    val para = StringBuilder()
                    while (i < lines.size && lines[i].isNotBlank() &&
                        !lines[i].trimStart().startsWith("```") &&
                        !Regex("^\\s{0,3}#{1,6}\\s+.*$").matches(lines[i]) &&
                        !Regex("^\\s*([-*+]|\\d+[.)])\\s+.*$").matches(lines[i]) &&
                        !lines[i].trimStart().startsWith(">")
                    ) {
                        if (para.isNotEmpty()) para.append(' ')
                        para.append(lines[i].trim())
                        i++
                    }
                    out.append("<p>").append(inline(para.toString())).append("</p>")
                }
            }
        }
        if (inCode) out.append("</code></pre>")   // 尾部未闭合的围栏，兜住
        return out.toString()
    }

    /** 行内语法。顺序要紧：先转义，再处理代码，最后才是加粗/斜体/链接。 */
    private fun inline(raw: String): String {
        var s = escape(raw)

        // 行内代码先抽出来占位，避免其中的 * 和 _ 被当成强调
        val codes = mutableListOf<String>()
        s = Regex("`([^`]+)`").replace(s) { m ->
            codes.add(m.groupValues[1])
            "\u0000${codes.size - 1}\u0000"
        }

        s = Regex("\\*\\*([^*]+)\\*\\*").replace(s, "<strong>$1</strong>")
        s = Regex("(?<!\\*)\\*([^*]+)\\*(?!\\*)").replace(s, "<em>$1</em>")
        s = Regex("\\[([^]]+)]\\((https?://[^)\\s]+)\\)")
            .replace(s, "<a href=\"$2\">$1</a>")
        s = Regex("&lt;(https?://[^&\\s]+)&gt;")
            .replace(s, "<a href=\"$1\">$1</a>")

        codes.forEachIndexed { index, code ->
            s = s.replace("\u0000$index\u0000", "<code>$code</code>")
        }
        return s
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /**
     * 内联样式而不是外链 CSS：产物是本地文件，任何一次外链请求
     * 都会变成一次白屏等待（弱网下尤其明显）。
     */
    private fun wrapStyle(dark: Boolean): String {
        val bg = if (dark) "#171715" else "#FFFFFF"
        val fg = if (dark) "#F2F0EB" else "#1E1E1B"
        val muted = if (dark) "#B9B5AD" else "#6B6862"
        val line = if (dark) "#2A2A25" else "#EFECE6"
        val codeBg = if (dark) "#26261F" else "#F7F5F1"
        return """
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
        <style>
          :root { color-scheme: ${if (dark) "dark" else "light"}; }
          body { margin:0; padding:16px 16px 28px; background:$bg; color:$fg;
                 font:400 14.5px/1.68 -apple-system, "Noto Sans SC", sans-serif;
                 -webkit-text-size-adjust:100%; word-wrap:break-word; }
          h1,h2,h3,h4,h5,h6 { margin:20px 0 8px; line-height:1.35; font-weight:600; }
          h1 { font-size:22px; } h2 { font-size:19px; } h3 { font-size:16.5px; }
          h4,h5,h6 { font-size:15px; color:$muted; }
          p { margin:9px 0; }
          a { color:$fg; text-decoration:underline; text-underline-offset:2px; }
          code { background:$codeBg; border-radius:5px; padding:1.5px 5px;
                 font:400 13px/1.5 ui-monospace, Menlo, Consolas, monospace; }
          pre { background:$codeBg; border:1px solid $line; border-radius:11px;
                padding:12px 13px; overflow:auto; }
          pre code { background:none; padding:0; font-size:12.5px; line-height:1.55; }
          blockquote { margin:10px 0; padding:2px 0 2px 12px; border-left:3px solid $line;
                       color:$muted; }
          blockquote p { margin:5px 0; }
          ul,ol { margin:9px 0; padding-left:22px; }
          li { margin:4px 0; }
          hr { border:none; border-top:1px solid $line; margin:20px 0; }
          img { max-width:100%; height:auto; border-radius:8px; }
        </style>
        </head>
        """.trimIndent()
    }
}
