package com.mamba.picme.data.download

import android.content.Context
import com.mamba.picme.core.common.NetworkUtils
import com.mamba.picme.domain.repository.UserSettingsRepository
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 推荐模型 WiFi 静默预下载（最小实现）。
 *
 * 非 WorkManager——项目未引入 androidx.work；复用既有 [NetworkUtils.isWifiConnected]
 * 与 [LlmModelDownloadManager.downloadModel]。
 *
 * @param settings 用于读取 [UserSettingsRepository.autoDownloadRecommendedOnWifiFlow]。
 * @param downloader 用于查询已下载与发起下载。
 */
class RecommendedModelAutoDownloader(
    private val context: Context,
    private val settings: UserSettingsRepository,
    private val downloader: LlmModelDownloadManager
) {
    private val running = AtomicBoolean(false)

    /**
     * 纯逻辑：需要下载的推荐模型（[ModelConfig.RECOMMENDED_MODEL_IDS]
     * 去除已下载 [downloadedIds] 与进行中 [inProgressIds]，保持集合稳定顺序）。
     */
    companion object {
        fun computeMissing(
            downloadedIds: Set<String>,
            inProgressIds: Set<String>
        ): List<String> = ModelConfig.RECOMMENDED_MODEL_IDS
            .filter { id -> id !in downloadedIds && id !in inProgressIds }
    }

    /**
     * 满足条件时静默下载缺失推荐模型：设置开启 + WiFi + 有缺失项。
     * 不可重入；单模型失败不中断其余；不自动重试。
     */
    suspend fun triggerIfEligible(inProgressIds: Set<String> = emptySet()) {
        if (!running.compareAndSet(false, true)) return
        try {
            if (!settings.autoDownloadRecommendedOnWifiFlow.first()) return
            if (!NetworkUtils.isWifiConnected(context)) return
            val downloadedIds = ModelConfig.RECOMMENDED_MODEL_IDS
                .filter { id -> downloader.isModelDownloaded(id) }
                .toSet()
            val missing = computeMissing(downloadedIds, inProgressIds)
            for (id in missing) {
                runCatching { downloader.downloadModel(id).collect { /* 驱动至完成 */ } }
            }
        } finally {
            running.set(false)
        }
    }
}
