package com.opencode.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.opencode.mobile.ui.theme.OcColors

/**
 * 弹窗里的动作按钮（权限审批 / 提问作答共用）。
 *
 * [hint] 是按钮下方那一行小字 —— 用来解释这个动作的后果，
 * 比如"始终允许"底下那句"同类操作以后不再询问"。别让人盲点。
 */
@Composable
fun PromptButton(
    text: String,
    filled: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    showProgress: Boolean = false,
) {
    val interaction = rememberPress()
    val scale = pressScale(interaction)
    Box(
        modifier = modifier
            .height(if (hint == null) 46.dp else 52.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(if (filled) OcColors.Ink else OcColors.Surface)
            .border(
                1.dp,
                if (filled) OcColors.Ink else OcColors.Line,
                RoundedCornerShape(13.dp),
            )
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) {
                onClick()
            }
            .scale(scale),
        contentAlignment = Alignment.Center,
    ) {
        when {
            showProgress -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = OcColors.Surface,
                strokeWidth = 2.dp,
            )

            hint == null -> Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                color = when {
                    !enabled && filled -> OcColors.Surface.copy(alpha = 0.45f)
                    filled -> OcColors.Surface
                    !enabled -> OcColors.Ink3
                    else -> OcColors.Ink2
                },
                maxLines = 1,
                softWrap = false,
            )

            else -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) OcColors.Ink2 else OcColors.Ink3,
                    maxLines = 1,
                    softWrap = false,
                )
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (enabled) OcColors.Ink3 else OcColors.Ink3.copy(alpha = 0.6f),
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
