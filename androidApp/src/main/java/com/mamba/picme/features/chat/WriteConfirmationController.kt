package com.mamba.picme.features.chat

import com.mamba.picme.agent.core.model.command.CommandRisk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * JS 写操作待确认请求（capability.dispatch 触发，ChatScreen 弹确认框）。
 * 同一时刻最多一个（[CapabilityDispatchHandler] 的确认互斥锁保证串行）。
 *
 * @property previewUris 涉及媒体的缩略图 URI 预览（前几个，供确认框核实删除目标）
 * @property onResolved UI 确认（true）/ 拒绝（false）回调
 */
data class PendingWriteConfirmation(
    val method: String,
    val risk: CommandRisk,
    val targetCount: Int,
    val previewUris: List<String> = emptyList(),
    val onResolved: (Boolean) -> Unit,
)

/**
 * capability.dispatch 写确认状态管理（从 ChatViewModel 抽出，纯 Kotlin 可单测）。
 *
 * 核心不变式——「脚本已死，确认不再生效」：
 * - [onScriptEnded] 后在途确认一律拒绝（弹窗消失）；
 * - 脚本未运行时 [request] 立即拒绝（不弹窗）。
 *
 * 防「孤儿确认」：eval 超时只取消 evaluate 协程，async handler 仍存活在 bridge scope；
 * 没有本机制时用户在脚本超时后点「确认」会真实执行写操作，而 LLM 已收到 SCRIPT_TIMEOUT。
 */
class WriteConfirmationController {

    private val _pending = MutableStateFlow<PendingWriteConfirmation?>(null)
    val pending: StateFlow<PendingWriteConfirmation?> = _pending.asStateFlow()

    /** 是否有脚本正在 eval（onRunScript 进入/退出 eval 时维护）。 */
    @Volatile
    private var scriptRunning = false

    fun onScriptStarted() {
        scriptRunning = true
    }

    /** 脚本结束（正常/超时/取消统一走这里）：标记停止并拒绝在途确认。 */
    fun onScriptEnded() {
        scriptRunning = false
        resolve(false)
    }

    /** UI 确认/拒绝入口（ChatScreen 确认框按钮回调）。 */
    fun resolve(confirmed: Boolean) {
        val current = _pending.value ?: return
        _pending.value = null
        current.onResolved(confirmed)
    }

    /**
     * 挂起等待用户确认。脚本未在运行 → 立即返回 false（不弹窗）；
     * 等待期间脚本结束/协程被取消 → finally 清理弹窗状态。
     */
    suspend fun request(
        method: String,
        risk: CommandRisk,
        targetCount: Int,
        previewUris: List<String>,
    ): Boolean {
        if (!scriptRunning) return false
        val deferred = CompletableDeferred<Boolean>()
        _pending.value = PendingWriteConfirmation(method, risk, targetCount, previewUris) { confirmed ->
            deferred.complete(confirmed)
        }
        return try {
            deferred.await()
        } finally {
            // 超时/取消/脚本结束路径也要清掉弹窗，避免残留确认框
            _pending.value = null
        }
    }
}
