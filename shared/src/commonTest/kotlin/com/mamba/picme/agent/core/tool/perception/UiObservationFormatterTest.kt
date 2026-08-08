package com.mamba.picme.agent.core.tool.perception

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiObservationFormatterTest {

    @Test
    fun `format combines action and screen state`() {
        val result = UiObservationFormatter.format(
            actionDescription = "Clicked element with text: '搜索照片'",
            screenState = "=== 页面结构摘要 ===\n页面标题: 相册\n"
        )

        assertTrue(result.startsWith("Action: Clicked element with text: '搜索照片'"))
        assertTrue(result.contains("Post-action screen state:"))
        assertTrue(result.contains("页面标题: 相册"))
    }

    @Test
    fun `containsObservation returns true when state prefix exists`() {
        val result = UiObservationFormatter.format(
            actionDescription = "Navigated to camera",
            screenState = "页面结构摘要"
        )
        assertTrue(UiObservationFormatter.containsObservation(result))
    }

    @Test
    fun `containsObservation returns false for plain strings`() {
        assertFalse(UiObservationFormatter.containsObservation("OK: capture executed"))
    }

    @Test
    fun `format preserves multi line screen state`() {
        val screenState = "Line 1\nLine 2\nLine 3"
        val result = UiObservationFormatter.format("Scrolled down", screenState)
        val lines = result.lines()
        assertEquals("Action: Scrolled down", lines[0])
        assertEquals("Post-action screen state:", lines[1])
        assertEquals("Line 1", lines[2])
        assertEquals("Line 2", lines[3])
        assertEquals("Line 3", lines[4])
    }
}
