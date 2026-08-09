import Foundation
import SharedKit

/// Chat UI 态模型（Identifiable + Codable）。
/// 与 Koog 记忆层（koog_memory_default）是两套：
/// - 本文件管 UI 展示历史全量（Documents/chat_history_default.json）；
/// - Koog 记忆管 LLM 多轮上下文（NSUserDefaults，三不变式裁剪）。
struct ChatMessage: Identifiable, Codable {
    let id: UUID
    let role: Role
    var text: String
    let timestamp: Date
    var isStreaming: Bool
    var mediaIds: [Int64]   // 媒体卡片（assistant 搜索结果），存 Int64（Codable 友好）
    var error: String?      // 错误气泡文案

    enum Role: String, Codable {
        case user, assistant
    }

    init(id: UUID = UUID(), role: Role, text: String, timestamp: Date = Date(),
         isStreaming: Bool = false, mediaIds: [Int64] = [], error: String? = nil) {
        self.id = id
        self.role = role
        self.text = text
        self.timestamp = timestamp
        self.isStreaming = isStreaming
        self.mediaIds = mediaIds
        self.error = error
    }
}
