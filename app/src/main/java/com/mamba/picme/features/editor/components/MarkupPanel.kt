package com.mamba.picme.features.editor.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mamba.picme.R
import com.mamba.picme.features.editor.MarkupAction

enum class MarkupTool { DOODLE, MOSAIC, TEXT }

@Composable
fun MarkupPanel(
    actions: List<MarkupAction>,
    onChange: (List<MarkupAction>) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = true,
                onClick = { /* select doodle */ },
                label = {
                    Icon(
                        Icons.Default.Brush,
                        contentDescription = stringResource(R.string.doodle)
                    )
                }
            )
            FilterChip(
                selected = false,
                onClick = { /* select mosaic */ },
                label = {
                    Icon(
                        Icons.Default.BlurOn,
                        contentDescription = stringResource(R.string.mosaic)
                    )
                }
            )
            FilterChip(
                selected = false,
                onClick = { /* select text */ },
                label = {
                    Icon(
                        Icons.Default.Title,
                        contentDescription = stringResource(R.string.markup_tool_text)
                    )
                }
            )
        }

        Text(
            text = stringResource(R.string.markup_stroke_width),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
        )
        Slider(
            value = 20f,
            onValueChange = { /* stroke width */ },
            valueRange = 5f..100f,
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = stringResource(R.string.markup_phase2_hint),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
