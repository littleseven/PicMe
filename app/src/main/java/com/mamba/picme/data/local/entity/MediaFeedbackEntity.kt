package com.mamba.picme.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "media_feedback",
    indices = [
        Index(value = ["media_id", "query_text", "feedback_type"], name = "index_media_feedback_lookup")
    ]
)
data class MediaFeedbackEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "media_id") val mediaId: String,
    @ColumnInfo(name = "feedback_type") val feedbackType: String,
    @ColumnInfo(name = "query_text") val queryText: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
