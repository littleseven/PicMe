package com.mamba.picme.agent.core.tool.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.Handler
import android.view.accessibility.AccessibilityNodeInfo
import com.mamba.picme.agent.core.platform.logging.Logger
import java.lang.ref.WeakReference

/**
 * AccessibilityService 持有者。
 *
 * PoLangAccessibilityService 在连接/断开时向此 Holder 注册/注销，
 * RemoteControlToolService 等消费者无需关心服务生命周期，只需通过本对象读取当前窗口节点。
 */
object AccessibilityServiceHolder {

    private const val TAG = "AccessibilityServiceHolder"

    private var serviceRef: WeakReference<AccessibilityService>? = null

    fun attach(service: AccessibilityService) {
        serviceRef = WeakReference(service)
        Logger.i(TAG, "Accessibility service attached")
    }

    fun detach(service: AccessibilityService) {
        if (serviceRef?.get() === service) {
            serviceRef = null
            Logger.i(TAG, "Accessibility service detached")
        }
    }

    fun isActive(): Boolean = serviceRef?.get() != null

    /**
     * 获取当前活动窗口的根节点。调用方负责在使用后调用 [AccessibilityNodeInfo.recycle]。
     */
    fun getRootNode(): AccessibilityNodeInfo? {
        val node = serviceRef?.get()?.rootInActiveWindow
        if (node == null) {
            Logger.d(TAG, "No active accessibility root node")
            return null
        }
        // 如果根节点本身为空且没有任何信息，视为无效
        if (node.childCount == 0 && node.text == null && node.contentDescription == null) {
            node.recycle()
            return null
        }
        return node
    }

    fun performGlobalAction(action: Int): Boolean {
        return serviceRef?.get()?.performGlobalAction(action) ?: false
    }

    fun dispatchGesture(
        gesture: GestureDescription,
        callback: AccessibilityService.GestureResultCallback?,
        handler: Handler?
    ): Boolean {
        return serviceRef?.get()?.dispatchGesture(gesture, callback, handler) ?: false
    }
}
