package com.mamba.picme.features.editor.components

import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 编辑器统一选中态 chip。
 *
 * 画布映射规范：选中 chip 填充 `scheme/primaryContainer` + 文字 `onPrimaryContainer`
 * （2026-08-19 签核全局钉青玉后的编辑器选中态 SSOT）。M3 裸 [FilterChip] 选中缺省为
 * secondaryContainer 系，与 Ardot 画布不符——编辑器内所有工具/参数切换 chip 一律
 * 经由本组件取色，未选中态保持 [FilterChipDefaults] 缺省值不变。
 */
@Composable
fun EditorChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier,
        leadingIcon = leadingIcon,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            // 带图标的 chip（涂鸦/马赛克）：图标随文字走 onPrimaryContainer，
            // 缺省 selectedLeadingIconColor 仍是 onSecondaryContainer 系，会与容器色不搭
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    )
}
