package com.mamba.picme.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mamba.picme.R
import com.mamba.picme.domain.model.RemoteChannelType
import com.mamba.picme.features.common.topbar.AppTopBar
import com.mamba.picme.features.common.topbar.AppTopBarNavBack

/**
 * 「通信通道」配置页：单通道选择（飞书 / Telegram / 不启用）+ 双通道凭据 + 连接状态。
 *
 * 形态参照 [MemoryFactsScreen]（设置二级页 + onNavigateBack）；
 * 输入复用同包 `internal` 组件 [SettingsSection] / [SettingsTextInputRow]。
 *
 * @param isConnected 当前激活通道连接状态（进入页面时快照，由 Application 激活驱动）
 * @param isConfigured 当前选中通道凭据是否齐全（VM state 驱动，随输入实时更新）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunicationChannelScreen(
    viewModel: CommunicationChannelViewModel,
    isConnected: Boolean,
    isConfigured: Boolean,
    onNavigateBack: () -> Unit
) {
    val selected by viewModel.selectedChannel.collectAsState()
    val feishuAppId by viewModel.feishuAppId.collectAsState()
    val feishuAppSecret by viewModel.feishuAppSecret.collectAsState()
    val telegramBotToken by viewModel.telegramBotToken.collectAsState()
    val telegramChatId by viewModel.telegramAllowedChatId.collectAsState()

    val statusRes = when {
        !isConfigured -> R.string.channel_status_not_configured
        isConnected -> R.string.channel_status_connected
        else -> R.string.channel_status_disconnected
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = { Text(stringResource(R.string.communication_channel)) },
                navigationIcon = { AppTopBarNavBack(onClick = onNavigateBack) }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            // ── 通道选择 ──
            SettingsSection(title = stringResource(R.string.channel_selection)) {
                ChannelSelectionChips(selected = selected, onSelect = viewModel::selectChannel)
                Text(
                    text = stringResource(statusRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            // ── 飞书 ──
            SettingsSection(
                title = stringResource(R.string.channel_feishu),
                description = stringResource(R.string.feishu_channel_desc)
            ) {
                SettingsTextInputRow(
                    title = stringResource(R.string.feishu_app_id),
                    value = feishuAppId,
                    onValueChange = viewModel::setFeishuAppId,
                    placeholder = stringResource(R.string.feishu_app_id_placeholder)
                )
                SettingsTextInputRow(
                    title = stringResource(R.string.feishu_app_secret),
                    value = feishuAppSecret,
                    onValueChange = viewModel::setFeishuAppSecret,
                    placeholder = stringResource(R.string.feishu_app_secret_placeholder),
                    isPassword = true
                )
            }

            // ── Telegram ──
            SettingsSection(
                title = stringResource(R.string.channel_telegram),
                description = stringResource(R.string.telegram_channel_desc)
            ) {
                SettingsTextInputRow(
                    title = stringResource(R.string.telegram_bot_token),
                    value = telegramBotToken,
                    onValueChange = { viewModel.setTelegramConfig(it, telegramChatId) },
                    placeholder = stringResource(R.string.telegram_bot_token_placeholder),
                    isPassword = true
                )
                SettingsTextInputRow(
                    title = stringResource(R.string.telegram_chat_id),
                    value = telegramChatId,
                    onValueChange = { viewModel.setTelegramConfig(telegramBotToken, it) },
                    placeholder = stringResource(R.string.telegram_chat_id_placeholder)
                )
                Text(
                    text = stringResource(R.string.telegram_bot_token_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )
                Text(
                    text = stringResource(R.string.telegram_chat_id_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )
                Text(
                    text = stringResource(R.string.telegram_security_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChannelSelectionChips(
    selected: RemoteChannelType,
    onSelect: (RemoteChannelType) -> Unit
) {
    val options = listOf(
        RemoteChannelType.FEISHU to stringResource(R.string.channel_feishu),
        RemoteChannelType.TELEGRAM to stringResource(R.string.channel_telegram),
        RemoteChannelType.NONE to stringResource(R.string.channel_none)
    )
    FlowRow(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (type, label) ->
            FilterChip(
                selected = selected == type,
                onClick = { onSelect(type) },
                label = { Text(label) }
            )
        }
    }
}
