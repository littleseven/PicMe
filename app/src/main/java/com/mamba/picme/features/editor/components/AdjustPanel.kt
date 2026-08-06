package com.mamba.picme.features.editor.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mamba.picme.R
import com.mamba.picme.core.designsystem.components.AppSlider
import com.mamba.picme.features.editor.AdjustmentRecipe

@Composable
fun AdjustPanel(
    adjustments: AdjustmentRecipe,
    onChange: (AdjustmentRecipe) -> Unit
) {
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }

    val params = listOf(
        AdjustParam(
            label = stringResource(R.string.adjust_brightness),
            value = adjustments.brightness,
            valueRange = -100f..100f,
            resetValue = 0f,
            onValueChange = { onChange(adjustments.copy(brightness = it)) }
        ),
        AdjustParam(
            label = stringResource(R.string.adjust_exposure),
            value = adjustments.exposure,
            valueRange = -100f..100f,
            resetValue = 0f,
            onValueChange = { onChange(adjustments.copy(exposure = it)) }
        ),
        AdjustParam(
            label = stringResource(R.string.contrast),
            value = adjustments.contrast,
            valueRange = 0f..200f,
            resetValue = 50f,
            onValueChange = { onChange(adjustments.copy(contrast = it)) }
        ),
        AdjustParam(
            label = stringResource(R.string.saturation),
            value = adjustments.saturation,
            valueRange = 0f..200f,
            resetValue = 100f,
            onValueChange = { onChange(adjustments.copy(saturation = it)) }
        ),
        AdjustParam(
            label = stringResource(R.string.adjust_temperature),
            value = adjustments.temperature,
            valueRange = 2000f..8000f,
            resetValue = 5000f,
            onValueChange = { onChange(adjustments.copy(temperature = it)) }
        ),
        AdjustParam(
            label = stringResource(R.string.adjust_tint),
            value = adjustments.tint,
            valueRange = -100f..100f,
            resetValue = 0f,
            onValueChange = { onChange(adjustments.copy(tint = it)) }
        )
    )

    val current = params[selectedIndex]

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = current.label,
                style = MaterialTheme.typography.titleMedium
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "%.0f".format(current.value),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                TextButton(onClick = { current.onValueChange(current.resetValue) }) {
                    Text(stringResource(R.string.reset))
                }
            }
        }

        AppSlider(
            value = current.value,
            onValueChange = current.onValueChange,
            valueRange = current.valueRange,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            params.forEachIndexed { index, param ->
                FilterChip(
                    selected = selectedIndex == index,
                    onClick = { selectedIndex = index },
                    label = { Text(param.label) }
                )
            }
        }
    }
}

private data class AdjustParam(
    val label: String,
    val value: Float,
    val valueRange: ClosedFloatingPointRange<Float>,
    val resetValue: Float,
    val onValueChange: (Float) -> Unit
)
