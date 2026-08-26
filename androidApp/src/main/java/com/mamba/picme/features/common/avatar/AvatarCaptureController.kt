package com.mamba.picme.features.common.avatar

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 头像拍摄目标：人物聚类封面或「我」的头像。
 */
sealed interface AvatarCaptureTarget {
    /** 指定人物聚类的封面 */
    data class Person(val personId: Long) : AvatarCaptureTarget

    /** 「我」的头像（persons.is_self 标记的人物） */
    data object Self : AvatarCaptureTarget
}

/**
 * 头像拍摄来源页：拍摄完成后 popBackStack 自然落回该页（仅作诊断记录，不再驱动返回导航）。
 */
enum class AvatarCaptureOrigin {
    /** 人物页（Pager 页 3）内的人物信息编辑 */
    PEOPLE_PAGE,

    /** 相册页（Pager 页 0）人物分组标题唤起的人物信息编辑 overlay */
    GALLERY_PAGE,

    /** 设置页账号 Hero 卡头像 */
    SETTINGS_PAGE
}

/**
 * 一次待处理的头像拍摄请求。
 *
 * @param beginMs begin 时间戳（仅作诊断/超时参考，不作为照片识别依据）
 */
data class PendingAvatarCapture(
    val target: AvatarCaptureTarget,
    val origin: AvatarCaptureOrigin,
    val beginMs: Long
)

/**
 * 头像拍摄会话控制器（全局单例 state holder，与 `RemotePhotoTracker`/`SceneManager` 同风格）。
 *
 * 相机为 NavHost 全屏路由（`Screen.Camera`，2026-08-26 路由化，此前是主页面 Pager 页 0），
 * 人物编辑页 / 设置 Hero 卡与相机页之间无法靠 NavController 传参，故用一个进程内单例传递
 * 「待拍头像」意图：`begin()` 登记目标与来源 → 调用方 `navigate(Screen.Camera)` →
 * `CameraScreen` 观察 [pending] 进入头像拍摄态（默认前置 + 提示文案）→ 拍照落库后由
 * `AvatarCaptureFinisher` 把新照片设为目标封面并 `clear()`。
 *
 * 返回与取消语义：完成/失败后 `popBackStack` 回来源页（origin 仅作诊断记录，返回栈自然落回
 * 来源页，不再按 origin 分别切页）；离开相机路由（返回键/返回箭头弹栈）且 pending 未被消费时
 * 由 CameraScreen 清除。
 */
object AvatarCaptureController {

    private val _pending = MutableStateFlow<PendingAvatarCapture?>(null)

    /** 当前待处理的头像拍摄请求；null = 非头像拍摄态 */
    val pending: StateFlow<PendingAvatarCapture?> = _pending

    private val _activated = MutableStateFlow(false)

    /**
     * 头像拍摄态是否已在相机页实际激活（前置切换与提示文案已生效）。
     * 作为「页面失活即取消」的前置条件：登记 pending 后到激活前的窗口内
     * （路由进入过渡 / 记忆水合等待），页面失活不得误清 pending。
     */
    val activated: StateFlow<Boolean> = _activated

    /** 登记一次头像拍摄请求；重复调用覆盖旧请求（以最后一次点击为准）。 */
    fun begin(
        target: AvatarCaptureTarget,
        origin: AvatarCaptureOrigin,
        beginMs: Long = System.currentTimeMillis()
    ) {
        _activated.value = false
        _pending.value = PendingAvatarCapture(target, origin, beginMs)
    }

    /** 相机页实际进入头像拍摄态时置位；无 pending 时为 no-op。 */
    fun markActivated() {
        if (_pending.value != null) {
            _activated.value = true
        }
    }

    /** 结束头像拍摄态（完成或取消）。幂等。 */
    fun clear() {
        _pending.value = null
        _activated.value = false
    }
}
