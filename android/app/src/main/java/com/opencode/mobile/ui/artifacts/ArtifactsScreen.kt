package com.opencode.mobile.ui.artifacts

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.opencode.mobile.core.AppResult
import com.opencode.mobile.core.resolveAbsolute
import com.opencode.mobile.data.local.CachedFileEntity
import com.opencode.mobile.data.local.DeviceEntity
import com.opencode.mobile.data.local.WorkspaceEntity
import com.opencode.mobile.data.remote.dto.FileEntryDto
import com.opencode.mobile.data.repository.ArtifactRepository
import com.opencode.mobile.data.repository.WorkspaceRepository
import com.opencode.mobile.data.storage.CacheUsage
import com.opencode.mobile.data.storage.FileKind
import com.opencode.mobile.ui.components.ChipTone
import com.opencode.mobile.ui.components.EmptyState
import com.opencode.mobile.ui.components.OcIconButton
import com.opencode.mobile.ui.components.StatusChip
import com.opencode.mobile.ui.components.UsageMeter
import com.opencode.mobile.ui.components.formatBytes
import com.opencode.mobile.ui.components.pressScale
import com.opencode.mobile.ui.components.rememberPress
import com.opencode.mobile.ui.nav.FileViewTarget
import com.opencode.mobile.ui.theme.OcColors
import com.opencode.mobile.ui.theme.OcMotion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ArtifactsViewModel @Inject constructor(
    private val artifactRepository: ArtifactRepository,
    private val workspaceRepository: WorkspaceRepository,
) : ViewModel() {

    private val deviceId = MutableStateFlow<String?>(null)

    /** 已经缓存到本机的产物。 */
    val cached: StateFlow<List<CachedFileEntity>> = deviceId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else artifactRepository.observeCached(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 用量走观察流：缓存一变就更新，不用记着"进页面刷一次"。 */
    val usage: StateFlow<CacheUsage?> = artifactRepository.observeUsage()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _browsing = MutableStateFlow(false)
    val browsing: StateFlow<Boolean> = _browsing.asStateFlow()

    /** 浏览路径栈。空栈 = 还没定位到任何目录。 */
    private val _stack = MutableStateFlow<List<String>>(emptyList())
    val stack: StateFlow<List<String>> = _stack.asStateFlow()

    private val _entries = MutableStateFlow<List<FileEntryDto>>(emptyList())
    val entries: StateFlow<List<FileEntryDto>> = _entries.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** 待打开的文件，交给导航层。 */
    private val _pendingOpen = MutableStateFlow<FileViewTarget?>(null)
    val pendingOpen: StateFlow<FileViewTarget?> = _pendingOpen.asStateFlow()

    /**
     * 绑定设备。
     *
     * ⚠️ **不能用 deviceId 相同就短路**：这个 ViewModel 是 Activity 作用域的，
     * Tab 切走再切回来它不会重建，`deviceId` 一直没变 —— 短路会让订阅一直停在旧设备上。
     */
    fun bind(id: String?) {
        deviceId.value = id
    }

    fun toggleMode(browsing: Boolean) {
        _browsing.value = browsing
        if (browsing && _stack.value.isEmpty()) locateWorkspaceRoot()
    }

    /** 浏览起点 = 你正在做的那个项目目录，而不是 opencode 进程的启动目录。 */
    fun locateWorkspaceRoot() {
        val id = deviceId.value ?: return
        viewModelScope.launch {
            // 优先用工作区记录 —— 它现在是从**会话身上的项目目录**来的，
            // 那才是用户真正在做的项目。
            var root = workspaceRepository.current(id)?.path

            if (root.isNullOrBlank()) {
                workspaceRepository.sync(id)
                root = workspaceRepository.current(id)?.path
            }

            // 还是没有才退到服务端的进程目录（往往不准，只当兜底）
            if (root.isNullOrBlank()) {
                root = when (val r = artifactRepository.currentDirectory()) {
                    is AppResult.Ok -> r.value.takeIf { it.isNotBlank() }
                    is AppResult.Err -> null
                }
            }

            if (root.isNullOrBlank()) {
                _notice.value = "没有可浏览的目录。先在电脑端打开一个项目。"
                return@launch
            }
            _stack.value = listOf(root)
            loadEntries(root)
        }
    }

    fun openDir(path: String) {
        // 服务端返回的条目路径是**相对本次 location** 的，
        // 所以子目录要先拼成绝对路径再进栈 —— 否则下一层就定位错了。
        val absolute = resolveAbsolute(path, _stack.value.lastOrNull())
        _stack.value = _stack.value + absolute
        viewModelScope.launch { loadEntries(absolute) }
    }

    /** 切到别的工作区：把浏览栈重置为那个目录的根。 */
    fun openWorkspace(path: String) {
        val absolute = resolveAbsolute(path, _stack.value.firstOrNull())
        _stack.value = listOf(absolute)
        viewModelScope.launch { loadEntries(absolute) }
    }

    fun up() {
        val next = _stack.value.dropLast(1)
        _stack.value = next
        val target = next.lastOrNull()
        if (target == null) {
            _entries.value = emptyList()
        } else {
            viewModelScope.launch { loadEntries(target) }
        }
    }

    /** 栈里存的都是**绝对路径**：它同时是这次 fs/list 的 path 和 location（沙箱要求）。 */
    private suspend fun loadEntries(absolute: String) {
        _loading.value = true
        when (val result = artifactRepository.listFiles(absolute)) {
            is AppResult.Ok -> {
                // 目录在前、同类按名称排 —— 稳定顺序比"服务端给什么顺序"重要得多
                _entries.value = result.value.sortedWith(
                    compareBy({ it.type != "directory" }, { it.name.lowercase() })
                )
            }

            is AppResult.Err -> {
                _entries.value = emptyList()
                _notice.value = result.message
            }
        }
        _loading.value = false
    }

    fun setOffline(row: CachedFileEntity, offline: Boolean) {
        viewModelScope.launch {
            when (val result = artifactRepository.setOffline(row.blobId, offline)) {
                is AppResult.Ok -> Unit          // 用量由观察流自己更新
                is AppResult.Err -> _notice.value = result.message
            }
        }
    }

    fun requestOpen(row: CachedFileEntity) {
        _pendingOpen.value = FileViewTarget(
            deviceId = row.deviceId,
            sessionId = row.sessionId,
            remotePath = row.remotePath,
            displayName = row.displayName,
        )
    }

    fun requestOpen(node: FileEntryDto) {
        val id = deviceId.value ?: return
        // 同样要拼成绝对路径：查看器后面要用它算出 fs/read 的 location
        val absolute = resolveAbsolute(node.path, _stack.value.lastOrNull())
        _pendingOpen.value = FileViewTarget(
            deviceId = id,
            sessionId = null,
            remotePath = absolute,
            displayName = node.name.ifBlank { absolute.substringAfterLast('/') },
        )
    }

    fun consumeOpen() {
        _pendingOpen.value = null
    }

    fun consumeNotice() {
        _notice.value = null
    }

    fun refresh() {
        viewModelScope.launch {
            _stack.value.lastOrNull()?.let { loadEntries(it) }
        }
    }
}

/**
 * 产物页。
 *
 * 两个模式共用一页，因为它们是同一个问题的两种问法：
 * 「我要的东西我已经拿过了吗」（缓存）和「我要的东西在电脑哪儿」（浏览）。
 */
@Composable
fun ArtifactsScreen(
    activeDevice: DeviceEntity?,
    workspaces: List<WorkspaceEntity>,
    onSelectWorkspace: (String) -> Unit,
    onOpenFile: (FileViewTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: ArtifactsViewModel = hiltViewModel()
    val cached by vm.cached.collectAsStateWithLifecycle()
    val usage by vm.usage.collectAsStateWithLifecycle()
    val browsing by vm.browsing.collectAsStateWithLifecycle()
    val stack by vm.stack.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val pendingOpen by vm.pendingOpen.collectAsStateWithLifecycle()

    LaunchedEffect(activeDevice?.id) { vm.bind(activeDevice?.id) }
    LaunchedEffect(pendingOpen) {
        pendingOpen?.let {
            onOpenFile(it)
            vm.consumeOpen()
        }
    }

    Column(modifier.fillMaxSize().background(OcColors.Bg)) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("产物", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.weight(1f))
                OcIconButton(Icons.Filled.Refresh, onClick = vm::refresh, tint = OcColors.Ink2)
            }

            Spacer(Modifier.height(10.dp))
            ModeSwitch(browsing = browsing, onChange = vm::toggleMode)

            usage?.let { u ->
                Spacer(Modifier.height(12.dp))
                UsageMeter(
                    previewBytes = u.previewBytes,
                    offlineBytes = u.offlineBytes,
                    quotaBytes = u.quotaBytes,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "预览 ${formatBytes(u.previewBytes)} · 离线 ${formatBytes(u.offlineBytes)} (${u.offlineCount} 个)",
                    style = MaterialTheme.typography.bodySmall,
                    color = OcColors.Ink3,
                )
            }
        }

        NoticeLine(notice, vm::consumeNotice)

        AnimatedContent(
            targetState = browsing,
            transitionSpec = {
                fadeIn(tween(OcMotion.TAB_MS, easing = OcMotion.Standard)) togetherWith
                    fadeOut(tween(OcMotion.TAB_MS, easing = OcMotion.Standard))
            },
            label = "artifacts_mode",
        ) { isBrowsing ->
            if (activeDevice == null) {
                EmptyState(
                    title = "还没有连接设备",
                    hint = "先在设置里加一台电脑，产物才能取回来",
                )
            } else if (isBrowsing) {
                Column(Modifier.fillMaxSize()) {
                    Breadcrumb(
                        path = stack.lastOrNull(),
                        depth = stack.size,
                        workspaces = workspaces,
                        onUp = vm::up,
                        onLocate = vm::locateWorkspaceRoot,
                        onSwitchWorkspace = { path ->
                            vm.openWorkspace(path)
                            onSelectWorkspace(path)
                        },
                    )
                    when {
                        stack.isEmpty() -> EmptyState(
                            title = "选择一个起点",
                            hint = "点上面的「定位工作区」从当前项目根目录开始浏览",
                            modifier = Modifier.clickable { vm.locateWorkspaceRoot() },
                        )

                        loading && entries.isEmpty() -> Box(Modifier.fillMaxSize())

                        entries.isEmpty() -> EmptyState(
                            title = "这个目录是空的",
                            hint = "或者服务端没返回内容",
                        )

                        else -> LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            items(entries, key = { it.path }) { node ->
                                EntryRow(
                                    node = node,
                                    onClick = {
                                        if (node.type == "directory") vm.openDir(node.path)
                                        else vm.requestOpen(node)
                                    },
                                )
                            }
                            item { Spacer(Modifier.height(24.dp)) }
                        }
                    }
                }
            } else {
                if (cached.isEmpty()) {
                    EmptyState(
                        title = "本机还没有缓存任何产物",
                        hint = "在会话里点开文件、或切到「浏览」从电脑上取一个回来",
                        modifier = Modifier.clickable { vm.toggleMode(true) },
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(cached, key = { it.blobId }) { row ->
                            CachedRow(
                                row = row,
                                onOpen = { vm.requestOpen(row) },
                                onToggleOffline = { vm.setOffline(row, !row.pin) },
                            )
                        }
                        item { Spacer(Modifier.height(24.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeSwitch(browsing: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(OcColors.Surface2)
            .border(1.dp, OcColors.Line2, CircleShape)
            .padding(3.dp),
    ) {
        ModeTab("缓存", active = !browsing) { onChange(false) }
        ModeTab("浏览电脑", active = browsing) { onChange(true) }
    }
}

@Composable
private fun ModeTab(text: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (active) OcColors.Surface else OcColors.Surface2)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) OcColors.Ink else OcColors.Ink3,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun Breadcrumb(
    path: String?,
    depth: Int,
    workspaces: List<WorkspaceEntity>,
    onUp: () -> Unit,
    onLocate: () -> Unit,
    onSwitchWorkspace: (String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(OcColors.Surface2)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OcIconButton(Icons.AutoMirrored.Filled.ArrowBack, onClick = onUp, tint = OcColors.Ink2)
        Text(
            text = path ?: "未定位",
            style = MaterialTheme.typography.bodySmall,
            color = OcColors.Ink,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        // 直接在浏览界面切工作区，不用每次点"定位"。
        // 只在根层显示：进了子目录之后切换会让人失去方向。
        if (depth <= 1) {
            Spacer(Modifier.width(6.dp))
            Box {
                Text(
                    text = "工作区",
                    style = MaterialTheme.typography.labelSmall,
                    color = OcColors.Ink2,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { menu = true }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (workspaces.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("还没有工作区") },
                            onClick = { menu = false },
                        )
                    }
                    workspaces.forEach { ws ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = ws.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            onClick = {
                                menu = false
                                onSwitchWorkspace(ws.path)
                            },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("重新定位到当前项目") },
                        onClick = {
                            menu = false
                            onLocate()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun NoticeLine(notice: String?, onConsumed: () -> Unit) {
    LaunchedEffect(notice) {
        if (notice != null) {
            kotlinx.coroutines.delay(3_200)
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

@Composable
private fun CachedRow(
    row: CachedFileEntity,
    onOpen: () -> Unit,
    onToggleOffline: () -> Unit,
) {
    val interaction = rememberPress()
    val scale = pressScale(interaction)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(interactionSource = interaction, indication = null) { onOpen() }
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .scale(scale),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KindBadge(row.displayName)

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = row.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.pin) {
                    StatusChip("已离线", ChipTone.Ok)
                    Spacer(Modifier.width(7.dp))
                }
                Text(
                    text = buildString {
                        append(formatBytes(row.size))
                        if (row.state != "ready") append(" · ").append(stateLabel(row.state))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = OcColors.Ink3,
                    maxLines = 1,
                )
            }
        }

        OcIconButton(
            icon = if (row.pin) Icons.Filled.Inventory2 else Icons.Filled.DownloadForOffline,
            onClick = onToggleOffline,
            tint = if (row.pin) OcColors.Ok else OcColors.Ink3,
        )
    }
}

@Composable
private fun EntryRow(node: FileEntryDto, onClick: () -> Unit) {
    val interaction = rememberPress()
    val scale = pressScale(interaction)
    val isDir = node.type == "directory"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .scale(scale),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isDir) {
            Icon(
                Icons.Filled.Folder,
                contentDescription = null,
                tint = OcColors.Ink2,
                modifier = Modifier.size(19.dp),
            )
        } else {
            KindBadge(node.name)
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = node.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 这里原本显示文件大小，但服务端的 FileNode 不提供 size（见 Dtos.kt 注释），
            // 编一个 0 B 出来只会误导人。改成本地真正确定的那条信息：要不要转换。
            if (!isDir && FileKind.of(node.name).needsConversion) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "预览前需要转换",
                    style = MaterialTheme.typography.bodySmall,
                    color = OcColors.Ink3,
                    maxLines = 1,
                )
            }
        }

        Icon(
            imageVector = if (isDir) Icons.Filled.ChevronRight else Icons.Filled.FileOpen,
            contentDescription = null,
            tint = OcColors.Ink3,
            modifier = Modifier.size(17.dp),
        )
    }
}

/** 扩展名徽章。文件类型在列表里是最强的识别线索，用文字比用图标准。 */
@Composable
private fun KindBadge(fileName: String) {
    val ext = FileKind.extensionOf(fileName).take(4).uppercase().ifBlank { "?" }
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(OcColors.Surface2)
            .border(1.dp, OcColors.Line2, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = ext,
            style = MaterialTheme.typography.labelSmall,
            color = OcColors.Ink2,
            maxLines = 1,
            softWrap = false,
        )
    }
}

private fun stateLabel(state: String): String = when (state) {
    "downloading" -> "下载中"
    "failed" -> "下载失败"
    "none" -> "未下载"
    else -> state
}
