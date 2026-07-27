package com.mamba.picme.features.gallery.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import com.mamba.picme.service.tag.TagGenerationService

@OptIn(ExperimentalMaterial3Api::class)
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
    onToggleScan: () -> Unit = {}
) {
    TopAppBar(
        modifier = Modifier.displayCutoutPadding(),
        title = {
            Text(
                if (isSelectionMode) {
                    stringResource(R.string.selected_items, selectedCount)
                } else {
                    stringResource(R.string.gallery)
                }
            )
        },
        navigationIcon = {
            if (isSelectionMode || onNavigateBack != null) {
                IconButton(onClick = {
                    if (isSelectionMode) {
                        onToggleSelectionMode()
                    } else {
                        onNavigateBack?.invoke()
                    }
                }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            }
        },
        actions = {
            if (isSelectionMode) {
                IconButton(onClick = onSelectAll) {
                    Icon(Icons.Rounded.SelectAll, contentDescription = stringResource(R.string.select_all))
                }
                IconButton(onClick = onShareSelected) {
                    Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.ocr_share))
                }
                IconButton(onClick = onDeleteSelected) {
                    Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.delete))
                }
            } else {
                val isScanning by TagGenerationService.isScanning.collectAsState(false)
                val iconTint = if (isScanning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                // TAG 控制入口：点击进入 TagGenerationControlScreen
                IconButton(onClick = onNavigateToTagControl) {
                    Icon(
                        Icons.Rounded.Sell,
                        contentDescription = stringResource(R.string.tag_scan_control),
                        tint = iconTint
                    )
                }
                // 播放/暂停开关
                IconButton(onClick = onToggleScan) {
                    Icon(
                        imageVector = if (isScanning) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isScanning) stringResource(R.string.pause) else stringResource(R.string.start_scan),
                        tint = iconTint
                    )
                }
                IconButton(onClick = onSearchClick) {
                    Icon(
                        Icons.Rounded.Search,
                        contentDescription = stringResource(R.string.search_photos)
                    )
                }
                GroupingMenu(
                    currentMode = groupingMode,
                    onModeSelected = onGroupingModeSelected
                )
                // 设置入口统一放到顶部栏最右侧
                IconButton(onClick = onNavigateToSettings) {
                    Icon(
                        Icons.Rounded.Settings,
                        contentDescription = stringResource(R.string.settings)
                    )
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicateManagerTopBar(
    onNavigateBack: () -> Unit,
    onDeleteAllDuplicates: () -> Unit
) {
    TopAppBar(
        title = { Text(stringResource(R.string.manage_duplicates)) },
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.close))
            }
        },
        actions = {
            IconButton(onClick = onDeleteAllDuplicates) {
                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.delete_all_duplicates))
            }
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
        IconButton(onClick = { showMenu = true }) {
            Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = stringResource(R.string.group_by))
        }
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            GroupingMode.entries.forEach { mode ->
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
