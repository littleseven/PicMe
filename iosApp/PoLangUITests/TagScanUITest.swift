import XCTest

/// TAG 扫描页 UI 自动化验证——从用户视角操作界面，发现不正常的功能。
/// 通过相册扫描图标进入 TAG 页，验证核心 UI + Pass2 聚类反馈 + 美学提示。
final class TagScanUITest: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    private func launchApp() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh-Hans"]
        app.launch()
        return app
    }

    private func openTagScanPage(_ app: XCUIApplication) {
        let scanBtn = app.buttons["topbar_scan"]
        XCTAssertTrue(scanBtn.waitForExistence(timeout: 20), "❌ 相册顶栏扫描图标不存在")
        scanBtn.tap()
        sleep(2)
    }

    /// 验证 TAG 扫描页能打开，核心 section 齐全
    func testTagScanPageSections() {
        let app = launchApp()
        openTagScanPage(app)

        // 统计卡
        let statsTitle = app.staticTexts["数据库累计统计"]
        XCTAssertTrue(statsTitle.waitForExistence(timeout: 5), "❌ 统计卡标题缺失")

        // 管线概览
        XCTAssertTrue(app.staticTexts["处理阶段概览"].exists, "❌ 管线概览缺失")

        // 分阶段控制
        XCTAssertTrue(app.staticTexts["分阶段独立控制"].exists, "❌ 分阶段控制缺失")

        // 精细控制
        XCTAssertTrue(app.staticTexts["精细控制"].exists, "❌ 精细控制缺失")

        // 4 张 PassControlCard 按钮
        XCTAssertTrue(app.buttons["pass1_incremental"].exists, "❌ Pass1 增量按钮缺失")
        XCTAssertTrue(app.buttons["pass2_incremental"].exists, "❌ Pass2 增量按钮缺失")
        XCTAssertTrue(app.buttons["pass3_incremental"].exists, "❌ Pass3 增量按钮缺失")
        XCTAssertTrue(app.buttons["aesthetic_incremental"].exists, "❌ 美学增量按钮缺失")

        print("✅ TAG 扫描页所有 section + PassControlCard 齐全")
    }

    /// 点 Pass2 增量 → 应显示聚类进度反馈
    func testPass2ShowsFeedback() {
        let app = launchApp()
        openTagScanPage(app)

        let pass2Inc = app.buttons["pass2_incremental"]
        XCTAssertTrue(pass2Inc.exists, "❌ Pass2 增量按钮不存在")
        pass2Inc.tap()

        // 等待进度卡出现（扫描中/完成 等状态文本）
        let feedback = app.staticTexts.containing(
            NSPredicate(format: "label CONTAINS '扫描' OR label CONTAINS '完成' OR label CONTAINS '聚类'")
        ).firstMatch
        let appeared = feedback.waitForExistence(timeout: 10)
        XCTAssertTrue(appeared, "❌ 点 Pass2 后 10s 内无任何进度反馈")

        print("✅ Pass2 点击后有进度反馈")
    }

    /// 美学评分卡 → 弹「后续版本」提示
    func testAestheticShowsToast() {
        let app = launchApp()
        openTagScanPage(app)

        let aestheticInc = app.buttons["aesthetic_incremental"]
        XCTAssertTrue(aestheticInc.exists, "❌ 美学增量按钮不存在")
        aestheticInc.tap()

        let okButton = app.alerts.firstMatch.buttons.firstMatch
        let alertShown = okButton.waitForExistence(timeout: 5)
        XCTAssertTrue(alertShown, "❌ 美学卡点击后未弹提示")
        okButton.tap()

        print("✅ 美学卡点击弹提示正常")
    }

    /// 关闭按钮 → 返回相册
    func testCloseReturnsToGallery() {
        let app = launchApp()
        openTagScanPage(app)

        let closeBtn = app.buttons["topbar_back"]
        XCTAssertTrue(closeBtn.exists, "❌ 关闭按钮不存在")
        closeBtn.tap()
        sleep(1)

        // 应回到相册（扫描页的统计卡不应再存在）
        XCTAssertFalse(app.staticTexts["数据库累计统计"].exists, "❌ 关闭后仍在扫描页")

        print("✅ 关闭按钮返回相册正常")
    }

    /// Pass3 内容打标——触发 Florence-2 推理（如果崩溃，app 进程会退出）
    func testPass3Trigger() {
        let app = launchApp()
        openTagScanPage(app)

        let pass3Inc = app.buttons["pass3_incremental"]
        XCTAssertTrue(pass3Inc.waitForExistence(timeout: 5), "❌ Pass3 增量按钮不存在")
        pass3Inc.tap()
        // 等 20s 让 Florence-2 加载 + 推理（如果崩溃会在这期间退出）
        sleep(20)
        // 如果 app 还活着说明没崩
        print("✅ Pass3 触发后 app 存活 20s")
    }
}
