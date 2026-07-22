package com.mamba.picme.features.tagviewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.mamba.picme.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagViewerTestScreen(
    onNavigateBack: () -> Unit,
    viewModel: TagViewerViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var photoQuery by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tag_viewer_title)) },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) { Text(stringResource(R.string.back)) }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.tag_viewer_tab_photos)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.tag_viewer_tab_tags)) }
                )
            }
            when (val current = state) {
                is TagViewerUiState.Loading -> LoadingView()
                is TagViewerUiState.Error -> ErrorView(current.message)
                is TagViewerUiState.Ready -> {
                    if (selectedTab == 0) {
                        PhotosTab(
                            state = current,
                            viewModel = viewModel,
                            query = photoQuery,
                            onQueryChange = { photoQuery = it }
                        )
                    } else {
                        TagsTab(
                            aggregates = current.aggregates,
                            onTagClick = { label ->
                                photoQuery = label
                                viewModel.setQuery(label)
                                selectedTab = 0
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingView() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorView(message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(message, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun PhotosTab(
    state: TagViewerUiState.Ready,
    viewModel: TagViewerViewModel,
    query: String,
    onQueryChange: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { text ->
                onQueryChange(text)
                viewModel.setQuery(text)
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.tag_viewer_search_hint)) },
            singleLine = true
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End
        ) {
            FilterChip(
                selected = state.showOnlyDisliked,
                onClick = { viewModel.setShowOnlyDisliked(!state.showOnlyDisliked) },
                label = { Text(stringResource(R.string.tag_viewer_filter_inaccurate)) }
            )
        }
        if (state.filteredPhotos.isEmpty()) {
            EmptyText(stringResource(R.string.tag_viewer_no_photos))
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.filteredPhotos, key = { item -> item.mediaId }) { item ->
                    PhotoRow(item, onDislike = { viewModel.toggleDislike(item.mediaId) })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun PhotoRow(
    item: PhotoTagsItem,
    onDislike: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val notTagged = stringResource(R.string.tag_viewer_no_labels)
    Column(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = item.uri,
                contentDescription = null,
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.fileName, style = MaterialTheme.typography.bodyLarge)
                val labelText = if (item.hasLabels) item.labelSummary else notTagged
                Text(
                    text = labelText.ifBlank { notTagged },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = onDislike) {
                Icon(
                    imageVector = if (item.isDisliked) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown,
                    contentDescription = stringResource(
                        if (item.isDisliked) R.string.tag_viewer_disliked else R.string.tag_viewer_dislike
                    ),
                    tint = if (item.isDisliked) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.outline
                    }
                )
            }
        }
        if (expanded) {
            ExpandedDetails(item)
        }
    }
}

@Composable
private fun ExpandedDetails(item: PhotoTagsItem) {
    val parsed = item.parsed
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        if (parsed == null) {
            Text(stringResource(R.string.tag_viewer_no_labels))
        } else {
            DetailLine(stringResource(R.string.tag_viewer_section_scenes), parsed.scene)
            DetailLine("activity", parsed.activity)
            DetailLine(
                stringResource(R.string.tag_viewer_section_objects),
                parsed.objects.joinToString(" · ")
            )
            DetailLine(
                stringResource(R.string.tag_viewer_section_tags_field),
                parsed.tags.joinToString(" · ")
            )
            DetailLine("summary", parsed.summary)
            val face = parsed.face
            if (face != null) {
                DetailLine("face", "count=${face.count} selfie=${face.selfie} group=${face.groupPhoto}")
            }
        }
        Spacer(Modifier.size(8.dp))
        Text(stringResource(R.string.tag_viewer_raw_json), style = MaterialTheme.typography.labelSmall)
        Text(
            text = item.rawJson.ifBlank { "{}" },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    if (value.isBlank()) return
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("$label: ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TagsTab(
    aggregates: TagAggregates,
    onTagClick: (String) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {
            SectionHeader(stringResource(R.string.tag_viewer_section_scenes), aggregates.scenes.size)
        }
        items(aggregates.scenes, key = { tag -> "scene-${tag.label}" }) { tagCount ->
            TagCountRow(tagCount, onClick = onTagClick)
        }
        item {
            SectionHeader(stringResource(R.string.tag_viewer_section_objects), aggregates.objects.size)
        }
        items(aggregates.objects, key = { tag -> "object-${tag.label}" }) { tagCount ->
            TagCountRow(tagCount, onClick = onTagClick)
        }
        item {
            SectionHeader(stringResource(R.string.tag_viewer_section_tags_field), aggregates.tags.size)
        }
        items(aggregates.tags, key = { tag -> "tag-${tag.label}" }) { tagCount ->
            TagCountRow(tagCount, onClick = onTagClick)
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Text(
        text = "$title ($count)",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
    )
}

@Composable
private fun TagCountRow(
    tagCount: TagCount,
    onClick: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(tagCount.label) }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(tagCount.label, style = MaterialTheme.typography.bodyMedium)
        Text("${tagCount.count}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun EmptyText(text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text, color = MaterialTheme.colorScheme.outline)
    }
}
