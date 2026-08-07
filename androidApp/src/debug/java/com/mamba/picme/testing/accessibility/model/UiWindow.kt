package com.mamba.picme.testing.accessibility.model

import org.json.JSONObject

data class UiWindow(
    val title: String?,
    val width: Int,
    val height: Int,
    val timestampMs: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        putOpt("title", title)
        put("width", width)
        put("height", height)
        put("timestampMs", timestampMs)
    }
}
