package com.mamba.picme.agent.core.tool.accessibility

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * 把 AccessibilityNodeInfo 树 dump 成紧凑 JSON。
 *
 * 相比 View 层级树，Accessibility 树能反映 Compose 页面的语义信息
 *（text / contentDescription / clickable / scrollable / editable / bounds 等）。
 */
object AccessibilityNodeDumper {

    private const val MAX_DEPTH = 50
    private const val MAX_CHILDREN = 200
    private const val MAX_TEXT_LENGTH = 80

    fun dump(root: AccessibilityNodeInfo, screenWidth: Int, screenHeight: Int): String {
        val rootObj = JSONObject()
        visitNode(root, rootObj, 0, screenWidth, screenHeight)
        return rootObj.toString()
    }

    private fun visitNode(
        node: AccessibilityNodeInfo,
        out: JSONObject,
        depth: Int,
        screenW: Int,
        screenH: Int
    ) {
        out.put("class", node.className?.toString() ?: "unknown")
        node.packageName?.toString()?.let { out.put("package", it) }

        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) {
            out.put("text", truncate(text))
        }

        val contentDesc = node.contentDescription?.toString()?.trim()
        if (!contentDesc.isNullOrEmpty()) {
            out.put("content_desc", truncate(contentDesc))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val hint = node.hintText?.toString()?.trim()
            if (!hint.isNullOrEmpty()) {
                out.put("hint", hint)
            }
        }

        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val boundsObj = JSONObject().apply {
            put("x", bounds.left)
            put("y", bounds.top)
            put("w", bounds.width())
            put("h", bounds.height())
            if (screenW > 0 && screenH > 0) {
                put("x_pct", String.format("%.1f", bounds.left * 100.0 / screenW))
                put("y_pct", String.format("%.1f", bounds.top * 100.0 / screenH))
            }
        }
        out.put("bounds", boundsObj)

        if (node.isClickable) out.put("clickable", true)
        if (node.isLongClickable) out.put("long_clickable", true)
        if (node.isScrollable) out.put("scrollable", true)
        if (node.isEditableCompat()) out.put("editable", true)
        if (!node.isEnabled) out.put("enabled", false)
        if (node.isChecked) out.put("checked", true)
        if (node.isSelected) out.put("selected", true)
        if (node.isFocused) out.put("focused", true)
        if (node.isPassword) out.put("password", true)

        if (depth < MAX_DEPTH && node.childCount > 0) {
            val children = JSONArray()
            val childCount = minOf(node.childCount, MAX_CHILDREN)
            for (i in 0 until childCount) {
                val child = node.getChild(i) ?: continue
                val childObj = JSONObject()
                visitNode(child, childObj, depth + 1, screenW, screenH)
                children.put(childObj)
                child.recycle()
            }
            if (children.length() > 0) {
                out.put("children", children)
            }
        }
    }

    private fun truncate(text: String): String {
        return if (text.length > MAX_TEXT_LENGTH) text.take(MAX_TEXT_LENGTH) + "…" else text
    }

    private fun AccessibilityNodeInfo.isEditableCompat(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isEditable
        } else {
            actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
        }
    }
}
