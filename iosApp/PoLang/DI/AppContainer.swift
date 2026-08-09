import Foundation
import SharedKit

/// 组合根：shared 接口的 iOS actual 在此构造并注入。
/// shared 不知道任何 iOS类型（spec §2.3）。各 feature 的实际注入在对应 Task 追加。
@MainActor
final class AppContainer: ObservableObject {
    static let shared = AppContainer()

    /// 相册数据通路（Task 7：IosMediaRepository + PhMediaBridge）
    let mediaRepository: IosMediaRepository

    /// 美颜渲染参数（全局共享，BeautyPanelView ↔ BeautyRenderer 双向绑定）
    @Published var beautyParams = BeautyRenderer.Params()

    private init() {
        self.mediaRepository = IosMediaRepository(bridge: PhMediaBridge())
    }
}
