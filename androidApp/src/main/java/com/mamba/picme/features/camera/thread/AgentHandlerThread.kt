package com.mamba.picme.features.camera.thread

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.mamba.picme.core.common.Logger

/**
 * [Day1 线程隔离] Agent 状态机专用 HandlerThread
 *
 * 职责边界：
 * - 唯一拥有者：AiAgentCommand 解析、Capability 执行、状态机转换
 * - 禁止：调用任何 CameraX API、GL API、直接操作 Bitmap/图像处理
 * - 与 CameraHandlerThread 通信：仅通过 Handler/Channel 发消息，禁止直接调用
 *
 * 线程名：PoLang-AgentState
 * 优先级：THREAD_PRIORITY_DEFAULT
 */
class AgentHandlerThread : HandlerThread(
    THREAD_NAME,
    Process.THREAD_PRIORITY_DEFAULT
) {

    companion object {
        private const val THREAD_NAME = "PoLang-AgentState"
        private const val TAG = "Thread"
    }

    private var handler: Handler? = null

    @Volatile
    private var isReady = false

    init {
        start()
        looper  // 阻塞等待 Looper 就绪
        handler = Handler(looper)
        isReady = true
        Logger.i(TAG, "AgentHandlerThread started: name=${Thread.currentThread().name}")
    }

    fun post(runnable: Runnable) {
        val h = handler ?: throw IllegalStateException("AgentHandlerThread not ready")
        h.post(runnable)
    }

    fun postDelayed(delayMs: Long, runnable: Runnable) {
        val h = handler ?: throw IllegalStateException("AgentHandlerThread not ready")
        h.postDelayed(runnable, delayMs)
    }

    override fun quitSafely(): Boolean {
        isReady = false
        handler = null
        val result = super.quitSafely()
        Logger.i(TAG, "AgentHandlerThread quitSafely")
        return result
    }

    fun isThreadReady(): Boolean = isReady

    fun getHandler(): Handler? = handler
}
