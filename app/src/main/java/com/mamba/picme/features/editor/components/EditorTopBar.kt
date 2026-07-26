package com.mamba.picme.features.editor.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.outlined.LayersClear
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mamba.picme.R

@OptIn(ExperimentalMaterial3Api::class)
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
    onCompare: (pressed: Boolean) -> Unit,
    onRemoveBackground: () -> Unit,
    onAiOptimize: () -> Unit,
    onDone: () -> Unit
) {
    val colors = TopAppBarDefaults.topAppBarColors()
    Surface(
        color = colors.containerColor,
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancel) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = colors.navigationIconContentColor
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = colors.titleContentColor,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRemoveBackground, enabled = !isSaving) {
                Icon(
                    imageVector = Icons.Outlined.LayersClear,
                    contentDescription = stringResource(R.string.remove_background),
                    tint = colors.actionIconContentColor
                )
            }
            IconButton(onClick = onAiOptimize, enabled = !isSaving) {
                Icon(
                    imageVector = Icons.Default.AutoFixHigh,
                    contentDescription = stringResource(R.string.ai_optimize),
                    tint = colors.actionIconContentColor
                )
            }
            IconButton(onClick = onUndo, enabled = canUndo) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Undo,
                    contentDescription = stringResource(R.string.undo),
                    tint = colors.actionIconContentColor,
                    modifier = Modifier.alpha(if (canUndo) 1f else 0.38f)
                )
            }
            IconButton(onClick = onRedo, enabled = canRedo) {
                Icon(
                    imageVector = Icons.Default.Redo,
                    contentDescription = stringResource(R.string.redo),
                    tint = colors.actionIconContentColor,
                    modifier = Modifier.alpha(if (canRedo) 1f else 0.38f)
                )
            }
            IconButton(
                onClick = onDone,
                enabled = !isSaving
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(R.string.done),
                    tint = colors.actionIconContentColor
                )
            }
        }
    }
}
