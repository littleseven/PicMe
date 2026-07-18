package com.mamba.picme.agent.core.tool.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.mamba.picme.agent.core.platform.logging.Logger

/**
 * PoLang 远程控制 AccessibilityService。
 *
 * 用户需要在系统设置 → 无障碍中开启本服务。
 * 服务连接后，会把自身注册到 [AccessibilityServiceHolder]，供 [PoLangToolService] 读取
 * Compose 页面的语义树并执行点击/输入/滚动等操作。
 */
class PoLangAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PoLangAccessibilityService"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityServiceHolder.attach(this)
        Logger.i(TAG, "PoLang accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 按需轮询 rootInActiveWindow，不监听事件
    }

    override fun onInterrupt() {
        Logger.i(TAG, "PoLang accessibility service interrupted")
        AccessibilityServiceHolder.detach(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Logger.i(TAG, "PoLang accessibility service unbound")
        AccessibilityServiceHolder.detach(this)
        return super.onUnbind(intent)
    }
}
