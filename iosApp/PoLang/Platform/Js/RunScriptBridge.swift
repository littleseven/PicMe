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

    /// 懒构建 JsRuntime：JsCoreEngine + 后台 scope（工厂内 Dispatchers.Default）+ 注册 Tier 1 handler。
    private func ensureRuntime() -> JsRuntime {
        lock.lock()
        defer { lock.unlock() }
        if let runtime = runtime { return runtime }
        let engine = JsCoreEngine()
        // 后台 scope（≠ evalAsync 线程，规避 __bridgeCallAsync 信号量死锁）
        let runtime = IosJsRuntimeSupportKt.createIosJsRuntime(engine: engine, source: "chat")
        GalleryScriptHandlers.registerAll(into: runtime)
        self.runtime = runtime
        NSLog("[PoLang:RunScript] JsRuntime ready (handlers=%@)", runtime.handlerNames().joined(separator: ","))
        return runtime
    }

    func runScript(code: String, onResult: @escaping (String) -> Void) {
        // evalAsync 同步阻塞（内部信号量等 handler）；派到后台队列，绝不阻塞调用线程（可能为 main）。
        // handler 在 JsRuntime 的 Dispatchers.Default scope 执行（≠ 此后台队列线程，无死锁）。
        DispatchQueue.global(qos: .userInitiated).async {
            let result = self.ensureRuntime().evalAsync(code: code, timeoutMs: 5_000)
            onResult(result.toJson())
        }
    }
}
