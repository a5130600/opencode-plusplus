package com.opencode.mobile.data.remote

import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 当前生效的设备端点。
 *
 * 我们不使用 Retrofit 的 baseUrl —— 因为支持多台电脑，baseUrl 是运行时才确定的。
 * Retrofit 用一个占位 baseUrl（http://localhost/），真正的主机由 [EndpointInterceptor]
 * 在发请求前重写。这样一套 Retrofit 实例就能服务所有设备，不必每切一次设备重建。
 */
data class ActiveEndpoint(
    val deviceId: String,
    val scheme: String,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
) {
    val baseUrl: String get() = "$scheme://$host:$port/"

    fun httpUrl(): HttpUrl = HttpUrl.Builder()
        .scheme(scheme)
        .host(host)
        .port(port)
        .build()

    val hasAuth: Boolean get() = password.isNotEmpty()

    /** 供 UI 展示，绝不带密码。 */
    val displayAddress: String get() = "$host:$port"
}

@Singleton
class ActiveEndpointHolder @Inject constructor() {
    @Volatile
    var endpoint: ActiveEndpoint? = null
        private set

    fun set(value: ActiveEndpoint?) {
        endpoint = value
    }

    fun require(): ActiveEndpoint =
        endpoint ?: throw IOException("还没有选择设备，请先在设置里添加并连接一台电脑")
}

/** 把请求重写到当前生效设备的主机上，path / query 原样保留。 */
@Singleton
class EndpointInterceptor @Inject constructor(
    private val holder: ActiveEndpointHolder,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val active = holder.require()
        val original = chain.request()
        val rewritten = original.url.newBuilder()
            .scheme(active.scheme)
            .host(active.host)
            .port(active.port)
            .build()
        return chain.proceed(original.newBuilder().url(rewritten).build())
    }
}

/** HTTP Basic 鉴权（对应 OPENCODE_SERVER_PASSWORD）。 */
@Singleton
class BasicAuthInterceptor @Inject constructor(
    private val holder: ActiveEndpointHolder,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val active = holder.endpoint
        val request = chain.request()
        if (active == null || !active.hasAuth) return chain.proceed(request)
        if (request.header("Authorization") != null) return chain.proceed(request)
        val credential = Credentials.basic(active.username, active.password)
        return chain.proceed(
            request.newBuilder().header("Authorization", credential).build()
        )
    }
}

object JsonFactory {
    val INSTANCE: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        isLenient = true
        coerceInputValues = true
    }

    val CONTENT_TYPE = "application/json; charset=utf-8".toMediaType()
}

/**
 * 统一的 OkHttp 客户端。
 *
 * 超时策略刻意分开：
 *  - callTimeout 给普通 REST 用（30s），避免请求悬挂。
 *  - SSE 走独立的 no-timeout 客户端，否则长连接会被 readTimeout 掐断
 *    —— 这正是文档里提到的"隧道空闲超时"之外，我们自身也会踩的坑。
 */
object HttpClients {
    fun rest(
        endpoint: EndpointInterceptor,
        auth: BasicAuthInterceptor,
        logging: HttpLoggingInterceptor,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(endpoint)
        .addInterceptor(auth)
        .addInterceptor(logging)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun streaming(
        endpoint: EndpointInterceptor,
        auth: BasicAuthInterceptor,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(endpoint)
        .addInterceptor(auth)
        // SSE / 大文件下载：不能设 readTimeout，否则空闲即断
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}
