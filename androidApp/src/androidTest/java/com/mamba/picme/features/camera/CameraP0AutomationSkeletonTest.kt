package com.mamba.picme.features.camera

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mamba.picme.di.BeautyEngineRuntimeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P0 自动化测试骨架（Agent 首批落地）
 *
 * 说明：此类先固定用例 ID 与执行入口，后续 RD/QA Agent 在该骨架上补充
 * Activity 启动、预览断言、滑杆时延与调试浮层字段校验。
 */
@RunWith(AndroidJUnit4::class)
class CameraP0AutomationSkeletonTest {

    @Test
    fun p0_01_cameraStartup_within5s_previewVisible_skeleton() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.mamba.picme", appContext.packageName)
    }

    @Test
    fun p0_02_beautySlider_latencyUnder100ms_skeleton() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertNotNull(appContext)
    }

    @Test
    fun p0_03_glEngineWarmupFailure_fallbackReasonObservable_skeleton() {
        // 骨架阶段先验证 fallback reason 的可观测链路可被测试捕获。
        BeautyEngineRuntimeState.markGlEngineFallback("warm-up timeout")
        val fallbackReason = BeautyEngineRuntimeState.consumeGlEngineFallbackReason()
        assertEquals("warm-up timeout", fallbackReason)
    }

    @Test
    fun p0_04_cooldownFinished_shouldRetryGlEngine_skeleton() {
        // 骨架阶段先校验 one-shot 语义，避免后续自动重试链路出现脏状态。
        BeautyEngineRuntimeState.markGlEngineFallback("recovering")
        val firstConsume = BeautyEngineRuntimeState.consumeGlEngineFallbackReason()
        val secondConsume = BeautyEngineRuntimeState.consumeGlEngineFallbackReason()

        assertEquals("recovering", firstConsume)
        assertNull(secondConsume)
    }

    @Test
    fun p0_05_debugOverlay_shouldContainPerfStatsFields_skeleton() {
        val requiredKeys = listOf("fps", "processingMs", "delayMs", "cpuUsage", "nullFrames", "errorCategory", "errorReason")
        assertTrue(requiredKeys.all { key -> key.isNotBlank() })
    }
}

