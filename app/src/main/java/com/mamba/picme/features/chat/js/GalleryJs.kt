package com.mamba.picme.features.chat.js

import com.mamba.picme.agent.core.js.JsValue
import com.mamba.picme.data.model.MediaEntity
import com.mamba.picme.domain.model.GalleryQueryResult
import com.mamba.picme.domain.model.QueryFilter
import com.mamba.picme.domain.usecase.QueryGalleryMediaUseCase
import org.json.JSONArray

/**
 * JS ↔ 只读查询模型的双向转换（app 层）。
 *
 * 落在 app 层的原因：依赖 [MediaEntity]（app/data 层），runtime-core 不可见
 * （对照 GallerySummary.toResultJsValue() 能放 runtime-core，因 GallerySummary 本就在 runtime-core）。
 *
 * - [parseQueryFilter]：JS `bridge.call('gallery.query', {...})` 的第二参 → [QueryFilter]。
 * - [toResultJsValue]：结果/元数据 → JsValue（回传 JS）。
 * 字段名小驼峰；数值转 Double（JS number）。
 */

/** JS 传入的 filter 对象 → QueryFilter（全可选，缺省/空串/类型不符一律走默认）。 */
fun parseQueryFilter(args: JsValue): QueryFilter {
    val obj = args as? JsValue.Obj ?: return QueryFilter()
    val e = obj.entries
    fun str(k: String) = (e[k] as? JsValue.Str)?.value?.takeIf { it.isNotBlank() }
    fun num(k: String) = (e[k] as? JsValue.Num)?.value?.toLong()
    fun bool(k: String) = (e[k] as? JsValue.Bool)?.value
    val limit = (e["limit"] as? JsValue.Num)?.value?.toInt()
    return QueryFilter(
        label = str("label"),
        ocr = str("ocr"),
        location = str("location"),
        fromMs = num("fromMs"),
        toMs = num("toMs"),
        hasFace = bool("hasFace"),
        limit = limit ?: QueryFilter.DEFAULT_LIMIT,
    )
}

/** GalleryQueryResult → `{ids:[...], total:N}`。 */
fun GalleryQueryResult.toResultJsValue(): JsValue.Obj = JsValue.Obj(
    linkedMapOf(
        "ids" to JsValue.Arr(ids.map { JsValue.Num(it.toDouble()) }),
        "total" to JsValue.Num(total.toDouble()),
    )
)

/**
 * MediaEntity → media.meta 白名单元数据。
 * **不回**：uri / latitude / longitude / ocrText / 任何 embedding/ROI（隐私红线）。
 */
fun MediaEntity.toMetaJsValue(): JsValue.Obj = JsValue.Obj(
    linkedMapOf(
        "id" to JsValue.Num(id.toDouble()),
        "type" to JsValue.Str(type.name),
        "captureMs" to JsValue.Num(captureDate.toDouble()),
        "fileName" to JsValue.Str(fileName),
        "labels" to (labels?.let { parseStringArray(it) } ?: JsValue.Null),
        "locationName" to (locationName?.let { JsValue.Str(it) } ?: JsValue.Null),
        "hasFace" to JsValue.Bool(hasFace),
        "faceId" to (faceId?.let { JsValue.Str(it) } ?: JsValue.Null),
    )
)

/** Map<标签, 照片数> → gallery.tags 结果（标签→计数，调用前应已按计数降序）。 */
fun Map<String, Int>.toTagsJsValue(): JsValue.Obj = JsValue.Obj(
    linkedMapOf(*entries.map { it.key to JsValue.Num(it.value.toDouble()) }.toTypedArray())
)

/** Map<桶起始时间戳, 照片数> → gallery.timeline 结果（时间升序）。 */
fun Map<Long, Int>.toTimelineJsValue(): JsValue.Obj = JsValue.Obj(
    linkedMapOf(
        *entries.map { (bucketMs, count) ->
            bucketMs.toString() to JsValue.Num(count.toDouble())
        }.toTypedArray()
    )
)

// ── gallery.intersect：集合交并差 ────────────────────────────────

/** JS 传入的集合运算请求。 */
data class IntersectRequest(
    val idsA: List<Long>,
    val idsB: List<Long>,
    val op: String, // intersect / union / diff
)

/** JS `{idsA:[...], idsB:[...], op:"intersect"}` → IntersectRequest。 */
fun parseIntersectArgs(args: JsValue): IntersectRequest {
    val obj = args as? JsValue.Obj ?: return IntersectRequest(emptyList(), emptyList(), "intersect")
    val e = obj.entries
    val idsA = parseJsIdList(e["idsA"])
    val idsB = parseJsIdList(e["idsB"])
    val op = (e["op"] as? JsValue.Str)?.value?.lowercase() ?: "intersect"
    return IntersectRequest(idsA, idsB, op)
}

/** JsValue → Long 列表（JS array `[1,2,3]`）。 */
private fun parseJsIdList(v: JsValue?): List<Long> = when (v) {
    is JsValue.Arr -> v.items.mapNotNull { (it as? JsValue.Num)?.value?.toLong() }
    else -> emptyList()
}

/** 集合运算结果 → `{ids:[...], total:N}`。 */
fun intersectResult(ids: List<Long>): JsValue.Obj = JsValue.Obj(
    linkedMapOf(
        "ids" to JsValue.Arr(ids.map { JsValue.Num(it.toDouble()) }),
        "total" to JsValue.Num(ids.size.toDouble()),
    )
)

/** 执行集合运算，返回结果 id 列表。 */
fun computeIntersect(req: IntersectRequest): List<Long> {
    val setA = LinkedHashSet(req.idsA)
    val setB = LinkedHashSet(req.idsB)
    return when (req.op) {
        "intersect" -> setA.filter { it in setB }
        "union" -> (setA + setB).toList()
        "diff" -> setA.filter { it !in setB }
        else -> setA.filter { it in setB }
    }
}

// ── media.batch_meta：批量元数据 ─────────────────────────────────

/** List<MediaEntity> → media.batch_meta 结果（JsValue.Arr）。 */
fun List<MediaEntity>.toBatchMetaJsValue(): JsValue.Arr =
    JsValue.Arr(map { it.toMetaJsValue() })

/** 解析 MediaEntity.labels 的 JSON 数组字符串（存储格式 `["猫","户外"]`）→ JsValue.Arr；空/异常 → 空数组。 */
private fun parseStringArray(raw: String?): JsValue {
    if (raw.isNullOrBlank()) return JsValue.Arr(emptyList())
    return runCatching {
        val arr = JSONArray(raw)
        JsValue.Arr((0 until arr.length()).map { JsValue.Str(arr.getString(it)) })
    }.getOrDefault(JsValue.Arr(emptyList()))
}

// ── gallery.timeline：参数解析 ────────────────────────────────────

/** JS `{fromMs?, toMs?, bucketMs?}` → 三元组（fromMs, toMs, bucketMs）。 */
fun parseTimelineArgs(args: JsValue): Triple<Long?, Long?, Long> {
    val obj = args as? JsValue.Obj ?: return Triple(null, null, QueryGalleryMediaUseCase.BUCKET_MONTH_MS)
    val e = obj.entries
    val fromMs = (e["fromMs"] as? JsValue.Num)?.value?.toLong()
    val toMs = (e["toMs"] as? JsValue.Num)?.value?.toLong()
    val bucketMs = (e["bucketMs"] as? JsValue.Num)?.value?.toLong()
        ?: QueryGalleryMediaUseCase.BUCKET_MONTH_MS
    return Triple(fromMs, toMs, bucketMs)
}
