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
            UserRow(1, "a@x.com", "active", 0L, 3L, 100L, 0.5, null, "picm••••wxyz", true, "device••••1234"),
            UserRow(2, "b@x.com", "active", 0L, 0L, 0L, 0.0, null, "—", false, "—"),
        )
        val html = AdminViews.usersPage(rows, devicesCount = 0L)
        assertTrue(html.contains("a@x.com"))
        assertTrue(html.contains("/admin/users/1"))
        // API Token 列：有明文者显示掩码 + 复制按钮；原用量列改名「Token 用量」消歧
        assertTrue(html.contains("API Token"))
        assertTrue(html.contains("picm••••wxyz"))
        assertTrue(html.contains("tokCopy(1, this)"))
        assertTrue(html.contains("Token 用量"))
        assertTrue(html.contains("Device ID"))
        assertTrue(html.contains("device••••1234"))
        assertTrue(html.contains("未注册设备")) // 二级 Tab 出现
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

    @Test
    fun `bar charts render compact labels on top of each bar`() {
        val series = listOf(
            DayBucket("2026-07-10", 1_500_000L, 0L, 800_000L, 700_000L, 1_500_000L, 1_234.56, 4096L),
            DayBucket("2026-07-11", 1_200L, 0L, 600L, 600L, 1_200L, 0.0044, 2048L),
            DayBucket("2026-07-12", 5L, 1L, 600L, 634L, 1234L, 1.5, 4096L),
        )
        val html = AdminViews.overviewPage(
            OverviewRow(2L, 1L, 5L, 1234L, 1.5, 4096L, 1L),
            series,
        ) + AdminViews.trafficPage(series)
        assertTrue("compact count label for millions", html.contains(">1.5M</text>"))
        assertTrue("compact count label for thousands", html.contains(">1.2k</text>"))
        assertTrue("compact cost label for thousands", html.contains(">1.23k</text>"))
        assertTrue("plain count label", html.contains(">5</text>"))
    }

    @Test
    fun `devices page lists masked ids quota and delete action`() {
        val rows = listOf(
            DeviceRow(1, "abcdef••••7890", 5, 1_700_000_000_000L, 1_700_000_001_000L),
            DeviceRow(2, "zzzzzz••••1111", 100, 1_700_000_000_000L, 1_700_000_002_000L),
        )
        val html = AdminViews.devicesPage(rows, usersCount = 3L, guestLimit = 100)
        assertTrue(html.contains("未注册设备"))
        assertTrue(html.contains("注册用户 (3)")) // 二级 Tab 计数
        assertTrue(html.contains("未注册设备 (2)")) // 二级 Tab 计数
        assertTrue(html.contains("abcdef••••7890"))
        assertTrue(html.contains("devCopy(1, this)"))
        assertTrue(html.contains("/admin/devices/1/delete"))
        assertTrue(html.contains("5 / 100"))
        assertTrue(html.contains("100 / 100")) // 超额行
        assertTrue(html.contains("btn-danger")) // 删除按钮
    }
}
