package com.mamba.picme.agent.core.tool.perception

/**
 * 把 UI 操作结果和观察到的屏幕状态格式化为 ReAct tool result。
 */
object UiObservationFormatter {

    private const val ACTION_PREFIX = "Action:"
    private const val STATE_PREFIX = "Post-action screen state:"

    /**
     * 格式化操作后的观察结果。
     *
     * @param actionDescription 操作结果简短描述，例如 "Clicked element with text: '搜索照片'"
     * @param screenState 当前屏幕状态字符串，通常来自 [ViewHierarchyExtractor.extractSemanticSummary]
     * @return 标准返回字符串
     */
    fun format(actionDescription: String, screenState: String): String {
        return buildString {
            appendLine("$ACTION_PREFIX $actionDescription")
            appendLine(STATE_PREFIX)
            append(screenState)
        }
    }

    /**
     * 判断一个工具返回字符串是否包含 post-action screen state。
     */
    fun containsObservation(result: String): Boolean {
        return result.contains(STATE_PREFIX)
    }
}
