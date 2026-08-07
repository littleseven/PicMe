package com.mamba.picme.domain.model

import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.beauty.api.BeautySettings
import com.mamba.picme.beauty.api.FilterType
import com.mamba.picme.beauty.api.StyleFilter

/**
 * 主题模式（领域模型，与 Android 平台无关）
 */
enum class ThemeMode {
    SYSTEM, LIGHT, DARK
}

/**
 * 应用语言（领域模型）
 */
enum class AppLanguage {
    SYSTEM, ENGLISH, CHINESE, TRADITIONAL_CHINESE
}

/**
 * 美颜引擎策略（领域模型）
 *
 * BIG_BEAUTY: R 计划自研 OpenGL ES 管线（默认主引擎）
 */
enum class BeautyStrategy {
    BIG_BEAUTY
}

/**
 * 人脸检测算法模式（领域模型）
 * 表示使用的检测算法/模型系列
 */
enum class FaceDetectionEngineMode {
    MEDIAPIPE,  // MediaPipe 算法系列 (TFLite)
    MNN,        // MNN 算法系列 (GPU/CPU)
    CUSTOM      // 使用 StageConfig 独立配置
}

/**
 * 人脸检测间隔档位（领域模型）
 */
enum class FaceDetectIntervalProfile {
    CONSERVATIVE,
    BALANCED,
    AGGRESSIVE
}

/**
 * 检测阶段类型（领域模型）
 */
enum class DetectionStage {
    ROI,        // ROI 检测阶段
    LANDMARK    // 关键点检测阶段
}

/**
 * 检测模型类型（领域模型）
 * 用于 ROI 和 Landmark 阶段的模型选择
 * 模型与推理引擎绑定，选择模型即确定引擎
 */
enum class DetectionModelType {
    MEDIAPIPE,          // MediaPipe 系列模型 (TFLite)
    DET_500M_MNN,       // InsightFace RetinaFace-MobileNet0.25 (MNN)
    FACE_2D106_MNN;     // InsightFace 2D106 (MNN)

    /**
     * 获取模型对应的推理引擎类型
     */
    fun toEngineType(): InferenceEngineType = when (this) {
        MEDIAPIPE -> InferenceEngineType.TFLITE
        DET_500M_MNN, FACE_2D106_MNN -> InferenceEngineType.MNN
    }

    /**
     * 判断是否为 ROI 阶段可用的模型
     */
    fun isRoiModel(): Boolean = when (this) {
        MEDIAPIPE, DET_500M_MNN -> true
        FACE_2D106_MNN -> false
    }

    /**
     * 判断是否为 Landmark 阶段可用的模型
     */
    fun isLandmarkModel(): Boolean = when (this) {
        MEDIAPIPE, FACE_2D106_MNN -> true
        DET_500M_MNN -> false
    }
}

/**
 * 推理引擎类型（领域模型）
 * 用于控制人脸检测和关键点检测的底层推理框架
 */
enum class InferenceEngineType {
    MNN,                // MNN (支持 CPU/GPU)
    TFLITE              // TensorFlow Lite (MediaPipe 默认)
}

/**
 * 推理设备偏好（领域模型）
 * 用于强制指定推理引擎使用 CPU 或 GPU
 */
enum class InferenceDevicePreference {
    AUTO,               // 自动选择（默认）
    FORCE_CPU,          // 强制使用 CPU
    FORCE_GPU           // 强制使用 GPU
}

/**
 * 阶段配置数据类（领域模型）
 * 每个检测阶段（ROI/Landmark）独立配置模型、推理引擎和设备偏好
 */
data class StageConfig(
    val stage: DetectionStage,
    val modelType: DetectionModelType,
    val engineType: InferenceEngineType,
    val devicePreference: InferenceDevicePreference
) {
    companion object {
        fun defaultRoi(): StageConfig = StageConfig(
            stage = DetectionStage.ROI,
            modelType = DetectionModelType.MEDIAPIPE,
            engineType = DetectionModelType.MEDIAPIPE.toEngineType(),
            devicePreference = InferenceDevicePreference.AUTO
        )

        fun defaultLandmark(): StageConfig = StageConfig(
            stage = DetectionStage.LANDMARK,
            modelType = DetectionModelType.MEDIAPIPE,
            engineType = DetectionModelType.MEDIAPIPE.toEngineType(),
            devicePreference = InferenceDevicePreference.AUTO
        )
    }
}

/**
 * AI Agent 推理模式（领域模型）
 * 控制使用本地 MNN-LLM 模型还是远程 API
 *
 * 自 2026-06-17 起默认切换为 REMOTE（远程推理优先策略）。
 * 参考：apkClaw（/Users/guoshuai/code/ApkClaw）的 DefaultAgentService 设计，
 *       远程控制链路使用 langchain4j 的 tool_calls 机制编排。
 */
enum class AiAgentMode {
    OFF,     // 完全关闭 Agent
    LOCAL,   // 本地 MNN-LLM 模型（离线兜底）
    REMOTE   // 远程 API 推理（默认，优先使用，langchain4j + OpenAI 协议）
}

/**
 * AI Agent 隐私级别（领域模型）
 * 控制是否允许远程 API 调用
 * 远程推理优先策略下，PERMISSIVE 为实际默认行为。
 */
enum class AiAgentPrivacyLevel {
    STRICT,      // 绝对本地，禁止任何远程调用
    PERMISSIVE   // 允许远程（需用户显式确认）
}

enum class CameraSceneMode {
    NONE,
    NIGHT,
    MOON
}

enum class CameraGridMode {
    NONE,
    THIRDS,
    GOLDEN
}

enum class CameraAspectRatioMode {
    RATIO_4_3,
    RATIO_16_9,
    FULL
}

data class CameraMemoryState(
    val useFrontCamera: Boolean = false,
    val captureMode: MediaType = MediaType.PHOTO,
    val selectedFilter: FilterType = FilterType.NONE,
    val selectedStyleFilter: StyleFilter = StyleFilter.NONE,
    val beautySettings: BeautySettings = BeautySettings(enabled = false),
    val aspectRatio: CameraAspectRatioMode = CameraAspectRatioMode.FULL,
    val zoomRatio: Float = 1f,
    val exposureCompensation: Int = 0,
    val whiteBalanceMode: Int = 0,
    val sceneMode: CameraSceneMode = CameraSceneMode.NONE,
    val gridMode: CameraGridMode = CameraGridMode.NONE
)

/**
 * 模型分类标签（基于 MNN model_market.json 的 tagTranslations）
 *
 * 每个标签对应一个 Tab 分类，标签名来自 API 返回的 tagTranslations key。
 * 使用 value class 包装 String，支持动态标签且保持类型安全。
 *
 * @property tag 标签英文名（如 "Vision", "Think", "Audio"）
 */
@JvmInline
value class ModelCategory(val tag: String) {
    companion object {
        /** 所有模型（无标签过滤） */
        val ALL = ModelCategory("All")

        /** 预置的服务功能分类，用于本地缓存缺失时的默认展示 */
        val DEFAULT_CATEGORIES = listOf(
            ModelCategory("must-have"),
            ModelCategory("photo-tagging"),
            ModelCategory("beauty-camera"),
            ModelCategory("chat")
        )
    }

    override fun toString(): String = tag
}

/**
 * 模型分类标签翻译映射
 *
 * 来自 MNN model_market.json 的 tagTranslations 字段。
 * key: 英文标签（如 "Vision"）
 * value: 中文翻译（如 "图像理解"）
 */
typealias TagTranslations = Map<String, String>

