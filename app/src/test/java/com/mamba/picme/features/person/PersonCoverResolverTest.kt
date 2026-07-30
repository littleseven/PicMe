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
}
