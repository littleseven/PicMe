package com.mamba.picme.testing.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.mamba.picme.core.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class AccessibilityActionPerformer(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "AccessibilityAction"
        private const val DEFAULT_SWIPE_DURATION_MS = 300L
        private const val DEFAULT_POLL_MS = 200L
    }

    fun click(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun longClick(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
    }

    fun click(bounds: Rect): Boolean {
        val x = (bounds.left + bounds.right) / 2
        val y = (bounds.top + bounds.bottom) / 2
        return tap(x, y)
    }

    fun tap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        return dispatchGesture(gesture)
    }

    fun swipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long = DEFAULT_SWIPE_DURATION_MS
    ): Boolean {
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture)
    }

    fun inputText(node: AccessibilityNodeInfo, text: String): Boolean {
        if (!node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
            Logger.w(TAG, "Failed to focus node before input")
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun pressBack(): Boolean {
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    suspend fun waitForIdle(timeoutMs: Long = 5000): Boolean = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        var previousDump = ""
        while (System.currentTimeMillis() - start < timeoutMs) {
            delay(DEFAULT_POLL_MS)
            val current = service.rootInActiveWindow?.let { root ->
                val serializer = AccessibilityNodeSerializer()
                val json = serializer.dump(service, maxDepth = 3).toString()
                root.recycle()
                json
            } ?: ""
            if (previousDump.isNotEmpty() && current == previousDump) {
                return@withContext true
            }
            previousDump = current
        }
        false
    }

    private fun dispatchGesture(gesture: GestureDescription): Boolean {
        return service.dispatchGesture(gesture, null, null)
    }
}
