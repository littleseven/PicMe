package com.mamba.picme.domain.memory

/** 记忆快照格式化纯函数 + DTO。无 Android/Room 依赖，纯 JVM 可测。 */

const val MEMORY_CONTEXT_CHAR_BUDGET = 1500

data class RelationLine(val name: String, val label: String)
data class FactLine(val content: String, val category: String?, val createdAt: Long)

private const val SECTION_HEADER = "【关于用户（系统已记住的最新记忆，直接引用即可，不要重复调 recall_memory 核对）】"

/**
 * 生成"关于用户"快照文本。无关系且无事实返回 ""（→ systemMessageProvider 不追加，零开销）。
 *
 * 关系全量，拼成 "名字=称谓"（分号分隔）。事实按 createdAt 倒序填进"扣除头部+关系段后的剩余
 * 字符预算"，超限截断并在末尾追加 recall_memory 兜底提示（与既有 recall_memory 工具闭环）。
 */
fun formatMemoryContext(
    relations: List<RelationLine>,
    facts: List<FactLine>,
    charBudget: Int = MEMORY_CONTEXT_CHAR_BUDGET
): String {
    if (relations.isEmpty() && facts.isEmpty()) return ""

    val sb = StringBuilder()
    sb.append(SECTION_HEADER).append('\n')

    if (relations.isNotEmpty()) {
        sb.append("关系：")
            .append(relations.joinToString("；") { "${it.name}=${it.label}" })
            .append('\n')
    }

    if (facts.isNotEmpty()) {
        val factsSorted = facts.sortedByDescending { it.createdAt }
        val bullets = factsSorted.map { factBullet(it) }
        val remaining = (charBudget - sb.length - "事实：\n".length).coerceAtLeast(0)
        val (shown, truncated) = fitBullets(bullets, remaining)

        sb.append("事实：")
        if (shown.isEmpty()) {
            sb.append('\n')
        } else {
            sb.append('\n').append(shown.joinToString("\n")).append('\n')
        }
        if (truncated > 0) {
            sb.append("（事实共 ${factsSorted.size} 条，已显示最近 ${shown.size} 条，更多可用 recall_memory 查询）")
        }
    }

    return sb.toString().trimEnd('\n')
}

/** 单条事实渲染：`- 内容（分类）`，分类空则无括号。 */
private fun factBullet(fact: FactLine): String {
    val category = fact.category?.trim()?.ifEmpty { null }?.let { "（$it）" } ?: ""
    return "- ${fact.content}$category"
}

/**
 * 在 [budget] 字符内尽量多地从头（最近）装下 bullet（每条按 内容长度+1 换行 计）。
 * 一条都放不下时返回空列表，由调用方决定是否出兜底提示。
 */
private fun fitBullets(bullets: List<String>, budget: Int): Pair<List<String>, Int> {
    val shown = mutableListOf<String>()
    var consumed = 0
    for (bullet in bullets) {
        val add = bullet.length + 1 // 内容 + 换行
        if (consumed + add > budget) break // 放不下就停（含第一条放不下）
        shown.add(bullet)
        consumed += add
    }
    return shown to (bullets.size - shown.size)
}
