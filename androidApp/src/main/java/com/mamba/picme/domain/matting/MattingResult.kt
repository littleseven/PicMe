package com.mamba.picme.domain.matting

/** 抠图结果：alpha（0..1，width×height）已上采样到原图尺寸。 */
data class MattingResult(
    val alpha: FloatArray,
    val width: Int,
    val height: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MattingResult) return false
        return width == other.width && height == other.height && alpha.contentEquals(other.alpha)
    }

    override fun hashCode(): Int = 31 * (31 * width + height) + alpha.contentHashCode()
}
