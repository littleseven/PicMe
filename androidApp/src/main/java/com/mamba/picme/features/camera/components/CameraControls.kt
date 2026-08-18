package com.mamba.picme.features.camera.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mamba.picme.R
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.core.designsystem.AppColors
import com.mamba.picme.core.designsystem.CameraTokens
import com.mamba.picme.core.designsystem.IconSize
import com.mamba.picme.core.designsystem.ShutterTokens

/**
 * 底部控制区（2026-08-18 Ardot 定稿三轮修正：specs/screens/refs/ardot/camera-idle.png）。
 * 行序（上→下）：模式行 → 变焦胶囊行 → 快门行；行距 模式→变焦 20dp、变焦→快门 28dp；
 * 快门行三元素垂直居中对齐；快门底距屏底 60dp（camera.bottomControlsPaddingBottom，
 * 一轮 123dp 偏高 / 二轮 20dp 偏低后的折中）。
 */
@Composable
fun CameraBottomControls(
    lastMedia: MediaAsset?,
    zoomRatio: Float,
    minZoomRatio: Float,
    maxZoomRatio: Float,
    captureMode: MediaType,
    isRecording: Boolean,
    onZoomPresetClick: (Float) -> Unit,
    onGalleryClick: () -> Unit,
    onCaptureClick: () -> Unit,
    onFlipCamera: () -> Unit,
    onModeChange: (MediaType) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = CameraTokens.bottomControlsPaddingBottom),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        ModeSelector(
            currentMode = captureMode,
            onModeChange = onModeChange,
            modifier = Modifier.fillMaxWidth()
        )

        // 变焦条常驻（2026-08-15 改版：面板均为顶部内联/底部覆盖，不再隐藏底栏控件）
        ZoomControls(
            zoomRatio = zoomRatio,
            minZoomRatio = minZoomRatio,
            maxZoomRatio = maxZoomRatio,
            onZoomClick = onZoomPresetClick
        )

        // 变焦→快门 28dp（20 spacedBy + 8 Spacer）：快门大圆与胶囊行拉开呼吸感
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GalleryThumbnail(lastMedia = lastMedia, onClick = onGalleryClick)
            ShutterButton(isRecording = isRecording, mode = captureMode, onClick = onCaptureClick)
            FlipCameraButton(onClick = onFlipCamera)
        }
    }
}

/**
 * 变焦预设条（Ardot：无容器、独立胶囊漂浮于预览）。
 * 未选 = Black@50% + 白字；选中 = 白底 + 黑字；12sp Bold，高 32dp 全圆胶囊。
 */
@Composable
private fun ZoomControls(
    zoomRatio: Float,
    minZoomRatio: Float,
    maxZoomRatio: Float,
    onZoomClick: (Float) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(CameraTokens.zoomBarSpacing)) {
        // 0.6x 按钮：仅在设备支持最小变焦比 <= 0.6 时显示
        if (minZoomRatio <= 0.6f) {
            ZoomPill(
                label = "0.6x",
                isSelected = zoomRatio < 0.9f
            ) { onZoomClick(0.6f) }
        }
        ZoomPill(
            label = "1x",
            isSelected = zoomRatio >= 0.9f && zoomRatio < 1.5f
        ) {
            onZoomClick(1f)
        }
        ZoomPill(
            label = "2x",
            isSelected = zoomRatio >= 1.5f && zoomRatio < 2.8f
        ) {
            onZoomClick(2f)
        }
        // 3.2x 按钮：仅在设备支持最大变焦比 >= 3.2 时显示
        if (maxZoomRatio >= 3.2f) {
            ZoomPill(
                label = "3.2x",
                isSelected = zoomRatio >= 2.8f
            ) { onZoomClick(3.2f) }
        }
    }
}

@Composable
private fun ZoomPill(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(CameraTokens.zoomCapsuleHeight)
            .clip(CircleShape)
            .background(
                if (isSelected) {
                    Color.White
                } else {
                    Color.Black.copy(alpha = CameraTokens.zoomUnselectedBgAlpha)
                }
            )
            .clickable { onClick() }
            .padding(horizontal = CameraTokens.zoomCapsulePaddingH),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isSelected) Color.Black else Color.White,
            fontSize = CameraTokens.zoomFontSize.value.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

/**
 * 模式选择行（Ardot：全白文字，选中态仅字重 SemiBold，不用 accent 色）。
 */
@Composable
private fun ModeSelector(
    currentMode: MediaType,
    onModeChange: (MediaType) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val modes = listOf(MediaType.VIDEO, MediaType.PHOTO, MediaType.DOCUMENT)
        modes.forEach { mode ->
            val label = when (mode) {
                MediaType.VIDEO -> stringResource(R.string.video)
                MediaType.PHOTO -> stringResource(R.string.photo)
                MediaType.DOCUMENT -> stringResource(R.string.document)
            }
            Text(
                text = label,
                color = CameraTokens.cameraAccentOn,
                fontSize = CameraTokens.modeTabFontSize.value.sp,
                fontWeight = if (currentMode == mode) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .clickable { onModeChange(mode) }
            )
        }
    }
}

/**
 * 快门（Ardot：#E4E4E4 外环实心圆 76dp + 9dp 环厚 + 白内核 58dp；录像态内核红/白方块）。
 */
@Composable
private fun ShutterButton(isRecording: Boolean, mode: MediaType, onClick: () -> Unit) {
    val innerColor = if (mode == MediaType.VIDEO) Color.Red else Color.White
    var lastClickTime by remember { mutableLongStateOf(0L) }
    val debounceIntervalMs = 500L
    val shutterDesc = stringResource(R.string.shutter)

    Box(
        modifier = Modifier
            .size(ShutterTokens.diameter)
            .clip(CircleShape)
            .background(AppColors.shutterRing)
            .padding(ShutterTokens.ringWidth)
            .semantics { contentDescription = shutterDesc }
            .clickable {
                val now = System.currentTimeMillis()
                if (now - lastClickTime >= debounceIntervalMs) {
                    lastClickTime = now
                    onClick()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(ShutterTokens.innerDiameter)
                .clip(CircleShape)
                .background(innerColor),
            contentAlignment = Alignment.Center
        ) {
            if (isRecording) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White)
                )
            }
        }
    }
}

/**
 * 相册入口（Ardot：#404040 圆钮 + 浅色 Photo 占位图标 + 钮下「相册」11sp 标签）。
 */
@Composable
private fun GalleryThumbnail(lastMedia: MediaAsset?, onClick: () -> Unit) {
    val galleryDesc = stringResource(R.string.gallery)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(CameraTokens.albumThumbColor)
                .clickable { onClick() }
                .semantics { contentDescription = galleryDesc },
            contentAlignment = Alignment.Center
        ) {
            if (lastMedia != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(lastMedia.uri)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Photo,
                    contentDescription = null,
                    tint = CameraTokens.albumPlaceholderIconColor,
                    modifier = Modifier.size(IconSize.sm)
                )
            }
        }
        ControlLabel(text = stringResource(R.string.gallery))
    }
}

/**
 * 翻转摄像头（Ardot：白@20% 圆钮 + 深色 #1C1B1F 图标 + 钮下「翻转」11sp 标签）。
 */
@Composable
private fun FlipCameraButton(onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.2f))
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Cameraswitch,
                contentDescription = stringResource(R.string.a11y_switch_camera),
                tint = CameraTokens.flipIconColor,
                modifier = Modifier.size(IconSize.sm)
            )
        }
        ControlLabel(text = stringResource(R.string.camera_label_flip))
    }
}

/** 快门行左右钮下方的文字标签（11sp Medium 白，距钮 4dp）。 */
@Composable
private fun ControlLabel(text: String) {
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = text,
        color = CameraTokens.cameraAccentOn,
        fontSize = CameraTokens.controlLabelFontSize.value.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1
    )
}
