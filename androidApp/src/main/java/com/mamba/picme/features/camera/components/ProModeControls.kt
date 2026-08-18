package com.mamba.picme.features.camera.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mamba.picme.R
import com.mamba.picme.beauty.api.BeautySettings
import com.mamba.picme.core.designsystem.CameraTokens
import com.mamba.picme.core.designsystem.components.AppSlider
import com.mamba.picme.core.designsystem.components.AppSliderStyle
import kotlin.math.abs

/**
 * ProMode 面板内容（WB chips + EV/对比度/饱和度/色温滑杆）。
 * 2026-08-15 改版：由底部半屏 Sheet 改为顶部内联面板（specs/screens/camera.yaml §4 inline_panels），
 * 容器外壳由调用方 InlineControlPanel 提供，本组件仅承载内容、不自带滚动/手柄/关闭按钮。
 * 2026-08-18 六修重设计（画布同步）：三段式=WB 组(标签@70+胶囊行) → 0.5dp 分隔线 → 滑杆组；
 * 滑杆标签默认白@85、数值默认白@40（未调态弱化）、已调 cameraAccent；
 * WB 胶囊 h34/12sp/间距8，选中 #0F766E；滑杆标签与数值 12sp 常显实际值。
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
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.white_balance),
                color = CameraTokens.cameraAccentOn.copy(alpha = 0.7f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(CameraTokens.wbChipSpacing),
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
                    WhiteBalanceChip(
                        label = label,
                        isSelected = whiteBalance == mode,
                        onClick = { onWhiteBalanceChange(mode) }
                    )
                }
            }
        }

        // 分隔线：WB 组与滑杆组分界（六修重设计，对应画布 y=84 divider）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(CameraTokens.cameraAccentOn.copy(alpha = 0.1f))
        )

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
                    style = AppSliderStyle.CameraOverlay,
                    modifier = Modifier.fillMaxWidth().height(36.dp)
                )
            }
        )

        ProModeSlider(
            label = stringResource(R.string.contrast),
            valueText = beautySettings.contrast.toInt().toString(),
            isValueChanged = abs(beautySettings.contrast - 50f) > 0.5f,
            sliderContent = {
                AppSlider(
                    value = beautySettings.contrast,
                    valueRange = 0f..200f,
                    onValueChange = { value ->
                        onBeautySettingsChanged(beautySettings.copy(contrast = value))
                    },
                    style = AppSliderStyle.CameraOverlay,
                    modifier = Modifier.fillMaxWidth().height(36.dp)
                )
            }
        )

        ProModeSlider(
            label = stringResource(R.string.saturation),
            valueText = beautySettings.saturation.toInt().toString(),
            isValueChanged = abs(beautySettings.saturation - 100f) > 0.5f,
            sliderContent = {
                AppSlider(
                    value = beautySettings.saturation,
                    valueRange = 0f..200f,
                    onValueChange = { value ->
                        onBeautySettingsChanged(beautySettings.copy(saturation = value))
                    },
                    style = AppSliderStyle.CameraOverlay,
                    modifier = Modifier.fillMaxWidth().height(36.dp)
                )
            }
        )

        ProModeSlider(
            label = stringResource(R.string.color_temperature),
            valueText = "${beautySettings.temperature.toInt()}K",
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
                    style = AppSliderStyle.CameraOverlay,
                    modifier = Modifier.fillMaxWidth().height(36.dp)
                )
            }
        )
    }
}

/** WB 预设胶囊（Ardot：h40 全圆、15sp Medium、选中 #0F766E / 未选白 15% 底）。 */
@Composable
private fun WhiteBalanceChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .height(CameraTokens.chipHeight)
            .clip(CircleShape)
            .background(
                if (isSelected) {
                    CameraTokens.cameraAccent
                } else {
                    CameraTokens.cameraAccentOn.copy(alpha = 0.15f)
                }
            )
            .clickable { onClick() }
            .padding(horizontal = CameraTokens.wbChipPaddingH),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = CameraTokens.cameraAccentOn,
            fontSize = CameraTokens.wbChipFontSize.value.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

/**
 * Pro 滑杆块：标签（14sp Medium）左置 + 数值（14sp Bold）右置。
 * 数值始终显示实际值；默认态白字、已调态 cameraAccent。
 */
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
                color = if (isValueChanged) {
                    CameraTokens.cameraAccent
                } else {
                    CameraTokens.cameraAccentOn.copy(alpha = 0.85f)
                },
                fontSize = CameraTokens.proSliderFontSize.value.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = valueText,
                color = if (isValueChanged) {
                    CameraTokens.cameraAccent
                } else {
                    CameraTokens.cameraAccentOn.copy(alpha = 0.4f)
                },
                fontSize = CameraTokens.proSliderFontSize.value.sp,
                fontWeight = FontWeight.Bold
            )
        }
        sliderContent()
    }
}
