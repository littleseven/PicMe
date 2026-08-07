package com.mamba.picme.beauty.internal.facedetect.adapter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * MnnLandmarkAdapter remap 表和坐标转换测试
 *
 * 验证 MNN 原始 106 点 → 统一 106 标准的映射正确性，
 * 以及前置/后置摄像头的镜像处理。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MnnLandmarkAdapterTest {

    private val adapter = MnnLandmarkAdapter()

    /**
     * 验证 remap 表的 identity 映射情况。
     *
     * 当前 remap 表中有 2 个已知的 identity 映射（unifiedIdx=28→mnnIdx=28，
     * unifiedIdx=83→mnnIdx=83），这是从视觉检查中手动建立映射表时的遗留。
     *
     * 此测试记录这些已知情况，如果 identity 映射数量增加，则表明 remap 表被错误修改。
     */
    @Test
    fun fullRemap_identityMappingsAreKnownAndStable() {
        // 构造输入：每个点的 x = mnnIdx, y = 0
        val native1 = FloatArray(106 * 2) { idx ->
            val pointIdx = idx / 2
            if (idx % 2 == 0) pointIdx.toFloat() else 0f
        }

        val unified = adapter.adapt(native1, lensFacing = 1).getOrThrow()

        val identityMappings = (0 until 106).filter { unifiedIdx ->
            kotlin.math.abs(unified[unifiedIdx * 2] - unifiedIdx) < 0.001f
        }

        // 使用第二组输入交叉验证（offset 1000）
        val native2 = FloatArray(106 * 2) { idx ->
            val pointIdx = idx / 2
            if (idx % 2 == 0) (pointIdx + 1000).toFloat() else 0f
        }
        val unified2 = adapter.adapt(native2, lensFacing = 1).getOrThrow()

        val identityMappings2 = (0 until 106).filter { unifiedIdx ->
            kotlin.math.abs(unified2[unifiedIdx * 2] - (unifiedIdx + 1000)) < 0.001f
        }

        // 两组输入应发现相同的 identity 映射位置
        assertEquals(
            "Identity mappings should be consistent across different inputs",
            identityMappings,
            identityMappings2
        )

        // 记录当前已知的 identity 映射数量（2 个）
        // 如果数量变化，说明 remap 表被修改，需要人工复核
        assertEquals(
            "Known identity mappings in FULL_REMAP: unifiedIdx=28→28, unifiedIdx=83→83. " +
                "If count changed, remap table may have been incorrectly modified. " +
                "Found identity mappings at: $identityMappings",
            2,
            identityMappings.size
        )
    }

    // ── front/back mirror & coordinate preservation ───────────

    @Test
    fun adapt_backPreserves_frontMirrors_andYUnchanged() {
        // 每个点的 x = mnnIdx / 106, y = mnnIdx / 106
        val native = FloatArray(106 * 2) { idx ->
            val pointIdx = idx / 2
            pointIdx / 106f
        }

        // BACK = 1: x 和 y 都应保持不变（经 remap 置换）
        val backUnified = adapter.adapt(native, lensFacing = 1).getOrThrow()
        val expectedValues = (0 until 106).map { it / 106f }.toSortedSet()
        assertEquals(
            "Back camera: x coordinates should be a permutation",
            expectedValues,
            (0 until 106).map { backUnified[it * 2] }.toSortedSet()
        )
        assertEquals(
            "Back camera: y coordinates should be preserved",
            expectedValues,
            (0 until 106).map { backUnified[it * 2 + 1] }.toSortedSet()
        )

        // FRONT = 0: x 应被镜像 (1 - x)，y 不变
        val frontUnified = adapter.adapt(native, lensFacing = 0).getOrThrow()
        val expectedMirroredX = (0 until 106).map { 1f - it / 106f }.toSortedSet()
        assertEquals(
            "Front camera: x coordinates should be mirrored (1 - x)",
            expectedMirroredX,
            (0 until 106).map { frontUnified[it * 2] }.toSortedSet()
        )
        assertEquals(
            "Front camera: y coordinate should not be mirrored",
            expectedValues,
            (0 until 106).map { frontUnified[it * 2 + 1] }.toSortedSet()
        )
    }

    // ── input size validation ─────────────────────────────────

    @Test
    fun adapt_inputSizeVariants_correctSuccessOrFail() {
        // 远小于 212 → 失败
        assertFalse(
            adapter.adapt(FloatArray(100), lensFacing = 1).isSuccess
        )
        // 恰好 212 → 成功，输出 212
        val exactResult = adapter.adapt(FloatArray(212) { it / 212f }, lensFacing = 1)
        assertTrue(exactResult.isSuccess)
        assertEquals("Output should have exactly 212 floats", 212, exactResult.getOrThrow().size)
        // 超过 212 → 成功
        assertTrue(
            adapter.adapt(FloatArray(300) { it / 300f }, lensFacing = 1).isSuccess
        )
    }

    // ── zero-value propagation ────────────────────────────────

    @Test
    fun adapt_zeroValues_backPreserved_frontProducesOnesForX() {
        val native = FloatArray(212) { 0f }

        // BACK: 全零输入 → 全零输出
        val backUnified = adapter.adapt(native, lensFacing = 1).getOrThrow()
        for (i in backUnified.indices) {
            assertEquals("Zero input should produce zero output at index $i", 0.0f, backUnified[i], 0.0001f)
        }

        // FRONT: 全零输入 → x = 1.0 (1 - 0), y = 0.0
        val frontUnified = adapter.adapt(native, lensFacing = 0).getOrThrow()
        for (unifiedIdx in 0 until 106) {
            assertEquals(
                "Front camera: x should be 1.0 at unifiedIdx=$unifiedIdx",
                1.0f, frontUnified[unifiedIdx * 2], 0.0001f
            )
            assertEquals(
                "Front camera: y should be 0.0 at unifiedIdx=$unifiedIdx",
                0.0f, frontUnified[unifiedIdx * 2 + 1], 0.0001f
            )
        }
    }

    // ── remap uniqueness ──────────────────────────────────────

    @Test
    fun adapt_remapProducesUniqueMapping() {
        // 构造两个输入，只有第 42 个 mnnIdx 不同
        val native1 = FloatArray(106 * 2) { 0.5f }
        native1[42 * 2] = 0.1f
        native1[42 * 2 + 1] = 0.2f

        val native2 = FloatArray(106 * 2) { 0.5f }
        native2[42 * 2] = 0.9f
        native2[42 * 2 + 1] = 0.8f

        val unified1 = adapter.adapt(native1, lensFacing = 1).getOrThrow()
        val unified2 = adapter.adapt(native2, lensFacing = 1).getOrThrow()

        // 只有一个 mnnIdx 不同，所以应该恰好有一个 unifiedIdx 不同
        val diffCount = (0 until 106).count { unifiedIdx ->
            unified1[unifiedIdx * 2] != unified2[unifiedIdx * 2] ||
                unified1[unifiedIdx * 2 + 1] != unified2[unifiedIdx * 2 + 1]
        }
        assertEquals(
            "Only one mnn point changed, so exactly one unified point should differ",
            1,
            diffCount
        )
    }
}
