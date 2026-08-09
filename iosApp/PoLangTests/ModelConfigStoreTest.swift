import XCTest
@testable import PoLang
import SharedKit

/// Model Center 持久化 + 配置生效验证
@MainActor
final class ModelConfigStoreTest: XCTestCase {

    override func setUp() {
        super.setUp()
        // 清理 UserDefaults + 清空 store 单例
        UserDefaults.standard.removeObject(forKey: "model_configs")
        UserDefaults.standard.removeObject(forKey: "selected_model_id")
        // 清空 shared store 中残留的配置（跨测试隔离）
        let store = ModelConfigStore.shared
        for c in store.configs {
            UserDefaults.standard.set("[]", forKey: "model_configs")
        }
        store.load()
    }

    /// T0：默认状态 = PICME_SERVER_DEFAULT（访客模式）
    func testDefaultIsGuestMode() {
        let store = ModelConfigStore.shared
        store.load()  // 强制重新读
        let config = store.activeConfig()
        XCTAssertEqual(config.modelId, "deepseek-v4-flash")
        XCTAssertEqual(config.baseUrl, "https://api.polang.net/")
        XCTAssertFalse(config.isConfigured, "Guest mode should not be configured (no API key)")
    }

    /// T1：添加自定义模型 → activeConfig 切换
    func testAddModelSwitchesActiveConfig() {
        let store = ModelConfigStore.shared
        // 清空已有配置
        for c in store.configs { store.remove(uniqueKey: c.uniqueKey) }

        let provider = (RemoteModelConfig.companion.PROVIDERS as? [RemoteModelProvider])!
            .first { $0.providerId == "deepseek-official" }!
        let modelId = "deepseek-v4-flash"
        store.add(provider: provider, modelId: modelId, apiKey: "sk-test-123")

        XCTAssertEqual(store.configs.count, 1)
        XCTAssertTrue(store.configs.first!.isConfigured)

        store.select(modelId: modelId)
        let active = store.activeConfig()
        XCTAssertEqual(active.modelId, modelId)
        XCTAssertEqual(active.apiKey, "sk-test-123")
        XCTAssertEqual(active.baseUrl, "https://api.deepseek.com/")
        XCTAssertTrue(active.isConfigured)
    }

    /// T2：删除所有自定义模型 → 回退访客模式
    func testRemoveAllFallsBackToGuest() {
        let store = ModelConfigStore.shared
        let provider = (RemoteModelConfig.companion.PROVIDERS as? [RemoteModelProvider])!
            .first { $0.providerId == "kimi-official" }!
        store.add(provider: provider, modelId: "kimi-k2.6", apiKey: "sk-test")

        XCTAssertEqual(store.configs.count, 1)
        store.remove(uniqueKey: store.configs.first!.uniqueKey)
        XCTAssertTrue(store.configs.isEmpty)

        let active = store.activeConfig()
        XCTAssertEqual(active.baseUrl, "https://api.polang.net/")
    }

    /// T3：持久化 — 添加后重启（load）仍能恢复
    func testPersistenceAcrossLoad() {
        let store = ModelConfigStore.shared
        for c in store.configs { store.remove(uniqueKey: c.uniqueKey) }

        let provider = (RemoteModelConfig.companion.PROVIDERS as? [RemoteModelProvider])!
            .first { $0.providerId == "tencent-tokenhub" }!
        store.add(provider: provider, modelId: "kimi-k2.6", apiKey: "sk-persist-test")

        // 重新 load（模拟重启）
        store.load()
        XCTAssertTrue(store.configs.contains { $0.apiKey == "sk-persist-test" })
    }

    /// T4：PROVIDERS 非空（K/N 导出验证）
    func testProvidersExported() {
        let providers = RemoteModelConfig.companion.PROVIDERS as? [RemoteModelProvider] ?? []
        XCTAssertGreaterThanOrEqual(providers.count, 3, "Should have at least 3 providers")
        XCTAssertTrue(providers.contains { $0.providerId == "deepseek-official" })
        XCTAssertTrue(providers.contains { $0.providerId == "kimi-official" })
        XCTAssertTrue(providers.contains { $0.providerId == "tencent-tokenhub" })
    }
}
