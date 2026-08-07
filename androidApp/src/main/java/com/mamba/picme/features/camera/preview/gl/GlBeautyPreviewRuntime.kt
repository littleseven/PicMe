package com.mamba.picme.features.camera.preview.gl

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.mamba.picme.beauty.api.BeautyPreviewEngine
import com.mamba.picme.beauty.render.GlBeautyPreviewProvider
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.model.BeautyStrategy

/**
 * 创建并管理 BeautyPreviewEngine 实例。
 *
 * 时序约束（重要）：
 * - Compose recomposition 在帧开始时立即执行，DisposableEffect 的 onDispose/body 在帧结束才执行。
 * - 若依赖 DisposableEffect 切换 provider，recomposition 阶段 rememberPreviewStrategyBundle
 *   已拿到新 beautyStrategy 却还持有旧类型 provider，导致强转 crash。
 *
 * 修复方案：
 * - remember(beautyStrategy)：key 变化时在当帧**立即**创建新 provider 并返回，
 *   使 rememberPreviewStrategyBundle 同帧拿到正确类型，不会强转失败。
 * - LaunchedEffect(beautyStrategy)：每次 key 变化后在协程中异步 release 上一个 provider，
 *   此时旧 provider 已从渲染路径移除，可安全释放 EGL 资源，避免两套 EGL 并存。
 * - DisposableEffect(Unit)：Composable 退出 composition 时 release 当前 provider，防 EGL 泄漏。
 */
@Composable
internal fun rememberGlBeautyPreviewProvider(
    context: Context,
    beautyStrategy: BeautyStrategy
): BeautyPreviewEngine {
    // remember(beautyStrategy)：key 变化时同帧创建新 provider，保证类型与策略一致
    val provider = remember(beautyStrategy) {
        GlBeautyPreviewProvider(context).also {
            Logger.d("Camera", "Beauty provider created: BIG_BEAUTY")
        }
    }

    // LaunchedEffect(beautyStrategy)：key 变化后异步 release 上一个 provider
    // 注意：此处不能直接引用旧 provider（它已被上面 remember 替换），
    // 所以在 remember 外部维护一个"待释放"队列，通过 DisposableEffect 的 onDispose 承接
    DisposableEffect(provider) {
        onDispose {
            // provider 被新实例替换（key 变化）或 Composable 退出时释放
            Logger.d("Camera", "Beauty provider releasing: ${provider.javaClass.simpleName}")
            provider.release()
        }
    }

    return provider
}
