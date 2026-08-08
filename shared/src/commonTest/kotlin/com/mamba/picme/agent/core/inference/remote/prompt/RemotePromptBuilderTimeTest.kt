package com.mamba.picme.agent.core.inference.remote.prompt

import com.mamba.picme.agent.core.runtime.state.SceneManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

/**
 * [RemotePromptBuilder] 动态时间语义的 KMP 化护栏（java.time → kotlinx-datetime 逐处对齐验收）：
 * - [RemotePromptBuilder.nowString] 格式与旧 java.time 实现逐字节一致
 *   （`yyyy-MM-dd 周X HH:mm`，second=0 时 java.time LocalTime.toString 省略秒）。
 * - 示例时间戳（去年夏天 / 近半年）的区间边界语义（本地时区、毫秒）。
 */
class RemotePromptBuilderTimeTest {

    private val zone = TimeZone.currentSystemDefault()
    private val builder = RemotePromptBuilder(SceneManager.getInstance())

    @Test
    fun `nowString 格式为 yyyy-MM-dd 周X HHmm 无秒`() {
        val text = builder.nowString()
        assertTrue(
            Regex("""^\d{4}-\d{2}-\d{2} 周[一二三四五六日] \d{2}:\d{2}$""").matches(text),
            "nowString 格式漂移（旧 java.time 语义是 second=0 省略秒）：'$text'",
        )
    }

    @Test
    fun `lastYearSummer 区间为去年 6月1日零点 至 8月31日 23点59分59秒999 本地时区`() {
        val (start, end) = builder.exampleTimestamps.lastYearSummer()
        val lastYear = Clock.System.now().toLocalDateTime(zone).year - 1

        val startLocal = Instant.fromEpochMilliseconds(start).toLocalDateTime(zone)
        assertEquals(lastYear, startLocal.year)
        assertEquals(6, startLocal.monthNumber)
        assertEquals(1, startLocal.dayOfMonth)
        assertEquals(0, startLocal.hour)
        assertEquals(0, startLocal.minute)

        val endLocal = Instant.fromEpochMilliseconds(end).toLocalDateTime(zone)
        assertEquals(lastYear, endLocal.year)
        assertEquals(8, endLocal.monthNumber)
        assertEquals(31, endLocal.dayOfMonth)
        assertEquals(23, endLocal.hour)
        assertEquals(59, endLocal.minute)
        assertEquals(59, endLocal.second)
        assertTrue(start < end, "lastYearSummer start 必须早于 end")
    }

    @Test
    fun `pastHalfYear 区间为 6 个月前当月 1 号零点 至今天 23点59分59秒999 本地时区`() {
        val (start, end) = builder.exampleTimestamps.pastHalfYear()
        val today = Clock.System.now().toLocalDateTime(zone).date
        val sixMonthsAgo = today.minus(6, DateTimeUnit.MONTH)

        val startLocal = Instant.fromEpochMilliseconds(start).toLocalDateTime(zone)
        assertEquals(sixMonthsAgo.year, startLocal.year)
        assertEquals(sixMonthsAgo.monthNumber, startLocal.monthNumber)
        assertEquals(1, startLocal.dayOfMonth)
        assertEquals(0, startLocal.hour)
        assertEquals(0, startLocal.minute)

        val endLocal = Instant.fromEpochMilliseconds(end).toLocalDateTime(zone)
        assertEquals(today.year, endLocal.year)
        assertEquals(today.monthNumber, endLocal.monthNumber)
        assertEquals(today.dayOfMonth, endLocal.dayOfMonth)
        assertEquals(23, endLocal.hour)
        assertEquals(59, endLocal.minute)
        assertEquals(59, endLocal.second)
        assertTrue(start < end, "pastHalfYear start 必须早于 end")
    }
}
