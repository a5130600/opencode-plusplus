package com.opencode.mobile.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * 对齐桌面版 opencode v2.0.11 的真实契约。
 * 依据：docs/reference/opencode-v2-openapi.json 与 opencode-v2-event-samples.txt（均从运行中的服务实抓）。
 *
 * 注意：npm 上的 opencode-ai 是 v1，路径无 /api 前缀、响应是裸数组，与这里完全不兼容。
 *
 * 通用约定：字段一律给默认值；未知字段靠 ignoreUnknownKeys 忽略；不确定的结构用 JsonElement 兜住。
 */

/* ── 通用 ─────────────────────────────────────────────────────────────── */

@Serializable
data class LocationDto(
    val directory: String = "",
    val project: ProjectRefDto? = null,
)

@Serializable
data class ProjectRefDto(
    val id: String = "",
    val directory: String = "",
    val canonical: String = "",
)

@Serializable
data class CursorDto(
    val previous: String? = null,
    val next: String? = null,
)

@Serializable
data class TimeDto(
    val created: Long? = null,
    val updated: Long? = null,
    val idle: Long? = null,
    val start: Long? = null,
    val end: Long? = null,
)

@Serializable
data class TokenUsageDto(
    val input: Long = 0,
    val output: Long = 0,
    val reasoning: Long = 0,
    val cache: TokenCacheDto? = null,
)

@Serializable
data class TokenCacheDto(val read: Long = 0, val write: Long = 0)

@Serializable
data class StructuredErrorDto(
    val type: String = "",
    val message: String = "",
    val status: Int? = null,
)

/** 模型引用。会话与 step 事件里都用它。 */
@Serializable
data class ModelRefDto(
    val id: String = "",
    @SerialName("providerID") val providerID: String = "",
    val variant: String? = null,
)

/* ── 服务信息 / 位置 / 项目 ───────────────────────────────────────────── */

/** `GET /api/info`。也是探活端点，不带信封。 */
@Serializable
data class InfoDto(
    val version: String = "",
    val pid: Long = 0,
    val urls: List<String> = emptyList(),
    val paths: JsonElement? = null,
)

/**
 * `GET /api/project` 的条目。
 *
 * ⚠️ 项目对象里**没有 `directory` 字段** —— 契约里的字段是 `id / canonical / vcs /
 * name / icon / commands / time / sandboxes`。项目路径叫 **canonical**。
 * 以前按 `directory` 取，取到空串，于是整份项目列表被当成无效项丢掉 ——
 * 这正是"电脑上已经有的工作目录刷不出来"的原因。
 */
@Serializable
data class ProjectDto(
    val id: String = "",
    val directory: String = "",
    val canonical: String = "",
    val name: String? = null,
    val vcs: String? = null,
) {
    /** 项目目录。canonical 是真值，directory 只是某些版本会带的同义字段。 */
    val pathOrEmpty: String get() = canonical.ifBlank { directory }
}

/* ── 会话 ─────────────────────────────────────────────────────────────── */

@Serializable
data class SessionDto(
    val id: String = "",
    @SerialName("projectID") val projectID: String = "",
    val slug: String? = null,
    val version: String? = null,
    val title: String = "",
    val agent: String? = null,
    val model: ModelRefDto? = null,
    val subpath: String? = null,
    val cost: Double? = null,
    val tokens: TokenUsageDto? = null,
    /** succeeded | failed | interrupted */
    val outcome: String? = null,
    val location: LocationDto? = null,
    val time: TimeDto? = null,
)

@Serializable
data class SessionPageDto(
    val location: LocationDto? = null,
    val data: List<SessionDto> = emptyList(),
    val cursor: CursorDto? = null,
)

@Serializable
data class SessionWrapDto(
    val location: LocationDto? = null,
    val data: SessionDto = SessionDto(),
)

/** `GET /api/session/active` → { data: { sessionID: ... } }，是 map 不是数组。 */
@Serializable
data class ActiveSessionsDto(
    val location: LocationDto? = null,
    val data: JsonElement? = null,
)

/* ── 消息 ─────────────────────────────────────────────────────────────── */

/**
 * 消息是按 `type` 判别的联合，共同字段只有 id / time / type / metadata。
 * 各type的专属字段平铺在顶层（**不是**放在 payload 里 —— 这点和 prompt 的返回不同）。
 */
@Serializable
data class MessageDto(
    val id: String = "",
    val type: String = "",
    val time: TimeDto? = null,
    val metadata: JsonElement? = null,

    /** user */
    val text: String? = null,
    val files: List<JsonElement> = emptyList(),
    val agents: List<JsonElement> = emptyList(),
    val skills: List<JsonElement> = emptyList(),

    /** assistant */
    val agent: String? = null,
    val model: ModelRefDto? = null,
    val content: List<ContentBlockDto> = emptyList(),
    val finish: String? = null,
    val rawFinish: String? = null,
    val cost: Double? = null,
    val tokens: TokenUsageDto? = null,
    val error: StructuredErrorDto? = null,
    val retry: JsonElement? = null,

    /** idle */
    val outcome: String? = null,
)

/** assistant.content 的元素，相当于 v1 的 part。 */
@Serializable
data class ContentBlockDto(
    /** text | reasoning | tool */
    val type: String = "",
    val text: String? = null,
    val state: BlockStateDto? = null,

    /** tool 专属 */
    val id: String? = null,
    val name: String? = null,
    val executed: Boolean? = null,
    val time: TimeDto? = null,
)

/** 工具状态。判别字段是 status（streaming / running / completed / error）。 */
@Serializable
data class BlockStateDto(
    val status: String? = null,
    val title: String? = null,
    val input: JsonElement? = null,
    val output: JsonElement? = null,
    val error: JsonElement? = null,
    val raw: String? = null,
)

@Serializable
data class MessagePageDto(
    val location: LocationDto? = null,
    val data: List<MessageDto> = emptyList(),
    val cursor: CursorDto? = null,
)

/** prompt 的返回：{ data: { id, sessionID, time, type:"user", payload:{text}, delivery } } */
@Serializable
data class InboxUserDto(
    val id: String = "",
    @SerialName("sessionID") val sessionID: String = "",
    val type: String = "user",
    val payload: InboxPayloadDto? = null,
    val delivery: String? = null,
    val time: TimeDto? = null,
)

@Serializable
data class InboxPayloadDto(val text: String = "")

/**
 * 下发指令的请求体。
 *
 * ⚠️ 不要写成 `Map<String, JsonElement>` 当 @Body ——
 * Kotlin 的 Map 值类型是协变的，编到 JVM 上变成 `Map<String, ? extends JsonElement>`，
 * 而 Retrofit 明确拒绝带通配符的参数类型，会在调用时抛
 * "Parameter type must not include a type variable or wildcard"。
 * （String 是 final 类所以 `Map<String,String>` 没事，JsonElement 是抽象类就中招了。）
 */
/**
 * prompt 的附件（`files[]` 的元素）。
 *
 * 只有 `uri` 是必填的，而且它必须是 `file://` 形式的**绝对**路径 URL ——
 * 服务端受理前会先把文件读一遍，读不到就整条 prompt 判 400。
 * 拼法见 core/Paths.kt 的 [com.opencode.mobile.core.attachmentUri]。
 */
@Serializable
data class FileAttachmentDto(
    val uri: String,
    val name: String = "",
)

@Serializable
data class PromptRequest(
    val text: String,
    /** 附件。@agent / @skill 结构没用上，先用 JsonElement 兜住。 */
    val files: List<FileAttachmentDto> = emptyList(),
    val agents: List<JsonElement> = emptyList(),
    val skills: List<JsonElement> = emptyList(),
    /** steer | queue。steer = 插到当前执行里，queue = 排队。 */
    val delivery: String? = null,
    val resume: Boolean? = null,
)

/* ── 权限 ─────────────────────────────────────────────────────────────── */

/**
 * v2 的权限请求字段与 v1 差别较大：是 action / resources / message，
 * 没有 v1 的 type / title / pattern。
 */
@Serializable
data class PermissionDto(
    val id: String = "",
    @SerialName("sessionID") val sessionID: String = "",
    val action: String = "",
    val resources: List<String> = emptyList(),
    val save: List<String> = emptyList(),
    val metadata: JsonElement? = null,
    val source: JsonElement? = null,
    val message: String = "",
) {
    /** 展示用的一行描述。 */
    val summary: String
        get() = message.ifBlank { action + resources.joinToString(" ", " ", "") }

    /**
     * 通知 / 弹窗里的完整正文：一句话 + 涉及的东西。
     *
     * 只给 summary 的话，像 bash 这类请求会变成"bash"两个字 ——
     * 用户根本不知道要批准的是什么命令，等于让人盲签。
     */
    val detail: String
        get() = buildString {
            append(message.ifBlank { action }.ifBlank { "需要你的确认" })
            if (resources.isNotEmpty()) {
                append('\n')
                append(resources.joinToString("\n"))
            }
        }
}

@Serializable
data class PermissionPageDto(
    val location: LocationDto? = null,
    val data: List<PermissionDto> = emptyList(),
)

/** 回复体字段名是 `decision`（v1 叫 `response`），取值枚举一致。 */
@Serializable
data class PermissionReplyRequest(
    val decision: String,
    val message: String? = null,
)

/* ── 提问（form） ─────────────────────────────────────────────────────── */

/**
 * agent 跑到一半向用户提的问题，v2 里叫 **form**：一组字段，字段可以带推荐选项。
 *
 * 这就是"问你一些事项、给你几个推荐选项让你选"那一类交互 ——
 * 电脑上它在 TUI 里弹一个选择框，手机上必须同样能显示并作答，
 * 否则 agent 会一直停在等你回答的那一步。
 */
@Serializable
data class FormDto(
    val id: String = "",
    @SerialName("sessionID") val sessionID: String = "",
    val title: String = "",
    val fields: List<FormFieldDto> = emptyList(),
) {
    /** 需要用户填的字段（hidden 的不显示）。 */
    val visibleFields: List<FormFieldDto> get() = fields.filterNot { it.hidden }
}

/**
 * 一个字段。契约里它是 string / number / integer / boolean / multiselect / external
 * 六种类型的联合，但**共有字段占绝大多数**，所以这里用扁平结构 + [type] 判别：
 * 解析端少一层分派，界面端也只需要按 type 分支。
 */
@Serializable
data class FormFieldDto(
    val key: String = "",
    val type: String = "",
    val title: String = "",
    val description: String = "",
    val required: Boolean = false,
    val hidden: Boolean = false,
    /** 推荐选项。空表示自由输入。 */
    val options: List<FormOptionDto> = emptyList(),
    /** 允许在推荐选项之外自己填（界面上就是"其他"那一栏）。 */
    val custom: Boolean = false,
    /** 默认值：string | number | boolean | string[]，用 JsonElement 兜住。 */
    val default: JsonElement? = null,
    val placeholder: String = "",
)

@Serializable
data class FormOptionDto(
    val value: String = "",
    val label: String = "",
    val description: String = "",
)

@Serializable
data class FormPageDto(
    val location: LocationDto? = null,
    val data: List<FormDto> = emptyList(),
)

/** 提交答案：{ answer: { 字段key: 值 } }。值的类型随字段走，所以用 JsonElement。 */
@Serializable
data class FormReplyRequest(
    val answer: Map<String, JsonElement> = emptyMap(),
)

/* ── 文件 ─────────────────────────────────────────────────────────────── */

/**
 * `/api/fs/list` 的条目：只有 path 与 type。
 * path 是**相对 location.directory 的路径**，目录带尾部分隔符（如 `.android\`）。
 */
@Serializable
data class FileEntryDto(
    val path: String = "",
    /** file | directory */
    val type: String = "file",
) {
    val isDirectory: Boolean get() = type == "directory"

    /** 取末段做显示名，去掉尾部分隔符。 */
    val name: String
        get() = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
}

@Serializable
data class FilePageDto(
    val location: LocationDto? = null,
    val data: List<FileEntryDto> = emptyList(),
)

/* ── 模型 / provider ──────────────────────────────────────────────────── */

@Serializable
data class ModelDto(
    val id: String = "",
    @SerialName("modelID") val modelID: String = "",
    @SerialName("providerID") val providerID: String = "",
    val family: String? = null,
    val name: String = "",
    val capabilities: ModelCapabilitiesDto? = null,
    val variants: List<ModelVariantDto> = emptyList(),
)

@Serializable
data class ModelCapabilitiesDto(
    val tools: Boolean = false,
    val input: List<String> = emptyList(),
    val output: List<String> = emptyList(),
)

@Serializable
data class ModelVariantDto(val id: String = "")

@Serializable
data class ModelPageDto(
    val location: LocationDto? = null,
    val data: List<ModelDto> = emptyList(),
)

@Serializable
data class ModelWrapDto(
    val location: LocationDto? = null,
    val data: ModelDto? = null,
)

@Serializable
data class ProviderDto(
    val id: String = "",
    @SerialName("integrationID") val integrationID: String? = null,
    val name: String = "",
    /** enabled | auto | disabled */
    val activation: String = "",
)

@Serializable
data class ProviderPageDto(
    val location: LocationDto? = null,
    val data: List<ProviderDto> = emptyList(),
)

/* ── 事件（SSE /api/event）───────────────────────────────────────────── */

/**
 * v2 事件信封。注意负载字段叫 **data**（v1 叫 properties）。
 * durable 是事件溯源信息，seq可用于判断丢包。
 */
@Serializable
data class EventDto(
    val id: String = "",
    val created: Long? = null,
    val type: String = "",
    val location: LocationDto? = null,
    val metadata: JsonElement? = null,
    val data: JsonElement? = null,
    val durable: DurableDto? = null,
)

@Serializable
data class DurableDto(
    @SerialName("aggregateID") val aggregateID: String = "",
    val seq: Long = 0,
    val version: Long = 0,
)

@Serializable
data class SessionCreatedData(
    @SerialName("sessionID") val sessionID: String = "",
    val slug: String? = null,
    val version: String? = null,
    @SerialName("projectID") val projectID: String? = null,
    val subpath: String? = null,
    val title: String = "",
    val location: LocationDto? = null,
)

@Serializable
data class SessionIdData(
    @SerialName("sessionID") val sessionID: String = "",
)

@Serializable
data class InboxEnqueuedData(
    @SerialName("inboxID") val inboxID: String = "",
    @SerialName("sessionID") val sessionID: String = "",
    val item: JsonElement? = null,
)

/** step.started / text.* / step.streamed / step.ended 共用的字段。 */
@Serializable
data class StepData(
    @SerialName("sessionID") val sessionID: String = "",
    @SerialName("assistantMessageID") val assistantMessageID: String? = null,
    /** 同一 step 内第几段文本 */
    val ordinal: Int? = null,
    /** text.delta 的增量 */
    val delta: String? = null,
    /** text.ended 的完整文本 */
    val text: String? = null,
    /** step.started */
    val agent: String? = null,
    val model: ModelRefDto? = null,
    val started: Long? = null,
    /** step.ended */
    val finish: String? = null,
    val rawFinish: String? = null,
    val cost: Double? = null,
    val tokens: TokenUsageDto? = null,
)

@Serializable
data class ErrorEventData(
    @SerialName("sessionID") val sessionID: String = "",
    val error: StructuredErrorDto? = null,
)

@Serializable
data class UsageEventData(
    @SerialName("sessionID") val sessionID: String = "",
    val cost: Double? = null,
    val tokens: TokenUsageDto? = null,
)
