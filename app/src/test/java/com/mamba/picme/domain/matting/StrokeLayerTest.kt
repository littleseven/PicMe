package com.mamba.picme.domain.matting

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeLayerTest {

    private fun stroke(mode: StrokeMode, x: Float, y: Float, radius: Float = 1.5f) = BrushStroke(
        mode = mode,
        radiusPx = radius,
        softness = 0f,
        points = listOf(StrokePoint(x, y))
    )

    @Test
    fun `add undo redo clear state transitions`() {
        val layer = StrokeLayer()
        assertFalse(layer.canUndo)
        layer.addStroke(stroke(StrokeMode.RESTORE, 2f, 2f))
        assertTrue(layer.canUndo)
        assertEquals(1, layer.count)
        assertTrue(layer.undo())
        assertFalse(layer.canUndo)
        assertTrue(layer.canRedo)
        assertTrue(layer.redo())
        assertEquals(1, layer.count)
        // 新描边清空 redo 栈
        layer.undo()
        layer.addStroke(stroke(StrokeMode.ERASE, 2f, 2f))
        assertFalse(layer.canRedo)
        layer.clear()
        assertEquals(0, layer.count)
        assertFalse(layer.canUndo)
    }

    @Test
    fun `replay restore hard brush sets disc to 1`() {
        val layer = StrokeLayer()
        layer.addStroke(stroke(StrokeMode.RESTORE, x = 2f, y = 2f, radius = 1.5f))
        val base = FloatArray(25) // 5x5 全 0
        val out = layer.replayOnto(base, w = 5, h = 5)
        assertEquals(1f, out[2 * 5 + 2], 0.001f) // 圆心
        assertEquals(1f, out[2 * 5 + 3], 0.001f) // 半径内
        assertEquals(0f, out[0], 0.001f)         // 远处不受影响
    }

    @Test
    fun `replay erase hard brush sets disc to 0`() {
        val layer = StrokeLayer()
        layer.addStroke(stroke(StrokeMode.ERASE, x = 2f, y = 2f, radius = 1.5f))
        val base = FloatArray(25) { 1f } // 5x5 全 1
        val out = layer.replayOnto(base, w = 5, h = 5)
        assertEquals(0f, out[2 * 5 + 2], 0.001f)
        assertEquals(1f, out[0], 0.001f)
    }

    @Test
    fun `replay applies strokes in order restore then erase ends 0`() {
        val layer = StrokeLayer()
        layer.addStroke(stroke(StrokeMode.RESTORE, 2f, 2f))
        layer.addStroke(stroke(StrokeMode.ERASE, 2f, 2f))
        val out = layer.replayOnto(FloatArray(25), w = 5, h = 5)
        assertEquals(0f, out[2 * 5 + 2], 0.001f)
    }

    @Test
    fun `replay does not modify input array`() {
        val layer = StrokeLayer()
        layer.addStroke(stroke(StrokeMode.RESTORE, 2f, 2f))
        val base = FloatArray(25)
        val snapshot = base.copyOf()
        layer.replayOnto(base, w = 5, h = 5)
        assertArrayEquals(snapshot, base, 0f)
    }

    @Test
    fun `replay interpolates between stroke points`() {
        val layer = StrokeLayer()
        layer.addStroke(
            BrushStroke(
                mode = StrokeMode.ERASE,
                radiusPx = 1.5f,
                softness = 0f,
                points = listOf(StrokePoint(1f, 2f), StrokePoint(4f, 2f))
            )
        )
        val base = FloatArray(25) { 1f } // 5x5 全 1
        val out = layer.replayOnto(base, w = 5, h = 5)
        // 连线中点 (2,2)/(3,2) 也被填充为 0，证明插值发生
        assertEquals(0f, out[2 * 5 + 2], 0.001f)
        assertEquals(0f, out[2 * 5 + 3], 0.001f)
        assertEquals(0f, out[2 * 5 + 1], 0.001f) // 起点圆盘
        assertEquals(0f, out[2 * 5 + 4], 0.001f) // 终点圆盘
        assertEquals(1f, out[0], 0.001f)         // 远处不受影响
    }

    @Test
    fun `replay soft brush fades toward disc edge`() {
        val layer = StrokeLayer()
        layer.addStroke(
            BrushStroke(
                mode = StrokeMode.RESTORE,
                radiusPx = 2f,
                softness = 1f,
                points = listOf(StrokePoint(2f, 2f))
            )
        )
        val base = FloatArray(25) // 5x5 全 0
        val out = layer.replayOnto(base, w = 5, h = 5)
        assertEquals(1f, out[2 * 5 + 2], 0.001f) // 圆心 weight=1
        val fadeBand = out[3 * 5 + 3]            // d≈0.71，软边过渡带内
        assertTrue(fadeBand > 0f)
        assertTrue(fadeBand < 1f)
        assertEquals(0f, out[2 * 5 + 4], 0.001f) // d=1 恰在圆盘边界，weight=0 不写入
    }

    @Test
    fun `empty layer returns copy of base`() {
        val base = floatArrayOf(0.3f, 0.7f)
        val out = StrokeLayer().replayOnto(base, w = 2, h = 1)
        assertArrayEquals(base, out, 0f)
    }
}
