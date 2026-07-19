package com.mamba.picme.features.idphoto.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mamba.picme.domain.matting.IDPhotoSpecs

@Composable
fun SizeChipRow(
    sizes: List<IDPhotoSpecs.Size>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        sizes.forEachIndexed { index, s ->
            AssistChip(
                onClick = { onSelect(index) },
                label = { Text(stringResource(s.nameRes)) },
                colors = if (index == selectedIndex) {
                    AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                } else {
                    AssistChipDefaults.assistChipColors()
                }
            )
        }
    }
}
