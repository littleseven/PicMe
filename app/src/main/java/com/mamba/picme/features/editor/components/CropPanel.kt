package com.mamba.picme.features.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mamba.picme.R
import com.mamba.picme.features.editor.AspectRatio
import com.mamba.picme.features.editor.CropRecipe

@Composable
fun CropPanel(
    crop: CropRecipe,
    onChange: (CropRecipe) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = stringResource(R.string.crop_ratio_label),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AspectRatio.entries.forEach { ratio ->
                FilterChip(
                    selected = crop.aspectRatio == ratio,
                    onClick = { onChange(crop.copy(aspectRatio = ratio)) },
                    label = { Text(stringResource(ratio.labelRes)) }
                )
            }
        }
    }
}

/**
 * 小米相册风格：旋转/镜像以半透明圆形按钮悬浮在预览区底部左右角，
 * 仅在 CROP tab 下显示，底部面板不再为它们单独占一行。
 */
@Composable
fun BoxScope.CropTransformOverlay(
    crop: CropRecipe,
    onChange: (CropRecipe) -> Unit
) {
    CropTransformButton(
        onClick = { onChange(crop.copy(rotation = (crop.rotation - 90).mod(360))) },
        contentDescription = stringResource(R.string.rotate_left),
        modifier = Modifier.align(Alignment.BottomStart)
    ) {
        Icon(
            Icons.AutoMirrored.Filled.RotateLeft,
            contentDescription = null,
            tint = Color.White
        )
    }
    CropTransformButton(
        onClick = { onChange(crop.copy(flippedH = !crop.flippedH)) },
        contentDescription = stringResource(R.string.flip_horizontal),
        modifier = Modifier.align(Alignment.BottomEnd)
    ) {
        Icon(
            Icons.Default.Flip,
            contentDescription = null,
            tint = Color.White
        )
    }
}

@Composable
private fun CropTransformButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .padding(16.dp)
            .size(44.dp)
            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
            .semantics { this.contentDescription = contentDescription }
    ) {
        icon()
    }
}
