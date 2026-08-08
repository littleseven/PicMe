package com.mamba.picme.agent.core.inference.local.llm

/**
 * 本地 LLM 单次生成的性能指标
 *
 * @property promptLen prompt token 数
 * @property decodeLen 生成的 token 数
 * @property prefillTime prefill 阶段耗时（微秒）
 * @property decodeTime decode 阶段耗时（微秒）
 * @property prefillSpeed prefill 速度（tokens/秒）
 * @property decodeSpeed decode 速度（tokens/秒）
 */
data class LlmGenerationMetrics(
    val promptLen: Long = 0,
    val decodeLen: Long = 0,
    val prefillTime: Long = 0,
    val decodeTime: Long = 0,
    val prefillSpeed: Float = 0f,
    val decodeSpeed: Float = 0f
)
