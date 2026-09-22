package com.opencode.mobile.ui.nav

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 通知 → 界面的单向传话筒。
 *
 * 点通知进来要**直达那条审批所属的会话**，而不是停在默认 Tab 让人自己找。
 * 但通知落在 Activity 上（onCreate / onNewIntent），路由却活在 Compose 里，
 * 两边没有 Navigation 组件可用（这个项目是手写路由），所以用一个进程内的小对象传一次。
 *
 * 只传"要打开哪个会话"，不传整条路由：Activity 那一步拿不到会话标题，
 * 标题由 AppRoot 自己查（见 AppViewModel.titleOf）。
 */
object PendingRoute {

    private val _session = MutableStateFlow<String?>(null)
    val session: StateFlow<String?> = _session.asStateFlow()

    fun request(sessionId: String?) {
        _session.value = sessionId
    }

    fun consume() {
        _session.value = null
    }
}
