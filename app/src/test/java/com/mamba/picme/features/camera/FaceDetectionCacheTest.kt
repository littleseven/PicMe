package com.mamba.picme.features.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * FaceDetectionCache 缓存逻辑测试
 *
 * 验证时间敏感的缓存行为、defensive copy 和过期逻辑。
 */
class FaceDetectionCacheTest {

    @Before
    fun setUp() {
        FaceDetectionCache.clear()
    }

    // ================================================================
    // cache 写语义 / 复杂生命周期（结构各不同，保留独立用例）
    // ================================================================

    @Test
    fun get_returnsDefensiveCopy() {
        val landmarks = FloatArray(212) { it / 212f }

        FaceDetectionCache.updateLandmarks106(landmarks)
        val cached1 = FaceDetectionCache.getCachedLandmarks106()!!
        val cached2 = FaceDetectionCache.getCachedLandmarks106()!!

        // 修改第一个返回的副本
        cached1[0] = 999f

        // 第二个副本和缓存都应不受影响
        assertEquals("Second copy should not be affected", 0f, cached2[0], 0.0001f)

        val cached3 = FaceDetectionCache.getCachedLandmarks106()!!
        assertEquals("Cache should not be affected by modifying returned copy", 0f, cached3[0], 0.0001f)
    }

    @Test
    fun update_overwritesPreviousValue() {
        val first = FloatArray(212) { 0.1f }
        val second = FloatArray(212) { 0.9f }

        FaceDetectionCache.updateLandmarks106(first)
        FaceDetectionCache.updateLandmarks106(second)

        val cached = FaceDetectionCache.getCachedLandmarks106()
        assertNotNull(cached)
        assertEquals("Cache should contain second value", 0.9f, cached!![0], 0.0001f)
    }

    @Test
    fun update_copiesInputArray() {
        val landmarks = FloatArray(212) { it / 212f }

        FaceDetectionCache.updateLandmarks106(landmarks)
        landmarks[0] = 999f // 修改原始数组

        val cached = FaceDetectionCache.getCachedLandmarks106()!!
        assertEquals("Cache should contain copied value, not reference", 0f, cached[0], 0.0001f)
    }

    @Test
    fun isValid_afterUpdateThenClearThenUpdate_returnsTrue() {
        FaceDetectionCache.updateLandmarks106(FloatArray(212) { 0.1f })
        FaceDetectionCache.clear()
        FaceDetectionCache.updateLandmarks106(FloatArray(212) { 0.2f })

        assertTrue("Cache should be valid after re-update", FaceDetectionCache.isValid())
        val cached = FaceDetectionCache.getCachedLandmarks106()
        assertNotNull(cached)
        assertEquals("Cache should contain latest value", 0.2f, cached!![0], 0.0001f)
    }

    @Test
    fun clear_isIdempotent() {
        FaceDetectionCache.clear()
        FaceDetectionCache.clear() // 不应抛异常
        FaceDetectionCache.clear()

        assertFalse("Cache should remain invalid", FaceDetectionCache.isValid())
        assertNull("Cache should remain null", FaceDetectionCache.getCachedLandmarks106())
    }

    @Test
    fun multipleSequentialUpdates_allValid() {
        for (i in 0..5) {
            FaceDetectionCache.updateLandmarks106(FloatArray(212) { i / 10f })
            assertTrue("Cache should be valid after update $i", FaceDetectionCache.isValid())
            val cached = FaceDetectionCache.getCachedLandmarks106()!!
            assertEquals("Cache should contain value $i", i / 10f, cached[0], 0.0001f)
        }
    }
}

// ================================================================
// getCachedLandmarks106 状态组合 — 参数化
// ================================================================

@RunWith(Parameterized::class)
class FaceDetectionCacheGetTest(
    private val testName: String,
    private val setup: () -> Unit,
    private val expectNull: Boolean,
    private val expectSize: Int
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> = listOf(
            arrayOf<Any>("update then get returns same values", { FaceDetectionCache.updateLandmarks106(FloatArray(212) { it / 212f }) }, false, 212),
            arrayOf<Any>("get without update returns null", {}, true, -1),
            arrayOf<Any>("get after clear returns null", { FaceDetectionCache.updateLandmarks106(FloatArray(212) { it / 212f }); FaceDetectionCache.clear() }, true, -1),
            arrayOf<Any>("get with empty array returns value", { FaceDetectionCache.updateLandmarks106(FloatArray(0)) }, false, 0),
        )
    }

    @Before
    fun setUp() {
        FaceDetectionCache.clear()
        setup()
    }

    @Test
    fun `getCachedLandmarks106 behaves as expected`() {
        val cached = FaceDetectionCache.getCachedLandmarks106()
        if (expectNull) {
            assertNull(testName, cached)
        } else {
            assertNotNull(testName, cached)
            if (expectSize >= 0) {
                assertEquals(testName, expectSize, cached!!.size)
            }
            if (expectSize == 212) {
                assertArrayEquals(testName, FloatArray(212) { it / 212f }, cached, 0.0001f)
            }
        }
    }
}

// ================================================================
// isValid 状态组合 — 参数化
// ================================================================

@RunWith(Parameterized::class)
class FaceDetectionCacheIsValidTest(
    private val testName: String,
    private val setup: () -> Unit,
    private val expected: Boolean
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> = listOf(
            arrayOf<Any>("isValid after update returns true", { FaceDetectionCache.updateLandmarks106(FloatArray(212) { it / 212f }) }, true),
            arrayOf<Any>("isValid without update returns false", {}, false),
            arrayOf<Any>("isValid after clear returns false", { FaceDetectionCache.updateLandmarks106(FloatArray(212) { it / 212f }); FaceDetectionCache.clear() }, false),
        )
    }

    @Before
    fun setUp() {
        FaceDetectionCache.clear()
        setup()
    }

    @Test
    fun `isValid reflects cache state`() {
        assertEquals(testName, expected, FaceDetectionCache.isValid())
    }
}
