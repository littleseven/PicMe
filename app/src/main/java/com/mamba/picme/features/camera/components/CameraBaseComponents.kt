package com.mamba.picme.features.camera.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mamba.picme.R
import com.mamba.picme.core.designsystem.components.AppSlider
import com.mamba.picme.features.camera.CameraAspectRatio
import com.mamba.picme.features.camera.GridType
import com.mamba.picme.features.camera.ScenePreset

private const val PANEL_HEIGHT_RATIO = 0.5f

@Composable
fun ControlPanel(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val panelMaxHeight = screenHeight * PANEL_HEIGHT_RATIO

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(panelMaxHeight + 24.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.55f),
                            Color.Black.copy(alpha = 0.82f)
                        )
                    )
                )
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .heightIn(max = panelMaxHeight),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            shadowElevation = 16.dp,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 10.dp, bottom = 4.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
fun RatioSelector(selectedRatio: CameraAspectRatio, onRatioSelected: (CameraAspectRatio) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        RatioItem(
            label = stringResource(R.string.ratio_4_3),
            isSelected = selectedRatio == CameraAspectRatio.RATIO_4_3
        ) {
            onRatioSelected(CameraAspectRatio.RATIO_4_3)
        }
        RatioItem(
            label = stringResource(R.string.ratio_16_9),
            isSelected = selectedRatio == CameraAspectRatio.RATIO_16_9
        ) {
            onRatioSelected(CameraAspectRatio.RATIO_16_9)
        }
        RatioItem(
            label = stringResource(R.string.ratio_full),
            isSelected = selectedRatio == CameraAspectRatio.RATIO_FULL
        ) {
            onRatioSelected(CameraAspectRatio.RATIO_FULL)
        }
    }
}

@Composable
private fun RatioItem(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.DarkGray
        )
    ) {
        Text(
            text = label,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp
        )
    }
}

@Composable
fun SceneSelector(currentScene: ScenePreset, onSceneSelected: (ScenePreset) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ScenePreset.values().forEach { scene ->
            val label = when (scene) {
                ScenePreset.NONE -> stringResource(R.string.scene_none)
                ScenePreset.NIGHT -> stringResource(R.string.scene_night)
                ScenePreset.MOON -> stringResource(R.string.scene_moon)
            }
            Button(
                onClick = { onSceneSelected(scene) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (currentScene == scene) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.DarkGray
                    }
                )
            ) {
                Text(
                    text = label,
                    color = if (currentScene == scene) Color.Black else Color.White
                )
            }
        }
    }
}

@Composable
fun GridSelector(currentGrid: GridType, onGridSelected: (GridType) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GridType.values().forEach { grid ->
            val label = when (grid) {
                GridType.NONE -> stringResource(R.string.grid_none)
                GridType.THIRDS -> stringResource(R.string.grid_thirds)
                GridType.GOLDEN -> stringResource(R.string.grid_golden)
            }
            Button(
                onClick = { onGridSelected(grid) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (currentGrid == grid) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.DarkGray
                    }
                )
            ) {
                Text(
                    text = label,
                    color = if (currentGrid == grid) Color.Black else Color.White
                )
            }
        }
    }
}

@Composable
private fun ExpandableSection(
    title: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val rotation by animateFloatAsState(if (isExpanded) 180f else 0f, label = "rotation")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(rotation),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = if (isExpanded) "收起" else "展开",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically() + fadeIn(initialAlpha = 0.3f),
            exit = shrinkVertically() + fadeOut(targetAlpha = 0.3f)
        ) {
            Column(
                modifier = Modifier.padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
fun BeautySlider(
    icon: ImageVector,
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float> = 0f..100f,
    onValueChange: (Float) -> Unit,
    onReset: () -> Unit
) {
    val displayValue = if (valueRange.start < 0) {
        value.toInt()
    } else {
        (value * 100 / valueRange.endInclusive).toInt()
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(onClick = onReset)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (value != 0f) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    },
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = label,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Text(
                text = if (value != 0f) "$displayValue" else "--",
                color = if (value != 0f) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }

        AppSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
        )
    }
}
