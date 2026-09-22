package com.opencode.mobile.ui.questions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.opencode.mobile.R
import com.opencode.mobile.data.remote.dto.FormDto
import com.opencode.mobile.data.remote.dto.FormFieldDto
import com.opencode.mobile.ui.components.PromptButton
import com.opencode.mobile.ui.theme.OcColors
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** "其他（自己填）"这个选项的哨兵值 —— 不能和真实选项值撞车，所以带下划线。 */
private const val OTHER = "__other__"

/**
 * agent 提问的作答弹窗 —— 和权限审批一样是**全局**的，盖在任何页面之上。
 *
 * 电脑上 agent 问你问题时会在 TUI 里弹个选择框；手机上不接住这件事，
 * 它就一直停在那一句上等你，而你看不见屏幕。
 *
 * 通知栏那条**只负责喊你回来**（见 ConnectionService.postQuestionNotification）：
 * 有选项的问题塞不进通知栏，硬塞只会让人盲选，作答必须回到 App 里做。
 */
@Composable
fun QuestionPromptDialog(
    form: FormDto,
    index: Int,
    total: Int,
    /** 返回 null = 成功；非 null = 要显示在弹窗里的错误文案。 */
    onSubmit: suspend (Map<String, JsonElement>) -> String?,
    onSkip: suspend () -> String?,
    onLater: () -> Unit,
) {
    Dialog(
        onDismissRequest = onLater,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        var busy by remember { mutableStateOf(false) }
        var error by remember(form.id) { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()

        QuestionCard(
            form = form,
            index = index,
            total = total,
            busy = busy,
            error = error,
            onSubmit = { answer ->
                scope.launch {
                    busy = true
                    error = onSubmit(answer)
                    busy = false
                }
            },
            onSkip = {
                scope.launch {
                    busy = true
                    error = onSkip()
                    busy = false
                }
            },
            onLater = onLater,
        )
    }
}

@Composable
private fun QuestionCard(
    form: FormDto,
    index: Int,
    total: Int,
    busy: Boolean,
    error: String?,
    onSubmit: (Map<String, JsonElement>) -> Unit,
    onSkip: () -> Unit,
    onLater: () -> Unit,
) {
    val fields = form.visibleFields

    /** 单选结果 / 自由文本，key = 字段 key。 */
    val single = remember(form.id) {
        mutableStateMapOf<String, String>().apply {
            fields.forEach { field ->
                val d = field.default
                if (d is JsonPrimitive && d.isString) put(field.key, d.content)
            }
        }
    }
    /** 多选结果。整体替换而不是就地改，这样 SnapshotStateMap 一定会通知重组。 */
    val multi = remember(form.id) { mutableStateMapOf<String, List<String>>() }
    /** "其他"那一栏自己填的内容。 */
    val otherText = remember(form.id) { mutableStateMapOf<String, String>() }

    fun answerOf(field: FormFieldDto): JsonElement? = when (field.type) {
        "multiselect" -> {
            val values = multi[field.key].orEmpty()
                .map { if (it == OTHER) otherText[field.key].orEmpty() else it }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (values.isEmpty()) null else JsonArray(values.map { JsonPrimitive(it) })
        }

        "boolean" -> single[field.key]?.let { JsonPrimitive(it == "true") }

        "number" -> single[field.key]?.trim()?.takeIf { it.isNotEmpty() }
            ?.toDoubleOrNull()?.let { JsonPrimitive(it) }

        "integer" -> single[field.key]?.trim()?.takeIf { it.isNotEmpty() }
            ?.toLongOrNull()?.let { JsonPrimitive(it) }

        else -> {
            val chosen = single[field.key]
            val text = if (chosen == OTHER) otherText[field.key] else chosen
            text?.trim()?.takeIf { it.isNotEmpty() }?.let { JsonPrimitive(it) }
        }
    }

    val missingKeys = fields.filter { it.required && answerOf(it) == null }.map { it.key }.toSet()
    val canSubmit = missingKeys.isEmpty() && !busy

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OcColors.Ink.copy(alpha = 0.34f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(OcColors.Surface)
                .border(1.dp, OcColors.Line, RoundedCornerShape(20.dp)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(OcColors.Surface2)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.ChatBubbleOutline,
                    contentDescription = null,
                    tint = OcColors.Ink2,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.question_prompt_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = OcColors.Ink,
                    modifier = Modifier.weight(1f),
                )
                if (total > 1) {
                    Text(
                        text = stringResource(R.string.question_prompt_queue, index, total),
                        style = MaterialTheme.typography.labelSmall,
                        color = OcColors.Ink2,
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    text = stringResource(R.string.question_prompt_later),
                    style = MaterialTheme.typography.labelSmall,
                    color = OcColors.Ink2,
                    modifier = Modifier.clickable(enabled = !busy) { onLater() },
                )
            }

            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = form.title.ifBlank { stringResource(R.string.question_prompt_title) },
                    style = MaterialTheme.typography.titleLarge,
                    color = OcColors.Ink,
                )

                fields.forEach { field ->
                    FieldBlock(
                        field = field,
                        single = single,
                        multi = multi,
                        otherText = otherText,
                        missing = field.key in missingKeys,
                        enabled = !busy,
                    )
                }

                Text(
                    text = stringResource(R.string.question_prompt_blocking),
                    style = MaterialTheme.typography.bodySmall,
                    color = OcColors.Ink2,
                )

                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = OcColors.Warn,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PromptButton(
                        text = stringResource(R.string.question_prompt_skip),
                        filled = false,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        onClick = onSkip,
                    )
                    PromptButton(
                        text = stringResource(R.string.question_prompt_submit),
                        filled = true,
                        enabled = canSubmit,
                        showProgress = busy,
                        modifier = Modifier.weight(2f),
                        onClick = {
                            val answer = fields.mapNotNull { field ->
                                answerOf(field)?.let { field.key to it }
                            }.toMap()
                            onSubmit(answer)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FieldBlock(
    field: FormFieldDto,
    single: SnapshotStateMap<String, String>,
    multi: SnapshotStateMap<String, List<String>>,
    otherText: SnapshotStateMap<String, String>,
    missing: Boolean,
    enabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = field.title.ifBlank { field.key },
                style = MaterialTheme.typography.titleMedium,
                color = OcColors.Ink,
                modifier = Modifier.weight(1f),
            )
            if (field.required) {
                Text(
                    text = stringResource(R.string.question_prompt_required),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (missing) OcColors.Warn else OcColors.Ink3,
                )
            }
        }

        if (field.description.isNotBlank()) {
            Text(
                text = field.description,
                style = MaterialTheme.typography.bodySmall,
                color = OcColors.Ink2,
            )
        }

        when {
            field.type == "boolean" -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChoiceChip(
                    label = "是",
                    selected = single[field.key] == "true",
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    onClick = { single[field.key] = "true" },
                )
                ChoiceChip(
                    label = "否",
                    selected = single[field.key] == "false",
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    onClick = { single[field.key] = "false" },
                )
            }

            field.options.isNotEmpty() -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    field.options.forEach { option ->
                        ChoiceChip(
                            label = option.label.ifBlank { option.value },
                            description = option.description,
                            selected = if (field.type == "multiselect") {
                                option.value in multi[field.key].orEmpty()
                            } else {
                                single[field.key] == option.value
                            },
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                if (field.type == "multiselect") {
                                    val current = multi[field.key].orEmpty()
                                    multi[field.key] = if (option.value in current) {
                                        current - option.value
                                    } else {
                                        current + option.value
                                    }
                                } else {
                                    single[field.key] = option.value
                                }
                            },
                        )
                    }

                    // 允许自定义时多给一栏：推荐选项覆盖不到的情况很常见
                    if (field.custom) {
                        val chosen = if (field.type == "multiselect") {
                            OTHER in multi[field.key].orEmpty()
                        } else {
                            single[field.key] == OTHER
                        }
                        ChoiceChip(
                            label = stringResource(R.string.question_field_other),
                            selected = chosen,
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                if (field.type == "multiselect") {
                                    val current = multi[field.key].orEmpty()
                                    multi[field.key] = if (OTHER in current) current - OTHER else current + OTHER
                                } else {
                                    single[field.key] = OTHER
                                }
                            },
                        )
                        if (chosen) {
                            FreeTextField(
                                value = otherText[field.key].orEmpty(),
                                onValueChange = { otherText[field.key] = it },
                                placeholder = stringResource(R.string.question_prompt_custom),
                                numeric = false,
                                enabled = enabled,
                            )
                        }
                    }
                }
            }

            else -> FreeTextField(
                value = single[field.key].orEmpty(),
                onValueChange = { single[field.key] = it },
                placeholder = field.placeholder.ifBlank { field.title.ifBlank { field.key } },
                numeric = field.type == "number" || field.type == "integer",
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun ChoiceChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String = "",
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) OcColors.Ink else OcColors.Surface)
            .border(
                1.dp,
                if (selected) OcColors.Ink else OcColors.Line,
                RoundedCornerShape(12.dp),
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) OcColors.Surface else OcColors.Ink,
        )
        if (description.isNotBlank()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) OcColors.Surface.copy(alpha = 0.7f) else OcColors.Ink2,
            )
        }
    }
}

@Composable
private fun FreeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    numeric: Boolean,
    enabled: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp, max = 110.dp),
        placeholder = {
            Text(placeholder, style = MaterialTheme.typography.bodyMedium)
        },
        textStyle = MaterialTheme.typography.bodyMedium,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
        ),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = OcColors.Line,
            unfocusedBorderColor = OcColors.Line2,
            focusedContainerColor = OcColors.Surface2,
            unfocusedContainerColor = OcColors.Surface2,
            // 显式钉死颜色：不能依赖 MaterialTheme 默认值（见 ChatScreen 输入框的注释）
            focusedTextColor = OcColors.Ink,
            unfocusedTextColor = OcColors.Ink,
            cursorColor = OcColors.Ink,
            focusedPlaceholderColor = OcColors.Ink3,
            unfocusedPlaceholderColor = OcColors.Ink3,
        ),
    )
}
