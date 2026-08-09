import Foundation
import SharedKit
import UIKit

/// 组合根：shared 接口的 iOS actual 在此构造并注入。
/// shared 不知道任何 iOS类型（spec §2.3）。各 feature 的实际注入在对应 Task 追加。
@MainActor
final class AppContainer: ObservableObject {
    static let shared = AppContainer()

    /// 相册数据通路（Task 7：IosMediaRepository + PhMediaBridge）
    let mediaRepository: IosMediaRepository

    /// 相册桥（共享给 IosAgentComposition + GalleryViewModel + Camera）
    let mediaBridge: PhMediaBridge

    /// 美颜渲染参数（全局共享，BeautyPanelView ↔ BeautyRenderer 双向绑定）
    @Published var beautyParams = BeautyRenderer.Params()

    /// Chat Agent 桥（Phase 6.2 T6：IosAgentComposition.initialize 后可用）
    private(set) var chatBridge: ChatAgentBridge?

    private init() {
        self.mediaBridge = PhMediaBridge()
        self.mediaRepository = IosMediaRepository(bridge: mediaBridge)
        setupAgentComposition()
    }

    /// Phase 6.2 T6：初始化 iOS Agent 组合根（访客模式）
    private func setupAgentComposition() {
        let deviceId = DeviceIdStore.shared.getOrCreate()
        IosAgentComposition.shared.initialize(
            bridge: mediaBridge,
            deviceId: deviceId
        )
        chatBridge = IosAgentComposition.shared.chatBridge
        // 应用用户保存的模型配置（有自定义模型则覆盖访客默认，无则保持 PICME_SERVER_DEFAULT）
        ModelConfigStore.shared.applyToOrchestrator()
    }
}

/// 设备标识持久化（identifierForVendor + UserDefaults fallback）。
/// 访客模式 X-Device-Id 用，卸载重装会变（plan 风险 8，语义可接受）。
@MainActor
final class DeviceIdStore {
    static let shared = DeviceIdStore()
    private let key = "polang_device_id"

    func getOrCreate() -> String {
        if let saved = UserDefaults.standard.string(forKey: key) {
            return saved
        }
        let id = UIDevice.current.identifierForVendor?.uuidString ?? UUID().uuidString
        UserDefaults.standard.set(id, forKey: key)
        return id
    }
}
