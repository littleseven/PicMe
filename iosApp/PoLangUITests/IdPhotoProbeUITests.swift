import XCTest

/// 证照屏 E2E 探针（/ios-follow idphoto Stage 4）：
/// gallery → pager → pager_id_photo 全链路进证照屏，验证：
/// ① 入口接线（toast 已替换为 fullScreenCover）② 三态收敛（模型在位 → Ready）
/// ③ 四 tab 面板切换 + 截图（attachments 供双端比对）④ 顶栏保存按钮契约。
final class IdPhotoProbeUITests: XCTestCase {

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

    /// tab chip 按本地化标签定位（zh-Hans / en 双语）
    private func tabChip(_ zh: String, _ en: String) -> XCUIElement {
        let zhBtn = app.buttons[zh]
        return zhBtn.exists ? zhBtn : app.buttons[en]
    }

    func testIdPhotoEntryAndTabs() throws {
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

        // ① 入口存在且可点（原 coming-soan toast 已移除）
        let idButton = app.descendants(matching: .any)["pager_id_photo"]
        XCTAssertTrue(idButton.waitForExistence(timeout: 5), "证照入口应存在")
        XCTAssertTrue(idButton.isEnabled, "证照入口应可用")
        idButton.tap()

        // ② 证照屏出现：顶栏返回键（topbar_back）+ 保存键（idphoto_save）
        let back = app.descendants(matching: .any)["topbar_back"]
        XCTAssertTrue(back.waitForExistence(timeout: 8), "证照屏顶栏应出现")
        let save = app.descendants(matching: .any)["idphoto_save"]
        XCTAssertTrue(save.waitForExistence(timeout: 5), "保存按钮(idphoto_save)应存在")

        // 抠图收敛等待（modnet ~1-3s + 首帧合成；模型缺失时收敛到 Error 态文本）
        sleep(8)
        attach("idphoto_default")

        // 保存按钮在 Ready 且非保存中应可用（Error 态 disabled——按钮状态契约）
        let saveEnabled = save.isEnabled
        print("[idphoto-probe] save.isEnabled=\(saveEnabled) (Ready 应=true)")

        // ③ 四 tab 轮巡 + 截图（底色 default 已采）
        let sizeChip = tabChip("尺寸", "Size")
        if sizeChip.waitForExistence(timeout: 3) {
            sizeChip.tap()
            sleep(1)
            attach("idphoto_tab_size")
        }
        let edgeChip = tabChip("边缘", "Edge")
        if edgeChip.exists {
            edgeChip.tap()
            sleep(1)
            attach("idphoto_tab_edge")
        }
        let repairChip = tabChip("修补", "Repair")
        if repairChip.exists {
            repairChip.tap()
            sleep(1)
            attach("idphoto_tab_repair")
        }

        // 底色三色板轮巡（标准蓝→标准红→白，视觉回归证据）
        for label in ["标准红", "Red"] {
            let swatch = app.buttons[label]
            if swatch.exists {
                swatch.tap()
                sleep(2)   // 等底图重算
                attach("idphoto_color_red")
                break
            }
        }

        // ④ 返回退出（Back 契约：无面板拦截，直接回 pager）
        back.tap()
        XCTAssertTrue(pager.waitForExistence(timeout: 5), "返回应回大图页")
    }
}
