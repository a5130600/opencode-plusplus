package com.opencode.mobile.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

data class SseEvent(
    val event: String?,
    val data: String,
    val id: String?,
)

/**
 * 手写的 SSE 客户端。
 *
 * 为什么不用 okhttp-sse 那个 artifact：
 *  - 我们只需要 data 行 + 事件分帧，自己解析更可控；
 *  - 更重要的是**取消语义**：读流是阻塞的，协程取消不会中断它，
 *    所以必须在 awaitClose 里显式 close 掉 Response，否则会泄漏连接。
 *    这是长连接在 Android 上最常见的 bug 来源。
 */
@Singleton
class SseClient @Inject constructor(
    @Named(DiNames.STREAMING_CLIENT) private val client: OkHttpClient,
    private val holder: ActiveEndpointHolder,
) {

    companion object {
        /**
         * 事件流路径。**必须带 `api/` 前缀** —— 桌面版 v2 的所有端点都在 /api 下。
         * v1（npm 的 opencode-ai）才是 `global/event`，别再写回去。
         *
         * 这里做成常量是因为：路径是字符串，写错了编译器抓不到，
         * 只会表现成"连接一直重连中"，从现象很难反推。
         */
        const val EVENT_PATH = "api/event"
    }

    fun events(path: String = EVENT_PATH): Flow<SseEvent> = callbackFlow {
        val active = holder.require()
        val url = active.httpUrl().newBuilder()
            .addPathSegments(path.trimStart('/'))
            .build()

        val builder = okhttp3.Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .header("Connection", "keep-alive")
        if (active.hasAuth) {
            builder.header(
                "Authorization",
                Credentials.basic(active.username, active.password),
            )
        }

        val call = client.newCall(builder.build())
        val response = try {
            call.execute()
        } catch (t: Throwable) {
            close(t)
            return@callbackFlow
        }

        if (!response.isSuccessful) {
            response.close()
            close(IOException("SSE 连接失败：HTTP ${response.code}"))
            return@callbackFlow
        }

        val source = response.body?.source()
        if (source == null) {
            response.close()
            close(IOException("SSE 响应为空"))
            return@callbackFlow
        }

        val reader = launch(Dispatchers.IO) {
            try {
                val buffer = StringBuilder()
                var eventName: String? = null
                var lastId: String? = null
                while (isActive) {
                    val line = source.readUtf8Line() ?: break
                    when {
                        // 空行 = 一条事件结束
                        line.isEmpty() -> {
                            if (buffer.isNotEmpty()) {
                                trySend(SseEvent(eventName, buffer.toString(), lastId))
                                buffer.setLength(0)
                            }
                            eventName = null
                        }
                        // 以冒号开头是注释，服务端常用作心跳，忽略即可
                        line.startsWith(":") -> Unit
                        line.startsWith("event:") -> eventName = line.substring(6).trim()
                        line.startsWith("id:") -> lastId = line.substring(3).trim()
                        line.startsWith("retry:") -> Unit
                        line.startsWith("data:") -> {
                            if (buffer.isNotEmpty()) buffer.append('\n')
                            buffer.append(line.substring(5).trim())
                        }
                    }
                }
                close()
            } catch (t: Throwable) {
                close(t)
            }
        }

        // 关键：取消时必须关掉连接，否则阻塞读永远不会退出
        awaitClose {
            reader.cancel()
            runCatching { response.close() }
        }
    }
}

object DiNames {
    const val REST_CLIENT = "rest_client"
    const val STREAMING_CLIENT = "streaming_client"
    const val APPLICATION_SCOPE = "application_scope"
}
