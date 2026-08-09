import XCTest
@testable import PoLang

final class TagDatabaseScanTests: XCTestCase {
    /// 用临时库测试，避免污染 Documents/polang_tag.db。
    func makeDb() -> TagDatabase {
        let tmp = NSTemporaryDirectory() + "tag_test_\(UUID().uuidString).db"
        return TagDatabase(dbPath: tmp)
    }

    // MARK: media_assets get-or-create
    func testGetOrCreateIsStable() {
        let db = makeDb()
        let id1 = db.getOrCreateMedia(localIdentifier: "L-1", type: "IMAGE",
                                      captureDateMs: 1_000, fileName: "a.jpg")
        let id2 = db.getOrCreateMedia(localIdentifier: "L-1", type: "IMAGE",
                                      captureDateMs: 1_000, fileName: "a.jpg")
        XCTAssertEqual(id1, id2, "同一 localIdentifier 必须返回同一 id")
        XCTAssertGreaterThan(id1, 0)
    }
    func testGetOrCreateDistinct() {
        let db = makeDb()
        let a = db.getOrCreateMedia(localIdentifier: "A", type: "IMAGE", captureDateMs: 1, fileName: "a")
        let b = db.getOrCreateMedia(localIdentifier: "B", type: "IMAGE", captureDateMs: 2, fileName: "b")
        XCTAssertNotEqual(a, b)
    }
    func testUpdateScanFieldsWritesAndCounts() {
        let db = makeDb()
        let id = db.getOrCreateMedia(localIdentifier: "L-1", type: "IMAGE", captureDateMs: 1, fileName: "a")
        db.updateMediaAssetsScanFields(
            mediaId: id, hasFace: true, faceRoiResult: "{\"boxes\":[]}",
            faceFocusY: 0.4, semanticEmbedding: "BASE64",
            lastTagScanPasses: "{\"1\":1234}"
        )
        XCTAssertEqual(db.pass1CoveredMediaIds(), Set([id]))
        let stats = db.scanStats()
        XCTAssertEqual(stats.totalMedia, 1)
        XCTAssertEqual(stats.withFace, 1)
        XCTAssertEqual(stats.withSemantic, 1)
        XCTAssertEqual(stats.faceEmbeddingCount, 0)
        XCTAssertEqual(stats.remainingPass1, 0, "已 Pass1 覆盖，剩余应为 0")
    }
    func testRemainingPass1CountsUnscanned() {
        let db = makeDb()
        _ = db.getOrCreateMedia(localIdentifier: "A", type: "IMAGE", captureDateMs: 1, fileName: "a")
        _ = db.getOrCreateMedia(localIdentifier: "B", type: "IMAGE", captureDateMs: 2, fileName: "b")
        XCTAssertEqual(db.scanStats().remainingPass1, 2)
        XCTAssertEqual(db.scanStats().totalMedia, 2)
    }
    func testAllImageMediaIdsDescByCaptureDate() {
        let db = makeDb()
        let a = db.getOrCreateMedia(localIdentifier: "A", type: "IMAGE", captureDateMs: 100, fileName: "a")
        let b = db.getOrCreateMedia(localIdentifier: "B", type: "IMAGE", captureDateMs: 200, fileName: "b")
        XCTAssertEqual(db.allImageMediaIds(), [b, a], "按 captureDate 降序")
    }

    // MARK: tag_scan_tasks
    func testEnqueueAndPollFifo() {
        let db = makeDb()
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        db.enqueuePass1Tasks(sessionId: "S1", mediaIds: [10, 20, 30], now: now)
        XCTAssertEqual(db.countTasks(sessionId: "S1", status: "PENDING"), 3)
        let first = db.pollNextPending(sessionId: "S1", now: now)
        XCTAssertEqual(first?.mediaId, 10)
        XCTAssertNotNil(first?.taskId)
        XCTAssertEqual(first?.pass, "FACE_DETECTION")
        db.markRunning(taskId: first!.taskId, now: now)
        let second = db.pollNextPending(sessionId: "S1", now: now)
        XCTAssertEqual(second?.mediaId, 20)
    }
    func testMarkCompletedReducesPending() {
        let db = makeDb()
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        db.enqueuePass1Tasks(sessionId: "S1", mediaIds: [1, 2], now: now)
        let t = db.pollNextPending(sessionId: "S1", now: now)!
        db.markRunning(taskId: t.taskId, now: now)
        db.markCompleted(taskId: t.taskId, now: now)
        XCTAssertEqual(db.countTasks(sessionId: "S1", status: "COMPLETED"), 1)
        XCTAssertEqual(db.countTasks(sessionId: "S1", status: "PENDING"), 1)
    }
    func testMarkFailedSetsBackoff() {
        let db = makeDb()
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        db.enqueuePass1Tasks(sessionId: "S1", mediaIds: [1], now: now)
        let t = db.pollNextPending(sessionId: "S1", now: now)!
        db.markFailed(taskId: t.taskId, now: now, errorMessage: "boom", backoffMs: 5_000)
        XCTAssertEqual(db.countTasks(sessionId: "S1", status: "FAILED"), 1)
        XCTAssertNil(db.pollNextPending(sessionId: "S1", now: now + 1_000), "backoff 未到不应 poll")
        XCTAssertNotNil(db.pollNextPending(sessionId: "S1", now: now + 6_000), "backoff 过后可 poll")
    }
    func testPauseCancelSession() {
        let db = makeDb()
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        db.enqueuePass1Tasks(sessionId: "S1", mediaIds: [1, 2, 3], now: now)
        db.pauseSession(sessionId: "S1")
        XCTAssertEqual(db.countTasks(sessionId: "S1", status: "PAUSED"), 3)
        XCTAssertNil(db.pollNextPending(sessionId: "S1", now: now), "PAUSED 不应被 poll")
        db.resumeSession(sessionId: "S1")
        XCTAssertEqual(db.countTasks(sessionId: "S1", status: "PENDING"), 3)
        db.cancelSession(sessionId: "S1")
        XCTAssertEqual(db.countTasks(sessionId: "S1", status: "CANCELLED"), 3)
    }
    func testResetRunningToPending() {
        let db = makeDb()
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        db.enqueuePass1Tasks(sessionId: "S1", mediaIds: [1, 2], now: now)
        let t = db.pollNextPending(sessionId: "S1", now: now)!
        db.markRunning(taskId: t.taskId, now: now)
        XCTAssertEqual(db.countTasks(sessionId: "S1", status: "RUNNING"), 1)
        db.resetRunningToPending(sessionId: "S1")
        XCTAssertEqual(db.countTasks(sessionId: "S1", status: "PENDING"), 2)
    }
    func testUnfinishedSessionDetected() {
        let db = makeDb()
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        XCTAssertNil(db.unfinishedSessionId())
        db.enqueuePass1Tasks(sessionId: "S7", mediaIds: [1], now: now)
        XCTAssertEqual(db.unfinishedSessionId(), "S7")
        db.cancelSession(sessionId: "S7")
        XCTAssertNil(db.unfinishedSessionId(), "全部 CANCELLED 后无未完成 session")
    }
}
