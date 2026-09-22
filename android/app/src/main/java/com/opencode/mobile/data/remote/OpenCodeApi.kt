package com.opencode.mobile.data.remote

import com.opencode.mobile.data.remote.dto.ActiveSessionsDto
import com.opencode.mobile.data.remote.dto.FilePageDto
import com.opencode.mobile.data.remote.dto.FormPageDto
import com.opencode.mobile.data.remote.dto.FormReplyRequest
import com.opencode.mobile.data.remote.dto.InfoDto
import com.opencode.mobile.data.remote.dto.LocationDto
import com.opencode.mobile.data.remote.dto.MessagePageDto
import com.opencode.mobile.data.remote.dto.ModelPageDto
import com.opencode.mobile.data.remote.dto.ModelWrapDto
import com.opencode.mobile.data.remote.dto.PermissionPageDto
import com.opencode.mobile.data.remote.dto.PermissionReplyRequest
import com.opencode.mobile.data.remote.dto.PromptRequest
import com.opencode.mobile.data.remote.dto.ProviderPageDto
import com.opencode.mobile.data.remote.dto.SessionPageDto
import com.opencode.mobile.data.remote.dto.SessionWrapDto
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * 桌面版 opencode v2 的 HTTP 接口。
 *
 * 依据 docs/reference/opencode-v2-openapi.json（从运行中的 2.0.11 实抓）。
 * 所有路径带 `api/` 前缀；列表类响应统一包一层 {location, data, cursor}。
 *
 * baseUrl 是占位值，真实主机由 EndpointInterceptor 重写。
 * 路径一律不带前导斜杠，避免 Retrofit 丢掉占位 baseUrl 的路径。
 */
interface OpenCodeApi {

    /** 探活。返回 Unit 不解析响应体，服务端改字段也不影响结论。 */
    @GET("api/info")
    suspend fun ping()

    @GET("api/info")
    suspend fun info(): InfoDto

    /* ── 位置 / 项目 ─────────────────────────────────────────────────── */

    @GET("api/location")
    suspend fun location(): LocationDto

    /**
     * 项目列表。
     *
     * ⚠️ 响应形状**两种都见过**：契约里写的是裸数组，实抓某些版本是 `{location,data}`。
     * 所以这里不绑死 DTO，拿原始 JsonElement 交给仓库层两种都认（见 WorkspaceRepository）。
     * 绑死形状的代价是：解析一失败就被静默吞掉，表现就是"工作区刷新了没用"。
     */
    @GET("api/project")
    suspend fun listProjects(): JsonElement

    /* ── 会话 ────────────────────────────────────────────────────────── */

    @GET("api/session")
    suspend fun listSessions(): SessionPageDto

    @POST("api/session")
    suspend fun createSession(@Body body: JsonObject): SessionWrapDto

    @GET("api/session/{sessionID}")
    suspend fun session(@Path("sessionID") sessionID: String): SessionWrapDto

    @PATCH("api/session/{sessionID}")
    suspend fun updateSession(
        @Path("sessionID") sessionID: String,
        @Body body: Map<String, String>,
    ): SessionWrapDto

    @DELETE("api/session/{sessionID}")
    suspend fun deleteSession(@Path("sessionID") sessionID: String)

    /** { data: { sessionID: ... } }，data 是对象不是数组。 */
    @GET("api/session/active")
    suspend fun activeSessions(): ActiveSessionsDto

    /* ── 消息 ────────────────────────────────────────────────────────── */

    /**
     * 会话消息（**分页**）。
     *
     * 不传 limit 时服务端按自己的默认页大小返回，长会话就只剩最后一屏 ——
     * 表现就是"往上翻到一定程度再也翻不动"。所以这里**必须显式传 limit**，
     * 并用 [cursor] 往前翻（响应里的 cursor.next = 更早那一页的方向）。
     *
     * ⚠️ limit 的上限是 **200**（v2.0.12 实测：`limit=500` 直接返回
     * `400 InvalidRequestError: Expected a value less than or equal to 200`）。
     */
    @GET("api/session/{sessionID}/message")
    suspend fun listMessages(
        @Path("sessionID") sessionID: String,
        @Query("limit") limit: Int? = null,
        @Query("cursor") cursor: String? = null,
        @Query("order") order: String? = null,
    ): MessagePageDto

    /**
     * 下发指令。
     *
     * body 只认 text —— v2 的 prompt 不接受 parts，也不接受 model（模型走 selectSessionModel）。
     * 参数类型必须用具体的 PromptRequest，不能写 Map<String, JsonElement>：
     * Kotlin 的 Map 值类型协变，编到 JVM 会带 `? extends`，Retrofit 直接拒绝。
     */
    @POST("api/session/{sessionID}/prompt")
    suspend fun prompt(
        @Path("sessionID") sessionID: String,
        @Body body: PromptRequest,
    )

    /** 指定本会话使用的模型。body 已核对：{model:{id, providerID, variant?}} */
    @POST("api/session/{sessionID}/model")
    suspend fun selectSessionModel(
        @Path("sessionID") sessionID: String,
        @Body body: JsonObject,
    )

    @POST("api/session/{sessionID}/interrupt")
    suspend fun interrupt(@Path("sessionID") sessionID: String)

    /* ── 权限 ────────────────────────────────────────────────────────── */

    @GET("api/session/{sessionID}/permission")
    suspend fun sessionPermissions(@Path("sessionID") sessionID: String): PermissionPageDto

    @GET("api/permission/request")
    suspend fun pendingPermissions(): PermissionPageDto

    @POST("api/session/{sessionID}/permission/{requestID}/reply")
    suspend fun replyPermission(
        @Path("sessionID") sessionID: String,
        @Path("requestID") requestID: String,
        @Body body: PermissionReplyRequest,
    )

    /* ── 提问（form） ────────────────────────────────────────────────── */

    /*
     * agent 跑到一半向用户提的问题，v2 叫 form：一组字段，字段可以带推荐选项。
     *
     * 全局那条同样带 `location[directory]`（和 permission 一样的坑），
     * 不传只覆盖服务端默认目录 —— 所以提问也要按会话再补一遍。
     */

    @GET("api/form")
    suspend fun pendingForms(): FormPageDto

    @GET("api/session/{sessionID}/form")
    suspend fun sessionForms(@Path("sessionID") sessionID: String): FormPageDto

    /** 提交答案。body = { answer: { 字段key: 值 } }。 */
    @POST("api/session/{sessionID}/form/{formID}/reply")
    suspend fun replyForm(
        @Path("sessionID") sessionID: String,
        @Path("formID") formID: String,
        @Body body: FormReplyRequest,
    )

    /** 跳过：告诉电脑端"这题不答了"，不然它会一直等。 */
    @DELETE("api/session/{sessionID}/form/{formID}")
    suspend fun cancelForm(
        @Path("sessionID") sessionID: String,
        @Path("formID") formID: String,
    )

    /* ── 文件 ────────────────────────────────────────────────────────── */

    /*
     * fs 三兄弟都是 **location 沙箱接口**。
     *
     * location 是 deepObject + explode 的 query，wire 上是 `?location[directory]=<绝对路径>`。
     * 服务端会校验目标路径是否落在 location 内，不在就 500
     * （日志 "Path escapes the location"）。所以：
     *   - 读文件 → location 传**文件的父目录**
     *   - 列目录 → location 传**被列的那个目录**
     *   - 搜索   → location 传搜索根目录
     * 不传的话服务端用它自己的默认工作区，工作区外的文件一律 500。
     */

    /** path 可以是绝对路径，也可以是相对 [location] 的路径；不传则列 location 本身。 */
    @GET("api/fs/list")
    suspend fun listFiles(
        @Query("path") path: String? = null,
        @Query("location[directory]") location: String? = null,
    ): FilePageDto

    /**
     * 读文件内容 —— 返回的是**原始字节**（不是 JSON，也不是 base64）。
     * 所以这里用 @Streaming，大文件可以边下边写，不必整份进内存。
     *
     * path 需调用方预处理：反斜杠转正斜杠、各段做 URL 编码，再配 encoded=true 原样拼进 URL。
     */
    @Streaming
    @GET("api/fs/read/{path}")
    suspend fun readFile(
        @Path(value = "path", encoded = true) path: String,
        @Query("location[directory]") location: String? = null,
    ): ResponseBody

    @GET("api/fs/find")
    suspend fun findFiles(
        @Query("query") query: String,
        @Query("type") type: String? = null,
        @Query("limit") limit: String? = null,
        @Query("location[directory]") location: String? = null,
    ): FilePageDto

    /* ── 模型 / provider ─────────────────────────────────────────────── */

    @GET("api/model")
    suspend fun models(): ModelPageDto

    @GET("api/model/default")
    suspend fun defaultModel(): ModelWrapDto

    @GET("api/provider")
    suspend fun providers(): ProviderPageDto
}
