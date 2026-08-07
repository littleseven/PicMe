package com.mamba.picme.domain.search

import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.data.local.MediaDao
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaSearchEngineFeedbackTest {

    private val mediaDao: MediaDao = mockk(relaxed = true)
    private val feedbackUseCase: MediaFeedbackUseCase = mockk()

    private val engine = MediaSearchEngine(
        mediaDao = mediaDao,
        mediaFeedbackUseCase = feedbackUseCase
    )

    @Test
    fun `liked media ranks higher for same query`() = runTest {
        coEvery { feedbackUseCase.getScoresForQuery("海边") } returns mapOf(
            "2" to FeedbackScore(mediaId = "2", likeCount = 1, dislikeCount = 0)
        )
        every { feedbackUseCase.calculateScoreDelta(any()) } answers {
            val score = it.invocation.args[0] as FeedbackScore?
            if (score == null) 0f else score.likeCount * 0.15f - score.dislikeCount * 0.15f
        }

        val media1 = createMediaAsset(id = 1, mediaId = "1")
        val media2 = createMediaAsset(id = 2, mediaId = "2")

        val scoreMap = mutableMapOf(1L to 0.5f, 2L to 0.5f)
        val mediaMap = mapOf(1L to media1, 2L to media2)

        engine.applyFeedbackScores(scoreMap, mediaMap, "海边")

        assertEquals(true, scoreMap[2L]!! > scoreMap[1L]!!)
    }

    private fun createMediaAsset(id: Long, mediaId: String): MediaAsset {
        return MediaAsset(
            id = id,
            uri = "",
            type = MediaType.PHOTO,
            captureDate = System.currentTimeMillis(),
            fileName = "",
            duration = 0,
            hasFace = false,
            faceId = null,
            source = "",
            labels = null,
            ocrText = null,
            latitude = null,
            longitude = null,
            locationName = null,
            indexedAt = 0
        )
    }
}
