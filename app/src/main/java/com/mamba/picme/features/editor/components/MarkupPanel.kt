package com.mamba.picme.features.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mamba.picme.R
import com.mamba.picme.core.designsystem.components.AppSlider
import com.mamba.picme.features.editor.MarkupAction

enum class MarkupTool { DOODLE, MOSAIC, TEXT }

/** 笔画粗细滑块范围（图片宽度的归一化比例）。 */
const val MARKUP_STROKE_MIN = 0.005f
const val MARKUP_STROKE_MAX = 0.06f

/** 默认文字大小（图片宽度的归一化比例）。 */
const val MARKUP_DEFAULT_TEXT_SIZE = 0.05f

/** 标记工具的可选颜色。 */
val MARKUP_COLORS = listOf(
    0xFFFF3B30.toInt(), // 红
    0xFFFF9500.toInt(), // 橙
    0xFFFFCC00.toInt(), // 黄
    0xFF34C759.toInt(), // 绿
    0xFF0A84FF.toInt(), // 蓝
    0xFFFFFFFF.toInt(), // 白
    0xFF000000.toInt()  // 黑
)

/**
 * 标记工具的选中状态（工具/颜色/粗细），由屏幕层 remember 并同时驱动
 * 面板与预览区绘制覆盖层，保证「面板选什么、手指画什么」一致。
 */
class MarkupToolState(
    tool: MarkupTool = MarkupTool.DOODLE,
    color: Int = MARKUP_COLORS.first(),
    strokeWidth: Float = 0.015f
) {
    var tool by mutableStateOf(tool)
    var color by mutableStateOf(color)
    var strokeWidth by mutableFloatStateOf(strokeWidth)
}

@Composable
fun MarkupPanel(
    toolState: MarkupToolState,
    actions: List<MarkupAction>,
    onChange: (List<MarkupAction>) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        MarkupToolRow(toolState)
        MarkupColorRow(toolState)
        MarkupStrokeRow(toolState, actions, onChange)
    }
}

@Composable
private fun MarkupToolRow(toolState: MarkupToolState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        MarkupToolChip(
            icon = Icons.Default.Brush,
            labelRes = R.string.doodle,
            selected = toolState.tool == MarkupTool.DOODLE,
            onClick = { toolState.tool = MarkupTool.DOODLE }
        )
        MarkupToolChip(
            icon = Icons.Default.BlurOn,
            labelRes = R.string.mosaic,
            selected = toolState.tool == MarkupTool.MOSAIC,
            onClick = { toolState.tool = MarkupTool.MOSAIC }
        )
        MarkupToolChip(
            icon = Icons.Default.Title,
            labelRes = R.string.markup_tool_text,
            selected = toolState.tool == MarkupTool.TEXT,
            onClick = { toolState.tool = MarkupTool.TEXT }
        )
    }
}

@Composable
private fun MarkupToolChip(
    icon: ImageVector,
    labelRes: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    val label = stringResource(labelRes)
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Icon(icon, contentDescription = label) },
        modifier = Modifier.semantics { contentDescription = "Markup tool $label" }
    )
}

@Composable
private fun MarkupColorRow(toolState: MarkupToolState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        MARKUP_COLORS.forEach { colorInt ->
            val selected = toolState.color == colorInt
            Box(
                modifier = Modifier
                    .size(if (selected) 32.dp else 24.dp)
                    .clip(CircleShape)
                    .background(Color(colorInt))
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        shape = CircleShape
                    )
                    .semantics { contentDescription = "Markup color" }
                    .clickable { toolState.color = colorInt }
            )
        }
    }
}

@Composable
private fun MarkupStrokeRow(
    toolState: MarkupToolState,
    actions: List<MarkupAction>,
    onChange: (List<MarkupAction>) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.markup_stroke_width),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 8.dp, end = 8.dp)
        )
        AppSlider(
            value = toolState.strokeWidth,
            onValueChange = { toolState.strokeWidth = it },
            valueRange = MARKUP_STROKE_MIN..MARKUP_STROKE_MAX,
            modifier = Modifier.weight(1f)
        )
        TextButton(
            onClick = { onChange(emptyList()) },
            enabled = actions.isNotEmpty()
        ) {
            Text(stringResource(R.string.markup_clear))
        }
    }
}
