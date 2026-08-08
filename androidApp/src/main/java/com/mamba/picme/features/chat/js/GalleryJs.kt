package com.mamba.picme.features.chat.js

import com.mamba.picme.agent.core.js.JsValue
import com.mamba.picme.data.local.entity.PersonEntity
import com.mamba.picme.data.model.MediaEntity
import com.mamba.picme.domain.model.GalleryQueryResult
import com.mamba.picme.domain.model.QueryFilter
import com.mamba.picme.domain.tag.scan.ScanSessionState
import com.mamba.picme.domain.tag.scan.TagScanSessionProgress
import com.mamba.picme.domain.usecase.QueryGalleryMediaUseCase
import org.json.JSONArray

/**
 * JS ↔ 只读查询模型的双向转换（app 层）。
 *
 * 落在 app 层的原因：依赖 [MediaEntity]（app/data 层），:shared 不可见
 * （对照 GallerySummary.toResultJsValue() 能放 :shared，因 GallerySummary 本就在 :shared）。
 *
 * - [parseQueryFilter]：JS `bridge.callAsync('gallery.query', {...})` 的第二参 → [QueryFilter]。
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
        person = str("person"),
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
 * 评分字段（aestheticScore/faceQualityScore）为纯数值、不含媒体内容，可回。
 */
fun MediaEntity.toMetaJsValue(): JsValue.Obj = JsValue.Obj(
    linkedMapOf(
        "id" to JsValue.Num(id.toDouble()),
        "type" to JsValue.Str(type.name),
        "captureMs" to JsValue.Num(captureDate.toDouble()),
        "fileName" to JsValue.Str(fileName),
        "labels" to (labels?.let { parseStringArray(it) } ?: JsValue.Null),
        "locationName" to (locationName?.let { JsValue.Str(it) } ?: JsValue.Null),
        "city" to (city?.let { JsValue.Str(it) } ?: JsValue.Null),
        "hasFace" to JsValue.Bool(hasFace),
        "faceId" to (faceId?.let { JsValue.Str(it) } ?: JsValue.Null),
        "aestheticScore" to (aestheticScore?.let { JsValue.Num(it.toDouble()) } ?: JsValue.Null),
        "faceQualityScore" to (faceQualityScore?.let { JsValue.Num(it.toDouble()) } ?: JsValue.Null),
    )
)

/** Map<标签, 照片数> → gallery.tags 结果（标签→计数，调用前应已按计数降序）。 */
fun Map<String, Int>.toTagsJsValue(): JsValue.Obj =
    JsValue.Obj(entries.associate { it.key to JsValue.Num(it.value.toDouble()) })

/** Map<桶起始时间戳, 照片数> → gallery.timeline 结果（时间升序）。 */
fun Map<Long, Int>.toTimelineJsValue(): JsValue.Obj =
    JsValue.Obj(entries.associate { (bucketMs, count) ->
        bucketMs.toString() to JsValue.Num(count.toDouble())
    })

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
private fun parseJsIdList(v: JsValue?): List<Long> =
    if (v is JsValue.Arr) v.items.mapNotNull { (it as? JsValue.Num)?.value?.toLong() }
    else emptyList()

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

// ── face.cluster / tag.audit ──────────────────────────────────────

/**
 * PersonEntity → face.cluster 的 topPersons 元素。
 * **不回** embedding 原始数据（隐私红线）；coverMediaId 仅作封面引用 id。
 */
fun PersonEntity.toPersonJsValue(): JsValue.Obj = JsValue.Obj(
    linkedMapOf(
        "personId" to JsValue.Num(personId.toDouble()),
        "name" to (name?.takeIf { it.isNotBlank() }?.let { JsValue.Str(it) } ?: JsValue.Null),
        "faceCount" to JsValue.Num(faceCount.toDouble()),
        "coverMediaId" to (coverMediaId?.let { JsValue.Num(it.toDouble()) } ?: JsValue.Null),
    )
)

/**
 * 词表外标签过滤：从 [tagDistribution]（标签→照片数）中挑出不在 [vocabTags] 里的标签，
 * 按计数降序取 top [limit]。供 tag.audit 的 outOfVocabTags。
 */
fun outOfVocabTags(
    tagDistribution: Map<String, Int>,
    vocabTags: Collection<String>,
    limit: Int,
): Map<String, Int> {
    val vocab = vocabTags.toSet()
    return tagDistribution.entries
        .filter { it.key !in vocab }
        .sortedByDescending { it.value }
        .take(limit)
        .associate { it.key to it.value }
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

// ── tag.scan_status：扫描会话状态快照 ──────────────────────────────

/** 会话进行中（含暂停/取消中）的状态集合；IDLE/CANCELLED/COMPLETED 视为非活跃。 */
private val ACTIVE_SCAN_STATES = setOf(
    ScanSessionState.RUNNING,
    ScanSessionState.PAUSING,
    ScanSessionState.PAUSED,
    ScanSessionState.CANCELLING,
)

/**
 * TagScanSessionProgress? → tag.scan_status 结果。
 * 无会话（null）时仅回 `{active:false, state:null}`；不回 messages 明细（量大，UI 专用）。
 */
fun TagScanSessionProgress?.toScanStatusJsValue(): JsValue.Obj {
    if (this == null) {
        return JsValue.Obj(
            linkedMapOf(
                "active" to JsValue.Bool(false),
                "state" to JsValue.Null,
            )
        )
    }
    return JsValue.Obj(
        linkedMapOf(
            "active" to JsValue.Bool(state in ACTIVE_SCAN_STATES),
            "state" to JsValue.Str(state.name),
            "sessionId" to JsValue.Str(sessionId),
            "currentPass" to (currentPass?.let { JsValue.Str(it.name) } ?: JsValue.Null),
            "processed" to JsValue.Num(processed.toDouble()),
            "total" to JsValue.Num(total.toDouble()),
            "pending" to JsValue.Num(pending.toDouble()),
            "failed" to JsValue.Num(failed.toDouble()),
            "estimatedRemainingMs" to (estimatedRemainingMs?.let { JsValue.Num(it.toDouble()) } ?: JsValue.Null),
        )
    )
}
