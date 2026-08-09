import Foundation
import Combine
import SharedKit

/// Chat ViewModel（MV 模式，对齐 Android ChatViewModel）。
///
/// 交互模型（spec chat.yaml §9.1）：
/// - 发送流程：user 消息即追加 → thinking 占位（3 点）→ streaming（文本 + 光标）
///   → toolCalling（「正在调用工具…」）→ complete
/// - 发送按钮仅在有内容 && !isProcessing 时显示（Android 无 stop 按钮）
/// - 媒体结果作为独立消息项（不嵌入文本气泡）
/// - 空状态示例 chips 点击直接发送
@MainActor
final class ChatViewModel: ObservableObject {
    @Published private(set) var messages: [ChatMessage] = []
    @Published private(set) var isProcessing = false

    private var bridge: ChatAgentBridge?
    private var actionWatcher: FlowWatcher?

    func configure(bridge: ChatAgentBridge) {
        self.bridge = bridge
        messages = ChatHistoryStore.shared.load()
        actionWatcher?.cancel()
        actionWatcher = bridge.watchUiActions { [weak self] dto in
            Task { @MainActor in self?.handleUiAction(dto) }
        }
    }

    // MARK: - Send (对齐 Android sendMessage 流程)

    func send(_ input: String) {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !isProcessing else { return }
        guard let bridge else { return }

        // 1. user 消息即追加
        messages.append(ChatMessage(role: .user, text: trimmed))
        persist()

        // 2. assistant 占位：thinking 态（首 token 前显示 3 点动画）
        let placeholderId = UUID()
        messages.append(ChatMessage(
            id: placeholderId, role: .assistant,
            text: "", isStreaming: true, isThinking: true
        ))
        isProcessing = true

        // 3. 启动推理
        bridge.sendMessage(
            input: trimmed,
            onText: { [weak self] snapshot in
                Task { @MainActor in
                    // 首 token 到达：退出 thinking，进入 streaming
                    self?.streamingUpdate(id: placeholderId, text: snapshot)
                }
            },
            onToolCall: { [weak self] in
                Task { @MainActor in
                    // 工具调用：替换为状态文案，清空文本（下一轮从空重新累计）
                    self?.toolCallingUpdate(id: placeholderId)
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
        bridge.clearHistory { [weak self] in
            Task { @MainActor in
                self?.messages = []
                ChatHistoryStore.shared.clear()
            }
        }
    }

    deinit {
        actionWatcher?.cancel()
    }

    // MARK: - Streaming State Updates

    /// 首 token 到达或后续 token：退出 thinking/toolCalling，显示流式文本
    private func streamingUpdate(id: UUID, text: String) {
        guard let idx = messages.firstIndex(where: { $0.id == id }) else { return }
        messages[idx].isThinking = false
        messages[idx].isToolCalling = false
        messages[idx].text = text
    }

    /// 工具调用开始：显示状态文案
    private func toolCallingUpdate(id: UUID) {
        guard let idx = messages.firstIndex(where: { $0.id == id }) else { return }
        messages[idx].isThinking = false
        messages[idx].isToolCalling = true
        messages[idx].text = String(localized: "Calling tools…")  // 正在调用工具…
    }

    /// 推理完成
    private func completeMessage(id: UUID, summary: String, errorMessage: String?) {
        guard let idx = messages.firstIndex(where: { $0.id == id }) else { return }
        messages[idx].isStreaming = false
        messages[idx].isThinking = false
        messages[idx].isToolCalling = false
        if let errorMessage, !errorMessage.isEmpty {
            messages[idx].text = errorMessage
            messages[idx].error = errorMessage
        } else {
            messages[idx].text = summary.isEmpty ? String(localized: "(No response)") : summary
        }
        persist()
    }

    // MARK: - UI Actions（媒体结果 = 独立消息）

    private func handleUiAction(_ dto: ChatUiActionDto) {
        switch dto.kind {
        case "media_results":
            // 媒体结果作为独立消息项追加（不嵌入文本气泡）
            let ids = dto.mediaIds.map { $0.int64Value }
            let header = dto.totalCount > 0
                ? String(localized: "Found \(dto.totalCount) results for「\(dto.query)」")
                : String(localized: "No results found for「\(dto.query)」")
            messages.append(ChatMessage(
                role: .assistant,
                text: header,
                mediaIds: ids
            ))
            persist()
        default:
            break
        }
    }

    // MARK: - Persistence

    private func persist() {
        // 只持久化非流式消息
        let persisted = messages.filter { !$0.isStreaming }
        ChatHistoryStore.shared.save(persisted)
    }
}
