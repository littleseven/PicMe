package com.mamba.picme.features.idphoto.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.mamba.picme.R
import com.mamba.picme.features.idphoto.IdPhotoTab

/** 底部 4-tab 行：底色/尺寸/边缘/修补。 */
@Composable
fun IdPhotoTabRow(selected: IdPhotoTab, onSelect: (IdPhotoTab) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        IdPhotoTab.entries.forEach { tab ->
            val labelRes = when (tab) {
                IdPhotoTab.BG_COLOR -> R.string.id_photo_tab_color
                IdPhotoTab.SIZE -> R.string.id_photo_tab_size
                IdPhotoTab.EDGE -> R.string.id_photo_tab_edge
                IdPhotoTab.REPAIR -> R.string.id_photo_tab_repair
            }
            FilterChip(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                label = {
                    Text(
                        stringResource(labelRes),
                        color = if (tab == selected) MaterialTheme.colorScheme.onPrimary else Color.White
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Color(0xFF2A2A2A),
                    selectedContainerColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}
