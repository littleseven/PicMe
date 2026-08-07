package com.mamba.picme.beauty.api.facedetect

/**
 * 人脸检测引擎类型（替代 domain 层的 FaceDetectionEngineMode）
 */
enum class EngineType {
    MEDIAPIPE,
    MNN       // [性能优化] MNN OpenCL GPU 检测器
}
