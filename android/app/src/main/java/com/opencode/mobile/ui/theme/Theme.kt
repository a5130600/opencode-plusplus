package com.opencode.mobile.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 设计令牌。改这里等于改整套界面，别在页面里写死颜色。
 *
 * 配色逻辑：**彩色只表示状态，不表示操作。**
 * 主按钮用近黑而不是品牌色 —— 工具型产品里，彩色应该留给
 * "运行中 / 待审批" 这类真正要抓眼球的信息。
 */
object OcColors {
    val Bg = Color(0xFFEFEDE8)
    val Surface = Color(0xFFFFFFFF)
    val Surface2 = Color(0xFFF7F5F1)
    val Ink = Color(0xFF1E1E1B)
    val Ink2 = Color(0xFF6B6862)
    val Ink3 = Color(0xFFA5A199)
    val Line = Color(0xFFE4E0D8)
    val Line2 = Color(0xFFEFECE6)

    val Run = Color(0xFFB0742F)
    val RunSoft = Color(0xFFF7EEE0)
    val RunLine = Color(0xFFEBDCC4)

    val Ok = Color(0xFF3F6B4F)
    val OkSoft = Color(0xFFE7EFE9)
    val OkLine = Color(0xFFD6E4DA)

    val Warn = Color(0xFFA5443A)
    val WarnSoft = Color(0xFFF6E8E6)
    val WarnLine = Color(0xFFEED9D6)

    /** diff 走代码约定：绿增红删（区别于金融的红涨绿跌）。 */
    val Add = Color(0xFF2F6B45)
    val AddBg = Color(0xFFE9F1EA)
    val Del = Color(0xFFA5443A)
    val DelBg = Color(0xFFF7E9E7)
}

/**
 * 动效纪律：**只准两组缓动曲线。**
 * 位移用 Emphasized（iOS 系统级手感），透明度/缩放用 Standard。
 * 曲线一多，产品立刻显得业余 —— 这是"成熟感"最容易被破坏的地方。
 */
object OcMotion {
    val Emphasized: Easing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
    val Standard: Easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

    const val TAB_MS = 200
    const val PUSH_MS = 340
    const val SHEET_MS = 380
    const val PRESS_MS = 130
    const val CARD_MS = 340
    const val LIST_STAGGER_MS = 55
}

private val lightScheme = lightColorScheme(
    primary = OcColors.Ink,
    onPrimary = Color.White,
    primaryContainer = OcColors.Ink,
    onPrimaryContainer = Color.White,
    background = OcColors.Bg,
    onBackground = OcColors.Ink,
    surface = OcColors.Surface,
    onSurface = OcColors.Ink,
    surfaceVariant = OcColors.Surface2,
    onSurfaceVariant = OcColors.Ink2,
    outline = OcColors.Line,
    outlineVariant = OcColors.Line2,
    error = OcColors.Warn,
    onError = Color.White,
)

/**
 * 深色模式：同一套语义令牌反向映射。
 * 近黑主按钮在深色下必须反转为浅色底，否则会和背景糊在一起。
 */
private val darkScheme = darkColorScheme(
    primary = Color(0xFFF0EEE9),
    onPrimary = Color(0xFF1E1E1B),
    primaryContainer = Color(0xFFF0EEE9),
    onPrimaryContainer = Color(0xFF1E1E1B),
    background = Color(0xFF171715),
    onBackground = Color(0xFFF2F0EB),
    surface = Color(0xFF1F1F1C),
    onSurface = Color(0xFFF2F0EB),
    surfaceVariant = Color(0xFF26261F),
    onSurfaceVariant = Color(0xFFB9B5AD),
    outline = Color(0xFF3A3A34),
    outlineVariant = Color(0xFF2A2A25),
    error = Color(0xFFE0857A),
    onError = Color(0xFF2A0F0C),
)

private val OcTypography = Typography(
    headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.02).sp),
    titleLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.01).sp),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.01).sp),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 17.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.01.sp),
)

val MonoFamily = FontFamily.Monospace

/**
 * 主题。
 *
 * ⚠️ **刻意不跟随系统深色模式**（darkTheme 默认 false，而不是 isSystemInDarkTheme()）。
 *
 * 原因：整个界面用的是硬编码的 [OcColors]（纯浅色），而 OcColors **不随主题变化**。
 * 一旦让 Material 配色跟着系统切成 darkScheme，就会出现"Material 说是深色、实际背景是浅色"的撕裂 ——
 * 典型症状是输入框/默认文字拿到 darkScheme 的 onSurface（#F2F0EB 近白），
 * 压在浅色底上**直接看不见**。系统栏图标也是同一个毛病（见 MainActivity 的 SystemBarStyle）。
 *
 * 要做真正的深色模式，得先把 OcColors 全部换成随主题变化的语义令牌 ——
 * 那是独立的一件事，不能靠打开这个开关来假装完成。
 */
@Composable
fun OpenCodeTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) darkScheme else lightScheme,
        typography = OcTypography,
        content = content,
    )
}
