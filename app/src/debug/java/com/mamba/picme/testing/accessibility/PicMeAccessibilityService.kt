package com.mamba.picme.testing.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
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
        rpcServer?.stop()
        return super.onUnbind(intent)
    }
}
