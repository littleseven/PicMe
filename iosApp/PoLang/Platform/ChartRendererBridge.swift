import Foundation
import SharedKit

/// `IosChartBridge` 的 Swift 实现：Kotlin `IosChartCapability`（draw_chart 命令）→ `ChartJsEngine`。
///
/// SharedBridge 铁律（同 `PhSearchBridge`）：本类方法绝不抛异常跨边界——渲染失败时回传
/// 兜底 summary（不产图），`onResult` **必须**被调用（Kotlin 侧 suspendCancellableCoroutine 等待恢复）。
///
/// 渲染产物（SVG）经 [onChart] 静态闭包交给 `ChatViewModel`（主线程）作 CHART 消息渲染，
/// 不跨 K/N 边界；仅 summary 经 `onResult` 回传能力层 → 远程 LLM observation。
@objc final class ChartRendererBridge: NSObject, IosChartBridge {

    static let shared = ChartRendererBridge()

    /// Swift → ChatViewModel 通道：渲染完成回调 (svg, summary)。
    /// `ChatViewModel.configure` 时注入；nil 时仅回传 summary（图卡不显示）。
    static var onChart: ((String, String) -> Void)?

    func renderChart(
        type: String,
        title: String,
        labels: [String],
        values: [KotlinDouble],
        unit: String?,
        onResult: @escaping (String) -> Void
    ) {
        // Kotlin List<Double> 经 K/N 导出为 [KotlinDouble]（NSNumber 子类），
        // 转 [Double] 喂 ChartJsEngine（参照 PhSearchBridge [KotlinLong].int64Value 模式）。
        let doubles = values.map { value -> Double in value.doubleValue }
        // ChartJsEngine.render 为同步 JSContext eval（毫秒级），直接调用。
        guard let r = ChartJsEngine.render(
            type: type, title: title, labels: labels, values: doubles, unit: unit
        ) else {
            onResult("图表「\(title)」生成失败")
            return
        }
        // SVG → ChatViewModel（主线程，对齐 handleUiAction 的 @MainActor 约定）
        if let onChart = ChartRendererBridge.onChart {
            DispatchQueue.main.async { onChart(r.svg, r.summary) }
        }
        onResult(r.summary)
    }
}
