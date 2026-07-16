package com.mamba.picme.data.repository

import com.mamba.picme.data.local.dao.MediaFeedbackDao
import com.mamba.picme.data.local.entity.MediaFeedbackEntity
import com.mamba.picme.agent.core.model.command.FeedbackAction
import com.mamba.picme.domain.search.FeedbackScore

class MediaFeedbackRepositoryImpl(
    private val dao: MediaFeedbackDao
) : MediaFeedbackRepository {

    override suspend fun recordFeedback(
        mediaId: String,
        queryText: String,
        sessionId: String,
        action: FeedbackAction
    ) {
        dao.insert(
            MediaFeedbackEntity(
                mediaId = mediaId,
                feedbackType = action.name.lowercase(),
                queryText = queryText,
                sessionId = sessionId,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    override suspend fun getFeedbackScores(queryText: String): List<FeedbackScore> {
        return dao.getFeedbackScoresForQuery(queryText).map { row ->
            FeedbackScore(
                mediaId = row.mediaId,
                likeCount = row.likeCount,
                dislikeCount = row.dislikeCount
            )
        }
    }
}
