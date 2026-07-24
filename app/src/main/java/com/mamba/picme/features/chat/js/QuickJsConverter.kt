package com.mamba.picme.features.chat.js

import com.dokar.quickjs.binding.JsObject
import com.mamba.picme.agent.core.js.JsValue

/**
 * dokar3/quickjs-kt（com.dokar.quickjs.*）↔ JsValue 双向转换。
 *
 * dokar3 类型映射：JS object → [JsObject]（实现 Map），JS Array → List，原始 → Kotlin 原始。
 * bridge 与 handler 只交换 JsValue（引擎无关）。
 */
object QuickJsConverter {

    /** dokar3 evaluate/binding 结果（JsObject/List/原始）→ JsValue。 */
    fun toJsValue(o: Any?): JsValue = when (o) {
        null -> JsValue.Null
        is Boolean -> JsValue.Bool(o)
        is Number -> JsValue.Num(o.toDouble())
        is String -> JsValue.Str(o)
        is List<*> -> JsValue.Arr(o.map { toJsValue(it) })
        is JsObject -> JsValue.Obj(
            linkedMapOf(*o.entries.map { it.key to toJsValue(it.value) }.toTypedArray())
        )
        is Map<*, *> -> JsValue.Obj(
            linkedMapOf(*o.entries.map { it.key.toString() to toJsValue(it.value) }.toTypedArray())
        )
        else -> JsValue.Str(o.toString())
    }

    /** JsValue → dokar3 可返回值（List→JS Array，JsObject→JS object，原始）。 */
    fun toQuickJS(v: JsValue): Any? = when (v) {
        JsValue.Null -> null
        is JsValue.Bool -> v.value
        is JsValue.Num -> v.value
        is JsValue.Str -> v.value
        is JsValue.Arr -> v.items.map { toQuickJS(it) }
        is JsValue.Obj -> JsObject(
            linkedMapOf(*v.entries.entries.map { it.key to toQuickJS(it.value) }.toTypedArray())
        )
    }
}
