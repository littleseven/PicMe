package com.mamba.picme.features.editor.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import com.mamba.picme.R
import com.mamba.picme.features.editor.MarkupAction
import com.mamba.picme.features.editor.NormPoint
import java.util.UUID

/**
 * 标记绘制覆盖层：覆盖在预览图之上（与 ContentScale.Fit 同一适配矩形），
 * 负责把手指拖拽/点按转换为归一化图片坐标，并实时渲染进行中的笔画。
 *
 * 已提交的笔画由 RecipeApplier 烘焙进预览 Bitmap，但烘焙要走 debounce + 全管线重渲染；
 * 提交后到新预览图到达之间，[pendingActions] 继续在覆盖层绘制，避免「画完闪一下」。
 *
 * 仅在 MARKUP tab 且缩放被重置为 1x 时启用（见 PhotoEditorScreen），
 * 因此视图坐标→图片坐标无需考虑缩放/平移。
 */
@Composable
fun MarkupDrawingOverlay(
    toolState: MarkupToolState,
    bitmapRatio: Float,
    pendingActions: List<MarkupAction>,
    onCommit: (MarkupAction) -> Unit,
    onTextTap: (NormPoint) -> Unit
) {
    var strokePoints by remember { mutableStateOf<List<NormPoint>>(emptyList()) }
    val tool = toolState.tool
    // pointerInput 闭包在 key 不变时不重启，回调必须经 rememberUpdatedState 取最新，
    // 否则连续两笔可能捕获到旧的 recipe 快照导致丢笔画
    val currentOnCommit by rememberUpdatedState(onCommit)
    val currentOnTextTap by rememberUpdatedState(onTextTap)

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(tool, bitmapRatio) {
                val bounds = computeFitBounds(size.width.toFloat(), size.height.toFloat(), bitmapRatio)
                when (tool) {
                    MarkupTool.TEXT -> detectTapGestures { offset ->
                        currentOnTextTap(offset.toNormPoint(bounds))
                    }
                    MarkupTool.DOODLE, MarkupTool.MOSAIC -> detectDragGestures(
                        onDragStart = { offset ->
                            strokePoints = listOf(offset.toNormPoint(bounds))
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            strokePoints = strokePoints + change.position.toNormPoint(bounds)
                        },
                        onDragEnd = {
                            val points = strokePoints
                            strokePoints = emptyList()
                            if (points.isNotEmpty()) {
                                val action = if (tool == MarkupTool.DOODLE) {
                                    MarkupAction.Doodle(
                                        id = UUID.randomUUID().toString(),
                                        points = points,
                                        color = toolState.color,
                                        strokeWidth = toolState.strokeWidth
                                    )
                                } else {
                                    MarkupAction.Mosaic(
                                        id = UUID.randomUUID().toString(),
                                        points = points,
                                        strokeWidth = toolState.strokeWidth
                                    )
                                }
                                currentOnCommit(action)
                            }
                        },
                        onDragCancel = { strokePoints = emptyList() }
                    )
                }
            }
    ) {
        val bounds = computeFitBounds(size.width, size.height, bitmapRatio)
        // 已提交、待烘焙的动作：涂鸦/文字按最终效果绘制（烘焙后无缝衔接），
        // 马赛克无法廉价复刻 Shader，沿用半透明指示色
        pendingActions.forEach { action ->
            when (action) {
                is MarkupAction.Doodle -> drawNormStroke(
                    action.points, bounds, action.strokeWidth * bounds.width, Color(action.color)
                )
                is MarkupAction.Mosaic -> drawNormStroke(
                    action.points, bounds, action.strokeWidth * bounds.width, PENDING_STROKE_COLOR
                )
                is MarkupAction.Text -> drawNormText(action, bounds)
            }
        }
        if (strokePoints.isEmpty()) return@Canvas
        val strokePx = toolState.strokeWidth * bounds.width
        val color = if (tool == MarkupTool.DOODLE) {
            Color(toolState.color)
        } else {
            PENDING_STROKE_COLOR
        }
        drawNormStroke(strokePoints, bounds, strokePx, color)
    }
}

private const val IN_PROGRESS_STROKE_ALPHA = 0.5f
private val PENDING_STROKE_COLOR = Color.White.copy(alpha = IN_PROGRESS_STROKE_ALPHA)

/** 归一化点序列笔画：单点画圆点，多点画圆头折线。 */
private fun DrawScope.drawNormStroke(points: List<NormPoint>, bounds: Rect, strokePx: Float, color: Color) {
    if (points.isEmpty()) return
    if (points.size == 1) {
        drawCircle(color, radius = strokePx / 2f, center = points.first().toOffset(bounds))
        return
    }
    val path = Path().apply {
        points.forEachIndexed { index, p ->
            val o = p.toOffset(bounds)
            if (index == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
        }
    }
    drawPath(path, color, style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** 文字标记：与 RecipeApplier 同一坐标语义（position 为基线起点），保证烘焙前后无缝。 */
private fun DrawScope.drawNormText(action: MarkupAction.Text, bounds: Rect) {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = action.color
        textSize = action.size * bounds.width
    }
    drawContext.canvas.nativeCanvas.drawText(
        action.text,
        bounds.left + action.position.x * bounds.width,
        bounds.top + action.position.y * bounds.height,
        paint
    )
}

/** ContentScale.Fit 下图片在视图内的显示矩形（scale=1 无平移时）。 */
internal fun computeFitBounds(viewWidth: Float, viewHeight: Float, bitmapRatio: Float): Rect {
    if (viewWidth <= 0f || viewHeight <= 0f || bitmapRatio <= 0f) return Rect.Zero
    val viewRatio = viewWidth / viewHeight
    val (fitW, fitH) = if (bitmapRatio > viewRatio) {
        viewWidth to (viewWidth / bitmapRatio)
    } else {
        (viewHeight * bitmapRatio) to viewHeight
    }
    val left = (viewWidth - fitW) / 2f
    val top = (viewHeight - fitH) / 2f
    return Rect(left, top, left + fitW, top + fitH)
}

private fun Offset.toNormPoint(bounds: Rect): NormPoint {
    if (bounds.width <= 0f || bounds.height <= 0f) return NormPoint(0f, 0f)
    return NormPoint(
        ((x - bounds.left) / bounds.width).coerceIn(0f, 1f),
        ((y - bounds.top) / bounds.height).coerceIn(0f, 1f)
    )
}

private fun NormPoint.toOffset(bounds: Rect): Offset =
    Offset(bounds.left + x * bounds.width, bounds.top + y * bounds.height)

/** 文字标记输入对话框：确认后回调非空文本。 */
@Composable
fun MarkupTextInputDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.markup_text_dialog_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.markup_text_hint)) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim()) },
                enabled = text.isNotBlank()
            ) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
