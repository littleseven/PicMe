package com.mamba.picme.features.chat.streaming

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 流式吐字节奏器：把高频到达的「全文快照」按固定帧节奏逐字平滑回放给 UI。
 *
 * - [onTextSnapshot] 只更新缓冲，立即返回，不触发 UI。
 * - 节奏循环每 [FRAME_MS] 一帧：积压大→步长放大加速追赶（仍逐字不蹦）；
 *   无积压→直接追平如实显示（智能混合）。
 * - 流式中有内容时光标可见；全文无变化超过 [IDLE_CURSOR_TIMEOUT_MS] 后隐藏。
 *
 * 可测性：用 [delay] 驱动节奏（实际设备 ≈60fps），注入 [timeSource] 以便 runTest
 * 虚拟时间控制停顿判断；不依赖 Compose MonotonicFrameClock。
 */
class StreamingPacingController(
    private val scope: CoroutineScope,
    private val onPaced: (text: String, cursorVisible: Boolean) -> Unit,
    private val timeSource: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        const val MIN_STEP = 1
        const val MAX_STEP = 6
        const val BACKLOG_DIVISOR = 8
        const val IDLE_CURSOR_TIMEOUT_MS = 1200L
        const val FRAME_MS = 16L
    }

    @Volatile private var latestFullText: String = ""
    @Volatile private var shownLength: Int = 0
    @Volatile private var lastChangedAtMs: Long = 0L
    @Volatile private var finished: Boolean = false
    private var loopJob: Job? = null

    /** 流式开始：重置缓冲并启动节奏循环。 */
    fun start() {
        loopJob?.cancel()
        finished = false
        latestFullText = ""
        shownLength = 0
        lastChangedAtMs = timeSource()
        loopJob = scope.launch { paceLoop() }
    }

    /**
     * 来自事件链的全文快照：只更新缓冲，立即返回。
     * 若 [fullText] 不是当前缓冲的连续扩展（回退/新轮），重置已展示长度从 0 重新累计。
     */
    fun onTextSnapshot(fullText: String) {
        val prev = latestFullText
        if (fullText == prev) {
            lastChangedAtMs = timeSource()
            return
        }
        val isContinuousGrowth = fullText.length > prev.length && fullText.startsWith(prev)
        latestFullText = fullText
        lastChangedAtMs = timeSource()
        if (!isContinuousGrowth) {
            shownLength = 0
        }
    }

    /** 清空缓冲（供 ToolCallStarted 切换状态文案时协调，避免节奏器用旧全文覆盖）。 */
    fun reset() {
        latestFullText = ""
        shownLength = 0
        lastChangedAtMs = timeSource()
    }

    /** 轮次完成 / 取消收尾：一次性追平全文、隐藏光标、停循环。 */
    fun finish() {
        finished = true
        loopJob?.cancel()
        loopJob = null
        val full = latestFullText
        if (full.isNotEmpty()) {
            shownLength = full.length
            onPaced(full, false)
        }
    }

    private suspend fun paceLoop() {
        while (scope.isActive && !finished) {
            delay(FRAME_MS)
            val full = latestFullText
            val target = full.length
            if (target == 0) continue // 无内容（思考中/reset 后）：静默，不干预 UI
            if (shownLength < target) {
                val backlog = target - shownLength
                val step = (backlog / BACKLOG_DIVISOR).coerceIn(MIN_STEP, MAX_STEP)
                shownLength = (shownLength + step).coerceAtMost(target)
                onPaced(full.substring(0, shownLength), true)
            } else {
                val cursor = timeSource() - lastChangedAtMs <= IDLE_CURSOR_TIMEOUT_MS
                onPaced(full, cursor)
            }
        }
    }
}
