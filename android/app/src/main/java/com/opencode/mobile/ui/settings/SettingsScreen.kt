package com.opencode.mobile.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opencode.mobile.BuildConfig
import com.opencode.mobile.R
import com.opencode.mobile.core.ConnectionStatus
import com.opencode.mobile.data.local.DeviceEntity
import com.opencode.mobile.data.local.WorkspaceEntity
import com.opencode.mobile.data.repository.ModelOption
import com.opencode.mobile.data.storage.CacheUsage
import com.opencode.mobile.ui.components.OcCard
import com.opencode.mobile.ui.components.SectionTitle
import com.opencode.mobile.ui.components.StatusChip
import com.opencode.mobile.ui.components.StatusDot
import com.opencode.mobile.ui.components.UsageMeter
import com.opencode.mobile.ui.components.formatBytes
import com.opencode.mobile.ui.components.pressScale
import com.opencode.mobile.ui.components.rememberPress
import com.opencode.mobile.ui.theme.OcColors

/**
 * 设置。
 *
 * 这里承担一个不显眼但重要的职责：**把"连接到底通不通"讲清楚。**
 * 远程使用最大的挫败感来源是"连不上但不知道为什么"，
 * 所以设备地址、工作区来源、当前模型、缓存占用全部直白地摊在这里。
 */
@Composable
fun SettingsScreen(
    status: ConnectionStatus,
    devices: List<DeviceEntity>,
    activeDevice: DeviceEntity?,
    workspaces: List<WorkspaceEntity>,
    currentWorkspace: WorkspaceEntity?,
    selectedModel: ModelOption?,
    usage: CacheUsage?,
    onOpenDevices: () -> Unit,
    onOpenWorkspaces: () -> Unit,
    onOpenModels: () -> Unit,
    onClearPreviewCache: () -> Unit,
    onRemoveDevice: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmRemove by remember { mutableStateOf<DeviceEntity?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OcColors.Bg)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp),
        )

        /* ── 连接 ────────────────────────────────────────────────────── */
        SectionTitle("连接")

        OcCard {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(status)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = statusLabel(status),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = activeDevice?.let { "${it.host}:${it.port}" } ?: "没有选择设备",
                            style = MaterialTheme.typography.bodySmall,
                            color = OcColors.Ink3,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    activeDevice?.let { device ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(9.dp))
                                .clickable { confirmRemove = device }
                                .padding(6.dp),
                        ) {
                            Icon(
                                Icons.Filled.DeleteOutline,
                                contentDescription = "移除设备",
                                tint = OcColors.Ink3,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }

                Divider()

                SettingsRow(
                    title = "设备",
                    subtitle = if (devices.isEmpty()) "还没添加电脑"
                    else "${devices.size} 台 · 当前 ${activeDevice?.name ?: "未选"}",
                    onClick = onOpenDevices,
                )

                Divider()

                SettingsRow(
                    title = "工作区",
                    subtitle = currentWorkspace?.path
                        ?: if (workspaces.isEmpty()) "未同步到工作区" else "未选择",
                    monospace = true,
                    onClick = onOpenWorkspaces,
                )

                Divider()

                SettingsRow(
                    title = "模型",
                    subtitle = selectedModel?.let { "${it.providerName} · ${it.modelName}" }
                        ?: "跟随服务端默认",
                    onClick = onOpenModels,
                )
            }
        }

        /* ── 存储 ────────────────────────────────────────────────────── */
        SectionTitle("存储")

        OcCard {
            Column(Modifier.padding(16.dp)) {
                if (usage == null) {
                    Text(
                        text = "正在统计…",
                        style = MaterialTheme.typography.bodySmall,
                        color = OcColors.Ink3,
                    )
                } else {
                    UsageMeter(
                        previewBytes = usage.previewBytes,
                        offlineBytes = usage.offlineBytes,
                        quotaBytes = usage.quotaBytes,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row {
                        Text(
                            text = "预览缓存 ${formatBytes(usage.previewBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = OcColors.Ink2,
                        )
                        Spacer(Modifier.width(14.dp))
                        Text(
                            text = "离线保存 ${formatBytes(usage.offlineBytes)} (${usage.offlineCount})",
                            style = MaterialTheme.typography.bodySmall,
                            color = OcColors.Ink2,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "预览缓存上限 ${formatBytes(usage.quotaBytes)}，" +
                            "超出后按「转换产物 → 缩略图 → 原始文件」的顺序自动清理；" +
                            "离线保存的文件永远不会被自动删除。",
                        style = MaterialTheme.typography.bodySmall,
                        color = OcColors.Ink3,
                    )
                }

                Spacer(Modifier.height(14.dp))
                PillButton(
                    text = "清理预览缓存",
                    enabled = (usage?.previewBytes ?: 0L) > 0L,
                    onClick = onClearPreviewCache,
                )
            }
        }

        /* ── 安全提醒 ────────────────────────────────────────────────── */
        SectionTitle("安全")

        OcCard {
            Column(Modifier.padding(16.dp)) {
                SecurityLine(
                    title = "只走私网",
                    body = "opencode serve 的端口绝不要直接暴露到公网。" +
                        "用 Tailscale / WireGuard 组网，App 里填私网地址即可。",
                )
                Spacer(Modifier.height(12.dp))
                SecurityLine(
                    title = "必须设密码",
                    body = "电脑端启动时设置 OPENCODE_SERVER_PASSWORD，" +
                        "App 通过 HTTP Basic 鉴权访问。密码只存在手机的加密存储里。",
                )
                Spacer(Modifier.height(12.dp))
                SecurityLine(
                    title = "不要跳过权限",
                    body = "不要用 --dangerously-skip-permissions 启动。" +
                        "审批是这个 App 存在的核心价值，关掉它等于把电脑交给一条网络请求。",
                )
            }
        }

        /* ── 关于 ────────────────────────────────────────────────────── */
        SectionTitle("关于")

        OcCard {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 取 res/values/strings.xml 的 app_name，而不是在这里写死：
                    // 名字一改必须连这块一起改，写死就会留下"桌面图标叫新名字、
                    // 关于里还是旧名字"这种不一致。
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.weight(1f))
                    StatusChip("v${BuildConfig.VERSION_NAME}")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "opencode 的第三方远程客户端。没有中转服务器，" +
                        "所有请求都从这台手机直接打到你自己的电脑上。",
                    style = MaterialTheme.typography.bodySmall,
                    color = OcColors.Ink3,
                )
            }
        }

        Spacer(Modifier.height(36.dp))
    }

    confirmRemove?.let { device ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("移除「${device.name}」？") },
            text = {
                Text(
                    "会同时清掉这台设备的会话记录、工作区索引和已缓存的文件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = OcColors.Ink2,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRemoveDevice(device.id)
                    confirmRemove = null
                }) { Text("移除", color = OcColors.Warn) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) { Text("取消") }
            },
            containerColor = OcColors.Surface,
        )
    }
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    monospace: Boolean = false,
) {
    val interaction = rememberPress()
    val scale = pressScale(interaction)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .scale(scale),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(3.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = OcColors.Ink3,
                fontFamily = if (monospace) FontFamily.Monospace else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = OcColors.Ink3,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(
        color = OcColors.Line2,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun SecurityLine(title: String, body: String) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = OcColors.Ink,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = OcColors.Ink2,
        )
    }
}

@Composable
private fun PillButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val interaction = rememberPress()
    val scale = pressScale(interaction)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) OcColors.Surface2 else OcColors.Surface2.copy(alpha = 0.5f))
            .border(
                1.dp,
                if (enabled) OcColors.Line else OcColors.Line2,
                RoundedCornerShape(12.dp),
            )
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) {
                onClick()
            }
            .scale(scale),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) OcColors.Ink else OcColors.Ink3,
            maxLines = 1,
            softWrap = false,
        )
    }
}

private fun statusLabel(status: ConnectionStatus): String = when (status) {
    ConnectionStatus.CONNECTED -> "已连接"
    ConnectionStatus.CONNECTING -> "连接中"
    ConnectionStatus.RECONNECTING -> "重连中"
    ConnectionStatus.DISCONNECTED -> "未连接"
}
