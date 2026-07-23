package com.mamba.picme.agent.core.js

/**
 * JS 值的引擎无关投影。bridge 与 handler 之间只交换 JsValue，
 * 避免泄漏 Rhino/QuickJS 等引擎特定类型。
 */
sealed class JsValue {
    data object Null : JsValue()
    data class Bool(val value: Boolean) : JsValue()
    data class Num(val value: Double) : JsValue()
    data class Str(val value: String) : JsValue()
    data class Obj(val entries: Map<String, JsValue>) : JsValue()
    data class Arr(val items: List<JsValue>) : JsValue()

    /** 调试用：递归转 JSON 片段（非完整 JSON 转义实现，仅用于日志/展示）。 */
    fun toJson(): String = when (this) {
        Null -> "null"
        is Bool -> value.toString()
        is Num -> value.toString()
        is Str -> "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        is Obj -> entries.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (k, v) ->
            "\"$k\":${v.toJson()}"
        }
        is Arr -> items.joinToString(prefix = "[", postfix = "]", separator = ",") { it.toJson() }
    }
}
