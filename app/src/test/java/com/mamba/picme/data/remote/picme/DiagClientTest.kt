package com.mamba.picme.data.remote.picme

import com.mamba.picme.core.diag.DiagBundle
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DiagClientTest {

    @Test
    fun `report request body matches server DiagReportRequest contract`() {
        val bundle = DiagBundle(
            logs = "PoLang:Camera preview",
            crashTrace = null,
            appVersion = "1.0.29",
            gitSha = "abc1234",
            deviceModel = "Pixel 8",
            androidVersion = "14",
        )
        val json = DiagClient.buildReportBody("crash on open", bundle)
        val obj = JSONObject(json)
        assertEquals("crash on open", obj.getString("description"))
        val b = obj.getJSONObject("bundle")
        assertEquals("PoLang:Camera preview", b.getString("logs"))
        assertEquals("1.0.29", b.getString("appVersion"))
        assertEquals("abc1234", b.getString("gitSha"))
        assertEquals("Pixel 8", b.getString("deviceModel"))
        assertEquals("14", b.getString("androidVersion"))
    }

    @Test
    fun `report body sanitizes description and conversationSummary`() {
        val bundle = DiagBundle(
            logs = "x", crashTrace = null, appVersion = "1",
            gitSha = "s", deviceModel = "m", androidVersion = "14",
        )
        val obj = JSONObject(
            DiagClient.buildReportBody(
                "mail me at a@b.com", bundle,
                "token pl-0123456789abcdef0123456789abcdef leaked",
            )
        )
        assertEquals("mail me at <email>", obj.getString("description"))
        assertEquals("token <token> leaked", obj.getString("conversationSummary"))
    }

    @Test
    fun `report body omits conversationSummary when null or blank`() {
        val bundle = DiagBundle(
            logs = "x", crashTrace = null, appVersion = "1",
            gitSha = "s", deviceModel = "m", androidVersion = "14",
        )
        assertFalse(JSONObject(DiagClient.buildReportBody("d", bundle)).has("conversationSummary"))
        assertFalse(JSONObject(DiagClient.buildReportBody("d", bundle, "  ")).has("conversationSummary"))
    }

    @Test
    fun `report body truncates overlong description and summary`() {
        val bundle = DiagBundle(
            logs = "x", crashTrace = null, appVersion = "1",
            gitSha = "s", deviceModel = "m", androidVersion = "14",
        )
        val obj = JSONObject(DiagClient.buildReportBody("d".repeat(3000), bundle, "s".repeat(5000)))
        assertEquals(2000, obj.getString("description").length)
        assertEquals(4000, obj.getString("conversationSummary").length)
    }

    @Test
    fun `parseJobStatus tolerates TIMED_OUT and reads error and updatedAt`() {
        val st = DiagClient.parseJobStatus(
            """{"jobId":3,"status":"TIMED_OUT","updatedAt":1722440000000,"error":"sweep timeout"}"""
        )
        assertEquals("TIMED_OUT", st.status)
        assertEquals("sweep timeout", st.error)
        assertEquals(1722440000000L, st.updatedAt)
    }

    @Test
    fun `parseJobStatus defaults optional fields for old server responses`() {
        val st = DiagClient.parseJobStatus("""{"jobId":3,"status":"QUEUED"}""")
        assertNull(st.error)
        assertEquals(0L, st.updatedAt)
    }
}
