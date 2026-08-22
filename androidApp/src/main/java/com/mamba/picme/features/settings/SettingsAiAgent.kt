package com.mamba.picme.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.mamba.picme.agent.core.model.config.AiAgentMode
import com.mamba.picme.agent.core.model.config.AssistantPersona
import com.mamba.picme.agent.core.remote.config.RemoteModelConfig
import com.mamba.picme.agent.core.remote.config.RemoteModelConfigs
import com.mamba.picme.agent.core.remote.config.RemoteProtocol
import java.util.Locale

@Composable
internal fun AiAgentModeSelection(
    currentMode: AiAgentMode,
    onModeSelected: (AiAgentMode) -> Unit
) {
    val options = listOf(
        AiAgentMode.REMOTE to stringResource(R.string.ai_agent_mode_remote)
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(
            text = stringResource(R.string.ai_agent_mode),
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
internal fun AssistantPersonaSelection(
    currentPersona: AssistantPersona,
    onPersonaSelected: (AssistantPersona) -> Unit
) {
    val options = listOf(
        AssistantPersona.DEFAULT to stringResource(R.string.assistant_persona_default),
        AssistantPersona.WARM to stringResource(R.string.assistant_persona_warm),
        AssistantPersona.LIVELY to stringResource(R.string.assistant_persona_lively),
        AssistantPersona.CONCISE to stringResource(R.string.assistant_persona_concise)
    )
    val descRes = when (currentPersona) {
        AssistantPersona.DEFAULT -> R.string.assistant_persona_default_desc
        AssistantPersona.WARM -> R.string.assistant_persona_warm_desc
        AssistantPersona.LIVELY -> R.string.assistant_persona_lively_desc
        AssistantPersona.CONCISE -> R.string.assistant_persona_concise_desc
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(
            text = stringResource(R.string.assistant_persona),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(descRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(start = 12.dp, top = 2.dp)
        )
        CompactOptionChips(
            options = options,
            currentValue = currentPersona,
            maxLines = 2,
            onSelected = onPersonaSelected
        )
    }
}

@Composable
internal fun AiAgentRemoteModelsSection(
    configsJson: String,
    onConfigsChange: (String) -> Unit,
    selectedModelId: String,
    onSelectedModelChange: (String) -> Unit
) {
    val configs = remember(configsJson) {
        if (configsJson.isNotBlank()) {
            RemoteModelConfigs.fromJson(configsJson)
        } else {
            RemoteModelConfigs()
        }
    }
    var showAddDialog by remember { mutableStateOf(false) }
    val configuredConfigs = configs.configs.filter { it.isConfigured }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        val selectedConfig = configs.getConfig(selectedModelId)
        if (selectedConfig != null && selectedConfig.isConfigured) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = stringResource(R.string.ai_agent_current_model),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                        Text(
                            text = selectedConfig.modelId,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        val selectedProvider = RemoteModelConfig.getProvider(selectedConfig.providerId)
                        Text(
                            text = selectedProvider?.displayName ?: selectedConfig.providerId,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.remote_models),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = { showAddDialog = true }) {
                Text("+ ${stringResource(R.string.add_model)}")
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        if (configuredConfigs.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.remote_model_default_limit_title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.remote_model_default_limit_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            configuredConfigs.forEach { config ->
                RemoteModelConfigCard(
                    config = config,
                    isSelected = config.uniqueKey == selectedModelId,
                    onSelect = { onSelectedModelChange(config.uniqueKey) },
                    onConfigChange = { originalId, updatedConfig ->
                        val updated = configs.updateConfig(originalId, updatedConfig)
                        onConfigsChange(RemoteModelConfigs.toJson(updated))
                        if (originalId == selectedModelId && originalId != updatedConfig.uniqueKey) {
                            onSelectedModelChange(updatedConfig.uniqueKey)
                        }
                    },
                    onDelete = { modelId ->
                        val updated = configs.removeConfig(modelId)
                        onConfigsChange(RemoteModelConfigs.toJson(updated))
                        if (modelId == selectedModelId) {
                            val nextModel = updated.configs.find { it.isConfigured }?.uniqueKey
                            onSelectedModelChange(nextModel ?: "")
                        }
                    },
                    isPredefined = RemoteModelConfig.ALL_PREDEFINED_MODELS.any { it.modelId == config.modelId }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    if (showAddDialog) {
        AddProviderModelDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { newConfig ->
                val updated = configs.addConfig(newConfig)
                onConfigsChange(RemoteModelConfigs.toJson(updated))
                onSelectedModelChange(newConfig.uniqueKey)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun RemoteModelConfigCard(
    config: RemoteModelConfig,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onConfigChange: (String, RemoteModelConfig) -> Unit,
    onDelete: (String) -> Unit,
    isPredefined: Boolean
) {
    var isEditing by remember { mutableStateOf(false) }
    var editModelId by remember { mutableStateOf(config.modelId) }
    var editApiKey by remember { mutableStateOf(config.apiKey) }
    var editBaseUrl by remember { mutableStateOf(config.baseUrl) }
    var editProtocol by remember { mutableStateOf(config.protocol) }

    val provider = RemoteModelConfig.getProvider(config.providerId)
    val providerName = provider?.displayName ?: stringResource(R.string.provider_custom)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        onClick = onSelect
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            if (isEditing) {
                if (isPredefined) {
                    OutlinedTextField(
                        value = editApiKey,
                        onValueChange = { editApiKey = it },
                        label = { Text(stringResource(R.string.api_key)) },
                        placeholder = { Text(stringResource(R.string.api_key_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = {
                            onConfigChange(
                                config.uniqueKey,
                                config.copy(apiKey = editApiKey.trim())
                            )
                            isEditing = false
                        }) {
                            Text(stringResource(R.string.save))
                        }
                        TextButton(onClick = {
                            editApiKey = config.apiKey
                            isEditing = false
                        }) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = editModelId,
                        onValueChange = { editModelId = it },
                        label = { Text(stringResource(R.string.model_id)) },
                        placeholder = { Text("e.g. gpt-4o") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.protocol),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        RemoteProtocol.entries.forEach { protocol ->
                            FilterChip(
                                selected = editProtocol == protocol,
                                onClick = { editProtocol = protocol },
                                label = { Text(protocol.name) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = editBaseUrl,
                        onValueChange = { editBaseUrl = it },
                        label = { Text(stringResource(R.string.base_url)) },
                        placeholder = { Text(config.baseUrl) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = editApiKey,
                        onValueChange = { editApiKey = it },
                        label = { Text(stringResource(R.string.api_key)) },
                        placeholder = { Text(stringResource(R.string.api_key_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = {
                            onConfigChange(
                                config.uniqueKey,
                                config.copy(
                                    modelId = editModelId.trim(),
                                    apiKey = editApiKey.trim(),
                                    baseUrl = editBaseUrl.trim(),
                                    protocol = editProtocol
                                )
                            )
                            isEditing = false
                        }) {
                            Text(stringResource(R.string.save))
                        }
                        TextButton(onClick = {
                            editModelId = config.modelId
                            editApiKey = config.apiKey
                            editBaseUrl = config.baseUrl
                            editProtocol = config.protocol
                            isEditing = false
                        }) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = onSelect
                        )
                        Column {
                            Text(
                                text = config.modelId,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = providerName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { isEditing = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = stringResource(R.string.edit),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = { onDelete(config.uniqueKey) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(R.string.delete),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
