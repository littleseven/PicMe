import Foundation

/// 会话线程（侧栏列表项）。
///
/// 对齐 Android ChatSessionEntity + ChatThreadUi：标题 / 更新时间 / 最后消息预览。
struct ChatThread: Codable, Identifiable {
    var id: String { sessionId }
    let sessionId: String
    var title: String
    var updatedAt: Date
    var lastMessagePreview: String

    init(sessionId: String, title: String, updatedAt: Date = Date(), lastMessagePreview: String = "") {
        self.sessionId = sessionId
        self.title = title
        self.updatedAt = updatedAt
        self.lastMessagePreview = lastMessagePreview
    }
}

/// 聊天 UI 历史多会话持久化。
///
/// 布局（Documents/）：
/// - `chat_sessions.json`：会话索引（[ChatThread]）；
/// - `chat_history_<sessionId>.json`：每会话消息（default 沿用老文件名，天然迁移老数据）。
///
/// 与 Koog 记忆层是两套（见 ChatMessage 注释）：本 store 管 UI 展示历史，重启后恢复；
/// Koog 记忆经 IosKoogMessageMemoryStore（NSUserDefaults，按 koog_memory_<sessionId> 分键）管 LLM 多轮上下文。
@MainActor
final class ChatHistoryStore {
    static let shared = ChatHistoryStore()
    static let defaultSessionId = "default"

    private let sessionsFileName = "chat_sessions.json"
    private let currentSessionKey = "polang_chat_current_session_id"

    private var sessionsFileURL: URL {
        Self.documents.appendingPathComponent(sessionsFileName)
    }

    private static var documents: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    private func messagesFileURL(sessionId: String) -> URL {
        Self.documents.appendingPathComponent("chat_history_\(sessionId).json")
    }

    // MARK: - 会话索引

    /// 读取会话列表（updatedAt 倒序，对齐 Android 侧栏排序）。
    func loadThreads() -> [ChatThread] {
        guard let data = try? Data(contentsOf: sessionsFileURL),
              let threads = try? JSONDecoder().decode([ChatThread].self, from: data) else { return [] }
        return threads.sorted { $0.updatedAt > $1.updatedAt }
    }

    private func saveThreads(_ threads: [ChatThread]) {
        do {
            let data = try JSONEncoder().encode(threads)
            try data.write(to: sessionsFileURL, options: .atomic)
        } catch {
            print("ChatHistoryStore: saveThreads failed — \(error)")
        }
    }

    /// 新建/更新会话（按 sessionId upsert）。
    func upsertThread(_ thread: ChatThread) {
        var threads = loadThreads()
        if let idx = threads.firstIndex(where: { $0.sessionId == thread.sessionId }) {
            threads[idx] = thread
        } else {
            threads.append(thread)
        }
        saveThreads(threads)
    }

    /// 删除会话记录及其消息文件。
    func deleteThread(sessionId: String) {
        saveThreads(loadThreads().filter { $0.sessionId != sessionId })
        try? FileManager.default.removeItem(at: messagesFileURL(sessionId: sessionId))
    }

    // MARK: - 当前会话 ID（UserDefaults 持久化，对齐 Android DataStore）

    /// 恢复当前会话：校验会话仍存在，已删除则回退 default（对齐 Android restoreLastSessionId）。
    var currentSessionId: String {
        get {
            let saved = UserDefaults.standard.string(forKey: currentSessionKey) ?? Self.defaultSessionId
            guard saved != Self.defaultSessionId else { return Self.defaultSessionId }
            let exists = loadThreads().contains { $0.sessionId == saved }
            if !exists {
                UserDefaults.standard.set(Self.defaultSessionId, forKey: currentSessionKey)
                return Self.defaultSessionId
            }
            return saved
        }
        set {
            UserDefaults.standard.set(newValue, forKey: currentSessionKey)
        }
    }

    // MARK: - 消息（每会话文件）

    /// 消息编解码经 ChatMessage 自定义 init(from:)：optimize_candidates 消息的 gacha payload
    /// 随消息整体 Codable 落盘；老 JSON 无该字段（decodeIfPresent）或结构漂移时
    /// 该字段被静默丢弃（nil）——消息退化为普通文本气泡，不崩（chat.yaml §17 persistence）。
    func loadMessages(sessionId: String) -> [ChatMessage] {
        guard let data = try? Data(contentsOf: messagesFileURL(sessionId: sessionId)) else { return [] }
        return (try? JSONDecoder().decode([ChatMessage].self, from: data)) ?? []
    }

    func saveMessages(sessionId: String, messages: [ChatMessage]) {
        do {
            let data = try JSONEncoder().encode(messages)
            try data.write(to: messagesFileURL(sessionId: sessionId), options: .atomic)
        } catch {
            // 持久化失败不阻断聊天，下次启动从上次成功的文件恢复
            print("ChatHistoryStore: saveMessages failed — \(error)")
        }
    }

    /// 清空某会话消息（保留会话记录；顶栏「清空对话」语义）。
    func clearMessages(sessionId: String) {
        try? FileManager.default.removeItem(at: messagesFileURL(sessionId: sessionId))
    }
}
