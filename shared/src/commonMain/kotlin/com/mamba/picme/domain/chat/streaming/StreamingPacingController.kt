package com.mamba.picme.domain.chat.streaming

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * 流式吐字节奏器（豆包风格）：按字符时间轴播放全文快照，而非 append 后端 token。
 *
 * - [onTextSnapshot] 只更新缓冲，立即返回，不触发 UI。
 * - [paceLoop] 按固定速率（[BASE_CHAR_MS]/字）+ 词块（中文 [CHUNK_CJK] 字一跳，标点/空白单字跳）
 *   推进；标点后加 [PUNCT_MS] 停顿，换行后加 [LINE_MS]「落笔」停顿。
 * - [finish] 后光标余闪 [TAIL_BLINK_MS] 再隐藏。
 *
 * 可测性：用 [delay] 驱动节奏，纯 JVM `runTest` 虚拟时间可控（需 runCurrent 触发 initial dispatch）。
 *
 * 2026-08-13 由 `androidApp/.../features/chat/streaming/StreamingPacingController.kt` 下沉至 commonMain，
 * 逻辑零改动（纯 Kotlin，仅依赖 kotlinx.coroutines + kotlin.math）。双端共用。
 */
class StreamingPacingController(
    private val scope: CoroutineScope,
    private val onPaced: (text: String, cursorVisible: Boolean) -> Unit,
) {
    companion object {
        const val BASE_CHAR_MS = 50L
        const val PUNCT_MS = 100L
        const val LINE_MS = 200L
        const val CHUNK_CJK = 2
        const val TAIL_BLINK_MS = 2500L
        const val IDLE_POLL_MS = 50L
        private val PUNCT_CHARS = setOf('，', '。', '？', '！', '：', '；', ',', '?', '!', ':', ';')
    }

    // 注：原 Android 用 @Volatile（JVM 注解）。commonMain 跨平台不支持 @Volatile；
    // 本类设计为单线程使用（scope=MainScope，onTextSnapshot/onPaced 均在 main 调用），
    // 无跨线程可见性需求，故移除。调用方须保证 onTextSnapshot/reset 与 scope 同线程（main）。
    private var latestFullText: String = ""
    private var shownLength: Int = 0
    private var finished: Boolean = false
    private var loopJob: Job? = null

    /** 流式开始：重置缓冲并启动节奏循环。 */
    fun start() {
        loopJob?.cancel()
        finished = false
        latestFullText = ""
        shownLength = 0
        loopJob = scope.launch { paceLoop() }
    }

    /**
     * 来自事件链的全文快照：只更新缓冲，立即返回。
     * 若 [fullText] 不是当前缓冲的连续扩展（回退/新轮），重置已展示长度从 0 重新累计。
     */
    fun onTextSnapshot(fullText: String) {
        val prev = latestFullText
        if (fullText == prev) return
        val isContinuousGrowth = fullText.length > prev.length && fullText.startsWith(prev)
        latestFullText = fullText
        if (!isContinuousGrowth) shownLength = 0
    }

    /** 清空缓冲（供 ToolCallStarted 切换状态文案时协调，避免节奏器用旧全文覆盖）。 */
    fun reset() {
        latestFullText = ""
        shownLength = 0
    }

    /** 轮次完成 / 取消收尾：追平全文、光标余闪 [TAIL_BLINK_MS] 后隐藏、停循环。 */
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
            val full = latestFullText
            val target = full.length
            if (target == 0) {
                delay(IDLE_POLL_MS) // 无内容（思考中/reset 后）：静默
                continue
            }
            if (shownLength < target) {
                val chunkEnd = nextChunkEnd(full, shownLength, target)
                delay(nextDelay(full, shownLength, chunkEnd))
                shownLength = chunkEnd
                onPaced(full.substring(0, shownLength), true)
            } else {
                delay(IDLE_POLL_MS) // 追平：空转等新 token 或 finish
            }
        }
    }

    /** 下一词块末尾：边界字符（标点/空白）单字跳，否则中文/字母连串 [CHUNK_CJK] 字一跳。 */
    private fun nextChunkEnd(full: String, start: Int, target: Int): Int {
        if (start >= target) return start
        val c = full[start]
        return if (isBoundary(c)) start + 1 else min(start + CHUNK_CJK, target)
    }

    /** 本跳延迟：基础 [BASE_CHAR_MS]×字数；若上一字符是标点 +[PUNCT_MS]，换行 +[LINE_MS]。 */
    private fun nextDelay(full: String, start: Int, end: Int): Long {
        var ms = BASE_CHAR_MS * (end - start)
        if (start > 0) {
            val prev = full[start - 1]
            if (isPunct(prev)) ms += PUNCT_MS
            else if (prev == '\n') ms += LINE_MS
        }
        return ms
    }

    private fun isBoundary(c: Char): Boolean = isPunct(c) || c.isWhitespace()

    private fun isPunct(c: Char): Boolean = c in PUNCT_CHARS
}
