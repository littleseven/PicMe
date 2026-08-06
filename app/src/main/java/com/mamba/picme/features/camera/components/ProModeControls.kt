package com.mamba.picme.features.camera.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mamba.picme.R
import com.mamba.picme.beauty.api.BeautySettings
import com.mamba.picme.core.designsystem.components.AppSlider
import kotlin.math.abs

private const val PANEL_HEIGHT_RATIO = 0.5f

@Composable
fun ProModeControls(
    exposure: Int,
    exposureRange: IntRange,
    onExposureChange: (Int) -> Unit,
    whiteBalance: Int,
    onWhiteBalanceChange: (Int) -> Unit,
    onClose: () -> Unit,
    beautySettings: BeautySettings = BeautySettings(),
    onBeautySettingsChanged: (BeautySettings) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val panelMaxHeight = screenHeight * PANEL_HEIGHT_RATIO

    Box(
        modifier = modifier
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(top = 0.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onClose() }
                        .padding(top = 10.dp, bottom = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 36.dp, height = 4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                    )
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.white_balance),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(listOf(0, 1, 2, 3, 4)) { mode ->
                            val label = when (mode) {
                                0 -> stringResource(R.string.wb_auto)
                                1 -> stringResource(R.string.wb_sunny)
                                2 -> stringResource(R.string.wb_cloudy)
                                3 -> stringResource(R.string.wb_incandescent)
                                4 -> stringResource(R.string.wb_fluorescent)
                                else -> ""
                            }
                            FilterChip(
                                selected = whiteBalance == mode,
                                onClick = { onWhiteBalanceChange(mode) },
                                label = {
                                    Text(
                                        text = label,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                    selectedLabelColor = Color.Black
                                )
                            )
                        }
                    }
                }

                val exposureValueRange = exposureRange.first.toFloat()..exposureRange.last.toFloat()
                val exposureDisplayText = if (exposure >= 0) "+$exposure" else "$exposure"
                ProModeSlider(
                    label = stringResource(R.string.exposure),
                    valueText = exposureDisplayText,
                    isValueChanged = exposure != 0,
                    sliderContent = {
                        AppSlider(
                            value = exposure.toFloat(),
                            valueRange = exposureValueRange,
                            steps = if (exposureRange.last > exposureRange.first) {
                                exposureRange.last - exposureRange.first - 1
                            } else {
                                0
                            },
                            onValueChange = { newValue -> onExposureChange(newValue.toInt()) },
                            modifier = Modifier.fillMaxWidth().height(36.dp)
                        )
                    }
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                )

                ProModeSlider(
                    label = stringResource(R.string.contrast),
                    valueText = if (abs(beautySettings.contrast - 50f) > 0.5f)
                        beautySettings.contrast.toInt().toString() else "--",
                    isValueChanged = abs(beautySettings.contrast - 50f) > 0.5f,
                    sliderContent = {
                        AppSlider(
                            value = beautySettings.contrast,
                            valueRange = 0f..200f,
                            onValueChange = { value ->
                                onBeautySettingsChanged(beautySettings.copy(contrast = value))
                            },
                            modifier = Modifier.fillMaxWidth().height(36.dp)
                        )
                    }
                )

                ProModeSlider(
                    label = stringResource(R.string.saturation),
                    valueText = if (abs(beautySettings.saturation - 100f) > 0.5f)
                        beautySettings.saturation.toInt().toString() else "--",
                    isValueChanged = abs(beautySettings.saturation - 100f) > 0.5f,
                    sliderContent = {
                        AppSlider(
                            value = beautySettings.saturation,
                            valueRange = 0f..200f,
                            onValueChange = { value ->
                                onBeautySettingsChanged(beautySettings.copy(saturation = value))
                            },
                            modifier = Modifier.fillMaxWidth().height(36.dp)
                        )
                    }
                )

                ProModeSlider(
                    label = stringResource(R.string.color_temperature),
                    valueText = if (abs(beautySettings.temperature - 5000f) > 50f)
                        "${beautySettings.temperature.toInt()}K" else "--",
                    isValueChanged = abs(beautySettings.temperature - 5000f) > 50f,
                    sliderContent = {
                        AppSlider(
                            value = beautySettings.temperature,
                            valueRange = 2000f..8000f,
                            onValueChange = { value ->
                                onBeautySettingsChanged(beautySettings.copy(temperature = value))
                            },
                            modifier = Modifier.fillMaxWidth().height(36.dp)
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun ProModeSlider(
    label: String,
    valueText: String,
    isValueChanged: Boolean,
    sliderContent: @Composable () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = valueText,
                color = if (isValueChanged) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        sliderContent()
    }
}
