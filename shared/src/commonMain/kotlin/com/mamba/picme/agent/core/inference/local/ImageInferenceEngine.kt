package com.mamba.picme.agent.core.inference.local

/**
 * 端侧 VLM 图像推理引擎抽象（TAG 打标 / 图像理解）。Android actual = LocalLlmEngine(MNN JNI)；iOS actual 属 Phase 6.1。
 *
 * 方法签名与 Android 实现 `LocalLlmEngine`（`inference.local.llm`）公开 API 逐项对应，
 * 仅将 Bitmap 入参统一改为 [ByteArray]（编码后的图片字节，如 JPEG/PNG）；
 * ByteArray → Bitmap 的解码在 Android actual 内完成。
 *
 * TODO(Task 9+): 错误返回统一为 sealed class 或 Result，消除 `__ERROR_` 魔法前缀字符串契约（旧实现遗留编码风格，iOS actual 暂需遵守）。
 */
interface ImageInferenceEngine {

    /** 模型是否已加载 */
    val isLoaded: Boolean

    /**
     * 检查指定模型是否已下载可用（本地文件存在性检查，不加载模型）。
     *
     * @param modelId 模型注册表中的 key，如 "qwen3_vl_2b"
     */
    fun isModelAvailable(modelId: String): Boolean

    /**
     * 加载指定模型
     *
     * @param modelId 模型注册表中的 key，如 "qwen3_vl_2b"
     * @param useOpencl 是否使用 OpenCL GPU 后端
     * @return 加载结果，模型未下载时返回 [com.mamba.picme.agent.core.inference.local.llm.LlmModelNotFoundException] 失败
     */
    suspend fun loadModel(modelId: String, useOpencl: Boolean = false): Result<Unit>

    /**
     * 卸载当前模型，释放内存（异步投递，立即返回；语义同 `LocalLlmEngine.unload`）
     */
    fun unload()

    /**
     * 使用端侧多模态模型对图片进行推理
     *
     * @param imageBytes   编码后的图片字节（JPEG/PNG 等 `BitmapFactory` 可解码格式）
     * @param systemPrompt 系统提示词（定义任务，如 "简短描述图片内容"）
     * @param userPrompt   用户提示词（具体问题）
     * @param maxTokens    最大生成 token 数，默认 256
     * @return 模型生成的文本回复；所有错误路径均返回空字符串，不产生 `__ERROR_` 前缀
     */
    suspend fun imageInference(
        imageBytes: ByteArray,
        systemPrompt: String,
        userPrompt: String = "请描述这张图片",
        maxTokens: Int = 256
    ): String

    /**
     * 带超时的多模态图片推理
     *
     * @param timeoutMs 最大等待时间（毫秒），默认 30 秒
     * @return 模型生成的文本回复；native 层推理错误返回 `__ERROR_{error}__` 格式字符串；超时或 JVM 异常返回空字符串
     */
    suspend fun imageInferenceWithTimeout(
        imageBytes: ByteArray,
        systemPrompt: String,
        userPrompt: String = "请描述这张图片",
        maxTokens: Int = 128,
        timeoutMs: Int = 30_000
    ): String
}
