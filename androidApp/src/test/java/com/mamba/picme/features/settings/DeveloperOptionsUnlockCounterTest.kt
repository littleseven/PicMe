package com.mamba.picme.features.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeveloperOptionsUnlockCounterTest {

    @Test
    fun firstTap_returnsCountdownWithRequiredMinusOne() {
        val counter = DeveloperOptionsUnlockCounter(now = { 0L })
        val result = counter.tap()
        assertEquals(
            UnlockTapResult.Countdown(DeveloperOptionsUnlockCounter.REQUIRED_TAPS - 1),
            result
        )
    }

    @Test
    fun tapsBelowThreshold_returnDescendingCountdown() {
        var time = 0L
        val counter = DeveloperOptionsUnlockCounter(now = { time })
        assertEquals(UnlockTapResult.Countdown(6), counter.tap())
        time = 100
        assertEquals(UnlockTapResult.Countdown(5), counter.tap())
        time = 200
        assertEquals(UnlockTapResult.Countdown(4), counter.tap())
    }

    @Test
    fun tapReachingThreshold_returnsUnlocked() {
        var time = 0L
        val counter = DeveloperOptionsUnlockCounter(requiredTaps = 3, now = { time })
        time = 0
        counter.tap()
        time = 100
        counter.tap()
        time = 200
        val result = counter.tap()
        assertTrue(result is UnlockTapResult.Unlocked)
    }

    @Test
    fun resetTimeoutGap_resetsTheCount() {
        var time = 0L
        val counter = DeveloperOptionsUnlockCounter(
            requiredTaps = 5,
            resetTimeoutMs = 4_000L,
            now = { time }
        )
        time = 0
        counter.tap() // count=1
        time = 1_000
        counter.tap() // count=2
        time = 6_000   // gap > 4000 → reset to 1
        val result = counter.tap()
        assertEquals(UnlockTapResult.Countdown(4), result)
    }

    @Test
    fun resetTimeoutBoundary_withinWindowKeepsCount() {
        var time = 0L
        val counter = DeveloperOptionsUnlockCounter(
            requiredTaps = 5,
            resetTimeoutMs = 4_000L,
            now = { time }
        )
        time = 0
        counter.tap() // count=1
        time = 4_000   // boundary, not exceeding (>), keep count
        val result = counter.tap()
        assertEquals(UnlockTapResult.Countdown(3), result)
    }

    @Test
    fun afterUnlock_counterResetsSoNextTapStartsFresh() {
        var time = 0L
        val counter = DeveloperOptionsUnlockCounter(requiredTaps = 2, now = { time })
        time = 0
        counter.tap()
        time = 100
        assertTrue(counter.tap() is UnlockTapResult.Unlocked)
        time = 200
        assertEquals(UnlockTapResult.Countdown(1), counter.tap())
    }
}
