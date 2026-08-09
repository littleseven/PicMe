package com.mamba.picme.features.camera.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Crop169
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.FilterBAndW
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Landscape
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mamba.picme.R

@OptIn(ExperimentalLayoutApi::class) // statusBarsIgnoringVisibility：沉浸式下仍避让刘海
@Composable
fun CameraLeftControls(
    onResetCameraMemoryState: () -> Unit,
    onToggleLogOverlay: () -> Unit,
    debugUiEnabled: Boolean,
    showLogOverlay: Boolean,
    onLlmRelease: () -> Unit = {},
    onFaceDetectRelease: () -> Unit = {},
    onNavigateBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(16.dp)
            // 🔴 相机页沉浸式隐藏系统栏后 statusBars inset 归零（statusBarsPadding 失效），
            // 必须用 IgnoringVisibility 才能稳定避让刘海/挖孔区域
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ControlButton(
            icon = Icons.AutoMirrored.Rounded.ArrowBack,
            onClick = onNavigateBack,
            contentDescription = stringResource(R.string.back)
        )
        ControlButton(
            icon = Icons.Rounded.Refresh,
            onClick = onResetCameraMemoryState,
            contentDescription = stringResource(R.string.a11y_reset_camera)
        )
        if (debugUiEnabled) {
            ControlButton(
                icon = Icons.Rounded.Terminal,
                onClick = onToggleLogOverlay,
                contentDescription = stringResource(R.string.a11y_log_overlay),
                isActive = showLogOverlay
            )

            // LLM 全量释放
            ControlButton(
                icon = Icons.Rounded.Psychology,
                onClick = onLlmRelease,
                contentDescription = stringResource(R.string.a11y_release_llm),
                tint = Color(0xFF64B5F6),
                modifier = Modifier.size(36.dp)
            )

            // Face Detection 全量释放
            ControlButton(
                icon = Icons.Rounded.Face,
                onClick = onFaceDetectRelease,
                contentDescription = stringResource(R.string.a11y_release_face),
                tint = Color(0xFF81C784),
                modifier = Modifier.size(36.dp)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class) // statusBarsIgnoringVisibility：沉浸式下仍避让刘海
@Composable
fun CameraRightControls(
    onToggleBeauty: () -> Unit,
    onToggleFilter: () -> Unit,
    onToggleRatio: () -> Unit,
    onToggleScene: () -> Unit,
    onToggleGrid: () -> Unit,
    onToggleProPanel: () -> Unit,
    onToggleBeautyEnabled: () -> Unit,
    isBeautySelected: Boolean,
    isFilterSelected: Boolean,
    isRatioSelected: Boolean,
    isSceneActive: Boolean,
    isGridActive: Boolean,
    isProPanelOpen: Boolean,
    isBeautyEnabled: Boolean,
    currentRatio: Int,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(16.dp)
            // 🔴 同左列：沉浸式下 statusBarsPadding 归零，改用 IgnoringVisibility 避让刘海
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.End
    ) {
        BeautyEntryButton(
            isEnabled = isBeautyEnabled,
            isPanelOpen = isBeautySelected,
            onTogglePanel = onToggleBeauty,
            onToggleEnabled = onToggleBeautyEnabled,
            contentDescription = stringResource(R.string.beauty)
        )

        Spacer(modifier = Modifier.height(8.dp))

        ControlButton(
            icon = when (currentRatio) {
                0 -> Icons.Rounded.AspectRatio
                1 -> Icons.Rounded.Crop169
                2 -> Icons.Rounded.CropSquare
                else -> Icons.Rounded.CropFree
            },
            onClick = onToggleRatio,
            contentDescription = stringResource(R.string.aspect_ratio),
            isActive = isRatioSelected
        )
        ControlButton(
            icon = Icons.Rounded.GridOn,
            onClick = onToggleGrid,
            contentDescription = stringResource(R.string.grid),
            isActive = isGridActive
        )

        Spacer(modifier = Modifier.height(8.dp))

        ControlButton(
            icon = Icons.Rounded.Landscape,
            onClick = onToggleScene,
            contentDescription = stringResource(R.string.scene),
            isActive = isSceneActive
        )
        ControlButton(
            icon = Icons.Rounded.FilterBAndW,
            onClick = onToggleFilter,
            contentDescription = stringResource(R.string.a11y_filter),
            isActive = isFilterSelected
        )

        Spacer(modifier = Modifier.height(8.dp))

        ControlButton(
            icon = Icons.Filled.Tune,
            onClick = onToggleProPanel,
            contentDescription = stringResource(R.string.pro_mode),
            isActive = isProPanelOpen
        )
    }
}

@Composable
private fun BeautyEntryButton(
    isEnabled: Boolean,
    isPanelOpen: Boolean,
    onTogglePanel: () -> Unit,
    onToggleEnabled: () -> Unit,
    contentDescription: String
) {
    Box(contentAlignment = Alignment.TopEnd) {
        FilledIconButton(
            onClick = onTogglePanel,
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = when {
                    isPanelOpen -> MaterialTheme.colorScheme.primary
                    isEnabled -> MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                    else -> Color.Black.copy(alpha = 0.5f)
                },
                contentColor = when {
                    isPanelOpen -> Color.Black
                    isEnabled -> MaterialTheme.colorScheme.primary
                    else -> Color.White.copy(alpha = 0.55f)
                }
            )
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoFixHigh,
                contentDescription = contentDescription,
                modifier = Modifier.size(24.dp)
            )
        }

        if (isEnabled && !isPanelOpen) {
            Box(
                modifier = Modifier
                    .padding(top = 4.dp, end = 4.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .border(1.dp, Color.Black.copy(alpha = 0.6f), CircleShape)
            )
        }
    }
}

@Composable
fun ControlButton(
    icon: ImageVector,
    onClick: () -> Unit,
    contentDescription: String,
    isActive: Boolean = false,
    tint: Color? = null,
    modifier: Modifier = Modifier
) {
    FilledIconButton(
        onClick = onClick,
        modifier = modifier.size(48.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.primary
            } else {
                Color.Black.copy(alpha = 0.5f)
            },
            contentColor = tint ?: if (isActive) Color.Black else Color.White
        )
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, modifier = Modifier.size(24.dp))
    }
}
