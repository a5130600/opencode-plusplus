package com.opencode.mobile.core

import kotlin.coroutines.cancellation.CancellationException

/**
 * 项目统一的错误封装。
 *
 * 刻意不用 Kotlin 的 `Result`：它在 `suspend` 场景下容易被滥用成"到处 catch"，
 * 这里显式区分 Ok / Err，强制调用方处理失败分支。
 */
sealed interface AppResult<out T> {
    data class Ok<T>(val value: T) : AppResult<T>
    data class Err(val message: String, val cause: Throwable? = null) : AppResult<Nothing>
}

inline fun <T, R> AppResult<T>.map(f: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Ok -> AppResult.Ok(f(value))
    is AppResult.Err -> this
}

val AppResult<*>.isOk: Boolean get() = this is AppResult.Ok

fun <T> AppResult<T>.getOrNull(): T? = (this as? AppResult.Ok)?.value

fun <T> AppResult<T>.errorMessageOrNull(): String? = (this as? AppResult.Err)?.message

/**
 * 包一层 try/catch，但**必须**让 CancellationException 穿透。
 * 吞掉它会让协程取消失效，这是 Android 上最难查的一类 bug。
 */
suspend fun <T> resultOf(block: suspend () -> T): AppResult<T> =
    try {
        AppResult.Ok(block())
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        AppResult.Err(t.message ?: t::class.java.simpleName, t)
    }
