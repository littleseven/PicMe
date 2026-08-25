package com.mamba.picme.domain.dedup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepPolicyEngineTest {

    private fun member(
        uri: String, sizeBytes: Long = 1_000_000, pixelArea: Int = 12_000_000,
        captureDate: Long = 1_000L, modifiedAt: Long = captureDate,
        aestheticScore: Float? = null,
    ) = DedupMember(
        uri = uri, sizeBytes = sizeBytes, mime = "image/jpeg",
        captureDate = captureDate, modifiedAt = modifiedAt,
        pixelArea = pixelArea, aestheticScore = aestheticScore,
        role = VersionRole.UNKNOWN, md5 = null, phash = null,
    )

    @Test
    fun `classify marks smallest copy as COMPRESSED`() {
        val big = member("a", sizeBytes = 4_000_000, pixelArea = 12_000_000)
        val small = member("b", sizeBytes = 900_000, pixelArea = 3_000_000)
        val out = KeepPolicyEngine.classify(listOf(big, small))
        assertEquals(VersionRole.ORIGINAL, out.first { m -> m.uri == "a" }.role)
        assertEquals(VersionRole.COMPRESSED, out.first { m -> m.uri == "b" }.role)
    }

    @Test
    fun `classify marks edited version when modified long after capture`() {
        val original = member("a")
        val edited = member("b", modifiedAt = 1_000L + 48 * 3600_000L)
        val out = KeepPolicyEngine.classify(listOf(original, edited))
        assertEquals(VersionRole.EDITED, out.first { m -> m.uri == "b" }.role)
    }

    @Test
    fun `BEST_QUALITY keeps largest pixelArea then size`() {
        val a = member("a", sizeBytes = 4_000_000, pixelArea = 12_000_000)
        val b = member("b", sizeBytes = 5_000_000, pixelArea = 8_000_000)
        val sorted = KeepPolicyEngine.recommend(KeepPolicy.BEST_QUALITY, KeepPolicyEngine.classify(listOf(a, b)))
        assertEquals("a", sorted.first().uri)
    }

    @Test
    fun `ORIGINAL policy keeps original over edited and compressed`() {
        val original = member("a")
        val edited = member("b", modifiedAt = 1_000L + 48 * 3600_000L)
        val compressed = member("c", sizeBytes = 500_000, pixelArea = 2_000_000)
        val sorted = KeepPolicyEngine.recommend(KeepPolicy.ORIGINAL, KeepPolicyEngine.classify(listOf(edited, compressed, original)))
        assertEquals("a", sorted.first().uri)
    }

    @Test
    fun `EDITED policy prefers edited version`() {
        val original = member("a")
        val edited = member("b", modifiedAt = 1_000L + 48 * 3600_000L)
        val sorted = KeepPolicyEngine.recommend(KeepPolicy.EDITED, KeepPolicyEngine.classify(listOf(original, edited)))
        assertEquals("b", sorted.first().uri)
    }

    @Test
    fun `LATEST policy keeps most recently modified`() {
        val a = member("a", modifiedAt = 100L)
        val b = member("b", modifiedAt = 200L)
        val sorted = KeepPolicyEngine.recommend(KeepPolicy.LATEST, KeepPolicyEngine.classify(listOf(a, b)))
        assertEquals("b", sorted.first().uri)
    }

    @Test
    fun `recommend always keeps exactly one and never empties group`() {
        val members = listOf(member("a"), member("b"), member("c"))
        KeepPolicy.entries.forEach { policy ->
            val sorted = KeepPolicyEngine.recommend(policy, members)
            assertEquals(3, sorted.size)
            assertTrue(sorted.first().uri.isNotEmpty())
        }
    }

    @Test
    fun `classify does not mark COMPRESSED at exactly half pixelArea and size`() {
        val big = member("a", sizeBytes = 2_000_000, pixelArea = 12_000_000)
        val half = member("b", sizeBytes = 1_000_000, pixelArea = 6_000_000)
        val out = KeepPolicyEngine.classify(listOf(big, half))
        assertEquals(VersionRole.ORIGINAL, out.first { m -> m.uri == "b" }.role)
    }

    @Test
    fun `classify does not mark EDITED at exactly six hours after capture`() {
        val m = member("a", modifiedAt = 1_000L + 6 * 3600_000L)
        val out = KeepPolicyEngine.classify(listOf(m))
        assertEquals(VersionRole.ORIGINAL, out.first().role)
    }
}
