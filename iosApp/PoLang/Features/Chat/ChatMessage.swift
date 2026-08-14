import Foundation

/// Chat UI 态模型（Identifiable + Codable）。
///
/// 与 Koog 记忆层（koog_memory_default）是两套：
/// - 本文件管 UI 展示历史全量（Documents/chat_history_default.json）；
/// - Koog 记忆管 LLM 多轮上下文（NSUserDefaults，三不变式裁剪）。
struct ChatMessage: Identifiable, Codable {
    let id: UUID
    let role: Role
    var text: String
    let timestamp: Date
    var isStreaming: Bool       // 流式中（文本逐 token 更新）
    var isThinking: Bool        // 思考态（首 token 到达前的 3 点动画）
    var isToolCalling: Bool     // 工具调用中（「正在调用工具…」）
    var mediaIds: [Int64]       // 媒体卡片（MEDIA_RESULTS 独立消息）
    var error: String?          // 错误文案（同 agent 气泡渲染，无特殊色）
    var mediaQuery: String?     // 媒体结果搜索词（ViewAll 导航回相册用；Optional 保 Codable 向后兼容）
    var mediaTotalCount: Int?   // 媒体结果全量命中数（> mediaIds.count 时显示「查看全部」）
    /// 流式光标可见性（commonMain StreamingPacingController 驱动；不持久化——CodingKeys 排除，老数据兼容）
    var showCursor: Bool = false
    /// CHART 图卡 SVG（draw_chart 端侧 ChartJsEngine 生成；不持久化）
    var chartSvg: String?

    enum CodingKeys: String, CodingKey {
        case id, role, text, timestamp, isStreaming, isThinking, isToolCalling
        case mediaIds, error, mediaQuery, mediaTotalCount
        // showCursor 不参与编解码（仅流式内存态）
    }

    enum Role: String, Codable {
        case user, assistant
    }

    init(id: UUID = UUID(), role: Role, text: String, timestamp: Date = Date(),
         isStreaming: Bool = false, isThinking: Bool = false, isToolCalling: Bool = false,
         mediaIds: [Int64] = [], error: String? = nil,
         mediaQuery: String? = nil, mediaTotalCount: Int? = nil) {
        self.id = id
        self.role = role
        self.text = text
        self.timestamp = timestamp
        self.isStreaming = isStreaming
        self.isThinking = isThinking
        self.isToolCalling = isToolCalling
        self.mediaIds = mediaIds
        self.error = error
        self.mediaQuery = mediaQuery
        self.mediaTotalCount = mediaTotalCount
    }
}
