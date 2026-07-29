package com.mamba.picme.features.chat.streaming

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamingPacingControllerTest {

    private data class Paced(val text: String, val cursorVisible: Boolean)

    @Test
    fun `start with no content stays silent`() = runTest {
        val paced = mutableListOf<Paced>()
        val ctrl = StreamingPacingController(
            scope = this,
            onPaced = { t, c -> paced += Paced(t, c) },
            timeSource = { testScheduler.currentTime },
        )
        ctrl.start()
        advanceTimeBy(16L * 10) // 10 帧
        assertTrue(paced.isEmpty())
        ctrl.finish()
    }

    @Test
    fun `finish stops the loop and emits nothing more`() = runTest {
        val paced = mutableListOf<Paced>()
        val ctrl = StreamingPacingController(
            scope = this,
            onPaced = { t, c -> paced += Paced(t, c) },
            timeSource = { testScheduler.currentTime },
        )
        ctrl.start()
        ctrl.finish()
        val sizeAfterFinish = paced.size
        advanceTimeBy(16L * 10)
        assertEquals(sizeAfterFinish, paced.size)
    }
}
