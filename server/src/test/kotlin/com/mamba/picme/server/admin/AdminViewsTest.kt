package com.mamba.picme.server.admin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminViewsTest {

    private fun rangeFixture(days: List<DayBucket> = emptyList(), byModel: List<DimStat> = emptyList()): RangeStats =
        RangeStats(days, byModel, emptyList(), emptyList(), emptyList(), LatencyStats(0, 0, 0),
            DayBucket("合计", 0, 0, 0, 0, 0, 0.0, 0, 0))

    @Test
    fun `overview page renders delta cards trend chart and model share bars`() {
        val ov = OverviewRow(2L, 1L, 5L, 1234L, 1.5, 4096L, 1L, 0L, 0L, 0L, 0.0)
        val range = rangeFixture(
            days = listOf(DayBucket("2026-07-12", 5L, 1L, 600L, 634L, 1234L, 1.5, 4096L, 0L)),
            byModel = listOf(DimStat("glm-5.2", 3L, 1000L, 5.0)),
        )
        val html = AdminViews.overviewPage(ov, range, days = 7, metric = "calls")
        assertTrue(html.contains("今日调用"))
        assertTrue(html.contains("累计"))
        assertTrue(html.contains("<svg"))
        assertTrue(html.contains("模型 Top"))
        assertTrue(html.contains("share-bar-fill"))
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
    fun `traffic page renders range tabs detail table with total row and top lists`() {
        val range = RangeStats(
            days = listOf(DayBucket("2026-07-12", 1L, 0L, 10L, 5L, 15L, 0.1, 100L, 0L)),
            byModel = listOf(DimStat("deepseek-chat", 1L, 15L, 0.1)),
            byProvider = listOf(DimStat("CLOUDFLARE", 1L, 15L, 0.1)),
            topUsers = listOf(TopStat(1, "a***@x.com", 1L, 15L, 0.1)),
            topDevices = emptyList(),
            latency = LatencyStats(1, 120, 120),
            totals = DayBucket("合计", 1L, 0L, 10L, 5L, 15L, 0.1, 100L, 0L),
        )
        val html = AdminViews.trafficPage(range, days = 30, metric = "calls")
        assertTrue(html.contains("每日明细"))
        assertTrue(html.contains("合计"))
        assertTrue(html.contains("2026-07-12"))
        assertTrue(html.contains("构成"))
        assertTrue(html.contains("异常 Top"))
        assertTrue(html.contains("blocked 率"))
    }

    @Test
    fun `overview page renders sub-cent cost with precision not zero`() {
        // 今日成本 0.0044 元：%.2f 会显示 "0.00"，计费看起来没生效
        val ov = OverviewRow(1L, 0L, 5L, 7944L, 0.0044, 819L, 0L, 0L, 0L, 0L, 0.0)
        val html = AdminViews.overviewPage(ov, rangeFixture(), days = 7, metric = "calls")
        assertTrue("sub-cent cost must not round to 0.00", html.contains("0.0044"))
    }

    @Test
    fun `bar charts render compact labels on top of each bar`() {
        val series = listOf(
            DayBucket("2026-07-10", 1_500_000L, 0L, 800_000L, 700_000L, 1_500_000L, 1_234.56, 4096L, 0L),
            DayBucket("2026-07-11", 1_200L, 0L, 600L, 600L, 1_200L, 0.0044, 2048L, 0L),
            DayBucket("2026-07-12", 5L, 1L, 600L, 634L, 1234L, 1.5, 4096L, 0L),
        )
        val ov = OverviewRow(2L, 1L, 5L, 1234L, 1.5, 4096L, 1L, 0L, 0L, 0L, 0.0)
        val byCalls = AdminViews.overviewPage(ov, rangeFixture(days = series), days = 7, metric = "calls")
        assertTrue("compact count label for millions", byCalls.contains(">1.5M</text>"))
        assertTrue("compact count label for thousands", byCalls.contains(">1.2k</text>"))
        assertTrue("plain count label", byCalls.contains(">5</text>"))
        val byCost = AdminViews.overviewPage(ov, rangeFixture(days = series), days = 7, metric = "cost")
        assertTrue("compact cost label for thousands", byCost.contains(">1.23k</text>"))
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

    @Test
    fun `limit-card has centered css so the quota card does not span full viewport width`() {
        // 修复前 .limit-card 无任何 CSS：div("card limit-card") 是 body 直接子元素，
        // 而 body>.cards 居中规则只覆盖 .cards 容器、不覆盖裸 .card，导致「改上限」卡片整宽贴边显示。
        // 此处锁定 .limit-card 必须带居中规则（max-width + auto margin），防回归。
        val html = AdminViews.overviewPage(
            OverviewRow(0L, 0L, 0L, 0L, 0.0, 0L, 0L, 0L, 0L, 0L, 0.0),
            rangeFixture(),
            days = 7,
            metric = "calls",
        )
        val rule = Regex("\\.limit-card\\{[^}]*\\}").find(html)?.value ?: ""
        assertTrue("expected a .limit-card CSS rule to exist", rule.isNotEmpty())
        assertTrue(
            "limit-card must be centered (max-width:1200px + margin auto): ${rule.take(80)}",
            rule.contains("max-width:1200px") && rule.contains("margin:") && rule.contains("auto"),
        )
    }

    @Test
    fun `diag list page renders health bar stats status badges table and auto refresh`() {
        val stats = DiagStats(total = 3, queued = 1, diagnosed = 0, fixRequested = 1, fixed = 1, failed = 0)
        val rows = listOf(
            DiagListRow(1, "QUEUED", "打开相册闪退", "dev12••••abcd", "sha12345abcdef", null, null, false, false, 100L, 100L, null),
            DiagListRow(
                2, "FIXED", "搜索无结果", "—", "sha99999",
                "diag-fix/2", "https://github.com/x/y/compare/main...diag-fix-2", true, true, 50L, 90L, 60L,
            ),
        )
        val activity = DiagWorkerActivity(lastClaimAt = 60_000L, pendingCount = 1, oldestPendingCreatedAt = 60_000L, health = DiagWorkerHealth.ONLINE)
        val html = AdminViews.diagListPage(stats, rows, activity, now = 200_000L, autoSec = 30)

        assertTrue(html.contains("诊断任务"))
        assertTrue(html.contains("状态分布"))
        assertTrue(html.contains("待诊断"))
        assertTrue(html.contains("/admin/diag/1"))
        assertTrue(html.contains("badge-diag-pending")) // QUEUED 徽章
        assertTrue(html.contains("badge-active"))        // FIXED 徽章
        assertTrue(html.contains("worker 在线"))
        assertTrue(html.contains("自动刷新中"))
        assertTrue(html.contains("setInterval")) // 自动轮询脚本
    }

    @Test
    fun `diag list page idle and manual refresh when no auto`() {
        val html = AdminViews.diagListPage(
            DiagStats(0, 0, 0, 0, 0, 0),
            emptyList(),
            DiagWorkerActivity(null, 0, null, DiagWorkerHealth.IDLE),
            now = 0L,
            autoSec = 0,
        )
        assertTrue(html.contains("worker 空闲"))
        assertTrue(html.contains("暂无诊断任务"))
        assertTrue(html.contains("?auto=30"))
        assertFalse(html.contains("setInterval"))
    }

    @Test
    fun `diag detail page renders description root cause bundle fix and timeline`() {
        val d = DiagDetailRow(
            id = 7,
            status = "FIXED",
            description = "自然语言搜索返回空",
            deviceIdMasked = "dev12••••abcd",
            bundleJson = """{"logs":"PoLang:Gallery boom","crashTrace":"at Foo()","appVersion":"1.0.26","gitSha":"sha7","deviceModel":"Pixel","androidVersion":"14"}""",
            gitSha = "sha7",
            rootCause = "NPE at Gallery.kt:88\n修复：加判空",
            fixMode = "push",
            fixBranch = "diag-fix/7",
            compareUrl = "https://github.com/x/y/compare/main...diag-fix-7",
            tested = true,
            workerLog = null,
            createdAt = 100L,
            updatedAt = 200L,
            claimedAt = 150L,
        )
        val html = AdminViews.diagDetailPage(d)
        assertTrue(html.contains("诊断任务 #7"))
        assertTrue(html.contains("自然语言搜索返回空"))
        assertTrue(html.contains("NPE at Gallery.kt:88"))
        assertTrue(html.contains("diag-fix/7"))
        assertTrue(html.contains("已通过")) // tested
        assertTrue(html.contains("PoLang:Gallery boom")) // bundle 日志
        assertTrue(html.contains("Pixel")) // deviceModel
        assertTrue(html.contains("时间线"))
        assertTrue(html.contains("worker 领取")) // timeline claimed
    }

    @Test
    fun `diag list page renders per-row action buttons depending on status`() {
        val stats = DiagStats(total = 3, queued = 1, diagnosed = 0, fixRequested = 0, fixed = 0, failed = 0, archived = 1)
        val rows = listOf(
            DiagListRow(1, "QUEUED", "q", "dev••••", "sha1234567890", null, null, false, false, 100L, 100L, null),
            DiagListRow(2, "ARCHIVED", "a", "dev••••", "sha1234567890", null, null, false, false, 100L, 100L, null),
            DiagListRow(3, "TIMED_OUT", "t", "dev••••", "sha1234567890", null, null, false, false, 100L, 100L, null),
        )
        val activity = DiagWorkerActivity(null, 0, null, DiagWorkerHealth.IDLE)
        val html = AdminViews.diagListPage(stats, rows, activity, now = 200_000L, autoSec = 0)

        // 表头有「操作」列
        assertTrue(html.contains("操作"))
        // QUEUED 行：可废弃、可删除，不可激活
        assertTrue(html.contains("/admin/diag/1/archive"))
        assertTrue(html.contains("/admin/diag/1/delete"))
        assertTrue(!html.contains("/admin/diag/1/activate"))
        // ARCHIVED 行：可激活、可删除，不可废弃
        assertTrue(html.contains("/admin/diag/2/activate"))
        assertTrue(html.contains("/admin/diag/2/delete"))
        assertTrue(!html.contains("/admin/diag/2/archive"))
        // TIMED_OUT 行：可废弃、可激活、可删除
        assertTrue(html.contains("/admin/diag/3/archive"))
        assertTrue(html.contains("/admin/diag/3/activate"))
        // ARCHIVED 徽标文案 + 统计卡「已废弃」
        assertTrue(html.contains("已废弃"))
    }

    @Test
    fun `diag detail page renders actions bar with archive activate delete`() {
        val d = DiagDetailRow(
            id = 7, status = "DIAGNOSE_FAILED", description = "搜索崩溃", deviceIdMasked = "dev••••",
            bundleJson = """{"logs":"x","gitSha":"sha7","appVersion":"1.0.26"}""", gitSha = "sha7",
            rootCause = null, fixMode = null, fixBranch = null, compareUrl = null,
            tested = false, workerLog = "err", createdAt = 100L, updatedAt = 120L, claimedAt = 110L,
        )
        val html = AdminViews.diagDetailPage(d)
        assertTrue(html.contains("/admin/diag/7/archive"))
        assertTrue(html.contains("/admin/diag/7/activate"))
        assertTrue(html.contains("/admin/diag/7/delete"))
    }
}
