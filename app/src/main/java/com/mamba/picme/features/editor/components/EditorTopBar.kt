package com.mamba.picme.features.editor.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.LayersClear
import androidx.compose.material.icons.rounded.Redo
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.mamba.picme.R
import com.mamba.picme.features.common.topbar.AppTopBar
import com.mamba.picme.features.common.topbar.AppTopBarAction

@Suppress("LongParameterList") // 待重构：抽 EditorTopBarState
@Composable
fun EditorTopBar(
    title: String,
    canUndo: Boolean,
    canRedo: Boolean,
    isSaving: Boolean,
    onCancel: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onRemoveBackground: () -> Unit,
    onAiOptimize: () -> Unit,
    onDone: () -> Unit
) {
    AppTopBar(
        title = title,
        onBack = onCancel,
        actions = {
            AppTopBarAction(
                icon = Icons.Rounded.LayersClear,
                contentDescription = stringResource(R.string.remove_background),
                onClick = onRemoveBackground,
                enabled = !isSaving
            )
            AppTopBarAction(
                icon = Icons.Rounded.AutoFixHigh,
                contentDescription = stringResource(R.string.ai_optimize),
                onClick = onAiOptimize,
                enabled = !isSaving
            )
            AppTopBarAction(
                icon = Icons.AutoMirrored.Rounded.Undo,
                contentDescription = stringResource(R.string.undo),
                onClick = onUndo,
                enabled = canUndo
            )
            AppTopBarAction(
                icon = Icons.Rounded.Redo,
                contentDescription = stringResource(R.string.redo),
                onClick = onRedo,
                enabled = canRedo
            )
            AppTopBarAction(
                icon = Icons.Rounded.Check,
                contentDescription = stringResource(R.string.done),
                onClick = onDone,
                enabled = !isSaving
            )
        }
    )
}
