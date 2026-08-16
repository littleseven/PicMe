import Foundation
import SQLite3
import CryptoKit

// MARK: - OptimizeFeedbackLogger（抽卡反馈落库）
//
// 移植自 androidApp `domain/agent/capability/optimize/gacha/OptimizeFeedbackLogger.kt`。
// 自动选优（AiOptimizeService）与用户手选/关闭（编辑器/chat 控制器，其他任务）共用；
// 落库失败只记日志，绝不影响主流程。
//
// 语义偏差（相对 Android）：
// - Android 经 Room DAO（OptimizeFeedbackDao）写入独立 Room 库；iOS 复用 TagDatabase
//   （sqlite3 C API）加 `optimize_feedback` 表，建表语句幂等（CREATE TABLE IF NOT EXISTS，
//   字段逐字对齐 Android OptimizeFeedbackEntity：id/image_key/scene/candidates_json/
//   selected_index/selection_source/created_at）。为避免改动共享的 TagDatabase.swift，
//   建表挂在本扩展内（首次 insert 前兜底执行），写入沿用 TagDatabase 既有串行 queue.sync 惯例。
// - Android candidatesToJson 用 org.json（键序为插入序）；iOS 用 JSONSerialization
//   （.sortedKeys 稳定键序）——JSON 语义等价，供 Phase 2 个性化消费。
final class OptimizeFeedbackLogger {

    static let sourceAuto = "auto"
    static let sourceUser = "user"
    static let sourceDismiss = "dismiss"

    private static let tag = "[PoLang:OptimizeGacha]"

    private let database: TagDatabase?

    /// database 传 nil 时只读空跑（对齐 Android dao == null 早退）。
    init(database: TagDatabase? = TagDatabase.shared) {
        self.database = database
    }

    /// 落一条反馈记录（失败仅日志不抛）。
    ///
    /// - Parameters:
    ///   - imageUri: 图片 URI（本地文件路径；只落 SHA-256 摘要，不存原始路径，spec §7）
    ///   - scene: 识别场景
    ///   - all: 候选卡组
    ///   - selectedIndex: 选中的卡序号；-1 = KeepOriginal / 未选择
    ///   - source: `sourceAuto` / `sourceUser` / `sourceDismiss`
    func log(imageUri: String,
             scene: OptimizeScene,
             all: [ScoredCandidate],
             selectedIndex: Int,
             source: String) {
        guard let database = database else { return }
        database.ensureOptimizeFeedbackTable()
        let ok = database.insertOptimizeFeedback(
            imageKey: OptimizeFeedbackLogger.imageKey(imageUri),
            scene: scene.rawValue,
            candidatesJson: OptimizeFeedbackLogger.candidatesToJson(all),
            selectedIndex: selectedIndex,
            selectionSource: source,
            createdAt: Int64(Date().timeIntervalSince1970 * 1000)
        )
        if !ok {
            NSLog("%@ feedback insert failed", OptimizeFeedbackLogger.tag)
        }
    }

    /// 图片 URI → SHA-256 前 16 位 hex（不存原始路径，spec §7）。
    static func imageKey(_ uri: String) -> String {
        let digest = SHA256.hash(data: Data(uri.utf8))
        let hex = digest.map { byte in
            String(format: "%02x", byte)
        }.joined()
        return String(hex.prefix(16))
    }

    /// 候选卡组 → JSON（含参数、NIMA 分、护栏淘汰标记，供 Phase 2 个性化消费）。
    /// 结构对齐 Android candidatesToJson：index/direction/nimaScore(null 可)/rejected/
    /// rejectReason(null 可)/beauty{smoothing,whitening}/filter/adjustment{6 参数}。
    static func candidatesToJson(_ all: [ScoredCandidate]) -> String {
        let array: [[String: Any]] = all.map { sc -> [String: Any] in
            let preset = sc.candidate.preset
            return [
                "index": sc.candidate.index,
                "direction": sc.candidate.direction,
                "nimaScore": sc.nimaScore.map { score in Double(score) } ?? NSNull(),
                "rejected": sc.rejected,
                "rejectReason": sc.rejectReason ?? NSNull(),
                "beauty": [
                    "smoothing": Double(preset.beauty.smoothing),
                    "whitening": Double(preset.beauty.whitening),
                ],
                "filter": preset.filter.colorFilter,
                "adjustment": [
                    "brightness": Double(preset.adjustment.brightness),
                    "exposure": Double(preset.adjustment.exposure),
                    "contrast": Double(preset.adjustment.contrast),
                    "saturation": Double(preset.adjustment.saturation),
                    "temperature": Double(preset.adjustment.temperature),
                    "tint": Double(preset.adjustment.tint),
                ],
            ]
        }
        guard let data = try? JSONSerialization.data(withJSONObject: array,
                                                     options: [.sortedKeys]) else {
            return "[]"
        }
        return String(data: data, encoding: .utf8) ?? "[]"
    }
}

// MARK: - TagDatabase 扩展（optimize_feedback 表）

extension TagDatabase {

    private static let sqliteTransient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    /// 建 optimize_feedback 表（幂等；schema 逐字对齐 Android Room OptimizeFeedbackEntity）。
    /// 在既有串行 queue 上执行，遵循 TagDatabase.openAndCreateSchema 的 exec 惯例。
    func ensureOptimizeFeedbackTable() {
        queue.sync {
            exec("""
                CREATE TABLE IF NOT EXISTS optimize_feedback (
                    id               INTEGER PRIMARY KEY AUTOINCREMENT,
                    image_key        TEXT    NOT NULL,
                    scene            TEXT    NOT NULL,
                    candidates_json  TEXT    NOT NULL,
                    selected_index   INTEGER NOT NULL,
                    selection_source TEXT    NOT NULL,
                    created_at       INTEGER NOT NULL
                );
                """)
        }
    }

    /// 插入一条反馈记录；返回是否成功（失败仅日志不抛，对齐 Android try/catch 语义）。
    func insertOptimizeFeedback(imageKey: String,
                                scene: String,
                                candidatesJson: String,
                                selectedIndex: Int,
                                selectionSource: String,
                                createdAt: Int64) -> Bool {
        queue.sync {
            guard let handle = db else { return false }
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(
                handle,
                "INSERT INTO optimize_feedback (image_key, scene, candidates_json, selected_index, selection_source, created_at) VALUES (?, ?, ?, ?, ?, ?);",
                -1, &stmt, nil
            ) == SQLITE_OK else {
                assertionFailure("[TagDatabase] prepare insertOptimizeFeedback failed: \(String(cString: sqlite3_errmsg(handle)))")
                return false
            }
            defer { sqlite3_finalize(stmt) }

            sqlite3_bind_text(stmt, 1, imageKey, -1, TagDatabase.sqliteTransient)
            sqlite3_bind_text(stmt, 2, scene, -1, TagDatabase.sqliteTransient)
            sqlite3_bind_text(stmt, 3, candidatesJson, -1, TagDatabase.sqliteTransient)
            sqlite3_bind_int(stmt, 4, Int32(selectedIndex))
            sqlite3_bind_text(stmt, 5, selectionSource, -1, TagDatabase.sqliteTransient)
            sqlite3_bind_int64(stmt, 6, createdAt)

            let rc = sqlite3_step(stmt)
            guard rc == SQLITE_DONE else {
                NSLog("[PoLang:OptimizeGacha] insertOptimizeFeedback step failed rc=%d: %@",
                      rc, String(cString: sqlite3_errmsg(handle)))
                return false
            }
            return true
        }
    }
}
