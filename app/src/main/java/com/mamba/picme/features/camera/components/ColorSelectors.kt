package com.mamba.picme.features.camera.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mamba.picme.R
import com.mamba.picme.core.designsystem.components.AppSlider

@Composable
fun BlushColorFamilySelector(
    selectedFamily: Int,
    onFamilyChanged: (Int) -> Unit
) {
    val familyLabels = listOf(
        stringResource(R.string.blush_family_pink),
        stringResource(R.string.blush_family_orange),
        stringResource(R.string.blush_family_plum)
    )
    val familyColors = listOf(
        Color(0xFFFF8DAA),
        Color(0xFFFFA85C),
        Color(0xFF9B3D6A)
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.blush_color_family),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            familyLabels.forEachIndexed { index, label ->
                val isSelected = selectedFamily == index
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isSelected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                            }
                        )
                        .border(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                            },
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable { onFamilyChanged(index) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(familyColors[index])
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = label,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                        },
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
fun LipColorSelector(
    strength: Float,
    colorIndex: Int,
    onStrengthChanged: (Float) -> Unit,
    onColorIndexChanged: (Int) -> Unit,
    onReset: () -> Unit
) {
    val lipColors = listOf(
        Color(0xFFD4757D),
        Color(0xFFC43343),
        Color(0xFFFF7F50),
        Color(0xFFE0527C),
        Color(0xFFFF6B9D),
        Color(0xFF9B2335),
        Color(0xFFFFA07A),
        Color(0xFFCD5C5C),
        Color(0xFFDC143C),
        Color(0xFFFFB6C1),
        Color(0xFFB22222),
        Color(0xFFFF1493)
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(onClick = onReset)
            ) {
                Icon(
                    imageVector = Icons.Rounded.ColorLens,
                    contentDescription = null,
                    tint = if (strength > 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    },
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.lip_color),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Text(
                text = if (strength > 0) "${strength.toInt()}" else "--",
                color = if (strength > 0) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }

        LazyHorizontalGrid(
            rows = GridCells.Fixed(2),
            contentPadding = PaddingValues(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.height(64.dp)
        ) {
            items(lipColors.size) { index ->
                Box(
                    modifier = Modifier
                        .size(48.dp, 28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(lipColors[index])
                        .border(
                            width = if (index == colorIndex) 3.dp else 1.dp,
                            color = if (index == colorIndex) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.White.copy(alpha = 0.3f)
                            },
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable { onColorIndexChanged(index) }
                )
            }
        }

        AppSlider(
            value = strength.coerceIn(0f, 100f),
            onValueChange = { value ->
                onStrengthChanged(value.coerceIn(0f, 100f))
            },
            valueRange = 0f..100f,
            modifier = Modifier.height(32.dp)
        )
    }
}
