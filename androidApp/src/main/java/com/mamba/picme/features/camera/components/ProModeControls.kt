package com.mamba.picme.features.camera.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mamba.picme.R
import com.mamba.picme.beauty.api.BeautySettings
import com.mamba.picme.core.designsystem.components.AppSlider
import kotlin.math.abs

/**
 * ProMode 面板内容（WB chips + EV/对比度/饱和度/色温滑杆）。
 * 2026-08-15 改版：由底部半屏 Sheet 改为顶部内联面板（specs/screens/camera.yaml §4 inline_panels），
 * 容器外壳由调用方 InlineControlPanel 提供，本组件仅承载内容、不自带滚动/手柄/关闭按钮。
 */
@Composable
fun ProModeControlsContent(
    exposure: Int,
    exposureRange: IntRange,
    onExposureChange: (Int) -> Unit,
    whiteBalance: Int,
    onWhiteBalanceChange: (Int) -> Unit,
    onTemperatureManualChange: () -> Unit = {},
    beautySettings: BeautySettings = BeautySettings(),
    onBeautySettingsChanged: (BeautySettings) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
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
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
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
                        // 手动调色温 = 脱离 WB 预设，chip 回到「自动」选中态（不改 temperature）
                        onTemperatureManualChange()
                    },
                    modifier = Modifier.fillMaxWidth().height(36.dp)
                )
            }
        )
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
