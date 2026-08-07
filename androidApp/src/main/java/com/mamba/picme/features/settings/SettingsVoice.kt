package com.mamba.picme.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mamba.picme.R
import com.mamba.picme.data.download.LlmModelDownloadManager
import com.mamba.picme.data.download.ModelConfig
import com.mamba.picme.domain.model.VoiceCommandMode

@Composable
internal fun VoiceCommandModeSelection(
    currentMode: VoiceCommandMode,
    onModeSelected: (VoiceCommandMode) -> Unit
) {
    val options = listOf(
        VoiceCommandMode.DISABLED to stringResource(R.string.voice_command_mode_disabled),
        VoiceCommandMode.PUSH_TO_TALK to stringResource(R.string.voice_command_mode_push_to_talk),
        VoiceCommandMode.WAKE_WORD to stringResource(R.string.voice_command_mode_wake_word)
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(
            text = stringResource(R.string.voice_command_mode),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        CompactOptionChips(
            options = options,
            currentValue = currentMode,
            maxLines = 1,
            onSelected = onModeSelected
        )
    }
}

@Composable
internal fun LocalAsrModelSelection(
    currentModel: String,
    onModelSelected: (String) -> Unit,
    onNavigateToModelCenter: (String) -> Unit
) {
    val context = LocalContext.current
    val downloadManager = remember { LlmModelDownloadManager(context) }
    var downloadedModels by remember { mutableStateOf<List<ModelConfig>>(emptyList()) }

    LaunchedEffect(Unit) {
        downloadedModels = downloadManager.getDownloadedModels()
            .filter { model ->
                model.tags.any { tag -> tag.equals("ASR", ignoreCase = true) } ||
                    model.id.contains("asr", ignoreCase = true)
            }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.local_asr_model),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier
                    .clickable(onClick = { onNavigateToModelCenter("Audio") })
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.model_center),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    imageVector = Icons.Outlined.CloudDownload,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (downloadedModels.isEmpty()) {
            Text(
                text = stringResource(R.string.local_asr_model_fallback),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            val options = downloadedModels.map { it.id to it.name }
            CompactOptionChips(
                options = options,
                currentValue = currentModel,
                maxLines = 2,
                onSelected = onModelSelected
            )
        }
    }
}

@Composable
internal fun LocalKwsModelSelection(
    currentModel: String,
    onModelSelected: (String) -> Unit,
    onNavigateToModelCenter: (String) -> Unit
) {
    val context = LocalContext.current
    val downloadManager = remember { LlmModelDownloadManager(context) }
    var downloadedModels by remember { mutableStateOf<List<ModelConfig>>(emptyList()) }

    LaunchedEffect(Unit) {
        downloadedModels = downloadManager.getDownloadedModels()
            .filter { model ->
                model.tags.any { tag -> tag.equals("KWS", ignoreCase = true) } ||
                    model.id.contains("kws", ignoreCase = true)
            }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.local_kws_model),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier
                    .clickable(onClick = { onNavigateToModelCenter("Audio") })
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.model_center),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    imageVector = Icons.Outlined.CloudDownload,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (downloadedModels.isEmpty()) {
            Text(
                text = stringResource(R.string.local_kws_model_fallback),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            val options = downloadedModels.map { it.id to it.name }
            CompactOptionChips(
                options = options,
                currentValue = currentModel,
                maxLines = 2,
                onSelected = onModelSelected
            )
        }
    }
}
