import XCTest

/// 编辑页顶栏灰显问题诊断（2026-08-16 真机反馈「头部功能全部未开放」）：
/// 走 gallery → pager → pager_edit 全链路进编辑器，dump 顶栏按钮 enabled/hittable
/// 状态 + 截图，定位是状态绑定 bug 还是视觉/命中问题。
final class EditorProbeUITests: XCTestCase {

    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["-uitest"]
        addUIInterruptionMonitor(withDescription: "permission alerts") { alert in
            for label in ["Allow Full Access", "Allow", "OK", "允许完全访问", "允许", "好"] {
                let button = alert.buttons[label]
                if button.exists { button.tap(); return true }
            }
            return false
        }
    }

    private func attach(_ name: String) {
        let shot = XCUIScreen.main.screenshot()
        let att = XCTAttachment(screenshot: shot)
        att.name = name
        att.lifetime = .keepAlways
        add(att)
    }

    private func dumpButtons(_ context: String) {
        let desc = app.buttons.debugDescription
        let att = XCTAttachment(string: desc)
        att.name = "buttons_\(context)"
        att.lifetime = .keepAlways
        add(att)
        print("[\(context)] buttons:\n\(desc)")
    }

    func testEditorTopBarProbe() throws {
        app.launch()
        let grid = app.descendants(matching: .any)["gallery_grid"]
        XCTAssertTrue(grid.waitForExistence(timeout: 15), "初始页应为相册网格")

        let firstCell = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH 'cell_'")).firstMatch
        guard firstCell.waitForExistence(timeout: 5) else {
            throw XCTSkip("相册无照片，跳过")
        }
        firstCell.tap()

        let pager = app.descendants(matching: .any)["media_pager"]
        XCTAssertTrue(pager.waitForExistence(timeout: 8), "大图页应出现")

        let edit = app.descendants(matching: .any)["pager_edit"]
        XCTAssertTrue(edit.waitForExistence(timeout: 5), "编辑入口应存在")
        edit.tap()

        // 编辑页就绪：等待顶栏返回键出现
        let back = app.descendants(matching: .any)["topbar_back"]
        XCTAssertTrue(back.waitForExistence(timeout: 10), "编辑页顶栏应出现")
        sleep(2)   // 等加载态收敛

        attach("editor_topbar")
        dumpButtons("editor_topbar")

        // 逐按钮报告 enabled / hittable（ai_optimize 有 accessibilityLabel，语言相关）
        for label in ["AI 一键优化", "AI Optimize"] {
            let btn = app.buttons[label]
            if btn.exists {
                print("[probe] ai_optimize exists=1 enabled=\(btn.isEnabled) hittable=\(btn.isHittable)")
            }
        }
        // undo/redo/done 无 a11y 标识——由 debugDescription 附件人工判读

        // 实点验证：AI 优化应可点且触发状态变化（isProcessing → 按钮短暂禁用或 gacha 条出现）
        for label in ["AI 一键优化", "AI Optimize"] {
            let btn = app.buttons[label]
            if btn.exists && btn.isEnabled {
                btn.tap()
                sleep(3)
                attach("editor_after_ai_optimize")
                dumpButtons("editor_after_ai_optimize")
                break
            }
        }
    }
}
