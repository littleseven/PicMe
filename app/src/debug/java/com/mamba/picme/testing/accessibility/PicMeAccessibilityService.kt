package com.mamba.picme.testing.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.mamba.picme.agent.core.tool.accessibility.AccessibilityServiceHolder
import com.mamba.picme.core.common.Logger

class PicMeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PicMeAccessibilityService"
        const val DEFAULT_PORT = 27183
    }

    private var rpcServer: UiAutomationRpcServer? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Logger.i(TAG, "Accessibility service connected")
        // 向 runtime-core 的 Holder 注册，使 App 内 ReAct 工具（get_screen_info / click / input 等）
        // 也能复用同一份 Accessibility 树，而不是回退到只能看到 AndroidComposeView 的 View 层级树。
        AccessibilityServiceHolder.attach(this)
        rpcServer = UiAutomationRpcServer(this, DEFAULT_PORT).apply { start() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op: we poll rootInActiveWindow on demand via RPC.
    }

    override fun onInterrupt() {
        Logger.i(TAG, "Accessibility service interrupted")
        rpcServer?.stop()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Logger.i(TAG, "Accessibility service unbound")
        AccessibilityServiceHolder.detach(this)
        rpcServer?.stop()
        return super.onUnbind(intent)
    }
}
