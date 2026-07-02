package com.mamba.picme.features.editor.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                onChange(crop.copy(rotation = (crop.rotation - 90).mod(360)))
            }) {
                Icon(
                    Icons.AutoMirrored.Filled.RotateLeft,
                    contentDescription = stringResource(R.string.rotate_left)
                )
            }
            IconButton(onClick = {
                onChange(crop.copy(rotation = (crop.rotation + 90).mod(360)))
            }) {
                Icon(
                    Icons.AutoMirrored.Filled.RotateRight,
                    contentDescription = stringResource(R.string.rotate_right)
                )
            }
            IconButton(onClick = { onChange(crop.copy(flippedH = !crop.flippedH)) }) {
                Icon(
                    Icons.Default.Flip,
                    contentDescription = stringResource(R.string.flip_horizontal)
                )
            }
        }
    }
}
