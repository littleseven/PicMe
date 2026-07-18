package com.mamba.picme.domain.tag

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.mamba.picme.core.common.Logger

/**
 * ML Kit Image Labeler 标签提取器
 *
 * 封装 ML Kit Image Labeler 客户端生命周期，提供从 URI 提取英文标签的能力。
 * 输出结果按置信度降序排列，支持置信度阈值和最大数量限制。
 */
class MlKitTagExtractor(
    private val context: Context,
    private val confidenceThreshold: Float = 0.5f,
    private val maxLabels: Int = 5
) {

    companion object {
        private const val TAG = "PoLang:MlKitTagExtractor"

        /**
         * 纯函数：过滤并排序标签
         */
        fun filterLabels(
            labels: List<Pair<String, Float>>,
            confidenceThreshold: Float,
            maxLabels: Int
        ): List<String> {
            return labels
                .filter { it.second >= confidenceThreshold }
                .sortedByDescending { it.second }
                .take(maxLabels)
                .map { it.first }
        }

        /**
         * 纯函数：将标签列表序列化为 JSON 数组字符串
         *
         * 注意：不依赖 Android 的 org.json.JSONArray.toString()，
         * 避免在 JVM 单元测试中遇到 Android stub 抛异常。
         */
        fun toJsonArray(labels: List<String>): String {
            if (labels.isEmpty()) return "[]"
            return labels.joinToString(prefix = "[", postfix = "]", separator = ",") { label ->
                "\"${label.replace("\\", "\\\\").replace("\"", "\\\"")}\""
            }
        }
    }

    /**
     * ML Kit 图像标注客户端。
     *
     * 延迟初始化并在失败时降级为 null，避免在构造阶段因 ML Kit 内部初始化异常
     * （如 R8 构建下 MultiFlavorDetectorCreator NPE）导致崩溃。
     */
    private val labeler by lazy {
        try {
            ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to initialize ML Kit image labeler, tagging disabled", e)
            null
        }
    }

    /**
     * 从 URI 提取 ML Kit 英文标签
     *
     * @param uri 媒体 Content URI
     * @return 英文标签列表（已过滤/排序），失败返回空列表
     */
    fun extract(uri: String): List<String> {
        return try {
            val inputImage = InputImage.fromFilePath(context, Uri.parse(uri))
            extract(inputImage)
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to load InputImage from $uri: ${e.message}")
            emptyList()
        }
    }

    /**
     * 从 InputImage 提取 ML Kit 英文标签
     *
     * @param inputImage ML Kit 输入图像
     * @return 英文标签列表（已过滤/排序），失败返回空列表
     */
    fun extract(inputImage: InputImage): List<String> {
        val currentLabeler = labeler ?: run {
            Logger.w(TAG, "Image labeler unavailable, skipping label extraction")
            return emptyList()
        }
        return try {
            val result = Tasks.await(currentLabeler.process(inputImage))
            val labels = result.map { it.text to it.confidence }
            filterLabels(labels, confidenceThreshold, maxLabels)
                .also { Logger.d(TAG, "ML Kit labels extracted: $it") }
        } catch (e: com.google.mlkit.common.MlKitException) {
            if (e.message?.contains("download") == true || e.message?.contains("optional module") == true) {
                Logger.w(TAG, "ML Kit label model not ready yet, skipping")
            } else {
                Logger.e(TAG, "ML Kit label error", e)
            }
            emptyList()
        } catch (e: Exception) {
            Logger.e(TAG, "Label extraction failed", e)
            emptyList()
        }
    }

    /**
     * 预热 ML Kit 模型（首次使用可能触发 Play Services 下载）
     */
    fun warmup(inputImage: InputImage): Boolean {
        val currentLabeler = labeler ?: run {
            Logger.w(TAG, "Image labeler unavailable, skipping warmup")
            return false
        }
        return try {
            Tasks.await(currentLabeler.process(inputImage))
            true
        } catch (e: Exception) {
            Logger.w(TAG, "ML Kit warmup failed: ${e.message}")
            false
        }
    }

    fun close() {
        try {
            labeler?.close()
        } catch (e: Exception) {
            Logger.w(TAG, "Error closing labeler", e)
        }
    }
}
