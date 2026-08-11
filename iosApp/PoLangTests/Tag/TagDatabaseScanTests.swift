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

    // MARK: persons / Pass2 clustering DAO
    func testInsertPersonAndAssign() {
        let db = makeDb()
        let mid1 = db.getOrCreateMedia(localIdentifier: "L-1", type: "IMAGE", captureDateMs: 1, fileName: "a")
        let mid2 = db.getOrCreateMedia(localIdentifier: "L-2", type: "IMAGE", captureDateMs: 2, fileName: "b")
        let emb = Data(repeating: 0, count: 2048) // 512 Float32
        db.insertEmbeddings(mediaId: mid1, embeddings: [emb])
        db.insertEmbeddings(mediaId: mid2, embeddings: [emb])

        let pid = db.insertPerson(name: nil, coverMediaId: mid1, faceCount: 2, isSelf: false)
        XCTAssertGreaterThan(pid, 0)

        let mediaIds = [mid1, mid2]
        db.assignEmbeddingsByMediaIds(mediaIds, personId: pid)
        db.updateFaceIdBatch(mediaIds, faceId: String(pid))

        let map = db.faceIdByLocalIdentifier()
        XCTAssertEqual(map["L-1"], String(pid))
        XCTAssertEqual(map["L-2"], String(pid))

        XCTAssertTrue(db.getUnassignedEmbeddings().isEmpty, "赋值后无未分配 embedding")
    }

    func testClearAndReset() {
        let db = makeDb()
        let mid = db.getOrCreateMedia(localIdentifier: "L-1", type: "IMAGE", captureDateMs: 1, fileName: "a")
        db.insertEmbeddings(mediaId: mid, embeddings: [Data(repeating: 0, count: 2048)])
        let pid = db.insertPerson(name: "Test", coverMediaId: mid, faceCount: 1, isSelf: false)
        db.assignEmbeddingsByMediaIds([mid], personId: pid)
        db.updateFaceIdBatch([mid], faceId: String(pid))

        // 清空
        db.clearAllPersons()
        db.resetAllEmbeddingAssignments()
        db.resetAllFaceIds()

        XCTAssertTrue(db.faceIdByLocalIdentifier().isEmpty, "清空后无 faceId")
        XCTAssertFalse(db.getUnassignedEmbeddings().isEmpty, "reset 后 embedding 重新未分配")
    }

    // MARK: deleteMediaByLocalIdentifiers / reconcileMediaAssets（防线1/2：删图同步清 media_assets）
    func testDeleteMediaByLocalIdentifiersCascades() {
        let db = makeDb()
        let l1 = db.getOrCreateMedia(localIdentifier: "L-1", type: "IMAGE", captureDateMs: 1, fileName: "a")
        let l2 = db.getOrCreateMedia(localIdentifier: "L-2", type: "IMAGE", captureDateMs: 2, fileName: "b")
        _ = db.getOrCreateMedia(localIdentifier: "L-3", type: "IMAGE", captureDateMs: 3, fileName: "c")
        let emb = Data(repeating: 0, count: 2048) // 512 Float32
        db.insertEmbeddings(mediaId: l1, embeddings: [emb])
        db.insertEmbeddings(mediaId: l2, embeddings: [emb])
        let p1 = db.insertPerson(name: nil, coverMediaId: l1, faceCount: 2, isSelf: false)
        db.assignEmbeddingsByMediaIds([l1, l2], personId: p1)

        // 删 L-1：totalMedia 减 1，L-1 的 embedding 级联删，P1 仍有 L-2 → face_count 重算为 1、不删
        db.deleteMediaByLocalIdentifiers(["L-1"])
        let stats = db.scanStats()
        XCTAssertEqual(stats.totalMedia, 2, "删 1 张后剩 2 张")
        XCTAssertEqual(stats.faceEmbeddingCount, 1, "L-1 的 embedding 应级联删除")
        XCTAssertEqual(stats.personCount, 1, "P1 仍有 L-2 的 embedding，不应被删")
    }

    func testDeleteOrphanRemovesEmptyPerson() {
        let db = makeDb()
        let l1 = db.getOrCreateMedia(localIdentifier: "L-1", type: "IMAGE", captureDateMs: 1, fileName: "a")
        let l2 = db.getOrCreateMedia(localIdentifier: "L-2", type: "IMAGE", captureDateMs: 2, fileName: "b")
        let l3 = db.getOrCreateMedia(localIdentifier: "L-3", type: "IMAGE", captureDateMs: 3, fileName: "c")
        let emb = Data(repeating: 0, count: 2048)
        db.insertEmbeddings(mediaId: l1, embeddings: [emb])
        db.insertEmbeddings(mediaId: l2, embeddings: [emb])
        db.insertEmbeddings(mediaId: l3, embeddings: [emb])
        let p1 = db.insertPerson(name: nil, coverMediaId: l1, faceCount: 2, isSelf: false)
        let p2 = db.insertPerson(name: nil, coverMediaId: l3, faceCount: 1, isSelf: false)
        db.assignEmbeddingsByMediaIds([l1, l2], personId: p1)
        db.assignEmbeddingsByMediaIds([l3], personId: p2)
        XCTAssertEqual(db.scanStats().personCount, 2)

        // 删 L-1 + L-2：P1 无 embedding → 被 reconcile 删；P2 保留
        db.deleteMediaByLocalIdentifiers(["L-1", "L-2"])
        let stats = db.scanStats()
        XCTAssertEqual(stats.totalMedia, 1)
        XCTAssertEqual(stats.personCount, 1, "P1 空簇应被删，只剩 P2")
        XCTAssertEqual(stats.faceEmbeddingCount, 1, "只剩 L-3 的 embedding")
    }

    func testReconcileMediaAssetsDropsMissing() {
        let db = makeDb()
        _ = db.getOrCreateMedia(localIdentifier: "L-1", type: "IMAGE", captureDateMs: 1, fileName: "a")
        _ = db.getOrCreateMedia(localIdentifier: "L-2", type: "IMAGE", captureDateMs: 2, fileName: "b")
        _ = db.getOrCreateMedia(localIdentifier: "L-3", type: "IMAGE", captureDateMs: 3, fileName: "c")
        XCTAssertEqual(db.scanStats().totalMedia, 3)

        // 系统里只剩 L-3 → L-1/L-2 是孤儿，应被清
        db.reconcileMediaAssets(keepLocalIdentifiers: ["L-3"])
        let stats = db.scanStats()
        XCTAssertEqual(stats.totalMedia, 1, "孤儿行应被清理")
        XCTAssertEqual(db.allImageMediaIds().count, 1)
    }
}
