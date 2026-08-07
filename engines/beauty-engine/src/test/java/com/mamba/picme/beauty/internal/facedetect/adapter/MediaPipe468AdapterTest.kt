package com.mamba.picme.beauty.internal.facedetect.adapter

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [QA] MediaPipe468Adapter 坐标镜像逻辑单元测试
 *
 * 测试目标：验证 lensFacing 参数对 X 坐标的镜像影响。
 * 这是相册编辑唇色偏移 bug 的核心根因：
 * - 预览路径（SurfaceTexture）使用 lensFacing=0，需要做 x=1-x 镜像
 * - 照片路径（Bitmap）应使用 lensFacing=1，不做镜像
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaPipe468AdapterTest {

    private val adapter = MediaPipe468Adapter()

    // ================================================================
    // X 镜像逻辑测试（核心 bug 场景）
    // ================================================================

    @Test
    fun `adapt with front camera lensFacing 0 mirrors X coordinate`() {
        // 构造一个只有 1 个点的简化输入（MediaPipe468Adapter 内部使用 468 点，
        // 但轮廓插值可能因点不足而失败，因此提供完整 468 点）
        val landmarks = createFakeLandmarks(centerX = 0.3f, centerY = 0.4f)

        val result = adapter.adapt(landmarks, lensFacing = 0)
        assertTrue("adapt should succeed", result.isSuccess)

        val unified = result.getOrThrow()
        // 106 点中的第 0 点（轮廓点 M0）对应 MediaPipe 127
        // 原始 x=0.3，前置镜像后应为 1-0.3=0.7
        val mirroredX = unified[0 * 2]
        assertEquals("Front camera should mirror X: 0.3 -> 0.7", 0.7f, mirroredX, 0.001f)

        // Y 坐标不应被镜像
        val y = unified[0 * 2 + 1]
        assertEquals("Y should not be mirrored", 0.4f, y, 0.001f)
    }

    @Test
    fun `adapt with back camera lensFacing 1 does not mirror X`() {
        val landmarks = createFakeLandmarks(centerX = 0.3f, centerY = 0.4f)

        val result = adapter.adapt(landmarks, lensFacing = 1)
        assertTrue("adapt should succeed", result.isSuccess)

        val unified = result.getOrThrow()
        val x = unified[0 * 2]
        assertEquals("Back camera should not mirror X", 0.3f, x, 0.001f)

        val y = unified[0 * 2 + 1]
        assertEquals("Y should remain unchanged", 0.4f, y, 0.001f)
    }

    @Test
    fun `adapt with center X 0_5 remains 0_5 for both lensFacing`() {
        val landmarks = createFakeLandmarks(centerX = 0.5f, centerY = 0.5f)

        // 前置：0.5 镜像后还是 0.5
        val frontResult = adapter.adapt(landmarks, lensFacing = 0)
        assertTrue(frontResult.isSuccess)
        val frontX = frontResult.getOrThrow()[0 * 2]
        assertEquals("Center X should remain 0.5 for front", 0.5f, frontX, 0.001f)

        // 后置：不变
        val backResult = adapter.adapt(landmarks, lensFacing = 1)
        assertTrue(backResult.isSuccess)
        val backX = backResult.getOrThrow()[0 * 2]
        assertEquals("Center X should remain 0.5 for back", 0.5f, backX, 0.001f)
    }

    @Test
    fun `adapt with front camera left side becomes right side`() {
        // 原始点在左侧（x=0.2），前置镜像后应在右侧（x=0.8）
        val landmarks = createFakeLandmarks(centerX = 0.2f, centerY = 0.5f)

        val result = adapter.adapt(landmarks, lensFacing = 0)
        assertTrue(result.isSuccess)

        val unified = result.getOrThrow()
        val mirroredX = unified[0 * 2]
        assertEquals("Left side should become right side", 0.8f, mirroredX, 0.001f)
    }

    // ================================================================
    // 错误处理测试
    // ================================================================

    @Test
    fun `adapt with insufficient landmarks returns failure`() {
        val landmarks = List(10) { createMockLandmark(0f, 0f) }
        val result = adapter.adapt(landmarks, lensFacing = 0)
        assertTrue("adapt should fail with insufficient landmarks", result.isFailure)
    }

    // ================================================================
    // 辅助方法
    // ================================================================

    /**
     * 构造 468 个 fake landmarks，所有点的坐标相同。
     * 简化测试：轮廓插值会生成 33 个轮廓点，其余 73 个点直接复制。
     */
    private fun createFakeLandmarks(centerX: Float, centerY: Float): List<NormalizedLandmark> {
        return List(468) { createMockLandmark(centerX, centerY) }
    }

    private fun createMockLandmark(x: Float, y: Float): NormalizedLandmark {
        return mockk<NormalizedLandmark>(relaxed = true) {
            every { x() } returns x
            every { y() } returns y
        }
    }
}
