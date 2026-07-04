package com.mamba.picme.domain.agent.capability.optimize.analyzer

/**
 * 场景分析器接口
 *
 * 分析单张图片，输出识别到的场景类型与置信度。
 */
interface SceneAnalyzer {

    /**
     * 分析图片场景
     *
     * @param imageUri 图片本地 URI
     * @return 场景分析结果
     */
    suspend fun analyze(imageUri: String): SceneAnalysis
}

/**
 * 场景分析结果
 *
 * @property scene 识别到的场景
 * @property confidence 置信度，0.0 ~ 1.0
 * @property signals 用于调试的识别信号列表
 */
data class SceneAnalysis(
    val scene: Scene,
    val confidence: Float,
    val signals: List<SceneSignal> = emptyList()
)

/**
 * 场景识别信号
 */
sealed class SceneSignal {

    /**
     * 人脸信号
     *
     * @property count 人脸数量
     * @property faceRatio 人脸占画面比例（最大人脸面积 / 图像面积）
     */
    data class Face(
        val count: Int,
        val faceRatio: Float
    ) : SceneSignal()

    /**
     * 图像标签信号
     *
     * @property labels ML Kit 识别出的 Top-K 标签
     */
    data class Labels(
        val labels: List<String>
    ) : SceneSignal()

    /**
     * 亮度信号
     *
     * @property meanBrightness 平均亮度，0.0 ~ 255.0
     */
    data class Brightness(
        val meanBrightness: Float
    ) : SceneSignal()

    /**
     * EXIF 信号
     *
     * @property iso ISO 值
     * @property focalLength 焦距（mm）
     */
    data class Exif(
        val iso: Int?,
        val focalLength: Float?
    ) : SceneSignal()
}
