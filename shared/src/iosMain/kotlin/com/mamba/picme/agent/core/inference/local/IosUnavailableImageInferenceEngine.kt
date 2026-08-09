package com.mamba.picme.agent.core.inference.local

/**
 * iOS 端侧 VLM 缺位 stub（Phase 6.2 T1）：6.1 端侧推理未落地前，
 * AgentConfigurator 构造期 eager 调用 `isLoaded`，组合根不可省此注入。
 *
 * 契约遵守：所有错误路径返回空字符串，不产生 `__ERROR_` 前缀；
 * `isModelAvailable=false` / `loadModel` 恒失败（Result.failure），
 * 调用方（修图/打标工具）在 iOS v1 不注册，本 stub 只为满足编排层装配。
 */
class IosUnavailableImageInferenceEngine : ImageInferenceEngine {
    override val isLoaded: Boolean get() = false
    override fun isModelAvailable(modelId: String): Boolean = false
    override suspend fun loadModel(modelId: String, useOpencl: Boolean): Result<Unit> =
        Result.failure(IllegalStateException("On-device VLM is not available on iOS yet (Phase 6.1)"))
    override fun unload() {}
    override suspend fun imageInference(
        imageBytes: ByteArray,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
    ): String = ""
    override suspend fun imageInferenceWithTimeout(
        imageBytes: ByteArray,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        timeoutMs: Int,
    ): String = ""
}
