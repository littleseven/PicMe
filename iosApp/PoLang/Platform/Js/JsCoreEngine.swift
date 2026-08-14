import Foundation
import JavaScriptCore
import SharedKit

/// iOS 端 JS 引擎：用系统 `JavaScriptCore` 实现 commonMain `JsEngine` 契约。
///
/// 对齐 Android `QuickJsEngine`（dokar3/quickjs-kt）——两端 `bridge`/`console` API、
/// async 两段式包装、bootstrap JS **完全一致**（脚本可跨端复用）。
///
/// **JSCore 与 QuickJS 的关键差异——async 解法**：
/// dokar3 的 `evaluate` 是 suspend + 真正中断 C 死循环；其 `evaluate` 会 pump 完所有
/// pending job，故 Promise 在 `evaluate` 返回前已 settle。`JavaScriptCore` 的
/// `evaluateScript` 不能中断、也不会跨调用 pump——但其**同步执行段末会排空微任务**
/// （Promise 反应）。故 `__bridgeCallAsync` block 用 `DispatchSemaphore` **同步**等待
/// native handler 完成，并返回一个**已 settle 的 Promise**：JS 侧 `await` 立即 resolve，
/// 整条 async IIFE 在一次 `evaluateScript` 内同步跑完，QuickJS 的两段式
/// `__asyncResult`/`__asyncError` 读回 JS 可**原样复用**。
///
/// **死锁前提**：`JsBridge.dispatchAsync` 在后台协程 scope（Dispatchers.Default）launch；
/// `evaluateScript` 在调用线程同步阻塞信号量——两者必须不同线程（scope 池 ≥2 线程，满足）。
///
/// **超时**：JSCore 无原生求值超时、不能安全中断 `evaluateScript`。Tier 1 gallery 脚本为
/// 协作式（几次 `await bridge.callAsync` + `return`，无死循环），`timeoutMs` 参数仅为
/// API 对齐保留，不做硬中断（与 plan Task 4 注记一致；硬超时留 Tier 3 写操作场景评估）。
///
/// **沙箱**：JSCore 无 LiveConnect，JS 碰不到 Swift/反射；唯一 native 通道是注入的 bridge。
final class JsCoreEngine: NSObject, JsEngine, JsClosable {

    private let tag = "PoLang:JsCore"
    private let virtualMachine = JSVirtualMachine()
    private let context: JSContext

    override init() {
        self.context = JSContext(virtualMachine: virtualMachine)
        super.init()
        context.exceptionHandler = { [tag] _, exception in
            NSLog("[%@] JS error: %@", tag, exception?.description ?? "unknown")
        }
    }

    // MARK: - JsEngine

    func eval(script: String) -> JsValue {
        eval(script: script, timeoutMs: Int64(Self.defaultEvalTimeoutMs))
    }

    func eval(script: String, timeoutMs: Int64) -> JsValue {
        let result = context.evaluateScript(script)
        return JsValueConverter.toJsValue(result)
    }

    func evalAsync(code: String, timeoutMs: Int64) -> JsValue {
        // 第一段：包 async IIFE，.then 把 resolved/rejection 写入全局变量。
        context.evaluateScript(Self.asyncWrapperHead + code + Self.asyncWrapperTail)
        // 第二段：同步读回；rejection 转为 throw（暴露真实 JS 错误）。
        let settled = context.evaluateScript(Self.readAsyncResultJs)
        // 脚本异常（含 rejection 转 throw、语法错误）被 JSCore 捕为 context.exception，
        // evaluateScript 返回 nil。转可读错误串返回——与 Android「错误抛出→capability 捕获→
        // 回传 LLM 文案」终态等价（iOS 桥不跨边界抛，故错误经结果字符串上送）。
        if let exception = context.exception {
            context.exception = nil
            return JsValue.Str(value: "[脚本错误] \(exception.description ?? "unknown")")
        }
        return JsValueConverter.toJsValue(settled)
    }

    func callFunction(name: String, args: KotlinArray<JsValue>) -> JsValue {
        var jsArgs: [Any] = []
        jsArgs.reserveCapacity(Int(args.size))
        for index in 0..<args.size {
            if let item = args.get(index: index) {
                jsArgs.append(JsValueConverter.toAny(item))
            }
        }
        let function = context.objectForKeyedSubscript(name)
        let result = function?.call(withArguments: jsArgs)
        return JsValueConverter.toJsValue(result)
    }

    func installBridge(bridge: JsBridge) {
        // __bridgeCall（同步）：bridge.call(name, args)。
        // 仅 built-in 同步 handler（math.add/string.upper/echo）经此路径；gallery handler 均为 async，
        // 脚本须用 callAsync（dispatchSync 对 async handler 会抛，K/N 非 @Throws 无法 Swift 捕获）。
        // 故契约：脚本只用 bridge.callAsync；本 block 仅供同步 handler，不触发抛。
        let bridgeCall: @convention(block) (String, JSValue) -> Any = { name, arg in
            let jsArg = JsValueConverter.toJsValue(arg)
            let result = bridge.dispatchSync(name: name, args: jsArg)
            return JsValueConverter.toAny(result)
        }

        // __bridgeCallAsync（异步）：bridge.callAsync(name, args) → Promise。
        // 信号量同步等 handler 完成，返回**已 settle 的 Promise**（见类注释 async 解法）。
        // [weak context] 打破 engine↔context↔blocks 循环引用（context 持 blocks，blocks 弱持 context）。
        let bridgeCallAsync: @convention(block) (String, JSValue) -> JSValue = { [weak context] name, arg in
            guard let context = context else { return JSValue(undefinedIn: JSContext()) }
            // 显式 JSValue? 兼容 init(newPromiseIn:) 在不同 SDK 的 failable/非 failable 导入。
            let promise: JSValue? = JSValue(newPromiseIn: context) { resolve, reject in
                let jsArg = JsValueConverter.toJsValue(arg)
                let semaphore = DispatchSemaphore(value: 0)
                var capturedError: JsValue?
                var capturedResult: JsValue?
                let callback = JsCallbackBox { error, result in
                    capturedError = error
                    capturedResult = result
                    semaphore.signal()
                }
                // dispatchAsync 在后台 scope launch（≠ 当前 evaluateScript 线程）
                bridge.dispatchAsync(name: name, args: jsArg, cb: callback)
                semaphore.wait()
                if let error = capturedError {
                    reject?.call(withArguments: [JsValueConverter.toAny(error)])
                } else {
                    resolve?.call(withArguments: [JsValueConverter.toAny(capturedResult ?? JsValue.Null())])
                }
            }
            return promise ?? JSValue(undefinedIn: context)
        }

        // __bridgeList：bridge.list() → handler 名数组。
        let bridgeList: @convention(block) () -> [String] = {
            bridge.names()
        }

        // __consoleLog：console.log(...args)。
        let consoleLog: @convention(block) ([Any]) -> Void = { args in
            let parts = args.map { JsValueConverter.fromAny($0).toJson() }
            NSLog("[PoLang:JsCore] %@", parts.joined(separator: " "))
        }

        context.setObject(bridgeCall, forKeyedSubscript: "__bridgeCall" as NSCopying & NSObjectProtocol)
        context.setObject(bridgeCallAsync, forKeyedSubscript: "__bridgeCallAsync" as NSCopying & NSObjectProtocol)
        context.setObject(bridgeList, forKeyedSubscript: "__bridgeList" as NSCopying & NSObjectProtocol)
        context.setObject(consoleLog, forKeyedSubscript: "__consoleLog" as NSCopying & NSObjectProtocol)

        // bootstrap：包装全局函数为 bridge/console 对象（与 QuickJsEngine BOOTSTRAP_JS 一致）。
        context.evaluateScript(Self.bootstrapJs)
        NSLog("[%@] bridge installed (handlers=%@)", tag, bridge.names().joined(separator: ","))
    }

    // MARK: - JsClosable

    func close() {
        // JSContext/JSVirtualMachine 由 ARC 释放；清理全局引用避免 engine 复用残留。
        context.evaluateScript("globalThis.bridge = undefined; globalThis.__bridgeCall = undefined;")
        NSLog("[%@] closed", tag)
    }

    // MARK: - 常量（与 QuickJsEngine.kt companion 原样一致，双端 JS 共享）

    static let defaultEvalTimeoutMs: Int64 = 5_000

    /// evalAsync 第一段：执行用户代码并把 Promise 落定结果写入全局变量。
    private static let asyncWrapperHead = """
globalThis.__asyncResult = undefined;
globalThis.__asyncError = undefined;
(async function() {
"""

    private static let asyncWrapperTail = """
})().then(
  function(r) { globalThis.__asyncResult = r === undefined ? null : r; },
  function(e) { globalThis.__asyncError = String((e && e.stack) || e); }
);
"""

    /// evalAsync 第二段：同步读回结果；rejection 转为 throw（暴露真实 JS 错误）。
    private static let readAsyncResultJs = """
(function() {
  if (globalThis.__asyncError !== undefined) {
    var m = globalThis.__asyncError;
    globalThis.__asyncError = undefined;
    throw new Error(m);
  }
  var r = globalThis.__asyncResult === undefined ? null : globalThis.__asyncResult;
  globalThis.__asyncResult = undefined;
  return r;
})()
"""

    private static let bootstrapJs = """
globalThis.bridge = {
  call: function(n, a) { return __bridgeCall(n, a); },
  callAsync: function(n, a) { return __bridgeCallAsync(n, a); },
  list: function() { return __bridgeList(); }
};
globalThis.console = { log: function() { __consoleLog(Array.prototype.slice.call(arguments)); } };
"""
}
