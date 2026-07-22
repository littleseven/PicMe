package com.mamba.picme.agent.core.js

import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.Undefined

/**
 * JsValue ↔ Rhino 原生对象互转。
 *
 * - 只产出 JS 原生类型（NativeObject/NativeArray/原始值），不泄漏任意 Java 对象，
 *   配合 [RhinoJsEngine] 的 deny-all ClassShutter 保证沙箱。
 * - [toJsValue] 把 JS 侧传来的任意 Rhino 值归一化为 JsValue。
 */
object RhinoConverter {

    /** Rhino 运行时值 → JsValue。 */
    fun toJsValue(o: Any?): JsValue = when {
        o == null || o is Undefined -> JsValue.Null
        o is Boolean -> JsValue.Bool(o)
        o is Number -> JsValue.Num(o.toDouble())
        o is String -> JsValue.Str(o)
        o is NativeArray -> JsValue.Arr(o.toList().map { toJsValue(it) })
        o is NativeObject -> {
            val map = linkedMapOf<String, JsValue>()
            for (id in o.ids) {
                val key = id.toString()
                map[key] = toJsValue(o.get(key, o))
            }
            JsValue.Obj(map)
        }
        o is Scriptable -> {
            val map = linkedMapOf<String, JsValue>()
            for (id in o.ids) {
                val key = id.toString()
                map[key] = toJsValue(o.get(key, o))
            }
            JsValue.Obj(map)
        }
        else -> JsValue.Str(o.toString())
    }

    /** JsValue → Rhino 原生值（需传入 scope 以构建 NativeObject/NativeArray）。 */
    fun toRhino(v: JsValue, scope: Scriptable): Any? = when (v) {
        JsValue.Null -> null
        is JsValue.Bool -> v.value
        is JsValue.Num -> v.value
        is JsValue.Str -> v.value
        is JsValue.Obj -> NativeObject().apply {
            v.entries.forEach { (k, vv) -> put(k, this, toRhino(vv, scope)) }
        }
        is JsValue.Arr -> NativeArray(v.items.map { toRhino(it, scope) }.toTypedArray())
    }
}
