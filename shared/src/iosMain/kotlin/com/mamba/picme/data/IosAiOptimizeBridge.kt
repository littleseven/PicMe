package com.mamba.picme.data

/**
 * Swift → Kotlin 的 AI 一键优化桥协议（2026-08-16 抽卡追齐，contracts.md C-G1.4）。
 *
 * 对齐 Android `AiOptimizeCapability`（固定预设轻量优化产 observation）的双执行语义：
 * 本桥只承担 **capability observation** 路径（端侧场景分析 + 固定预设优化 + 结果 DTO）；
 * 抽卡（gacha）由 Swift UI 层在收到 `AgentAction.Success(AiOptimize)` 后另行触发
 * （ChatOptimizeGachaController.draw），不经本桥——与 Android「ViewModel 层分流」一致。
 *
 * SharedBridge 铁律（同 [IosChartBridge]）：
 * - Swift 实现侧绝不抛异常跨边界（逃逸会 signal 6 / SIGABRT）；
 * - [onResult] **必须**被调用（成功或失败）恰好一次，否则 Kotlin 侧
 *   suspendCancellableCoroutine 永久挂起；失败时回传 ok=false + 兜底文案。
 *
 * [PRIVACY]：全链路端侧（场景分析/渲染在 Swift AiOptimizeService 内完成），
 * 不触发任何媒体上传；imageUri 仅在进程内桥接层流转（与 Android AgentCommand 同口径）。
 */
interface IosAiOptimizeBridge {

    /**
     * 端侧固定预设优化（轻量，非抽卡）。
     *
     * Swift 实现语义（对齐 Android AiOptimizeUseCase.optimize → OptimizeRecipeMapper.toResultDto）：
     * 场景分析 → 该场景固定预设 → 渲染 → 产出结果。
     *
     * ⚠️ 回调参数只用 String（K/N 对 lambda 参数位的 Boolean 装箱为 SharedKitBoolean，
     * Swift 侧对齐成本高；参照 [IosChartBridge] 单 String 回调惯例）。
     *
     * @param imageUri 图片标识（LLM 传入：PHAsset localIdentifier / file:// 路径 / 媒体 URI；Swift 侧解析）
     * @param onResult 成功回调：explanation=场景解释句（Swift 侧已本地化），
     *   resultRecipeJson=结果配方 JSON（sourceUri/scene/explanation/recipe 结构，对齐 Android OptimizeResultDto）
     * @param onError 失败回调：message=失败原因（LLM observation / 日志用）
     *
     * **onResult / onError 恰好调用其一且仅一次**。
     */
    fun optimizeFixed(
        imageUri: String,
        onResult: (explanation: String, resultRecipeJson: String) -> Unit,
        onError: (message: String) -> Unit
    )
}
