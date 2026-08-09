package com.mamba.picme.features.gallery.components

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import com.mamba.picme.R

// 与 FaceMakeupPass 中的腮红三角网格保持一致，便于对齐静态图左右脸区域。索引基于统一 106 点。
private val BLUSH_TRIANGLE_INDICES = intArrayOf(
    2, 3, 78,
    3, 78, 44,
    3, 4, 44,
    4, 44, 45,
    4, 5, 45,
    5, 45, 46,
    5, 6, 46,
    29, 30, 79,
    79, 29, 44,
    28, 29, 44,
    44, 28, 45,
    27, 28, 45,
    45, 27, 46,
    26, 27, 46
)

/**
 * 相册大图页人脸关键点检测状态。
 *
 * - [points106]：归一化 [0,1] 坐标（偶数索引=x，奇数索引=y），由 MediaViewModel.detectFaceLandmarks
 *   通过 FaceDetector.detectPhoto（必装 MNN 模型）产出，与已应用 EXIF 朝向的显示 bitmap 同向。
 * - [imageWidth]/[imageHeight]：解码后 bitmap 尺寸，用于 ContentScale.Fit 信箱映射。
 * - [isLoading]/[noFace]/[errorMessage]：供 [FaceLandmarkFeedback] 渲染加载/无脸/异常反馈。
 */
class FaceLandmarkDetectionState(
    val imageWidth: Int,
    val imageHeight: Int,
    val points106: FloatArray?,
    val isLoading: Boolean,
    val noFace: Boolean,
    val errorMessage: String?
) {
    companion object {
        val IDLE = FaceLandmarkDetectionState(
            imageWidth = 0,
            imageHeight = 0,
            points106 = null,
            isLoading = false,
            noFace = false,
            errorMessage = null
        )
    }
}

/**
 * 把 106 关键点叠加到大图上（ContentScale.Fit 信箱映射，与 ZoomableImage scale=1 时的显示几何一致）。
 */
@Composable
fun FaceLandmarkCanvasOverlay(
    state: FaceLandmarkDetectionState,
    modifier: Modifier = Modifier
) {
    val points = state.points106
    if (points == null || state.imageWidth <= 0 || state.imageHeight <= 0) {
        return
    }

    val imageAspect = state.imageWidth.toFloat() / state.imageHeight.toFloat()

    Canvas(modifier = modifier.fillMaxSize()) {
        val canvasAspect = size.width / size.height

        val drawWidth: Float
        val drawHeight: Float
        val drawLeft: Float
        val drawTop: Float
        if (imageAspect > canvasAspect) {
            drawWidth = size.width
            drawHeight = size.width / imageAspect
            drawLeft = 0f
            drawTop = (size.height - drawHeight) / 2f
        } else {
            drawHeight = size.height
            drawWidth = size.height * imageAspect
            drawLeft = (size.width - drawWidth) / 2f
            drawTop = 0f
        }

        fun toCanvasPoint(normX: Float, normY: Float): Offset =
            Offset(x = drawLeft + normX * drawWidth, y = drawTop + normY * drawHeight)

        fun drawBlushTriangleMesh(color: Color) {
            val pointCount = points.size / 2
            val fillColor = color.copy(alpha = 0.14f)
            val strokeColor = color.copy(alpha = 0.75f)

            for (index in BLUSH_TRIANGLE_INDICES.indices step 3) {
                val first = BLUSH_TRIANGLE_INDICES[index]
                val second = BLUSH_TRIANGLE_INDICES[index + 1]
                val third = BLUSH_TRIANGLE_INDICES[index + 2]
                if (first >= pointCount || second >= pointCount || third >= pointCount) {
                    continue
                }

                val p0 = toCanvasPoint(points[first * 2], points[first * 2 + 1])
                val p1 = toCanvasPoint(points[second * 2], points[second * 2 + 1])
                val p2 = toCanvasPoint(points[third * 2], points[third * 2 + 1])
                val path = Path().apply {
                    moveTo(p0.x, p0.y)
                    lineTo(p1.x, p1.y)
                    lineTo(p2.x, p2.y)
                    close()
                }

                drawPath(path = path, color = fillColor)
                drawPath(path = path, color = strokeColor, style = Stroke(width = 1.5f))
            }
        }

        val blueColor = Color(0xFF4488FF)
        drawBlushTriangleMesh(blueColor)
        for (index in 0 until points.size / 2) {
            val canvasPoint = toCanvasPoint(points[index * 2], points[index * 2 + 1])
            drawCircle(color = blueColor, radius = 6f, center = canvasPoint)
            drawIntoCanvas { canvas ->
                val paint = Paint().apply {
                    color = "#4488FF".toColorInt()
                    textSize = 18f
                    textAlign = Paint.Align.CENTER
                }
                canvas.nativeCanvas.drawText(
                    index.toString(),
                    canvasPoint.x,
                    canvasPoint.y - 8f,
                    paint
                )
            }
        }
    }
}

/**
 * 关键点检测的加载/无脸/异常反馈（覆盖在图上居中），让"点击后人脸关键点"有可见响应，
 * 不再像旧版那样检测失败时静默空白。
 */
@Composable
fun FaceLandmarkFeedback(
    state: FaceLandmarkDetectionState,
    modifier: Modifier = Modifier
) {
    if (!state.isLoading && !state.noFace && state.errorMessage == null) {
        return
    }

    val message = when {
        state.isLoading -> stringResource(R.string.landmark_loading)
        state.noFace -> stringResource(R.string.landmark_no_face_detected)
        else -> stringResource(R.string.load_failed)
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            color = Color.Black.copy(alpha = 0.6f),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(modifier = Modifier.height(12.dp))
                }
                Text(
                    text = message,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
