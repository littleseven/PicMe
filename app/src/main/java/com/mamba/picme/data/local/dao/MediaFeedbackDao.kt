package com.mamba.picme.data.local.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mamba.picme.data.local.entity.MediaFeedbackEntity

@Dao
interface MediaFeedbackDao {

    @Insert
    suspend fun insert(feedback: MediaFeedbackEntity)

    @Query(
        """
        SELECT media_id, 
               SUM(CASE WHEN feedback_type = 'like' THEN 1 ELSE 0 END) as likeCount,
               SUM(CASE WHEN feedback_type = 'dislike' THEN 1 ELSE 0 END) as dislikeCount
        FROM media_feedback
        WHERE query_text = :queryText
        GROUP BY media_id
        """
    )
    suspend fun getFeedbackScoresForQuery(queryText: String): List<FeedbackScoreRow>

    @Query("SELECT * FROM media_feedback WHERE media_id = :mediaId AND query_text = :queryText")
    suspend fun getFeedbackForMediaAndQuery(mediaId: String, queryText: String): List<MediaFeedbackEntity>

    @Query("SELECT * FROM media_feedback WHERE media_id IN (:mediaIds) AND query_text = :queryText")
    suspend fun getFeedbackForMediaIds(mediaIds: List<String>, queryText: String): List<MediaFeedbackEntity>

    @Query("DELETE FROM media_feedback WHERE media_id = :mediaId AND feedback_type = :feedbackType AND query_text = :queryText")
    suspend fun deleteFeedback(mediaId: String, feedbackType: String, queryText: String)
}

data class FeedbackScoreRow(
    @ColumnInfo(name = "media_id") val mediaId: String,
    @ColumnInfo(name = "likeCount") val likeCount: Int,
    @ColumnInfo(name = "dislikeCount") val dislikeCount: Int
)
