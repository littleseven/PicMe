import Foundation
import Combine
import SharedKit

/// Chat ViewModel（MV 模式，对齐 GalleryViewModel）。
///
/// 状态管理（spec S4 单一状态源）：
/// - messages：全量 UI 消息（user + assistant），含流式占位与最终结果；
/// - isProcessing：串行发送守卫（plan 风险 5：Dispatchers.Default 无串行语义，必须排他）；
/// - 流式期间经 onText 回调逐 token 替换 assistant 占位气泡内容。
///
/// Bridge 交互（signal 6 纪律）：
/// - ChatAgentBridge 非 suspend 回调式，回调线程非主线程 → 必须 `Task { @MainActor in }`；
/// - sendMessage 返回 FlowWatcher，sendWatcher 持有；新消息前 cancel 旧的防并发；
/// - watchUiActions 在 onAppear 注册、onDisappear cancel。
@MainActor
final class ChatViewModel: ObservableObject {
    @Published private(set) var messages: [ChatMessage] = []
    @Published private(set) var isProcessing = false

    private var bridge: ChatAgentBridge?
    private var actionWatcher: FlowWatcher?

    func configure(bridge: ChatAgentBridge) {
        self.bridge = bridge
        // 加载 UI 历史
        messages = ChatHistoryStore.shared.load()
        // 订阅工具产出（媒体卡片 / 文本提示）
        actionWatcher?.cancel()
        actionWatcher = bridge.watchUiActions { [weak self] dto in
            Task { @MainActor in self?.handleUiAction(dto) }
        }
    }

    func send(_ input: String) {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !isProcessing else { return }
        guard let bridge else { return }

        // 1. user 消息即追加
        messages.append(ChatMessage(role: .user, text: trimmed))
        persist()

        // 2. assistant 占位（流式更新）
        let placeholderId = UUID()
        messages.append(ChatMessage(id: placeholderId, role: .assistant, text: "", isStreaming: true))
        isProcessing = true

        // 3. 启动新推理（sendMessage 返回 void，取消经 cancelCurrent()）
        bridge.sendMessage(
            input: trimmed,
            onText: { [weak self] snapshot in
                Task { @MainActor in
                    self?.updateMessage(id: placeholderId, text: snapshot)
                }
            },
            onToolCall: { [weak self] in
                Task { @MainActor in
                    self?.updateMessage(id: placeholderId, text: String(localized: "Searching…"))
                }
            },
            onComplete: { [weak self] summary, errorMessage in
                Task { @MainActor in
                    self?.completeMessage(id: placeholderId, summary: summary, errorMessage: errorMessage)
                    self?.isProcessing = false
                }
            }
        )
    }

    func clearHistory() {
        guard let bridge else { return }
        // 清 Koog 记忆 + UI 历史
        bridge.clearHistory { [weak self] in
            Task { @MainActor in
                self?.messages = []
                ChatHistoryStore.shared.clear()
            }
        }
    }

    func stop() {
        bridge?.cancelCurrent()
        isProcessing = false
        // 把最后一个流式消息标记为已完成（防 UI 残留光标）
        if let idx = messages.indices.last(where: { messages[$0].isStreaming }) {
            messages[idx].isStreaming = false
            if messages[idx].text.isEmpty {
                messages[idx].text = String(localized: "Cancelled")
            }
            persist()
        }
    }

    /// onDisappear 取消 uiActions 订阅：FlowWatcher 持有 Kotlin 协程 Job，
    /// Swift 属性释放不会自动 cancel Kotlin 侧 Job，必须显式取消
    func stopWatching() {
        actionWatcher?.cancel()
        actionWatcher = nil
    }

    deinit {
        actionWatcher?.cancel()
    }

    // MARK: - Private

    private func updateMessage(id: UUID, text: String) {
        guard let idx = messages.firstIndex(where: { $0.id == id }) else { return }
        messages[idx].text = text
    }

    private func completeMessage(id: UUID, summary: String, errorMessage: String?) {
        guard let idx = messages.firstIndex(where: { $0.id == id }) else { return }
        messages[idx].isStreaming = false
        if let errorMessage, !errorMessage.isEmpty {
            messages[idx].text = errorMessage
            messages[idx].error = errorMessage
        } else {
            messages[idx].text = summary.isEmpty ? String(localized: "(No response)") : summary
        }
        persist()
    }

    private func handleUiAction(_ dto: ChatUiActionDto) {
        switch dto.kind {
        case "media_results":
            // 追加一条带媒体卡片的 assistant 消息
            let ids = dto.mediaIds.map { $0.int64Value }
            messages.append(ChatMessage(
                role: .assistant,
                text: String(localized: "Found \(dto.totalCount) results for「\(dto.query)」"),
                mediaIds: ids
            ))
            persist()
        case "text_reply" where !dto.message.isEmpty:
            // 工具产出的文本提示（如「已收藏」「删除请求已提交」）
            // 已在流式回复中体现，不重复追加
            break
        default:
            break
        }
    }

    private func persist() {
        ChatHistoryStore.shared.save(messages)
    }
}
