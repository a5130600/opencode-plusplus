package com.opencode.mobile.core

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/** 生命周期长于任何 ViewModel 的作用域，用于跑 SSE、缓存清理这类后台工作。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/** 前台服务与 SSE 共用的连接状态机。 */
enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }
