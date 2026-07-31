package com.mamba.picme.features.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 待用户确认的诊断修复请求（根因已出，等用户选交付方式）。 */
data class PendingDiagConfirm(
    val jobId: Int,
    val rootCause: String,
    val onResolved: (String?) -> Unit, // "push" | "pr" | "auto" | null(取消)
)

/**
 * 远程诊断「待确认」状态机（仿 WriteConfirmationController，纯 Kotlin 可单测）。
 * 同一时刻最多一个；resolve 后清空。ChatViewModel 在 DIAGNOSED 时 requestConfirm，
 * DiagConfirmSheet 展示根因 + 按钮，用户选 mode → resolve。
 */
class DiagController {
    private val _pending = MutableStateFlow<PendingDiagConfirm?>(null)
    val pending: StateFlow<PendingDiagConfirm?> = _pending.asStateFlow()

    fun requestConfirm(jobId: Int, rootCause: String, onResolved: (String?) -> Unit) {
        _pending.value = PendingDiagConfirm(jobId, rootCause, onResolved)
    }

    /** UI 入口：mode="push"|"pr"|"auto" 确认；null=取消。无 pending 时 no-op。 */
    fun resolve(mode: String?) {
        val cur = _pending.value ?: return
        _pending.value = null
        cur.onResolved(mode)
    }

    /** 流程结束/被打断时清空（不回调）。 */
    fun clear() {
        _pending.value = null
    }
}
