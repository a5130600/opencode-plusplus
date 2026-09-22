package com.opencode.mobile.ui.artifacts

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/*
 * Office 文档的端上提取。
 *
 * ⚠️ 为什么放在 ui 层而不是 data：它只服务于"预览界面"，不包含任何取文件逻辑
 * （文件早就下载到本地了，这里纯粹是 File → 界面要的数据）。放进 data 会让人
 * 误以为它是仓库层的一部分，还牵扯 DI。
 *
 * ⚠️ 为什么不用第三方库 / 不用 WebView + JS 库：
 *   - mammoth.js / SheetJS 打包进 assets 要多 1~2MB，还得把整份文件 base64 塞进 WebView；
 *   - docx/xlsx 的 OOXML 只需要用到其中很小一部分（段落文本 / 单元格），
 *     自己解析的代码量远小于引入一个渲染器带来的不确定性。
 *
 * ⚠️ 只支持 OOXML（docx / xlsx / xlsm）。旧版 .doc / .xls 是 OLE2 二进制复合文档，
 * 解析它是另一套东西（相当于写一个精简版 POI），**明确不做** —— 走到 extract 时
 * 会返回 null，界面上诚实提示，而不是假装能开。
 *
 * 已知取舍（写在前面，免得后来人以为是 bug）：
 *   - 只取文本与单元格值：图片、图表、样式、页眉页脚、批注、脚注全部丢弃；
 *   - docx 里的表格按"单元格各自一行"输出，不做还原成网格。
 *   这两条符合"手机上只看内容"的定位，要做完整排版还原应该走让 AI 读那条路。
 */

object OfficeExtract {

    /** xlsx / xlsm → 行列。不是支持的包（或解析失败）返回 null。 */
    fun grid(file: File, maxRows: Int = 400, maxCols: Int = 30): List<List<String>>? =
        runCatching { readGrid(file, maxRows, maxCols) }.getOrNull()

    /** docx → 纯文本（一段一行）。不是支持的包（或解析失败）返回 null。 */
    fun text(file: File, maxChars: Int = 2 * 1024 * 1024): String? =
        runCatching { readText(file, maxChars) }.getOrNull()

    /* ── xlsx ──────────────────────────────────────────────────────────── */

    private fun readGrid(file: File, maxRows: Int, maxCols: Int): List<List<String>> {
        // 共享字符串单独一趟读：它在包里不一定排在 worksheet 前面，
        // 边流式边解析没法保证已经读到 strings。
        val strings = readSharedStrings(file)

        ZipInputStream(FileInputStream(file).buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: return emptyList()
                if (entry.isWorksheet) return readSheet(zis, strings, maxRows, maxCols)
            }
        }
    }

    private fun readSheet(
        input: java.io.InputStream,
        strings: List<String>,
        maxRows: Int,
        maxCols: Int,
    ): List<List<String>> {
        val rows = ArrayList<List<String>>(minOf(maxRows, 64))
        // TreeMap 保证按列号排序：OOXML 允许单元格乱序出现，也允许跳空列
        var cells = java.util.TreeMap<Int, String>()
        var cellOrder = 0
        var done = false

        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var event = parser.eventType
        var cellRef = ""
        var cellIsShared = false
        var inCell = false
        var value = StringBuilder()
        var inline = StringBuilder()

        fun flush() {
            if (cells.isEmpty()) {
                rows.add(emptyList())
            } else {
                val line = ArrayList<String>(cells.size)
                for ((_, v) in cells) {
                    if (line.size >= maxCols) break
                    line.add(v)
                }
                if (line.isEmpty()) line.add("")
                rows.add(line)
            }
            cells = java.util.TreeMap()
            cellOrder = 0
        }

        while (event != XmlPullParser.END_DOCUMENT && !done) {
            when (event) {
                XmlPullParser.START_TAG -> when (localName(parser)) {
                    "row" -> cells = java.util.TreeMap()
                    "c" -> {
                        inCell = true
                        cellRef = parser.getAttributeValue(null, "r").orEmpty()
                        cellIsShared = parser.getAttributeValue(null, "t") == "s"
                        value.setLength(0)
                        inline.setLength(0)
                    }
                    "v" -> collectText(parser) { value.append(it) }
                    "t" -> collectText(parser) { inline.append(it) }
                }

                XmlPullParser.END_TAG -> when (localName(parser)) {
                    "c" -> {
                        if (inCell) {
                            val resolved = when {
                                cellIsShared -> value.toString().trim().toIntOrNull()
                                    ?.let { strings.getOrNull(it) }.orEmpty()
                                inline.isNotEmpty() -> inline.toString()
                                else -> value.toString()
                            }
                            val col = columnOf(cellRef) ?: cellOrder++
                            cells[col] = resolved.trim()
                            inCell = false
                        }
                    }
                    "row" -> {
                        flush()
                        if (rows.size >= maxRows) done = true
                    }
                }
            }
            if (!done) event = parser.next()
        }

        return rows.filter { it.any { c -> c.isNotBlank() } }
    }

    /** "AB12" → 27（A=0）。解析不出就返回 null，由调用方退化为出现顺序。 */
    private fun columnOf(ref: String): Int? {
        var n = 0
        var i = 0
        while (i < ref.length) {
            val c = ref[i]
            if (c !in 'A'..'Z') break
            n = n * 26 + (c - 'A' + 1)
            i++
        }
        return if (i > 0) n - 1 else null
    }

    private fun readSharedStrings(file: File): List<String> {
        val out = ArrayList<String>()
        ZipInputStream(FileInputStream(file).buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.name != SHARED_STRINGS) continue
                val parser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                parser.setInput(zis, null)
                var event = parser.eventType
                var inItem = false
                var skip = false              // 忽略 <rPh>（日文注音），它不是正文
                var acc = StringBuilder()
                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> when (localName(parser)) {
                            "si" -> { inItem = true; acc = StringBuilder() }
                            "rPh" -> skip = true
                            "t" -> if (inItem && !skip) collectText(parser) { acc.append(it) }
                        }
                        XmlPullParser.END_TAG -> when (localName(parser)) {
                            "rPh" -> skip = false
                            "si" -> {
                                out.add(acc.toString())
                                if (out.size >= MAX_SHARED_STRINGS) return out
                                inItem = false
                            }
                        }
                    }
                    event = parser.next()
                }
            }
        }
        return out
    }

    /* ── docx ──────────────────────────────────────────────────────────── */

    private fun readText(file: File, maxChars: Int): String {
        ZipInputStream(FileInputStream(file).buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: return ""
                if (entry.name != DOCUMENT_XML) continue
                val out = StringBuilder()
                val parser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                parser.setInput(zis, null)
                var event = parser.eventType
                var para = StringBuilder()

                fun endParagraph() {
                    // 连续空行压成一个：文档里的空白段落很多，全留着会刷屏
                    val line = para.toString().trim()
                    if (line.isNotEmpty() || out.endsWith("\n\n").not()) out.append(line).append('\n')
                    para.setLength(0)
                }

                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> when (localName(parser)) {
                            "t" -> collectText(parser) { para.append(it) }
                            "tab" -> para.append('\t')
                            "br", "cr" -> para.append('\n')
                        }
                        XmlPullParser.END_TAG -> when (localName(parser)) {
                            "p" -> endParagraph()
                        }
                    }
                    if (out.length >= maxChars) return out.append("\n… 内容过长，已截断").toString()
                    event = parser.next()
                }
                return out.toString().trimEnd()
            }
        }
    }

    /* ── 工具 ──────────────────────────────────────────────────────────── */

    /**
     * 去掉命名空间前缀。
     *
     * ⚠️ 踩过：关掉 FEATURE_PROCESS_NAMESPACES 后，[XmlPullParser.name] 返回的是
     * **带前缀的原始名** —— docx 里全是 `w:p`、`w:t`，而 xlsx 用默认命名空间、没有前缀。
     * 于是按 "p"/"t" 匹配在 xlsx 上正常、在 docx 上一个都不中，提取结果是空字符串，
     * 界面表现就是"纯白一片啥也没有"。统一按本地名比较。
     */
    private fun localName(parser: XmlPullParser): String = parser.name.substringAfter(':')

    /** 读一个标签里的文本；自闭合标签不会有 TEXT 事件，读不到就算了。 */
    private fun collectText(parser: XmlPullParser, sink: (String) -> Unit) {
        if (parser.next() == XmlPullParser.TEXT) sink(parser.text)
    }

    private val ZipEntry.isWorksheet: Boolean
        get() = name.startsWith("xl/worksheets/") && name.endsWith(".xml")

    private const val SHARED_STRINGS = "xl/sharedStrings.xml"
    private const val DOCUMENT_XML = "word/document.xml"
    private const val MAX_SHARED_STRINGS = 200_000
}
