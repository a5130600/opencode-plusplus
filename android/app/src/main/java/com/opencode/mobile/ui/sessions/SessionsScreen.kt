package com.opencode.mobile.ui.sessions

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.opencode.mobile.core.AppResult
import com.opencode.mobile.data.local.DeviceEntity
import com.opencode.mobile.data.local.SessionEntity
import com.opencode.mobile.data.local.WorkspaceEntity
import com.opencode.mobile.data.repository.SessionRepository
import com.opencode.mobile.ui.components.ChipTone
import com.opencode.mobile.ui.components.EmptyState
import com.opencode.mobile.ui.components.OcIconButton
import com.opencode.mobile.ui.components.StatusChip
import com.opencode.mobile.ui.components.pressScale
import com.opencode.mobile.ui.components.rememberPress
import com.opencode.mobile.ui.theme.OcColors
import com.opencode.mobile.ui.theme.OcMotion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val repository: SessionRepository,
) : ViewModel() {

    private val deviceId = MutableStateFlow<String?>(null)

    val sessions: StateFlow<List<SessionEntity>> = deviceId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else repository.observeSessions(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun bind(deviceId: String?) {
        if (this.deviceId.value == deviceId) return
        this.deviceId.value = deviceId
        refresh()
    }

    fun refresh() {
        val id = deviceId.value ?: return
        viewModelScope.launch {
            repository.refreshSessions(id)
            // SSE 不是真相源：进列表就顺手校准一次运行状态
            repository.refreshStatus()
        }
    }

    /** 删除会话。返回 null 表示成功，否则是要显示给用户的原因。 */
    suspend fun delete(session: SessionEntity): String? {
        val id = deviceId.value
        // 会话不属于当前设备（理论上不会发生）就不要动它：删错设备的数据不可恢复
        if (id != null && session.deviceId != id) return "这个会话不属于当前设备"
        return when (val result = repository.deleteSession(session.id)) {
            is AppResult.Ok -> null
            is AppResult.Err -> result.message
        }
    }
}

@Composable
fun SessionsScreen(
    activeDevice: DeviceEntity?,
    currentWorkspace: WorkspaceEntity?,
    workspaces: List<WorkspaceEntity>,
    onOpenWorkspaces: () -> Unit,
    onOpenSession: (String, String) -> Unit,
    onAddDevice: () -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: SessionsViewModel = hiltViewModel()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    /** 待确认删除的会话。非 null 时弹确认框。 */
    var pendingDelete by remember { mutableStateOf<SessionEntity?>(null) }
    var deleteError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(activeDevice?.id) { vm.bind(activeDevice?.id) }

    val currentKey = currentWorkspace?.path.orEmpty()
    val groups = remember(sessions, currentKey) { groupSessions(sessions, currentKey) }
    val deviceKey = activeDevice?.id.orEmpty()

    Column(modifier.fillMaxSize().background(OcColors.Bg)) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "会话",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f),
                )
                // 新建会话常驻在标题右侧：这是"我要开始一件新事"的入口，
                // 藏在空状态里的话，有会话之后就找不到它了。
                OcIconButton(Icons.Filled.Add, onClick = onNewSession)
            }

            Spacer(Modifier.height(12.dp))
            WorkspaceButton(
                label = currentWorkspace?.path ?: "未选择工作区",
                onClick = {
                    if (workspaces.isEmpty()) vm.refresh() else onOpenWorkspaces()
                },
            )

            Spacer(Modifier.height(10.dp))
            val running = sessions.count { it.status != null && it.status != "idle" }
            Text(
                text = if (sessions.isEmpty()) "还没有会话"
                else buildString {
                    append("${sessions.size} 个会话")
                    if (running > 0) append(" · $running 个正在运行")
                },
                style = MaterialTheme.typography.bodySmall,
                color = OcColors.Ink3,
            )
        }

        when {
            activeDevice == null -> EmptyState(
                title = "还没有连接设备",
                hint = "点一下这里，填上电脑在 Tailscale 里的私网地址",
                modifier = Modifier.clickable { onAddDevice() },
            )

            sessions.isEmpty() -> EmptyState(
                title = "还没有会话",
                hint = "点右上角的 + 直接新建一个（可以选工作区）；" +
                    "或者在电脑上开一个会话，回到这里下拉刷新",
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                groups.forEach { group ->
                    val collapsed = GroupCollapse.isCollapsed(
                        deviceKey = deviceKey,
                        groupKey = group.key,
                        fallback = groups.size > 1 && group.key != currentKey,
                    )
                    item(key = "g_${group.key}") {
                        GroupHeader(
                            title = group.title,
                            count = group.sessions.size,
                            running = group.sessions.count {
                                it.status != null && it.status != "idle"
                            },
                            collapsed = collapsed,
                            onToggle = {
                                GroupCollapse.toggle(
                                    deviceKey = deviceKey,
                                    groupKey = group.key,
                                    fallback = groups.size > 1 && group.key != currentKey,
                                )
                            },
                        )
                    }
                    if (!collapsed) {
                        items(group.sessions, key = { it.id }) { session ->
                            SessionRow(
                                session = session,
                                onClick = { onOpenSession(session.id, session.title) },
                                onDelete = { pendingDelete = session },
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    pendingDelete?.let { session ->
        DeleteSessionDialog(
            title = session.title,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                val target = session
                pendingDelete = null
                scope.launch {
                    deleteError = vm.delete(target)
                }
            },
        )
    }

    if (deleteError != null) {
        Text(
            text = "删除失败：${deleteError!!}",
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
}

/**
 * 删除确认。
 *
 * 措辞必须说清三件事，不然用户不敢点也不知道会发生什么：
 *   1. 电脑端会一起删（不是只从手机上消失）；
 *   2. 对话记录找不回来；
 *   3. 工作区还在 —— 很多人担心的其实是"我的代码会不会没了"。
 */
@Composable
private fun DeleteSessionDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OcColors.Surface,
        title = { Text("删除这个会话？", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = OcColors.Ink,
                    maxLines = 2,
                )
                Text(
                    text = "电脑端会一起删掉，这段对话记录就找不回来了。\n" +
                        "工作区和你的文件都还在，只是这个会话消失。",
                    style = MaterialTheme.typography.bodySmall,
                    color = OcColors.Ink2,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = OcColors.Ink2) }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("删除", color = OcColors.Warn) }
        },
    )
}

/** 按工作目录分好的一组会话。 */
private data class SessionGroup(
    /** 工作目录；空串代表"服务端没报工作目录"。 */
    val key: String,
    val title: String,
    val sessions: List<SessionEntity>,
)

/**
 * 折叠状态。
 *
 * 放在 object 里而不是 remember：Tab 切走再切回来时 AnimatedContent 会丢弃组合状态，
 * 用 remember 的话用户刚收起来的组又弹开了。和聊天页的滚动位置一个道理 ——
 * **只在进程内记**，App 被杀就重置，可以接受。
 */
private object GroupCollapse {
    // ⚠️ 必须是 **mutableStateMapOf**：普通 mutableMap 改了不会通知 Compose，
    // 表现就是"点了箭头没反应，切到别的界面再回来才生效"（回来时重新组合才读到新值）。
    private val state = mutableStateMapOf<String, Boolean>()

    private fun id(deviceKey: String, groupKey: String) = "$deviceKey|$groupKey"

    fun isCollapsed(deviceKey: String, groupKey: String, fallback: Boolean): Boolean =
        state[id(deviceKey, groupKey)] ?: fallback

    fun toggle(deviceKey: String, groupKey: String, fallback: Boolean) {
        state[id(deviceKey, groupKey)] = !(state[id(deviceKey, groupKey)] ?: fallback)
    }
}

/**
 * 按工作目录分组。
 *
 * 排序：**当前工作区那组放最前**，其余按组内最近活动时间倒序 ——
 * 这样"我正在做的项目"永远一屏内可见，别的目录收起来也不影响找。
 */
private fun groupSessions(sessions: List<SessionEntity>, currentKey: String): List<SessionGroup> =
    sessions
        .groupBy { it.workspacePath?.trim()?.takeIf { it.isNotBlank() } ?: "" }
        .map { (key, list) ->
            val name = key.trimEnd('/', '\\')
                .substringAfterLast('/')
                .substringAfterLast('\\')
            SessionGroup(
                key = key,
                title = name.ifBlank { if (key.isBlank()) "未指定工作区" else key },
                sessions = list,
            )
        }
        .sortedWith(
            compareByDescending<SessionGroup> { it.key == currentKey }
                .thenByDescending { group ->
                    group.sessions.maxOf { it.updatedAt ?: it.createdAt ?: 0L }
                }
        )

@Composable
private fun GroupHeader(
    title: String,
    count: Int,
    running: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
) {
    val interaction = rememberPress()
    val scale = pressScale(interaction)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(OcColors.Surface2)
            .clickable(interactionSource = interaction, indication = null) { onToggle() }
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .scale(scale),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (collapsed) Icons.Filled.ChevronRight else Icons.Filled.ExpandMore,
            contentDescription = if (collapsed) "展开" else "收起",
            tint = OcColors.Ink2,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = OcColors.Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        if (running > 0) {
            Text(
                text = "$running 个运行中",
                style = MaterialTheme.typography.labelSmall,
                color = OcColors.Run,
                maxLines = 1,
                softWrap = false,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelSmall,
            color = OcColors.Ink3,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun WorkspaceButton(label: String, onClick: () -> Unit) {
    val interaction = rememberPress()
    val scale = pressScale(interaction)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(OcColors.Surface2)
            .border(1.dp, OcColors.Line, RoundedCornerShape(10.dp))
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(horizontal = 11.dp, vertical = 8.dp)
            .scale(scale),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = OcColors.Ink2, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(7.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = OcColors.Ink,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = OcColors.Ink3, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun SessionRow(
    session: SessionEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val interaction = rememberPress()
    val scale = pressScale(interaction)
    val tone = when (session.status) {
        null, "idle" -> ChipTone.Ok
        "busy", "retry" -> ChipTone.Run
        else -> ChipTone.Neutral
    }
    val label = when (session.status) {
        null, "idle" -> "已完成"
        "busy" -> "运行中"
        "retry" -> "重试中"
        else -> session.status
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(horizontal = 12.dp, vertical = 14.dp)
            .scale(scale),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(
                    when (tone) {
                        ChipTone.Run -> OcColors.RunSoft
                        ChipTone.Ok -> OcColors.OkSoft
                        else -> OcColors.Surface2
                    }
                )
                .border(
                    1.dp,
                    when (tone) {
                        ChipTone.Run -> OcColors.RunLine
                        ChipTone.Ok -> OcColors.OkLine
                        else -> OcColors.Line
                    },
                    RoundedCornerShape(11.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = session.title.trim().take(1).ifBlank { "S" },
                style = MaterialTheme.typography.titleMedium,
                color = when (tone) {
                    ChipTone.Run -> OcColors.Run
                    ChipTone.Ok -> OcColors.Ok
                    else -> OcColors.Ink2
                },
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = session.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(label, tone)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = session.workspacePath ?: "会话 ${session.id.take(6)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = OcColors.Ink3,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = relativeTime(session.updatedAt ?: session.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = OcColors.Ink3,
            )
            Spacer(Modifier.height(2.dp))
            // 删除做成行内小图标而不是左滑：左滑手势在这个列表里容易和"打开"混淆，
            // 而且误触的代价是整段对话没了 —— 这里宁可多一步确认。
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onDelete() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.DeleteOutline,
                    contentDescription = "删除会话",
                    tint = OcColors.Ink3,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private val dateFormat = SimpleDateFormat("MM-dd", Locale.getDefault())

private fun relativeTime(millis: Long?): String {
    if (millis == null || millis <= 0) return ""
    val delta = System.currentTimeMillis() - millis
    return when {
        delta < 60_000 -> "刚刚"
        delta < 3_600_000 -> "${delta / 60_000} 分钟"
        delta < 86_400_000 -> timeFormat.format(Date(millis))
        else -> dateFormat.format(Date(millis))
    }
}
