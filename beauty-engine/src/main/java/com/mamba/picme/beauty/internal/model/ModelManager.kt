package com.mamba.picme.beauty.internal.model

import android.content.Context
import com.mamba.picme.beauty.api.Logger
import java.io.File

/**
 * 统一模型文件管理器
 *
 * 负责所有推理模型文件的元数据管理和 assets → filesDir 复制逻辑。
 * 替代各检测器中重复的 ensureModelFile() 方法。
 */
object ModelManager {

    private const val TAG = "ModelManager"

    /**
     * 模型文件元数据
     *
     * @param assetPath assets 中的路径（相对于 assets 根目录）
     * @param cacheName 复制到 filesDir 后的文件名
     * @param version 模型版本号，用于校验是否需要更新
     */
    data class ModelInfo(
        val assetPath: String,
        val cacheName: String,
        val version: String
    )

    // ── 模型注册表 ───────────────────────────────────────────

    /**
     * 需要远程下载的模型 key 集合（对应模型文件已从 assets 移除）
     */
    private val DOWNLOAD_ONLY_KEYS = setOf(
        "det_500m_mnn"
    )

    /**
     * 人脸检测模型下载配置（映射到 llm_models/<modelId>/ 目录）
     */
    private val FACE_DETECTION_DOWNLOAD_KEYS = mapOf(
        "det10g_mnn" to "face-det-retina10g-mnn",
        "2d106_mnn" to "face-landmark-2d106-mnn",
        "det_500m_mnn" to "face-det-retina500m-mnn"
    )

    private val MODEL_REGISTRY = mapOf(
        // MNN 模型（已从 assets 移除，优先从下载目录加载）
        "det10g_mnn" to ModelInfo(
            assetPath = "models/mnn/det_10g.mnn",
            cacheName = "det_10g.mnn",
            version = "1.0"
        ),
        "2d106_mnn" to ModelInfo(
            assetPath = "models/mnn/2d106det.mnn",
            cacheName = "2d106det.mnn",
            version = "1.0"
        ),
        "det_500m_mnn" to ModelInfo(
            assetPath = "models/mnn/det_500m.mnn",
            cacheName = "det_500m.mnn",
            version = "1.0"
        )
    )

    // ── 公共 API ─────────────────────────────────────────────

    /**
     * 准备单个模型文件
     *
     * 策略：优先从下载目录 (llm_models/) 加载，其次从 assets 复制。
     *
     * @param key 模型注册表中的 key
     * @param context Context
     * @return 模型文件
     * @throws IllegalArgumentException 如果 key 不存在
     * @throws RuntimeException 如果复制失败且下载目录无可用文件
     */
    fun prepareModel(key: String, context: Context): File {
        val info = MODEL_REGISTRY[key]
            ?: throw IllegalArgumentException("Unknown model key: $key")

        // 1. 优先检查下载目录
        val downloadKey = FACE_DETECTION_DOWNLOAD_KEYS[key]
        if (downloadKey != null) {
            val downloadFile = File(context.filesDir, "llm_models/$downloadKey/${info.cacheName}")
            if (downloadFile.exists() && downloadFile.length() > 0) {
                Logger.d(TAG, "Using downloaded model: ${downloadFile.absolutePath}")
                return downloadFile
            }
        }

        // 2. 回退到 assets 复制
        if (key !in DOWNLOAD_ONLY_KEYS) {
            return copyAssetToCache(info.assetPath, info.cacheName, context)
        }

        // 3. 下载-only 模型且未下载：抛出明确错误
        val downloadKeyName = FACE_DETECTION_DOWNLOAD_KEYS[key] ?: key
        throw IllegalStateException(
            "Model [$key] not found in download directory. " +
            "Please download it from ModelScope (model ID: $downloadKeyName) via Settings."
        )
    }

    /**
     * 检查模型是否已缓存（下载目录或 assets 缓存）
     */
    fun isModelCached(key: String, context: Context): Boolean {
        val info = MODEL_REGISTRY[key] ?: return false

        // 1. 检查下载目录
        val downloadKey = FACE_DETECTION_DOWNLOAD_KEYS[key]
        if (downloadKey != null) {
            val downloadFile = File(context.filesDir, "llm_models/$downloadKey/${info.cacheName}")
            if (downloadFile.exists() && downloadFile.length() > 0) {
                return true
            }
        }

        // 2. 检查传统缓存
        val file = File(context.filesDir, info.cacheName)
        return file.exists() && file.length() > 0L
    }

    /**
     * 将单个 asset 文件复制到缓存目录
     */
    private fun copyAssetToCache(assetPath: String, cacheName: String, context: Context): File {
        val file = File(context.filesDir, cacheName)

        if (file.exists() && file.length() > 0L) {
            return file
        }

        try {
            context.assets.open(assetPath).use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Logger.d(TAG, "Model copied: $assetPath -> ${file.absolutePath}")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to copy model: $assetPath", e)
            throw RuntimeException("Failed to copy model from assets: $assetPath", e)
        }

        return file
    }
}
