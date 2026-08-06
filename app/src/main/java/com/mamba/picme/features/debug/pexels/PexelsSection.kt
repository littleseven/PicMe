package com.mamba.picme.features.debug.pexels

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mamba.picme.R

private val BATCH_SIZES = listOf(10, 20, 50)
private const val DEFAULT_BATCH_SIZE = 20

/** Pexels 图库 Tab 内容（布局 A：搜索栏 + 网格 + 底部下载栏） */
@Composable
fun PexelsSection(
    viewModel: PexelsViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is PexelsEvent.DownloadCompleted -> Toast.makeText(
                    context,
                    context.getString(
                        R.string.pexels_download_done,
                        event.success,
                        event.total
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        when (val s = state) {
            is PexelsUiState.NoKey -> PexelsKeyInput(
                invalidPrevious = s.invalidPrevious,
                onSave = viewModel::saveKey
            )

            else -> {
                PexelsTopBar(
                    onSearch = viewModel::search,
                    onChangeKey = viewModel::clearKey
                )
                Box(modifier = Modifier.weight(1f)) {
                    when (s) {
                        PexelsUiState.Loading -> PexelsLoading()
                        is PexelsUiState.Error -> PexelsError(
                            kind = s.kind,
                            onRetry = viewModel::retry
                        )

                        is PexelsUiState.Ready -> PexelsPhotoGrid(
                            state = s,
                            onToggle = viewModel::toggleSelect,
                            onLoadMore = viewModel::loadMore
                        )

                        is PexelsUiState.NoKey -> Unit
                    }
                }
                PexelsAttribution()
                if (s is PexelsUiState.Ready) {
                    PexelsDownloadBar(
                        state = s,
                        onDownloadSelected = viewModel::downloadSelected,
                        onDownloadBatch = viewModel::downloadBatch
                    )
                }
            }
        }
    }
}

@Composable
private fun PexelsKeyInput(
    invalidPrevious: Boolean,
    onSave: (String) -> Unit
) {
    var key by remember { mutableStateOf("") }
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (invalidPrevious) {
            Text(
                stringResource(R.string.pexels_api_key_invalid),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.pexels_api_key_hint)) },
            singleLine = true
        )
        Button(
            onClick = { onSave(key) },
            enabled = key.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.pexels_api_key_save))
        }
        TextButton(
            onClick = { uriHandler.openUri("https://www.pexels.com/api/") },
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text(stringResource(R.string.pexels_get_key), fontSize = 12.sp)
        }
    }
}

@Composable
private fun PexelsTopBar(
    onSearch: (String) -> Unit,
    onChangeKey: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.pexels_api_key_configured),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onChangeKey) {
                Text(stringResource(R.string.pexels_api_key_change), fontSize = 12.sp)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(stringResource(R.string.pexels_search_hint), fontSize = 12.sp)
                },
                singleLine = true,
                shape = RoundedCornerShape(8.dp)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { onSearch(query) }) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = stringResource(R.string.pexels_search),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun PexelsLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun PexelsError(
    kind: PexelsErrorKind,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(
                when (kind) {
                    PexelsErrorKind.RATE_LIMITED -> R.string.pexels_error_rate_limited
                    else -> R.string.pexels_error_network
                }
            ),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onRetry) {
            Text(stringResource(R.string.pexels_retry))
        }
    }
}

@Composable
private fun PexelsPhotoGrid(
    state: PexelsUiState.Ready,
    onToggle: (Long) -> Unit,
    onLoadMore: () -> Unit
) {
    if (state.photos.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.pexels_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val gridState = rememberLazyGridState()
    val shouldLoadMore by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 6
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) onLoadMore()
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(state.photos, key = { it.id }) { photo ->
            PexelsPhotoCell(
                photo = photo,
                selected = photo.id in state.selectedIds,
                onClick = { onToggle(photo.id) }
            )
        }
        if (state.loadingMore) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
        }
    }
}

@Composable
private fun PexelsPhotoCell(
    photo: PexelsPhoto,
    selected: Boolean,
    onClick: () -> Unit
) {
    val placeholderPainter = ColorPainter(MaterialTheme.colorScheme.surfaceVariant)

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = photo.src.medium,
            contentDescription = stringResource(R.string.pexels_photo_desc, photo.photographer),
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            placeholder = placeholderPainter,
            error = placeholderPainter
        )
        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
            )
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(22.dp)
            )
        }
    }
}

@Composable
private fun PexelsAttribution() {
    Text(
        stringResource(R.string.pexels_attribution),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        textAlign = TextAlign.Center
    )
}

@Composable
private fun PexelsDownloadBar(
    state: PexelsUiState.Ready,
    onDownloadSelected: () -> Unit,
    onDownloadBatch: (Int) -> Unit
) {
    var batchSize by rememberSaveable { mutableIntStateOf(DEFAULT_BATCH_SIZE) }
    var batchMenuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            if (state.downloading) {
                stringResource(R.string.pexels_downloading, state.downloadProgress)
            } else {
                stringResource(R.string.pexels_selected_count, state.selectedIds.size)
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        Button(
            onClick = onDownloadSelected,
            enabled = state.selectedIds.isNotEmpty() && !state.downloading
        ) {
            Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.pexels_download_selected), fontSize = 12.sp)
        }
        Box {
            OutlinedButton(
                onClick = { onDownloadBatch(batchSize) },
                enabled = !state.downloading
            ) {
                Text(
                    stringResource(R.string.pexels_download_batch, batchSize),
                    fontSize = 12.sp
                )
            }
        }
        Box {
            TextButton(onClick = { batchMenuOpen = true }, enabled = !state.downloading) {
                Text(batchSize.toString(), fontSize = 12.sp)
            }
            DropdownMenu(
                expanded = batchMenuOpen,
                onDismissRequest = { batchMenuOpen = false }
            ) {
                BATCH_SIZES.forEach { size ->
                    DropdownMenuItem(
                        text = { Text(size.toString()) },
                        onClick = {
                            batchSize = size
                            batchMenuOpen = false
                        }
                    )
                }
            }
        }
    }
}
