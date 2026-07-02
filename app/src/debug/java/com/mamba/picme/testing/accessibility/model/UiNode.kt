package com.mamba.picme.testing.accessibility.model

import org.json.JSONArray
import org.json.JSONObject

data class UiNode(
    val id: String,
    val packageName: String?,
    val className: String?,
    val text: String?,
    val contentDescription: String?,
    val hint: String?,
    val bounds: Bounds,
    val clickable: Boolean,
    val longClickable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
    val checked: Boolean,
    val selected: Boolean,
    val focused: Boolean,
    val children: List<UiNode> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        putOpt("packageName", packageName)
        putOpt("className", className)
        putOpt("text", text)
        putOpt("contentDescription", contentDescription)
        putOpt("hint", hint)
        put("bounds", bounds.toJson())
        put("clickable", clickable)
        put("longClickable", longClickable)
        put("scrollable", scrollable)
        put("enabled", enabled)
        put("checked", checked)
        put("selected", selected)
        put("focused", focused)
        put("children", JSONArray(children.map { it.toJson() }))
    }
}

data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("left", left)
        put("top", top)
        put("right", right)
        put("bottom", bottom)
    }

    fun center(): Pair<Int, Int> = ((left + right) / 2) to ((top + bottom) / 2)
}
