import Foundation

/// Chat UI 态模型（Identifiable + Codable）。
///
/// 与 Koog 记忆层（koog_memory_default）是两套：
/// - 本文件管 UI 展示历史全量（Documents/chat_history_default.json）；
/// - Koog 记忆管 LLM 多轮上下文（NSUserDefaults，三不变式裁剪）。
///
/// `type`/`imageUri` 字段 shape 对齐 commonMain `ChatMessageType`（批次① Task 1b 下沉的 SSOT）；
/// iOS 保持 Swift struct（KMP Codable 跨平台复杂，plan 批次① Task 3 既定方案）。
struct ChatMessage: Identifiable, Codable {
    let id: UUID
    let role: Role
    var text: String
    let timestamp: Date
    /// 消息类型（老 JSON 无此字段 → init(from:) 推断，见下）
    var type: MessageType
    /// 图片引用：userImageText = PHAsset localIdentifier；agentEditResult = Documents/chat_edits 文件路径
    var imageUri: String?
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
        case id, role, text, timestamp, type, imageUri
        case isStreaming, isThinking, isToolCalling
        case mediaIds, error, mediaQuery, mediaTotalCount
        // showCursor 不参与编解码（仅流式内存态）；chartSvg 亦不持久化
    }

    enum Role: String, Codable {
        case user, assistant
    }

    /// 消息类型（对齐 commonMain ChatMessageType 的 iOS 在用子集；
    /// 其余 case（userImage/agentImage/command/planPreview/optimizeCandidates）待产生源接入时补）
    enum MessageType: String, Codable {
        case userText, agentText, userImageText, mediaResults, chart, agentEditResult
    }

    init(id: UUID = UUID(), role: Role, text: String, timestamp: Date = Date(),
         type: MessageType? = nil, imageUri: String? = nil,
         isStreaming: Bool = false, isThinking: Bool = false, isToolCalling: Bool = false,
         mediaIds: [Int64] = [], error: String? = nil,
         mediaQuery: String? = nil, mediaTotalCount: Int? = nil) {
        self.id = id
        self.role = role
        self.text = text
        self.timestamp = timestamp
        // 缺省按 role 推断（存量调用点零改动；media/chart 等特殊类型须显式传）
        self.type = type ?? (role == .user ? .userText : .agentText)
        self.imageUri = imageUri
        self.isStreaming = isStreaming
        self.isThinking = isThinking
        self.isToolCalling = isToolCalling
        self.mediaIds = mediaIds
        self.error = error
        self.mediaQuery = mediaQuery
        self.mediaTotalCount = mediaTotalCount
    }

    // MARK: - Codable 向前兼容（老 JSON 无 type/imageUri）

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(UUID.self, forKey: .id)
        role = try c.decode(Role.self, forKey: .role)
        text = try c.decode(String.self, forKey: .text)
        timestamp = try c.decode(Date.self, forKey: .timestamp)
        isStreaming = try c.decode(Bool.self, forKey: .isStreaming)
        isThinking = try c.decode(Bool.self, forKey: .isThinking)
        isToolCalling = try c.decode(Bool.self, forKey: .isToolCalling)
        mediaIds = try c.decode([Int64].self, forKey: .mediaIds)
        error = try c.decodeIfPresent(String.self, forKey: .error)
        mediaQuery = try c.decodeIfPresent(String.self, forKey: .mediaQuery)
        mediaTotalCount = try c.decodeIfPresent(Int.self, forKey: .mediaTotalCount)
        let decodedType = try c.decodeIfPresent(MessageType.self, forKey: .type)
        imageUri = try c.decodeIfPresent(String.self, forKey: .imageUri)
        // 老数据推断：user → userText；assistant + mediaIds → mediaResults；否则 agentText
        // （chartSvg 不持久化，历史 JSON 里不存在 chart 型，无需推断）
        type = decodedType ?? (role == .user ? .userText : (!mediaIds.isEmpty ? .mediaResults : .agentText))
    }
}
