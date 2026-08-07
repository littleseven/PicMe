package com.mamba.picme.beauty.api.facedetect

import android.graphics.PointF

/**
 * 人脸 106 点数据结构（通用格式）
 *
 * 注：返回 111 个点，但前 106 个是标准 Face++ 106 点规范
 * 我们只使用标准的 106 点，忽略额外的 5 个辅助点
 *
 * [GC 优化] 新增 rawPoints 字段，fromFloatArray 直接存储 FloatArray 引用，
 * 避免创建 80+ 个 PointF 对象。热路径代码优先使用 rawPoints，
 * points 保留供调试 UI（debug overlay，默认关闭）使用。
 */
data class GpuPixelLandmarks(
    val points: List<PointF> = emptyList(),
    val hasFace: Boolean = false,
    val rawPoints: FloatArray = FloatArray(0)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as GpuPixelLandmarks

        if (points != other.points) return false
        if (hasFace != other.hasFace) return false
        if (!rawPoints.contentEquals(other.rawPoints)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = points.hashCode()
        result = 31 * result + hasFace.hashCode()
        result = 31 * result + rawPoints.contentHashCode()
        return result
    }

    companion object {
        /**
         * 从 float 数组创建（格式：[x0,y0,x1,y1,...]）
         * 只取前 106 个点，忽略额外的 5 个辅助点
         *
         * [GC 优化] 直接持有 FloatArray 引用，不创建 PointF 对象。
         */
        fun fromFloatArray(landmarks: FloatArray?): GpuPixelLandmarks {
            if (landmarks == null || landmarks.isEmpty()) {
                return GpuPixelLandmarks()
            }
            val count = minOf(landmarks.size / 2, 106) * 2
            val raw = landmarks.copyOfRange(0, count)
            return GpuPixelLandmarks(
                points = emptyList(),
                hasFace = true,
                rawPoints = raw
            )
        }
    }
}
