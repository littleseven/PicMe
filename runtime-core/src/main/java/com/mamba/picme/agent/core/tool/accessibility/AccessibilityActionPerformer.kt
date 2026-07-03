package com.mamba.picme.agent.core.tool.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.mamba.picme.agent.core.platform.logging.Logger

/**
 * 通过 AccessibilityService 执行 UI 操作。
 *
 * 主要用于 Compose 页面：View 树只能看到 AndroidComposeView 外壳，
 * 而 Accessibility 树可以看到语义节点并执行点击、输入、滚动等动作。
 */
object AccessibilityActionPerformer {

    private const val TAG = "AccessibilityActionPerformer"

    fun clickByText(root: AccessibilityNodeInfo, text: String): Boolean {
        val target = findNodeByText(root, text) ?: run {
            Logger.w(TAG, "No accessible node with text: '$text'")
            return false
        }
        val result = clickNodeOrClickableAncestor(target)
        target.recycle()
        return result
    }

    fun clickByCoordinate(root: AccessibilityNodeInfo, x: Int, y: Int): Boolean {
        val target = findNodeAt(root, x, y) ?: run {
            Logger.w(TAG, "No accessible node at ($x, $y)")
            return false
        }
        val result = clickNodeOrClickableAncestor(target)
        target.recycle()
        return result
    }

    fun inputText(root: AccessibilityNodeInfo, text: String, clearFirst: Boolean = true): Boolean {
        val target = findEditableNode(root) ?: run {
            Logger.w(TAG, "No editable accessible node found")
            return false
        }
        if (!target.isFocused) {
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }
        val finalText = if (clearFirst) {
            text
        } else {
            (target.text?.toString() ?: "") + text
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, finalText)
        }
        val result = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        target.recycle()
        return result
    }

    /**
     * 滚动可滚动节点。
     * @param direction "up" 表示向上滑，显示下方内容（对应 forward）；"down" 相反。
     */
    fun scroll(root: AccessibilityNodeInfo, direction: String): Boolean {
        val scrollable = findScrollableNode(root) ?: run {
            Logger.w(TAG, "No scrollable accessible node found")
            return false
        }
        val action = if (direction.equals("up", ignoreCase = true)) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        val result = scrollable.performAction(action)
        scrollable.recycle()
        return result
    }

    fun pressBack(): Boolean {
        return AccessibilityServiceHolder.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    /**
     * 对目标节点或其 clickable 祖先执行 ACTION_CLICK。
     */
    private fun clickNodeOrClickableAncestor(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        var original = node
        while (current != null) {
            if (current.isClickable) {
                val result = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (current !== original) {
                    current.recycle()
                }
                return result
            }
            val parent = current.parent
            if (current !== original) {
                current.recycle()
            } else {
                // 第一次循环的 current 是 node 的拷贝，不需要再单独回收 original
                original = current
            }
            current = parent
        }
        return false
    }

    private fun findNodeByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val lower = text.lowercase()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeFirst()
            val nodeText = node.text?.toString()?.lowercase() ?: ""
            val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
            if (nodeText.contains(lower) || contentDesc.contains(lower)) {
                return AccessibilityNodeInfo.obtain(node)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.add(it) }
            }
        }
        return null
    }

    private fun findNodeAt(root: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val rootBounds = Rect().also { root.getBoundsInScreen(it) }
        if (!rootBounds.contains(x, y)) return null

        var best: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(root)
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNodeAt(child, x, y)
            child.recycle()
            if (found != null) {
                best?.recycle()
                best = found
            }
        }
        return best
    }

    private fun findEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isEditableCompat()) return AccessibilityNodeInfo.obtain(root)
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findEditableNode(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun findScrollableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isScrollable) return AccessibilityNodeInfo.obtain(root)
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findScrollableNode(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun AccessibilityNodeInfo.isEditableCompat(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isEditable
        } else {
            actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
        }
    }
}
