package com.mamba.picme.domain.person

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RelationSnapshotRestorer] 纯函数单测（无 IO）。
 *
 * 覆盖：按名恢复、SELF 以 isSelf 为准（不依赖名字）、
 * 人物消失进 dropped、重名条目各自独立解析。
 */
class RelationSnapshotRestorerTest {

    private fun entry(
        subjectName: String? = "小宝",
        subjectIsSelf: Boolean = false,
        objectName: String? = null,
        objectIsSelf: Boolean = true,
        predicate: String = "CHILD",
        source: String = "CHAT_DECLARATION"
    ) = RelationSnapshotEntry(
        subjectName = subjectName,
        subjectIsSelf = subjectIsSelf,
        objectName = objectName,
        objectIsSelf = objectIsSelf,
        predicate = predicate,
        source = source
    )

    @Test
    fun `restores relation by name and self flag`() {
        val plan = RelationSnapshotRestorer.buildRestorePlan(listOf(entry())) { name, isSelf ->
            when {
                isSelf -> 100L
                name == "小宝" -> 1L
                else -> null
            }
        }

        assertTrue(plan.dropped.isEmpty())
        val restored = plan.restored.single()
        assertEquals(1L, restored.subjectPersonId)
        assertEquals(100L, restored.objectPersonId)
        assertEquals("CHILD", restored.predicate)
        assertEquals("CHAT_DECLARATION", restored.source)
    }

    @Test
    fun `self side resolves by isSelf flag even without name`() {
        val seen = mutableListOf<Pair<String?, Boolean>>()
        RelationSnapshotRestorer.buildRestorePlan(listOf(entry())) { name, isSelf ->
            seen.add(name to isSelf)
            if (isSelf) 100L else 1L
        }

        assertTrue(
            "object 端必须以 isSelf=true 解析（不依赖名字）",
            seen.any { (name, isSelf) -> isSelf && name == null }
        )
    }

    @Test
    fun `unresolvable person goes to dropped`() {
        val plan = RelationSnapshotRestorer.buildRestorePlan(
            listOf(entry(subjectName = "已消失人物"))
        ) { _, isSelf -> if (isSelf) 100L else null }

        assertTrue(plan.restored.isEmpty())
        assertEquals(1, plan.dropped.size)
        assertEquals("已消失人物", plan.dropped.single().subjectName)
    }

    @Test
    fun `dropped when self person missing after reclustering`() {
        val plan = RelationSnapshotRestorer.buildRestorePlan(listOf(entry())) { name, isSelf ->
            when {
                isSelf -> null // 重聚后"我"未恢复
                name == "小宝" -> 1L
                else -> null
            }
        }

        assertTrue(plan.restored.isEmpty())
        assertEquals(1, plan.dropped.size)
    }

    @Test
    fun `duplicate names resolve independently through caller policy`() {
        // 重名场景：解析策略由调用方决定（如取第一命中），纯函数只透传
        val entries = listOf(
            entry(subjectName = "小宝", predicate = "CHILD"),
            entry(subjectName = "小宝", predicate = "FRIEND")
        )
        val plan = RelationSnapshotRestorer.buildRestorePlan(entries) { name, isSelf ->
            when {
                isSelf -> 100L
                name == "小宝" -> 1L
                else -> null
            }
        }

        assertEquals(2, plan.restored.size)
        assertTrue(plan.restored.all { it.subjectPersonId == 1L })
        assertEquals(listOf("CHILD", "FRIEND"), plan.restored.map { it.predicate })
    }

    @Test
    fun `empty snapshot list yields empty plan`() {
        val plan = RelationSnapshotRestorer.buildRestorePlan(emptyList()) { _, _ -> 1L }

        assertTrue(plan.restored.isEmpty())
        assertTrue(plan.dropped.isEmpty())
    }
}
