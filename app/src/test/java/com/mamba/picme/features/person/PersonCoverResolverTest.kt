package com.mamba.picme.features.person

import com.mamba.picme.data.local.entity.PersonEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersonCoverResolverTest {
    @Test
    fun mapsCoverUriAndFaceFocusYByPersonId() {
        val persons = listOf(
            PersonEntity(personId = 1, coverMediaId = 10),
            PersonEntity(personId = 2, coverMediaId = null),
            PersonEntity(personId = 3, coverMediaId = 30)
        )
        val uriById = mapOf(10L to "content://a", 30L to "content://c")
        val focusYById = mapOf(10L to 0.4f, 30L to null)
        val resolved = PersonCoverResolver.resolve(persons, uriById, focusYById)
        assertEquals("content://a", resolved[1L]?.coverUri)
        assertEquals(0.4f, resolved[1L]?.faceFocusY)
        // 无 coverMediaId → 无封面
        assertNull(resolved[2L]?.coverUri)
        // 有 uri 但该媒体 faceFocusY 为 null（无人脸）→ 对齐回退居中
        assertEquals("content://c", resolved[3L]?.coverUri)
        assertNull(resolved[3L]?.faceFocusY)
    }

    @Test
    fun filterCoverableDropsPersonsWithNullCoverUri() {
        val persons = listOf(
            PersonEntity(personId = 1, coverMediaId = 10),
            PersonEntity(personId = 2, coverMediaId = 20), // 封面媒体已删 → coverUri null → 不展示
            PersonEntity(personId = 3, coverMediaId = null) // 无 coverMediaId → 不展示
        )
        // 仅 media 10 存在；media 20 已被删，resolve 查不到 → null
        val covers = PersonCoverResolver.resolve(
            persons,
            uriByMediaId = mapOf(10L to "content://a"),
            focusYByMediaId = emptyMap()
        )
        val coverable = PersonCoverResolver.filterCoverable(persons, covers)
        assertEquals(listOf(1L), coverable.map { person -> person.personId })
    }
}
