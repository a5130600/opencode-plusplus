package com.opencode.mobile

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import com.opencode.mobile.service.ConnectionService
import com.opencode.mobile.ui.components.KaTeXWarmUp
import com.opencode.mobile.ui.nav.AppRoot
import com.opencode.mobile.ui.nav.PendingRoute
import com.opencode.mobile.ui.theme.OcColors
import com.opencode.mobile.ui.theme.OpenCodeTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝也不阻塞使用 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleLaunchIntent(intent)

        // 必须显式指定 light，不能用无参的 enableEdgeToEdge()。
        // 无参版本按系统深色模式自动选图标颜色 —— 我们界面是纯浅色（没有 values-night），
        // 用户手机开着深色模式时它会把系统栏图标刷成白色，在浅色栏上就看不见了。
        val transparentBar = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(
            statusBarStyle = transparentBar,
            navigationBarStyle = transparentBar,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            OpenCodeTheme {
                Surface(color = OcColors.Bg) {
                    // 这里不加 systemBars padding：那会让状态栏区域露出页面底色，
                    // 而且漏掉挖孔的左右安全区（横屏时摄像头在侧边）。
                    // 顶栏 / 底栏 / 输入框各自吃自己的 inset，策略见 ui/components/Insets.kt。
                    AppRoot()
                }
            }
        }

        // 首屏画完再预热，不能让预热抢走第一帧。
        // 目的：把"进程首次初始化 WebView 引擎 + 首次解析 KaTeX 资源"这笔主线程开销，
        // 从用户打开含公式对话的那一帧，挪到还没进聊天的时候。
        window.decorView.post { KaTeXWarmUp.warm(this) }
    }

    /**
     * 通知点进来时必须带上 sessionId（launchMode 是 singleTask，
     * Activity 已在栈里时走的是这里而不是 onCreate）—— 少了这个覆写，
     * 点通知只会把 App 拉到前台，停在原来的页面。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        val sessionId = intent?.getStringExtra(ConnectionService.EXTRA_SESSION_ID)
        if (!sessionId.isNullOrBlank()) PendingRoute.request(sessionId)
    }
}
