package com.mamba.picme.beauty.internal.facedetect

import android.content.Context
import com.mamba.picme.beauty.api.facedetect.DetectionPipelineConfig
import com.mamba.picme.beauty.api.facedetect.DevicePreference
import com.mamba.picme.beauty.api.facedetect.InferenceBackendType
import com.mamba.picme.beauty.api.facedetect.LandmarkDetectorType
import com.mamba.picme.beauty.api.facedetect.RoiDetectorType

/**
 * 检测流水线工厂
 * 根据模型类型 + 推理引擎 + 设备偏好创建对应检测器
 */
internal object DetectionPipelineFactory {

    /**
     * 创建 ROI 检测器
     * 根据模型类型、引擎类型和设备偏好选择对应实现
     */
    fun createRoiDetector(
        type: RoiDetectorType,
        engine: InferenceBackendType,
        device: DevicePreference,
        context: Context,
        sharedMediaPipeDetector: MediaPipeFaceDetector? = null
    ): RoiDetector {
        return when (type) {
            RoiDetectorType.MEDIAPIPE -> {
                // MediaPipe 固定使用 TFLite，忽略引擎和设备选择
                // 使用共享的 MediaPipeFaceDetector 实例，避免重复初始化
                MediaPipeRoiDetector(sharedMediaPipeDetector ?: MediaPipeFaceDetector(context))
            }
            RoiDetectorType.DET10G -> {
                when (engine) {
                    InferenceBackendType.MNN -> {
                        // [关键策略] FORCE_GPU 或 AUTO 时要求 GPU，失败不降级
                        val requireGpu = device != DevicePreference.FORCE_CPU
                        MnnRoiDetector(context, requireGpu = requireGpu)
                    }
                    else -> error("DET10G only supports MNN backend")
                }
            }
        }
    }

    /**
     * 创建关键点检测器
     * 根据模型类型、引擎类型和设备偏好选择对应实现
     */
    fun createLandmarkDetector(
        type: LandmarkDetectorType,
        engine: InferenceBackendType,
        device: DevicePreference,
        context: Context,
        sharedMediaPipeDetector: MediaPipeFaceDetector? = null
    ): LandmarkDetector {
        return when (type) {
            LandmarkDetectorType.INSIGHTFACE_2D106 -> {
                when (engine) {
                    InferenceBackendType.MNN -> {
                        // [关键策略] FORCE_GPU 或 AUTO 时要求 GPU，失败不降级
                        val requireGpu = device != DevicePreference.FORCE_CPU
                        MnnLandmarkDetector(context, requireGpu = requireGpu)
                    }
                    else -> error("INSIGHTFACE_2D106 only supports MNN backend")
                }
            }
            LandmarkDetectorType.MEDIAPIPE -> {
                // MediaPipe 固定使用 TFLite，忽略引擎和设备选择
                // 使用共享的 MediaPipeFaceDetector 实例，避免重复初始化
                MediaPipeLandmarkDetector(sharedMediaPipeDetector ?: MediaPipeFaceDetector(context))
            }
        }
    }

    /**
     * 从完整配置创建检测器对
     */
    fun createPipeline(
        config: DetectionPipelineConfig,
        context: Context
    ): Pair<RoiDetector, LandmarkDetector> {
        // [优化] 当 ROI 和 Landmark 都使用 MediaPipe 时，共享同一个 MediaPipeFaceDetector 实例
        val sharedMediaPipeDetector = if (
            config.roiDetector == RoiDetectorType.MEDIAPIPE &&
            config.landmarkDetector == LandmarkDetectorType.MEDIAPIPE
        ) {
            MediaPipeFaceDetector(context)
        } else {
            null
        }

        val roiDetector = createRoiDetector(
            config.roiDetector,
            config.roiEngine,
            config.roiDevice,
            context,
            sharedMediaPipeDetector
        )
        val landmarkDetector = createLandmarkDetector(
            config.landmarkDetector,
            config.landmarkEngine,
            config.landmarkDevice,
            context,
            sharedMediaPipeDetector
        )
        return Pair(roiDetector, landmarkDetector)
    }
}
