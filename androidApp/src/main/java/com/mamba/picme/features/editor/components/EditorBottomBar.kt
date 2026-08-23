package com.mamba.picme.features.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mamba.picme.R
import com.mamba.picme.features.editor.PhotoEditorViewModel

/**
 * 底部 tab 条（editor.yaml §5 tab_bar）。
 *
 * chip 宽度 hug 文本 + 紧凑内距（2026-08-23 EN 折行修复定稿，Ardot editor/tabbar-en-preview 方案A）：
 * 等宽平分槽位（SpaceEvenly ≈ 68dp）在英文下容不下 labelLarge 14sp + M3 32dp 内距，
 * "Adjust/Markup" 折行且第二行被 chip 圆角裁掉——改为自适应宽度、水平内距 8dp、间距 6dp 整行居中；
 * 选中态仅以 primaryContainer 底色区分（M3 FilterChip 自动 ✓ 图标随本次一并去除）。
 */
@Composable
fun EditorBottomBar(
    selectedTab: PhotoEditorViewModel.EditorTab,
    onTabSelected: (PhotoEditorViewModel.EditorTab) -> Unit
) {
    val tabs = PhotoEditorViewModel.EditorTab.entries
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEach { tab ->
            val label = stringResource(tabLabelRes(tab))
            EditorTabChip(
                label = label,
                selected = selectedTab == tab,
                onClick = { onTabSelected(tab) }
            )
        }
    }
}

/** tab 条自适应宽度 chip：h=36 / r=8 / 水平内距 8，与 EditorChip 同取色规则。 */
@Composable
private fun EditorTabChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(containerColor)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "Editor tab $label"
                this[SemanticsProperties.Selected] = selected
            }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor
        )
    }
}

private fun tabLabelRes(tab: PhotoEditorViewModel.EditorTab): Int = when (tab) {
    PhotoEditorViewModel.EditorTab.CROP -> R.string.editor_tab_crop
    PhotoEditorViewModel.EditorTab.ADJUST -> R.string.editor_tab_adjust
    PhotoEditorViewModel.EditorTab.BEAUTY -> R.string.editor_tab_beauty
    PhotoEditorViewModel.EditorTab.FILTER -> R.string.editor_tab_filter
    PhotoEditorViewModel.EditorTab.MARKUP -> R.string.editor_tab_markup
}
