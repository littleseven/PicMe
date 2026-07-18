package com.mamba.picme.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.mamba.picme.BuildConfig
import com.mamba.picme.agent.core.tool.accessibility.AccessibilityServiceHolder
import com.mamba.picme.core.common.Logger

/**
 * PoLang 无障碍服务（release / debug 共用）。
 *
 * 该服务本身不执行任何 UI 自动化动作，仅向系统注册并持有 AccessibilityService
 * 实例，使 App 内部件（如 ReAct Agent 的 get_screen_info、click、input_text）
 * 能够读取无障碍树，从而识别 Compose 语义节点。
 *
 * 用户必须先在系统设置中开启本服务，相关引导入口位于设置页。
 *
 * debug 模式下通过反射启动 `com.mamba.picme.testing.accessibility.UiAutomationRpcServer`，
 * 供 PC 端 ui-driver 技能调用；release 模式不启动 RPC 服务器，避免暴露本地端口。
 */
class PoLangAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PoLangAccessibilityService"
        private const val RPC_SERVER_CLASS = "com.mamba.picme.testing.accessibility.UiAutomationRpcServer"
        private const val DEFAULT_RPC_PORT = 27183
    }

    private var rpcServer: Any? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Logger.i(TAG, "Accessibility service connected")
        AccessibilityServiceHolder.attach(this)
        if (BuildConfig.DEBUG) {
            startRpcServer()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 按需轮询 rootInActiveWindow，不依赖事件流。
    }

    override fun onInterrupt() {
        Logger.i(TAG, "Accessibility service interrupted")
        stopRpcServer()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Logger.i(TAG, "Accessibility service unbound")
        stopRpcServer()
        AccessibilityServiceHolder.detach(this)
        return super.onUnbind(intent)
    }

    private fun startRpcServer() {
        try {
            val clazz = Class.forName(RPC_SERVER_CLASS)
            val constructor = clazz.getDeclaredConstructor(AccessibilityService::class.java, Int::class.java)
            rpcServer = constructor.newInstance(this, DEFAULT_RPC_PORT)
            clazz.getDeclaredMethod("start").invoke(rpcServer)
            Logger.i(TAG, "Debug RPC server started on port $DEFAULT_RPC_PORT")
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to start debug RPC server", e)
        }
    }

    private fun stopRpcServer() {
        rpcServer?.let { server ->
            try {
                server.javaClass.getDeclaredMethod("stop").invoke(server)
                Logger.i(TAG, "Debug RPC server stopped")
            } catch (e: Exception) {
                Logger.w(TAG, "Failed to stop debug RPC server", e)
            }
            rpcServer = null
        }
    }
}
