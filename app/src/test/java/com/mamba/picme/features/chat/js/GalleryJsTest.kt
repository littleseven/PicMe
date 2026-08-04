package com.mamba.picme.features.chat.js

import com.mamba.picme.agent.core.js.JsValue
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.data.local.entity.PersonEntity
import com.mamba.picme.data.local.entity.TagScanPass
import com.mamba.picme.data.model.MediaEntity
import com.mamba.picme.domain.model.GalleryQueryResult
import com.mamba.picme.domain.tag.scan.ScanSessionState
import com.mamba.picme.domain.tag.scan.TagScanSessionProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GalleryJs 转换函数单元测试（纯 JVM，无 Android 依赖）。
 */
class GalleryJsTest {

    // ── parseIntersectArgs ──────────────────────────────────────────

    @Test
    fun `parseIntersectArgs parses intersect operation`() {
        val args = JsValue.Obj(
            linkedMapOf(
                "idsA" to JsValue.Arr(listOf(JsValue.Num(1.0), JsValue.Num(2.0), JsValue.Num(3.0))),
                "idsB" to JsValue.Arr(listOf(JsValue.Num(2.0), JsValue.Num(3.0), JsValue.Num(4.0))),
                "op" to JsValue.Str("intersect"),
            )
        )
        val req = parseIntersectArgs(args)
        assertEquals(listOf(1L, 2L, 3L), req.idsA)
        assertEquals(listOf(2L, 3L, 4L), req.idsB)
        assertEquals("intersect", req.op)
    }

    @Test
    fun `parseIntersectArgs defaults to intersect when op missing`() {
        val args = JsValue.Obj(
            linkedMapOf(
                "idsA" to JsValue.Arr(listOf(JsValue.Num(1.0))),
                "idsB" to JsValue.Arr(listOf(JsValue.Num(1.0))),
            )
        )
        val req = parseIntersectArgs(args)
        assertEquals("intersect", req.op)
    }

    @Test
    fun `parseIntersectArgs handles null args`() {
        val req = parseIntersectArgs(JsValue.Null)
        assertTrue(req.idsA.isEmpty())
        assertTrue(req.idsB.isEmpty())
    }

    // ── computeIntersect ────────────────────────────────────────────

    @Test
    fun `computeIntersect intersect returns common ids`() {
        val req = IntersectRequest(listOf(1L, 2L, 3L), listOf(2L, 3L, 4L), "intersect")
        assertEquals(listOf(2L, 3L), computeIntersect(req))
    }

    @Test
    fun `computeIntersect union returns all unique ids`() {
        val req = IntersectRequest(listOf(1L, 2L), listOf(2L, 3L), "union")
        assertEquals(listOf(1L, 2L, 3L), computeIntersect(req))
    }

    @Test
    fun `computeIntersect diff returns ids in A not in B`() {
        val req = IntersectRequest(listOf(1L, 2L, 3L), listOf(2L), "diff")
        assertEquals(listOf(1L, 3L), computeIntersect(req))
    }

    @Test
    fun `computeIntersect unknown op defaults to intersect`() {
        val req = IntersectRequest(listOf(1L, 2L), listOf(2L, 3L), "unknown")
        assertEquals(listOf(2L), computeIntersect(req))
    }

    // ── intersectResult ─────────────────────────────────────────────

    @Test
    fun `intersectResult produces correct JsValue`() {
        val result = intersectResult(listOf(1L, 2L))
        assertTrue(result is JsValue.Obj)
        val entries = (result as JsValue.Obj).entries
        val ids = entries["ids"] as JsValue.Arr
        assertEquals(2, ids.items.size)
        assertEquals(1.0, (ids.items[0] as JsValue.Num).value, 0.001)
        assertEquals(2.0, (entries["total"] as JsValue.Num).value, 0.001)
    }

    // ── toTimelineJsValue ───────────────────────────────────────────

    @Test
    fun `toTimelineJsValue converts map to JsValue Obj`() {
        val timeline = linkedMapOf<Long, Int>(
            1704067200000L to 15,
            1706745600000L to 23,
        )
        val result = timeline.toTimelineJsValue()
        assertTrue(result is JsValue.Obj)
        val entries = (result as JsValue.Obj).entries
        assertEquals(2, entries.size)
        assertEquals(15.0, (entries["1704067200000"] as JsValue.Num).value, 0.001)
        assertEquals(23.0, (entries["1706745600000"] as JsValue.Num).value, 0.001)
    }

    @Test
    fun `toTimelineJsValue handles empty map`() {
        val result = emptyMap<Long, Int>().toTimelineJsValue()
        assertTrue(result is JsValue.Obj)
        assertTrue((result as JsValue.Obj).entries.isEmpty())
    }

    // ── parseTimelineArgs ───────────────────────────────────────────

    @Test
    fun `parseTimelineArgs parses all params`() {
        val args = JsValue.Obj(
            linkedMapOf(
                "fromMs" to JsValue.Num(1704067200000.0),
                "toMs" to JsValue.Num(1735689600000.0),
                "bucketMs" to JsValue.Num(2592000000.0),
            )
        )
        val (fromMs, toMs, bucketMs) = parseTimelineArgs(args)
        assertEquals(1704067200000L, fromMs)
        assertEquals(1735689600000L, toMs)
        assertEquals(2592000000L, bucketMs)
    }

    @Test
    fun `parseTimelineArgs defaults when params missing`() {
        val (fromMs, toMs, bucketMs) = parseTimelineArgs(JsValue.Null)
        assertEquals(null, fromMs)
        assertEquals(null, toMs)
        // Default is BUCKET_MONTH_MS
        assertTrue(bucketMs > 0)
    }

    // ── toBatchMetaJsValue ──────────────────────────────────────────

    @Test
    fun `toBatchMetaJsValue converts media list to JsValue Arr`() {
        val entities = listOf(
            MediaEntity(
                id = 1,
                uri = "content://test/1",
                type = MediaType.PHOTO,
                captureDate = 1704067200000L,
                fileName = "photo1.jpg",
                labels = """["户外","猫"]""",
                hasFace = true,
            ),
            MediaEntity(
                id = 2,
                uri = "content://test/2",
                type = MediaType.VIDEO,
                captureDate = 1704153600000L,
                fileName = "video1.mp4",
                labels = null,
                hasFace = false,
            ),
        )
        val result = entities.toBatchMetaJsValue()
        assertTrue(result is JsValue.Arr)
        assertEquals(2, (result as JsValue.Arr).items.size)

        val first = result.items[0] as JsValue.Obj
        assertEquals(1.0, (first.entries["id"] as JsValue.Num).value, 0.001)
        assertEquals("photo1.jpg", (first.entries["fileName"] as JsValue.Str).value)
    }

    @Test
    fun `toBatchMetaJsValue handles empty list`() {
        val result = emptyList<MediaEntity>().toBatchMetaJsValue()
        assertTrue(result is JsValue.Arr)
        assertTrue((result as JsValue.Arr).items.isEmpty())
    }

    // ── parseQueryFilter ────────────────────────────────────────────

    @Test
    fun `parseQueryFilter parses all fields`() {
        val args = JsValue.Obj(
            linkedMapOf(
                "label" to JsValue.Str("户外"),
                "ocr" to JsValue.Str("菜单"),
                "location" to JsValue.Str("北京"),
                "fromMs" to JsValue.Num(1704067200000.0),
                "toMs" to JsValue.Num(1735689600000.0),
                "hasFace" to JsValue.Bool(true),
                "limit" to JsValue.Num(50.0),
            )
        )
        val filter = parseQueryFilter(args)
        assertEquals("户外", filter.label)
        assertEquals("菜单", filter.ocr)
        assertEquals("北京", filter.location)
        assertEquals(1704067200000L, filter.fromMs)
        assertEquals(1735689600000L, filter.toMs)
        assertEquals(true, filter.hasFace)
        assertEquals(50, filter.limit)
    }

    @Test
    fun `parseQueryFilter defaults on null args`() {
        val filter = parseQueryFilter(JsValue.Null)
        assertEquals(null, filter.label)
        assertEquals(null, filter.fromMs)
        assertEquals(200, filter.limit) // DEFAULT_LIMIT
    }

    // ── toResultJsValue (from old tests) ────────────────────────────

    @Test
    fun `GalleryQueryResult toJsValue shape`() {
        val v = GalleryQueryResult(ids = listOf(1L, 2L), total = 2).toResultJsValue()
        val obj = (v as JsValue.Obj).entries
        assertEquals(
            listOf(1.0, 2.0),
            (obj["ids"] as JsValue.Arr).items.map { (it as JsValue.Num).value },
        )
        assertEquals(2.0, (obj["total"] as JsValue.Num).value, 0.0)
    }

    // ── toMetaJsValue (from old tests) ──────────────────────────────

    @Test
    fun `MediaEntity toMetaJsValue whitelist and labels parse`() {
        val m = MediaEntity(
            id = 12,
            uri = "content://x/12",
            type = MediaType.PHOTO,
            captureDate = 1_000L,
            fileName = "IMG_1.jpg",
            labels = """["猫","户外"]""",
            locationName = "北京",
            city = "北京",
            hasFace = true,
            faceId = "p_3",
            aestheticScore = 7.5f,
            faceQualityScore = 0.82f,
        )
        val obj = (m.toMetaJsValue() as JsValue.Obj).entries
        assertEquals(12.0, (obj["id"] as JsValue.Num).value, 0.0)
        assertEquals("PHOTO", (obj["type"] as JsValue.Str).value)
        assertEquals(
            listOf("猫", "户外"),
            (obj["labels"] as JsValue.Arr).items.map { (it as JsValue.Str).value },
        )
        assertEquals("北京", (obj["locationName"] as JsValue.Str).value)
        assertEquals("北京", (obj["city"] as JsValue.Str).value)
        assertEquals(true, (obj["hasFace"] as JsValue.Bool).value)
        assertEquals("p_3", (obj["faceId"] as JsValue.Str).value)
        assertEquals(7.5, (obj["aestheticScore"] as JsValue.Num).value, 0.001)
        assertEquals(0.82, (obj["faceQualityScore"] as JsValue.Num).value, 0.001)
        // 隐私白名单：不含 uri / GPS / ocrText
        assertFalse(obj.containsKey("uri"))
        assertFalse(obj.containsKey("latitude"))
        assertFalse(obj.containsKey("longitude"))
        assertFalse(obj.containsKey("ocrText"))
    }

    @Test
    fun `MediaEntity toMetaJsValue null fields`() {
        val m = MediaEntity(
            id = 1, uri = "u", type = MediaType.PHOTO, captureDate = 1L, fileName = "f",
        )
        val obj = (m.toMetaJsValue() as JsValue.Obj).entries
        assertEquals(JsValue.Null, obj["labels"])
        assertEquals(JsValue.Null, obj["locationName"])
        assertEquals(JsValue.Null, obj["faceId"])
        assertEquals(JsValue.Null, obj["city"])
        assertEquals(JsValue.Null, obj["aestheticScore"])
        assertEquals(JsValue.Null, obj["faceQualityScore"])
    }

    // ── toPersonJsValue（face.cluster）──────────────────────────────

    @Test
    fun `PersonEntity toPersonJsValue shape and privacy whitelist`() {
        val p = PersonEntity(personId = 7, name = "小明", coverMediaId = 42, faceCount = 15)
        val obj = p.toPersonJsValue().entries
        assertEquals(7.0, (obj["personId"] as JsValue.Num).value, 0.0)
        assertEquals("小明", (obj["name"] as JsValue.Str).value)
        assertEquals(15.0, (obj["faceCount"] as JsValue.Num).value, 0.0)
        assertEquals(42.0, (obj["coverMediaId"] as JsValue.Num).value, 0.0)
        // 隐私白名单：不含 embedding 原始数据
        assertFalse(obj.containsKey("embedding"))
    }

    @Test
    fun `PersonEntity toPersonJsValue null name and cover`() {
        val p = PersonEntity(personId = 3, name = null, coverMediaId = null, faceCount = 2)
        val obj = p.toPersonJsValue().entries
        assertEquals(JsValue.Null, obj["name"])
        assertEquals(JsValue.Null, obj["coverMediaId"])
    }

    // ── outOfVocabTags（tag.audit）──────────────────────────────────

    @Test
    fun `outOfVocabTags filters vocab tags and sorts desc`() {
        val dist = linkedMapOf(
            "户外" to 10,
            "奇怪的标签" to 7,
            "猫" to 5,
            "另一个非标" to 3,
        )
        val result = outOfVocabTags(dist, listOf("户外", "猫"), limit = 10)
        assertEquals(linkedMapOf("奇怪的标签" to 7, "另一个非标" to 3), result)
    }

    @Test
    fun `outOfVocabTags respects limit`() {
        val dist = (1..20).associate { "非标$it" to it }
        val result = outOfVocabTags(dist, emptyList(), limit = 5)
        assertEquals(5, result.size)
        // 计数降序
        assertEquals(listOf(20, 19, 18, 17, 16), result.values.toList())
    }

    @Test
    fun `outOfVocabTags empty when all in vocab`() {
        val dist = linkedMapOf("户外" to 10, "猫" to 5)
        assertTrue(outOfVocabTags(dist, listOf("户外", "猫"), limit = 10).isEmpty())
    }

    // ── toScanStatusJsValue（tag.scan_status）───────────────────────

    @Test
    fun `toScanStatusJsValue null session returns inactive`() {
        val obj = (null as TagScanSessionProgress?).toScanStatusJsValue().entries
        assertEquals(false, (obj["active"] as JsValue.Bool).value)
        assertEquals(JsValue.Null, obj["state"])
        assertEquals(2, obj.size)
    }

    @Test
    fun `toScanStatusJsValue running session is active with full fields`() {
        val p = TagScanSessionProgress(
            sessionId = "s-1",
            state = ScanSessionState.RUNNING,
            currentPass = TagScanPass.IMAGE_TAGGING,
            processed = 10,
            total = 50,
            pending = 39,
            failed = 1,
            estimatedRemainingMs = 30_000L,
        )
        val obj = p.toScanStatusJsValue().entries
        assertEquals(true, (obj["active"] as JsValue.Bool).value)
        assertEquals("RUNNING", (obj["state"] as JsValue.Str).value)
        assertEquals("IMAGE_TAGGING", (obj["currentPass"] as JsValue.Str).value)
        assertEquals(10.0, (obj["processed"] as JsValue.Num).value, 0.0)
        assertEquals(50.0, (obj["total"] as JsValue.Num).value, 0.0)
        assertEquals(39.0, (obj["pending"] as JsValue.Num).value, 0.0)
        assertEquals(1.0, (obj["failed"] as JsValue.Num).value, 0.0)
        assertEquals(30_000.0, (obj["estimatedRemainingMs"] as JsValue.Num).value, 0.0)
    }

    @Test
    fun `toScanStatusJsValue paused is active, completed is inactive`() {
        val paused = TagScanSessionProgress(sessionId = "s", state = ScanSessionState.PAUSED)
        assertEquals(true, (paused.toScanStatusJsValue().entries["active"] as JsValue.Bool).value)

        val completed = TagScanSessionProgress(sessionId = "s", state = ScanSessionState.COMPLETED)
        val obj = completed.toScanStatusJsValue().entries
        assertEquals(false, (obj["active"] as JsValue.Bool).value)
        assertEquals("COMPLETED", (obj["state"] as JsValue.Str).value)
        assertEquals(JsValue.Null, obj["currentPass"])
        assertEquals(JsValue.Null, obj["estimatedRemainingMs"])
    }
}
