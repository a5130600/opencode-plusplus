package com.opencode.mobile.ui.sessions

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.opencode.mobile.core.AppResult
import com.opencode.mobile.data.local.SessionEntity
import com.opencode.mobile.data.local.WorkspaceEntity
import com.opencode.mobile.ui.settings.DefaultHandle
import com.opencode.mobile.ui.settings.SheetHeader
import com.opencode.mobile.ui.settings.SheetRow
import com.opencode.mobile.ui.theme.OcColors
import kotlinx.coroutines.launch

/**
 * 新建会话。
 *
 * 关键点是**工作区可以选** —— v2 的 `POST /api/session` 带 `location:{directory}`
 * 才能决定这个会话在电脑的哪个目录里干活。不指定就是跟随电脑端当前目录，
 * 那通常不是用户想要的那个项目。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewSessionSheet(
    workspaces: List<WorkspaceEntity>,
    currentPath: String?,
    onDismiss: () -> Unit,
    onCreate: suspend (String?) -> AppResult<SessionEntity>,
    onCreated: (SessionEntity) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun create(path: String?) {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            when (val result = onCreate(path)) {
                is AppResult.Ok -> onCreated(result.value)
                is AppResult.Err -> error = result.message
            }
            busy = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = sheetState,
        containerColor = OcColors.Surface,
        dragHandle = { DefaultHandle() },
    ) {
        SheetHeader(
            title = "新建会话",
            subtitle = "选一个电脑上已有的工作区；不指定就跟随电脑端当前目录",
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .padding(horizontal = 8.dp),
        ) {
            item {
                SheetRow(
                    title = if (busy) "正在创建…" else "不指定工作区",
                    subtitle = "跟随电脑端 opencode 的当前目录",
                    selected = false,
                    onClick = { create(null) },
                    trailing = {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            tint = OcColors.Ink2,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }
            items(workspaces, key = { it.path }) { ws ->
                SheetRow(
                    title = ws.name,
                    subtitle = buildString {
                        append(ws.path)
                        ws.vcs?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                    },
                    selected = ws.path == currentPath,
                    onClick = { create(ws.path) },
                )
            }
            if (workspaces.isEmpty()) {
                item {
                    Text(
                        "还没同步到工作区。也可以直接「不指定」，用电脑端的当前目录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = OcColors.Ink3,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
                    )
                }
            }
            if (error != null) {
                item {
                    Text(
                        error!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = OcColors.Warn,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

/**
 * 「让 AI 读」的目标选择器。
 *
 * 文件查看器里的 AI 读按钮以前只能跳到"当前会话"，从产物页打开时根本没会话 ——
 * 按钮是死的。这里让它先选对话：既可以是已有会话，也可以顺手建一个新的。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskAiSheet(
    fileName: String,
    sessions: List<SessionEntity>,
    onDismiss: () -> Unit,
    /** 返回 null 表示成功，非 null 是要显示在面板里的错误文案。 */
    onPick: suspend (SessionEntity) -> String?,
    /** 同上。建一个新会话再把文件挂上去。 */
    onNewSession: suspend () -> String?,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = sheetState,
        containerColor = OcColors.Surface,
        dragHandle = { DefaultHandle() },
    ) {
        SheetHeader(
            title = "让 AI 读",
            subtitle = "把 $fileName 挂到选中的对话上；发出去才花 token",
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .padding(horizontal = 8.dp),
        ) {
            item {
                SheetRow(
                    title = if (busy) "正在处理…" else "在新会话里读",
                    subtitle = "用当前工作区新建一个会话，再挂上这个文件",
                    selected = false,
                    onClick = {
                        if (busy) return@SheetRow
                        scope.launch {
                            busy = true
                            error = onNewSession()
                            busy = false
                            if (error == null) onDismiss()
                        }
                    },
                    trailing = {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            tint = OcColors.Ink2,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }
            items(sessions, key = { it.id }) { session ->
                SheetRow(
                    title = session.title,
                    subtitle = session.workspacePath ?: session.id.take(8),
                    selected = false,
                    onClick = {
                        if (busy) return@SheetRow
                        scope.launch {
                            busy = true
                            error = onPick(session)
                            busy = false
                            if (error == null) onDismiss()
                        }
                    },
                )
            }
            if (sessions.isEmpty()) {
                item {
                    Text(
                        "还没有会话。用上面的「在新会话里读」直接开一个。",
                        style = MaterialTheme.typography.bodySmall,
                        color = OcColors.Ink3,
                        fontFamily = FontFamily.Default,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
                    )
                }
            }
            if (error != null) {
                item {
                    Text(
                        error!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = OcColors.Warn,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}
