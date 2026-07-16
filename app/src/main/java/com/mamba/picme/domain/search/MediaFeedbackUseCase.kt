package com.mamba.picme.domain.search

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
