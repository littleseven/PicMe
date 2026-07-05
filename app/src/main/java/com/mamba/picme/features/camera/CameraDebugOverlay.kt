package com.mamba.picme.features.camera

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mamba.picme.R
import com.mamba.picme.beauty.api.facedetect.EngineType
import com.mamba.picme.beauty.api.facedetect.FaceDetectionSource
import com.mamba.picme.beauty.api.facedetect.FaceWarpParams
import com.mamba.picme.beauty.api.facedetect.GpuPixelLandmarks
import kotlin.math.sqrt
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.ui.geometry.Size
import kotlin.math.PI

// [常量定义] 调试颜色
private val MEDIAPIPE_DEBUG_COLOR = Color(0xFF4DB6AC)
private val MNN_DEBUG_COLOR = Color(0xFF7E57C2)
private val MEDIAPIPE_SOURCE_COLOR = Color(0xFF26A69A)
private val MNN_SOURCE_COLOR = Color(0xFFAB47BC)
private val NONE_DEBUG_COLOR = Color(0xFF9E9E9E)

@Composable
internal fun FaceDebugOverlay(
    faceWarpParams: FaceWarpParams,
    slimFaceValue: Float,
    aspectRatio: Int = AspectRatio.RATIO_FULL
) {
    val bigBeautyLandmarks = faceWarpParams.bigBeautyLandmarks
    val hasBigBeauty = bigBeautyLandmarks.hasFace &&
        (bigBeautyLandmarks.rawPoints.size >= 106 * 2 || bigBeautyLandmarks.points.isNotEmpty())
    val detectionLabel = when (faceWarpParams.detectionSource) {
        FaceDetectionSource.MEDIAPIPE -> stringResource(R.string.face_detection_engine_mode_mediapipe)
        FaceDetectionSource.MNN -> stringResource(R.string.inference_engine_mnn)
        FaceDetectionSource.NONE -> stringResource(R.string.face_detection_source_none)
    }
    val requestedLabel = when (faceWarpParams.requestedDetectionEngineMode) {
        EngineType.MEDIAPIPE -> stringResource(R.string.face_detection_engine_mode_mediapipe)
        EngineType.MNN -> stringResource(R.string.inference_engine_mnn)
    }
    val requestedColor = faceDebugRequestedColor(faceWarpParams.requestedDetectionEngineMode)
    val detectionColor = faceDebugSourceColor(faceWarpParams.detectionSource)

    Box(modifier = Modifier.fillMaxSize()) {
        if (faceWarpParams.hasFace && hasBigBeauty) {
            FaceDebugOverlayBigBeauty(
                bigBeautyLandmarks = bigBeautyLandmarks,
                aspectRatio = aspectRatio,
                roiRect = faceWarpParams.roiRect
            )
        }

        // [已移除] 调试状态面板(请求引擎和实际命中)
        // FaceDebugStatusPanel(
        //     requestedLabel = requestedLabel,
        //     requestedColor = requestedColor,
        //     detectionLabel = detectionLabel,
        //     detectionColor = detectionColor,
        //     modifier = Modifier
        //         .align(Alignment.TopStart)
        //         .padding(start = 12.dp, top = 12.dp)
        // )
    }
}

@Composable
private fun FaceDebugStatusPanel(
    requestedLabel: String,
    requestedColor: Color,
    detectionLabel: String,
    detectionColor: Color,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                shape = shape
            )
            .border(
                width = 1.dp,
                color = detectionColor.copy(alpha = 0.55f),
                shape = shape
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        FaceDebugStatusRow(
            title = stringResource(R.string.camera_face_debug_requested_label),
            label = requestedLabel,
            accentColor = requestedColor,
            emphasized = false
        )
        Spacer(modifier = Modifier.height(8.dp))
        FaceDebugStatusRow(
            title = stringResource(R.string.camera_face_debug_active_label),
            label = detectionLabel,
            accentColor = detectionColor,
            emphasized = true
        )
    }
}

@Composable
private fun FaceDebugStatusRow(
    title: String,
    label: String,
    accentColor: Color,
    emphasized: Boolean
) {
    Column {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            fontSize = 10.sp
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(if (emphasized) 10.dp else 8.dp)
                    .background(color = accentColor, shape = CircleShape)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = if (emphasized) 14.sp else 12.sp
            )
        }
    }
}

private fun faceDebugRequestedColor(mode: EngineType): Color {
    return when (mode) {
        EngineType.MEDIAPIPE -> MEDIAPIPE_DEBUG_COLOR
        EngineType.MNN -> MNN_DEBUG_COLOR  // [性能优化] MNN OpenCL GPU
    }
}

private fun faceDebugSourceColor(source: FaceDetectionSource): Color {
    return when (source) {
        FaceDetectionSource.MEDIAPIPE -> MEDIAPIPE_SOURCE_COLOR
        FaceDetectionSource.MNN -> MNN_SOURCE_COLOR  // [性能优化] MNN OpenCL GPU
        FaceDetectionSource.NONE -> NONE_DEBUG_COLOR
    }
}

@Composable
private fun FaceDebugOverlayBigBeauty(
    bigBeautyLandmarks: GpuPixelLandmarks,
    aspectRatio: Int = AspectRatio.RATIO_FULL,
    roiRect: RectF? = null
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val contentOffsetX: Float
        val contentOffsetY: Float
        val contentWidth: Float
        val contentHeight: Float

        if (aspectRatio == AspectRatio.RATIO_FULL) {
            contentOffsetX = 0f
            contentOffsetY = 0f
            contentWidth = size.width
            contentHeight = size.height
        } else {
            val imageContentAspect = when (aspectRatio) {
                AspectRatio.RATIO_4_3 -> 3f / 4f
                AspectRatio.RATIO_16_9 -> 9f / 16f
                else -> size.width / size.height
            }
            val canvasAspect = size.width / size.height
            if (imageContentAspect < canvasAspect) {
                contentHeight = size.height
                contentWidth = size.height * imageContentAspect
                contentOffsetX = (size.width - contentWidth) / 2f
                contentOffsetY = 0f
            } else {
                contentWidth = size.width
                contentHeight = size.width / imageContentAspect
                contentOffsetX = 0f
                contentOffsetY = (size.height - contentHeight) / 2f
            }
        }

        fun toCanvasPoint(point: Offset): Offset {
            return Offset(
                x = contentOffsetX + point.x.coerceIn(0f, 1f) * contentWidth,
                y = contentOffsetY + point.y.coerceIn(0f, 1f) * contentHeight
            )
        }

        // [新增] 绘制 ROI 矩形框（橙色虚线）
        roiRect?.let { roi ->
            val roiLeft = contentOffsetX + roi.left.coerceIn(0f, 1f) * contentWidth
            val roiTop = contentOffsetY + roi.top.coerceIn(0f, 1f) * contentHeight
            val roiRight = contentOffsetX + roi.right.coerceIn(0f, 1f) * contentWidth
            val roiBottom = contentOffsetY + roi.bottom.coerceIn(0f, 1f) * contentHeight

            // 绘制 ROI 边框
            drawRect(
                color = Color(0xFFFF6D00).copy(alpha = 0.8f),
                topLeft = Offset(roiLeft, roiTop),
                size = Size(roiRight - roiLeft, roiBottom - roiTop),
                style = Stroke(width = 3.dp.toPx())
            )

            // 绘制 ROI 四个角点
            val cornerSize = 15.dp.toPx()
            // 左上角
            drawLine(color = Color.Yellow, start = Offset(roiLeft, roiTop), end = Offset(roiLeft + cornerSize, roiTop), strokeWidth = 4.dp.toPx())
            drawLine(color = Color.Yellow, start = Offset(roiLeft, roiTop), end = Offset(roiLeft, roiTop + cornerSize), strokeWidth = 4.dp.toPx())
            // 右上角
            drawLine(color = Color.Yellow, start = Offset(roiRight, roiTop), end = Offset(roiRight - cornerSize, roiTop), strokeWidth = 4.dp.toPx())
            drawLine(color = Color.Yellow, start = Offset(roiRight, roiTop), end = Offset(roiRight, roiTop + cornerSize), strokeWidth = 4.dp.toPx())
            // 左下角
            drawLine(color = Color.Yellow, start = Offset(roiLeft, roiBottom), end = Offset(roiLeft + cornerSize, roiBottom), strokeWidth = 4.dp.toPx())
            drawLine(color = Color.Yellow, start = Offset(roiLeft, roiBottom), end = Offset(roiLeft, roiBottom - cornerSize), strokeWidth = 4.dp.toPx())
            // 右下角
            drawLine(color = Color.Yellow, start = Offset(roiRight, roiBottom), end = Offset(roiRight - cornerSize, roiBottom), strokeWidth = 4.dp.toPx())
            drawLine(color = Color.Yellow, start = Offset(roiRight, roiBottom), end = Offset(roiRight, roiBottom - cornerSize), strokeWidth = 4.dp.toPx())

            // 标注 ROI 尺寸
            val textPaint = Paint().apply {
                textSize = 12.dp.toPx()
                isAntiAlias = true
                textAlign = Paint.Align.LEFT
            }
            drawIntoCanvas { canvas ->
                textPaint.color = Color.White.toArgb()
                canvas.nativeCanvas.drawText(
                    "ROI: ${(roi.right - roi.left).toInt()}x${(roi.bottom - roi.top).toInt()}",
                    roiLeft,
                    roiTop - 8.dp.toPx(),
                    textPaint
                )
            }
        }

        // 绘制大美丽点位（蓝色，带序号）
        val bbPoints = if (bigBeautyLandmarks.rawPoints.size >= 106 * 2) {
            buildList(106) {
                var index = 0
                repeat(106) {
                    add(
                        toCanvasPoint(
                            Offset(
                                bigBeautyLandmarks.rawPoints[index],
                                bigBeautyLandmarks.rawPoints[index + 1]
                            )
                        )
                    )
                    index += 2
                }
            }
        } else {
            bigBeautyLandmarks.points.map { pt -> toCanvasPoint(Offset(pt.x, pt.y)) }
        }
        bbPoints.forEachIndexed { index, point ->
            drawCircle(
                color = Color.Blue.copy(alpha = 0.85f),
                radius = 2.5f,
                center = point,
                style = Fill
            )
        }

        // 绘制瘦脸控制点及方向（调试用）
        drawThinFaceControlPoints(bbPoints)

        // 绘制大眼控制点及方向（调试用）
        drawBigEyeControlPoints(bbPoints)

        // 绘制腮红控制点及区域（调试用）
        drawBlushControlPoints(bbPoints)
    }
}

@Composable
private fun FaceDebugOverlaySingle(
    faceWarpParams: FaceWarpParams,
    slimFaceValue: Float,
    aspectRatio: Int = AspectRatio.RATIO_FULL
) {
    if (!faceWarpParams.hasFace) {
        return
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val centerNorm = Offset(
            x = faceWarpParams.faceCenterX.coerceIn(0f, 1f),
            y = faceWarpParams.faceCenterY.coerceIn(0f, 1f)
        )
        val leftEyeNorm = Offset(
            x = faceWarpParams.leftEyeX.coerceIn(0f, 1f),
            y = faceWarpParams.leftEyeY.coerceIn(0f, 1f)
        )
        val rightEyeNorm = Offset(
            x = faceWarpParams.rightEyeX.coerceIn(0f, 1f),
            y = faceWarpParams.rightEyeY.coerceIn(0f, 1f)
        )
        val faceRadiusNorm = faceWarpParams.faceRadius.coerceIn(0f, 1f)
        val slimFaceIntensity = (slimFaceValue / 50f).coerceIn(-1f, 1f)

        // 计算 FIT_CENTER 模式下图像内容在 Canvas 中的实际显示区域
        // 4:3/16:9 比例时 PreviewView 使用 FIT_CENTER，有上下或左右黑边
        // normY 是基于全屏高度归一化的，需要映射到内容区域才能与视觉画面对齐
        val contentOffsetX: Float
        val contentOffsetY: Float
        val contentWidth: Float
        val contentHeight: Float

        if (aspectRatio == AspectRatio.RATIO_FULL) {
            // FILL_CENTER：内容填满 Canvas，无黑边，直接用全屏坐标
            contentOffsetX = 0f
            contentOffsetY = 0f
            contentWidth = size.width
            contentHeight = size.height
        } else {
            // FIT_CENTER：根据目标宽高比计算内容区域
            // 4:3 竖屏旋转后：图像内容宽高比 = 3/4 = 0.75（高 > 宽，高不是更大，而是比例上竖向更长）
            // 16:9 竖屏旋转后：图像内容宽高比 = 9/16 = 0.5625
            val imageContentAspect = when (aspectRatio) {
                AspectRatio.RATIO_4_3 -> 3f / 4f   // 竖屏时 width/height = 3/4
                AspectRatio.RATIO_16_9 -> 9f / 16f  // 竖屏时 width/height = 9/16
                else -> size.width / size.height
            }
            val canvasAspect = size.width / size.height
            if (imageContentAspect < canvasAspect) {
                // 内容比 Canvas 更窄：上下填满，左右有黑边
                contentHeight = size.height
                contentWidth = size.height * imageContentAspect
                contentOffsetX = (size.width - contentWidth) / 2f
                contentOffsetY = 0f
            } else {
                // 内容比 Canvas 更宽：左右填满，上下有黑边
                contentWidth = size.width
                contentHeight = size.width / imageContentAspect
                contentOffsetX = 0f
                contentOffsetY = (size.height - contentHeight) / 2f
            }
        }

        // 将归一化坐标（基于 previewView 全尺寸）映射到内容区域实际像素坐标
        fun toCanvasPoint(point: Offset): Offset {
            return Offset(
                x = contentOffsetX + point.x.coerceIn(0f, 1f) * contentWidth,
                y = contentOffsetY + point.y.coerceIn(0f, 1f) * contentHeight
            )
        }

        val center = toCanvasPoint(centerNorm)
        val leftEye = toCanvasPoint(leftEyeNorm)
        val rightEye = toCanvasPoint(rightEyeNorm)
        val radiusPx = faceRadiusNorm * contentWidth

        val contourPoints = faceWarpParams.contourPoints.map { contourPoint ->
            toCanvasPoint(Offset(contourPoint.x, contourPoint.y))
        }
        val leftEyeContourPoints = faceWarpParams.leftEyeContourPoints.map { contourPoint ->
            toCanvasPoint(Offset(contourPoint.x, contourPoint.y))
        }
        val rightEyeContourPoints = faceWarpParams.rightEyeContourPoints.map { contourPoint ->
            toCanvasPoint(Offset(contourPoint.x, contourPoint.y))
        }

        fun drawClosedContour(points: List<Offset>, color: Color, strokeWidth: Float) {
            if (points.size < 2) {
                return
            }
            points.zipWithNext().forEach { (start, end) ->
                drawLine(
                    color = color,
                    start = start,
                    end = end,
                    strokeWidth = strokeWidth
                )
            }
            drawLine(
                color = color,
                start = points.last(),
                end = points.first(),
                strokeWidth = strokeWidth
            )
        }

        fun applySlimFaceDebug(point: Offset): Offset {
            val dirX = point.x - centerNorm.x
            val dirY = point.y - centerNorm.y
            val distance = sqrt(dirX * dirX + dirY * dirY)
            if (distance >= faceRadiusNorm || faceRadiusNorm <= 0.0001f) {
                return point
            }

            val eyeAxisXRaw = rightEyeNorm.x - leftEyeNorm.x
            val eyeAxisYRaw = rightEyeNorm.y - leftEyeNorm.y
            val eyeAxisLen = sqrt(eyeAxisXRaw * eyeAxisXRaw + eyeAxisYRaw * eyeAxisYRaw)
            val eyeAxisX = if (eyeAxisLen > 0.0001f) eyeAxisXRaw / eyeAxisLen else 1f
            val eyeAxisY = if (eyeAxisLen > 0.0001f) eyeAxisYRaw / eyeAxisLen else 0f

            val percent = 1f - distance / faceRadiusNorm
            val strength = slimFaceIntensity * percent * percent * 0.45f
            val axisOffset = (dirX * eyeAxisX + dirY * eyeAxisY) / faceRadiusNorm
            val offsetX = eyeAxisX * axisOffset * strength * faceRadiusNorm
            val offsetY = eyeAxisY * axisOffset * strength * faceRadiusNorm

            return Offset(
                x = (point.x - offsetX).coerceIn(0f, 1f),
                y = (point.y - offsetY).coerceIn(0f, 1f)
            )
        }

        // 瘦脸控制点：仅在 ML Kit 模式（有 allContours 数据）下绘制，MediaPipe 模式下跳过
        val isMlKitMode = faceWarpParams.allContours.totalPointCount() > 0
        if (isMlKitMode) {
            drawClosedContour(
                points = contourPoints,
                color = Color.Magenta.copy(alpha = 0.9f),
                strokeWidth = 2.dp.toPx()
            )
            drawClosedContour(
                points = leftEyeContourPoints,
                color = Color.Yellow.copy(alpha = 0.95f),
                strokeWidth = 2.dp.toPx()
            )
            drawClosedContour(
                points = rightEyeContourPoints,
                color = Color.Green.copy(alpha = 0.95f),
                strokeWidth = 2.dp.toPx()
            )

            drawCircle(
                color = Color(0xFFFF6D00).copy(alpha = 0.12f),
                radius = radiusPx,
                center = center,
                style = Fill
            )
            drawCircle(
                color = Color(0xFFFF6D00).copy(alpha = 0.18f),
                radius = radiusPx * 0.58f,
                center = center,
                style = Fill
            )
            drawCircle(
                color = Color.Cyan.copy(alpha = 0.75f),
                radius = radiusPx,
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )

            val debugWarpAmplifier = 6f
            val yRatios = listOf(-0.45f, -0.2f, 0f, 0.2f, 0.45f)
            yRatios.forEach { ratio ->
                val sampleY = (centerNorm.y + faceRadiusNorm * ratio).coerceIn(0f, 1f)
                val leftSample = Offset(
                    x = (centerNorm.x - faceRadiusNorm * 0.72f).coerceIn(0f, 1f),
                    y = sampleY
                )
                val rightSample = Offset(
                    x = (centerNorm.x + faceRadiusNorm * 0.72f).coerceIn(0f, 1f),
                    y = sampleY
                )
                listOf(leftSample, rightSample).forEach { samplePoint ->
                    val warpedPoint = applySlimFaceDebug(samplePoint)
                    val sampleCanvasPoint = toCanvasPoint(samplePoint)
                    val warpedCanvasPoint = toCanvasPoint(warpedPoint)
                    val debugWarpedCanvasPoint = Offset(
                        x = (
                            sampleCanvasPoint.x +
                                (warpedCanvasPoint.x - sampleCanvasPoint.x) * debugWarpAmplifier
                            ).coerceIn(0f, size.width),
                        y = (
                            sampleCanvasPoint.y +
                                (warpedCanvasPoint.y - sampleCanvasPoint.y) * debugWarpAmplifier
                            ).coerceIn(0f, size.height)
                    )

                    drawLine(
                        color = Color(0xFFFF6D00),
                        start = sampleCanvasPoint,
                        end = debugWarpedCanvasPoint,
                        strokeWidth = 3.dp.toPx()
                    )
                    drawCircle(
                        color = Color(0xFFFF6D00),
                        radius = 3.dp.toPx(),
                        center = sampleCanvasPoint
                    )
                    drawCircle(
                        color = Color(0xFF00E5FF),
                        radius = 4.dp.toPx(),
                        center = debugWarpedCanvasPoint
                    )
                }
            }

            drawLine(
                color = Color.White.copy(alpha = 0.8f),
                start = leftEye,
                end = rightEye,
                strokeWidth = 1.5.dp.toPx()
            )
        }

        // 绘制所有 133 点 Contour（调试用，ML Kit 模式）
        drawAllContours(faceWarpParams, ::toCanvasPoint)
    }
}

/**
 * 绘制大眼控制点及方向
 */
private fun DrawScope.drawBigEyeControlPoints(points: List<Offset>) {
    if (points.size < 78) return

    // 2对控制点映射（originIndex=瞳孔 -> targetIndex=眼角）
    val controlPairs = listOf(
        Pair(74, 72),   // 右瞳孔 -> 右眼内角
        Pair(77, 75)    // 左瞳孔 -> 左眼内角
    )

    controlPairs.forEachIndexed { idx, (originIdx, targetIdx) ->
        if (originIdx >= points.size || targetIdx >= points.size) return@forEachIndexed

        val origin = points[originIdx]
        val target = points[targetIdx]

        // 大眼使用径向放大，origin是瞳孔，target是眼角
        // 绘制从瞳孔到眼角的连线，表示放大中心方向
        val eyeColor = if (idx == 0) {
            Color(0xFFFFD700).copy(alpha = 0.9f)  // 金色 - 右眼
        } else {
            Color(0xFF00CED1).copy(alpha = 0.9f)  // 深青色 - 左眼
        }

        // 绘制瞳孔到眼角的实线
        drawLine(
            color = eyeColor.copy(alpha = 0.6f),
            start = origin,
            end = target,
            strokeWidth = 2.5f
        )

        // 绘制瞳孔点（大圆，表示放大中心）
        drawCircle(
            color = Color.Yellow.copy(alpha = 0.9f),
            radius = 8.dp.toPx(),
            center = origin,
            style = Stroke(width = 3f)
        )
        drawCircle(
            color = Color.Yellow.copy(alpha = 0.2f),
            radius = 8.dp.toPx(),
            center = origin,
            style = Fill
        )

        // 绘制眼角点（小圆，绿色）
        drawCircle(
            color = Color.Green.copy(alpha = 0.9f),
            radius = 4.dp.toPx(),
            center = target,
            style = Fill
        )

        // 绘制影响范围圆圈（radius = 瞳孔到眼角距离 * 5）
        val radiusPx = distance(
            vec2(target.x, target.y),
            vec2(origin.x, origin.y)
        ) * 5.0f
        drawCircle(
            color = eyeColor.copy(alpha = 0.3f),
            radius = radiusPx,
            center = origin,
            style = Stroke(width = 2.dp.toPx())
        )

        // 绘制放大方向箭头（从眼角指向瞳孔外侧，表示向外扩展）
        val direction = origin - target
        val angle = kotlin.math.atan2(direction.y, direction.x)
        val arrowLen = 20f
        val arrowAngle = 0.5f
        val tip = origin + direction * 0.5f
        val leftWing = Offset(
            tip.x - arrowLen * kotlin.math.cos(angle + arrowAngle),
            tip.y - arrowLen * kotlin.math.sin(angle + arrowAngle)
        )
        val rightWing = Offset(
            tip.x - arrowLen * kotlin.math.cos(angle - arrowAngle),
            tip.y - arrowLen * kotlin.math.sin(angle - arrowAngle)
        )
        drawLine(color = eyeColor, start = tip, end = leftWing, strokeWidth = 3f)
        drawLine(color = eyeColor, start = tip, end = rightWing, strokeWidth = 3f)

        // 标注序号和信息
        val textPaint = Paint().apply {
            textSize = 10.dp.toPx()
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        val eyeLabel = if (idx == 0) "右眼" else "左眼"
        drawIntoCanvas { canvas ->
            textPaint.color = eyeColor.toArgb()
            canvas.nativeCanvas.drawText(
                "$eyeLabel: $originIdx(瞳孔)->$targetIdx(眼角)",
                origin.x,
                origin.y - 12.dp.toPx(),
                textPaint
            )
            canvas.nativeCanvas.drawText(
                "radius=${radiusPx.toInt()}px",
                origin.x,
                origin.y + 20.dp.toPx(),
                textPaint
            )
        }
    }
}

// GLSL风格辅助函数
private fun vec2(x: Float, y: Float): Offset = Offset(x, y)
private fun distance(a: Offset, b: Offset): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return kotlin.math.sqrt(dx * dx + dy * dy)
}

/**
 * 绘制腮红算法的控制点及椭圆区域
 * 基于眼-嘴轴几何计算双颊椭圆中心、长轴/短轴方向
 *
 * 控制点语义：
 * - 左右眼中心 (52-57范围, 58-63范围) 确定眼轴方向
 * - 嘴巴中心 (84-95范围) 确定垂直参考
 * - 椭圆中心 = 眼-嘴轴中点向外/向上偏移
 * - 椭圆长轴沿眼轴方向，短轴垂直于眼轴
 */
private fun DrawScope.drawBlushControlPoints(points: List<Offset>) {
    if (points.size < 96) return

    // 计算左眼中心（取左眼外轮廓6点的平均）
    val leftEyeIndices = listOf(52, 53, 54, 55, 56, 57)
    val leftEyeCenter = if (leftEyeIndices.all { it < points.size }) {
        val sumX = leftEyeIndices.sumOf { points[it].x.toDouble() }.toFloat()
        val sumY = leftEyeIndices.sumOf { points[it].y.toDouble() }.toFloat()
        Offset(sumX / leftEyeIndices.size, sumY / leftEyeIndices.size)
    } else {
        return
    }

    // 计算右眼中心（取右眼外轮廓6点的平均）
    val rightEyeIndices = listOf(58, 59, 60, 61, 62, 63)
    val rightEyeCenter = if (rightEyeIndices.all { it < points.size }) {
        val sumX = rightEyeIndices.sumOf { points[it].x.toDouble() }.toFloat()
        val sumY = rightEyeIndices.sumOf { points[it].y.toDouble() }.toFloat()
        Offset(sumX / rightEyeIndices.size, sumY / rightEyeIndices.size)
    } else {
        return
    }

    // 计算嘴巴中心（取嘴巴外轮廓12点的平均）
    val mouthIndices = listOf(84, 85, 86, 87, 88, 89, 90, 91, 92, 93, 94, 95)
    val mouthCenter = if (mouthIndices.all { it < points.size }) {
        val sumX = mouthIndices.sumOf { points[it].x.toDouble() }.toFloat()
        val sumY = mouthIndices.sumOf { points[it].y.toDouble() }.toFloat()
        Offset(sumX / mouthIndices.size, sumY / mouthIndices.size)
    } else {
        return
    }

    // 眼轴方向（从左眼指向右眼，归一化）
    val eyeAxisX = rightEyeCenter.x - leftEyeCenter.x
    val eyeAxisY = rightEyeCenter.y - leftEyeCenter.y
    val eyeAxisLen = kotlin.math.sqrt(eyeAxisX * eyeAxisX + eyeAxisY * eyeAxisY)
    if (eyeAxisLen < 0.0001f) return
    val eyeAxisNormX = eyeAxisX / eyeAxisLen
    val eyeAxisNormY = eyeAxisY / eyeAxisLen

    // 垂直于眼轴的方向（向下为正向，用于腮红偏移）
    val perpAxisX = -eyeAxisNormY
    val perpAxisY = eyeAxisNormX

    // 眼-嘴垂直距离（用于自适应椭圆大小）
    val eyeToMouthDist = kotlin.math.abs(
        (mouthCenter.x - (leftEyeCenter.x + rightEyeCenter.x) / 2f) * perpAxisX +
            (mouthCenter.y - (leftEyeCenter.y + rightEyeCenter.y) / 2f) * perpAxisY
    )

    // 两眼间距
    val eyeSpacing = eyeAxisLen

    // 对齐 Shader (blush.glsl) 的几何计算逻辑
    // faceUp = normalize(eyeCenter - mouthCenter)，从嘴指向眼 = 向上
    // appleBase = eyeCenter - faceUp * eyeMouthDist * appleBaseFactor（眼-嘴轴中点向下偏移）
    // 左右腮红 = appleBase ± faceRight * cheekOffsetX + faceUp * cheekOffsetY
    val faceUpX = -perpAxisX  // faceUp 与 perpAxis 相反（perpAxis向下，faceUp向上）
    val faceUpY = -perpAxisY

    // 脸型自适应参数（与 Shader 一致）
    val faceAspect = (eyeToMouthDist / eyeSpacing).coerceIn(0.9f, 1.8f)
    val roundFace = ((1.28f - faceAspect) / 0.28f).coerceIn(0f, 1f)
    val longFace = ((faceAspect - 1.40f) / 0.30f).coerceIn(0f, 1f)

    // appleBaseFactor: 0.34 + longFace * 0.05 - roundFace * 0.03
    val appleBaseFactor = 0.34f + longFace * 0.05f - roundFace * 0.03f
    val eyeCenterX = (leftEyeCenter.x + rightEyeCenter.x) / 2f
    val eyeCenterY = (leftEyeCenter.y + rightEyeCenter.y) / 2f

    // appleBase = eyeCenter - faceUp * max(eyeMouthDist * appleBaseFactor, faceRadius * 0.17)
    // 使用 faceRadius 的估算值 = eyeSpacing * 0.5
    val faceRadius = eyeSpacing * 0.5f
    val appleBaseOffset = kotlin.math.max(eyeToMouthDist * appleBaseFactor, faceRadius * 0.17f)
    val appleBaseX = eyeCenterX - faceUpX * appleBaseOffset
    val appleBaseY = eyeCenterY - faceUpY * appleBaseOffset

    // cheekOffsetX = max(eyeWidth * (0.34 + roundFace * 0.05 - longFace * 0.02), faceRadius * (0.31 + roundFace * 0.03))
    val cheekOffsetX = kotlin.math.max(
        eyeSpacing * (0.34f + roundFace * 0.05f - longFace * 0.02f),
        faceRadius * (0.31f + roundFace * 0.03f)
    )

    // cheekOffsetY = max(eyeMouthDist * (0.06 + roundFace * 0.05 - longFace * 0.03), faceRadius * (0.03 + roundFace * 0.04 - longFace * 0.01))
    val cheekOffsetY = kotlin.math.max(
        eyeToMouthDist * (0.06f + roundFace * 0.05f - longFace * 0.03f),
        faceRadius * (0.03f + roundFace * 0.04f - longFace * 0.01f)
    )

    // 左腮红中心 = appleBase - faceRight * cheekOffsetX + faceUp * cheekOffsetY
    val leftBlushCenter = Offset(
        x = appleBaseX - eyeAxisNormX * cheekOffsetX + faceUpX * cheekOffsetY,
        y = appleBaseY - eyeAxisNormY * cheekOffsetX + faceUpY * cheekOffsetY
    )

    // 右腮红中心 = appleBase + faceRight * cheekOffsetX + faceUp * cheekOffsetY
    val rightBlushCenter = Offset(
        x = appleBaseX + eyeAxisNormX * cheekOffsetX + faceUpX * cheekOffsetY,
        y = appleBaseY + eyeAxisNormY * cheekOffsetX + faceUpY * cheekOffsetY
    )

    // 椭圆半径（与 Shader 一致）
    val ellipseRadiusX = kotlin.math.max(
        faceRadius * (0.128f + longFace * 0.018f + roundFace * 0.005f),
        0.05f
    )
    val ellipseRadiusY = kotlin.math.max(
        faceRadius * (0.102f + roundFace * 0.010f - longFace * 0.008f),
        0.04f
    )

    // 绘制颜色定义（使用粉红色系，与腮红语义一致）
    val blushPink = Color(0xFFFF4081).copy(alpha = 0.9f)
    val blushLight = Color(0xFFFF4081).copy(alpha = 0.25f)
    val blushStroke = Color(0xFFFF4081).copy(alpha = 0.6f)

    // 绘制左右腮红椭圆区域
    listOf(
        Pair("左腮红", leftBlushCenter),
        Pair("右腮红", rightBlushCenter)
    ).forEach { (label, center) ->
        // 绘制椭圆外框（虚线风格）
        drawBlushEllipse(
            center = center,
            radiusX = ellipseRadiusX,
            radiusY = ellipseRadiusY,
            eyeAxisX = eyeAxisNormX,
            eyeAxisY = eyeAxisNormY,
            strokeColor = blushStroke,
            fillColor = blushLight
        )

        // 绘制椭圆中心点（实心圆）
        drawCircle(
            color = blushPink,
            radius = 5.dp.toPx(),
            center = center,
            style = Fill
        )

        // 绘制长轴方向线（沿眼轴方向）
        val longAxisStart = Offset(
            center.x - ellipseRadiusX * eyeAxisNormX,
            center.y - ellipseRadiusX * eyeAxisNormY
        )
        val longAxisEnd = Offset(
            center.x + ellipseRadiusX * eyeAxisNormX,
            center.y + ellipseRadiusX * eyeAxisNormY
        )
        drawLine(
            color = blushPink.copy(alpha = 0.5f),
            start = longAxisStart,
            end = longAxisEnd,
            strokeWidth = 1.5f
        )

        // 绘制短轴方向线（垂直眼轴方向）
        val shortAxisStart = Offset(
            center.x - ellipseRadiusY * perpAxisX,
            center.y - ellipseRadiusY * perpAxisY
        )
        val shortAxisEnd = Offset(
            center.x + ellipseRadiusY * perpAxisX,
            center.y + ellipseRadiusY * perpAxisY
        )
        drawLine(
            color = blushPink.copy(alpha = 0.5f),
            start = shortAxisStart,
            end = shortAxisEnd,
            strokeWidth = 1.5f
        )

        // 标注文字
        val textPaint = Paint().apply {
            textSize = 10.dp.toPx()
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        drawIntoCanvas { canvas ->
            textPaint.color = blushPink.toArgb()
            canvas.nativeCanvas.drawText(
                label,
                center.x,
                center.y - 10.dp.toPx(),
                textPaint
            )
            canvas.nativeCanvas.drawText(
                "rx=${(ellipseRadiusX * 1000).toInt() / 10f}‰ ry=${(ellipseRadiusY * 1000).toInt() / 10f}‰",
                center.x,
                center.y + 18.dp.toPx(),
                textPaint
            )
        }
    }

    // 绘制眼轴参考线（调试用，浅灰色）
    drawLine(
        color = Color.LightGray.copy(alpha = 0.3f),
        start = leftEyeCenter,
        end = rightEyeCenter,
        strokeWidth = 1f
    )
}

/**
 * 绘制腮红椭圆（使用线段近似椭圆轮廓）
 */
private fun DrawScope.drawBlushEllipse(
    center: Offset,
    radiusX: Float,
    radiusY: Float,
    eyeAxisX: Float,
    eyeAxisY: Float,
    strokeColor: Color,
    fillColor: Color
) {
    val segments = 32
    val ellipsePoints = mutableListOf<Offset>()

    for (i in 0..segments) {
        val angle = 2f * PI * i / segments
        // 椭圆在局部坐标系中的点
        val localX = kotlin.math.cos(angle) * radiusX
        val localY = kotlin.math.sin(angle) * radiusY

        // 转换到全局坐标系（考虑眼轴旋转）
        // 眼轴方向为长轴，垂直方向为短轴
        val perpAxisX = -eyeAxisY
        val perpAxisY = eyeAxisX

        val globalX = center.x + localX * eyeAxisX + localY * perpAxisX
        val globalY = center.y + localX * eyeAxisY + localY * perpAxisY

        ellipsePoints.add(Offset(globalX.toFloat(), globalY.toFloat()))
    }

    // 绘制填充（使用多边形近似）
    if (ellipsePoints.size >= 3) {
        val path = Path().apply {
            moveTo(ellipsePoints.first().x, ellipsePoints.first().y)
            for (i in 1 until ellipsePoints.size) {
                lineTo(ellipsePoints[i].x, ellipsePoints[i].y)
            }
            close()
        }
        drawIntoCanvas { canvas ->
            val paint = Paint().apply {
                color = fillColor.toArgb()
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            canvas.nativeCanvas.drawPath(path, paint)
        }
    }

    // 绘制椭圆轮廓线
    ellipsePoints.zipWithNext().forEach { (start, end) ->
        drawLine(
            color = strokeColor,
            start = start,
            end = end,
            strokeWidth = 2f
        )
    }
}

/**
 * 绘制瘦脸算法的9对控制点及方向
 * originIndex -> targetIndex：从轮廓点指向鼻梁方向
 */
private fun DrawScope.drawThinFaceControlPoints(points: List<Offset>) {
    if (points.size < 50) return

    // 9对控制点映射（originIndex -> targetIndex）
    val controlPairs = listOf(
        Pair(3, 44),   // 右脸轮廓 -> 鼻梁右侧
        Pair(29, 44),  // 左脸轮廓 -> 鼻梁右侧
        Pair(7, 45),   // 右脸颊 -> 鼻梁中
        Pair(25, 45),  // 左脸颊 -> 鼻梁中
        Pair(10, 46),  // 右下颌 -> 鼻梁下
        Pair(22, 46),  // 左下颌 -> 鼻梁下
        Pair(14, 49),  // 右下巴 -> 下巴中心
        Pair(18, 49),  // 左下巴 -> 下巴中心
        Pair(16, 49)   // 下巴尖 -> 下巴中心
    )

    controlPairs.forEachIndexed { idx, (originIdx, targetIdx) ->
        if (originIdx >= points.size || targetIdx >= points.size) return@forEachIndexed

        val origin = points[originIdx]
        val target = points[targetIdx]

        // 绘制方向箭头（从 origin 指向 target，表示形变方向）
        val arrowColor = when (idx) {
            0, 1 -> Color(0xFFFF6D00).copy(alpha = 0.9f)  // 橙色 - 上部
            2, 3 -> Color(0xFF00E5FF).copy(alpha = 0.9f)  // 青色 - 中部
            4, 5 -> Color(0xFF76FF03).copy(alpha = 0.9f)  // 绿色 - 下部
            else -> Color(0xFFFF00FF).copy(alpha = 0.9f)  // 紫色 - 底部
        }

        // 绘制虚线连接 origin 和 target
        val direction = target - origin
        val segmentCount = 10
        for (i in 0 until segmentCount step 2) {
            val t1 = i.toFloat() / segmentCount
            val t2 = (i + 1).toFloat() / segmentCount
            drawLine(
                color = arrowColor.copy(alpha = 0.4f),
                start = origin + direction * t1,
                end = origin + direction * t2,
                strokeWidth = 2.5f
            )
        }

        // 绘制 origin 点（大圆，红色边框）
        drawCircle(
            color = Color.Red.copy(alpha = 0.9f),
            radius = 6.dp.toPx(),
            center = origin,
            style = Stroke(width = 2.5f)
        )
        drawCircle(
            color = Color.Red.copy(alpha = 0.3f),
            radius = 6.dp.toPx(),
            center = origin,
            style = Fill
        )

        // 绘制 target 点（小圆，绿色填充）
        drawCircle(
            color = Color.Green.copy(alpha = 0.9f),
            radius = 4.dp.toPx(),
            center = target,
            style = Fill
        )

        // 绘制箭头头部
        val arrowLen = 15f
        val arrowAngle = 0.5f  // 弧度
        val angle = kotlin.math.atan2(direction.y, direction.x)
        val tip = origin + direction * 0.85f
        val leftWing = Offset(
            tip.x - arrowLen * kotlin.math.cos(angle + arrowAngle),
            tip.y - arrowLen * kotlin.math.sin(angle + arrowAngle)
        )
        val rightWing = Offset(
            tip.x - arrowLen * kotlin.math.cos(angle - arrowAngle),
            tip.y - arrowLen * kotlin.math.sin(angle - arrowAngle)
        )
        drawLine(color = arrowColor, start = tip, end = leftWing, strokeWidth = 2.5f)
        drawLine(color = arrowColor, start = tip, end = rightWing, strokeWidth = 2.5f)

        // 标注序号
        val textPaint = Paint().apply {
            textSize = 10.dp.toPx()
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        drawIntoCanvas { canvas ->
            textPaint.color = arrowColor.toArgb()
            canvas.nativeCanvas.drawText(
                "$idx:$originIdx->$targetIdx",
                (origin.x + target.x) / 2,
                (origin.y + target.y) / 2,
                textPaint
            )
        }
    }
}

/**
 * 绘制所有 133 点 Contour（调试用）
 */
private fun DrawScope.drawAllContours(
    faceWarpParams: FaceWarpParams,
    toCanvasPoint: (Offset) -> Offset
) {
    val allContours = faceWarpParams.allContours
    if (allContours.totalPointCount() == 0) return

    // 绘制脸部轮廓（品红色）
    allContours.faceOval.map { Offset(it.x, it.y) }.map(toCanvasPoint).let { points ->
        drawContourPoints(points, Color.Magenta.copy(alpha = 0.7f), 3.dp.toPx())
    }

    // 绘制左眉毛（青色）
    allContours.leftEyebrowTop.map { Offset(it.x, it.y) }.map(toCanvasPoint).let { points ->
        drawContourPoints(points, Color.Cyan.copy(alpha = 0.8f), 2.dp.toPx())
    }
    allContours.leftEyebrowBottom.map { Offset(it.x, it.y) }.map(toCanvasPoint).let { points ->
        drawContourPoints(points, Color.Cyan.copy(alpha = 0.6f), 2.dp.toPx())
    }

    // 绘制右眉毛（蓝色）
    allContours.rightEyebrowTop.map { Offset(it.x, it.y) }.map(toCanvasPoint).let { points ->
        drawContourPoints(points, Color.Blue.copy(alpha = 0.8f), 2.dp.toPx())
    }
    allContours.rightEyebrowBottom.map { Offset(it.x, it.y) }.map(toCanvasPoint).let { points ->
        drawContourPoints(points, Color.Blue.copy(alpha = 0.6f), 2.dp.toPx())
    }

    // 绘制左眼（黄色）
    allContours.leftEye.map { Offset(it.x, it.y) }.map(toCanvasPoint).let { points ->
        drawContourPoints(points, Color.Yellow.copy(alpha = 0.9f), 2.dp.toPx())
    }

    // 绘制右眼（绿色）
    allContours.rightEye.map { Offset(it.x, it.y) }.map(toCanvasPoint).let { points ->
        drawContourPoints(points, Color.Green.copy(alpha = 0.9f), 2.dp.toPx())
    }

    // 绘制上嘴唇（红色）
    allContours.upperLipTop.map { Offset(it.x, it.y) }.map(toCanvasPoint).let { points ->
        drawContourPoints(points, Color.Red.copy(alpha = 0.8f), 2.dp.toPx())
    }
    allContours.upperLipBottom.map { Offset(it.x, it.y) }.map(toCanvasPoint).let { points ->
        drawContourPoints(points, Color.Red.copy(alpha = 0.6f), 2.dp.toPx())
    }

    // 绘制下嘴唇（橙色）
    allContours.lowerLipTop.map { Offset(it.x, it.y) }.map(toCanvasPoint).let { points ->
        drawContourPoints(points, Color(0xFFFF9800).copy(alpha = 0.6f), 2.dp.toPx())
    }
    allContours.lowerLipBottom.map { Offset(it.x, it.y) }.map(toCanvasPoint).let { points ->
        drawContourPoints(points, Color(0xFFFF9800).copy(alpha = 0.8f), 2.dp.toPx())
    }

    // 绘制鼻梁（紫色）
    allContours.noseBridge.map { Offset(it.x, it.y) }.map(toCanvasPoint).let { points ->
        drawContourPoints(points, Color(0xFF9C27B0).copy(alpha = 0.8f), 2.dp.toPx())
    }

    // 绘制鼻翼（深紫色）
    allContours.noseBottom.map { Offset(it.x, it.y) }.map(toCanvasPoint).let { points ->
        drawContourPoints(points, Color(0xFF673AB7).copy(alpha = 0.8f), 2.dp.toPx())
    }

    // 绘制左脸颊（浅绿色）
    allContours.leftCheek.map { Offset(it.x, it.y) }.map(toCanvasPoint).let { points ->
        drawContourPoints(points, Color(0xFF8BC34A).copy(alpha = 0.7f), 2.dp.toPx())
    }

    // 绘制右脸颊（深绿色）
    allContours.rightCheek.map { Offset(it.x, it.y) }.map(toCanvasPoint).let { points ->
        drawContourPoints(points, Color(0xFF4CAF50).copy(alpha = 0.7f), 2.dp.toPx())
    }

    // 显示点数统计
    val totalCount = allContours.totalPointCount()
    drawRect(
        color = Color.Black.copy(alpha = 0.5f),
        topLeft = Offset(10f, 10f),
        size = Size(200f, 120f)
    )
}

/**
 * 绘制 Contour 点（小圆点 + 连线）
 */
private fun DrawScope.drawContourPoints(
    points: List<Offset>,
    color: Color,
    strokeWidth: Float
) {
    if (points.isEmpty()) return

    // 绘制点
    points.forEach { point ->
        drawCircle(
            color = color,
            radius = 3.dp.toPx(),
            center = point,
            style = Fill
        )
    }

    // 绘制连线
    if (points.size >= 2) {
        points.zipWithNext().forEach { (start, end) ->
            drawLine(
                color = color.copy(alpha = 0.5f),
                start = start,
                end = end,
                strokeWidth = strokeWidth * 0.5f
            )
        }
    }
}

