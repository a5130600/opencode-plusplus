package com.opencode.mobile.data.repository

import com.opencode.mobile.core.AppResult
import com.opencode.mobile.core.IoDispatcher
import com.opencode.mobile.core.map
import com.opencode.mobile.core.resultOf
import com.opencode.mobile.data.local.SecureStore
import com.opencode.mobile.data.remote.OpenCodeApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class ModelOption(
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val modelName: String,
    val isServerDefault: Boolean,
    /** 不支持工具调用的模型对 opencode 没用，直接不给选。 */
    val supportsTools: Boolean = true,
) {
    val key: String get() = "$providerId/$modelId"
    val label: String get() = modelName
}

/**
 * 模型目录。
 *
 * v2 走 GET /api/model：一次拿到全部可用模型，每个都带 providerID 与 capabilities。
 * 只保留 tools=true 的 —— opencode 靠工具调用读写文件，不支持工具的模型选了也用不了。
 */
@Singleton
class ModelRepository @Inject constructor(
    private val api: OpenCodeApi,
    private val secure: SecureStore,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    suspend fun list(): AppResult<List<ModelOption>> = withContext(io) {
        val default = runCatching { api.defaultModel().data }.getOrNull()
        val defaultKey = default?.let { "${it.providerID}/${it.modelID}" }

        resultOf { api.models() }.map { page ->
            page.data
                .filter { it.capabilities?.tools != false }
                .map { m ->
                    val providerId = m.providerID.ifBlank { "unknown" }
                    ModelOption(
                        providerId = providerId,
                        providerName = providerId,
                        modelId = m.modelID.ifBlank { m.id },
                        modelName = m.name.ifBlank { m.modelID.ifBlank { m.id } },
                        isServerDefault = "$providerId/${m.modelID.ifBlank { m.id }}" == defaultKey,
                        supportsTools = m.capabilities?.tools != false,
                    )
                }
                .distinctBy { it.key }
                .sortedWith(compareBy({ !it.isServerDefault }, { it.providerName }, { it.modelName }))
        }
    }

    /** 用户显式选过的模型（"provider/model"）。为空表示跟随服务端默认。 */
    var selectedKey: String?
        get() = secure.defaultModel
        set(value) {
            secure.defaultModel = value
        }

    fun selectedOption(options: List<ModelOption>): ModelOption? {
        val key = selectedKey ?: return options.firstOrNull { it.isServerDefault }
        return options.firstOrNull { it.key == key }
            ?: options.firstOrNull { it.isServerDefault }
    }

    /** 切换模型只影响后续消息，不会重跑历史。 */
    fun selectionFor(options: List<ModelOption>): Pair<String?, String?> {
        val option = selectedOption(options)
        return option?.providerId to option?.modelId
    }
}
