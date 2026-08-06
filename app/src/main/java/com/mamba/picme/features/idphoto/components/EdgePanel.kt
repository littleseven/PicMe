package com.mamba.picme.features.idphoto.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.mamba.picme.R
import com.mamba.picme.domain.matting.EdgeParams
import kotlin.math.roundToInt

/**
 * 边缘参数面板：羽化 / 收缩扩张 / 边缘对比度。
 * 拖动中只更新本地滑块态，松手（onValueChangeFinished）才回调，避免每帧重建底图。
 * 证件照页内容区为强制深色背景，滑块与文字使用显式深色配色（白字 + 深色轨道），
 * 避免浅色主题下默认颜色不可读（对齐 SizeChipRow 的配色约定）。
 */
@Composable
fun EdgePanel(
    params: EdgeParams,
    onParamsChange: (EdgeParams) -> Unit,
    onReset: () -> Unit
) {
    var feather by remember(params) { mutableFloatStateOf(params.featherRadiusPx.toFloat()) }
    var shrinkExpand by remember(params) { mutableFloatStateOf(params.shrinkExpandPx.toFloat()) }
    var contrast by remember(params) { mutableFloatStateOf(params.contrast) }

    // 任一滑块松手都提交三个本地态的完整 EdgeParams，避免 params 回流前拖第二个滑块丢更新
    val commitParams = {
        onParamsChange(
            EdgeParams(
                contrast = contrast,
                shrinkExpandPx = shrinkExpand.roundToInt(),
                featherRadiusPx = feather.roundToInt()
            )
        )
    }

    val sliderColors = SliderDefaults.colors(
        thumbColor = MaterialTheme.colorScheme.primary,
        activeTrackColor = MaterialTheme.colorScheme.primary,
        inactiveTrackColor = Color(0xFF3A3A3A)
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        EdgeSlider(
            label = stringResource(R.string.id_photo_edge_feather),
            value = feather,
            valueRange = 0f..EdgeParams.MAX_FEATHER_PX.toFloat(),
            display = "${feather.roundToInt()}px",
            onValueChange = { feather = it },
            onFinished = commitParams,
            colors = sliderColors
        )
        EdgeSlider(
            label = stringResource(R.string.id_photo_edge_shrink_expand),
            value = shrinkExpand,
            valueRange = -EdgeParams.MAX_SHRINK_EXPAND_PX.toFloat()..EdgeParams.MAX_SHRINK_EXPAND_PX.toFloat(),
            display = "${shrinkExpand.roundToInt()}px",
            onValueChange = { shrinkExpand = it },
            onFinished = commitParams,
            colors = sliderColors
        )
        EdgeSlider(
            label = stringResource(R.string.id_photo_edge_contrast),
            value = contrast,
            valueRange = EdgeParams.MIN_CONTRAST..EdgeParams.MAX_CONTRAST,
            display = "%.1f".format(contrast),
            onValueChange = { contrast = it },
            onFinished = commitParams,
            colors = sliderColors
        )
        TextButton(onClick = onReset, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.id_photo_edge_reset), color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun EdgeSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    display: String,
    onValueChange: (Float) -> Unit,
    onFinished: () -> Unit,
    colors: SliderColors
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label, color = Color.White, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        Text(
            display, color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodySmall
        )
    }
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onFinished,
        valueRange = valueRange,
        colors = colors,
        modifier = Modifier.fillMaxWidth()
    )
}
