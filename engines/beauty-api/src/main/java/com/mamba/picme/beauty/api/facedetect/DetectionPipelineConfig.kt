package com.mamba.picme.beauty.api.facedetect

/**
 * 推理引擎类型（与 domain 层 InferenceEngineType 对应）
 */
enum class InferenceBackendType {
    ONNX,       // ONNX Runtime (CPU/NNAPI)
    MNN,        // MNN (支持 CPU/GPU)
    TFLITE;     // TensorFlow Lite (MediaPipe 默认)

    val displayName: String
        get() = when (this) {
            ONNX -> "ONNX"
            MNN -> "MNN"
            TFLITE -> "TFLite"
        }
}

/**
 * 推理设备偏好（与 domain 层 InferenceDevicePreference 对应）
 */
enum class DevicePreference {
    AUTO,       // 自动选择
    FORCE_CPU,  // 强制 CPU
    FORCE_GPU;  // 强制 GPU

    val displayName: String
        get() = when (this) {
            AUTO -> "Auto"
            FORCE_CPU -> "CPU"
            FORCE_GPU -> "GPU"
        }
}

/**
 * 检测流水线配置
 * 支持模型类型 + 推理引擎 + 设备偏好的完整组合
 */
data class DetectionPipelineConfig(
    val roiDetector: RoiDetectorType = RoiDetectorType.DET10G,
    val landmarkDetector: LandmarkDetectorType = LandmarkDetectorType.INSIGHTFACE_2D106,
    val roiEngine: InferenceBackendType = InferenceBackendType.ONNX,
    val landmarkEngine: InferenceBackendType = InferenceBackendType.ONNX,
    val roiDevice: DevicePreference = DevicePreference.AUTO,
    val landmarkDevice: DevicePreference = DevicePreference.AUTO,
    val useLooseCrop: Boolean = false
)

enum class RoiDetectorType {
    MEDIAPIPE,
    DET10G;

    val displayName: String
        get() = when (this) {
            MEDIAPIPE -> "MediaPipe"
            DET10G -> "InsightFace Det10G"
        }
}

enum class LandmarkDetectorType {
    INSIGHTFACE_2D106,
    MEDIAPIPE;

    val displayName: String
        get() = when (this) {
            INSIGHTFACE_2D106 -> "InsightFace 2D106"
            MEDIAPIPE -> "MediaPipe"
        }
}
