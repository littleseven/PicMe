package com.mamba.picme.agent.core.tool.perception

import android.view.View
import android.view.ViewGroup
import android.widget.Checkable
import android.widget.EditText
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * View 系统 UI 层级树提取器。
 * 从 Activity 的 decorView 递归遍历，输出精简 JSON 树。
 * 移除默认属性、截断长文本，输出体积 2–20 KB。
 */
object ViewHierarchyExtractor {

    private const val MAX_TEXT_LENGTH = 80
    private const val MAX_CHILDREN = 200
    private const val DEFAULT_SUMMARY_TEXT_LENGTH = 30
    private const val DEFAULT_SUMMARY_ELEMENTS = 30

    /**
     * 从根 View 提取 UI 树 JSON。
     * @param rootView 根 View（通常是 activity.window.decorView.rootView）
     * @param screenWidth 屏幕宽度像素
     * @param screenHeight 屏幕高度像素
     * @return UI 树 JSON 字符串
     */
    fun extract(rootView: View, screenWidth: Int, screenHeight: Int): String {
        val root = JSONObject()
        try {
            visitNode(rootView, root, screenWidth, screenHeight, depth = 0)
        } catch (e: Exception) {
            root.put("error", "View tree extraction failed: ${e.message}")
        }
        // 使用紧凑格式输出，减少 LLM token 消耗
        return root.toString()
    }

    /**
     * 提取当前屏幕的语义化摘要，供 LLM 快速理解页面结构。
     * 包含：页面标题、可交互元素列表、关键状态信息。
     *
     * @param includeFullTree 是否在摘要后附加完整层级树，默认 true
     * @param maxSummaryTextLength 摘要中元素文本的最大长度，默认 30
     * @param maxSummaryElements 摘要中返回的可交互元素最大数量，默认 30
     */
    fun extractSemanticSummary(
        rootView: View,
        screenWidth: Int,
        screenHeight: Int,
        includeFullTree: Boolean = true,
        maxSummaryTextLength: Int = DEFAULT_SUMMARY_TEXT_LENGTH,
        maxSummaryElements: Int = DEFAULT_SUMMARY_ELEMENTS
    ): String {
        val summary = StringBuilder()
        summary.appendLine("=== 页面结构摘要 ===")

        // 尝试提取页面标题（从 Toolbar/ActionBar 或顶部 TextView）
        val title = findTitleText(rootView)
        if (title != null) {
            summary.appendLine("页面标题: $title")
        }

        // 提取所有可交互元素的语义描述
        val interactiveElements = mutableListOf<String>()
        collectInteractiveElements(
            rootView,
            interactiveElements,
            screenWidth,
            screenHeight,
            maxTextLength = maxSummaryTextLength
        )

        val trimmedElements = interactiveElements.take(maxSummaryElements)
        if (trimmedElements.isNotEmpty()) {
            summary.appendLine("可交互元素 (${trimmedElements.size}个):")
            trimmedElements.forEach { summary.appendLine("  - $it") }
        } else {
            summary.appendLine("可交互元素: 无")
        }

        if (interactiveElements.size > maxSummaryElements) {
            summary.appendLine("  ... 还有 ${interactiveElements.size - maxSummaryElements} 个元素已省略")
        }

        // 提取关键状态（如选中状态、开关状态等）
        val states = mutableListOf<String>()
        collectKeyStates(rootView, states)
        if (states.isNotEmpty()) {
            summary.appendLine("关键状态:")
            states.forEach { summary.appendLine("  - $it") }
        }

        if (includeFullTree) {
            summary.appendLine("=== 完整层级树 ===")
            summary.appendLine(extract(rootView, screenWidth, screenHeight))
        }

        return summary.toString()
    }

    private fun findTitleText(view: View): String? {
        if (view.javaClass.simpleName.contains("Toolbar", ignoreCase = true)) {
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    val child = view.getChildAt(i)
                    if (child is TextView) {
                        val text = child.text?.toString()?.trim()
                        if (!text.isNullOrEmpty()) return text
                    }
                }
            }
        }

        if (view is TextView) {
            val text = view.text?.toString()?.trim()
            if (!text.isNullOrEmpty() && view.textSize >= 40f) {
                return text
            }
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findTitleText(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun collectInteractiveElements(
        view: View,
        out: MutableList<String>,
        screenWidth: Int,
        screenHeight: Int,
        parentDesc: String = "",
        maxTextLength: Int = MAX_TEXT_LENGTH
    ) {
        if (view.visibility != View.VISIBLE) return

        val desc = buildSemanticDescription(view, screenWidth, screenHeight, maxTextLength)
        if (desc != null) {
            out.add("$parentDesc$desc")
        }

        if (view is ViewGroup) {
            val childPrefix = if (desc != null) "$desc > " else ""
            for (i in 0 until view.childCount) {
                collectInteractiveElements(
                    view.getChildAt(i),
                    out,
                    screenWidth,
                    screenHeight,
                    childPrefix,
                    maxTextLength
                )
            }
        }
    }

    private fun buildSemanticDescription(
        view: View,
        screenWidth: Int,
        screenHeight: Int,
        maxTextLength: Int = MAX_TEXT_LENGTH
    ): String? {
        val className = view.javaClass.simpleName

        val text = when (view) {
            is TextView -> view.text?.toString()?.trim()
            else -> view.contentDescription?.toString()?.trim()
        }

        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val x = location[0]
        val y = location[1]
        val xPct = if (screenWidth > 0) String.format("%.1f", x * 100.0 / screenWidth) else "0"
        val yPct = if (screenHeight > 0) String.format("%.1f", y * 100.0 / screenHeight) else "0"

        val isClickable = view.isClickable
        val isFocusable = view.isFocusable
        val isEditable = view is EditText
        val isScrollable = view is ViewGroup && (view.canScrollVertically(1) || view.canScrollVertically(-1))

        if (!isClickable && !isFocusable && !isEditable && !isScrollable && text.isNullOrEmpty()) {
            return null
        }

        val id = if (view.id != View.NO_ID) {
            try { view.resources.getResourceEntryName(view.id) } catch (_: Exception) { null }
        } else null

        val parts = mutableListOf<String>()
        parts.add(className)
        if (id != null) parts.add("id=$id")
        if (!text.isNullOrEmpty()) {
            val displayText = if (text.length > maxTextLength) text.take(maxTextLength) + "…" else text
            parts.add("text=\"$displayText\"")
        }
        if (isClickable) parts.add("clickable")
        if (isEditable) parts.add("editable")
        if (isScrollable) parts.add("scrollable")
        if (view is Checkable && view.isChecked) parts.add("checked")
        if (view.isSelected) parts.add("selected")
        if (!view.isEnabled) parts.add("disabled")
        parts.add("bounds=(${x},${y} ${view.width}x${view.height} ~${xPct}%,${yPct}%)")

        return parts.joinToString(" ")
    }

    private fun collectKeyStates(view: View, out: MutableList<String>) {
        if (view.visibility != View.VISIBLE) return

        if (view is Checkable) {
            val text = (view as? TextView)?.text?.toString()?.trim() ?: view.contentDescription?.toString() ?: ""
            out.add("${text.ifEmpty { view.javaClass.simpleName }}: checked=${view.isChecked}")
        }

        if (view is android.widget.AbsListView) {
            out.add("ListView: selectedPosition=${view.selectedItemPosition}")
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collectKeyStates(view.getChildAt(i), out)
            }
        }
    }

    private fun visitNode(
        view: View,
        out: JSONObject,
        screenWidth: Int,
        screenHeight: Int,
        depth: Int
    ) {
        if (depth > 30) {
            out.put("_truncated", true)
            return
        }

        out.put("class", view.javaClass.simpleName)

        val id = view.id
        if (id != View.NO_ID) {
            try {
                val resourceName = view.resources.getResourceEntryName(id)
                out.put("id", resourceName)
            } catch (_: Exception) {
                out.put("id", "0x${Integer.toHexString(id)}")
            }
        }

        if (view.visibility != View.VISIBLE) {
            out.put("visibility", when (view.visibility) {
                View.INVISIBLE -> "invisible"
                View.GONE -> "gone"
                else -> "visible"
            })
        }

        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val left = location[0]
        val top = location[1]
        val right = left + view.width
        val bottom = top + view.height
        val bounds = JSONObject()
        bounds.put("x", left)
        bounds.put("y", top)
        bounds.put("w", view.width)
        bounds.put("h", view.height)

        if (screenWidth > 0 && screenHeight > 0) {
            bounds.put("x_pct", String.format("%.1f", left * 100.0 / screenWidth))
            bounds.put("y_pct", String.format("%.1f", top * 100.0 / screenHeight))
        }
        out.put("bounds", bounds)

        when (view) {
            is TextView -> {
                val text = view.text?.toString()?.trim()
                if (!text.isNullOrEmpty()) {
                    out.put("text", if (text.length > MAX_TEXT_LENGTH) text.take(MAX_TEXT_LENGTH) + "…" else text)
                }
                if (view.contentDescription?.isNotEmpty() == true) {
                    out.put("content_desc", view.contentDescription)
                }
            }
            else -> {
                if (view.contentDescription?.isNotEmpty() == true) {
                    out.put("content_desc", view.contentDescription)
                }
            }
        }

        if (view is EditText) {
            out.put("input_type", "text")
            val hint = view.hint?.toString()?.trim()
            if (!hint.isNullOrEmpty()) {
                out.put("hint", hint)
            }
        }

        if (view.isClickable) {
            out.put("clickable", true)
        }
        if (!view.isEnabled) {
            out.put("enabled", false)
        }
        if (view.isFocusable) {
            out.put("focusable", true)
        }
        if (view.isSelected) {
            out.put("selected", true)
        }
        if (view is Checkable && view.isChecked) {
            out.put("checked", true)
        }

        if (view is ViewGroup) {
            val canScroll = view.canScrollVertically(1) || view.canScrollVertically(-1)
            if (canScroll) {
                out.put("scrollable", true)
            }
        }

        if (view is ViewGroup && view.childCount > 0) {
            val children = JSONArray()
            val childCount = minOf(view.childCount, MAX_CHILDREN)
            for (i in 0 until childCount) {
                val child = view.getChildAt(i)
                if (child.visibility == View.GONE) continue
                val childObj = JSONObject()
                visitNode(child, childObj, screenWidth, screenHeight, depth + 1)
                children.put(childObj)
            }
            if (children.length() > 0) {
                out.put("children", children)
            }
        }
    }
}
