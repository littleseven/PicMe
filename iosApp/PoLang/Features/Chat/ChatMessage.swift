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
    /// OPTIMIZE_CANDIDATES 候选卡组 payload（type == .optimizeCandidates，chat.yaml §17；
    /// 持久化随消息走；解析失败静默丢弃该字段——消息退化为纯文本气泡，不崩）
    var gacha: GachaPayload? = nil

    enum CodingKeys: String, CodingKey {
        case id, role, text, timestamp, type, imageUri
        case isStreaming, isThinking, isToolCalling
        case mediaIds, error, mediaQuery, mediaTotalCount
        case gacha
        // showCursor 不参与编解码（仅流式内存态）；chartSvg 亦不持久化
    }

    enum Role: String, Codable {
        case user, assistant
    }

    /// 消息类型（对齐 commonMain ChatMessageType 的 iOS 在用子集；
    /// 其余 case（userImage/agentImage/command/planPreview）待产生源接入时补；
    /// optimizeCandidates 已接入——AI 优化抽卡候选卡组，§17）
    enum MessageType: String, Codable {
        case userText, agentText, userImageText, mediaResults, chart, agentEditResult
        case optimizeCandidates
        /// agent 单发结果图（Android AGENT_IMAGE：gacha 确认/降级单发；FillWidth 240 完整显示）
        case agentImage
    }

    /// 单张抽卡候选卡（结构照 chat.yaml §17 message_model.payload.candidates）。
    struct GachaCandidate: Codable, Equatable {
        let index: Int
        /// 扰动方向（base/clarity/vivid/warm/cool/brighten/crisp；UI 经 GachaDirectionLabel 本地化）
        let direction: String
        /// 512px 候选缩略图落盘路径（nil=落盘失败，卡条显示占位）
        let thumbPath: String?
        /// NIMA 美学分（1~10；nil=未评分/护栏淘汰）
        let nimaScore: Float?
        /// 护栏淘汰标记（0.4 alpha 不可点）
        let rejected: Bool
        var rejectReason: String? = nil
    }

    /// 抽卡候选卡组（结构照 chat.yaml §17 message_model.payload，
    /// 对齐 commonMain OptimizeCandidateGroup——iOS 保持 Swift struct 沿用 ChatMessage 既有决策）。
    struct GachaPayload: Codable, Equatable {
        /// 优化目标图标识（LLM 传入：PHAsset localIdentifier / file:// 路径）
        var sourceImageUri: String
        /// 场景（Scene.rawValue：SELFIE/PORTRAIT/GROUP/FOOD/LANDSCAPE/LOW_LIGHT/DOCUMENT/GENERAL）
        var scene: String
        /// 推荐卡序号；-1 = KeepOriginal（不预选）
        var recommendedIndex: Int
        var candidates: [GachaCandidate]
        /// 已出现参数指纹（换一组 exclude 去重；排序持久化保证 JSON 稳定）
        var usedFingerprints: [String]
        /// 第几抽（首抽 0，换一组 +1）
        var drawIndex: Int
    }

    init(id: UUID = UUID(), role: Role, text: String, timestamp: Date = Date(),
         type: MessageType? = nil, imageUri: String? = nil,
         isStreaming: Bool = false, isThinking: Bool = false, isToolCalling: Bool = false,
         mediaIds: [Int64] = [], error: String? = nil,
         mediaQuery: String? = nil, mediaTotalCount: Int? = nil,
         gacha: GachaPayload? = nil) {
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
        self.gacha = gacha
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
        // gacha：老 JSON 无此字段；结构漂移时静默丢弃（nil）——消息退化为纯文本气泡，不崩
        // （try? 吞掉 decode 错误；spec §17 message_model.persistence「解析失败静默丢弃」）
        gacha = (try? c.decodeIfPresent(GachaPayload.self, forKey: .gacha)) ?? nil
    }
}
