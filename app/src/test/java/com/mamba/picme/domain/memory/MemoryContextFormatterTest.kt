package com.mamba.picme.domain.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryContextFormatterTest {

    @Test
    fun empty_returnsBlank() {
        assertEquals("", formatMemoryContext(emptyList(), emptyList()))
    }

    @Test
    fun relationsOnly_noFactsSection() {
        val relations = listOf(RelationLine("小宝", "女儿"))
        val out = formatMemoryContext(relations, emptyList())
        assertTrue(out.contains("关系：小宝=女儿"))
        assertFalse(out.contains("事实："))
    }

    @Test
    fun factsOnly_includesBulletWithCategory() {
        val facts = listOf(FactLine("小宝对花粉过敏", "健康", createdAt = 100L))
        val out = formatMemoryContext(emptyList(), facts)
        assertTrue(out.contains("事实："))
        assertTrue(out.contains("- 小宝对花粉过敏（健康）"))
        assertFalse(out.contains("关系："))
    }

    @Test
    fun factsOnly_nullCategory_noParens() {
        val facts = listOf(FactLine("喜欢低饱和度滤镜", null, createdAt = 1L))
        val out = formatMemoryContext(emptyList(), facts)
        assertTrue(out.contains("- 喜欢低饱和度滤镜"))
        assertFalse(out.contains("（）"))
    }

    @Test
    fun both_relationsAndFacts() {
        val relations = listOf(RelationLine("小宝", "女儿"))
        val facts = listOf(FactLine("喜欢猫", null, createdAt = 1L))
        val out = formatMemoryContext(relations, facts)
        assertTrue(out.contains("关系：小宝=女儿"))
        assertTrue(out.contains("- 喜欢猫"))
    }

    @Test
    fun facts_sortedByCreatedAtDesc_newerFirst() {
        val facts = listOf(
            FactLine("旧事实", null, createdAt = 100L),
            FactLine("新事实", null, createdAt = 300L),
            FactLine("中事实", null, createdAt = 200L)
        )
        val out = formatMemoryContext(emptyList(), facts)
        val newIdx = out.indexOf("新事实")
        val midIdx = out.indexOf("中事实")
        val oldIdx = out.indexOf("旧事实")
        assertTrue("newer must come first", newIdx < midIdx && midIdx < oldIdx)
    }

    @Test
    fun budgetTruncation_addsRecallHint_andDropsSome() {
        // 5 条事实，每条较长；预算只够装下少数
        val facts = (1..5).map { FactLine("这是第$it 条比较长的事实内容用于撑爆预算", null, createdAt = it.toLong()) }
        val out = formatMemoryContext(emptyList(), facts, charBudget = 120)
        assertTrue(out.contains("共 5 条"))
        assertTrue(out.contains("recall_memory"))
        // 第 5 条 createdAt 最大（最近），应优先出现；第 1 条最旧，很可能被截掉
        assertTrue(out.contains("第5"))
        assertFalse(out.contains("第1"))
    }

    @Test
    fun budgetTruncation_hintShowsShownCount() {
        val facts = (1..5).map { FactLine("事实$it", null, createdAt = it.toLong()) }
        // budget 取到能装下部分但装不下全部 5 条（每条 5 字符），触发截断提示
        val out = formatMemoryContext(emptyList(), facts, charBudget = 45)
        // 提示格式：已显示最近 K 条
        assertTrue(Regex("已显示最近 \\d+ 条").containsMatchIn(out))
    }
}
