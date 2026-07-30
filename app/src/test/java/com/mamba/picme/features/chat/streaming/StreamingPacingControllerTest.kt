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

    private fun newController(paced: MutableList<Paced>, scope: TestScope) =
        StreamingPacingController(scope = scope, onPaced = { t, c -> paced += Paced(t, c) })

    @Test
    fun `start with no content stays silent`() = runTest {
        val paced = mutableListOf<Paced>()
        val ctrl = newController(paced, this)
        ctrl.start()
        runCurrent()
        advanceMs(300)
        assertTrue(paced.isEmpty())
        ctrl.finish()
    }

    @Test
    fun `basic rate advances two chars per chunk at 50ms per char`() = runTest {
        val paced = mutableListOf<Paced>()
        val ctrl = newController(paced, this)
        ctrl.start()
        ctrl.onTextSnapshot("abcdefgh")
        runCurrent()
        advanceMs(100) // 跳1: 50*2=100ms（首跳无停顿）
        assertEquals("ab", paced.last().text)
        advanceMs(100) // 跳2
        assertEquals("abcd", paced.last().text)
        advanceMs(100) // 跳3
        assertEquals("abcdef", paced.last().text)
        ctrl.finish()
    }

    @Test
    fun `cjk chunk is two chars per hop`() = runTest {
        val paced = mutableListOf<Paced>()
        val ctrl = newController(paced, this)
        ctrl.start()
        ctrl.onTextSnapshot("你好世界")
        runCurrent()
        advanceMs(100)
        assertEquals("你好", paced.last().text)
        advanceMs(100)
        assertEquals("你好世界", paced.last().text)
        ctrl.finish()
    }

    @Test
    fun `punctuation adds extra delay before next chunk`() = runTest {
        val paced = mutableListOf<Paced>()
        val ctrl = newController(paced, this)
        ctrl.start()
        ctrl.onTextSnapshot("你好，世界") // chunks: 你好 | ，| 世界
        runCurrent()
        advanceMs(100) // 你好
        assertEquals("你好", paced.last().text)
        advanceMs(50) // ，（上一字符'好'非标点 → 50ms）
        assertEquals("你好，", paced.last().text)
        advanceMs(199) // 标点停顿共 200ms，还差 1ms
        assertEquals("你好，", paced.last().text)
        advanceMs(1)
        assertEquals("你好，世界", paced.last().text)
        ctrl.finish()
    }

    @Test
    fun `newline adds extra delay before next chunk`() = runTest {
        val paced = mutableListOf<Paced>()
        val ctrl = newController(paced, this)
        ctrl.start()
        ctrl.onTextSnapshot("你好\n世界") // chunks: 你好 | \n | 世界
        runCurrent()
        advanceMs(100) // 你好
        assertEquals("你好", paced.last().text)
        advanceMs(50) // \n
        assertEquals("你好\n", paced.last().text)
        advanceMs(299) // 换行停顿共 300ms，还差 1ms
        assertEquals("你好\n", paced.last().text)
        advanceMs(1)
        assertEquals("你好\n世界", paced.last().text)
        ctrl.finish()
    }

    @Test
    fun `finish hides cursor immediately`() = runTest {
        val paced = mutableListOf<Paced>()
        val ctrl = newController(paced, this)
        ctrl.start()
        ctrl.onTextSnapshot("hi")
        runCurrent()
        advanceMs(100) // hi 一跳（2 字母）追平
        ctrl.finish()
        assertEquals(false, paced.last().cursorVisible) // 立即隐藏，无余闪
    }

    @Test
    fun `non-continuous snapshot resets shown length`() = runTest {
        val paced = mutableListOf<Paced>()
        val ctrl = newController(paced, this)
        ctrl.start()
        ctrl.onTextSnapshot("hello world")
        runCurrent()
        advanceMs(1000) // 追平
        ctrl.onTextSnapshot("xyz") // 非连续 → 重置 shownLength
        advanceMs(500)
        assertEquals("xyz", paced.last().text) // 从 0 重新追到 xyz
        ctrl.finish()
    }
}

private fun TestScope.advanceMs(ms: Long) {
    advanceTimeBy(ms)
    runCurrent()
}
