package com.mamba.picme.features.camera.thread

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.mamba.picme.core.common.Logger

/**
 * [Day1 线程隔离] 相机 HandlerThread 基类
 *
 * 职责边界：
 * - PoLang-CameraCapture：ImageCapture.takePicture() 回调 + 拍照后处理
 * - PoLang-CameraAnalysis：ImageAnalysis 帧分析（人脸检测等）
 * - 禁止：调用任何 CameraX API（bind/unbind）、GL API、Agent 状态机
 *
 * 优先级：THREAD_PRIORITY_BACKGROUND + THREAD_PRIORITY_MORE_FAVORABLE
 */
class CameraHandlerThread(name: String) : HandlerThread(
    name,
    Process.THREAD_PRIORITY_BACKGROUND + Process.THREAD_PRIORITY_MORE_FAVORABLE
) {

    companion object {
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
        Logger.i(TAG, "CameraHandlerThread started: name=$name")
    }

    fun post(runnable: Runnable) {
        val h = handler ?: throw IllegalStateException("CameraHandlerThread not ready")
        h.post(runnable)
    }

    fun postDelayed(delayMs: Long, runnable: Runnable) {
        val h = handler ?: throw IllegalStateException("CameraHandlerThread not ready")
        h.postDelayed(runnable, delayMs)
    }

    /**
     * 在当前线程（必须是本 HandlerThread）同步执行
     */
    fun <T> executeSync(action: () -> T): T {
        check(Thread.currentThread().name == this.name) {
            "executeSync must be called on ${this.name}, current=${Thread.currentThread().name}"
        }
        return action()
    }

    override fun quitSafely(): Boolean {
        isReady = false
        handler = null
        val result = super.quitSafely()
        Logger.i(TAG, "CameraHandlerThread quitSafely: name=${this.name}")
        return result
    }

    fun isThreadReady(): Boolean = isReady

    fun getHandler(): Handler? = handler
}
