package com.mamba.picme.agent.core.inference.local.llm

import android.content.Context
import com.mamba.picme.agent.core.platform.logging.Logger
import java.io.File

/**
 * LLM 模型文件管理器
 *
 * 负责从 assets 复制 LLM 模型到应用缓存目录，并管理模型可用性检查。
 * 从 beauty-engine 的 ModelManager 解耦，使 :agent-core 模块可独立编译。
 *
 * @param context Application Context
 */
class LlmModelManager(private val context: Context) {

    private val tag = "LlmModelManager"

    /**
     * LLM 模型信息
     */
    data class LlmModelInfo(
        val assetDir: String,
        val cacheDirName: String,
        val version: String
    )

    private val llmModelRegistry = mapOf(
        "qwen3_5_2b" to LlmModelInfo(
            assetDir = "models/llm/Qwen3.5-2B-MNN",
            cacheDirName = "qwen3_5_2b",
            version = "1.0"
        )
    )

    /**
     * 准备 LLM 模型目录
     *
     * 优先从下载目录 (llm_models/) 加载，其次从 assets 复制。
     *
     * @param modelKey 模型注册表中的 key，如 "qwen3_5_2b"
     * @return 模型目录绝对路径
     * @throws IllegalArgumentException 如果 key 不存在
     */
    fun prepareModel(modelKey: String): String {
        // 1. 优先检查下载目录：支持注册表模型和动态新增模型
        findDownloadedModelDir(modelKey)?.let { modelDir ->
            Logger.d(tag, "LLM model found in download dir: ${modelDir.absolutePath}")
            return modelDir.absolutePath
        }

        // 2. 从注册表回退到 assets（仅注册表内模型支持）
        val info = llmModelRegistry[modelKey]
            ?: throw IllegalArgumentException("Unknown LLM model key: $modelKey (not downloaded and not in registry)")
        return copyAssetModelToCache(info.assetDir, info.cacheDirName)
    }

    /**
     * 检查指定模型是否已缓存可用
     */
    fun isModelCached(modelKey: String): Boolean {
        findDownloadedModelDir(modelKey)?.let { _ ->
            return true
        }

        val info = llmModelRegistry[modelKey] ?: return false
        val cacheDir = File(context.filesDir, info.cacheDirName)
        return cacheDir.exists() && isModelComplete(cacheDir)
    }

    private fun findDownloadedModelDir(modelKey: String): File? {
        val directDir = File(File(context.filesDir, "llm_models"), modelKey)
        if (directDir.exists() && isModelComplete(directDir)) {
            return directDir
        }

        val info = llmModelRegistry[modelKey] ?: return null
        val mappedDir = File(File(context.filesDir, "llm_models"), info.cacheDirName)
        return if (mappedDir.exists() && isModelComplete(mappedDir)) {
            mappedDir
        } else {
            null
        }
    }

    private fun isModelComplete(dir: File): Boolean {
        return dir.walkTopDown().any { file -> file.name.endsWith(".mnn") }
    }

    private fun copyAssetModelToCache(assetDir: String, cacheDirName: String): String {
        val destDir = File(context.filesDir, cacheDirName)

        if (destDir.exists() && isModelComplete(destDir)) {
            return destDir.absolutePath
        }

        destDir.mkdirs()

        try {
            val assetFiles = context.assets.list(assetDir)
                ?: throw RuntimeException("Asset directory not found: $assetDir")

            for (fileName in assetFiles) {
                val assetPath = "$assetDir/$fileName"
                val destFile = File(destDir, fileName)

                context.assets.open(assetPath).use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            Logger.d(tag, "Model copied: $assetDir -> ${destDir.absolutePath}")
        } catch (e: Exception) {
            Logger.e(tag, "Failed to copy model from assets: $assetDir", e)
            throw RuntimeException("Failed to copy model from assets: $assetDir", e)
        }

        return destDir.absolutePath
    }
}
