package com.mamba.picme.features.editor.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mamba.picme.beauty.api.FilterType
import com.mamba.picme.beauty.api.StyleFilter
import com.mamba.picme.features.camera.components.UnifiedFilterSelector

/**
 * 编辑页滤镜面板。
 *
 * 复用相机页的 [UnifiedFilterSelector]，支持色调滤镜（FilterType）与风格特效（StyleFilter）。
 * 两者互斥：选择色调滤镜时清除风格特效，反之亦然。
 */
@Composable
fun FilterPanel(
    colorFilter: FilterType,
    styleFilter: StyleFilter,
    onChange: (FilterType, StyleFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        UnifiedFilterSelector(
            selectedFilter = colorFilter,
            selectedStyleFilter = styleFilter,
            onFilterSelected = { filter ->
                onChange(filter, StyleFilter.NONE)
            },
            onStyleFilterSelected = { style ->
                onChange(FilterType.NONE, style)
            }
        )
    }
}
