package com.mamba.picme.features.camera.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.mamba.picme.core.designsystem.CameraTokens

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
            .navigationBarsPadding()
            .padding(bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // 变焦条常驻（2026-08-15 改版：面板均为顶部内联/底部覆盖，不再隐藏底栏控件）
        ZoomControls(
            zoomRatio = zoomRatio,
            minZoomRatio = minZoomRatio,
            maxZoomRatio = maxZoomRatio,
            onZoomClick = onZoomPresetClick
        )

        ModeSelector(
            currentMode = captureMode,
            onModeChange = onModeChange,
            modifier = Modifier.fillMaxWidth()
        )

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

@Composable
private fun ZoomControls(
    zoomRatio: Float,
    minZoomRatio: Float,
    maxZoomRatio: Float,
    onZoomClick: (Float) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(CameraTokens.zoomBarSpacing),
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.4f))
            .padding(horizontal = CameraTokens.zoomCapsulePaddingH, vertical = 6.dp)
    ) {
        // 0.6x 按钮：仅在设备支持最小变焦比 <= 0.6 时显示
        if (minZoomRatio <= 0.6f) {
            ZoomButton(
                label = "0.6x",
                isSelected = zoomRatio < 0.9f
            ) { onZoomClick(0.6f) }
        }
        ZoomButton(
            label = "1x",
            isSelected = zoomRatio >= 0.9f && zoomRatio < 1.5f
        ) {
            onZoomClick(1f)
        }
        ZoomButton(
            label = "2x",
            isSelected = zoomRatio >= 1.5f && zoomRatio < 2.8f
        ) {
            onZoomClick(2f)
        }
        // 3.2x 按钮：仅在设备支持最大变焦比 >= 3.2 时显示
        if (maxZoomRatio >= 3.2f) {
            ZoomButton(
                label = "3.2x",
                isSelected = zoomRatio >= 2.8f
            ) { onZoomClick(3.2f) }
        }
    }
}

@Composable
private fun ZoomButton(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(CameraTokens.zoomCapsuleHeight)
            .clip(CircleShape)
            .background(if (isSelected) Color.White else Color.Transparent)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isSelected) Color.Black else Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ModeSelector(
    currentMode: MediaType,
    onModeChange: (MediaType) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(vertical = 8.dp),
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
                color = if (currentMode == mode) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.White.copy(alpha = 0.6f)
                },
                fontSize = 13.sp,
                fontWeight = if (currentMode == mode) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .clickable { onModeChange(mode) }
            )
        }
    }
}

@Composable
private fun ShutterButton(isRecording: Boolean, mode: MediaType, onClick: () -> Unit) {
    val color = if (mode == MediaType.VIDEO) Color.Red else Color.White
    var lastClickTime by remember { mutableLongStateOf(0L) }
    val debounceIntervalMs = 500L
    val shutterDesc = stringResource(R.string.shutter)

    Box(
        modifier = Modifier
            .size(76.dp)
            .border(4.dp, Color.White, CircleShape)
            .padding(6.dp)
            .clip(CircleShape)
            .background(color)
            .clickable {
                val now = System.currentTimeMillis()
                if (now - lastClickTime >= debounceIntervalMs) {
                    lastClickTime = now
                    onClick()
                }
            }
            .semantics { contentDescription = shutterDesc },
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

@Composable
private fun GalleryThumbnail(lastMedia: MediaAsset?, onClick: () -> Unit) {
    val galleryDesc = stringResource(R.string.gallery)
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color.DarkGray)
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
        }
    }
}

@Composable
private fun FlipCameraButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.2f))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Rounded.Cameraswitch, contentDescription = stringResource(R.string.a11y_switch_camera), tint = Color.White)
    }
}
