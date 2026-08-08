package com.mamba.picme.agent.core.inference.local.llm

/**
 * LLM 模型未找到异常
 *
 * 用于区分"模型未下载"和"其他加载错误"，便于 UI 层引导用户下载。
 *
 * （Phase 4 Task 9：自 `LocalLlmEngine.kt` 拆出迁 commonMain——commonMain 的
 * `LocalModelService.ensureModelLoaded` 需要引用；包名/FQN 不变，调用方无需改 import。）
 */
class LlmModelNotFoundException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
