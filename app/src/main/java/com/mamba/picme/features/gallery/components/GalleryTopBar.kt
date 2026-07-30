package com.mamba.picme.features.gallery.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.mamba.picme.R
import com.mamba.picme.domain.model.GroupingMode
import com.mamba.picme.domain.model.GroupingMode.DATE
import com.mamba.picme.domain.model.GroupingMode.FACE
import com.mamba.picme.domain.model.GroupingMode.LANDSCAPE
import com.mamba.picme.domain.model.GroupingMode.LOCATION
import com.mamba.picme.domain.model.GroupingMode.NONE
import com.mamba.picme.domain.model.GroupingMode.PERSON
import com.mamba.picme.domain.model.GroupingMode.SEXY
import com.mamba.picme.domain.model.GroupingMode.SWIMWEAR
import com.mamba.picme.features.common.topbar.AppTopBar
import com.mamba.picme.features.common.topbar.AppTopBarAction
import com.mamba.picme.features.common.topbar.AppTopBarNavBack
import com.mamba.picme.service.tag.TagGenerationService

@Composable
fun GalleryTopBar(
    isSelectionMode: Boolean,
    selectedCount: Int,
    groupingMode: GroupingMode,
    onNavigateBack: (() -> Unit)? = null,
    onNavigateToSettings: () -> Unit = {},
    onToggleSelectionMode: () -> Unit,
    onSelectAll: () -> Unit,
    onDeleteSelected: () -> Unit,
    onShareSelected: () -> Unit,
    onGroupingModeSelected: (GroupingMode) -> Unit,
    onSearchClick: () -> Unit = {},
    onTagScanClick: () -> Unit = {},
    onNavigateToTagControl: () -> Unit = {},
    onToggleScan: () -> Unit = {},
    onNavigateToPeople: () -> Unit = {}
) {
    val isScanning by TagGenerationService.isScanning.collectAsState(false)
    AppTopBar(
        title = {
            Text(
                if (isSelectionMode) {
                    stringResource(R.string.selected_items, selectedCount)
                } else {
                    stringResource(R.string.gallery)
                }
            )
        },
        modifier = Modifier.displayCutoutPadding(),
        navigationIcon = {
            if (isSelectionMode || onNavigateBack != null) {
                AppTopBarNavBack(onClick = {
                    if (isSelectionMode) {
                        onToggleSelectionMode()
                    } else {
                        onNavigateBack?.invoke()
                    }
                })
            }
        },
        actions = {
            if (isSelectionMode) {
                AppTopBarAction(Icons.Rounded.SelectAll, stringResource(R.string.select_all), onSelectAll)
                AppTopBarAction(Icons.Rounded.Share, stringResource(R.string.ocr_share), onShareSelected)
                AppTopBarAction(Icons.Rounded.Delete, stringResource(R.string.delete), onDeleteSelected)
            } else {
                val scanTint = if (isScanning) MaterialTheme.colorScheme.primary else null
                AppTopBarAction(
                    icon = Icons.Rounded.Sell,
                    contentDescription = stringResource(R.string.tag_scan_control),
                    onClick = onNavigateToTagControl,
                    tint = scanTint
                )
                AppTopBarAction(
                    icon = if (isScanning) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isScanning) {
                        stringResource(R.string.pause)
                    } else {
                        stringResource(R.string.start_scan)
                    },
                    onClick = onToggleScan,
                    tint = scanTint
                )
                AppTopBarAction(Icons.Rounded.Search, stringResource(R.string.search_photos), onSearchClick)
                // 人物页入口（main 新增，统一为 AppTopBarAction）
                AppTopBarAction(Icons.Rounded.AccountCircle, stringResource(R.string.gallery_people_entry), onNavigateToPeople)
                GroupingMenu(currentMode = groupingMode, onModeSelected = onGroupingModeSelected)
                AppTopBarAction(Icons.Rounded.Settings, stringResource(R.string.settings), onNavigateToSettings)
            }
        }
    )
}

@Composable
fun DuplicateManagerTopBar(
    onNavigateBack: () -> Unit,
    onDeleteAllDuplicates: () -> Unit
) {
    AppTopBar(
        title = { Text(stringResource(R.string.manage_duplicates)) },
        navigationIcon = {
            AppTopBarAction(
                icon = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.close),
                onClick = onNavigateBack
            )
        },
        actions = {
            AppTopBarAction(Icons.Rounded.Delete, stringResource(R.string.delete_all_duplicates), onDeleteAllDuplicates)
        }
    )
}

@Composable
private fun GroupingMenu(
    currentMode: GroupingMode,
    onModeSelected: (GroupingMode) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        AppTopBarAction(
            icon = Icons.AutoMirrored.Rounded.Sort,
            contentDescription = stringResource(R.string.group_by),
            onClick = { showMenu = true }
        )
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            GroupingMode.entries
                .filter { mode -> mode != SWIMWEAR && mode != SEXY }
                .forEach { mode ->
                val label = when (mode) {
                    NONE -> stringResource(R.string.group_none)
                    DATE -> stringResource(R.string.group_date)
                    FACE -> stringResource(R.string.group_face)
                    PERSON -> stringResource(R.string.group_person)
                    LANDSCAPE -> stringResource(R.string.landscape)
                    SWIMWEAR -> stringResource(R.string.swimwear)
                    SEXY -> stringResource(R.string.sexy)
                    LOCATION -> stringResource(R.string.gallery_group_location)
                }
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onModeSelected(mode)
                        showMenu = false
                    },
                    leadingIcon = {
                        if (currentMode == mode) {
                            Icon(Icons.Rounded.Check, null)
                        }
                    }
                )
            }
        }
    }
}
