package com.mamba.picme.domain.chat.markdown

/**
 * Agent 气泡 markdown 分段（双端 SSOT）。CODE 段交由 CodeBlock 折叠渲染。
 *
 * 2026-08-13 由 `androidApp/.../features/chat/ClaudeMarkdownSegmenter.kt` 下沉至 commonMain，
 * 去 Claude 前缀（`AgentSegmentType`→`SegmentType`、`AgentSegment`→`Segment`、`segmentMarkdown` 等
 * 函数名保留以减少引用改动）。纯 Kotlin + Regex，逻辑零改动。
 */

/** markdown 分段类型。CODE 段交由 CodeBlock 折叠渲染。 */
enum class SegmentType { MARKDOWN, TABLE, CODE }

data class Segment(val type: SegmentType, val text: String)

/** GFM 表格分隔行，如 `|---|---|`、`| --- | ---: |`、`---|---`（可无前后导 `|`）。 */
private val TABLE_DELIMITER = Regex("""^\s*\|?(\s*:?-{2,}:?\s*\|)+(\s*:?-{2,}:?\s*)\|?\s*$""")

private val CODE_FENCE = Regex("""^\s*```""")

/** 解析出的 markdown 表格：表头 + 数据行（分隔行已剔除，单元格为去行内标记的纯文本）。 */
data class MarkdownTable(val header: List<String>, val rows: List<List<String>>)

/** 未转义的 `|` 作单元格分隔符；`\|` 是字面管道。 */
private val CELL_SPLIT = Regex("""(?<!\\)\|""")

/** 单元格行内标记清理：`**bold**`、`__bold__`、`` `code` `` → 纯文本。 */
private val INLINE_MARKERS = Regex("""(\*\*|__|`)""")

/**
 * 把 TABLE 段原文解析成 [MarkdownTable]：首行为表头，第二行（分隔行）跳过，其余为数据行；
 * 列数不足表头的行补空串、超出的截断；`\|` 还原为字面 `|`。
 */
fun parseMarkdownTable(raw: String): MarkdownTable {
    fun cells(line: String): List<String> {
        val body = line.trim().removePrefix("|").removeSuffix("|")
        return CELL_SPLIT.split(body).map { cell ->
            cell.replace("\\|", "|").replace(INLINE_MARKERS, "").trim()
        }
    }
    val lines = raw.split("\n").filter { it.isNotBlank() }
    if (lines.size < 2) return MarkdownTable(emptyList(), emptyList())
    val header = cells(lines[0])
    val rows = lines.drop(2).map { line ->
        val parsed = cells(line)
        if (parsed.size >= header.size) parsed.take(header.size)
        else parsed + List(header.size - parsed.size) { "" }
    }
    return MarkdownTable(header, rows)
}

/**
 * 把 agent 回复切成 MARKDOWN / TABLE / CODE 段。表格段由原生网格渲染（不走通用 markdown 库，防位图抖动）；
 * 代码段（围栏内，含围栏行）交给 CodeBlock 折叠。一条回复可含多个表格/代码块。
 * 流式期间末围栏可能缺失：未闭合的 ``` 之后所有行均归 CODE。
 */
fun segmentMarkdown(content: String): List<Segment> {
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
    val types = ArrayList<SegmentType>(lines.size)
    var inFence = false
    for (i in lines.indices) {
        val line = lines[i]
        if (CODE_FENCE.containsMatchIn(line)) {
            inFence = !inFence
            types += SegmentType.CODE
            continue
        }
        types += when {
            inFence -> SegmentType.CODE
            isTableLine[i] -> SegmentType.TABLE
            else -> SegmentType.MARKDOWN
        }
    }
    // 合并连续同类型
    val segments = mutableListOf<Segment>()
    var start = 0
    for (i in 1..lines.size) {
        if (i == lines.size || types[i] != types[start]) {
            segments += Segment(types[start], lines.subList(start, i).joinToString("\n"))
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
