package com.mamba.picme.core.image

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class FaceAwareAlignmentTest {
    // 竖图 2:3 进正方形 cell（Crop）：space=100×100，绘制后图片 size=100×150
    private val space = IntSize(100, 100)
    private val portrait = IntSize(100, 150)

    @Test
    fun null_focus_returns_center_alignment() {
        val a = faceAwareVerticalAlignment(null)
        assertEquals(
            Alignment.Center.align(portrait, space, LayoutDirection.Ltr),
            a.align(portrait, space, LayoutDirection.Ltr)
        )
    }

    @Test
    fun centered_face_shifts_up_by_bias() {
        // faceFocusY=0.5：rawY = 50 - (1/6)*100 - 0.5*150 = 50 - 16.6667 - 75 = -41.6667 → -42
        val a = faceAwareVerticalAlignment(0.5f)
        assertEquals(IntOffset(0, -42), a.align(portrait, space, LayoutDirection.Ltr))
    }

    @Test
    fun top_face_clamps_to_zero() {
        // faceFocusY=0.2：rawY = 50 - 16.6667 - 30 = 3.333 → coerceIn(-50,0) = 0
        val a = faceAwareVerticalAlignment(0.2f)
        assertEquals(IntOffset(0, 0), a.align(portrait, space, LayoutDirection.Ltr))
    }

    @Test
    fun bottom_face_clamps_to_min() {
        // faceFocusY=0.9：rawY = 50 - 16.6667 - 135 = -101.667 → coerceIn(-50,0) = -50
        val a = faceAwareVerticalAlignment(0.9f)
        assertEquals(IntOffset(0, -50), a.align(portrait, space, LayoutDirection.Ltr))
    }

    @Test
    fun landscape_keeps_horizontal_centered() {
        // 横图进竖卡（Crop）：space=100×150，size=200×150
        val s = IntSize(100, 150)
        val landscape = IntSize(200, 150)
        // faceFocusY=0.5：x=(100-200)/2=-50；y: minY=0, rawY=75-25-75=-25→coerceIn(0,0)=0
        val a = faceAwareVerticalAlignment(0.5f)
        assertEquals(IntOffset(-50, 0), a.align(landscape, s, LayoutDirection.Ltr))
    }
}
