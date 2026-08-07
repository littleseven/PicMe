package com.mamba.picme.features.debug.pexels

/** 错误枚举优于字符串：UI 层映射到 stringResource（[I18N] 红线）。401 不走此枚举，直接回 NoKey(invalidPrevious=true) */
enum class PexelsErrorKind { RATE_LIMITED, NETWORK }

sealed interface PexelsUiState {

    /** 未配置 API Key；invalidPrevious=true 表示上一个 Key 被 401 拒绝 */
    data class NoKey(val invalidPrevious: Boolean = false) : PexelsUiState

    data object Loading : PexelsUiState

    data class Ready(
        val photos: List<PexelsPhoto>,
        val selectedIds: Set<Long> = emptySet(),
        val page: Int = 1,
        val endReached: Boolean = false,
        val loadingMore: Boolean = false,
        val downloading: Boolean = false,
        val downloadProgress: String = ""
    ) : PexelsUiState

    data class Error(val kind: PexelsErrorKind) : PexelsUiState
}

/** 一次性事件（Toast/Snackbar），不驻留状态 */
sealed interface PexelsEvent {
    data class DownloadCompleted(val success: Int, val total: Int) : PexelsEvent
}
