package com.mamba.picme.core.image

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

/**
 * 列表缩略图「人脸感知」纵向对齐。
 *
 * 在 AsyncImage 使用 `ContentScale.Crop`（不整体缩放）时，用本函数返回值作为 `alignment`，
 * 使含人脸的照片按人脸纵向位置对齐，避免居中裁剪「砍头」。
 *
 * - [faceFocusY] == null（无人脸 / 未回填 / 视频）→ 返回 [Alignment.Center]，与改动前一致。
 * - 否则把「人脸中心」对齐到「框中心上方 [biasUp]·框高」处，并 clamp 到合法裁剪范围。
 * - 横向始终居中（本功能只优化上下方向）。
 *
 * @param faceFocusY 人脸纵向聚焦点（归一化 0~1，来自 MediaAsset.faceFocusY）
 * @param biasUp 人脸中心相对框中心向上偏移的比例，默认 1/6（头顶留白，接近主流相册观感）
 */
fun faceAwareVerticalAlignment(
    faceFocusY: Float?,
    biasUp: Float = 1f / 6f
): Alignment = if (faceFocusY == null) {
    Alignment.Center
} else {
    Alignment { size, space, _ ->
        val x = ((space.width - size.width) / 2f).roundToInt()
        val minY = space.height - size.height
        val rawY = space.height / 2f - biasUp * space.height - faceFocusY * size.height
        val y = rawY.roundToInt().coerceIn(minY, 0)
        IntOffset(x, y)
    }
}
