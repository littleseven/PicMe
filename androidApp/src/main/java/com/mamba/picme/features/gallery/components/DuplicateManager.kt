package com.mamba.picme.features.gallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mamba.picme.R
import com.mamba.picme.domain.model.DuplicateGroup
import com.mamba.picme.features.gallery.MediaViewModel
import java.io.File
import androidx.compose.foundation.layout.Spacer

@Composable
fun DuplicateManagerScreen(
    duplicateGroups: List<DuplicateGroup>,
    isScanning: Boolean,
    onDeleteGroup: (DuplicateGroup) -> Unit
) {
    if (isScanning) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    } else if (duplicateGroups.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.no_duplicates_found),
                style = MaterialTheme.typography.bodyLarge
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.duplicate_groups_found, duplicateGroups.size),
                    style = MaterialTheme.typography.titleMedium
                )
            }

            items(
                count = duplicateGroups.size,
                key = { index -> index }
            ) { index ->
                val group = duplicateGroups[index]
                DuplicateGroupCard(
                    group = group,
                    onDeleteGroup = onDeleteGroup
                )
            }
        }
    }
}

@Composable
private fun DuplicateGroupCard(
    group: DuplicateGroup,
    onDeleteGroup: (DuplicateGroup) -> Unit
) {
    var showPreview by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (group.isExactDuplicate) {
                        stringResource(R.string.exact_duplicate)
                    } else {
                        stringResource(R.string.similar_image)
                    },
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = stringResource(R.string.count_files, group.fileUris.size),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                group.fileUris.take(3).forEach { uri ->
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(uri)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f),
                        contentScale = ContentScale.Crop
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = { showPreview = true }
                ) {
                    Text(stringResource(R.string.preview_all))
                }
                Button(
                    onClick = { onDeleteGroup(group) },
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text(stringResource(R.string.keep_first_delete_others))
                }
            }
        }
    }

    if (showPreview) {
        DuplicatePreviewDialog(
            fileUris = group.fileUris,
            onDismiss = { showPreview = false },
            onDelete = {
                showPreview = false
                onDeleteGroup(group)
            }
        )
    }
}

@Composable
private fun DuplicatePreviewDialog(
    fileUris: List<String>,
    onDismiss: () -> Unit,
    onDelete: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.duplicate_preview)) },
        text = {
            Column {
                Text(stringResource(R.string.will_keep_first_file))
                Spacer(modifier = Modifier.padding(4.dp))
                fileUris.forEachIndexed { index, uri ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (index == 0) {
                            Icon(
                                Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Text(
                            text = File(uri).name,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDelete) {
                Text(stringResource(R.string.confirm_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicateManagerRoute(
    viewModel: MediaViewModel,
    onNavigateBack: () -> Unit
) {
    val duplicateGroups by viewModel.duplicateGroups.collectAsState()
    val isScanningDuplicates by viewModel.isScanningDuplicates.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.startDuplicateScan()
    }

    Scaffold(
        topBar = {
            DuplicateManagerTopBar(
                onNavigateBack = onNavigateBack,
                onDeleteAllDuplicates = { viewModel.deleteAllDuplicatesExceptOne() }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            DuplicateManagerScreen(
                duplicateGroups = duplicateGroups,
                isScanning = isScanningDuplicates,
                onDeleteGroup = { group -> viewModel.deleteDuplicateGroup(group, 0) }
            )
        }
    }
}
