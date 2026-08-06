package com.mamba.picme.features.idphoto.components

import androidx.annotation.StringRes
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
    @StringRes val labels = mapOf(
        IdPhotoTab.BG_COLOR to R.string.id_photo_tab_color,
        IdPhotoTab.SIZE to R.string.id_photo_tab_size,
        IdPhotoTab.EDGE to R.string.id_photo_tab_edge,
        IdPhotoTab.REPAIR to R.string.id_photo_tab_repair
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        IdPhotoTab.entries.forEach { tab ->
            FilterChip(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                label = {
                    Text(
                        stringResource(labels.getValue(tab)),
                        color = if (tab == selected) Color.Black else Color.White
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
