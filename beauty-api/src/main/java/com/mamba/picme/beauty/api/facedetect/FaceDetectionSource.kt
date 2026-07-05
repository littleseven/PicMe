package com.mamba.picme.beauty.api.facedetect

/**
 * 人脸检测算法来源
 */
enum class FaceDetectionSource {
    NONE,
    MEDIAPIPE,
    MNN       // [性能优化] MNN OpenCL GPU 检测器
}
