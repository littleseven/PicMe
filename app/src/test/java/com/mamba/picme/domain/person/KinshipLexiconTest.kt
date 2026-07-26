package com.mamba.picme.domain.person

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KinshipLexicon] 单测（纯 JVM）。
 *
 * 覆盖：新称谓 → 具体谓词映射、谓词族查询扩展（具体含未指定桶、泛化含整族）、
 * scan 长短称谓去重。
 */
class KinshipLexiconTest {

    @Test
    fun `declaration terms map to specific predicates`() {
        assertEquals(RelationPredicate.SON, KinshipLexicon.predicateFor("儿子"))
        assertEquals(RelationPredicate.DAUGHTER, KinshipLexicon.predicateFor("女儿"))
        assertEquals(RelationPredicate.DAUGHTER, KinshipLexicon.predicateFor("闺女"))
        assertEquals(RelationPredicate.FATHER, KinshipLexicon.predicateFor("爸爸"))
        assertEquals(RelationPredicate.FATHER, KinshipLexicon.predicateFor("父亲"))
        assertEquals(RelationPredicate.MOTHER, KinshipLexicon.predicateFor("妈妈"))
        assertEquals(RelationPredicate.ELDER_BROTHER, KinshipLexicon.predicateFor("哥哥"))
        assertEquals(RelationPredicate.ELDER_SISTER, KinshipLexicon.predicateFor("姐姐"))
        assertEquals(RelationPredicate.YOUNGER_BROTHER, KinshipLexicon.predicateFor("弟弟"))
        assertEquals(RelationPredicate.YOUNGER_SISTER, KinshipLexicon.predicateFor("妹妹"))
        assertEquals(RelationPredicate.GRANDFATHER, KinshipLexicon.predicateFor("爷爷"))
        assertEquals(RelationPredicate.GRANDFATHER, KinshipLexicon.predicateFor("外公"))
        assertEquals(RelationPredicate.GRANDMOTHER, KinshipLexicon.predicateFor("奶奶"))
        assertEquals(RelationPredicate.GRANDMOTHER, KinshipLexicon.predicateFor("外婆"))
    }

    @Test
    fun `general terms map to unspecified buckets`() {
        assertEquals(RelationPredicate.CHILD, KinshipLexicon.predicateFor("孩子"))
        assertEquals(RelationPredicate.CHILD, KinshipLexicon.predicateFor("小孩"))
        assertEquals(RelationPredicate.PARENT, KinshipLexicon.predicateFor("父母"))
        assertEquals(RelationPredicate.SIBLING, KinshipLexicon.predicateFor("兄弟姐妹"))
        assertEquals(RelationPredicate.GRANDPARENT, KinshipLexicon.predicateFor("祖辈"))
        assertEquals(RelationPredicate.GRANDPARENT, KinshipLexicon.predicateFor("爷爷奶奶"))
    }

    @Test
    fun `query expansion for specific term includes same-family bucket`() {
        assertEquals(
            setOf(RelationPredicate.DAUGHTER, RelationPredicate.CHILD),
            KinshipLexicon.queryPredicatesFor("女儿")
        )
        assertEquals(
            setOf(RelationPredicate.FATHER, RelationPredicate.PARENT),
            KinshipLexicon.queryPredicatesFor("爸爸")
        )
        assertEquals(
            setOf(RelationPredicate.ELDER_SISTER, RelationPredicate.SIBLING),
            KinshipLexicon.queryPredicatesFor("姐姐")
        )
        assertEquals(
            setOf(RelationPredicate.GRANDFATHER, RelationPredicate.GRANDPARENT),
            KinshipLexicon.queryPredicatesFor("外公")
        )
    }

    @Test
    fun `query expansion for general term covers whole family`() {
        assertEquals(
            setOf(RelationPredicate.SON, RelationPredicate.DAUGHTER, RelationPredicate.CHILD),
            KinshipLexicon.queryPredicatesFor("孩子")
        )
        assertEquals(
            setOf(RelationPredicate.FATHER, RelationPredicate.MOTHER, RelationPredicate.PARENT),
            KinshipLexicon.queryPredicatesFor("父母")
        )
        assertEquals(
            setOf(
                RelationPredicate.ELDER_BROTHER, RelationPredicate.ELDER_SISTER,
                RelationPredicate.YOUNGER_BROTHER, RelationPredicate.YOUNGER_SISTER,
                RelationPredicate.SIBLING
            ),
            KinshipLexicon.queryPredicatesFor("兄弟姐妹")
        )
        assertEquals(
            setOf(
                RelationPredicate.GRANDFATHER, RelationPredicate.GRANDMOTHER,
                RelationPredicate.GRANDPARENT
            ),
            KinshipLexicon.queryPredicatesFor("祖辈")
        )
    }

    @Test
    fun `query expansion for non-family term is singleton and unknown is null`() {
        assertEquals(setOf(RelationPredicate.SPOUSE), KinshipLexicon.queryPredicatesFor("老婆"))
        assertNull(KinshipLexicon.queryPredicatesFor("表妹"))
    }

    @Test
    fun `scan deduplicates shorter terms covered by longer hit`() {
        val hits = KinshipLexicon.scan("我爸爸的照片").map { (term, _) -> term }
        assertEquals("「爸爸」命中后「爸」不重复", listOf("爸爸"), hits)

        val both = KinshipLexicon.scan("我爸和我妈的合照").map { (term, _) -> term }.toSet()
        assertEquals(setOf("爸", "妈"), both)

        // 「爷爷奶奶」作为整体命中祖辈桶（等价于 爷爷+奶奶 的并集）
        val grandparents = KinshipLexicon.scan("爷爷奶奶的老照片").map { (term, _) -> term }
        assertEquals(listOf("爷爷奶奶"), grandparents)
    }
}
