package com.mamba.picme.agent.core.js

import com.mamba.picme.agent.core.model.context.GallerySummary

/**
 * [GallerySummary] → [JsValue.Obj] 映射，供 JS `gallery.summary` handler 返回给脚本。
 * 字段名小驼峰（JS 习惯）；数值统一转 Double（JS number）。
 */
fun GallerySummary.toJsValue(): JsValue.Obj = JsValue.Obj(
    linkedMapOf(
        "totalPhotos" to JsValue.Num(totalPhotos.toDouble()),
        "totalVideos" to JsValue.Num(totalVideos.toDouble()),
        "totalMedia" to JsValue.Num(totalMedia.toDouble()),
        "hasFaceCount" to JsValue.Num(hasFaceCount.toDouble()),
        "personClusterCount" to JsValue.Num(personClusterCount.toDouble()),
        "namedPersonCount" to JsValue.Num(namedPersonCount.toDouble()),
        "labeledCount" to JsValue.Num(labeledCount.toDouble()),
        "unlabeledCount" to JsValue.Num(unlabeledCount.toDouble()),
        "semanticEncodedCount" to JsValue.Num(semanticEncodedCount.toDouble()),
        "remainingPass1" to JsValue.Num(remainingPass1.toDouble()),
        "remainingPass3" to JsValue.Num(remainingPass3.toDouble()),
        "isScanning" to JsValue.Bool(isScanning),
        "currentPass" to (currentPass?.let { JsValue.Str(it) } ?: JsValue.Null),
        "recommendation" to JsValue.Str(recommendation.name),
    )
)
