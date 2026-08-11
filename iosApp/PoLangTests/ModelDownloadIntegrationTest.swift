import XCTest
import Combine
@testable import PoLang

/// 真机网络集成测试：验证「下载进度实时上报」端到端可用。
///
/// 背景：async/await `session.download(for:)` 完全不回调 `didWriteData`（实测 0 次），
/// 导致进度条恒 0。修复后走经典 downloadTask + delegate，本测试用真实 ModelScope
/// 下载证明：单连接（小文件）与分块并行（大文件）两条路径都有中间进度回调。
///
/// ⚠️ 依赖网络与 ModelScope CDN，仅真机/开发环境运行。
@MainActor
final class ModelDownloadIntegrationTest: XCTestCase {

    private var cancellables = Set<AnyCancellable>()

    /// I1：小文件单连接路径——det_500m（~1.2MB）有中间进度，最终落盘
    func testSingleFileDownloadReportsProgress() async throws {
        let mgr = ModelDownloadManager.shared
        let modelId = "face-det-retina500m-mnn"
        mgr.delete(modelId)

        var progressSamples: [Double] = []
        mgr.$downloadStates
            .compactMap { $0[modelId]?.progress }
            .sink { progressSamples.append($0) }
            .store(in: &cancellables)

        mgr.download(modelId)

        let status = await waitForTerminalStatus(mgr, modelId: modelId, timeout: 60)
        XCTAssertEqual(status, .completed, "下载应完成，实际=\(String(describing: status))")
        XCTAssertTrue(mgr.isModelDownloaded(modelId), "文件应已落盘")
        XCTAssertTrue(progressSamples.contains { $0 > 0 && $0 < 1 },
                      "应有 (0,1) 区间的中间进度回调，实际样本=\(progressSamples)")
        XCTAssertEqual(progressSamples.last, 1.0)
    }

    /// I2：大文件分块并行路径——glintr100（~248MB，单文件 > 32MB 阈值）有中间进度，
    /// 完成后 SHA256 校验通过（落盘即完整文件）
    func testParallelDownloadReportsProgress() async throws {
        let mgr = ModelDownloadManager.shared
        let modelId = "face-embedding-glint360k-r100-onnx"
        mgr.delete(modelId)

        var progressSamples: [Double] = []
        mgr.$downloadStates
            .compactMap { $0[modelId]?.progress }
            .sink { progressSamples.append($0) }
            .store(in: &cancellables)

        mgr.download(modelId)

        let status = await waitForTerminalStatus(mgr, modelId: modelId, timeout: 300)
        XCTAssertEqual(status, .completed, "下载应完成，实际=\(String(describing: status))")
        XCTAssertTrue(mgr.isModelDownloaded(modelId), "文件应已落盘（.part 已转正）")
        XCTAssertTrue(progressSamples.contains { $0 > 0 && $0 < 1 },
                      "并行下载应有 (0,1) 区间的中间进度回调，样本数=\(progressSamples.count)")

        // .part 临时文件不应残留
        let partUrl = mgr.modelsDir
            .appendingPathComponent(modelId)
            .appendingPathComponent("glintr100.mnn.part")
        XCTAssertFalse(FileManager.default.fileExists(atPath: partUrl.path), ".part 应已转正")
    }

    /// 轮询直到终态或超时
    private func waitForTerminalStatus(
        _ mgr: ModelDownloadManager, modelId: String, timeout: TimeInterval
    ) async -> DownloadStatus? {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if let status = mgr.downloadStates[modelId]?.status {
                switch status {
                case .completed, .failed, .cancelled:
                    return status
                default:
                    break
                }
            }
            try? await Task.sleep(nanoseconds: 500_000_000)
        }
        return mgr.downloadStates[modelId]?.status
    }
}
