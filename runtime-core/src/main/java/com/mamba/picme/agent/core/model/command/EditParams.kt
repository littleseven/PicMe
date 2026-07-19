package com.mamba.picme.agent.core.model.command

/**
 * LLM 结构化编辑意图。
 *
 * 每个字段可以是：
 * - [Unchanged]：不修改
 * - [Absolute]：设置为绝对数值
 * - [AbsoluteString]：设置为绝对字符串（如滤镜名）
 * - [Delta]：在当前值基础上增减
 */
data class EditParams(
    val smoothing: Value = Unchanged,
    val whitening: Value = Unchanged,
    val slimFace: Value = Unchanged,
    val bigEyes: Value = Unchanged,
    val lipColor: Value = Unchanged,
    val blush: Value = Unchanged,
    val eyebrow: Value = Unchanged,
    val brightness: Value = Unchanged,
    val exposure: Value = Unchanged,
    val contrast: Value = Unchanged,
    val saturation: Value = Unchanged,
    val temperature: Value = Unchanged,
    val tint: Value = Unchanged,
    val filterName: Value = Unchanged,
    val filterIntensity: Float? = null,
    val styleName: Value = Unchanged
) {
    sealed interface Value
    data object Unchanged : Value
    data class Absolute(val value: Float) : Value
    data class AbsoluteString(val value: String) : Value
    data class Delta(val value: Float) : Value
}
