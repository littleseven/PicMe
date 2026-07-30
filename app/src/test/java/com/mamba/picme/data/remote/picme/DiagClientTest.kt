package com.mamba.picme.data.remote.picme

import com.mamba.picme.core.diag.DiagBundle
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
}
