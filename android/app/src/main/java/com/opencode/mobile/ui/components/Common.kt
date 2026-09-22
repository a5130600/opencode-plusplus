package com.opencode.mobile.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opencode.mobile.core.ConnectionStatus
import com.opencode.mobile.ui.theme.OcColors
import com.opencode.mobile.ui.theme.OcMotion

enum class ChipTone { Neutral, Run, Ok, Warn, Solid }

private data class ChipColors(val bg: Color, val fg: Color, val border: Color)

private fun chipColors(tone: ChipTone) = when (tone) {
    ChipTone.Neutral -> ChipColors(OcColors.Surface2, OcColors.Ink2, OcColors.Line2)
    ChipTone.Run -> ChipColors(OcColors.RunSoft, OcColors.Run, OcColors.RunLine)
    ChipTone.Ok -> ChipColors(OcColors.OkSoft, OcColors.Ok, OcColors.OkLine)
    ChipTone.Warn -> ChipColors(OcColors.WarnSoft, OcColors.Warn, OcColors.WarnLine)
    ChipTone.Solid -> ChipColors(OcColors.Ink, Color.White, OcColors.Ink)
}

/**
 * 状态标签。
 * 注意 maxLines = 1 + softWrap = false：中文标签在挤压下发换行会撑破容器，
 * 这是原型里暴露出来的第一个 bug，这里从根上避免。
 */
@Composable
fun StatusChip(
    text: String,
    tone: ChipTone = ChipTone.Neutral,
    modifier: Modifier = Modifier,
) {
    val colors = chipColors(tone)
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(colors.bg)
            .border(1.dp, colors.border, CircleShape)
            .padding(horizontal = 9.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = colors.fg,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** 按压缩放反馈：130ms，松手弹回。 */
@Composable
fun pressScale(interaction: MutableInteractionSource): Float {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = tween(OcMotion.PRESS_MS, easing = OcMotion.Standard),
        label = "pressScale",
    )
    return scale
}

@Composable
fun rememberPress(): MutableInteractionSource = remember { MutableInteractionSource() }

/** 连接状态点。呼吸光环传达"活着"，而不是一个静止的圆。 */
@Composable
fun StatusDot(status: ConnectionStatus, modifier: Modifier = Modifier) {
    val color by animateColorAsState(
        targetValue = when (status) {
            ConnectionStatus.CONNECTED -> OcColors.Ok
            ConnectionStatus.CONNECTING, ConnectionStatus.RECONNECTING -> OcColors.Run
            ConnectionStatus.DISCONNECTED -> OcColors.Ink3
        },
        animationSpec = tween(300, easing = OcMotion.Standard),
        label = "statusDot",
    )
    Box(
        modifier = modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/** 顶部连接条：一眼看到"连着谁、通不通"。[onClick] 打开设备切换。 */
@Composable
fun ConnectionBar(
    status: ConnectionStatus,
    deviceName: String?,
    latencyHint: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = rememberPress()
    val scale = pressScale(interaction)
    val label = when (status) {
        ConnectionStatus.CONNECTED -> "已连接"
        ConnectionStatus.CONNECTING -> "连接中"
        ConnectionStatus.RECONNECTING -> "重连中"
        ConnectionStatus.DISCONNECTED -> "未连接"
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 背景先铺，再吃 inset —— 这样颜色一直延到状态栏里，顶上不会漏出页面底色
            .background(OcColors.Surface2)
            .topBarInsets()
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .scale(scale),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(status)
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (deviceName != null) "$label · $deviceName" else label,
            style = MaterialTheme.typography.bodySmall,
            color = OcColors.Ink,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        if (latencyHint != null) {
            Text(
                text = latencyHint,
                style = MaterialTheme.typography.bodySmall,
                color = OcColors.Ink3,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = OcColors.Ink3,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp),
    )
}

@Composable
fun OcCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(OcColors.Surface2)
            .border(1.dp, OcColors.Line2, RoundedCornerShape(18.dp)),
    ) { content() }
}

@Composable
fun OcIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = OcColors.Ink2,
) {
    val interaction = rememberPress()
    val scale = pressScale(interaction)
    Box(
        modifier = modifier
            .size(44.dp)   // 热区撑到 44dp：图标视觉上 34dp，但手指要够得着
            .scale(scale)
            .clickable(interactionSource = interaction, indication = null) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun EmptyState(
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = OcColors.Ink2)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = OcColors.Ink3)
    }
}

/** 存储占用条：预览 / 离线两段，超出配额部分一眼可见。 */
@Composable
fun UsageMeter(
    previewBytes: Long,
    offlineBytes: Long,
    quotaBytes: Long,
    modifier: Modifier = Modifier,
) {
    val quota = quotaBytes.coerceAtLeast(1L)
    val used = previewBytes + offlineBytes
    // 超过配额时按比例压缩两段，保证进度条永远不溢出容器
    val scale = if (used > quota) quota.toFloat() / used.toFloat() else 1f
    val previewWeight = (previewBytes * scale).coerceAtLeast(0f)
    val offlineWeight = (offlineBytes * scale).coerceAtLeast(0f)
    val restWeight = (quota - previewBytes - offlineBytes).coerceAtLeast(0L).toFloat()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(7.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(OcColors.Line2),
    ) {
        if (used == 0L) {
            Spacer(Modifier.weight(1f))
        } else {
            if (previewWeight > 0f) {
                Box(
                    Modifier
                        .weight(previewWeight.coerceAtLeast(0.001f))
                        .fillMaxHeight()
                        .background(OcColors.Run)
                )
            }
            if (offlineWeight > 0f) {
                Box(
                    Modifier
                        .weight(offlineWeight.coerceAtLeast(0.001f))
                        .fillMaxHeight()
                        .background(OcColors.Ok)
                )
            }
            if (restWeight > 0f) {
                Spacer(Modifier.weight(restWeight.coerceAtLeast(0.001f)))
            }
        }
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return if (index == 0) "${bytes} B"
    else String.format("%.1f %s", value, units[index])
}
