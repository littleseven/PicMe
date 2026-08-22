import XCTest
import CryptoKit
@testable import PoLang

/// 校验 `ModelDownloadManager.verifyDownloadedFile`（单流下载转正前终检，
/// 对齐 Android `verifyDownloadedFile`）：size + SHA256 任一不符 → 删除文件 + 返回 false。
/// 网络路径不可注入，按纯函数形态直测（临时目录 + 写假文件 + 篡改 size/hash）。
final class ModelDownloadIntegrityTest: XCTestCase {

    private var tempDir: URL!

    override func setUp() {
        super.setUp()
        tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("integrity-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: tempDir)
        tempDir = nil
        super.tearDown()
    }

    // MARK: - Helpers

    private func writeTempFile(_ data: Data, name: String = "fake_model.bin") -> URL {
        let url = tempDir.appendingPathComponent(name)
        FileManager.default.createFile(atPath: url.path, contents: data)
        return url
    }

    private func sha256Hex(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    private func fileExists(_ url: URL) -> Bool {
        FileManager.default.fileExists(atPath: url.path)
    }

    // MARK: - Cases

    /// T1：size + SHA256 全符 → true，文件保留（正常下载转正）
    func testPassesWhenSizeAndShaMatch() {
        let data = Data("hello polang integrity".utf8)
        let file = writeTempFile(data)
        let info = ModelFileInfo(name: "fake_model.bin",
                                 size: Int64(data.count),
                                 sha256: sha256Hex(data))

        XCTAssertTrue(ModelDownloadManager.verifyDownloadedFile(file, info: info))
        XCTAssertTrue(fileExists(file), "校验通过不应删文件")
    }

    /// T2：size 不符 → false + 文件被删（单流下载标 FAILED 的判定源）
    func testSizeMismatchDeletesFileAndFails() {
        let data = Data((0..<2048).map { byte in UInt8(byte % 251) })
        let file = writeTempFile(data)
        let info = ModelFileInfo(name: "fake_model.bin",
                                 size: Int64(data.count) + 1,  // 篡改期望 size
                                 sha256: sha256Hex(data))

        XCTAssertFalse(ModelDownloadManager.verifyDownloadedFile(file, info: info))
        XCTAssertFalse(fileExists(file), "size 不符应删除已下载文件")
    }

    /// T3：SHA256 不符（size 恰好相符）→ false + 文件被删
    func testShaMismatchDeletesFileAndFails() {
        let data = Data("corrupt me".utf8)
        let file = writeTempFile(data)
        let wrongSha = String(repeating: "0", count: 64)  // 与真实 hash 必不同
        let info = ModelFileInfo(name: "fake_model.bin",
                                 size: Int64(data.count),
                                 sha256: wrongSha)

        XCTAssertFalse(ModelDownloadManager.verifyDownloadedFile(file, info: info))
        XCTAssertFalse(fileExists(file), "SHA256 不符应删除已下载文件")
    }

    /// T4：API 无文件清单（info=nil）→ 无从校验，存在即完整（对齐既有语义），文件保留
    func testNilInfoSkipsVerification() {
        let file = writeTempFile(Data("any bytes".utf8))

        XCTAssertTrue(ModelDownloadManager.verifyDownloadedFile(file, info: nil))
        XCTAssertTrue(fileExists(file), "无清单信息不应删文件")
    }

    /// T5：SHA256 期望为空串 → 只校验 size（API 有 size 时），文件保留
    func testEmptyShaChecksSizeOnly() {
        let data = Data("size only".utf8)
        let file = writeTempFile(data)
        let info = ModelFileInfo(name: "fake_model.bin",
                                 size: Int64(data.count),
                                 sha256: "")

        XCTAssertTrue(ModelDownloadManager.verifyDownloadedFile(file, info: info))
        XCTAssertTrue(fileExists(file))
    }

    /// T6：空文件（0 字节，如下载中断残留）→ false + 文件被删
    func testEmptyFileFailsAndDeleted() {
        let file = writeTempFile(Data())
        let info = ModelFileInfo(name: "fake_model.bin", size: 0, sha256: "")

        XCTAssertFalse(ModelDownloadManager.verifyDownloadedFile(file, info: info))
        XCTAssertFalse(fileExists(file), "空文件应删除")
    }
}
