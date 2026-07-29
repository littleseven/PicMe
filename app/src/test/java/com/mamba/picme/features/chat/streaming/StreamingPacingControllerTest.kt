package com.mamba.picme.features.chat.streaming

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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
        runCurrent()
        tickPacer(10)
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
        runCurrent()
        ctrl.finish()
        val sizeAfterFinish = paced.size
        tickPacer(10)
        assertEquals(sizeAfterFinish, paced.size)
    }

    @Test
    fun `grows substring each frame with step capped at MAX_STEP and catches up`() = runTest {
        val paced = mutableListOf<Paced>()
        val ctrl = StreamingPacingController(
            scope = this,
            onPaced = { t, c -> paced += Paced(t, c) },
            timeSource = { testScheduler.currentTime },
        )
        ctrl.start()
        ctrl.onTextSnapshot("x".repeat(100))
        runCurrent()
        tickPacer(1) // 帧 1：backlog=100 → step=100/8=12 → coerceIn(1,6)=6
        assertEquals(6, paced.last().text.length)
        tickPacer(1) // 帧 2：backlog=94 → step=6 → shown=12
        assertEquals(12, paced.last().text.length)
        tickPacer(50) // 追平（自适应步长，余量足够）
        assertEquals(100, paced.last().text.length)
        assertEquals("x".repeat(100), paced.last().text)
        ctrl.finish()
    }

    @Test
    fun `small backlog advances one char per frame`() = runTest {
        val paced = mutableListOf<Paced>()
        val ctrl = StreamingPacingController(
            scope = this,
            onPaced = { t, c -> paced += Paced(t, c) },
            timeSource = { testScheduler.currentTime },
        )
        ctrl.start()
        ctrl.onTextSnapshot("hi") // backlog=2 → step=2/8=0 → coerceIn(1,6)=1
        runCurrent()
        tickPacer(1)
        assertEquals(1, paced.last().text.length)
        tickPacer(1)
        assertEquals(2, paced.last().text.length)
        ctrl.finish()
    }
}

private fun TestScope.tickPacer(frames: Long) {
    advanceTimeBy(StreamingPacingController.FRAME_MS * frames)
    runCurrent()
}
