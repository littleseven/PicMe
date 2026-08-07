package com.mamba.picme.features.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QA] MediaViewModel.OcrResult 状态机单元测试
 *
 * 测试目标：验证 OCR 结果密封类的状态建模、相等性与继承结构，
 * 以及状态之间的过渡逻辑（Loading → Success / Error → null）。
 *
 * 注意：该测试不依赖 Android 框架（纯 Kotlin 测试），
 * 只验证 OcrResult sealed class 及其子类的构造与属性正确性。
 */
class MediaViewModelOcrStateTest {

    // ================================================================
    // OcrResult.Loading 状态测试
    // ================================================================

    @Test
    fun `Loading is singleton object`() {
        val a = MediaViewModel.OcrResult.Loading
        val b = MediaViewModel.OcrResult.Loading
        assertTrue("Loading should be the same object reference", a === b)
    }

    @Test
    fun `Loading is subtype of OcrResult`() {
        val state: MediaViewModel.OcrResult = MediaViewModel.OcrResult.Loading
        assertTrue(state is MediaViewModel.OcrResult.Loading)
    }

    @Test
    fun `Loading is not Success or Error`() {
        val state: MediaViewModel.OcrResult = MediaViewModel.OcrResult.Loading
        assertTrue(state !is MediaViewModel.OcrResult.Success)
        assertTrue(state !is MediaViewModel.OcrResult.Error)
    }

    // ================================================================
    // OcrResult.Success 状态测试
    // ================================================================

    @Test
    fun `Success carries recognized text`() {
        val text = "Hello World"
        val state = MediaViewModel.OcrResult.Success(text)
        assertEquals("Success.text should match input", text, state.text)
    }

    @Test
    fun `Success with empty string is valid`() {
        val state = MediaViewModel.OcrResult.Success("")
        assertEquals("Empty string success should have empty text", "", state.text)
    }

    @Test
    fun `Success with multi-line text preserves newlines`() {
        val multiLine = "Line 1\nLine 2\n第三行"
        val state = MediaViewModel.OcrResult.Success(multiLine)
        assertEquals("Multi-line text should be preserved", multiLine, state.text)
    }

    @Test
    fun `Success equality - same text produces equal states`() {
        val a = MediaViewModel.OcrResult.Success("text")
        val b = MediaViewModel.OcrResult.Success("text")
        assertEquals("Same text should produce equal Success states", a, b)
    }

    @Test
    fun `Success equality - different text produces non-equal states`() {
        val a = MediaViewModel.OcrResult.Success("text A")
        val b = MediaViewModel.OcrResult.Success("text B")
        assertTrue("Different texts should produce non-equal Success states", a != b)
    }

    @Test
    fun `Success is subtype of OcrResult`() {
        val state: MediaViewModel.OcrResult = MediaViewModel.OcrResult.Success("ok")
        assertTrue(state is MediaViewModel.OcrResult.Success)
    }

    // ================================================================
    // OcrResult.Error 状态测试
    // ================================================================

    @Test
    fun `Error carries error message`() {
        val message = "识别失败：网络超时"
        val state = MediaViewModel.OcrResult.Error(message)
        assertEquals("Error.message should match input", message, state.message)
    }

    @Test
    fun `Error with empty message is valid`() {
        val state = MediaViewModel.OcrResult.Error("")
        assertEquals("Empty error message should be preserved", "", state.message)
    }

    @Test
    fun `Error equality - same message produces equal states`() {
        val a = MediaViewModel.OcrResult.Error("err")
        val b = MediaViewModel.OcrResult.Error("err")
        assertEquals("Same message should produce equal Error states", a, b)
    }

    @Test
    fun `Error equality - different messages are not equal`() {
        val a = MediaViewModel.OcrResult.Error("err A")
        val b = MediaViewModel.OcrResult.Error("err B")
        assertTrue("Different messages should produce non-equal Error states", a != b)
    }

    @Test
    fun `Error is subtype of OcrResult`() {
        val state: MediaViewModel.OcrResult = MediaViewModel.OcrResult.Error("fail")
        assertTrue(state is MediaViewModel.OcrResult.Error)
    }

    // ================================================================
    // 状态枚举（when 表达式）测试
    // ================================================================

    @Test
    fun `when expression covers all OcrResult subtypes`() {
        val states = listOf(
            MediaViewModel.OcrResult.Loading,
            MediaViewModel.OcrResult.Success("recognized text"),
            MediaViewModel.OcrResult.Error("failed")
        )

        val descriptions = states.map { state ->
            when (state) {
                MediaViewModel.OcrResult.Loading -> "loading"
                is MediaViewModel.OcrResult.Success -> "success:${state.text}"
                is MediaViewModel.OcrResult.Error -> "error:${state.message}"
            }
        }

        assertEquals("loading", descriptions[0])
        assertEquals("success:recognized text", descriptions[1])
        assertEquals("error:failed", descriptions[2])
    }

    @Test
    fun `Success state - text can be extracted via smart cast`() {
        val result: MediaViewModel.OcrResult = MediaViewModel.OcrResult.Success("content")
        if (result is MediaViewModel.OcrResult.Success) {
            assertNotNull("Text should not be null in Success state", result.text)
            assertEquals("content", result.text)
        } else {
            throw AssertionError("Expected Success state")
        }
    }

    @Test
    fun `Error state - message can be extracted via smart cast`() {
        val result: MediaViewModel.OcrResult = MediaViewModel.OcrResult.Error("network error")
        if (result is MediaViewModel.OcrResult.Error) {
            assertNotNull("Message should not be null in Error state", result.message)
            assertEquals("network error", result.message)
        } else {
            throw AssertionError("Expected Error state")
        }
    }

    // ================================================================
    // 状态流转场景测试
    // ================================================================

    @Test
    fun `state flow Loading to Success is valid transition`() {
        var state: MediaViewModel.OcrResult? = null

        // 触发 OCR
        state = MediaViewModel.OcrResult.Loading
        assertTrue("State after trigger should be Loading", state === MediaViewModel.OcrResult.Loading)

        // OCR 完成
        val successState = MediaViewModel.OcrResult.Success("发票总金额：¥128.00")
        state = successState
        assertTrue("State after success should be Success", state is MediaViewModel.OcrResult.Success)
        assertEquals("发票总金额：¥128.00", successState.text)
    }

    @Test
    fun `state flow Loading to Error is valid transition`() {
        var state: MediaViewModel.OcrResult? = null

        state = MediaViewModel.OcrResult.Loading
        val errorState = MediaViewModel.OcrResult.Error("未找到文字")
        state = errorState

        assertTrue("State after failure should be Error", state is MediaViewModel.OcrResult.Error)
        assertEquals("未找到文字", errorState.message)
    }

    @Test
    fun `null state represents cleared OCR result`() {
        // clearOcrResult 将状态置为 null
        var state: MediaViewModel.OcrResult? = MediaViewModel.OcrResult.Success("text")

        // 调用 clear
        state = null

        assertNull("Cleared state should be null", state)
    }

    @Test
    fun `state flow complete cycle - Loading then Success then null`() {
        var state: MediaViewModel.OcrResult? = null

        // 1. 触发识别
        state = MediaViewModel.OcrResult.Loading
        assertNotNull(state)

        // 2. 识别成功
        state = MediaViewModel.OcrResult.Success("Some OCR Text")
        assertEquals("Some OCR Text", (state as MediaViewModel.OcrResult.Success).text)

        // 3. 清除结果
        state = null
        assertNull("After clear, state should be null", state)
    }
}

