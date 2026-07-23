package com.mamba.picme.agent.core.js

import com.mamba.picme.agent.core.model.context.GallerySummary
import org.junit.Assert.assertEquals
import org.junit.Test

class GallerySummaryJsTest {
    private fun sample() = GallerySummary(
        totalPhotos = 100,
        totalVideos = 5,
        totalMedia = 105,
        hasFaceCount = 30,
        personClusterCount = 8,
        namedPersonCount = 3,
        labeledCount = 80,
        unlabeledCount = 25,
        semanticEncodedCount = 60,
        remainingPass1 = 10,
        remainingPass3 = 25,
        isScanning = false,
        currentPass = null,
        recommendation = GallerySummary.ScanRecommendation.INCREMENTAL,
    )

    @Test
    fun `maps counts and recommendation`() {
        val v = sample().toJsValue()
        assertEquals(JsValue.Num(105.0), v.entries["totalMedia"])
        assertEquals(JsValue.Num(80.0), v.entries["labeledCount"])
        assertEquals(JsValue.Num(8.0), v.entries["personClusterCount"])
        assertEquals(JsValue.Num(3.0), v.entries["namedPersonCount"])
        assertEquals(JsValue.Bool(false), v.entries["isScanning"])
        assertEquals(JsValue.Str("INCREMENTAL"), v.entries["recommendation"])
    }

    @Test
    fun `null currentPass maps to Null`() {
        val v = sample().toJsValue()
        assertEquals(JsValue.Null, v.entries["currentPass"])
    }
}
