package com.opencode.mobile.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/*
 * 全面屏（edge-to-edge）适配策略集中在这里，别散到各个屏幕里去。
 *
 * 目标机型是有挖孔屏 + 手势条的机器（小米 17 Pro Max 这类）。原先的做法是在最外层
 * Column 上一次性 padding(WindowInsets.systemBars)，有两个毛病：
 *
 *   1. systemBars 不含 displayCutout —— 横屏时挖孔跑到屏幕侧边，内容会钻到摄像头底下；
 *   2. 系统栏区域露出的是页面底色而不是栏自身的颜色，屏幕顶上会看到一条色带，
 *      观感上"没铺满"。
 *
 * 现在改成**每条栏自己吃自己的 inset**：顶栏的背景铺进状态栏、底栏铺进手势条，
 * 屏幕中间的内容不再重复加 padding。
 *
 * 两个 Compose 上的小坑：WindowInsets.xxx 的 getter 是 @Composable 的，所以这几个
 * 扩展函数必须标 @Composable；但**不能**标 @ReadOnlyComposable —— 内部的
 * windowInsetsPadding 不是只读的。
 */

/** 常驻顶栏：状态栏 + 挖孔。背景铺到状态栏里，内容压在下面。 */
@Composable
fun Modifier.topBarInsets(): Modifier =
    windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))

/**
 * 常驻底栏（Tab 栏）：手势条。背景铺到屏幕最下沿。
 * 刻意不用 safeDrawing —— 它含 ime，会让 Tab 栏在键盘弹起时浮到屏幕中间。
 */
@Composable
fun Modifier.bottomBarInsets(): Modifier =
    windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))

/**
 * 侧边安全区。挖孔屏横屏时挖孔在左右边缘，必须避开。
 * 只取左右两侧 —— 纵向交给各条栏自己负责。
 */
@Composable
fun Modifier.horizontalSafeInsets(): Modifier =
    windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))

/**
 * 输入框底部留白：键盘与手势条**取较大者**。
 *
 * 必须含 ime：edge-to-edge 之后系统不再自动为键盘让位
 * （adjustResize 对 edge-to-edge 应用已失效），不吃 ime inset 输入框会被键盘整个盖住。
 * 用 union 取并集而不是两层 padding 叠加，避免键盘弹起时被垫两次。
 */
@Composable
fun Modifier.composerInsets(): Modifier =
    windowInsetsPadding(
        WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom)
    )
