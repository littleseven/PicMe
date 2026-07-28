package com.mamba.picme.domain.tag

import com.mamba.picme.data.local.entity.TagScanPass
import com.mamba.picme.domain.tag.scan.ScanQueuePolicy
import com.mamba.picme.domain.tag.scan.TagScanOrchestrator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TAG 扫描编排器单元测试
 */
class TagScanOrchestratorTest {

    @Test
    fun `isPassesCovered returns true when requested is empty`() {
        assertTrue(TagScanOrchestrator.isPassesCovered(null, emptySet()))
        assertTrue(TagScanOrchestrator.isPassesCovered("{}", emptySet()))
    }

    @Test
    fun `isPassesCovered returns false when lastTagScanPasses is null or empty`() {
        assertFalse(TagScanOrchestrator.isPassesCovered(null, setOf("1")))
        assertFalse(TagScanOrchestrator.isPassesCovered("", setOf("1")))
    }

    @Test
    fun `isPassesCovered returns true only when all requested passes exist`() {
        val passes = """{"1":1000,"2":2000}"""

        assertTrue(TagScanOrchestrator.isPassesCovered(passes, setOf("1")))
        assertTrue(TagScanOrchestrator.isPassesCovered(passes, setOf("1", "2")))
        assertFalse(TagScanOrchestrator.isPassesCovered(passes, setOf("1", "2", "3")))
        assertFalse(TagScanOrchestrator.isPassesCovered(passes, setOf("3")))
    }

    @Test
    fun `isPassesCovered handles malformed json gracefully`() {
        assertFalse(TagScanOrchestrator.isPassesCovered("not-json", setOf("1")))
    }

    @Test
    fun `perMediaCoveragePassNumbers excludes DBSCAN from per-media coverage`() {
        // 默认 phase 1 passes = FACE_DETECTION + DBSCAN；DBSCAN 是全局任务，必须不计入覆盖判定
        val phase1 = ScanQueuePolicy().passes
        assertEquals(setOf("1"), TagScanOrchestrator.perMediaCoveragePassNumbers(phase1))
        // phase 2 (IMAGE_TAGGING) 是单媒体 pass，保留
        val phase2 = TagScanOrchestrator.nextPhasePolicy(ScanQueuePolicy())!!.passes
        assertEquals(setOf("3"), TagScanOrchestrator.perMediaCoveragePassNumbers(phase2))
    }

    @Test
    fun `photo covered for pass 1 but missing DBSCAN is not re-selected`() {
        // 回归 Pass 1 死循环：照片已完成 FACE_DETECTION（lastTagScanPasses={"1"}），DBSCAN 全局任务
        // 从不写单媒体 "2"。覆盖判定必须剔除 "2"，否则该照片被恒判「未覆盖」→ 4h 窗口过期后被无限重选。
        val lastTagScanPasses = """{"1":1700000000000}""" // 仅有 "1"，无 "2"
        val requested = TagScanOrchestrator.perMediaCoveragePassNumbers(ScanQueuePolicy().passes)
        assertTrue(TagScanOrchestrator.isPassesCovered(lastTagScanPasses, requested))
    }

    @Test
    fun `TagCategory toPasses maps face to pass 1 and 2`() {
        val passes = TagCategory.toPasses(setOf(TagCategory.FACE))
        assertEquals(listOf(TagScanPass.FACE_DETECTION, TagScanPass.DBSCAN), passes)
    }

    @Test
    fun `TagCategory toPasses maps scene to pass 3`() {
        val passes = TagCategory.toPasses(setOf(TagCategory.SCENE))
        assertEquals(listOf(TagScanPass.IMAGE_TAGGING), passes)
    }

    @Test
    fun `TagCategory toPasses combines passes without duplicates`() {
        val passes = TagCategory.toPasses(setOf(TagCategory.FACE, TagCategory.SCENE, TagCategory.TAGS))
        assertEquals(
            listOf(
                TagScanPass.FACE_DETECTION,
                TagScanPass.DBSCAN,
                TagScanPass.IMAGE_TAGGING
            ),
            passes
        )
    }

    @Test
    fun `nextPhasePolicy returns second-phase policy when deferredPasses non-empty`() {
        val policy = ScanQueuePolicy()
        val next = TagScanOrchestrator.nextPhasePolicy(policy)

        assertNotNull(next)
        assertEquals(listOf(TagScanPass.IMAGE_TAGGING), next!!.passes)
        // 防死循环：第二阶段不再有延迟阶段
        assertTrue(next.deferredPasses.isEmpty())
    }

    @Test
    fun `nextPhasePolicy returns null when deferredPasses empty`() {
        val policy = ScanQueuePolicy(
            passes = listOf(TagScanPass.FACE_DETECTION),
            deferredPasses = emptyList()
        )
        assertNull(TagScanOrchestrator.nextPhasePolicy(policy))
    }

    @Test
    fun `nextPhasePolicy on second-phase policy returns null (no third phase)`() {
        val secondPhase = TagScanOrchestrator.nextPhasePolicy(ScanQueuePolicy())!!
        assertNull(TagScanOrchestrator.nextPhasePolicy(secondPhase))
    }
}
