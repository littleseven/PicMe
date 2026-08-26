package com.mamba.picme.domain.dedup

sealed interface DedupScanEvent {
    data class Progress(val phase: DedupLevel, val scanned: Int, val total: Int) : DedupScanEvent
    data class GroupFound(val group: DedupGroup) : DedupScanEvent
    data class PhaseChanged(val phase: DedupLevel, val phaseIndex: Int, val phaseCount: Int) : DedupScanEvent
    data class Done(val groups: List<DedupGroup>) : DedupScanEvent
    data object Cancelled : DedupScanEvent
}
