package com.mamba.picme.features.idphoto.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mamba.picme.domain.matting.IDPhotoSpecs

@Composable
fun ColorSwatchRow(
    colors: List<IDPhotoSpecs.Color>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        colors.forEachIndexed { index, c ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(Color(c.argb), CircleShape)
                    .border(
                        width = if (selected) 3.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray,
                        shape = CircleShape
                    )
                    .selectable(selected = selected, onClick = { onSelect(index) })
            )
        }
    }
}
