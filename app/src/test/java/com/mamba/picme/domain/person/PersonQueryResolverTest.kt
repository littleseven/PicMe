package com.mamba.picme.domain.person

import com.mamba.picme.data.local.entity.PersonEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PersonQueryResolver] 单测（纯 JVM，PersonRepository 用 MockK 替身）。
 *
 * 覆盖：称谓→谓词命中、已命名人物 contains 命中、双人共现判定、
 * 歧义并集、零命中回退、"我和X"合拍计入 SELF、第一人称单独出现不计入。
 */
class PersonQueryResolverTest {

    private val repository = mockk<PersonRepository>()
    private val resolver = PersonQueryResolver(repository)

    private fun person(id: Long, name: String?, isSelf: Boolean = false) =
        PersonEntity(personId = id, name = name, isSelf = isSelf)

    private fun stubDefaults(
        named: List<PersonEntity> = emptyList(),
        kinship: Map<String, List<PersonEntity>> = emptyMap(),
        self: PersonEntity? = null
    ) {
        coEvery { repository.getNamedPersons() } returns named
        coEvery { repository.resolveByKinship(any()) } answers {
            kinship[firstArg<String>()].orEmpty()
        }
        coEvery { repository.getSelfPerson() } returns self
    }

    @Test
    fun `kinship term resolves to related persons`() = runTest {
        stubDefaults(kinship = mapOf("女儿" to listOf(person(1, "小宝"))))

        val result = resolver.resolve("我女儿的照片")

        assertEquals(setOf(1L), result.personIds)
        assertFalse(result.isAmbiguous)
        assertTrue(result.descriptions.single().contains("女儿"))
    }

    @Test
    fun `named person is matched by query contains`() = runTest {
        stubDefaults(named = listOf(person(1, "小宝"), person(2, "阿珍")))

        val result = resolver.resolve("小宝去海边的照片")

        assertEquals(setOf(1L), result.personIds)
        assertFalse(result.isAmbiguous)
    }

    @Test
    fun `self join pattern with another person yields two personIds for cooccurrence`() = runTest {
        stubDefaults(
            named = listOf(person(1, "小宝")),
            self = person(9, "我", isSelf = true)
        )

        val result = resolver.resolve("我和小宝的合照")

        assertEquals("≥2 personId 供共现查询", setOf(1L, 9L), result.personIds)
        assertTrue(result.descriptions.contains("我"))
    }

    @Test
    fun `kinship term hitting multiple relations unions and marks ambiguous`() = runTest {
        stubDefaults(
            kinship = mapOf("女儿" to listOf(person(1, "大宝"), person(2, "二宝")))
        )

        val result = resolver.resolve("我女儿的单人照")

        assertEquals(setOf(1L, 2L), result.personIds)
        assertTrue(result.isAmbiguous)
        assertTrue(result.descriptions.single().contains("并集"))
    }

    @Test
    fun `zero hit returns empty result`() = runTest {
        stubDefaults(named = listOf(person(1, "小宝")))

        val result = resolver.resolve("猫咪的照片")

        assertTrue(result.personIds.isEmpty())
        assertFalse(result.isAmbiguous)
    }

    @Test
    fun `first person pronoun alone does not include self`() = runTest {
        stubDefaults(
            named = listOf(person(1, "小宝")),
            self = person(9, "我", isSelf = true)
        )

        // 无合拍 Pattern：不计入 SELF（"我"只是第一人称叙述）
        val plain = resolver.resolve("我想看小宝的照片")
        assertEquals(setOf(1L), plain.personIds)

        // 有合拍 Pattern 但没有其他人物命中：也不计入 SELF
        val noOther = resolver.resolve("我和猫咪的合照")
        assertTrue(noOther.personIds.isEmpty())
    }

    @Test
    fun `same name hitting multiple persons unions and marks ambiguous`() = runTest {
        stubDefaults(
            named = listOf(person(1, "小宝"), person(2, "小宝"))
        )

        val result = resolver.resolve("小宝的照片")

        assertEquals(setOf(1L, 2L), result.personIds)
        assertTrue(result.isAmbiguous)
    }

    @Test
    fun `person hit by both name and kinship is deduplicated`() = runTest {
        stubDefaults(
            named = listOf(person(1, "小宝")),
            kinship = mapOf("女儿" to listOf(person(1, "小宝")))
        )

        val result = resolver.resolve("我女儿小宝的照片")

        assertEquals(setOf(1L), result.personIds)
        assertFalse(result.isAmbiguous)
    }
}
