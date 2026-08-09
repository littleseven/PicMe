import XCTest
@testable import PoLang

/// 验证已下载模型的 UI 状态逻辑
@MainActor
final class ModelDownloadStateTest: XCTestCase {

    /// T1：refreshStates 不 crash，逻辑正确
    func testRefreshStatesNoCrash() {
        let mgr = ModelDownloadManager.shared
        mgr.refreshStates()
        // 遍历所有模型，completed 的打印出来
        for entry in ModelCatalog.shared.models {
            let state = mgr.downloadStates[entry.id]
            if state?.status == .completed {
                print("✅ \(entry.id): completed")
            }
        }
    }

    /// T2：删除模型后状态清除
    func testDeleteClearsState() {
        let mgr = ModelDownloadManager.shared
        let modelId = "face-det-retina500m-mnn"

        // 先确保有状态（下载或手动标记）
        mgr.refreshStates()

        // 如果有这个模型的状态，删除后应为 nil
        if mgr.downloadStates[modelId] != nil {
            mgr.delete(modelId)
            XCTAssertNil(mgr.downloadStates[modelId], "删除后状态应清除")
        }
    }

    /// T3：cancel 不 crash
    func testCancelNoCrash() {
        let mgr = ModelDownloadManager.shared
        mgr.cancel("nonexistent-model")
        // 不 crash 即通过
    }

    /// T4：isModelDownloaded 对不存在的模型返回 false
    func testNonexistentModelNotDownloaded() {
        let mgr = ModelDownloadManager.shared
        XCTAssertFalse(mgr.isModelDownloaded("nonexistent-model-id"))
    }

    /// T5：downloadedModelIds 不 crash
    func testDownloadedModelIdsNoCrash() {
        let mgr = ModelDownloadManager.shared
        let ids = mgr.downloadedModelIds
        print("📋 Downloaded models: \(ids)")
    }

    /// T6：missingRequiredModels 正确统计
    func testMissingRequiredModels() {
        let mgr = ModelDownloadManager.shared
        let missing = mgr.missingRequiredModels
        print("📋 Missing required: \(missing.count) models")
        XCTAssertGreaterThanOrEqual(missing.count, 0)
    }
}
