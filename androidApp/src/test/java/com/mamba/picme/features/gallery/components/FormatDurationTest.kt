package com.mamba.picme.features.gallery.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ETA 剩余时间格式化单元测试。
 *
 * 重点回归：超过 24h 的合法估值必须按"天"展示（如 "1d 0h"），
 * 而非被钉死在 "24h 0m"。详见 TagScanOrchestrator 的 ETA 上限移除。
 */
class FormatDurationTest {

    @Test
    fun `formatDuration renders exactly 24h as 1d 0h not 24h 0m`() {
        assertEquals("1d 0h", formatDuration(24 * 60 * 60 * 1000L))
    }

    @Test
    fun `formatDuration renders 25h as 1d 1h`() {
        assertEquals("1d 1h", formatDuration(25 * 60 * 60 * 1000L))
    }

    @Test
    fun `formatDuration renders multi-day durations`() {
        // 2 天 3 小时
        assertEquals("2d 3h", formatDuration((2 * 24 + 3) * 60 * 60 * 1000L))
    }

    @Test
    fun `formatDuration renders sub-day hours with minutes`() {
        assertEquals("1h 0m", formatDuration(60 * 60 * 1000L))
        assertEquals("1h 5m", formatDuration((60 + 5) * 60 * 1000L))
    }

    @Test
    fun `formatDuration renders minutes with seconds`() {
        assertEquals("1m 5s", formatDuration(65 * 1000L))
    }

    @Test
    fun `formatDuration renders bare seconds`() {
        assertEquals("5s", formatDuration(5_000L))
    }
}
