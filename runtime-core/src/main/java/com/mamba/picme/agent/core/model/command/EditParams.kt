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

    companion object {
        /**
         * 从 JSON 对象构建 [EditParams]（供远程 tool 路径使用，与 LocalCommandParser 的解析规则一致）。
         *
         * 每个字段支持三种形式：`"key": 数值` → [Absolute]；`"key": "字符串"` → [AbsoluteString]；
         * `"key_delta": 数值` → [Delta]；不存在 → [Unchanged]。key 为 snake_case。
         */
        fun fromJson(obj: org.json.JSONObject): EditParams {
            fun value(key: String): Value {
                val raw = obj.opt(key)
                when (raw) {
                    is Number -> return Absolute(raw.toFloat())
                    is String -> if (raw.isNotBlank()) return AbsoluteString(raw)
                }
                val delta = obj.optDouble("${key}_delta", Double.NaN)
                if (!delta.isNaN()) return Delta(delta.toFloat())
                return Unchanged
            }
            return EditParams(
                smoothing = value("smoothing"),
                whitening = value("whitening"),
                slimFace = value("slim_face"),
                bigEyes = value("big_eyes"),
                lipColor = value("lip_color"),
                blush = value("blush"),
                eyebrow = value("eyebrow"),
                brightness = value("brightness"),
                exposure = value("exposure"),
                contrast = value("contrast"),
                saturation = value("saturation"),
                temperature = value("temperature"),
                tint = value("tint"),
                filterName = value("filter_name"),
                filterIntensity = obj.optDouble("filter_intensity", Double.NaN)
                    .takeUnless { it.isNaN() }?.toFloat(),
                styleName = value("style_name")
            )
        }
    }
}
