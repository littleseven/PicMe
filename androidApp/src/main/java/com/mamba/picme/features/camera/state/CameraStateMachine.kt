package com.mamba.picme.features.camera.state

import com.mamba.picme.core.common.Logger
import java.util.concurrent.atomic.AtomicReference

/**
 * [Day2 状态机硬编码] 相机状态机 —— 密封类枚举所有合法状态
 *
 * 状态空间显式编码，AI 可枚举所有边界情况，不会遗漏。
 * 所有状态转换通过 transition() 方法，非法转换抛异常，App 不崩。
 *
 * 状态：
 * | 状态          | 含义                  | 允许的操作              |
 * |--------------|----------------------|------------------------|
 * | Idle         | 初始/未初始化          | bindCamera             |
 * | Previewing   | 预览中                | capturePhoto, startRecord, unbind |
 * | Capturing    | 拍照中（快门已触发）     | —（等待完成）            |
 * | Processing   | 拍照后处理中            | —（等待完成）            |
 * | Recording    | 视频录制中              | stopRecord, unbind      |
 * | Rebinding    | 重新绑定中（切镜头/比例） | —（等待完成）            |
 * | Error        | 错误状态                | reset                   |
 */
sealed class CameraStateMachine {

    abstract val name: String

    /** 初始状态 */
    data object Idle : CameraStateMachine() {
        override val name = "Idle"
    }

    /** 预览中 */
    data class Previewing(
        val lensFacing: Int,
        val captureMode: Int
    ) : CameraStateMachine() {
        override val name = "Previewing"
    }

    /** 拍照中（快门触发后到回调返回） */
    data class Capturing(
        val lensFacing: Int,
        val captureMode: Int
    ) : CameraStateMachine() {
        override val name = "Capturing"
    }

    /** 拍照后处理中（Bitmap 旋转/裁剪/美颜/保存） */
    data class Processing(
        val lensFacing: Int,
        val captureMode: Int
    ) : CameraStateMachine() {
        override val name = "Processing"
    }

    /** 视频录制中 */
    data class Recording(
        val lensFacing: Int,
        val recording: Recording
    ) : CameraStateMachine() {
        override val name = "Recording"
    }

    /** 重新绑定中（切换镜头/比例时的过渡状态） */
    data class Rebinding(
        val reason: RebindReason
    ) : CameraStateMachine() {
        override val name = "Rebinding"
    }

    /** 错误状态 */
    data class Error(
        val reason: String,
        val previousState: CameraStateMachine
    ) : CameraStateMachine() {
        override val name = "Error"
    }

    enum class RebindReason {
        LENS_FACING_CHANGED,
        ASPECT_RATIO_CHANGED,
        BEAUTY_STRATEGY_CHANGED,
        ENGINE_MODE_CHANGED,
        RECOVERY_FALLBACK
    }
}

/**
 * 线程安全的状态机管理器
 * 所有状态转换通过 AtomicReference CAS 操作，保证原子性
 */
class CameraStateManager {

    companion object {
        private const val TAG = "CameraState"
    }

    private val stateRef = AtomicReference<CameraStateMachine>(CameraStateMachine.Idle)

    fun getState(): CameraStateMachine = stateRef.get()

    /**
     * 尝试状态转换。非法转换时抛出 IllegalStateException。
     * 调用方负责捕获异常，避免 App 崩溃。
     */
    fun transition(newState: CameraStateMachine): Boolean {
        val current = stateRef.get()

        if (!isValidTransition(current, newState)) {
            val msg = "Illegal state transition: ${current.name} → ${newState.name}"
            Logger.e(TAG, msg)
            throw IllegalStateException(msg)
        }

        val success = stateRef.compareAndSet(current, newState)
        if (success) {
            Logger.i(TAG, "State: ${current.name} → ${newState.name}")
        } else {
            Logger.w(TAG, "CAS failed: expected ${current.name}, actual ${stateRef.get().name}")
        }
        return success
    }

    /**
     * 强制设置状态（仅用于错误恢复）
     */
    fun forceSetState(newState: CameraStateMachine) {
        val old = stateRef.getAndSet(newState)
        Logger.w(TAG, "Force state: ${old.name} → ${newState.name}")
    }

    /**
     * 验证状态转换是否合法
     */
    private fun isValidTransition(
        from: CameraStateMachine,
        to: CameraStateMachine
    ): Boolean {
        return when (from) {
            is CameraStateMachine.Idle -> when (to) {
                is CameraStateMachine.Previewing,
                is CameraStateMachine.Error -> true
                else -> false
            }

            is CameraStateMachine.Previewing -> when (to) {
                is CameraStateMachine.Capturing,
                is CameraStateMachine.Recording,
                is CameraStateMachine.Rebinding,
                is CameraStateMachine.Error,
                is CameraStateMachine.Idle -> true
                else -> false
            }

            is CameraStateMachine.Capturing -> when (to) {
                is CameraStateMachine.Processing,
                is CameraStateMachine.Previewing, // 拍照失败回退
                is CameraStateMachine.Error -> true
                else -> false
            }

            is CameraStateMachine.Processing -> when (to) {
                is CameraStateMachine.Previewing,
                is CameraStateMachine.Error -> true
                else -> false
            }

            is CameraStateMachine.Recording -> when (to) {
                is CameraStateMachine.Previewing,
                is CameraStateMachine.Error,
                is CameraStateMachine.Idle -> true
                else -> false
            }

            is CameraStateMachine.Rebinding -> when (to) {
                is CameraStateMachine.Previewing,
                is CameraStateMachine.Error,
                is CameraStateMachine.Idle -> true
                else -> false
            }

            is CameraStateMachine.Error -> when (to) {
                is CameraStateMachine.Idle,
                is CameraStateMachine.Previewing -> true
                else -> false
            }
        }
    }

    /**
     * 检查当前是否允许拍照
     */
    fun canCapture(): Boolean = stateRef.get() is CameraStateMachine.Previewing

    /**
     * 检查当前是否允许开始录制
     */
    fun canStartRecording(): Boolean = stateRef.get() is CameraStateMachine.Previewing

    /**
     * 检查当前是否允许切换镜头/比例
     */
    fun canRebind(): Boolean {
        val state = stateRef.get()
        return state is CameraStateMachine.Previewing || state is CameraStateMachine.Idle
    }

    /**
     * 检查当前是否处于忙碌状态（拍照/处理/录制中）
     */
    fun isBusy(): Boolean {
        return when (stateRef.get()) {
            is CameraStateMachine.Capturing,
            is CameraStateMachine.Processing,
            is CameraStateMachine.Recording,
            is CameraStateMachine.Rebinding -> true
            else -> false
        }
    }
}
