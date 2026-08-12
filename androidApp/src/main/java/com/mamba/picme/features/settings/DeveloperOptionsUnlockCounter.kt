package com.mamba.picme.features.settings

/**
 * 开发者选项「版本号连点解锁」计数器（纯逻辑，可单测）。
 *
 * 每次点击调用 [tap]：若距上次点击超过 [resetTimeoutMs] 则先归零；
 * 累计到 [requiredTaps] 次返回 [UnlockTapResult.Unlocked] 并归零；
 * 否则返回 [UnlockTapResult.Countdown] 告知剩余次数。
 */
class DeveloperOptionsUnlockCounter(
    private val requiredTaps: Int = REQUIRED_TAPS,
    private val resetTimeoutMs: Long = RESET_TIMEOUT_MS,
    private val now: () -> Long = System::currentTimeMillis
) {
    private var count = 0
    private var lastTapMs = 0L

    fun tap(): UnlockTapResult {
        val nowMs = now()
        if (count > 0 && nowMs - lastTapMs > resetTimeoutMs) {
            count = 0
        }
        count++
        lastTapMs = nowMs
        return if (count >= requiredTaps) {
            count = 0
            UnlockTapResult.Unlocked
        } else {
            UnlockTapResult.Countdown(remaining = requiredTaps - count)
        }
    }

    fun reset() {
        count = 0
    }

    companion object {
        const val REQUIRED_TAPS = 7
        const val RESET_TIMEOUT_MS: Long = 4_000L
    }
}

sealed interface UnlockTapResult {
    /** 还差 [remaining] 次点击解锁。 */
    data class Countdown(val remaining: Int) : UnlockTapResult
    /** 已达到阈值，解锁。 */
    data object Unlocked : UnlockTapResult
}
