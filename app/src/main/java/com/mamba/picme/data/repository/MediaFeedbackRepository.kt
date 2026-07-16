package com.mamba.picme.data.repository

import com.mamba.picme.domain.search.FeedbackAction
import com.mamba.picme.domain.search.FeedbackScore

interface MediaFeedbackRepository {
    suspend fun recordFeedback(
        mediaId: String,
        queryText: String,
        sessionId: String,
        action: FeedbackAction
    )

    suspend fun getFeedbackScores(queryText: String): List<FeedbackScore>
}
