package com.opencode.mobile.ui.permissions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.opencode.mobile.R
import com.opencode.mobile.core.PermissionResponses
import com.opencode.mobile.data.remote.dto.PermissionDto
import com.opencode.mobile.ui.components.PromptButton
import com.opencode.mobile.ui.theme.OcColors
import kotlinx.coroutines.launch

/**
 * 权限审批弹窗 —— **全局**的，盖在任何页面之上。
 *
 * 为什么必须是弹窗而不是"会话页里的一张卡"：
 * 电脑上弹确认框时，人可能在产物页翻文件、在设置页换模型，甚至压根没打开会话。
 * 而这一步不答复，电脑端的任务就一直卡在那儿 —— 远处还看不见屏幕。
 * 所以审批这件事不能"等你逛到那个会话才发现"。
 *
 * 排队的写法（第 N / M 条）是刻意的：一次只让人拍一条，
 * 三条叠在一起只会让人随手全点允许，那还不如没有审批。
 */
@Composable
fun PermissionPromptDialog(
    permission: PermissionDto,
    index: Int,
    total: Int,
    /** 返回 null = 成功；非 null = 要显示在弹窗里的错误文案（这条会留着让用户重试）。 */
    onDecide: suspend (String) -> String?,
    onLater: () -> Unit,
) {
    Dialog(
        // 返回键 = "稍后"：通知栏那条还在，随时能回来批
        onDismissRequest = onLater,
        properties = DialogProperties(
            dismissOnBackPress = true,
            // 点外面不关：这是要人做决定的框，误点一下就消失等于没提示
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        var busy by remember { mutableStateOf(false) }
        var error by remember(permission.id) { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()

        PermissionCard(
            permission = permission,
            index = index,
            total = total,
            busy = busy,
            error = error,
            onDecide = { decision ->
                scope.launch {
                    busy = true
                    error = onDecide(decision)
                    busy = false
                }
            },
            onLater = onLater,
        )
    }
}

@Composable
private fun PermissionCard(
    permission: PermissionDto,
    index: Int,
    total: Int,
    busy: Boolean,
    error: String?,
    onDecide: (String) -> Unit,
    onLater: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OcColors.Ink.copy(alpha = 0.34f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(OcColors.Surface)
                .border(1.dp, OcColors.WarnLine, RoundedCornerShape(20.dp)),
        ) {
            // 顶部条：状态 + 队列位置 + 稍后
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(OcColors.WarnSoft)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Shield,
                    contentDescription = null,
                    tint = OcColors.Warn,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.permission_prompt_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = OcColors.Warn,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (total > 1) {
                    Text(
                        text = stringResource(R.string.permission_prompt_queue, index, total),
                        style = MaterialTheme.typography.labelSmall,
                        color = OcColors.Warn,
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    text = stringResource(R.string.permission_prompt_later),
                    style = MaterialTheme.typography.labelSmall,
                    color = OcColors.Ink2,
                    modifier = Modifier.clickable(enabled = !busy) { onLater() },
                )
            }

            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = permission.action.ifBlank { "权限请求" },
                    style = MaterialTheme.typography.titleLarge,
                    color = OcColors.Ink,
                )

                Text(
                    text = permission.summary.ifBlank { "需要你确认" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = OcColors.Ink2,
                )

                if (permission.resources.isNotEmpty()) {
                    // 命令/路径必须原样给全：只显示摘要等于让人盲签
                    Text(
                        text = permission.resources.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        color = OcColors.Ink,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState())
                            .clip(RoundedCornerShape(10.dp))
                            .background(OcColors.Surface2)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }

                Text(
                    text = stringResource(R.string.permission_prompt_blocking),
                    style = MaterialTheme.typography.bodySmall,
                    color = OcColors.Warn,
                )

                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = OcColors.Warn,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PromptButton(
                        text = stringResource(R.string.action_reject),
                        filled = false,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        onClick = { onDecide(PermissionResponses.REJECT) },
                    )
                    PromptButton(
                        text = stringResource(R.string.action_approve),
                        filled = true,
                        enabled = !busy,
                        modifier = Modifier.weight(2f),
                        onClick = { onDecide(PermissionResponses.ALLOW) },
                        showProgress = busy,
                    )
                }

                PromptButton(
                    text = stringResource(R.string.action_approve_always),
                    filled = false,
                    enabled = !busy,
                    hint = stringResource(R.string.permission_prompt_always_hint),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onDecide(PermissionResponses.ALWAYS) },
                )
            }
        }
    }
}
