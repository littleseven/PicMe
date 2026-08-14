import XCTest

/// 完整性闸门:iOS 侧 a11y 树 dump。把当前界面可访问元素 dump 成 JSON 打到 stdout
/// (标记 DUMP_JSON:::),由 completeness-check.sh 经 xcodebuild test 输出捕获 → refs/ios/。
/// 默认 dump 相机 idle 态;DUMP_STATE 环境变量传状态名(仅作标签)。
final class CompletenessDumpUITests: XCTestCase {

    var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchEnvironment["UITEST"] = "1"
        app.launch()
    }

    func testDumpCameraIdle() throws {
        // 进相机页(默认相册,点 tab_camera)
        let cameraTab = app.buttons["tab_camera"]
        if cameraTab.waitForExistence(timeout: 10) { cameraTab.tap() }
        XCTAssertTrue(
            app.descendants(matching: .any)["camera_preview"].firstMatch.waitForExistence(timeout: 15),
            "相机预览未出现"
        )
        sleep(2)  // 让 UI 稳定

        let nodes = collectNodes()
        let payload: [String: Any] = ["state": "idle", "nodes": nodes]
        let json = try JSONSerialization.data(withJSONObject: payload, options: [])
        let str = String(data: json, encoding: .utf8) ?? "{}"
        // 打到 stdout(设备测试 stdout 常不显示,作备份);同时写 Documents 供 devicectl 取回
        print("DUMP_JSON:::\(str):::END")
        let docs = try FileManager.default.url(for: .documentDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        try json.write(to: docs.appendingPathComponent("ios-a11y-idle.json"))
        XCTAssertTrue(nodes.count > 0, "未采集到任何 a11y 节点")
    }

    /// 遍历 a11y 树,收集有 label 或 identifier 的元素。
    private func collectNodes() -> [[String: Any]] {
        var seen = Set<String>()
        var out: [[String: Any]] = []
        for el in app.descendants(matching: .any).allElementsBoundByIndex {
            let label = el.label ?? ""
            let id = el.identifier ?? ""
            let text = label.isEmpty ? id : label
            guard !text.isEmpty else { continue }
            let key = "\(text)|\(el.elementType.rawValue)"
            guard !seen.contains(key) else { continue }
            seen.insert(key)
            let f = el.frame
            out.append([
                "label": text, "role": roleString(el),
                "x": f.minX, "y": f.minY, "w": f.width, "h": f.height,
            ])
        }
        return out
    }

    private func roleString(_ el: XCUIElement) -> String {
        switch el.elementType {
        case .button: return "button"
        case .staticText: return "text"
        case .textField, .textView: return "input"
        case .slider: return "slider"
        default: return "generic"
        }
    }
}
