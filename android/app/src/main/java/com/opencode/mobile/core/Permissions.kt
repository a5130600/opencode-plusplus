package com.opencode.mobile.core

/**
 * 权限决策的响应值 —— **全工程唯一定义处**。
 *
 * 之前通知栏（ConnectionService）和会话页（SessionRepository）各写了一份常量，
 * 这种重复最危险：改了一处忘了另一处，就会出现"卡片能批、通知批不了"的灵异现象。
 *
 * ⚠️ 这里的取值是按 opencode 文档推测的**最可能正确值**。
 * 联调第一件事就是打开 http://<host>:<port>/doc，核对
 * POST /session/{id}/permissions/{permissionID} 请求体的枚举，
 * 若不一致只改这一个文件。
 */
object PermissionResponses {
    /** 仅本次允许。 */
    const val ALLOW = "once"

    /** 本次会话内始终允许。 */
    const val ALWAYS = "always"

    /** 拒绝。 */
    const val REJECT = "reject"
}
