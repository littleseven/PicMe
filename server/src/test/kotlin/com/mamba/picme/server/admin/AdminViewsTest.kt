package com.mamba.picme.server.admin

import org.junit.Assert.assertTrue
import org.junit.Test

class AdminViewsTest {

    @Test
    fun `overview page renders stat cards and an svg chart`() {
        val ov = OverviewRow(2L, 1L, 5L, 1234L, 1.5, 4096L, 1L)
        val series = listOf(DayBucket("2026-07-12", 5L, 1L, 600L, 634L, 1234L, 1.5, 4096L))
        val html = AdminViews.overviewPage(ov, series)
        assertTrue(html.contains("总用户数"))
        assertTrue(html.contains("今日成本 ¥"))
        assertTrue(html.contains("<svg"))
    }

    @Test
    fun `users page lists emails and detail links`() {
        val rows = listOf(UserRow(1, "a@x.com", "active", 0L, 3L, 100L, 0.5, null))
        val html = AdminViews.usersPage(rows)
        assertTrue(html.contains("a@x.com"))
        assertTrue(html.contains("/admin/users/1"))
    }

    @Test
    fun `login page has a password form`() {
        val html = AdminViews.loginPage(failed = true)
        assertTrue(html.contains("<form"))
        assertTrue(html.contains("name=\"password\""))
        assertTrue(html.contains("密码错误"))
    }

    @Test
    fun `traffic page renders daily table`() {
        val series = listOf(DayBucket("2026-07-12", 1L, 0L, 10L, 5L, 15L, 0.1, 100L))
        val html = AdminViews.trafficPage(series)
        assertTrue(html.contains("Total Token"))
        assertTrue(html.contains("2026-07-12"))
    }
}
