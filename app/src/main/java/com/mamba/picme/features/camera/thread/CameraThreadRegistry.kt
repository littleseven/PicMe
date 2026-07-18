package com.mamba.picme.features.camera.thread

import android.os.Handler
import android.os.Looper
import com.mamba.picme.core.common.Logger

/**
 * [Day1 线程隔离] 线程注册表 —— 统一管理四个物理线程的访问
 *
 * 四个线程：
 * | 线程名                | 类型          | 职责                          |
 * |----------------------|---------------|------------------------------|
 * | main                 | UI 线程       | Compose 渲染、用户交互          |
 * | PoLang-CameraCapture  | HandlerThread | 拍照回调 + 图像后处理            |
 * | PoLang-AgentState     | HandlerThread | Agent 命令解析 + 状态机          |
 * | CameraPreviewRender  | Thread        | GL 渲染（已有，不修改）          |
 *
 * 验收标准：用 Thread.currentThread().name 打印日志，确认四个线程物理分离
 */
object CameraThreadRegistry {

    private const val TAG = "Thread"

    private var cameraHandlerThread: CameraHandlerThread? = null
    private var analysisHandlerThread: CameraHandlerThread? = null
    private var agentHandlerThread: AgentHandlerThread? = null

    @Volatile
    private var isInitialized = false

    /**
     * 初始化所有专用线程。必须在 Application.onCreate() 或相机页面首次展示前调用。
     */
    @Synchronized
    fun initialize() {
        if (isInitialized) {
            Logger.w(TAG, "CameraThreadRegistry already initialized")
            return
        }

        cameraHandlerThread = CameraHandlerThread("PoLang-CameraCapture")
        analysisHandlerThread = CameraHandlerThread("PoLang-CameraAnalysis")
        agentHandlerThread = AgentHandlerThread()
        isInitialized = true

        Logger.i(
            TAG,
            "ThreadRegistry initialized. " +
                "CameraThread=${cameraHandlerThread?.isThreadReady()}, " +
                "AnalysisThread=${analysisHandlerThread?.isThreadReady()}, " +
                "AgentThread=${agentHandlerThread?.isThreadReady()}"
        )
    }

    fun getCameraHandlerThread(): CameraHandlerThread {
        checkInitialized()
        return cameraHandlerThread!!
    }

    fun getAgentHandlerThread(): AgentHandlerThread {
        checkInitialized()
        return agentHandlerThread!!
    }

    fun getCameraHandler(): Handler {
        checkInitialized()
        return cameraHandlerThread!!.getHandler()
            ?: throw IllegalStateException("CameraHandlerThread handler is null")
    }

    fun getAnalysisHandler(): Handler {
        checkInitialized()
        return analysisHandlerThread!!.getHandler()
            ?: throw IllegalStateException("AnalysisHandlerThread handler is null")
    }

    fun getAgentHandler(): Handler {
        checkInitialized()
        return agentHandlerThread!!.getHandler()
            ?: throw IllegalStateException("AgentHandlerThread handler is null")
    }

    /**
     * 获取当前线程的角色标识（用于日志和断言）
     */
    fun getCurrentThreadRole(): ThreadRole {
        val name = Thread.currentThread().name
        return when {
            name == "main" || Looper.myLooper() == Looper.getMainLooper() -> ThreadRole.MAIN
            name == "PoLang-CameraCapture" -> ThreadRole.CAMERA_CAPTURE
            name == "PoLang-CameraAnalysis" -> ThreadRole.ANALYSIS
            name == "PoLang-AgentState" -> ThreadRole.AGENT_STATE
            name.startsWith("CameraPreviewRender") -> ThreadRole.GL_RENDER
            else -> ThreadRole.UNKNOWN
        }
    }

    /**
     * 断言当前线程为指定角色，非法时抛异常
     */
    fun assertThread(role: ThreadRole, action: String) {
        val current = getCurrentThreadRole()
        if (current != role) {
            throw IllegalStateException(
                "Thread assertion failed: action='$action' requires $role, " +
                    "but current=$current (thread=${Thread.currentThread().name})"
            )
        }
    }

    /**
     * 断言当前线程**不是**指定角色，用于禁止越界调用
     */
    fun assertNotThread(role: ThreadRole, action: String) {
        val current = getCurrentThreadRole()
        if (current == role) {
            throw IllegalStateException(
                "Thread assertion failed: action='$action' is FORBIDDEN on $role, " +
                    "but current=$current (thread=${Thread.currentThread().name})"
            )
        }
    }

    @Synchronized
    fun release() {
        Logger.i(TAG, "Releasing CameraThreadRegistry...")
        cameraHandlerThread?.quitSafely()
        analysisHandlerThread?.quitSafely()
        agentHandlerThread?.quitSafely()
        cameraHandlerThread = null
        analysisHandlerThread = null
        agentHandlerThread = null
        isInitialized = false
    }

    private fun checkInitialized() {
        if (!isInitialized) {
            throw IllegalStateException("CameraThreadRegistry not initialized. Call initialize() first.")
        }
    }

    enum class ThreadRole {
        MAIN,           // UI 主线程
        CAMERA_CAPTURE, // 拍照专用线程
        ANALYSIS,       // 图像分析专用线程
        AGENT_STATE,    // Agent 状态机线程
        GL_RENDER,      // GL 渲染线程
        UNKNOWN         // 其他线程
    }
}
