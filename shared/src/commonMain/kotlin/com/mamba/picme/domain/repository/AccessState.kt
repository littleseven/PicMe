package com.mamba.picme.domain.repository

/**
 * 相册访问授权状态（双端范式统一抽象）。
 * Android: Photo Picker / READ_MEDIA_IMAGES；iOS: Full / Limited / AddOnly。
 * 权限请求流程留各端 UI 层，shared 只消费状态。
 */
sealed interface AccessState {
    /** 完整访问（Android 授权 / iOS Full Access） */
    data object Full : AccessState

    /** 受限访问（iOS Limited Access / Android 部分照片授权） */
    data object Limited : AccessState

    /** 已拒绝 */
    data object Denied : AccessState

    /** 仅可添加（iOS AddOnly，Android 无此态） */
    data object AddOnly : AccessState
}
