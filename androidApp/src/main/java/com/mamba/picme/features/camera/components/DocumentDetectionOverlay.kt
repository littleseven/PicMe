package com.mamba.picme.features.camera.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/**
 * 文档检测框 - 显示长方形区域引导用户拍摄文档
 */
@Composable
fun DocumentDetectionOverlay(
    documentBounds: Rect?,
    isDocumentDetected: Boolean = false,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = documentBounds != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                documentBounds?.let { bounds ->
                    // 绘制四个角的引导框
                    val cornerLength = 40f
                    val strokeWidth = 6f

                    // 左上角
                    drawLine(
                        color = Color.Unspecified,
                        start = Offset(bounds.left, bounds.top + cornerLength),
                        end = Offset(bounds.left, bounds.top),
                        strokeWidth = strokeWidth
                    )
                    drawLine(
                        color = Color.Unspecified,
                        start = Offset(bounds.left, bounds.top),
                        end = Offset(bounds.left + cornerLength, bounds.top),
                        strokeWidth = strokeWidth
                    )

                    // 右上角
                    drawLine(
                        color = Color.Unspecified,
                        start = Offset(bounds.right - cornerLength, bounds.top),
                        end = Offset(bounds.right, bounds.top),
                        strokeWidth = strokeWidth
                    )
                    drawLine(
                        color = Color.Unspecified,
                        start = Offset(bounds.right, bounds.top),
                        end = Offset(bounds.right, bounds.top + cornerLength),
                        strokeWidth = strokeWidth
                    )

                    // 右下角
                    drawLine(
                        color = Color.Unspecified,
                        start = Offset(bounds.right, bounds.bottom - cornerLength),
                        end = Offset(bounds.right, bounds.bottom),
                        strokeWidth = strokeWidth
                    )
                    drawLine(
                        color = Color.Unspecified,
                        start = Offset(bounds.right, bounds.bottom),
                        end = Offset(bounds.right - cornerLength, bounds.bottom),
                        strokeWidth = strokeWidth
                    )

                    // 左下角
                    drawLine(
                        color = Color.Unspecified,
                        start = Offset(bounds.left + cornerLength, bounds.bottom),
                        end = Offset(bounds.left, bounds.bottom),
                        strokeWidth = strokeWidth
                    )
                    drawLine(
                        color = Color.Unspecified,
                        start = Offset(bounds.left, bounds.bottom),
                        end = Offset(bounds.left, bounds.bottom - cornerLength),
                        strokeWidth = strokeWidth
                    )

                    // 半透明遮罩
                    drawRect(
                        brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.1f))),
                        topLeft = Offset(bounds.left, bounds.top),
                        size = Size(bounds.width, bounds.height),
                        style = Stroke(width = 2f)
                    )
                }
            }
        }
    }
}
