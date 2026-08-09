import XCTest

/// MNN 端侧推理 · 真机实拍验收（「0 的突破」可视化证据）。
///
/// 启动参数固定：相机页(`-startPage 0`) + MNN 引擎(`-mnnEngine`) + 瘦脸40(`-slim 40`)。
/// 镜头前放置人脸照片 → MNN 两阶段端侧推理(RetinaFace det_500m → 2d106)检测人脸 →
/// BeautyRenderer 应用瘦脸形变。展开 DebugOverlay 使 `face.mnn: 106pts` 等遥测写入截图，
/// 作为「iOS 端 MNN 实时人脸检测 + 瘦脸」的客观证据。
///
/// 真值通道：app 内 DebugOverlay（face.mnn / face.engine.active / camera.fps）。
/// 采集方式：`XCUIScreen.main.screenshot()`——真屏 framebuffer 捕获（Metal 相机预览可见），
/// 与单元测试 host 的 drawHierarchy（Metal 可能黑屏）不同。
final class CameraMnnLiveUITests: XCTestCase {

    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        // 直达相机页 + MNN 引擎 + 瘦脸强度 40（range -50..50，80% 满档，肉眼可辨）
        app.launchArguments = ["-startPage", "0", "-mnnEngine", "-slim", "40"]
        // 防御：相机权限弹窗（已授权时不出现）
        addUIInterruptionMonitor(withDescription: "permission alerts") { alert in
            for label in ["Allow", "OK", "允许", "好"] {
                let button = alert.buttons[label]
                if button.exists { button.tap(); return true }
            }
            return false
        }
        app.launch()
    }

    /// 主验收：MNN 实时检测镜头前人脸 + 瘦脸形变，截图含 DebugOverlay 遥测。
    func testCameraMnnLiveSlim() throws {
        // 1. 相机页就绪（授权 + 渲染）
        let preview = app.descendants(matching: .any)["camera_preview"].firstMatch
        XCTAssertTrue(preview.waitForExistence(timeout: 20), "相机预览应就绪（授权可能异步）")

        // 2. 轻点预览中心：触发对焦 + 驱动 interruption monitor（若权限弹窗）
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.6)).tap()

        // 3. 等 MNN 异步加载模型（initDetector 在 queue.async）+ 检测镜头前人脸
        sleep(8)

        // 4. 展开 DebugOverlay，使 face.mnn: 106pts / face.engine.active: MNN 等遥测入屏
        let summary = app.descendants(matching: .any)["debug_summary"].firstMatch
        if summary.waitForExistence(timeout: 3) {
            summary.tap()
            sleep(1)  // 等展开动画 + 遥测刷新
        }

        // 5. 采集（MNN + 瘦脸 40）
        attach("camera_mnn_live_slim40")

        // 6. 再采一帧（证明持续检测、fps 在跳）
        sleep(3)
        attach("camera_mnn_live_slim40_b")
    }

    // MARK: - helpers

    private func attach(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
