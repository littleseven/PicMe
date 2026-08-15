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

    // MARK: - Tier 2：query / meta / parseQueryFilter

    /// parseQueryFilter：JS filter 对象 → GalleryQueryFilter（字段映射 + 默认 limit）。
    func testParseQueryFilter() {
        let filter = GalleryScriptHandlers.parseQueryFilter(
            JsValue.Obj(entries: [
                "label": JsValue.Str(value: "猫"),
                "hasFace": JsValue.Bool(value: true),
                "limit": JsValue.Num(value: 10),
            ])
        )
        XCTAssertEqual(filter.label, "猫")
        XCTAssertEqual(filter.hasFace, true)
        XCTAssertEqual(filter.limit, 10)
        XCTAssertNil(filter.ocr)
    }

    /// gallery.query 结果形状：{ids: Arr, total: Num}（确定性，不依赖相册内容）。
    func testGalleryQueryShape() {
        let result = GalleryScriptHandlers.buildQueryResult(filter: GalleryQueryFilter())
        guard let object = result as? JsValue.Obj else {
            XCTFail("gallery.query 应返回 Obj，got \(type(of: result))")
            return
        }
        XCTAssertNotNil(object.entries["ids"], "缺 ids")
        XCTAssertNotNil(object.entries["total"], "缺 total")
    }

    /// media.meta 缺失 id → 无行（handler 据此返回 null）。
    func testMediaMetaMissing() {
        XCTAssertNil(TagDatabase.shared.mediaRow(id: -1), "id=-1 不应命中任何媒体")
    }

    // MARK: - Tier 3：intersect（纯计算，确定性） / face.cluster（形状）

    /// gallery.intersect：集合交/并/差（纯端侧计算，不依赖相册数据）。
    func testIntersectComputation() {
        let numList: ([Int]) -> JsValue = { ids in
            JsValue.Arr(items: ids.map { JsValue.Num(value: Double($0)) })
        }
        func idsOf(_ result: JsValue) -> [Int] {
            guard let obj = result as? JsValue.Obj,
                  let arr = obj.entries["ids"] as? JsValue.Arr else { return [] }
            return arr.items.compactMap { ($0 as? JsValue.Num).map { Int($0.value) } }
        }
        // intersect
        let inter = GalleryScriptHandlers.buildIntersect(args: JsValue.Obj(entries: [
            "idsA": numList([1, 2, 3]),
            "idsB": numList([2, 3, 4]),
            "op": JsValue.Str(value: "intersect"),
        ]))
        XCTAssertEqual(idsOf(inter), [2, 3], "intersect 应为 [2,3]")
        // union
        let union = GalleryScriptHandlers.buildIntersect(args: JsValue.Obj(entries: [
            "idsA": numList([1, 2]),
            "idsB": numList([2, 3]),
            "op": JsValue.Str(value: "union"),
        ]))
        XCTAssertEqual(idsOf(union), [1, 2, 3], "union 应为 [1,2,3]（保序去重）")
        // diff
        let diff = GalleryScriptHandlers.buildIntersect(args: JsValue.Obj(entries: [
            "idsA": numList([1, 2, 3]),
            "idsB": numList([2]),
            "op": JsValue.Str(value: "diff"),
        ]))
        XCTAssertEqual(idsOf(diff), [1, 3], "diff 应为 [1,3]")
    }

    /// face.cluster 结果形状：关键字段齐（计数值随相册，不硬断言）。
    func testFaceClusterShape() {
        let value = GalleryScriptHandlers.buildFaceCluster(args: JsValue.Obj(entries: [:]))
        guard let object = value as? JsValue.Obj else {
            XCTFail("face.cluster 应返回 Obj，got \(type(of: value))")
            return
        }
        let keys = Set(object.entries.keys)
        XCTAssertTrue(keys.contains("clusterCount"))
        XCTAssertTrue(keys.contains("namedCount"))
        XCTAssertTrue(keys.contains("totalEmbeddings"))
        XCTAssertTrue(keys.contains("topPersons"))
    }

    // MARK: - run_gallery_script 脚本产图（相册健康度报告场景）

    /// 「相册健康度报告」形态脚本（多 handler await + return Chart.timeline）经 chat 沙盒：
    /// Chart 全局须可用（chat_bootstrap 注入），SVG 经 ChartRendererBridge.onChart 渲染图卡，
    /// summary（非 SVG/非报错）回传 LLM。对齐 Android onRunScript 图表拦截。
    func testRunScriptChartReturn() {
        let origOnChart = ChartRendererBridge.onChart
        defer { ChartRendererBridge.onChart = origOnChart }
        let chartExpectation = XCTestExpectation(description: "onChart called")
        let resultExpectation = XCTestExpectation(description: "onResult called")
        var gotSvg: String?
        var gotResult = ""
        ChartRendererBridge.onChart = { svg, _ in
            gotSvg = svg
            chartExpectation.fulfill()
        }
        RunScriptBridge.shared.runScript(code: """
        const s = await bridge.callAsync('gallery.summary', {});
        const t = await bridge.callAsync('tag.scan_status', {});
        return Chart.timeline({
          title: '相册健康度',
          labels: ['已打标', '未打标'],
          values: [s.labeledCount, s.unlabeledCount],
        });
        """) { result in
            gotResult = result
            resultExpectation.fulfill()
        }
        wait(for: [chartExpectation, resultExpectation], timeout: 10)
        XCTAssertTrue(gotSvg?.contains("<svg") ?? false, "SVG 未经 onChart 渲染；result=\(gotResult)")
        XCTAssertFalse(gotResult.contains("[脚本错误]"), "脚本报错（Chart 未注入？）：\(gotResult)")
        XCTAssertFalse(gotResult.contains("<svg"), "应回传 summary 而非 SVG 本体：\(gotResult)")
    }
}

/// 测试用 echo handler：原样回传 args（验证 bridge 通路，不依赖相册数据）。
final class TestEchoHandler: NSObject, NativeHandlerAsync {
    let name = "test.echo"
    func __invoke(args: JsValue, completionHandler: @escaping (JsValue?, (any Error)?) -> Void) {
        completionHandler(args, nil)
    }
}
