package com.mamba.picme.features.idphoto.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mamba.picme.domain.matting.IDPhotoSpecs

private const val UNSELECTED_BORDER_ALPHA = 0.5f

@Composable
fun SizeChipRow(
    sizes: List<IDPhotoSpecs.Size>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    // 证件照页内容区为强制深色背景，chip 需显式指定可读颜色（浅色主题下默认文字为深色会不可见）
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        sizes.forEachIndexed { index, s ->
            val selected = index == selectedIndex
            AssistChip(
                onClick = { onSelect(index) },
                label = { Text(stringResource(s.nameRes)) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    labelColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else Color.White
                ),
                border = if (selected) null else BorderStroke(1.dp, Color.White.copy(alpha = UNSELECTED_BORDER_ALPHA))
            )
        }
    }
}
