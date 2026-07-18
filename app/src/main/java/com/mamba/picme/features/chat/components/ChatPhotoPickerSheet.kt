package com.mamba.picme.features.chat.components

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.mamba.picme.PoLangApplication
import com.mamba.picme.R
import com.mamba.picme.domain.model.GroupedMedia
import com.mamba.picme.domain.model.GroupTitleType
import com.mamba.picme.features.chat.ChatPhotoPickerViewModel
import com.mamba.picme.features.chat.PickerMode
import com.mamba.picme.features.common.SearchField
import com.mamba.picme.features.gallery.MediaViewModel
import com.mamba.picme.features.gallery.capability.GalleryCapability
import com.mamba.picme.features.gallery.components.MediaGrid

/**
 * Chat 选图半屏面板：
 * - 顶部 [SearchField] 接入既有 [GalleryCapability] 搜索引擎；
 * - 浏览态复用 [MediaGrid]（日期分组最近照片）；搜索态把结果包成单条 [GroupedMedia]；
 * - 单选：点击缩略图即回调 [onImageSelected] 并关闭面板（与旧 InAppPhotoPicker 行为一致）。
 *
 * `searchEngine` 为 null（语义索引未就绪）时隐藏搜索框、显示回退提示并只展示最近照片。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatPhotoPickerSheet(
    sheetState: SheetState,
    mediaViewModel: MediaViewModel,
    onImageSelected: (Uri) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as PoLangApplication
    val thumbnailCache = remember { app.container.thumbnailCache }
    val thumbnailPositions = remember { mutableStateMapOf<Long, Rect>() }
    val scope = rememberCoroutineScope()

    val searchEngine = remember { GalleryCapability.getInstance().searchEngine }
    val searchAvailable = searchEngine != null

    val vm = remember(searchAvailable) {
        ChatPhotoPickerViewModel(
            search = { q -> searchEngine?.search(q)?.media ?: emptyList() },
            searchAvailable = searchAvailable,
            coroutineScope = scope
        )
    }

    val query by vm.query.collectAsState()
    val results by vm.results.collectAsState()
    val isSearching by vm.isSearching.collectAsState()
    val groupedMedia by mediaViewModel.groupedMedia.collectAsState()

    val mode = if (query.isBlank() || !searchAvailable) PickerMode.BROWSE else PickerMode.SEARCH

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(560.dp)
                .padding(horizontal = 4.dp)
        ) {
            if (!searchAvailable) {
                Text(
                    text = stringResource(R.string.chat_photo_picker_search_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            } else {
                SearchField(
                    query = query,
                    onQueryChange = vm::setQuery,
                    placeholder = stringResource(R.string.chat_photo_picker_search_hint),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            Box(modifier = Modifier.fillMaxWidth().height(480.dp)) {
                when {
                    mode == PickerMode.SEARCH && isSearching -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    mode == PickerMode.SEARCH && results.isEmpty() -> {
                        Text(
                            text = stringResource(R.string.chat_photo_picker_no_results),
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    mode == PickerMode.SEARCH -> {
                        val searchGroup = remember(results) {
                            GroupedMedia(GroupTitleType.SEARCH, "", results)
                        }
                        MediaGrid(
                            context = context,
                            groupedMedia = listOf(searchGroup),
                            selectedIds = emptyList(),
                            isSelectionMode = false,
                            thumbnailPositions = thumbnailPositions,
                            mediaById = results.associateBy { it.id },
                            thumbnailCache = thumbnailCache,
                            onThumbnailPositioned = { id, rect -> thumbnailPositions[id] = rect },
                            onMediaClick = { asset ->
                                onImageSelected(asset.uri.toUri()); onDismiss()
                            },
                            onMediaLongClick = {},
                            onDragSelectionStart = {},
                            onDragSelectionItem = {},
                            onDragSelectionEnd = {}
                        )
                    }
                    else -> {
                        val mediaById = remember(groupedMedia) {
                            groupedMedia.flatMap { g -> g.items }.associateBy { it.id }
                        }
                        MediaGrid(
                            context = context,
                            groupedMedia = groupedMedia,
                            selectedIds = emptyList(),
                            isSelectionMode = false,
                            thumbnailPositions = thumbnailPositions,
                            mediaById = mediaById,
                            thumbnailCache = thumbnailCache,
                            onThumbnailPositioned = { id, rect -> thumbnailPositions[id] = rect },
                            onMediaClick = { asset ->
                                onImageSelected(asset.uri.toUri()); onDismiss()
                            },
                            onMediaLongClick = {},
                            onDragSelectionStart = {},
                            onDragSelectionItem = {},
                            onDragSelectionEnd = {}
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}
