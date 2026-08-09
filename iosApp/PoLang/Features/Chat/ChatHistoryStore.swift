import Foundation

/// 聊天 UI 历史持久化（Documents/chat_history_default.json）。
///
/// 单 session（"default"），与 Koog 记忆层是两套（见 ChatMessage 注释）：
/// - 本 store 存全量 UI 消息（含媒体卡片 id / 错误气泡），重启后恢复；
/// - Koog 记忆经 IosKoogMessageMemoryStore（NSUserDefaults）管 LLM 多轮上下文。
@MainActor
final class ChatHistoryStore {
    static let shared = ChatHistoryStore()

    private let fileName = "chat_history_default.json"

    private var fileURL: URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return docs.appendingPathComponent(fileName)
    }

    func load() -> [ChatMessage] {
        guard let data = try? Data(contentsOf: fileURL) else { return [] }
        return (try? JSONDecoder().decode([ChatMessage].self, from: data)) ?? []
    }

    func save(_ messages: [ChatMessage]) {
        do {
            let data = try JSONEncoder().encode(messages)
            try data.write(to: fileURL, options: .atomic)
        } catch {
            // 持久化失败不阻断聊天，下次启动从上次成功的文件恢复
            print("ChatHistoryStore: save failed — \(error)")
        }
    }

    func clear() {
        try? FileManager.default.removeItem(at: fileURL)
    }
}
