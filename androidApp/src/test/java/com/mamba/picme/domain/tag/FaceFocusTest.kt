package com.mamba.picme.domain.tag

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// isReturnDefaultValues=true 下纯 JVM 测试拿到的是 stub RectF（字段恒为 0），
// 需 Robolectric 提供 android.graphics.RectF 的真实实现。
@RunWith(RobolectricTestRunner::class)
class FaceFocusTest {
    @Test
    fun empty_faces_returns_null() {
        assertNull(computeFaceFocusY(emptyList(), bitmapHeight = 1000))
    }

    @Test
    fun single_face_center() {
        // 人脸框 top=300 bottom=500 → 中心 400 / 1000 = 0.4
        val faces = listOf(FaceRoi(RectF(100f, 300f, 200f, 500f)))
        val y = computeFaceFocusY(faces, bitmapHeight = 1000)
        assertEquals(0.4f, y!!, 1e-4f)
    }

    @Test
    fun group_photo_uses_envelope_center() {
        // 两人脸：top=200/bottom=400 与 top=600/bottom=800
        // 并集中心 = (min(200,600) + max(400,800))/2 = (200+800)/2 = 500 → 0.5
        val faces = listOf(
            FaceRoi(RectF(10f, 200f, 100f, 400f)),
            FaceRoi(RectF(10f, 600f, 100f, 800f))
        )
        val y = computeFaceFocusY(faces, bitmapHeight = 1000)
        assertEquals(0.5f, y!!, 1e-4f)
    }

    @Test
    fun result_is_clamped_to_unit_range() {
        // 极端框：top=-100 bottom=2000，bitmapHeight=1000
        // 中心 = (-100+2000)/2 = 950 → 0.95（落在 [0,1]，clamp 不触发）
        val faces = listOf(FaceRoi(RectF(0f, -100f, 10f, 2000f)))
        val y = computeFaceFocusY(faces, bitmapHeight = 1000)
        assertEquals(0.95f, y!!, 1e-4f)
    }
}
