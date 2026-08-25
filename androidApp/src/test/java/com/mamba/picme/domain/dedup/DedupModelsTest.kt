package com.mamba.picme.domain.dedup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DedupModelsTest {

    private fun member(uri: String, sizeBytes: Long = 1_000_000) = DedupMember(
        uri = uri, sizeBytes = sizeBytes, mime = "image/jpeg",
        captureDate = 1_000L, modifiedAt = 1_000L,
        pixelArea = 12_000_000, aestheticScore = null,
        role = VersionRole.UNKNOWN, md5 = null, phash = null,
    )

    private fun group(uris: List<String>, keepUri: String) = DedupGroup(
        id = DedupGroup.stableId(DedupLevel.EXACT, uris),
        level = DedupLevel.EXACT,
        members = uris.map { uri -> member(uri) },
        keepUri = keepUri,
    )

    @Test
    fun `deleteUris excludes keepUri`() {
        val g = group(listOf("a", "b", "c"), keepUri = "b")
        assertEquals(listOf("a", "c"), g.deleteUris)
    }

    @Test
    fun `reclaimBytes sums only non-kept members`() {
        val g = DedupGroup(
            id = "g1", level = DedupLevel.EXACT,
            members = listOf(
                member("a", sizeBytes = 2_000_000),
                member("b", sizeBytes = 3_000_000),
                member("c", sizeBytes = 5_000_000),
            ),
            keepUri = "a",
        )
        assertEquals(8_000_000L, g.reclaimBytes)
    }

    @Test
    fun `stableId is order-independent`() {
        val id1 = DedupGroup.stableId(DedupLevel.VISUAL, listOf("a", "b", "c"))
        val id2 = DedupGroup.stableId(DedupLevel.VISUAL, listOf("c", "a", "b"))
        assertEquals(id1, id2)
    }

    @Test
    fun `keepUri not in members throws IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            group(listOf("a", "b"), keepUri = "x")
        }
    }
}
