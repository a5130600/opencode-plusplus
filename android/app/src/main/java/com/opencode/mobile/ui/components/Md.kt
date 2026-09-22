package com.opencode.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencode.mobile.ui.theme.OcColors

/*
 * 聊天消息的 Markdown 渲染（Compose 原生，零依赖）。
 *
 * 为什么不复用 ui/artifacts/Markdown.kt：那份产出 HTML，是喂给 WebView 的，
 * 用在聊天里等于每条消息塞一个 WebView —— 滚动会卡、没法选中、主题也不跟。
 * 这里直接产出 AnnotatedString 和 Compose 布局。
 *
 * 支持的：标题 / 有序无序列表（含缩进嵌套）/ 引用 / 围栏与行内代码 /
 *        粗体斜体删除线 / 链接 / 表格 / 分割线 / 公式（见文末说明）。
 */

private sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class ListBlock(val ordered: Boolean, val items: List<ListItem>) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
    data class Quote(val lines: List<String>) : MdBlock
    data class Table(val head: List<String>, val rows: List<List<String>>) : MdBlock
    data class Formula(val tex: String, val display: Boolean) : MdBlock
    data object Rule : MdBlock
}

private data class ListItem(val depth: Int, val text: String)

/* ── 块级解析 ─────────────────────────────────────────────────────────── */

private object MdParser {

    private val fence = Regex("^\\s*```(.*)$")
    private val heading = Regex("^(#{1,6})\\s+(.*)$")
    private val bullet = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val ordered = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
    private val quote = Regex("^\\s*>\\s?(.*)$")
    private val rule = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val tableSep = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    fun parse(src: String): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        val lines = src.replace("\r\n", "\n").split("\n")
        var i = 0

        while (i < lines.size) {
            val line = lines[i]

            // 围栏代码块
            val f = fence.find(line)
            if (f != null) {
                val lang = f.groupValues[1].trim()
                val body = StringBuilder()
                i++
                while (i < lines.size && !fence.containsMatchIn(lines[i])) {
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(lines[i])
                    i++
                }
                i++                       // 吃掉结束围栏（可能越界，无妨）
                out += MdBlock.Code(lang, body.toString())
                continue
            }

            if (line.isBlank()) { i++; continue }

            if (rule.matches(line)) { out += MdBlock.Rule; i++; continue }

            val h = heading.find(line)
            if (h != null) {
                out += MdBlock.Heading(h.groupValues[1].length, h.groupValues[2].trim())
                i++
                continue
            }

            // 表格：当前行是 | a | b |，下一行是分隔行
            if (line.contains('|') && i + 1 < lines.size && tableSep.matches(lines[i + 1])) {
                val head = splitRow(line)
                val rows = mutableListOf<List<String>>()
                i += 2
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                    rows += splitRow(lines[i])
                    i++
                }
                out += MdBlock.Table(head, rows)
                continue
            }

            // 独立公式块 $$...$$
            if (line.trim().startsWith("$$")) {
                val body = StringBuilder()
                var rest = line.trim().removePrefix("$$")
                while (true) {
                    if (rest.contains("$$")) {
                        body.append(rest.substringBefore("$$"))
                        break
                    }
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(rest)
                    i++
                    if (i >= lines.size) break
                    rest = lines[i]
                }
                i++
                out += MdBlock.Formula(body.toString().trim(), display = true)
                continue
            }

            if (quote.containsMatchIn(line)) {
                val qs = mutableListOf<String>()
                while (i < lines.size) {
                    val m = quote.find(lines[i]) ?: break
                    qs += m.groupValues[1]
                    i++
                }
                out += MdBlock.Quote(qs)
                continue
            }

            // 列表：把连续同类（无序或有序）的行收成一块
            val b = bullet.find(line)
            val o = ordered.find(line)
            if (b != null || o != null) {
                val isOrdered = o != null && (b == null || o.range.first <= b.range.first)
                val items = mutableListOf<ListItem>()
                while (i < lines.size) {
                    val lb = bullet.find(lines[i])
                    val lo = ordered.find(lines[i])
                    if (isOrdered && lo != null) {
                        items += ListItem(depthOf(lo.groupValues[1]), lo.groupValues[3].trim())
                    } else if (!isOrdered && lb != null) {
                        items += ListItem(depthOf(lb.groupValues[1]), lb.groupValues[2].trim())
                    } else {
                        break
                    }
                    i++
                }
                out += MdBlock.ListBlock(isOrdered, items)
                continue
            }

            // 普通段落：连续非空行合并，段内换行按空格处理（Markdown 本来就这样）
            val para = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                val cur = lines[i]
                if (fence.containsMatchIn(cur) || heading.containsMatchIn(cur) ||
                    bullet.containsMatchIn(cur) || ordered.containsMatchIn(cur) ||
                    quote.containsMatchIn(cur) || rule.matches(cur) ||
                    cur.trim().startsWith("$$")
                ) break
                if (para.isNotEmpty()) para.append(' ')
                para.append(cur.trim())
                i++
            }
            if (para.isNotEmpty()) out += MdBlock.Paragraph(para.toString())
        }
        return out
    }

    private fun depthOf(indent: String): Int = indent.count { it == ' ' } / 2 + indent.count { it == '\t' }

    /** `| a | b |` → [a, b]，去掉首尾空列。 */
    private fun splitRow(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|")) s = s.dropLast(1)
        // 拆分时不理会被反引号包住的竖线（表格里写代码很常见）
        val cells = mutableListOf<String>()
        val cur = StringBuilder()
        var inCode = false
        s.forEach { ch ->
            when {
                ch == '`' -> { inCode = !inCode; cur.append(ch) }
                ch == '|' && !inCode -> { cells += cur.toString().trim(); cur.clear() }
                else -> cur.append(ch)
            }
        }
        cells += cur.toString().trim()
        return cells
    }
}

/* ── 行内样式 ─────────────────────────────────────────────────────────── */

private fun mdInline(
    src: String,
    base: SpanStyle,
    codeBg: Color,
    linkColor: Color,
    mutedColor: Color,
): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < src.length) {
        val rest = src.substring(i)

        // 转义
        if (src[i] == '\\' && i + 1 < src.length && src[i + 1] in "\\`*_~[]()#+-.!$|") {
            append(src[i + 1]); i += 2; continue
        }

        // 行内代码优先 —— 里面的 * _ 不该再被当成强调
        if (src[i] == '`') {
            val end = src.indexOf('`', i + 1)
            if (end > i) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) {
                    append(src.substring(i + 1, end))
                }
                i = end + 1; continue
            }
        }

        // 删除线
        if (rest.startsWith("~~")) {
            val end = rest.indexOf("~~", 2)
            if (end > 0) {
                withStyle(SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough, color = mutedColor)) {
                    append(rest.substring(2, end))
                }
                i += end + 2; continue
            }
        }

        // 粗体（** 或 __）
        val boldMark = when {
            rest.startsWith("**") -> "**"
            rest.startsWith("__") -> "__"
            else -> null
        }
        if (boldMark != null) {
            val end = rest.indexOf(boldMark, 2)
            if (end > 0) {
                withStyle(base.copy(fontWeight = FontWeight.SemiBold)) {
                    appendInline(rest.substring(2, end), base, codeBg, linkColor, mutedColor)
                }
                i += end + 2; continue
            }
        }

        // 斜体（* 或 _）
        if (src[i] == '*' || src[i] == '_') {
            val mark = src[i]
            val end = rest.indexOf(mark, 1)
            if (end > 0) {
                withStyle(base.copy(fontStyle = FontStyle.Italic)) {
                    appendInline(rest.substring(1, end), base, codeBg, linkColor, mutedColor)
                }
                i += end + 1; continue
            }
        }

        // 链接 [文字](地址)
        if (src[i] == '[') {
            val close = rest.indexOf(']')
            if (close > 0 && close + 1 < rest.length && rest[close + 1] == '(') {
                val paren = rest.indexOf(')', close + 2)
                if (paren > 0) {
                    withStyle(SpanStyle(color = linkColor)) {
                        append(rest.substring(1, close))
                    }
                    i += paren + 1; continue
                }
            }
        }

        append(src[i]); i++
    }
}

/** 粗体里还要能嵌行内代码/斜体，所以单独走一层。 */
private fun androidx.compose.ui.text.AnnotatedString.Builder.appendInline(
    src: String,
    base: SpanStyle,
    codeBg: Color,
    linkColor: Color,
    mutedColor: Color,
) {
    append(mdInline(src, base, codeBg, linkColor, mutedColor))
}

/* ── 渲染 ─────────────────────────────────────────────────────────────── */

/** 一套颜色。深色气泡（用户消息）与浅色背景用不同取值。 */
data class MdPalette(
    val text: Color,
    val muted: Color,
    val codeBg: Color,
    val codeText: Color,
    val surface: Color,
    val line: Color,
    val link: Color,
) {
    companion object {
        fun onLightBg(): MdPalette = MdPalette(
            text = OcColors.Ink,
            muted = OcColors.Ink3,
            codeBg = OcColors.Surface2,
            codeText = OcColors.Ink,
            surface = OcColors.Surface,
            line = OcColors.Line2,
            link = OcColors.Ink2,
        )

        /** 用户气泡是深色底，Markdown 的颜色得反过来，否则看不见。 */
        @Composable
        fun onDarkBubble(): MdPalette = MdPalette(
            text = OcColors.Surface,
            muted = OcColors.Surface.copy(alpha = 0.7f),
            codeBg = OcColors.Surface.copy(alpha = 0.16f),
            codeText = OcColors.Surface,
            surface = OcColors.Surface.copy(alpha = 0.12f),
            line = OcColors.Surface.copy(alpha = 0.25f),
            link = OcColors.Surface,
        )
    }
}

@Composable
fun MdText(
    markdown: String,
    palette: MdPalette,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val bodyStyle = MaterialTheme.typography.bodyMedium
    val baseSize = if (compact) bodyStyle.fontSize * 0.94f else bodyStyle.fontSize

    // 解析结果要记住：聊天列表里每条消息每次重组都重解析一遍会明显拖慢滚动
    val blocks = remember(markdown) { MdParser.parse(markdown) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> MdInline(
                    text = block.text,
                    palette = palette,
                    style = bodyStyle,
                    size = baseSize.value,
                )

                is MdBlock.Heading -> MdInline(
                    text = block.text,
                    palette = palette,
                    style = bodyStyle.copy(fontWeight = FontWeight.SemiBold),
                    size = (baseSize.value + (4 - block.level).coerceIn(0, 4)),
                )

                is MdBlock.ListBlock -> MdListBlock(block, palette, bodyStyle, baseSize.value)

                is MdBlock.Code -> CodeBlock(block, palette, baseSize.value)

                is MdBlock.Quote -> Row(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .width(2.dp)
                            .height(if (block.lines.size > 1) (block.lines.size * 18).dp else 16.dp)
                            .background(palette.line),
                    )
                    Spacer(Modifier.width(9.dp))
                    MdInline(
                        text = block.lines.joinToString("\n"),
                        palette = palette.copy(text = palette.muted),
                        style = bodyStyle,
                        size = baseSize.value,
                        modifier = Modifier.weight(1f),
                    )
                }

                is MdBlock.Table -> MdTable(block, palette, baseSize.value)

                is MdBlock.Formula -> KaTeXFormula(
                    tex = block.tex,
                    displayMode = block.display,
                    textColor = palette.text,
                    fontSizeSp = baseSize.value,
                )

                MdBlock.Rule -> HorizontalDivider(color = palette.line)
            }
        }
    }
}

/* ── 行内内容的统一入口 ───────────────────────────────────────────────── */

/*
 * 行内公式必须**所有块类型都走这里**，不能只给段落开小灶。
 *
 * 上一版只接了 Paragraph，结果模型把公式写进项目符号列表时（很常见）整片漏掉，
 * 界面上直接显示成 `$f(t)$`、`$\int ...$` 的原始字符。
 * 列表项 / 标题 / 引用 / 表格单元格 全都得走同一条路。
 */

/** `$…$` 与 `\(…\)` 两种写法都认。 */
private val MATH_DOLLAR = Regex("(?<!\\\\)\\$([^$\\n]+?)(?<!\\\\)\\$")
private val MATH_PAREN = Regex("\\\\\\((.+?)\\\\\\)")

private data class MathSpan(val start: Int, val end: Int, val tex: String)

/** 找出所有行内公式区间，重叠的丢掉（先出现的优先）。 */
private fun findMathSpans(text: String): List<MathSpan> {
    val spans = mutableListOf<MathSpan>()
    MATH_DOLLAR.findAll(text).forEach {
        spans += MathSpan(it.range.first, it.range.last, it.groupValues[1])
    }
    MATH_PAREN.findAll(text).forEach {
        val s = MathSpan(it.range.first, it.range.last, it.groupValues[1])
        if (spans.none { existing -> s.start <= existing.end && existing.start <= s.end }) spans += s
    }
    return spans.sortedBy { it.start }
}

private fun hasInlineMath(text: String): Boolean =
    MATH_DOLLAR.containsMatchIn(text) || MATH_PAREN.containsMatchIn(text)

/**
 * 渲染一段行内内容：有公式就拆成「文字段 + 公式段」用 FlowRow 混排
 * （AnnotatedString 装不了 WebView）；没公式就走原生 Text，质量最好。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MdInline(
    text: String,
    palette: MdPalette,
    style: androidx.compose.ui.text.TextStyle,
    size: Float,
    modifier: Modifier = Modifier,
) {
    val spans = remember(text) { findMathSpans(text) }

    if (spans.isEmpty()) {
        // 缓存 AnnotatedString：流式输出时列表每帧都在重组，
        // 每次都重新扫一遍行内语法（粗体/代码/链接）纯属白烧 CPU —— 会明显发热。
        val annotated = remember(text, palette.text, palette.codeBg, palette.link, palette.muted) {
            mdInline(text, SpanStyle(color = palette.text), palette.codeBg, palette.link, palette.muted)
        }
        Text(
            text = annotated,
            style = style.copy(fontSize = size.sp),
            color = palette.text,
            modifier = modifier,
        )
        return
    }

    val pieces = remember(text, spans) {
        val out = mutableListOf<Pair<String, Boolean>>()     // second = 是否公式
        var last = 0
        spans.forEach { span ->
            if (span.start > last) out += text.substring(last, span.start) to false
            out += span.tex to true
            last = span.end + 1
        }
        if (last < text.length) out += text.substring(last) to false
        out
    }

    // 文字段的行内样式同样缓存，理由同上
    val rendered = remember(pieces, palette.text, palette.codeBg, palette.link, palette.muted) {
        pieces.map { (chunk, isMath) ->
            if (isMath) null
            else mdInline(chunk, SpanStyle(color = palette.text), palette.codeBg, palette.link, palette.muted)
        }
    }

    // 量到**整行**可用宽度，传给公式。
    // 在 FlowRow 子项里量到的是"剩下的宽度" —— 同一行前面有文字时就只剩几十 dp，
    // 公式会被压窄裁掉。整行宽度才是有意义的判断依据。
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val rowAvail = maxWidth

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            pieces.forEachIndexed { index, (chunk, isMath) ->
                if (isMath) {
                    KaTeXFormula(
                        tex = chunk,
                        displayMode = false,
                        textColor = palette.text,
                        inline = true,
                        // 行内公式必须跟正文字号一致，否则要么过大要么过小
                        fontSizeSp = size,
                        inlineAvailDp = rowAvail,
                        // 垂直居中：默认是顶部对齐，公式会被顶到行首、和文字对不上
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .padding(horizontal = 1.dp),
                    )
                } else {
                    Text(
                        text = rendered[index] ?: AnnotatedString(chunk),
                        style = style.copy(fontSize = size.sp),
                        color = palette.text,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
            }
        }
    }
}

@Composable
private fun MdListBlock(
    block: MdBlock.ListBlock,
    palette: MdPalette,
    bodyStyle: androidx.compose.ui.text.TextStyle,
    size: Float,
) {
    val counters = mutableMapOf<Int, Int>()
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        block.items.forEach { item ->
            val indent = item.depth.coerceAtMost(3)
            val marker = if (block.ordered) {
                val n = (counters[indent] ?: 0) + 1
                counters[indent] = n
                counters.keys.filter { it > indent }.forEach { counters.remove(it) }
                "$n."
            } else {
                when (indent) { 0 -> "•"; 1 -> "◦"; else -> "▪" }
            }
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.width((indent * 14).dp))
                Text(
                    text = marker,
                    style = bodyStyle.copy(fontSize = size.sp),
                    color = palette.muted,
                    modifier = Modifier.width(if (block.ordered) 24.dp else 14.dp),
                )
                MdInline(
                    text = item.text,
                    palette = palette,
                    style = bodyStyle,
                    size = size,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun CodeBlock(block: MdBlock.Code, palette: MdPalette, size: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(palette.codeBg)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = block.code,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = (size - 1).sp,
                lineHeight = (size + 4).sp,
            ),
            color = palette.codeText,
        )
    }
}

@Composable
private fun MdTable(block: MdBlock.Table, palette: MdPalette, size: Float) {
    val cols = maxOf(block.head.size, block.rows.maxOfOrNull { it.size } ?: 0)
    if (cols == 0) return

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .border(1.dp, palette.line, RoundedCornerShape(9.dp))
            .horizontalScroll(rememberScrollState()),
    ) {
        Column(Modifier.width((cols * 132).dp)) {
            Row(Modifier.background(palette.surface)) {
                block.head.forEachIndexed { index, cell ->
                    if (index > 0) VerticalRule(palette)
                    MdInline(
                        text = cell,
                        palette = palette,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        size = size - 1,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                    )
                }
                repeat(cols - block.head.size) {
                    VerticalRule(palette)
                    Spacer(Modifier.weight(1f))
                }
            }
            block.rows.forEach { row ->
                HorizontalDivider(color = palette.line)
                Row {
                    row.forEachIndexed { index, cell ->
                        if (index > 0) VerticalRule(palette)
                        MdInline(
                            text = cell,
                            palette = palette,
                            style = MaterialTheme.typography.bodySmall,
                            size = size - 1,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp, vertical = 7.dp),
                        )
                    }
                    repeat(cols - row.size) {
                        VerticalRule(palette)
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun VerticalRule(palette: MdPalette) {
    Box(Modifier.width(1.dp).height(34.dp).background(palette.line))
}

/** 有没有公式 —— 调试/统计用。 */
fun hasFormula(markdown: String): Boolean =
    markdown.contains("$$") || hasInlineMath(markdown)
