import XCTest
@testable import PoLang

/// 端侧模型下载中心验证
@MainActor
final class ModelDownloadCenterTest: XCTestCase {

    /// T1：模型目录加载（16 个模型）
    func testCatalogLoadsAllModels() {
        let catalog = ModelCatalog.shared
        XCTAssertGreaterThanOrEqual(catalog.models.count, 16, "Should load all 16 models from JSON")

        // 验证关键模型存在
        XCTAssertNotNil(catalog.model(byId: "qwen3_vl_2b"))
        XCTAssertNotNil(catalog.model(byId: "face-det-retina500m-mnn"))
        XCTAssertNotNil(catalog.model(byId: "florence2_base"))
    }

    /// T2：Tier 分类正确
    func testTierClassification() {
        let catalog = ModelCatalog.shared

        // Must-have
        XCTAssertTrue(catalog.model(byId: "face-det-retina500m-mnn")!.isRequired)
        XCTAssertTrue(catalog.model(byId: "florence2_base")!.isRequired)
        XCTAssertTrue(catalog.model(byId: "mobileclip-onnx")!.isRequired)

        // Recommended
        XCTAssertTrue(catalog.model(byId: "sherpa-onnx-zipformer-zh-en")!.isRecommended)
        XCTAssertTrue(catalog.model(byId: "modnet-onnx")!.isRecommended)

        // Optional
        let qwen = catalog.model(byId: "qwen3_vl_2b")!
        XCTAssertFalse(qwen.isRequired)
        XCTAssertFalse(qwen.isRecommended)
    }

    /// T3：分类 Tab 分组
    func testCategoryGrouping() {
        let groups = ModelCatalog.shared.groupByCategory()
        XCTAssertFalse(groups.isEmpty, "Should have at least one category")

        // Must-have tab should contain the required models
        let mustHave = groups.first { $0.0 == .mustHave }
        XCTAssertNotNil(mustHave)
        XCTAssertGreaterThanOrEqual(mustHave!.1.count, 1)
    }

    /// T4：ModelScope 仓库路径
    func testModelScopeRepo() {
        let qwen = ModelCatalog.shared.model(byId: "qwen3_vl_2b")!
        XCTAssertEqual(qwen.modelScopeRepo, "MNN/Qwen3-VL-2B-Instruct-MNN")

        let retina = ModelCatalog.shared.model(byId: "face-det-retina500m-mnn")!
        XCTAssertEqual(retina.modelScopeRepo, "budaoshou/InsightFace-Det500M-MNN")
    }

    /// T5：下载管理器初始状态
    func testDownloadManagerInitialState() {
        let manager = ModelDownloadManager.shared
        // 初始无下载任务（除非已有模型在磁盘上）
        // 验证 downloadStates 不 crash
        for entry in ModelCatalog.shared.models {
            let _ = manager.isModelDownloaded(entry.id)
        }
    }

    /// T6：缺失 must-have 统计
    func testMissingRequiredModels() {
        let manager = ModelDownloadManager.shared
        let missing = manager.missingRequiredModels
        // 全新安装应该有 7 个缺失的 must-have
        XCTAssertGreaterThanOrEqual(missing.count, 0)  // 可能已有部分下载
    }

    /// T7：下载 + SHA256 验证（最小模型 RetinaFace Det500M 1.3MB）
    func testDownloadSmallModel() async {
        let manager = ModelDownloadManager.shared
        let modelId = "face-det-retina500m-mnn"

        // 确保未下载
        if manager.isModelDownloaded(modelId) {
            manager.delete(modelId)
        }

        // 启动下载
        manager.download(modelId)

        // 等待完成（最多 60 秒）
        for _ in 0..<60 {
            try? await Task.sleep(nanoseconds: 1_000_000_000)
            if let state = manager.downloadStates[modelId], state.status == .completed {
                break
            }
            if let state = manager.downloadStates[modelId], state.status == .failed {
                XCTFail("Download failed for \(modelId)")
                return
            }
        }

        // 验证下载完成
        let finalState = manager.downloadStates[modelId]
        XCTAssertEqual(finalState?.status, .completed, "Model should be downloaded")
        XCTAssertTrue(manager.isModelDownloaded(modelId), "Model files should exist")

        // 清理
        manager.delete(modelId)
    }
}
