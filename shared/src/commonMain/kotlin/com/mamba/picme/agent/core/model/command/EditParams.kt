package com.mamba.picme.agent.core.model.command

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

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
         * 从 JSON 字符串构建 [EditParams]（供远程 tool 路径使用，解析规则与原本地链路一致）。
         *
         * 每个字段支持三种形式：`"key": 数值` → [Absolute]；`"key": "字符串"` → [AbsoluteString]；
         * `"key_delta": 数值` → [Delta]；不存在 → [Unchanged]。key 为 snake_case。
         *
         * 容错语义与原 `org.json.opt*` 实现对齐：非数值/非字符串值（含 null、bool、嵌套对象）
         * 一律落到 `_delta` 检查再落 [Unchanged]；字符串形式的数字在 `_delta`/`filter_intensity`
         * 字段同样按数值解析（对齐 `optDouble` 的字符串强转）。
         */
        fun fromJson(jsonString: String): EditParams {
            val obj = Json.parseToJsonElement(jsonString).jsonObject

            /** 对齐 org.json.optDouble：数值直取，字符串尝试强转，其余（null/bool/对象）→ null */
            fun optDouble(key: String): Double? {
                val raw = obj[key] as? JsonPrimitive ?: return null
                return if (raw.isString) raw.content.toDoubleOrNull() else raw.doubleOrNull
            }

            fun value(key: String): Value {
                val raw = obj[key] as? JsonPrimitive
                if (raw != null) {
                    if (raw.isString) {
                        if (raw.content.isNotBlank()) return AbsoluteString(raw.content)
                    } else {
                        raw.doubleOrNull?.let { return Absolute(it.toFloat()) }
                    }
                }
                optDouble("${key}_delta")?.let { return Delta(it.toFloat()) }
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
                filterIntensity = optDouble("filter_intensity")?.toFloat(),
                styleName = value("style_name")
            )
        }
    }
}
