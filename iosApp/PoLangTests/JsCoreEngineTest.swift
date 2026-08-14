import XCTest
@testable import PoLang
import SharedKit

/// JsCoreEngine（JavaScriptCore 实现 commonMain JsEngine）单元测试。
///
/// 覆盖引擎基建三层：
/// 1. eval 算术 → JsValue.Num（JsValueConverter JSValue→JsValue 正向）
/// 2. evalAsync + bridge.callAsync（DispatchSemaphore + Promise 同步 settle + 两段式读回）
/// 3. 异步 handler 经 JsBridge.dispatchAsync 在后台 scope 执行 → 结果回传（不死锁）
///
/// 真机运行（host = PoLang.app，Intel 机 device 构建）。
final class JsCoreEngineTest: XCTestCase {

    /// eval("1 + 2") → JsValue.Num(3)。
    func testEvalArithmetic() {
        let engine = JsCoreEngine()
        let result = engine.eval(script: "1 + 2")
        guard let number = result as? JsValue.Num else {
            XCTFail("expected JsValue.Num, got \(type(of: result))")
            return
        }
        XCTAssertEqual(number.value, 3.0, accuracy: 0.0001)
    }

    /// eval 字符串/布尔/数组转换（JsValueConverter 各分支）。
    func testEvalValueConversion() {
        let engine = JsCoreEngine()
        XCTAssertTrue((engine.eval(script: "\"hello\"") as? JsValue.Str)?.value == "hello")
        XCTAssertTrue((engine.eval(script: "true") as? JsValue.Bool)?.value == true)
        let array = engine.eval(script: "[1, 2, 3]")
        XCTAssertEqual((array as? JsValue.Arr)?.items.count, 3)
    }

    /// evalAsync + bridge.callAsync：注册 stub echo handler，脚本 await 取数并 return，
    /// 验证 Promise+信号量+两段式读回链路通（handler 在后台 scope，evalAsync 线程不死锁）。
    func testBridgeCallAsyncEcho() {
        let engine = JsCoreEngine()
        let runtime = IosJsRuntimeSupportKt.createIosJsRuntime(engine: engine, source: "test")
        runtime.register(handler: TestEchoHandler())

        let result = runtime.evalAsync(
            code: "return await bridge.callAsync('test.echo', {a: 41});",
            timeoutMs: 5_000
        )
        let json = result.toJson()
        // EchoHandler 原样回传 args；JsValue.Num(41.0).toJson() = "41.0"
        XCTAssertTrue(json.contains("\"a\""), "echo 未回传对象字段：\(json)")
        XCTAssertTrue(json.contains("41"), "echo 数值未回传：\(json)")
    }

    /// gallery.summary handler 真实数据通路（端侧 TagDatabase 盘点 → JsValue.Obj 字段齐）。
    /// 仅校验结构与关键字段存在（计数值随相册而变，不硬断言）。
    func testGallerySummaryHandler() {
        let value = GalleryScriptHandlers.buildSummary()
        guard let object = value as? JsValue.Obj else {
            XCTFail("gallery.summary 应返回 Obj，got \(type(of: value))")
            return
        }
        let keys = Set(object.entries.keys)
        XCTAssertTrue(keys.contains("totalMedia"), "缺 totalMedia")
        XCTAssertTrue(keys.contains("totalPhotos"), "缺 totalPhotos")
        XCTAssertTrue(keys.contains("isScanning"), "缺 isScanning")
        XCTAssertTrue(keys.contains("recommendation"), "缺 recommendation")
    }
}

/// 测试用 echo handler：原样回传 args（验证 bridge 通路，不依赖相册数据）。
final class TestEchoHandler: NSObject, NativeHandlerAsync {
    let name = "test.echo"
    func __invoke(args: JsValue, completionHandler: @escaping (JsValue?, (any Error)?) -> Void) {
        completionHandler(args, nil)
    }
}
