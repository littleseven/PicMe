package com.mamba.picme.domain.dedup

object KeepPolicyEngine {

    private const val EDITED_GAP_MS = 6 * 3600_000L
    private const val COMPRESSED_RATIO = 0.5

    fun classify(members: List<DedupMember>): List<DedupMember> {
        val maxPixel = members.maxOf { member -> member.pixelArea }.coerceAtLeast(1)
        val maxSize = members.maxOf { member -> member.sizeBytes }.coerceAtLeast(1L)
        return members.map { member ->
            val role = when {
                member.pixelArea < maxPixel * COMPRESSED_RATIO || member.sizeBytes < maxSize * COMPRESSED_RATIO ->
                    VersionRole.COMPRESSED
                member.modifiedAt - member.captureDate > EDITED_GAP_MS -> VersionRole.EDITED
                else -> VersionRole.ORIGINAL
            }
            member.copy(role = role)
        }
    }

    fun recommend(policy: KeepPolicy, members: List<DedupMember>): List<DedupMember> {
        val roleRank = when (policy) {
            KeepPolicy.ORIGINAL -> mapOf(
                VersionRole.ORIGINAL to 0, VersionRole.EDITED to 1,
                VersionRole.UNKNOWN to 2, VersionRole.COMPRESSED to 3
            )
            KeepPolicy.EDITED -> mapOf(
                VersionRole.EDITED to 0, VersionRole.ORIGINAL to 1,
                VersionRole.UNKNOWN to 2, VersionRole.COMPRESSED to 3
            )
            else -> emptyMap()
        }
        val quality: Comparator<DedupMember> =
            compareByDescending<DedupMember> { member -> member.pixelArea }
                .thenByDescending { member -> member.sizeBytes }
                .thenByDescending { member -> member.aestheticScore ?: -1f }
                .thenByDescending { member -> member.captureDate }
        return when (policy) {
            KeepPolicy.BEST_QUALITY -> members.sortedWith(quality)
            KeepPolicy.LATEST -> members.sortedWith(compareByDescending { member -> member.modifiedAt })
            else -> members.sortedWith(
                compareBy<DedupMember> { member -> roleRank[member.role] ?: 2 }.then(quality)
            )
        }
    }
}
