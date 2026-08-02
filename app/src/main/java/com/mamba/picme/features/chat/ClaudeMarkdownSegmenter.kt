package com.mamba.picme.features.chat

/** agent 气泡 markdown 分段类型。CODE 段交由 CodeBlock 折叠渲染。 */
enum class AgentSegmentType { MARKDOWN, TABLE, CODE }

data class AgentSegment(val type: AgentSegmentType, val text: String)

/** GFM 表格分隔行，如 `|---|---|`、`| --- | ---: |`、`---|---`（可无前后导 `|`）。 */
private val TABLE_DELIMITER = Regex("""^\s*\|?(\s*:?-{2,}:?\s*\|)+(\s*:?-{2,}:?\s*)\|?\s*$""")

private val CODE_FENCE = Regex("""^\s*```""")

/**
 * 把 agent 回复切成 MARKDOWN / TABLE / CODE 段。表格段纯文本直出（防 Markwon 位图抖动）；
 * 代码段（围栏内，含围栏行）交给 CodeBlock 折叠。一条回复可含多个表格/代码块。
 * 流式期间末围栏可能缺失：未闭合的 ``` 之后所有行均归 CODE。
 */
fun segmentMarkdown(content: String): List<AgentSegment> {
    val lines = content.split("\n")
    val isTableLine = BooleanArray(lines.size)
    var inCodeFence = false
    for (i in lines.indices) {
        if (CODE_FENCE.containsMatchIn(lines[i])) inCodeFence = !inCodeFence
        if (!inCodeFence && i > 0 && TABLE_DELIMITER.matches(lines[i]) && lines[i - 1].contains("|")) {
            isTableLine[i - 1] = true
            isTableLine[i] = true
            var j = i + 1
            while (j < lines.size && lines[j].isNotBlank() && lines[j].contains("|")) {
                isTableLine[j] = true
                j++
            }
        }
    }
    // 逐行三分类：CODE（围栏内含围栏行）/ TABLE / MARKDOWN
    val types = ArrayList<AgentSegmentType>(lines.size)
    var inFence = false
    for (i in lines.indices) {
        val line = lines[i]
        if (CODE_FENCE.containsMatchIn(line)) {
            inFence = !inFence
            types += AgentSegmentType.CODE
            continue
        }
        types += when {
            inFence -> AgentSegmentType.CODE
            isTableLine[i] -> AgentSegmentType.TABLE
            else -> AgentSegmentType.MARKDOWN
        }
    }
    // 合并连续同类型
    val segments = mutableListOf<AgentSegment>()
    var start = 0
    for (i in 1..lines.size) {
        if (i == lines.size || types[i] != types[start]) {
            segments += AgentSegment(types[start], lines.subList(start, i).joinToString("\n"))
            start = i
        }
    }
    return segments
}

/** 从 CODE 段原文（含首尾围栏行）提取代码体：去首围栏行、去末围栏行（若存在）。 */
fun extractCodeBody(raw: String): String {
    val lines = raw.split("\n")
    if (lines.isEmpty()) return ""
    val startIdx = if (CODE_FENCE.containsMatchIn(lines.first())) 1 else 0
    val body = lines.subList(startIdx, lines.size)
    val endIdx = if (body.isNotEmpty() && CODE_FENCE.containsMatchIn(body.last())) body.lastIndex else body.size
    return body.subList(0, endIdx).joinToString("\n")
}

/** 代码体的逻辑行数（空串算 0 行）。 */
fun codeLineCount(code: String): Int = if (code.isEmpty()) 0 else code.count { it == '\n' } + 1

/** 折叠态预览：取前 [limit] 行。 */
fun previewCode(code: String, limit: Int): String = code.split("\n").take(limit).joinToString("\n")
