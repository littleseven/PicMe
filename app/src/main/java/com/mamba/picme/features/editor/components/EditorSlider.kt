package com.mamba.picme.features.editor.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mamba.picme.R

@Composable
fun EditorSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onReset: () -> Unit,
    valueFormatter: (Float) -> String = { "%.0f".format(it) },
    compact: Boolean = false
) {
    val verticalPadding = if (compact) 2.dp else 4.dp
    val textStyle = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = verticalPadding)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = textStyle,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = valueFormatter(value),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = if (compact) 4.dp else 8.dp)
            )
            TextButton(
                onClick = onReset,
                modifier = Modifier.padding(horizontal = if (compact) 0.dp else 4.dp)
            ) {
                Text(
                    stringResource(R.string.reset),
                    style = textStyle
                )
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
