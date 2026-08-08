package com.mamba.picme.agent.core.platform.logging

/**
 * Agent 核心模块日志接口
 *
 * 抽象日志依赖，让 :agent-core 模块保持平台无关。
 * App 模块通过 [Logger.setDelegate] 注入平台特定实现。
 */
interface Logger {
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String)
    fun w(tag: String, message: String, throwable: Throwable)
    fun e(tag: String, message: String, throwable: Throwable? = null)
    fun isLogEnabled(tag: String): Boolean

    companion object : Logger {
        private var delegate: Logger? = null

        fun setDelegate(logger: Logger) {
            delegate = logger
        }

        override fun d(tag: String, message: String) {
            if (!isLogEnabled(tag)) return
            delegate?.d(tag, message) ?: println("[DEBUG] Agent:$tag: $message")
        }

        override fun i(tag: String, message: String) {
            if (!isLogEnabled(tag)) return
            delegate?.i(tag, message) ?: println("[INFO] Agent:$tag: $message")
        }

        override fun w(tag: String, message: String) {
            if (!isLogEnabled(tag)) return
            delegate?.w(tag, message) ?: println("[WARN] Agent:$tag: $message")
        }

        override fun w(tag: String, message: String, throwable: Throwable) {
            if (!isLogEnabled(tag)) return
            delegate?.w(tag, message, throwable) ?: println("[WARN] Agent:$tag: $message")
        }

        override fun e(tag: String, message: String, throwable: Throwable?) {
            if (!isLogEnabled(tag)) return
            delegate?.e(tag, message, throwable) ?: println("[ERROR] Agent:$tag: $message")
        }

        override fun isLogEnabled(tag: String): Boolean {
            return delegate?.isLogEnabled(tag) ?: true
        }
    }
}
