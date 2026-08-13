package com.mamba.picme.domain.chat.streaming

import kotlinx.coroutines.MainScope

/**
 * iOS 工厂：封装 [StreamingPacingController] 构造。
 *
 * StreamingPacingController 构造需 Kotlin [kotlinx.coroutines.CoroutineScope]，
 * Swift 不能直接构造 CoroutineScope，故由 iosMain 提供本工厂。
 *
 * 用 [MainScope]：paceLoop 在 main dispatcher 跑 delay（suspension，不阻塞 UI），
 * onPaced 回调在 main 线程触发——Swift 端闭包可直接更新 @MainActor UI 状态。
 */
fun createStreamingPacingController(onPaced: (String, Boolean) -> Unit): StreamingPacingController {
    return StreamingPacingController(scope = MainScope(), onPaced = onPaced)
}
