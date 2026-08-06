package com.mamba.picme.domain.matting

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

enum class StrokeMode { RESTORE, ERASE }

/** 原图像素坐标点。 */
data class StrokePoint(val x: Float, val y: Float)

/**
 * 一条涂抹描边（矢量记录，非像素快照）：
 * [radiusPx] 原图像素坐标系下的半径；[softness] 0=硬边，1=全软边；
 * [points] 原图像素坐标折线（会话内 alpha 尺寸不可变，故不做归一化）。
 */
data class BrushStroke(
    val mode: StrokeMode,
    val radiusPx: Float,
    val softness: Float,
    val points: List<StrokePoint>
)

/**
 * 描边层：持有有序描边列表 + 重做栈，重放到参数层结果之上。
 * 撤销 = 移除尾条重放，天然无损；重放只写各描边包围盒局部区域。
 * 非线程安全：仅在 ViewModel 编排下使用（主线程收集点 + Default 调度重放，经状态串行化）。
 */
class StrokeLayer {

    private val strokes = mutableListOf<BrushStroke>()
    private val redoStack = mutableListOf<BrushStroke>()

    val count: Int get() = strokes.size
    val canUndo: Boolean get() = strokes.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun addStroke(stroke: BrushStroke) {
        strokes.add(stroke.copy(softness = stroke.softness.coerceIn(0f, 1f), points = stroke.points.toList()))
        redoStack.clear()
    }

    fun undo(): Boolean {
        val s = strokes.removeLastOrNull() ?: return false
        redoStack.add(s)
        return true
    }

    fun redo(): Boolean {
        val s = redoStack.removeLastOrNull() ?: return false
        strokes.add(s)
        return true
    }

    fun clear() {
        strokes.clear()
        redoStack.clear()
    }

    /** 把全部描边按序重放到 [base] 的拷贝上（不修改入参）。无描边时返回拷贝。 */
    fun replayOnto(base: FloatArray, w: Int, h: Int): FloatArray {
        val out = base.copyOf()
        for (stroke in strokes) replayStroke(out, w, h, stroke)
        return out
    }

    private fun replayStroke(out: FloatArray, w: Int, h: Int, stroke: BrushStroke) {
        if (stroke.points.isEmpty() || stroke.radiusPx <= 0f) return
        val target = if (stroke.mode == StrokeMode.RESTORE) 1f else 0f
        val step = max(1f, stroke.radiusPx / 2f)
        for (i in 0 until stroke.points.size) {
            val p = stroke.points[i]
            stampDisc(out, w, h, p.x, p.y, stroke.radiusPx, stroke.softness, target)
            if (i + 1 < stroke.points.size) {
                val q = stroke.points[i + 1]
                val dx = q.x - p.x
                val dy = q.y - p.y
                val dist = sqrt(dx * dx + dy * dy)
                var t = step
                while (t < dist) {
                    stampDisc(out, w, h, p.x + dx * t / dist, p.y + dy * t / dist,
                        stroke.radiusPx, stroke.softness, target)
                    t += step
                }
            }
        }
    }

    /** 在 (cx,cy) 处盖一个半径 r 的圆盘：weight 内向 target 混合，只写包围盒局部。 */
    private fun stampDisc(
        out: FloatArray, w: Int, h: Int,
        cx: Float, cy: Float, r: Float, softness: Float, target: Float
    ) {
        val r2 = r * r
        val x0 = floor(cx - r).toInt().coerceIn(0, w - 1)
        val x1 = ceil(cx + r).toInt().coerceIn(0, w - 1)
        val y0 = floor(cy - r).toInt().coerceIn(0, h - 1)
        val y1 = ceil(cy + r).toInt().coerceIn(0, h - 1)
        for (y in y0..y1) {
            for (x in x0..x1) {
                val dx = x - cx
                val dy = y - cy
                val d2 = dx * dx + dy * dy
                if (d2 >= r2) continue
                val d = sqrt(d2) / r
                val weight = if (softness <= 0f) 1f else ((1f - d) / softness).coerceIn(0f, 1f)
                if (weight <= 0f) continue
                val idx = y * w + x
                out[idx] = out[idx] * (1f - weight) + target * weight
            }
        }
    }
}
