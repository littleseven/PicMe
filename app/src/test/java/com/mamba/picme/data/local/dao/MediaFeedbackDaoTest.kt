package com.mamba.picme.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.local.entity.MediaFeedbackEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MediaFeedbackDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: MediaFeedbackDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.mediaFeedbackDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `getFeedbackScoresForQuery aggregates likes and dislikes`() = runTest {
        dao.insert(MediaFeedbackEntity(mediaId = "1", feedbackType = "like", queryText = "海边", sessionId = "s1", createdAt = 1))
        dao.insert(MediaFeedbackEntity(mediaId = "1", feedbackType = "like", queryText = "海边", sessionId = "s1", createdAt = 2))
        dao.insert(MediaFeedbackEntity(mediaId = "1", feedbackType = "dislike", queryText = "海边", sessionId = "s1", createdAt = 3))
        dao.insert(MediaFeedbackEntity(mediaId = "2", feedbackType = "like", queryText = "海边", sessionId = "s1", createdAt = 4))
        dao.insert(MediaFeedbackEntity(mediaId = "1", feedbackType = "like", queryText = "山", sessionId = "s1", createdAt = 5))

        val scores = dao.getFeedbackScoresForQuery("海边")

        assertEquals(2, scores.size)
        val score1 = scores.first { it.mediaId == "1" }
        assertEquals(2, score1.likeCount)
        assertEquals(1, score1.dislikeCount)
    }
}
