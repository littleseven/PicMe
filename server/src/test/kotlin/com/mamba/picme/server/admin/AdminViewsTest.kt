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
    fun `users page lists emails, detail links and masked api tokens`() {
        val rows = listOf(
            UserRow(1, "a@x.com", "active", 0L, 3L, 100L, 0.5, null, "picm••••wxyz", true),
            UserRow(2, "b@x.com", "active", 0L, 0L, 0L, 0.0, null, "—", false),
        )
        val html = AdminViews.usersPage(rows)
        assertTrue(html.contains("a@x.com"))
        assertTrue(html.contains("/admin/users/1"))
        // API Token 列：有明文者显示掩码 + 复制按钮；原用量列改名「Token 用量」消歧
        assertTrue(html.contains("API Token"))
        assertTrue(html.contains("picm••••wxyz"))
        assertTrue(html.contains("tokCopy(1, this)"))
        assertTrue(html.contains("Token 用量"))
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

    @Test
    fun `overview page renders sub-cent cost with precision not zero`() {
        // 今日成本 0.0044 元：%.2f 会显示 "0.00"，计费看起来没生效
        val ov = OverviewRow(1L, 0L, 5L, 7944L, 0.0044, 819L, 0L)
        val html = AdminViews.overviewPage(ov, emptyList())
        assertTrue("sub-cent cost must not round to 0.00", html.contains("0.0044"))
    }
}
