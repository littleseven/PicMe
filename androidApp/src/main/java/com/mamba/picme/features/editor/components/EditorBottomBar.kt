package com.mamba.picme.features.editor.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mamba.picme.R
import com.mamba.picme.features.editor.PhotoEditorViewModel

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
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        tabs.forEach { tab ->
            val label = stringResource(tabLabelRes(tab))
            EditorChip(
                selected = selectedTab == tab,
                onClick = { onTabSelected(tab) },
                label = { Text(label) },
                modifier = Modifier.semantics {
                    contentDescription = "Editor tab $label"
                }
            )
        }
    }
}

private fun tabLabelRes(tab: PhotoEditorViewModel.EditorTab): Int = when (tab) {
    PhotoEditorViewModel.EditorTab.CROP -> R.string.editor_tab_crop
    PhotoEditorViewModel.EditorTab.ADJUST -> R.string.editor_tab_adjust
    PhotoEditorViewModel.EditorTab.BEAUTY -> R.string.editor_tab_beauty
    PhotoEditorViewModel.EditorTab.FILTER -> R.string.editor_tab_filter
    PhotoEditorViewModel.EditorTab.MARKUP -> R.string.editor_tab_markup
}
