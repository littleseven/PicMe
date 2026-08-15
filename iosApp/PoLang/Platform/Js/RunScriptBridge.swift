import Foundation
import SharedKit

/// `IosRunScriptBridge` 的 Swift 实现：`IosRunScriptCapability`（run_gallery_script 命令）
/// → `JsRuntime`（引擎 = `JsCoreEngine`：JavaScriptCore + gallery 只读 handler）端侧执行脚本。
///
/// SharedBridge 铁律（同 `ChartRendererBridge` / `PhSearchBridge`）：本类方法绝不抛异常跨边界，
/// `onResult` **必须**被调用（Kotlin 侧 suspendCancellableCoroutine 等待恢复）。
///
/// `JsRuntime` 懒构建（首次 runScript 时）并复用——bootstrap JS 只 eval 一次；gallery handler
/// 一次性注册。线程安全（NSLock 保护构建）。
@objc final class RunScriptBridge: NSObject, IosRunScriptBridge {

    static let shared = RunScriptBridge()

    private var runtime: JsRuntime?
    private let lock = NSLock()

    /// 懒构建 JsRuntime：JsCoreEngine + 后台 scope（工厂内 Dispatchers.Default）+ Chart 注入 + handler。
    private func ensureRuntime() -> JsRuntime {
        lock.lock()
        defer { lock.unlock() }
        if let runtime = runtime { return runtime }
        let engine = JsCoreEngine()
        // 后台 scope（≠ evalAsync 线程，规避 __bridgeCallAsync 信号量死锁）
        let runtime = IosJsRuntimeSupportKt.createIosJsRuntime(engine: engine, source: "chat")
        // 注入 Chart 图表生成器（bar/line/pie/timeline → SVG）。失败仅告警，不阻断脚本能力
        // （对齐 Android getOrCreateJsRuntime：runCatching { rt.eval(loadChartBootstrapJs()) }）。
        if let chartJs = Self.loadChartBootstrap() {
            _ = runtime.eval(script: chartJs)
        } else {
            NSLog("[PoLang:RunScript] chart_bootstrap.js not found in bundle")
        }
        GalleryScriptHandlers.registerAll(into: runtime)
        self.runtime = runtime
        NSLog("[PoLang:RunScript] JsRuntime ready (handlers=%@)", runtime.handlerNames().joined(separator: ","))
        return runtime
    }

    /// 加载 chart_bootstrap.js（与 ChartJsEngine 同一双路径查找：js/ 子目录或 bundle 根）。
    private static func loadChartBootstrap() -> String? {
        if let url = Bundle.main.url(forResource: "chart_bootstrap", withExtension: "js", subdirectory: "js"),
           let js = try? String(contentsOf: url, encoding: .utf8) {
            return js
        }
        if let url = Bundle.main.url(forResource: "chart_bootstrap", withExtension: "js"),
           let js = try? String(contentsOf: url, encoding: .utf8) {
            return js
        }
        return nil
    }

    func runScript(code: String, onResult: @escaping (String) -> Void) {
        // evalAsync 同步阻塞（内部信号量等 handler）；派到后台队列，绝不阻塞调用线程（可能为 main）。
        // handler 在 JsRuntime 的 Dispatchers.Default scope 执行（≠ 此后台队列线程，无死锁）。
        DispatchQueue.global(qos: .userInitiated).async {
            let result = self.ensureRuntime().evalAsync(code: code, timeoutMs: 5_000)
            // 图表拦截（对齐 Android onRunScript）：脚本 return Chart.x({...}) → {chart:<svg>, summary:<text>}。
            // SVG 经 ChartRendererBridge.onChart 渲染图卡（不喂回 LLM），summary 回传 LLM 做文字总结（省 token）。
            if let obj = result as? JsValue.Obj,
               let chart = obj.entries["chart"] as? JsValue.Str {
                let summary = (obj.entries["summary"] as? JsValue.Str)?.value ?? "已生成图表"
                DispatchQueue.main.async {
                    ChartRendererBridge.onChart?(chart.value, summary)
                }
                onResult(summary)
            } else {
                onResult(result.toJson())
            }
        }
    }
}
