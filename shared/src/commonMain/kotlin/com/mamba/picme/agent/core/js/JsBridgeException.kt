package com.mamba.picme.agent.core.js

/**
 * JSBridge 错误类型。[errorCode] 对外暴露（不泄露内部栈/路径），满足过审最小信息原则。
 */
class JsBridgeException(
    val errorCode: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    companion object {
        const val HANDLER_NOT_FOUND = "HANDLER_NOT_FOUND"
        const val HANDLER_ERROR = "HANDLER_ERROR"
        const val HANDLER_NOT_ASYNC_CALLABLE = "HANDLER_NOT_ASYNC_CALLABLE"
        const val SCRIPT_ERROR = "SCRIPT_ERROR"
        const val SCRIPT_TIMEOUT = "SCRIPT_TIMEOUT"
        const val SANDBOX_VIOLATION = "SANDBOX_VIOLATION"
        const val FUNCTION_NOT_FOUND = "FUNCTION_NOT_FOUND"
    }
}
