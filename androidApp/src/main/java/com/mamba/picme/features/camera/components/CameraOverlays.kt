package com.mamba.picme.features.camera.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mamba.picme.R
import com.mamba.picme.beauty.api.BeautySettings
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.features.camera.GridType
import com.mamba.picme.features.camera.ScenePreset
import com.mamba.picme.beauty.api.FilterType
import com.mamba.picme.beauty.api.displayNameRes
import java.util.Locale

@Composable
fun CameraOverlays(
    isStable: Boolean,
    gridType: GridType,
    facePoint: Offset?,
    focusAlpha: Float,
    showInfo: Boolean,
    lensFacing: Int,
    captureMode: MediaType,
    zoomRatio: Float,
    aspectRatio: Int,
    selectedFilter: FilterType,
    beautySettings: BeautySettings,
    exposureCompensation: Int,
    whiteBalanceMode: Int,
    currentScene: ScenePreset,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        CompositionGrid(gridType = gridType)

        // [RD] 人脸对焦十字星：使用 Primary 色，带弹簧动画
        facePoint?.let { point ->
            if (focusAlpha > 0f) {
                FaceFocusCrosshair(
                    offset = point,
                    alpha = focusAlpha,
                    isActive = true
                )
            }
        }

        if (showInfo) {
            CameraInfoOverlay(
                captureMode = captureMode,
                lensFacing = lensFacing,
                zoomRatio = zoomRatio,
                aspectRatio = aspectRatio,
                filter = selectedFilter,
                beautySettings = beautySettings,
                exposureCompensation = exposureCompensation,
                whiteBalanceMode = whiteBalanceMode,
                currentScene = currentScene,
                gridType = gridType,
                isStable = isStable,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 100.dp) // Below top control bar
            )
        }
    }
}

@Composable
fun CameraInfoOverlay(
    captureMode: MediaType,
    lensFacing: Int,
    zoomRatio: Float,
    aspectRatio: Int,
    filter: FilterType,
    beautySettings: BeautySettings,
    exposureCompensation: Int,
    whiteBalanceMode: Int,
    currentScene: ScenePreset,
    gridType: GridType,
    isStable: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.widthIn(max = 280.dp),
        color = Color.Black.copy(alpha = 0.5f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CameraInfoTitle(isStable = isStable)

            InfoGroup(title = stringResource(R.string.info_group_device)) {
                InfoItem(
                    label = stringResource(R.string.info_lens_facing),
                    value = if (lensFacing == 1) {
                        stringResource(R.string.info_back)
                    } else {
                        stringResource(R.string.info_front)
                    }
                )
                InfoItem(
                    label = stringResource(R.string.info_zoom),
                    value = String.format(Locale.US, "%.1fx", zoomRatio)
                )
                InfoItem(
                    label = stringResource(R.string.pro_mode),
                    value = captureMode.name
                )
            }

            InfoGroup(title = stringResource(R.string.info_group_settings)) {
                SettingsInfoItems(
                    aspectRatio = aspectRatio,
                    filter = filter,
                    currentScene = currentScene,
                    gridType = gridType,
                    exposureCompensation = exposureCompensation,
                    whiteBalanceMode = whiteBalanceMode,
                    beautySettings = beautySettings
                )
            }
        }
    }
}

@Composable
private fun CameraInfoTitle(isStable: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.camera_info),
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            letterSpacing = 1.sp
        )
        // Stability indicator
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(if (isStable) Color.Green else Color.Red, CircleShape)
        )
    }
}

@Composable
private fun SettingsInfoItems(
    aspectRatio: Int,
    filter: FilterType,
    currentScene: ScenePreset,
    gridType: GridType,
    exposureCompensation: Int,
    whiteBalanceMode: Int,
    beautySettings: BeautySettings
) {
    InfoItem(
        label = stringResource(R.string.info_aspect_ratio),
        value = when (aspectRatio) {
            0 -> "4:3"
            1 -> "16:9"
            else -> "FULL"
        }
    )
    InfoItem(
        label = stringResource(R.string.info_filter),
        value = stringResource(filter.displayNameRes)
    )
    InfoItem(
        label = stringResource(R.string.scene),
        value = currentScene.name
    )
    InfoItem(
        label = stringResource(R.string.grid),
        value = gridType.name
    )
    if (exposureCompensation != 0) {
        InfoItem(
            label = stringResource(R.string.ev),
            value = if (exposureCompensation > 0) {
                "+$exposureCompensation"
            } else {
                exposureCompensation.toString()
            }
        )
    }
    if (whiteBalanceMode != 0) {
        val wbLabel = when (whiteBalanceMode) {
            1 -> stringResource(R.string.wb_sunny)
            2 -> stringResource(R.string.wb_cloudy)
            3 -> stringResource(R.string.wb_incandescent)
            4 -> stringResource(R.string.wb_fluorescent)
            else -> stringResource(R.string.wb_auto)
        }
        InfoItem(label = stringResource(R.string.wb), value = wbLabel)
    }

    // 美颜信息
    InfoItem(
        label = stringResource(R.string.beauty),
        value = if (beautySettings.enabled) "ON" else "OFF"
    )

    if (beautySettings.enabled && beautySettings.hasAnyEffect()) {
        if (beautySettings.smoothing > 0f) {
            InfoItem(
                label = stringResource(R.string.smoothing),
                value = String.format(Locale.US, "%.1f", beautySettings.smoothing)
            )
        }
        if (beautySettings.whitening > 0f) {
            InfoItem(
                label = stringResource(R.string.whitening),
                value = String.format(Locale.US, "%.1f", beautySettings.whitening)
            )
        }
        if (beautySettings.bigEyes > 0f) {
            InfoItem(
                label = stringResource(R.string.big_eyes),
                value = String.format(Locale.US, "%.1f", beautySettings.bigEyes)
            )
        }
        if (beautySettings.slimFace > 0f) {
            InfoItem(
                label = stringResource(R.string.slim_face),
                value = String.format(Locale.US, "%.1f", beautySettings.slimFace)
            )
        }
    }
}

@Composable
private fun InfoGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title.uppercase(Locale.getDefault()),
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.5.sp
        )
        content()
    }
}

@Composable
private fun InfoItem(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
        Text(text = value, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun CompositionGrid(gridType: GridType) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val strokeWidth = 1.dp.toPx()
        val color = Color.White.copy(alpha = 0.5f)
        val pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)

        when (gridType) {
            GridType.THIRDS -> {
                drawThirdsGrid(color, strokeWidth, pathEffect)
            }

            GridType.GOLDEN -> {
                drawGoldenGrid(color, strokeWidth, pathEffect)
            }

            else -> {}
        }
    }
}

private fun DrawScope.drawThirdsGrid(
    color: Color,
    strokeWidth: Float,
    pathEffect: PathEffect
) {
    drawLine(
        color,
        Offset(size.width / 3, 0f),
        Offset(size.width / 3, size.height),
        strokeWidth,
        pathEffect = pathEffect
    )
    drawLine(
        color,
        Offset(size.width * 2 / 3, 0f),
        Offset(size.width * 2 / 3, size.height),
        strokeWidth,
        pathEffect = pathEffect
    )
    drawLine(
        color,
        Offset(0f, size.height / 3),
        Offset(size.width, size.height / 3),
        strokeWidth,
        pathEffect = pathEffect
    )
    drawLine(
        color,
        Offset(0f, size.height * 2 / 3),
        Offset(size.width, size.height * 2 / 3),
        strokeWidth,
        pathEffect = pathEffect
    )
}

private fun DrawScope.drawGoldenGrid(
    color: Color,
    strokeWidth: Float,
    pathEffect: PathEffect
) {
    val ratio = 0.618f
    drawLine(
        color,
        Offset(size.width * (1 - ratio), 0f),
        Offset(size.width * (1 - ratio), size.height),
        strokeWidth,
        pathEffect = pathEffect
    )
    drawLine(
        color,
        Offset(size.width * ratio, 0f),
        Offset(size.width * ratio, size.height),
        strokeWidth,
        pathEffect = pathEffect
    )
    drawLine(
        color,
        Offset(0f, size.height * (1 - ratio)),
        Offset(size.width, size.height * (1 - ratio)),
        strokeWidth,
        pathEffect = pathEffect
    )
    drawLine(
        color,
        Offset(0f, size.height * ratio),
        Offset(size.width, size.height * ratio),
        strokeWidth,
        pathEffect = pathEffect
    )
}

/**
 * [RD] 人脸对焦十字星组件 - 重构版
 *
 * 设计规范：
 * - 颜色：使用 Primary 色 (#00E5FF) 符合 HyperOS 设计系统
 * - 动画：弹簧动画 (Spring Animation) 实现流畅出现/消失
 * - 结构：四角 L 型标记 + 中心十字 + 中心点
 * - 尺寸：100dp 外框 + 16dp 中心十字
 *
 * @param offset 十字星中心点在屏幕上的坐标
 * @param alpha 透明度 (0f - 1f)
 * @param isActive 是否处于激活状态（影响颜色亮度）
 */
@Composable
fun FaceFocusCrosshair(
    offset: Offset,
    alpha: Float,
    isActive: Boolean = true
) {
    // 弹簧动画：实现自然的出现/消失效果
    val scaleAnimatable = remember { Animatable(0.6f) }

    LaunchedEffect(alpha) {
        val targetScale = if (alpha > 0.5f) 1f else 0.6f
        scaleAnimatable.animateTo(
            targetValue = targetScale,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        )
    }

    // 颜色定义：使用 Primary 色系统
    val primaryColor = Color(0xFF00E5FF)
    val crosshairColor = if (isActive) {
        primaryColor
    } else {
        primaryColor.copy(alpha = 0.5f)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(
                alpha = alpha,
                scaleX = scaleAnimatable.value,
                scaleY = scaleAnimatable.value
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 3.dp.toPx()
            val cornerLength = 20.dp.toPx()
            val indicatorSize = 100.dp.toPx()

            val centerX = offset.x
            val centerY = offset.y
            val left = centerX - indicatorSize / 2
            val top = centerY - indicatorSize / 2
            val right = left + indicatorSize
            val bottom = top + indicatorSize

            // 绘制四角 L 型标记
            drawCrosshairCorner(
                color = crosshairColor,
                strokeWidth = strokeWidth,
                cornerLength = cornerLength,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                corner = Corner.TOP_LEFT
            )
            drawCrosshairCorner(
                color = crosshairColor,
                strokeWidth = strokeWidth,
                cornerLength = cornerLength,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                corner = Corner.TOP_RIGHT
            )
            drawCrosshairCorner(
                color = crosshairColor,
                strokeWidth = strokeWidth,
                cornerLength = cornerLength,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                corner = Corner.BOTTOM_RIGHT
            )
            drawCrosshairCorner(
                color = crosshairColor,
                strokeWidth = strokeWidth,
                cornerLength = cornerLength,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                corner = Corner.BOTTOM_LEFT
            )

            // 绘制中心十字
            val centerCrossSize = 16.dp.toPx()
            val crossStrokeWidth = 2.dp.toPx()

            // 水平线
            drawLine(
                color = crosshairColor.copy(alpha = 0.8f),
                start = Offset(centerX - centerCrossSize / 2, centerY),
                end = Offset(centerX + centerCrossSize / 2, centerY),
                strokeWidth = crossStrokeWidth
            )

            // 垂直线
            drawLine(
                color = crosshairColor.copy(alpha = 0.8f),
                start = Offset(centerX, centerY - centerCrossSize / 2),
                end = Offset(centerX, centerY + centerCrossSize / 2),
                strokeWidth = crossStrokeWidth
            )

            // 中心点
            drawCircle(
                color = crosshairColor,
                radius = 3.dp.toPx(),
                center = Offset(centerX, centerY)
            )
        }
    }
}

/**
 * 四角位置枚举
 */
private enum class Corner {
    TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT
}

/**
 * 绘制十字星的单个角
 */
private fun DrawScope.drawCrosshairCorner(
    color: Color,
    strokeWidth: Float,
    cornerLength: Float,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    corner: Corner
) {
    when (corner) {
        Corner.TOP_LEFT -> {
            drawLine(
                color = color,
                start = Offset(left, top + cornerLength),
                end = Offset(left, top),
                strokeWidth = strokeWidth
            )
            drawLine(
                color = color,
                start = Offset(left, top),
                end = Offset(left + cornerLength, top),
                strokeWidth = strokeWidth
            )
        }
        Corner.TOP_RIGHT -> {
            drawLine(
                color = color,
                start = Offset(right - cornerLength, top),
                end = Offset(right, top),
                strokeWidth = strokeWidth
            )
            drawLine(
                color = color,
                start = Offset(right, top),
                end = Offset(right, top + cornerLength),
                strokeWidth = strokeWidth
            )
        }
        Corner.BOTTOM_RIGHT -> {
            drawLine(
                color = color,
                start = Offset(right, bottom - cornerLength),
                end = Offset(right, bottom),
                strokeWidth = strokeWidth
            )
            drawLine(
                color = color,
                start = Offset(right, bottom),
                end = Offset(right - cornerLength, bottom),
                strokeWidth = strokeWidth
            )
        }
        Corner.BOTTOM_LEFT -> {
            drawLine(
                color = color,
                start = Offset(left + cornerLength, bottom),
                end = Offset(left, bottom),
                strokeWidth = strokeWidth
            )
            drawLine(
                color = color,
                start = Offset(left, bottom),
                end = Offset(left, bottom - cornerLength),
                strokeWidth = strokeWidth
            )
        }
    }
}

/**
 * [DEPRECATED] 旧版人脸对焦指示器 - 保留用于兼容性
 * 请使用 FaceFocusCrosshair 替代
 */
@Composable
fun FaceFocusIndicator(offset: Offset, alpha: Float) {
    FaceFocusCrosshair(offset = offset, alpha = alpha, isActive = true)
}

private fun DrawScope.drawCorners(
    color: Color,
    strokeWidth: Float,
    bracketLen: Float
) {
    // Top-left
    drawLine(color, Offset(0f, 0f), Offset(bracketLen, 0f), strokeWidth)
    drawLine(color, Offset(0f, 0f), Offset(0f, bracketLen), strokeWidth)
    // Top-right
    drawLine(color, Offset(size.width, 0f), Offset(size.width - bracketLen, 0f), strokeWidth)
    drawLine(color, Offset(size.width, 0f), Offset(size.width, bracketLen), strokeWidth)
    // Bottom-left
    drawLine(color, Offset(0f, size.height), Offset(bracketLen, size.height), strokeWidth)
    drawLine(color, Offset(0f, size.height), Offset(0f, size.height - bracketLen), strokeWidth)
    // Bottom-right
    drawLine(color, Offset(size.width, size.height), Offset(size.width - bracketLen, size.height), strokeWidth)
    drawLine(color, Offset(size.width, size.height), Offset(size.width, size.height - bracketLen), strokeWidth)
}

/**
 * [NEW] OCR 文字检测预览提示
 */
@Composable
private fun OcrTextPreviewOverlay(
    text: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        color = Color.Black.copy(alpha = 0.8f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "检测到文字",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
                Text(
                    text = "${text.length} 字",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = Color.White.copy(alpha = 0.2f)
            )

            Text(
                text = text,
                fontSize = 14.sp,
                color = Color.White,
                maxLines = 2,
                lineHeight = 20.sp
            )
        }
    }
}
