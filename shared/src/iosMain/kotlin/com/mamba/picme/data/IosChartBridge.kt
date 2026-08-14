package com.mamba.picme.data

/**
 * Swift → Kotlin 的图表渲染桥协议。
 *
 * Swift 实现（iosApp `ChartRendererBridge`/NSObject）调用 `ChartJsEngine`
 * （JavaScriptCore + chart_bootstrap.js）端侧生成 SVG，把 SVG 经 Swift 自有通道
 * 交给 `ChatViewModel` 作 CHART 消息渲染；[summary] 经 [onResult] 回传给能力层，
 * 作为远程 LLM 的 observation（做文字总结）。
 *
 * SharedBridge 铁律（同 [IosChatSearchBridge] / [IosMediaRepositoryBridge]）：
 * - Swift 实现侧绝不抛异常跨边界（逃逸会 signal 6 / SIGABRT）；
 * - [onResult] **必须**被调用（成功或失败），否则 Kotlin 侧 suspendCancellableCoroutine
 *   永久挂起；失败时回传兜底 summary。
 *
 * 与 Android `ChatRunScriptCapability.Delegate.onDrawChart` 的差异：iOS 能力在组合根
 * 构造（早于 ChatViewModel 存在），不走 Delegate 回调，故 SVG 不跨 K/N 边界，
 * 仅 summary 经 completion 回传（渲染产物留 Swift 侧）。
 *
 * [PRIVACY]：draw_chart 的 labels/values 来自远程 LLM（已聚合统计，非用户媒体文件），
 * 端侧纯渲染——不触发任何媒体上传。
 */
interface IosChartBridge {

    /**
     * 端侧渲染一张图表。
     *
     * @param type 图表类型：bar(柱状) / line(折线) / pie(饼图)
     * @param title 图表标题
     * @param labels 分类/x 轴标签
     * @param values 每个标签对应的数值（与 labels 等长）
     * @param unit 数值单位（如「张」）；无则 null
     * @param onResult summary 回调（回传 LLM）；渲染失败时回传兜底文案
     */
    fun renderChart(
        type: String,
        title: String,
        labels: List<String>,
        values: List<Double>,
        unit: String?,
        onResult: (summary: String) -> Unit
    )
}
