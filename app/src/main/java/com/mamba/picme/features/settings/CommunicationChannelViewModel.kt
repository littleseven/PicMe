package com.mamba.picme.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mamba.picme.domain.model.RemoteChannelType
import com.mamba.picme.domain.repository.UserSettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 「通信通道」配置页 VM：通道选择 + 飞书凭据 + Telegram 凭据。
 *
 * 直接依赖 [UserSettingsRepository]（与 MemoryFactsViewModel 同样的手动 DI 模式）。
 * 所有 state 由 DataStore Flow 驱动；写操作收口到 repository。
 */
class CommunicationChannelViewModel(
    private val repository: UserSettingsRepository
) : ViewModel() {

    val selectedChannel: StateFlow<RemoteChannelType> = repository.selectedRemoteChannelFlow
        .map { RemoteChannelType.fromStored(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RemoteChannelType.FEISHU)

    val feishuAppId: StateFlow<String> = repository.feishuAppIdFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val feishuAppSecret: StateFlow<String> = repository.feishuAppSecretFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val telegramBotToken: StateFlow<String> = repository.telegramBotTokenFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val telegramAllowedChatId: StateFlow<String> = repository.telegramAllowedChatIdFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    fun selectChannel(type: RemoteChannelType) {
        viewModelScope.launch { repository.updateSelectedRemoteChannel(type.name) }
    }

    fun setFeishuAppId(appId: String) {
        viewModelScope.launch { repository.updateFeishuAppId(appId) }
    }

    fun setFeishuAppSecret(secret: String) {
        viewModelScope.launch { repository.updateFeishuAppSecret(secret) }
    }

    /** Telegram token 与 chatId 同次写入（DataStore 原子 edit）。 */
    fun setTelegramConfig(botToken: String, allowedChatId: String) {
        viewModelScope.launch { repository.updateTelegramConfig(botToken, allowedChatId) }
    }

    companion object {
        fun factory(repository: UserSettingsRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(CommunicationChannelViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return CommunicationChannelViewModel(repository) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class")
                }
            }
    }
}
