package com.mamba.picme.testing.accessibility

import com.mamba.picme.testing.accessibility.model.RpcRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class RpcRequestParseTest {

    @Test
    fun parseDumpRequest() {
        val json = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "ui.dump")
            put("params", JSONObject().apply {
                put("package", "com.mamba.picme")
                put("maxDepth", 50)
            })
        }

        val request = RpcRequest.parse(json)
        assertEquals("2.0", request.jsonrpc)
        assertEquals(1, request.id)
        assertEquals("ui.dump", request.method)
        assertEquals("com.mamba.picme", request.params?.getString("package"))
        assertEquals(50, request.params?.getInt("maxDepth"))
    }
}
