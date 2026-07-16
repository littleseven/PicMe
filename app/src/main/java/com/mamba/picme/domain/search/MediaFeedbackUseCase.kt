package com.mamba.picme.domain.search

import com.mamba.picme.agent.core.model.command.FeedbackAction
import com.mamba.picme.data.repository.MediaFeedbackRepository

class MediaFeedbackUseCase(
    private val repository: MediaFeedbackRepository
) {
    suspend fun record(
        mediaId: String,
        queryText: String,
        sessionId: String,
        action: FeedbackAction
    ) {
        repository.recordFeedback(mediaId, queryText, sessionId, action)
    }

    /**
     * 记录用户主动排除的约束（如“不要夜景”）。
     *
     * 当前版本为内存/空实现，不写入数据库，避免 DB migration；
     * 排除状态由 [ChatViewModel] 在会话内维护。
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun recordExclude(constraint: String, sessionId: String) {
        // 空实现占位；后续如需持久化再接入 repository。
    }

    suspend fun getScoresForQuery(queryText: String): Map<String, FeedbackScore> {
        return repository.getFeedbackScores(queryText).associateBy { it.mediaId }
    }

    fun calculateScoreDelta(score: FeedbackScore?): Float {
        if (score == null) return 0f
        return score.likeCount * LIKE_BONUS - score.dislikeCount * DISLIKE_PENALTY
    }

    companion object {
        const val LIKE_BONUS = 0.15f
        const val DISLIKE_PENALTY = 0.15f
    }
}
