package com.mamba.picme.testing.accessibility.model

import org.json.JSONObject

data class RpcRequest(
    val jsonrpc: String,
    val id: Int?,
    val method: String,
    val params: JSONObject?
) {
    companion object {
        fun parse(json: JSONObject): RpcRequest = RpcRequest(
            jsonrpc = json.optString("jsonrpc", "2.0"),
            id = json.optInt("id", -1).takeIf { json.has("id") },
            method = json.getString("method"),
            params = json.optJSONObject("params")
        )
    }
}

data class RpcResponse(
    val jsonrpc: String = "2.0",
    val id: Int?,
    val result: JSONObject? = null,
    val error: JSONObject? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("jsonrpc", jsonrpc)
        id?.let { put("id", it) }
        result?.let { put("result", it) }
        error?.let { put("error", it) }
    }

    companion object {
        fun success(id: Int?, result: JSONObject): RpcResponse =
            RpcResponse(id = id, result = result)

        fun error(id: Int?, code: Int, message: String, data: JSONObject? = null): RpcResponse =
            RpcResponse(
                id = id,
                error = JSONObject().apply {
                    put("code", code)
                    put("message", message)
                    data?.let { put("data", it) }
                }
            )
    }
}
