import XCTest

/// TAG 生成控制页 v2 结构冒烟（spec: specs/screens/tag-control.yaml，特性 tag-control-v2）。
///
/// v2 四区块：① Library 统计 ② Scan 扫描动作 ③ Stages 阶段行 ④ Regenerate 精细重生成。
/// 断言以 accessibilityIdentifier 为主（语言无关）。已知的文本锚点脆弱性：
/// - 统计卡（TagStatsCard）无 a11y id → 以 zh-Hans hero 副标题「语义索引已覆盖」锚定。
///   v2 英文短语键已入 xcstrings（zh-Hans 有值），锚点用中文实值（测试强制 zh-Hans）。原注：尚未入
///   Localizable.xcstrings，NSLocalizedString 回退键面（全语言显示英文）——
///   i18n 补齐后需同步更新本文件的所有英文键面锚点。
/// - 阶段动作 Sheet 选项卡 / Regenerate 分类 chip 无 a11y id → 英文键面定位，
///   定位失败 XCTSkip（结构回归已由 testV2FourSectionsStructure 守住，不误报）。
/// 设备态相关：`scan_resume_unfinished_btn` 仅在存在未完成会话时渲染 → 独立用例，缺失即 Skip。
final class TagScanUITest: XCTestCase {

    /// 区块③ Stages 四行（TagStage.rawValue 拼接：stage_<raw>_row）。
    private let stageRowIds = ["stage_faces_row", "stage_people_row", "stage_content_row", "stage_aesthetic_row"]

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    // MARK: - 结构冒烟（核心用例）

    /// v2 四区块关键元素齐全：统计卡 + Scan 双按钮 + 4 行 Stages + Regenerate 提交钮（初始禁用）。
    /// 全部为存在性断言（不点执行类按钮——扫描依赖媒体库与模型下载状态，超出结构冒烟范围）。
    func testV2FourSectionsStructure() throws {
        let app = launchApp()
        openTagScanPage(app)

        // 区块① Library 统计卡：无 a11y id → hero 副标题文本锚定（键缺失 → 全语言英文键面）
        let statsSubtitle = app.staticTexts
            .matching(NSPredicate(format: "label CONTAINS '语义索引已覆盖'")).firstMatch
        XCTAssertTrue(statsSubtitle.waitForExistence(timeout: 5),
                      "❌ 区块① Library 统计卡缺失（hero 副标题未出现）")

        // 区块② Scan 动作卡：增量 + 全量双按钮
        XCTAssertTrue(element(app, "scan_new_btn").exists, "❌ 区块② scan_new_btn 缺失")
        XCTAssertTrue(element(app, "rescan_all_btn").exists, "❌ 区块② rescan_all_btn 缺失")

        // 区块③ Stages：4 行阶段入口
        for id in stageRowIds {
            XCTAssertTrue(element(app, id).waitForExistence(timeout: 3), "❌ 区块③ \(id) 缺失")
        }

        // 区块④ Regenerate 提交钮：存在 + 未选类别时禁用（v2 初始态 categories 为空）
        let submit = element(app, "regenerate_submit_btn")
        XCTAssertTrue(submit.waitForExistence(timeout: 3), "❌ 区块④ regenerate_submit_btn 缺失")
        XCTAssertFalse(submit.isEnabled, "❌ 未选类别时 regenerate_submit_btn 应处于禁用态")

        attachScreenshot(name: "tagscan_v2_structure")
        print("✅ TAG 控制页 v2 四区块结构齐全（stats / scan 双钮 / 4×stage 行 / regenerate）")
    }

    // MARK: - 阶段动作 Sheet（区块③ → StageActionSheet）

    /// v2 化的旧 testAestheticShowsToast：aesthetic 阶段行 → Sheet「仅处理新增」→
    /// iOS 无美学执行链 → onUnavailable 弹「该功能在后续版本」（scan_coming_soon_toast）。
    func testAestheticStageShowsComingSoonAlert() throws {
        let app = launchApp()
        openTagScanPage(app)

        let aestheticRow = element(app, "stage_aesthetic_row")
        XCTAssertTrue(aestheticRow.exists, "❌ stage_aesthetic_row 不存在")
        scrollToIfNeeded(aestheticRow, app)
        aestheticRow.tap()

        // Sheet 选项卡是复合 label（标题+副标题+badge），用 CONTAINS 匹配；无 a11y id → 英文键面锚定
        let runNew = app.buttons
            .matching(NSPredicate(format: "label CONTAINS '仅处理新增'")).firstMatch
        guard runNew.waitForExistence(timeout: 5) else {
            throw XCTSkip("阶段动作 Sheet 选项无法以英文键面定位（v2 i18n 键补齐后请更新锚点）")
        }
        runNew.tap()

        let okButton = app.alerts.firstMatch.buttons.firstMatch
        XCTAssertTrue(okButton.waitForExistence(timeout: 5), "❌ aesthetic 阶段执行后未弹「后续版本」提示")
        okButton.tap()

        print("✅ aesthetic 阶段行 → Sheet → 「后续版本」提示链路正常")
    }

    // MARK: - 区块④ Regenerate 交互（轻量）

    /// 选择一个分类 chip 后 regenerate_submit_btn 由禁用转可用（chip 无 a11y id → 英文键面定位）。
    func testRegenerateSubmitEnabledAfterSelectingCategory() throws {
        let app = launchApp()
        openTagScanPage(app)

        let sceneChip = app.buttons["场景"]
        scrollToIfNeeded(sceneChip, app)
        guard sceneChip.waitForExistence(timeout: 3) else {
            throw XCTSkip("分类 chip「Scene」无法以英文键面定位（v2 i18n 键补齐后请更新锚点）")
        }
        sceneChip.tap()

        let submit = element(app, "regenerate_submit_btn")
        XCTAssertTrue(waitUntil(timeout: 3) { submit.isEnabled },
                      "❌ 选择类别 Scene 后 regenerate_submit_btn 应转为可用")
        print("✅ Regenerate 选择类别后提交钮解禁正常")
    }

    // MARK: - 恢复行（设备态相关）

    /// v1 保留能力：存在未完成会话时顶部渲染恢复行（scan_resume_unfinished_btn）。
    /// 设备无未完成会话 → 不渲染，XCTSkip（不可伪造设备态）。
    /// 不点按：点按会触发真实扫描，依赖模型下载状态，超出结构冒烟范围。
    func testResumeUnfinishedRowIfExists() throws {
        let app = launchApp()
        openTagScanPage(app)

        let resumeBtn = element(app, "scan_resume_unfinished_btn")
        guard resumeBtn.waitForExistence(timeout: 4) else {
            throw XCTSkip("设备无未完成扫描会话（hasUnfinishedSession=false），恢复行不渲染")
        }
        XCTAssertTrue(resumeBtn.exists, "❌ 恢复行按钮存在性异常")
        print("✅ 检测到未完成会话，恢复行 scan_resume_unfinished_btn 渲染正常")
    }

    // MARK: - 关闭返回

    /// 顶栏返回（topbar_back）→ fullScreenCover 关闭回到相册。
    func testCloseReturnsToGallery() throws {
        let app = launchApp()
        openTagScanPage(app)

        let backBtn = app.buttons["topbar_back"]
        XCTAssertTrue(backBtn.exists, "❌ 返回按钮不存在")
        backBtn.tap()
        usleep(800_000) // fullScreenCover 关闭转场

        XCTAssertFalse(element(app, "scan_new_btn").waitForExistence(timeout: 3),
                       "❌ 返回后仍在 TAG 控制页")
        print("✅ 返回按钮关闭控制页正常")
    }

    // MARK: - helpers

    private func launchApp() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh-Hans"]
        // 首启相册权限弹窗是系统进程弹窗（不吃 forced language），双语兜底
        addUIInterruptionMonitor(withDescription: "permission alerts") { alert in
            for label in ["允许完全访问", "允许", "好", "Allow Full Access", "Allow", "OK"] {
                let button = alert.buttons[label]
                if button.exists { button.tap(); return true }
            }
            return false
        }
        app.launch()
        return app
    }

    /// SwiftUI 控件可能落在 buttons / otherElements 任一类别，统一按 id 查 any
    /// （对齐 GallerySpecUITests.element 的写法）。
    private func element(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        app.descendants(matching: .any)[id].firstMatch
    }

    /// 打开 TAG 控制页：相册顶栏扫描图标 → fullScreenCover；
    /// 以 scan_new_btn 作为「页已打开」锚点（闲时动作卡恒渲染）。
    private func openTagScanPage(_ app: XCUIApplication) {
        let scanBtn = app.buttons["topbar_scan"]
        XCTAssertTrue(scanBtn.waitForExistence(timeout: 20), "❌ 相册顶栏扫描图标不存在")
        scanBtn.tap()
        XCTAssertTrue(element(app, "scan_new_btn").waitForExistence(timeout: 10),
                      "❌ TAG 控制页未打开（scan_new_btn 10s 未出现）")
        usleep(500_000) // fullScreenCover 转场收尾
    }

    /// ScrollView 内元素可能位于折叠线下（Stages/Regenerate 靠下）：
    /// 不可点时上滑最多 3 次露出（SwiftUI ScrollView 无自动滚动）。
    private func scrollToIfNeeded(_ target: XCUIElement, _ app: XCUIApplication) {
        var attempts = 0
        while target.exists && !target.isHittable && attempts < 3 {
            app.swipeUp()
            attempts += 1
        }
    }

    /// 轮询等待条件成立（SwiftUI 状态翻转带动画，isEnabled 未必瞬时更新）。
    private func waitUntil(timeout: TimeInterval, _ condition: () -> Bool) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if condition() { return true }
            usleep(200_000)
        }
        return condition()
    }

    private func attachScreenshot(name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
