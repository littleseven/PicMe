import XCTest

/// 会话历史侧栏 + 空状态引导 真机验收（spec chat.yaml §2.5 / §4 / §9.1 session_model）。
///
/// 覆盖：
/// - 空状态本地化热词（zh 设备显示中文 chips，修复「热词恒英文」回归）
/// - 侧栏打开/新建会话/搜索过滤/重命名/删除（对标 Android ChatThreadSidebar）
///
/// 约束：不触发真实推理（不发送消息），仅验证 UI 与本地持久化逻辑。
final class ChatHistorySidebarUITests: XCTestCase {

    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launch()

        let gallery = app.descendants(matching: .any)["gallery_grid"].firstMatch
        XCTAssertTrue(gallery.waitForExistence(timeout: 12), "初始页应为相册网格")
        app.buttons["tab_chat"].tap()
        XCTAssertTrue(app.buttons["chat_menu"].waitForExistence(timeout: 12), "应进入 Chat 页")
    }

    /// 任一本地化命中即算存在（设备语言不确定，en/zh-Hans/zh-Hant 三语兜底）
    private func anyElement(_ labels: [String], timeout: TimeInterval = 3) -> XCUIElement {
        for label in labels {
            let el = app.descendants(matching: .any)[label].firstMatch
            if el.exists { return el }
        }
        // 不存在时返回第一个候选（由调用方 waitForExistence 判定）
        return app.descendants(matching: .any)[labels[0]].firstMatch
    }

    // MARK: - 空状态：本地化热词（无历史时）

    func testEmptyStateLocalizedExamples() throws {
        // 有历史消息时无空状态 → 跳过（不清用户数据）
        let sidebar = app.buttons["chat_menu"]
        _ = sidebar
        let tryThese = app.staticTexts["Try these:"].firstMatch
        let tryTheseZh = app.staticTexts["试试这些："].firstMatch
        let tryTheseTw = app.staticTexts["試試這些："].firstMatch
        guard tryThese.waitForExistence(timeout: 2) || tryTheseZh.exists || tryTheseTw.exists else {
            throw XCTSkip("当前会话非空，无空状态引导页")
        }
        // 热词必须本地化：任一非英文版本存在即证明走了查表（修复前恒为英文）
        let zhChip = app.buttons["搜索海边的照片"].firstMatch
        let twChip = app.buttons["搜尋海邊的照片"].firstMatch
        let enChip = app.buttons["Search beach photos"].firstMatch
        XCTAssertTrue(
            zhChip.exists || twChip.exists || enChip.exists,
            "空状态应显示热词 chips"
        )
        // 设备为中文时绝不应显示英文热词
        if tryTheseZh.exists || tryTheseTw.exists {
            XCTAssertFalse(enChip.exists, "中文设备热词不应为英文（i18n 回归）")
        }
    }

    // MARK: - 侧栏：打开 → 新建 → 搜索 → 重命名 → 删除

    func testSidebarLifecycle() throws {
        // 1. 打开侧栏
        app.buttons["chat_menu"].tap()
        let sidebar = app.descendants(matching: .any)["chat_sidebar"].firstMatch
        XCTAssertTrue(sidebar.waitForExistence(timeout: 5), "点菜单应打开会话历史侧栏")

        // 2. 记录现有会话数，新建一个会话
        let before = app.descendants(matching: .any)
            .matching(identifier: "chat_thread_item").count
        app.buttons["chat_sidebar_new"].tap()
        // 新建后侧栏关闭（对齐 Android onNewChat 收起抽屉）
        XCTAssertTrue(sidebar.waitForNonExistence(timeout: 3), "新建会话后侧栏应收起")

        // 3. 重新打开：应多出一个会话，且新会话为当前会话（标题为默认 "New Chat"/"新建聊天"）
        app.buttons["chat_menu"].tap()
        XCTAssertTrue(sidebar.waitForExistence(timeout: 5))
        let after = app.descendants(matching: .any)
            .matching(identifier: "chat_thread_item").count
        XCTAssertEqual(after, before + 1, "新建会话后列表应 +1")
        let newThread = anyElement(["New Chat", "新建聊天"])
        XCTAssertTrue(newThread.waitForExistence(timeout: 3), "新会话应以默认标题出现")

        // 4. 搜索过滤：输入乱码 → 列表清空；清空搜索 → 恢复
        let search = app.textFields["chat_sidebar_search"].firstMatch
        if search.waitForExistence(timeout: 2) {
            search.tap()
            search.typeText("zzz_no_match_zzz")
            XCTAssertEqual(
                app.descendants(matching: .any).matching(identifier: "chat_thread_item").count,
                0, "乱码搜索应过滤掉所有会话"
            )
            // 清空搜索框
            if let text = search.value as? String, !text.isEmpty {
                search.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: text.count))
            }
        }
        XCTAssertEqual(
            app.descendants(matching: .any).matching(identifier: "chat_thread_item").count,
            after, "清空搜索后列表应恢复"
        )

        // 5. 重命名新会话
        let menu = app.descendants(matching: .any)["chat_thread_menu"].firstMatch
        XCTAssertTrue(menu.waitForExistence(timeout: 3), "会话行应有溢出菜单")
        menu.tap()
        let renameItem = anyElement(["Rename", "重命名", "重新命名"])
        XCTAssertTrue(renameItem.waitForExistence(timeout: 3))
        renameItem.tap()
        let renameField = app.alerts.textFields.firstMatch
        XCTAssertTrue(renameField.waitForExistence(timeout: 3), "重命名弹窗应带文本框")
        renameField.typeText("UITest会话")
        let saveBtn = app.alerts.buttons["Save"].firstMatch
        let saveBtnZh = app.alerts.buttons["保存"].firstMatch
        let saveBtnTw = app.alerts.buttons["儲存"].firstMatch
        if saveBtn.exists { saveBtn.tap() }
        else if saveBtnZh.exists { saveBtnZh.tap() }
        else { saveBtnTw.tap() }
        let renamed = app.staticTexts["UITest会话"].firstMatch
        XCTAssertTrue(renamed.waitForExistence(timeout: 3), "重命名后新标题应出现在列表")

        // 6. 删除该会话（当前会话 → 回退 default）；重命名后视图已重建，菜单需重新查询
        let menuAgain = app.descendants(matching: .any)["chat_thread_menu"].firstMatch
        XCTAssertTrue(menuAgain.waitForExistence(timeout: 3))
        menuAgain.tap()
        let deleteItem = anyElement(["Delete chat", "删除聊天", "刪除聊天"])
        XCTAssertTrue(deleteItem.waitForExistence(timeout: 3))
        deleteItem.tap()
        let confirmDelete = app.sheets.buttons["Delete"].firstMatch
        let confirmDeleteZh = app.sheets.buttons["删除"].firstMatch
        let confirmDeleteTw = app.sheets.buttons["刪除"].firstMatch
        if confirmDelete.waitForExistence(timeout: 3) { confirmDelete.tap() }
        else if confirmDeleteZh.exists { confirmDeleteZh.tap() }
        else { confirmDeleteTw.tap() }
        XCTAssertEqual(
            app.descendants(matching: .any).matching(identifier: "chat_thread_item").count,
            before, "删除后会话数应回到新建前"
        )
    }
}
