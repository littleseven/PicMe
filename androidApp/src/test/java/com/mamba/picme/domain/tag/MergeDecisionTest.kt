package com.mamba.picme.domain.tag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 小簇跨簇合并判定（[decideSmallClusterMerge]）的纯逻辑单测。
 *
 * 覆盖：相似度不足跳过、双方命名跳过、命名/self 优先级、匿名按规模选幸存者。
 */
class MergeDecisionTest {

    private val thr = 0.65f
    private fun cand(id: Long, name: String? = null, isSelf: Boolean = false, count: Int = 1) =
        MergeCandidate(id, name, isSelf, count)

    @Test
    fun `similarity below threshold returns null`() {
        assertNull(decideSmallClusterMerge(cand(44), cand(144, count = 7), 0.64f, thr))
    }

    @Test
    fun `both named returns null`() {
        // 用户已分别命名 → 尊重人工区分，不自动合并
        assertNull(decideSmallClusterMerge(cand(44, "阿明"), cand(144, "明明", count = 7), 0.9f, thr))
    }

    @Test
    fun `blank names count as anonymous`() {
        // 空白名视为未命名，仍可合并
        val d = decideSmallClusterMerge(cand(44, "  "), cand(144, "", count = 7), 0.85f, thr)!!
        assertEquals(144L, d.survivor.personId)
    }

    @Test
    fun `small named survives over anonymous neighbor`() {
        val d = decideSmallClusterMerge(cand(44, "阿明"), cand(144, count = 7), 0.85f, thr)!!
        assertEquals(44L, d.survivor.personId)
        assertEquals(144L, d.absorbed.personId)
    }

    @Test
    fun `neighbor named survives over anonymous small`() {
        val d = decideSmallClusterMerge(cand(44), cand(144, "阿明", count = 7), 0.85f, thr)!!
        assertEquals(144L, d.survivor.personId)
        assertEquals(44L, d.absorbed.personId)
    }

    @Test
    fun `self survives over named neighbor`() {
        // isSelf 优先级高于命名
        val d = decideSmallClusterMerge(cand(44, isSelf = true), cand(144, "阿明", count = 7), 0.85f, thr)!!
        assertEquals(44L, d.survivor.personId)
    }

    @Test
    fun `anonymous larger neighbor survives`() {
        val d = decideSmallClusterMerge(cand(44), cand(144, count = 7), 0.85f, thr)!!
        assertEquals(144L, d.survivor.personId)
    }

    @Test
    fun `anonymous tie goes to older smaller id`() {
        // 同为匿名、同规模 → personId 小者（更早创建）存活
        val d = decideSmallClusterMerge(cand(144), cand(44, count = 1), 0.9f, thr)!!
        assertEquals(44L, d.survivor.personId)
        assertEquals(144L, d.absorbed.personId)
    }
}
