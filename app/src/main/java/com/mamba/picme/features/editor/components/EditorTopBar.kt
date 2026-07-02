package com.mamba.picme.features.editor.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import com.mamba.picme.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorTopBar(
    title: String,
    canUndo: Boolean,
    canRedo: Boolean,
    isSaving: Boolean,
    onCancel: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onCompare: (pressed: Boolean) -> Unit,
    onDone: () -> Unit
) {
    CenterAlignedTopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onCancel) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back)
                )
            }
        },
        actions = {
            IconButton(onClick = onUndo, enabled = canUndo) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Undo,
                    contentDescription = stringResource(R.string.undo),
                    modifier = Modifier.alpha(if (canUndo) 1f else 0.38f)
                )
            }
            IconButton(onClick = onRedo, enabled = canRedo) {
                Icon(
                    imageVector = Icons.Default.Redo,
                    contentDescription = stringResource(R.string.redo),
                    modifier = Modifier.alpha(if (canRedo) 1f else 0.38f)
                )
            }
            IconButton(
                onClick = onDone,
                enabled = !isSaving
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = stringResource(R.string.done)
                )
            }
        }
    )
}
