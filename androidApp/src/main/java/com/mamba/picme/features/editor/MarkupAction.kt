package com.mamba.picme.features.editor

/**
 * 归一化坐标（0..1，相对处理后图片的宽/高）。
 *
 * 标记在裁剪与 GPU 效果之后叠加（见 [RecipeApplier]），坐标系即最终输出图片；
 * 归一化存储保证预览（降采样）与保存（全分辨率）渲染一致，且可 JSON 序列化持久化。
 */
data class NormPoint(val x: Float, val y: Float)

/**
 * 标记动作（涂鸦/马赛克/文字）。
 *
 * 笔画宽度与文字大小同样以图片宽度的归一化比例存储，避免分辨率相关偏差。
 * 已知限制：先标记再改裁剪/旋转时标记不跟随，与多数相册编辑器「裁剪即压平」的取舍一致。
 */
sealed class MarkupAction {
    abstract val id: String

    data class Doodle(
        override val id: String,
        val points: List<NormPoint>,
        val color: Int,
        val strokeWidth: Float
    ) : MarkupAction()

    data class Mosaic(
        override val id: String,
        val points: List<NormPoint>,
        val strokeWidth: Float,
        val mode: MosaicMode = MosaicMode.PIXEL
    ) : MarkupAction()

    data class Text(
        override val id: String,
        val text: String,
        val position: NormPoint,
        val color: Int,
        val size: Float
    ) : MarkupAction()
}

enum class MosaicMode { PIXEL, BLUR }
