package com.mamba.picme.beauty.internal.facedetect.mnn

/**
 * RetinaFace 检测到的人脸框数据类
 *
 * 对应 C++ 层的 picme::FaceBox 结构，用于 JNI 返回多脸检测结果。
 */
data class FaceBox(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val confidence: Float,
    val landmarks: FloatArray = floatArrayOf()
) {
    fun width(): Float = x2 - x1
    fun height(): Float = y2 - y1
    fun area(): Float = width() * height()

    /**
     * 转换为 [x1, y1, x2, y2, score, landmarks(10)] 格式的 FloatArray
     */
    fun toFloatArray(): FloatArray {
        val result = FloatArray(15)
        result[0] = x1
        result[1] = y1
        result[2] = x2
        result[3] = y2
        result[4] = confidence
        for (i in 0 until minOf(landmarks.size, 10)) {
            result[5 + i] = landmarks[i]
        }
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FaceBox
        return x1 == other.x1 && y1 == other.y1 && x2 == other.x2 && y2 == other.y2 &&
                confidence == other.confidence && landmarks.contentEquals(other.landmarks)
    }

    override fun hashCode(): Int {
        var result = x1.hashCode()
        result = 31 * result + y1.hashCode()
        result = 31 * result + x2.hashCode()
        result = 31 * result + y2.hashCode()
        result = 31 * result + confidence.hashCode()
        result = 31 * result + landmarks.contentHashCode()
        return result
    }
}
