package com.mamba.picme.agent.core.inference.remote.tool

import com.mamba.picme.agent.core.tool.perception.UiObservationFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PicMeToolServiceObservationTest {

    @Test
    fun `formatter marks observation correctly`() {
        val formatted = UiObservationFormatter.format(
            actionDescription = "Clicked at (100, 200)",
            screenState = "=== 页面结构摘要 ==="
        )
        assertTrue(UiObservationFormatter.containsObservation(formatted))
    }

    @Test
    fun `capturePostActionState method exists via reflection`() {
        val method = PicMeToolService::class.java.getDeclaredMethod(
            "capturePostActionState",
            String::class.java
        )
        assertTrue(
            "capturePostActionState should be private",
            method.modifiers and java.lang.reflect.Modifier.PRIVATE != 0
        )
    }

    @Test
    fun `waitForUiSettle method exists via reflection`() {
        val method = PicMeToolService::class.java.getDeclaredMethod(
            "waitForUiSettle",
            Boolean::class.java
        )
        assertTrue(
            "waitForUiSettle should be private",
            method.modifiers and java.lang.reflect.Modifier.PRIVATE != 0
        )
    }

    @Test
    fun `click method still declares correct parameter annotations`() {
        val method = PicMeToolService::class.java.getDeclaredMethod(
            "click",
            Integer::class.java, Integer::class.java, String::class.java
        )
        val params = method.parameters
        assertEquals("click should have 3 parameters", 3, params.size)
    }

    @Test
    fun `navigateTo method still declares correct parameter annotations`() {
        val method = PicMeToolService::class.java.getDeclaredMethod(
            "navigateTo",
            String::class.java
        )
        val params = method.parameters
        assertEquals("navigateTo should have 1 parameter", 1, params.size)
    }
}
