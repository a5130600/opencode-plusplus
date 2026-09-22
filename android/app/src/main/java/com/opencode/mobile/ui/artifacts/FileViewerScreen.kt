package com.opencode.mobile.ui.artifacts

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.webkit.WebView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.opencode.mobile.core.AppResult
import com.opencode.mobile.core.decodeTextSmart
import com.opencode.mobile.data.repository.ArtifactRepository
import com.opencode.mobile.data.storage.BlobId
import com.opencode.mobile.data.storage.FileKind
import com.opencode.mobile.data.storage.ShareStaging
import com.opencode.mobile.ui.components.ChipTone
import com.opencode.mobile.ui.components.EmptyState
import com.opencode.mobile.ui.components.bottomBarInsets
import com.opencode.mobile.ui.components.OcIconButton
import com.opencode.mobile.ui.components.StatusChip
import com.opencode.mobile.ui.components.formatBytes
import com.opencode.mobile.ui.components.pressScale
import com.opencode.mobile.ui.components.rememberPress
import com.opencode.mobile.ui.nav.FileViewTarget
import com.opencode.mobile.ui.theme.OcColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** 超过这个体积就不做端上文本渲染了 —— 一个 20MB 的日志会把内存直接打爆。 */
private const val MAX_TEXT_BYTES = 2L * 1024 * 1024

/** 端上明确不处理的旧二进制格式。见 [OfficePanel] 的说明。 */
private val LEGACY_OFFICE_EXT = setOf("doc", "xls", "ppt")

sealed interface ViewerState {
    data object Loading : ViewerState
    data class Failed(val message: String) : ViewerState

    data class Ready(
        val file: File,
        val kind: FileKind,
        val bytes: Long,
        /** 文本类文件的内容；非文本为 null。 */
        val text: String?,
        /** xlsx 提取出的行列；其余类型为 null。有了它就不用再把表格拼成字符串。 */
        val grid: List<List<String>>? = null,
    ) : ViewerState
}

@HiltViewModel
class FileViewerViewModel @Inject constructor(
    private val artifactRepository: ArtifactRepository,
    private val shareStaging: ShareStaging,
) : ViewModel() {

    private val _state = MutableStateFlow<ViewerState>(ViewerState.Loading)
    val state: StateFlow<ViewerState> = _state.asStateFlow()

    private val _offline = MutableStateFlow(false)
    val offline: StateFlow<Boolean> = _offline.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _shareUri = MutableStateFlow<Uri?>(null)
    val shareUri: StateFlow<Uri?> = _shareUri.asStateFlow()

    private var blobId: String? = null
    private var displayName: String = "file"
    private var loadedKey: String? = null
    private var lastTarget: FileViewTarget? = null

    fun load(target: FileViewTarget) {
        // 身份就是 deviceId + remotePath —— 服务端没有 size/mtime 可用，
        // 它们不再是身份的一部分（见 AppRoot.FileViewTarget 的注释）。
        val key = "${target.deviceId}|${target.remotePath}"
        if (loadedKey == key) return
        loadedKey = key
        displayName = target.displayName
        lastTarget = target

        viewModelScope.launch {
            _state.value = ViewerState.Loading
            // 没有进度回调可传了：接口返回的是一整个 JSON，不是字节流，
            // 所以下载是"要么全有要么全无"的原子操作，中间没有可汇报的进度。
            // 等待感由 UI 上一条不确定进度条表达（见 FileViewerScreen 的 Loading 分支）。
            val result = artifactRepository.openPath(
                deviceId = target.deviceId,
                sessionId = target.sessionId,
                remotePath = target.remotePath,
                displayName = target.displayName,
            )
            applyResult(target, result)
        }
    }

    /** 手动拉最新内容。Agent 刚改过这个文件时用得上。 */
    fun refresh() {
        val target = lastTarget ?: return
        viewModelScope.launch {
            _state.value = ViewerState.Loading
            val result = artifactRepository.refreshFile(
                deviceId = target.deviceId,
                sessionId = target.sessionId,
                remotePath = target.remotePath,
                displayName = target.displayName,
            )
            applyResult(target, result)
        }
    }

    /** 把一次取文件的结果铺到界面上。load / refresh 共用。 */
    private suspend fun applyResult(target: FileViewTarget, result: AppResult<File>) {
        when (result) {
            is AppResult.Err -> _state.value = ViewerState.Failed(result.message)
            is AppResult.Ok -> {
                val file = result.value
                val kind = FileKind.of(target.displayName)
                blobId = BlobId.of(target.deviceId, target.remotePath)
                _offline.value = artifactRepository.cachedRow(blobId!!)?.pin ?: false

                // ⚠️ 必须在 IO 上做：读全文、解 zip、跑 XML 解析都是主线程不能碰的重活 ——
                // 放在 Main 上就是"点开文件掉一两帧"的来源。
                val prepared = withContext(Dispatchers.IO) { prepare(file, kind, target.displayName) }
                _state.value = ViewerState.Ready(file, kind, file.length(), prepared.first, prepared.second)
            }
        }
    }

    /**
     * 决定这个文件最终以什么形态进界面：
     * - 表格（xlsx）→ 行列
     * - 文本 / 代码 / csv → 原文
     * - docx → 提取出来的纯文本
     * 都取不到就返回 null —— 界面会据此给出诚实的说明，不会假装能渲染。
     */
    private fun prepare(file: File, kind: FileKind, name: String): Pair<String?, List<List<String>>?> {
        if (kind == FileKind.OFFICE_SHEET) {
            OfficeExtract.grid(file)?.let { return null to it }
            return null to null
        }
        val text = if (isTextRenderable(name, kind, file.length())) {
            runCatching { decodeTextSmart(file.readBytes()) }.getOrNull()
        } else if (kind == FileKind.OFFICE_DOC) {
            OfficeExtract.text(file)
        } else {
            null
        }
        return text to null
    }

    fun toggleOffline() {
        val id = blobId ?: return
        viewModelScope.launch {
            when (val result = artifactRepository.setOffline(id, !_offline.value)) {
                is AppResult.Ok -> {
                    _offline.value = !_offline.value
                    _notice.value = if (_offline.value) "已离线保存，不会再被自动清理" else "已取消离线保存"
                }

                is AppResult.Err -> _notice.value = result.message
            }
        }
    }

    /**
     * 分享。必须先把文件以**真实文件名**落到 share/ 再生成 URI ——
     * 本地文件是以 blobId（sha256）命名的，直接分享出去对方会收到一串哈希。
     */
    fun share() {
        val id = blobId ?: return
        viewModelScope.launch {
            val local = artifactRepository.localFile(id)
            if (local == null) {
                _notice.value = "文件还没在本地，先等它下载完"
                return@launch
            }
            when (val result = shareStaging.stage(local, displayName)) {
                is AppResult.Ok -> _shareUri.value = result.value
                is AppResult.Err -> _notice.value = result.message
            }
        }
    }

    fun consumeShare() {
        _shareUri.value = null
    }

    fun consumeNotice() {
        _notice.value = null
    }
}

/**
 * 文件查看器。
 *
 * 一条硬边界：**只是"看"，绝不把内容回传给 AI。**
 * 想让 AI 读是另一个动作（底部那个按钮）：它会先让你挑一个对话，再把文件当附件挂上去，
 * 而且**不自动发送** —— 花 token 的事得用户自己按发送键。
 * 把这两件事混成一个按钮，是这类工具最容易让人困惑的地方。
 */
@Composable
fun FileViewerScreen(
    target: FileViewTarget,
    onBack: () -> Unit,
    onAskAi: (String) -> Unit,
    modifier: Modifier = Modifier,
    vm: FileViewerViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val offline by vm.offline.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val shareUri by vm.shareUri.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val dark = isSystemInDarkTheme()

    LaunchedEffect(target) { vm.load(target) }

    LaunchedEffect(shareUri) {
        shareUri?.let { uri ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = context.contentResolver.getType(uri) ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching {
                context.startActivity(Intent.createChooser(intent, "分享 ${target.displayName}"))
            }
            vm.consumeShare()
        }
    }

    Column(modifier.fillMaxSize().background(OcColors.Bg)) {
        ViewerTopBar(
            title = target.displayName,
            subtitle = state.subtitle(target),
            onBack = onBack,
            onRefresh = vm::refresh,
        )

        // 不确定进度条：接口不返回字节流，进度百分比是编不出来的，
        // 但"在动"这个信号仍然要传达 —— 大文件可能要等几秒。
        if (state is ViewerState.Loading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = OcColors.Run,
                trackColor = OcColors.Line2,
            )
        }

        NoticeLine(notice, vm::consumeNotice)

        Box(Modifier.weight(1f)) {
            when (val current = state) {
                is ViewerState.Loading -> EmptyState(
                    title = "正在取回文件",
                    hint = "第一次打开要从电脑下载，之后就是本地秒开",
                )

                is ViewerState.Failed -> EmptyState(
                    title = "打不开这个文件",
                    hint = current.message,
                )

                is ViewerState.Ready -> FileContent(
                    state = current,
                    dark = dark,
                    context = context,
                )
            }
        }

        ActionBar(
            offline = offline,
            onToggleOffline = vm::toggleOffline,
            onShare = vm::share,
            onAskAi = { onAskAi(target.displayName) },
        )
    }
}

@Composable
private fun ViewerTopBar(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(OcColors.Surface)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OcIconButton(Icons.AutoMirrored.Filled.ArrowBack, onClick = onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = OcColors.Ink3,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 手动刷新。缓存有 10 分钟的软新鲜期，但 Agent 刚改完文件时用户
        // 不该被迫等它过期 —— 这个按钮就是那条逃生通道。
        OcIconButton(Icons.Filled.Sync, onClick = onRefresh)
    }
}

private fun ViewerState.subtitle(target: FileViewTarget): String = when (this) {
    is ViewerState.Ready -> "${kindLabel(kind)} · ${formatBytes(bytes)}"
    is ViewerState.Failed -> target.remotePath
    ViewerState.Loading -> target.remotePath
}

/* ── 内容分发 ─────────────────────────────────────────────────────────── */

@Composable
private fun FileContent(state: ViewerState.Ready, dark: Boolean, context: Context) {
    val name = state.file.name
    val ext = FileKind.extensionOf(name)

    when {
        // Office 走优先，取不到内容再兜到说明面板 —— needsConversion 不再是死路一条：
        // docx / xlsx 是 zip+xml，纯端上就能提取（见 OfficeExtract）。
        state.kind == FileKind.OFFICE_SHEET && state.grid != null -> GridPane(state.grid)

        state.kind == FileKind.OFFICE_DOC && state.text != null ->
            TextPane(state.text, gutter = false)

        state.kind.needsConversion -> OfficePanel(state)

        ext == "svg" -> TextPane(state.text.orEmpty(), gutter = false)

        state.kind == FileKind.MARKDOWN -> WebPane(
            // loadKey 决定"要不要重新灌一遍 HTML"。用正文本身当 key：
            // 刷新后内容变了就重载，没变就不动，不会白闪一下。
            loadKey = state.text,
            load = { web ->
                web.loadDataWithBaseURL(
                    null,
                    Markdown.toHtml(state.text.orEmpty(), dark),
                    "text/html",
                    "utf-8",
                    null,
                )
            },
        )

        ext == "html" || ext == "htm" -> WebPane(
            // 本地文件被原子替换后 mtime 会变，所以路径 + mtime 能反映"换过没有"
            loadKey = "${state.file.absolutePath}:${state.file.lastModified()}",
            load = { web -> web.loadUrl("file://${state.file.absolutePath}") },
        )

        state.kind == FileKind.IMAGE -> Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = state.file,
                contentDescription = name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        state.kind == FileKind.PDF -> PdfPane(state.file)

        state.kind == FileKind.CSV || state.kind == FileKind.SHEET ->
            CsvGrid(state.text.orEmpty())

        state.text != null -> TextPane(state.text, gutter = true)

        else -> BinaryPanel(state)
    }
}

/**
 * 纯文本 / 代码。
 *
 * 用 LazyColumn 按行渲染而不是一个巨大的 Text：
 * 长文件下后者会在测量阶段就卡住主线程，前者只渲染可见行。
 */
@Composable
private fun TextPane(text: String, gutter: Boolean) {
    val lines = remember(text) { text.split('\n') }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(OcColors.Surface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        itemsIndexed(lines) { index, line ->
            Row {
                if (gutter) {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.bodySmall,
                        color = OcColors.Ink3,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .width(38.dp)
                            .padding(top = 1.dp),
                    )
                }
                SelectionContainer {
                    Text(
                        text = line.ifEmpty { " " },
                        style = MaterialTheme.typography.bodySmall,
                        color = OcColors.Ink,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

/**
 * WebView 宿主。
 *
 * 用 [loadKey] 挡一道：AndroidView 的 update 会在每次重组时调用，
 * 无条件 load 会让页面不断重载 —— 表现是"内容一直在闪"。
 * 只有 key 真正变了才重新载入。
 */
@Composable
private fun WebPane(loadKey: Any?, load: (WebView) -> Unit) {
    var loadedKey by remember { mutableStateOf<Any?>(null) }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                setBackgroundColor(0x00000000)
            }
        },
        update = { web ->
            if (loadedKey != loadKey) {
                loadedKey = loadKey
                load(web)
            }
        },
    )
}

/* ── PDF ──────────────────────────────────────────────────────────────── */

/**
 * PDF 渲染器持有者。
 *
 * 刻意**只在内存里保留 3 页位图**：一页 A4 在 1240px 宽下约 8.7MB，
 * 40 页全渲染出来就是 350MB —— 必崩。
 * 也刻意**不调用 recycle()**：位图可能还在被 Compose 引用，
 * 主动回收会直接触发 "Canvas: trying to use a recycled bitmap"。
 * 交给 GC 是这里唯一安全的选择。
 */
private class PdfHolder(private val file: File) {
    private var renderer: PdfRenderer? = null
    private val cache = object : LinkedHashMap<Int, Bitmap>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap>?) =
            size > 3
    }

    private fun rendererOrOpen(): PdfRenderer? {
        if (renderer == null) {
            renderer = runCatching {
                PdfRenderer(
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                )
            }.getOrNull()
        }
        return renderer
    }

    val pageCount: Int get() = rendererOrOpen()?.pageCount ?: 0

    fun page(index: Int, targetWidth: Int): Bitmap? {
        cache[index]?.let { return it }
        val pdf = rendererOrOpen() ?: return null
        if (index < 0 || index >= pdf.pageCount) return null
        return runCatching {
            pdf.openPage(index).use { page ->
                val ratio = page.height.toFloat() / page.width.toFloat().coerceAtLeast(1f)
                val width = targetWidth.coerceIn(320, 1600)
                val height = (width * ratio).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                cache[index] = bitmap
                bitmap
            }
        }.getOrNull()
    }

    fun close() {
        runCatching { renderer?.close() }
        renderer = null
        cache.clear()
    }
}

@Composable
private fun PdfPane(file: File) {
    val holder = remember(file.absolutePath) { PdfHolder(file) }
    DisposableEffect(file.absolutePath) {
        onDispose { holder.close() }
    }

    val count = remember(file.absolutePath) { holder.pageCount }
    if (count == 0) {
        BinaryPanel(
            ViewerState.Ready(file, FileKind.PDF, file.length(), null),
            message = "这份 PDF 解析不了，可能是加密或损坏的",
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(OcColors.Surface2),
        contentPadding = PaddingValues(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(count) { index ->
            PdfPage(holder = holder, index = index)
        }
        item {
            Text(
                text = "$count 页",
                style = MaterialTheme.typography.bodySmall,
                color = OcColors.Ink3,
                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            )
        }
    }
}

@Composable
private fun PdfPage(holder: PdfHolder, index: Int) {
    var bitmap by remember(index) { mutableStateOf<Bitmap?>(null) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(OcColors.Surface),
    ) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        LaunchedEffect(index, widthPx) {
            bitmap = withContext(Dispatchers.IO) { holder.page(index, widthPx) }
        }
        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(420.dp)
                    .background(OcColors.Surface2),
            )
        }
    }
}

/* ── CSV ──────────────────────────────────────────────────────────────── */

/** CSV 入口：先解析，再交给共用的 [GridPane]。 */
@Composable
private fun CsvGrid(text: String) {
    GridPane(remember(text) { parseCsv(text) })
}

/**
 * 表格渲染，CSV 与 xlsx 共用。
 *
 * 自己写解析而不是引库：需要处理的只有 RFC 4180 的引号转义这一种情况，
 * 而引一个 CSV 库会给这个只有两屏用量的功能带来额外的体积和 API 面。
 */
@Composable
private fun GridPane(rows: List<List<String>>) {
    if (rows.isEmpty()) {
        EmptyState(title = "空表格", hint = "文件里没有可显示的行")
        return
    }

    val header = rows.first()
    val body = rows.drop(1)
    val hScroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OcColors.Surface)
            .verticalScroll(rememberScrollState())
            .horizontalScroll(hScroll)
            .padding(vertical = 8.dp),
    ) {
        CsvRow(cells = header, header = true)
        body.forEach { cells -> CsvRow(cells = cells, header = false) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CsvRow(cells: List<String>, header: Boolean) {
    Row(
        modifier = Modifier
            .background(if (header) OcColors.Surface2 else OcColors.Surface)
            .padding(vertical = 7.dp),
    ) {
        cells.forEach { cell ->
            Text(
                text = cell,
                style = MaterialTheme.typography.bodySmall,
                color = if (header) OcColors.Ink2 else OcColors.Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .width(148.dp)
                    .padding(horizontal = 10.dp),
            )
        }
    }
}

private fun parseCsv(text: String, maxRows: Int = 400, maxCols: Int = 30): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val cell = StringBuilder()
    var inQuotes = false
    var i = 0

    while (i < text.length && rows.size < maxRows) {
        val ch = text[i]
        when {
            inQuotes && ch == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                cell.append('"'); i += 2
            }

            ch == '"' -> {
                inQuotes = !inQuotes; i++
            }

            !inQuotes && ch == ',' -> {
                row.add(cell.toString()); cell.clear(); i++
            }

            !inQuotes && (ch == '\n' || ch == '\r') -> {
                row.add(cell.toString()); cell.clear()
                rows.add(row.take(maxCols))
                row = mutableListOf()
                // 吃掉 CRLF 里的第二个字符
                i += if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') 2 else 1
            }

            else -> {
                cell.append(ch); i++
            }
        }
    }
    if (cell.isNotEmpty() || row.isNotEmpty()) {
        row.add(cell.toString())
        rows.add(row.take(maxCols))
    }
    return rows.filter { it.any { c -> c.isNotBlank() } }
}

/* ── Office / 二进制 ──────────────────────────────────────────────────── */

/**
 * Office 面板（兜底）。
 *
 * 只在**提取不出内容**时出现 —— 三种情况：
 *   1. 旧版二进制格式（.doc / .xls）：OLE2 复合文档，解析它等于写一个精简版 POI，明确不做；
 *   2. 演示文稿（pptx / ppt）：还原版式的工作量是另一个量级，排期在后面；
 *   3. 加密、损坏，或者本就不是 OOXML（比如有人把 zip 改名成 .docx）。
 *
 * 不说"需要转换"这种含糊话：**端上能做的已经做了**（docx/xlsx 已经能预览），
 * 走到这里就是这两条具体原因之一，把原因说出来。
 */
@Composable
private fun OfficePanel(state: ViewerState.Ready) {
    val legacy = LEGACY_OFFICE_EXT.contains(FileKind.extensionOf(state.file.name))
    val hint = when {
        legacy -> "旧版 ${FileKind.extensionOf(state.file.name)} 是 OLE2 二进制格式，端上不解析。\n" +
            "另存为 .docx / .xlsx 就能在这里预览；现在可以先「让 AI 读」或分享出去打开。"
        state.kind == FileKind.OFFICE_SLIDE -> "演示文稿的端上预览还没做（要还原版式的工作量是另一个量级）。\n" +
            "想看内容就「让 AI 读」，想看原样就分享出去用其他应用打开。"
        else -> "这份文件提取不出内容 —— 可能是被加密、损坏，或者本来就不是 Office 文档。\n" +
            "可以先「让 AI 读」或分享出去打开。"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        StatusChip(kindLabel(state.kind), ChipTone.Neutral)
        Spacer(Modifier.height(14.dp))
        Text(
            text = "这个格式需要转换才能预览",
            style = MaterialTheme.typography.titleLarge,
            color = OcColors.Ink,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "${state.file.name}\n$hint",
            style = MaterialTheme.typography.bodySmall,
            color = OcColors.Ink2,
        )
    }
}

@Composable
private fun BinaryPanel(
    state: ViewerState.Ready,
    message: String = "这个文件不是文本，端上没有对应的预览方式",
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        StatusChip(formatBytes(state.bytes), ChipTone.Neutral)
        Spacer(Modifier.height(14.dp))
        Text(
            text = state.file.name,
            style = MaterialTheme.typography.titleLarge,
            color = OcColors.Ink,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = OcColors.Ink3,
        )
    }
}

/* ── 底部动作 ─────────────────────────────────────────────────────────── */

@Composable
private fun ActionBar(
    offline: Boolean,
    onToggleOffline: () -> Unit,
    onShare: () -> Unit,
    onAskAi: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(OcColors.Surface)
            // 查看器是深度 1 的页面，底下没有 Tab 栏托着，得自己避开手势条
            .bottomBarInsets()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionButton(
            label = if (offline) "取消离线" else "离线保存",
            icon = { Icon(
                if (offline) Icons.Filled.Inventory2 else Icons.Filled.DownloadForOffline,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (offline) OcColors.Ok else OcColors.Ink2,
            ) },
            onClick = onToggleOffline,
        )

        ActionButton(
            label = "分享",
            icon = { Icon(
                Icons.Filled.Share,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = OcColors.Ink2,
            ) },
            onClick = onShare,
        )

        Spacer(Modifier.weight(1f))

        ActionButton(
            label = "让 AI 读",
            primary = true,
            icon = { Icon(
                Icons.Filled.SmartToy,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = OcColors.Surface,
            ) },
            onClick = onAskAi,
        )
    }
}

@Composable
private fun ActionButton(
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
) {
    val interaction = rememberPress()
    val scale = pressScale(interaction)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (primary) OcColors.Ink else OcColors.Surface2)
            .border(
                1.dp,
                if (primary) OcColors.Ink else OcColors.Line2,
                RoundedCornerShape(12.dp),
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
            ) { onClick() }
            .padding(horizontal = 12.dp, vertical = 9.dp)
            .scale(scale),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.invoke()
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = when {
                !enabled -> OcColors.Ink3
                primary -> OcColors.Surface
                else -> OcColors.Ink2
            },
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun NoticeLine(notice: String?, onConsumed: () -> Unit) {
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(3_000)
            onConsumed()
        }
    }
    if (notice == null) return
    Text(
        text = notice,
        style = MaterialTheme.typography.bodySmall,
        color = OcColors.Warn,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(OcColors.WarnSoft)
            .border(1.dp, OcColors.WarnLine, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
    )
}

/* ── 工具 ─────────────────────────────────────────────────────────────── */

private fun kindLabel(kind: FileKind): String = when (kind) {
    FileKind.TEXT -> "文本"
    FileKind.MARKDOWN -> "Markdown"
    FileKind.IMAGE -> "图片"
    FileKind.PDF -> "PDF"
    FileKind.SHEET, FileKind.CSV -> "表格"
    FileKind.OFFICE_DOC -> "Word 文档"
    FileKind.OFFICE_SHEET -> "Excel 表格"
    FileKind.OFFICE_SLIDE -> "演示文稿"
    FileKind.ARCHIVE -> "压缩包"
    FileKind.BINARY -> "二进制"
}

/** 文本渲染的门槛：类型对 + 体积可控。 */
private fun isTextRenderable(name: String, kind: FileKind, size: Long): Boolean {
    if (size > MAX_TEXT_BYTES) return false
    val ext = FileKind.extensionOf(name)
    return when (kind) {
        FileKind.TEXT, FileKind.MARKDOWN, FileKind.CSV, FileKind.SHEET -> true
        else -> ext == "svg"
    }
}
