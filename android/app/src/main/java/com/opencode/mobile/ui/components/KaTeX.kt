package com.opencode.mobile.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

/*
 * 用 KaTeX 离线渲染 LaTeX 公式。资源在 assets/katex/（js + css + 20 个 woff2，约 548KB）。
 *
 * ⚠️ 两个必须做对的地方，都踩过：
 *
 * 1. **必须用 loadUrl 从 assets 打开 render.html，不能用 loadDataWithBaseURL。**
 *    后者的页面来源是 data:，WebView 视为不可信来源、**拦掉 file:// 子资源** ——
 *    JS/CSS 能过，**字体过不去**，于是 KaTeX 拿系统字体凑合排版：
 *    大公式符号错位被压扁、小公式挤成一团。这是"公式渲染不对"的真正根因。
 *
 * 2. **页面只加载一次，之后用 evaluateJavascript 重复渲染。**
 *    早先写在 AndroidView 的 update 里，每次重组都重新 loadDataWithBaseURL ——
 *    一条消息几十个公式就是几十次页面重载，直接卡死滚动。
 *
 * 尺寸：WebView 自己算不出内容高度，页内量好后经 JavascriptInterface 回传，
 * 再由 Compose 设尺寸；否则要么裁切、要么留一大片空白。
 */

private const val PAGE = "file:///android_asset/katex/render.html"
private const val BRIDGE = "AndroidKaTeX"

/** 尺寸回调 + 页面就绪回调。 */
private class TexBridge(
    private val tag: String,
    private val onSize: (Int, Int) -> Unit,
    private val onReady: () -> Unit,
) {
    /**
     * 公式尺寸问题排查了十二轮，最后靠这条日志定位。
     * `info` 是页面附带的诊断串（容器尺寸 / 墨迹尺寸 / 伸得最深的元素）——
     * 曾经单独加过一个 note 通道，但它被页面里的异常静默吞掉了，
     * 所以诊断信息改成搭在**必定会调用**的 report 上。
     */
    @JavascriptInterface
    fun report(w: Int, h: Int, info: String) {
        Log.d("KaTeX", "[$tag] report w=$w h=$h  $info")
        onSize(w, h)
    }

    @JavascriptInterface
    fun ready() {
        Log.d("KaTeX", "[$tag] page ready")
        onReady()
    }

    /** 页面主动上报的杂项诊断（如字体加载情况）。 */
    @JavascriptInterface
    fun note(msg: String) {
        Log.d("KaTeX", "[$tag] note: $msg")
    }
}

/**
 * @param inline 行内公式：按内容自适应宽高（嵌在 FlowRow 里跟文字混排）；
 *               否则占满宽度、内容居中（块级 `$$…$$`）。
 * @param fontSizeSp 正文字号。必须传下去 —— 不给的话页面用默认 16px，
 *                   和正文（14sp）对不上，行内公式会显得过小。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun KaTeXFormula(
    tex: String,
    displayMode: Boolean,
    textColor: Color,
    modifier: Modifier = Modifier,
    inline: Boolean = false,
    fontSizeSp: Float = 14f,
    /**
     * 行内公式所处**整行**的可用宽度。
     *
     * 必须由调用方（MdInline）传下来，不能在这里用 BoxWithConstraints 量 ——
     * 在 FlowRow 的子项里量到的是"**剩下的**宽度"：同一行前面已经有文字时，
     * 剩下的可能只有几十 dp，公式就被压到那个宽度里、直接裁掉。
     * 拿到整行宽度才能判断"是真的放不下"还是"只是这行放不下"。
     */
    inlineAvailDp: Dp = Dp.Unspecified,
) {
    var widthPx by remember(tex, displayMode) { mutableIntStateOf(0) }
    var heightPx by remember(tex, displayMode) { mutableIntStateOf(0) }
    var pageReady by remember { mutableStateOf(false) }

    // 原生侧才知道"可用宽度"。页面里**绝不能**拿 documentElement.clientWidth 当可用宽度 ——
    // 首次渲染时 WebView 还是兜底宽度（几十 dp），会算出 0.2 这种缩放比，把公式压成一小坨。
    val availDp = if (inline && inlineAvailDp != Dp.Unspecified) inlineAvailDp else Dp.Unspecified
    // 传给页面的是 **CSS px**，而 1 CSS px ≈ 1 dp —— 所以直接取 dp 数值，
    // 不能用 roundToPx()（那是物理 px，会被放大 density 倍）。
    var availPx by remember(displayMode, availDp) {
        mutableIntStateOf(if (availDp != Dp.Unspecified) availDp.value.toInt() else -1)
    }

    // ⚠️ 单位：页面报回来的是 **CSS px**，而 WebView 里 1 CSS px ≈ 1 dp。
    // 所以这些值**直接就是 dp**，绝不能再 toPx()/toDp() 换算 ——
    // 之前写成 `with(density) { (rawH + slackPx).toDp() }`，
    // 在 density=3 的机器上等于又除了一次 3：102 变成 34dp，
    // 公式被塞进只有三分之一高的框里，**底部整片被裁掉**。
    // 症状正是"分式的分母不见了""行内公式只剩一小截"。
    val slackDp = 6f
    val minHDp = if (inline) 26f else 42f
    val fallbackWDp = 44f
    val fallbackHDp = 26f

    val rawW = if (widthPx > 0) widthPx.toFloat() else fallbackWDp
    val rawH = maxOf(if (heightPx > 0) heightPx.toFloat() else fallbackHDp, minHDp)

    val wDp = (rawW + slackDp).dp
    val hDp = (rawH + slackDp).dp

    val colorHex = remember(textColor) { "#%06X".format(0xFFFFFF and textColor.toArgb()) }

    // 供 factory/update 共用的「最新渲染参数」，避免把整个 tex 塞进 WebView 的 tag
    val args = remember(tex, displayMode, colorHex, fontSizeSp, inline, availPx) {
        renderArgs(tex, displayMode, colorHex, fontSizeSp, !inline, availPx)
    }

    val sizeModifier = if (inline) {
        // ⚠️ 用 requiredWidth 而不是 width。
        // width 会被父级的 max 约束压住 —— 在 FlowRow 里那个约束是"**剩下的**宽度"，
        // 于是同一行前面有文字时公式会被压窄、内容被裁。
        // requiredWidth 无视传入约束：公式先按自然宽度排版，放不下就整块换到下一行。
        // 只有真的超过整行宽度才截到整行宽，那种情况由页面按 availPx 等比缩小。
        val maxW = if (availDp != Dp.Unspecified) availDp else Dp.Infinity
        Modifier
            .requiredWidth(wDp.coerceAtMost(maxW))
            .height(hDp)
    } else {
        Modifier.fillMaxWidth().height(hDp)
    }

    // 错峰挂载：领一个序号，到点了再创建 WebView。
    // 占位用的是**同一个 sizeModifier**（尺寸未回来时是兜底值），所以不额外跳版。
    val mountOrder = remember(tex, displayMode) { KaTeXDefer.next() }
    var mounted by remember(tex, displayMode) { mutableStateOf(false) }
    LaunchedEffect(mountOrder) {
        val wait = (mountOrder % DEFER_WINDOW) * DEFER_STEP_MS
        if (wait > 0L) delay(wait)
        mounted = true
    }
    if (!mounted) {
        Box(modifier.then(sizeModifier))
        return
    }

    // BoxWithConstraints 才能拿到父级给的**可用宽度**，把它传给页面做超宽自适应。
    // 写 state 会多触发一次重组，但值收敛后就停 —— 换来的是正确的缩放依据。
    BoxWithConstraints(modifier = modifier) {
        if (!inline) {
            // 同样：CSS px ≈ dp，取 dp 数值而不是物理 px
            val fromConstraints = maxWidth.value.toInt()
            if (fromConstraints > 0 && availPx != fromConstraints) availPx = fromConstraints
        }

        Box(
            // 不套 clip()：WebView 本身就会把内容裁到自己的边界内，
            // 再加一层圆角裁剪只会在尺寸略有偏差时**雪上加霜**（把公式边缘切掉）。
            modifier = Modifier.then(sizeModifier),
        ) {
        AndroidView(
            modifier = Modifier.matchParentSize(),
            // ⚠️ 必须显式销毁。AndroidView 被移出组合时只会把 View detach，
            // **WebView 不会自己释放** —— 聊天列表来回滚动会不断创建新的 WebView，
            // 每一个都带着渲染器资源，攒下来就是内存上涨 + 发热。
            onRelease = { web ->
                runCatching {
                    web.stopLoading()
                    web.destroy()
                }
            },
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setBackgroundColor(AndroidColor.TRANSPARENT)
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = WebView.OVER_SCROLL_NEVER
                    isFocusable = false
                    settings.javaScriptEnabled = true
                    settings.loadsImagesAutomatically = true
                    settings.allowFileAccess = true
                    settings.allowFileAccessFromFileURLs = true
                    settings.allowContentAccess = false
                    settings.domStorageEnabled = false
                    settings.builtInZoomControls = false
                    settings.displayZoomControls = false
                    settings.textZoom = 100
                    // 公式区域不参与手势竞争，纵向滑动交给外层列表
                    setOnTouchListener { _, _ -> true }

                    // 记下「已经渲染过的参数」：变了才重渲染，没变绝不动页面
                    tag = args
                    addJavascriptInterface(
                        TexBridge(
                            tag = (if (inline) "in" else "disp") + ":" + tex.take(28).replace('\n', ' '),
                            onSize = { w, h ->
                                post {
                                    if (w > 0) widthPx = w
                                    if (h > 0) heightPx = h
                                }
                            },
                            onReady = {
                                post {
                                    pageReady = true
                                    evaluateJavascript(script(args), null)
                                }
                            },
                        ),
                        BRIDGE,
                    )
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            // 页面里的 ready() 已负责首次渲染；这里只兜底（ready 可能在 JS 桥挂上之前就调过了）
                            if (!pageReady) {
                                pageReady = true
                                evaluateJavascript(script(args), null)
                            }
                        }
                    }
                    loadUrl(PAGE)
                }
            },
            update = { web ->
                // 只有渲染参数真的变了才重渲染。
                // 原来无条件 loadDataWithBaseURL —— 每次重组都重载页面，是滚动卡顿的主因。
                if (web.tag !== args) {
                    web.tag = args
                    if (pageReady) web.evaluateJavascript(script(args), null)
                }
            },
        )
        }
    }
}

/**
 * WebView 预热。
 *
 * 为什么需要它：一条含公式的消息里**有多少个公式就有多少个 WebView**（Md.kt 里每个
 * 公式 span 一个 [KaTeXFormula] → 一个 AndroidView → 一个 WebView，实测那条拉普拉斯
 * 变换的回复是 39 个）。而进程里**第一次**创建 WebView 要初始化渲染引擎 + 解析
 * assets 里的 katex.min.js/css/字体，这笔一次性开销全额压在那一帧上 ——
 * 这就是「第一次打开卡、之后有缓存就还好」的来源：之后引擎已经起来了。
 *
 * 这里做的事很小：App 起来后先空跑一次 render.html，把这笔开销付掉再释放。
 * 它**不能**替代「减少 WebView 数量」（那是根治），只是把首屏最贵的一次性成本挪走。
 */
object KaTeXWarmUp {

    fun warm(context: Context) {
        runCatching {
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                settings.allowFileAccessFromFileURLs = true
                // 不显示、不吃手势，纯粹为了把引擎与资源拉起来
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                isFocusable = false
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        // 使命到此为止：引擎已初始化、assets 已进缓存。
                        // 必须立刻释放 —— WebView 不能脱离 Activity 被长期持有。
                        runCatching { view.destroy() }
                    }
                }
                loadUrl(PAGE)
            }
        }
    }
}

/**
 * 公式的挂载序号。
 *
 * 一条消息里有多少个公式就有多少个 WebView，它们会在**同一帧**被创建 ——
 * 实测那条拉普拉斯变换的回复是 39 个，全部堆在一帧上就是"第一次打开卡死"的来源。
 * 预热（[KaTeXWarmUp]）只搬走了引擎初始化那一笔，搬不走这 39 次创建。
 *
 * 所以这里给每个公式发一个递增序号，[KaTeXFormula] 按序号**错峰挂载**：
 * 先按最终尺寸占位，到点了再真正创建 WebView，把一次性开销摊到后续若干帧。
 */
private object KaTeXDefer {
    private var seq = 0

    @Synchronized
    fun next(): Int = seq++
}

/** 错峰窗口与步长：40 × 12ms ≈ 最多半秒内全部挂完，肉眼几乎无感。 */
private const val DEFER_WINDOW = 40
private const val DEFER_STEP_MS = 12L

/** 一次渲染的全部参数。用 data class 是为了 update 时能按值比较。 */
private data class RenderArgs(
    val tex: String,
    val displayMode: Boolean,
    val colorHex: String,
    val fontSizeSp: Float,
    val centered: Boolean,
    /** 原生侧知道的可用宽度（px）。<=0 表示未知 —— 页面就不会做缩小处理。 */
    val availPx: Int,
)

private fun renderArgs(
    tex: String,
    displayMode: Boolean,
    colorHex: String,
    fontSizeSp: Float,
    centered: Boolean,
    availPx: Int,
) = RenderArgs(tex, displayMode, colorHex, fontSizeSp, centered, availPx)

private fun script(a: RenderArgs): String =
    "renderTex(${JSONObject.quote(a.tex)}, ${a.displayMode}, " +
        "${JSONObject.quote(a.colorHex)}, ${a.fontSizeSp}, ${a.centered}, ${a.availPx});"
