package com.mamba.picme.util.permission

import com.mamba.picme.util.permission.BackgroundScanGuard.IssueType
import com.mamba.picme.util.permission.BackgroundScanGuard.evaluate
import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundScanGuardTest {

    @Test
    fun `all clear and non-MIUI yields no issues`() {
        assertEquals(emptyList<IssueType>(), evaluate(batteryOk = true, notificationsOk = true, isMiui = false))
    }

    @Test
    fun `battery not whitelisted surfaces battery issue`() {
        assertEquals(
            listOf(IssueType.BATTERY_OPTIMIZATION),
            evaluate(batteryOk = false, notificationsOk = true, isMiui = false)
        )
    }

    @Test
    fun `notifications disabled surfaces notifications issue`() {
        assertEquals(
            listOf(IssueType.NOTIFICATIONS),
            evaluate(batteryOk = true, notificationsOk = false, isMiui = false)
        )
    }

    @Test
    fun `MIUI always surfaces autostart issue even when battery and notifications ok`() {
        // MIUI/HyperOS 不暴露自启动读取 API，只要判定为 MIUI 即列入让用户确认。
        assertEquals(
            listOf(IssueType.MIUI_AUTOSTART),
            evaluate(batteryOk = true, notificationsOk = true, isMiui = true)
        )
    }

    @Test
    fun `all missing plus MIUI yields stable ordered list`() {
        assertEquals(
            listOf(IssueType.BATTERY_OPTIMIZATION, IssueType.NOTIFICATIONS, IssueType.MIUI_AUTOSTART),
            evaluate(batteryOk = false, notificationsOk = false, isMiui = true)
        )
    }

    @Test
    fun `battery ordered before notifications before miui`() {
        // 顺序保证 UI 展示稳定。
        val result = evaluate(batteryOk = false, notificationsOk = false, isMiui = true)
        assertEquals(IssueType.BATTERY_OPTIMIZATION, result[0])
        assertEquals(IssueType.NOTIFICATIONS, result[1])
        assertEquals(IssueType.MIUI_AUTOSTART, result[2])
    }
}
