package com.mamba.picme.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mamba.picme.R
import com.mamba.picme.agent.core.remote.config.RemoteModelConfig
import com.mamba.picme.agent.core.remote.config.RemoteModelProvider

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AddProviderModelDialog(
    onDismiss: () -> Unit,
    onConfirm: (RemoteModelConfig) -> Unit
) {
    var selectedProvider by remember { mutableStateOf<RemoteModelProvider?>(null) }
    var selectedModel by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }

    val providers = RemoteModelConfig.PROVIDERS.filter { it.isVisible }
    val availableModels = selectedProvider?.models ?: emptyList()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加远程模型") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "供应商",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    providers.forEach { provider ->
                        FilterChip(
                            selected = selectedProvider?.providerId == provider.providerId,
                            onClick = {
                                selectedProvider = provider
                                selectedModel = ""
                            },
                            label = { Text(provider.displayName) }
                        )
                    }
                }

                if (availableModels.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "模型",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        availableModels.forEach { model ->
                            FilterChip(
                                selected = selectedModel == model,
                                onClick = { selectedModel = model },
                                label = { Text(model) }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.api_key)) },
                    placeholder = { Text(stringResource(R.string.api_key_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    selectedProvider?.let { provider ->
                        if (selectedModel.isNotBlank()) {
                            onConfirm(
                                RemoteModelConfig(
                                    modelId = selectedModel,
                                    providerId = provider.providerId,
                                    protocol = provider.protocol,
                                    baseUrl = provider.baseUrl,
                                    apiKey = apiKey.trim()
                                )
                            )
                        }
                    }
                },
                enabled = selectedProvider != null && selectedModel.isNotBlank()
            ) {
                Text(stringResource(R.string.add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
