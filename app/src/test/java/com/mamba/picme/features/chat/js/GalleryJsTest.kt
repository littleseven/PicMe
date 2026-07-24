package com.mamba.picme.features.chat.js

import com.mamba.picme.agent.core.js.JsValue
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.data.model.MediaEntity
import com.mamba.picme.domain.model.GalleryQueryResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GalleryJsTest {

    @Test
    fun `parseQueryFilter reads all fields`() {
        val args = JsValue.Obj(
            linkedMapOf(
                "label" to JsValue.Str("猫"),
                "ocr" to JsValue.Str("生日"),
                "location" to JsValue.Str("北京"),
                "fromMs" to JsValue.Num(1000.0),
                "toMs" to JsValue.Num(2000.0),
                "hasFace" to JsValue.Bool(true),
                "limit" to JsValue.Num(50.0),
            )
        )
        val f = parseQueryFilter(args)
        assertEquals("猫", f.label)
        assertEquals(1000L, f.fromMs)
        assertEquals(2000L, f.toMs)
        assertEquals(true, f.hasFace)
        assertEquals(50, f.limit)
    }

    @Test
    fun `parseQueryFilter blank strings become null`() {
        val args = JsValue.Obj(linkedMapOf("label" to JsValue.Str("   ")))
        val f = parseQueryFilter(args)
        assertEquals(null, f.label)
        assertEquals(200, f.limit) // 默认
    }

    @Test
    fun `parseQueryFilter non-obj returns defaults`() {
        val f = parseQueryFilter(JsValue.Str("oops"))
        assertEquals(null, f.label)
        assertEquals(200, f.limit)
    }

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
            hasFace = true,
            faceId = "p_3",
        )
        val obj = (m.toMetaJsValue() as JsValue.Obj).entries
        assertEquals(12.0, (obj["id"] as JsValue.Num).value, 0.0)
        assertEquals("PHOTO", (obj["type"] as JsValue.Str).value)
        assertEquals(
            listOf("猫", "户外"),
            (obj["labels"] as JsValue.Arr).items.map { (it as JsValue.Str).value },
        )
        assertEquals("北京", (obj["locationName"] as JsValue.Str).value)
        assertEquals(true, (obj["hasFace"] as JsValue.Bool).value)
        assertEquals("p_3", (obj["faceId"] as JsValue.Str).value)
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
    }
}
