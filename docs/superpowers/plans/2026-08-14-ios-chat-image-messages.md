# iOS Chat 图片消息子系统 Implementation Plan（批次②）

> **For agentic workers:** REQUIRED SUB-SKILL: 用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐 task 实现。步骤用 `- [ ]` 跟踪。
> **前置调研结论已内嵌**：本 plan 基于与 Android 的 gap 对比（图片消息子系统缺失 = 最大缺口）。

**Goal:** 补上 iOS chat 的「对话式图片」闭环——用户上图下文气泡（USER_IMAGE_TEXT）、AI 编辑结果图可见（AGENT_EDIT_RESULT，含失效占位）、图片/媒体卡点开全屏预览。前置：`ChatMessage` 加 `type` 字段（Task 3 of 批次①，图片渲染的地基）。

**Architecture:** iOS `ChatMessage` 保持 Swift struct（不直接消费 commonMain 类，KMP Codable 踩坑；**字段 shape 对齐** commonMain `ChatMessageType` 子集）+ `imageUri`（PHAsset localIdentifier 或编辑结果文件路径）。编辑器回链学 Android `outputUri` 模型：编辑结果**写文件** `Documents/chat_edits/`（lite 版编辑器不追踪新副本 PHAsset id，文件路径才可靠），chat 消息存路径，渲染时按文件存在性显示失效占位（对齐 Android `chatImageIsLive`）。跨层回链用仓库既有静态闭包范式（`ChartRendererBridge.onChart` 同款）。

**Tech Stack:** SwiftUI · PHAsset/ThumbnailLoader · Codable（向前兼容）· XCUITest · xcstrings 三语（en/zh-Hans/zh-Hant）

**范围裁决（已定）**：
- AGENT_IMAGE（相机 AI 编辑产生源）本批不做——iOS 相机 agent 链 v1 不在范围；渲染分支预留（`agentEditResult` 同一渲染路径可复用）。
- 保存按钮分歧：Android 由 chat 内「保存到相册」按钮保存；iOS 编辑器点「保存」时**已存相册**，chat 只显示结果图 + 「已保存」文案（不再给保存按钮，避免二次保存）。标注为有意分歧。
- 纯 Swift（无 Kotlin/shared 改动 → 不需重编 SharedKit 框架；改 Swift 后只需 `xcodegen`（仅当新增文件时）+ `xcodebuild`）。

---

## File Structure（文件职责映射）

| 文件 | 动作 | 职责 |
|---|---|---|
| `iosApp/PoLang/Features/Chat/ChatMessage.swift` | 改 | 加 `MessageType` 枚举 + `type`/`imageUri` 字段 + `init(from:)` 老数据推断 |
| `iosApp/PoLangTests/ChatMessageTypeTests.swift` | 新 | Codable 向前兼容单测（老 JSON 推断 + 新字段 roundtrip） |
| `iosApp/PoLang/Features/Chat/ChatViewModel.swift` | 改 | 9 处构造点带 type；send() 带图 userImageText；编辑结果插入 + `/editdemo` |
| `iosApp/PoLang/Features/Chat/ChatView.swift` | 改 | MessageBubble 分支（上图下文/编辑图卡）；全屏预览状态 + 装配 |
| `iosApp/PoLang/Features/Chat/ChatImageAttachment.swift` | 新 | `UserImageAttachment`（PHAsset 缩略图）+ `ChatEditImageCard`（文件图+失效占位）+ `ChatImagePreview`（全屏缩放） |
| `iosApp/PoLang/Platform/ChatEditResultBridge.swift` | 新 | 编辑器→chat 静态闭包桥（镜像 ChartRendererBridge） |
| `iosApp/PoLang/Features/Editor/PhotoEditorViewModel.swift` | 改 | save() 追加落盘 chat_edits + onEditResult 回调 |
| `iosApp/PoLang/Features/Editor/PhotoEditorScreen.swift` | 改 | 暴露 onEditResult 参数 |
| `iosApp/PoLang/Features/Main/MainTabView.swift` | 改 | fullScreenCover 传 onEditResult → Bridge |
| `iosApp/PoLang/Resources/Localizable.xcstrings` | 改 | 新增 2 个 key × 三语 |
| `iosApp/PoLangUITests/ChatSmokeUITests.swift` | 改 | `testEditResultDemo`（确定性 E2E） |

---

## Task 1: ChatMessage 加 MessageType + imageUri（Codable 向前兼容）

**Files:**
- Modify: `iosApp/PoLang/Features/Chat/ChatMessage.swift`
- Test: `iosApp/PoLangTests/ChatMessageTypeTests.swift`（新）

- [ ] **Step 1: 写失败测试**

```swift
import XCTest
@testable import PoLang

/// ChatMessage 的 type/imageUri 扩展 + Codable 向前兼容（老 JSON 无 type → 推断）。
final class ChatMessageTypeTests: XCTestCase {

    private func legacyJSON(role: String, mediaIds: [Int] = []) throws -> Data {
        let obj: [String: Any] = [
            "id": UUID().uuidString,
            "role": role,
            "text": "hello",
            "timestamp": 0,
            "isStreaming": false,
            "isThinking": false,
            "isToolCalling": false,
            "mediaIds": mediaIds,
        ]
        return try JSONSerialization.data(withJSONObject: [obj])
    }

    /// 老数据：user → 推断 userText
    func testLegacyUserInfersUserText() throws {
        let messages = try JSONDecoder().decode([ChatMessage].self, from: legacyJSON(role: "user"))
        XCTAssertEqual(messages[0].type, .userText)
        XCTAssertNil(messages[0].imageUri)
    }

    /// 老数据：assistant + mediaIds → 推断 mediaResults
    func testLegacyAssistantWithMediaInfersMediaResults() throws {
        let messages = try JSONDecoder().decode([ChatMessage].self, from: legacyJSON(role: "assistant", mediaIds: [1, 2]))
        XCTAssertEqual(messages[0].type, .mediaResults)
    }

    /// 老数据：assistant 纯文本 → agentText
    func testLegacyAssistantInfersAgentText() throws {
        let messages = try JSONDecoder().decode([ChatMessage].self, from: legacyJSON(role: "assistant"))
        XCTAssertEqual(messages[0].type, .agentText)
    }

    /// 新数据 roundtrip：type + imageUri 持久化不丢
    func testRoundtripPreservesTypeAndImageUri() throws {
        let msg = ChatMessage(role: .assistant, text: "edited", type: .agentEditResult, imageUri: "/path/x.jpg")
        let data = try JSONEncoder().encode([msg])
        let back = try JSONDecoder().decode([ChatMessage].self, from: data)
        XCTAssertEqual(back[0].type, .agentEditResult)
        XCTAssertEqual(back[0].imageUri, "/path/x.jpg")
    }

    /// 默认构造：不传 type 时按 role 推断（现有调用点零改动兼容）
    func testDefaultInitInfersByRole() {
        XCTAssertEqual(ChatMessage(role: .user, text: "hi").type, .userText)
        XCTAssertEqual(ChatMessage(role: .assistant, text: "hi").type, .agentText)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd iosApp && xcodebuild test -workspace PoLang.xcworkspace -scheme PoLang \
  -destination 'id=00008120-000105443AD2201E' \
  -only-testing:PoLangTests/ChatMessageTypeTests 2>&1 | grep -E "error:|Test Case|TEST"
```
Expected: FAIL（`type`/`imageUri` 成员不存在 → 编译错）。

- [ ] **Step 3: 改 ChatMessage.swift**

全文替换为（保留原注释语义；新增段见注释）：

```swift
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
    var mediaQuery: String?     // 媒体结果搜索词（ViewAll 导航回相册用）
    var mediaTotalCount: Int?   // 媒体结果全量命中数（> mediaIds.count 时显示「查看全部」）
    /// 流式光标可见性（commonMain StreamingPacingController 驱动；不持久化——CodingKeys 排除）
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
```

> ⚠️ 自定义 `init(from:)` 后 `encode(to:)` 仍由编译器合成（Codable 两协议独立合成），无需手写。

- [ ] **Step 4: 跑测试确认通过**

同 Step 2 命令。Expected: 5 用例 PASS。
（新测试文件需先 `cd iosApp && xcodegen generate` 再跑。）

- [ ] **Step 5: 全量编译防回归**

```bash
cd iosApp && xcodegen generate
xcodebuild -workspace PoLang.xcworkspace -scheme PoLang -destination 'generic/platform=iOS' build 2>&1 | tail -3
```
Expected: BUILD SUCCEEDED（存量 ChatMessage( 调用点零改动——type 缺省推断）。

- [ ] **Step 6: Commit**

```bash
git add iosApp/PoLang/Features/Chat/ChatMessage.swift iosApp/PoLangTests/ChatMessageTypeTests.swift iosApp/PoLang.xcodeproj/project.pbxproj
git commit -m "feat(ios): ChatMessage 加 MessageType/imageUri + Codable 向前兼容 (chat 图片消息 Task 1)"
```

---

## Task 2: ViewModel 构造点带 type + send() 带图 userImageText

**Files:**
- Modify: `iosApp/PoLang/Features/Chat/ChatViewModel.swift`

- [ ] **Step 1: send() 用户消息带图（line ~136）**

原：
```swift
        // 1. user 消息即追加
        messages.append(ChatMessage(role: .user, text: trimmed))
```
改为（`stagedImage` 在其后才消费，此处仍在）：
```swift
        // 1. user 消息即追加（带暂存图 → userImageText 上图下文；图引用 localIdentifier，
        //    远程只发文本——图片像素不上传，隐私红线）
        messages.append(ChatMessage(
            role: .user,
            text: trimmed,
            type: stagedImage != nil ? .userImageText : .userText,
            imageUri: stagedImage?.localIdentifier
        ))
```

- [ ] **Step 2: 媒体结果消息带 type（line 288-294）**

原：
```swift
            messages.append(ChatMessage(
                role: .assistant,
                text: header,
                mediaIds: ids,
                mediaQuery: dto.query,
                mediaTotalCount: Int(truncatingIfNeeded: dto.totalCount)
            ))
```
改（只加 `type:` 一行）：
```swift
            messages.append(ChatMessage(
                role: .assistant,
                text: header,
                type: .mediaResults,
                mediaIds: ids,
                mediaQuery: dto.query,
                mediaTotalCount: Int(truncatingIfNeeded: dto.totalCount)
            ))
```

- [ ] **Step 3: chart 消息带 type（appendChartMessage, line ~382）**

原：
```swift
        var msg = ChatMessage(role: .assistant, text: summary)
        msg.chartSvg = svg
```
改：
```swift
        var msg = ChatMessage(role: .assistant, text: summary, type: .chart)
        msg.chartSvg = svg
```

- [ ] **Step 4: 编译**

```bash
cd iosApp && xcodebuild -workspace PoLang.xcworkspace -scheme PoLang -destination 'generic/platform=iOS' build 2>&1 | tail -2
```
Expected: BUILD SUCCEEDED（line 145/300/308/409/433/436 等构造点走缺省推断，不动）。

- [ ] **Step 5: Commit**

```bash
git add iosApp/PoLang/Features/Chat/ChatViewModel.swift
git commit -m "feat(ios): Chat 消息构造点带 type + 带图发送 userImageText (chat 图片消息 Task 2)"
```

---

## Task 3: 图片附件渲染组件（上图下文 + 编辑图卡 + 失效占位）

**Files:**
- Create: `iosApp/PoLang/Features/Chat/ChatImageAttachment.swift`
- Modify: `iosApp/PoLang/Features/Chat/ChatView.swift`（MessageBubble 分支）

- [ ] **Step 1: 新建 ChatImageAttachment.swift**

```swift
import SwiftUI
import UIKit

// MARK: - USER_IMAGE_TEXT 上图下文缩略图（PHAsset，对齐 Android AsyncImage 气泡）

/// 用户气泡顶部图片：localIdentifier → ThumbnailLoader 缩略图（async），缺图给占位图标。
struct UserImageAttachment: View {
    let localIdentifier: String
    var onTap: (UIImage?) -> Void = { _ in }
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            Color.white.opacity(0.15)
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
            } else {
                Image(matIcon: "photo")
                    .font(.system(size: 22))
                    .foregroundColor(.white.opacity(0.55))
            }
        }
        .frame(height: 150)
        .frame(maxWidth: .infinity)
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .contentShape(Rectangle())
        .onTapGesture { onTap(image) }
        .task {
            image = await ThumbnailLoader.shared.thumbnail(
                for: localIdentifier, size: CGSize(width: 720, height: 720)
            )
        }
    }
}

// MARK: - AGENT_EDIT_RESULT 编辑结果图卡（文件加载 + 失效占位，对齐 Android chatImageIsLive）

/// 编辑结果图：Documents/chat_edits 文件路径 → UIImage 同步读；文件缺失显示失效占位
/// （Android 语义：编辑产物文件可能被清理，显示「图片已失效」而非空白）。
struct ChatEditImageCard: View {
    let imagePath: String
    var onTap: (UIImage?) -> Void = { _ in }
    @State private var image: UIImage?

    private var fileExists: Bool {
        FileManager.default.fileExists(atPath: imagePath)
    }

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                    .contentShape(Rectangle())
                    .onTapGesture { onTap(image) }
            } else if fileExists {
                // 加载中（同步读在 .task 内做，避免卡主线程首帧）
                Color(.secondarySystemBackground)
                    .frame(height: 180)
                    .task { image = UIImage(contentsOfFile: imagePath) }
            } else {
                // 失效占位（对齐 Android 过期图提示）
                VStack(spacing: 6) {
                    Image(matIcon: "broken_image")
                        .font(.system(size: 24))
                        .foregroundColor(.secondary.opacity(0.6))
                    Text(String(localized: "Image no longer available"))
                        .font(.system(size: 12))
                        .foregroundColor(.secondary)
                }
                .frame(height: 120)
                .frame(maxWidth: .infinity)
                .background(Color(.secondarySystemBackground))
                .clipShape(RoundedRectangle(cornerRadius: 10))
            }
        }
        .frame(maxWidth: .infinity)
        .accessibilityIdentifier("chat_edit_image")
    }
}
```

- [ ] **Step 2: MessageBubble 加渲染分支（ChatView.swift line 415 起）**

MessageBubble 增加参数（签名行）：
```swift
private struct MessageBubble: View {
    let message: ChatMessage
    var onNavigateToGallery: ((String) -> Void)? = nil
    var onImageTap: ((UIImage?) -> Void)? = nil
```

在 `VStack(alignment: .leading)` 内、`if message.isThinking` 分支**之前**插入（isThinking/isToolCalling 优先，正常态走下面）——具体：在 `} else if !message.text.isEmpty || !message.mediaIds.isEmpty {` 这行的条件前不动，改为在该分支体内、文本渲染（`if !message.text.isEmpty {`）**之前**插入图片：

```swift
                    // USER_IMAGE_TEXT：上图下文（图在文本上方，对齐 Android）
                    if message.type == .userImageText, let uri = message.imageUri {
                        UserImageAttachment(localIdentifier: uri, onTap: { onImageTap?($0) })
                            .padding(.bottom, 6)
                    }

                    // AGENT_EDIT_RESULT：编辑结果图卡（文件路径 + 失效占位）
                    if message.type == .agentEditResult, let path = message.imageUri {
                        ChatEditImageCard(imagePath: path, onTap: { onImageTap?($0) })
                            .padding(.bottom, 6)
                    }
```

> 注意：`agentEditResult` 的文本说明走既有文本渲染（`AgentTextView`）——插在文本前，图文同气泡。

- [ ] **Step 3: 编译**

```bash
cd iosApp && xcodegen generate
xcodebuild -workspace PoLang.xcworkspace -scheme PoLang -destination 'generic/platform=iOS' build 2>&1 | tail -2
```
Expected: BUILD SUCCEEDED。

- [ ] **Step 4: Commit**

```bash
git add iosApp/PoLang/Features/Chat/ChatImageAttachment.swift iosApp/PoLang/Features/Chat/ChatView.swift iosApp/PoLang.xcodeproj/project.pbxproj
git commit -m "feat(ios): Chat 上图下文气泡 + 编辑结果图卡（失效占位）渲染 (chat 图片消息 Task 3)"
```

---

## Task 4: 编辑器回链 + AGENT_EDIT_RESULT 插入 + /editdemo

**Files:**
- Create: `iosApp/PoLang/Platform/ChatEditResultBridge.swift`
- Modify: `iosApp/PoLang/Features/Editor/PhotoEditorViewModel.swift:164-194`（save()）
- Modify: `iosApp/PoLang/Features/Editor/PhotoEditorScreen.swift:8-9,56-58`
- Modify: `iosApp/PoLang/Features/Main/MainTabView.swift:113-115`
- Modify: `iosApp/PoLang/Features/Chat/ChatViewModel.swift`（configure + 插入 + demo）

- [ ] **Step 1: ChatEditResultBridge.swift（新）**

```swift
import Foundation

/// 编辑器 → chat 回链：`PhotoEditorScreen`（MainTabView fullScreenCover）完成保存后，
/// 把编辑结果文件路径经本桥送回 `ChatViewModel` 追加 AGENT_EDIT_RESULT 消息。
///
/// 范式同 `ChartRendererBridge.onChart`（组合根/宿主页面持不到 ChatViewModel 的 @StateObject，
/// 用静态闭包反向注入；ChatViewModel.configure 设置，MainTabView 调用）。
enum ChatEditResultBridge {
    /// 编辑结果图片文件路径（Documents/chat_edits/xxx.jpg）。
    static var onEditResult: ((String) -> Void)?
}
```

- [ ] **Step 2: PhotoEditorViewModel 加 onEditResult + 落盘**

在 `PhotoEditorViewModel` 的 `var onSaved: ((String?) -> Void)?`（line 37）下加：
```swift
    /// chat 回链：编辑结果落盘路径（Documents/chat_edits/xxx.jpg）。
    /// 与 onSaved 并存——onSaved 语义不变（新副本 localId，lite 版=原图 id），本回调专供 chat 渲染。
    var onEditResult: ((String) -> Void)?
```

`save()` 的 Task 内（line 173-185 区域），在 `if let image {` 的库保存块**之后**追加落盘（`image` 为渲染产物）：
```swift
                // chat 回链：编辑结果写文件（Documents/chat_edits）。lite 版库保存不追踪新副本
                // PHAsset id（savedUri=原图 id，显示会错图），文件路径才可靠——对齐 Android outputUri 模型。
                if let image, let data = image.jpegData(compressionQuality: 0.92) {
                    if let path = Self.writeChatEditFile(data) {
                        editResultPath = path
                    }
                }
```
在 Task 尾部 `self.onSaved?(savedUri)`（line 191）后加：
```swift
                if let p = editResultPath {
                    self.onEditResult?(p)
                }
```
并在 Task 开头（`let full = …` 前）声明 `var editResultPath: String? = nil`。
类内新增静态方法：
```swift
    /// 编辑结果落盘 Documents/chat_edits/<uuid>.jpg（目录懒创建）；失败返回 nil。
    private static func writeChatEditFile(_ data: Data) -> String? {
        let fm = FileManager.default
        guard let docs = fm.urls(for: .documentDirectory, in: .userDomainMask).first else { return nil }
        let dir = docs.appendingPathComponent("chat_edits", isDirectory: true)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("\(UUID().uuidString).jpg")
        do {
            try data.write(to: url)
            return url.path
        } catch {
            return nil
        }
    }
```

- [ ] **Step 3: PhotoEditorScreen 透传参数**

`PhotoEditorScreen`（line 8-9）加：
```swift
    var onEditResult: (String) -> Void = { _ in }
```
line 56-58 的 `vm.onSaved = { id in … }` 旁加：
```swift
            vm.onEditResult = { path in onEditResult(path) }
```

- [ ] **Step 4: MainTabView 路由**

fullScreenCover 内（line 113-115）：
```swift
            if let lid = editingImage {
                PhotoEditorScreen(
                    localIdentifier: lid,
                    onEditResult: { path in
                        ChatEditResultBridge.onEditResult?(path)
                    }
                )
            }
```
（`editingImage` 仅由 chat EDIT 意图设置——line 42，故此路由即 chat 专属，无需额外来源标记。）

- [ ] **Step 5: ChatViewModel 接线 + 插入 + /editdemo**

`configure()` 内 `ChartRendererBridge.onChart = {…}`（line 92-94）后加：
```swift
        // 编辑结果回链：chat EDIT → PhotoEditorScreen 保存 → 落盘路径回此追加 AGENT_EDIT_RESULT
        ChatEditResultBridge.onEditResult = { [weak self] path in
            Task { @MainActor in self?.appendEditResultMessage(imagePath: path) }
        }
```

CHART 段后新增方法（对齐 appendChartMessage 风格）：
```swift
    // MARK: - AGENT_EDIT_RESULT（chat EDIT → 编辑器 → 结果图回链）

    /// 追加编辑结果消息（图=Documents/chat_edits 文件路径；文案标注已存相册——iOS 编辑器
    /// 保存时已入库，与 Android「chat 内保存按钮」为有意分歧，见 plan 范围裁决）。
    private func appendEditResultMessage(imagePath: String) {
        let caption = String(localized: "Edit complete. Result saved to Photos.")
        messages.append(ChatMessage(role: .assistant, text: caption, type: .agentEditResult, imageUri: imagePath))
        touchThread(preview: caption)
        persist()
    }

    /// /editdemo：生成一张渐变图落盘并追加编辑结果消息（确定性验证渲染链，不经编辑器）。
    private func emitEditResultDemo() {
        let size = CGSize(width: 600, height: 400)
        let renderer = UIGraphicsImageRenderer(size: size)
        let image = renderer.image { ctx in
            let colors = [UIColor.systemBlue.cgColor, UIColor.systemTeal.cgColor]
            let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(),
                                      colors: colors as CFArray, locations: [0, 1])!
            ctx.cgContext.drawLinearGradient(
                gradient, start: .zero,
                end: CGPoint(x: size.width, y: size.height), options: []
            )
        }
        guard let data = image.jpegData(compressionQuality: 0.9),
              let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        let dir = docs.appendingPathComponent("chat_edits", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("demo-\(UUID().uuidString).jpg")
        do {
            try data.write(to: url)
            appendEditResultMessage(imagePath: url.path)
        } catch {
            messages.append(ChatMessage(role: .assistant, text: "demo 图片写入失败：\(error.localizedDescription)", error: error.localizedDescription))
            persist()
        }
    }
```

`send()` 的 `/runscript` 分支后加：
```swift
        // AGENT_EDIT_RESULT 渲染 demo：生成图落盘 → 追加编辑结果消息（确定性验证，/chart 同款）
        if trimmed.lowercased() == "/editdemo" {
            emitEditResultDemo()
            return
        }
```

- [ ] **Step 6: 编译**

```bash
cd iosApp && xcodegen generate
xcodebuild -workspace PoLang.xcworkspace -scheme PoLang -destination 'generic/platform=iOS' build 2>&1 | tail -2
```
Expected: BUILD SUCCEEDED。

- [ ] **Step 7: Commit**

```bash
git add iosApp/PoLang/Platform/ChatEditResultBridge.swift iosApp/PoLang/Features/Editor/PhotoEditorViewModel.swift iosApp/PoLang/Features/Editor/PhotoEditorScreen.swift iosApp/PoLang/Features/Main/MainTabView.swift iosApp/PoLang/Features/Chat/ChatViewModel.swift iosApp/PoLang.xcodeproj/project.pbxproj
git commit -m "feat(ios): chat EDIT→编辑器→结果图回链 AGENT_EDIT_RESULT + /editdemo (chat 图片消息 Task 4)"
```

---

## Task 5: 全屏预览（chat 图片 + 媒体卡点击）

**Files:**
- Modify: `iosApp/PoLang/Features/Chat/ChatImageAttachment.swift`（追加 ChatImagePreview）
- Modify: `iosApp/PoLang/Features/Chat/ChatView.swift`（预览状态 + MediaThumbnail 点击）

- [ ] **Step 1: ChatImagePreview（ChatImageAttachment.swift 末尾追加）**

```swift
// MARK: - 全屏图片预览（点 chat 图片/媒体卡打开；捏合缩放 1-5x，点关闭）

struct ChatImagePreview: View {
    let image: UIImage
    @Environment(\.dismiss) private var dismiss
    @State private var scale: CGFloat = 1

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            Image(uiImage: image)
                .resizable()
                .aspectRatio(contentMode: .fit)
                .scaleEffect(scale)
                .gesture(
                    MagnificationGesture()
                        .onChanged { value in scale = min(max(value, 1), 5) }
                        .onEnded { _ in if scale < 1.05 { scale = 1 } }
                )
                .onTapGesture { dismiss() }
            VStack {
                HStack {
                    Spacer()
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 28))
                            .foregroundColor(.white.opacity(0.85))
                            .padding(16)
                    }
                    .accessibilityIdentifier("chat_image_preview_close")
                }
                Spacer()
            }
        }
    }
}
```

- [ ] **Step 2: ChatView 预览状态与装配**

`ChatView` 的 `@State` 区加：
```swift
    /// 全屏图片预览（chat 图/媒体卡点击打开）
    @State private var previewImage: UIImage?
```

ForEach 内 MessageBubble 调用（line ~191）改为：
```swift
                        MessageBubble(
                            message: msg,
                            onNavigateToGallery: onNavigateToGallery,
                            onImageTap: { img in
                                if let img { previewImage = img }
                            },
                            onMediaTap: { lid in openPreview(localIdentifier: lid) }
                        )
```

MessageBubble 再加一个参数（Task 3 已加 onImageTap）：
```swift
    var onMediaTap: ((String) -> Void)? = nil
```
并把 `onMediaTap` 传入 `MediaCardRow`（line ~460 构造处加 `onMediaTap: onMediaTap`）。

`MediaCardRow` 加参数并下传 `MediaThumbnail`：
```swift
    var onMediaTap: ((String) -> Void)? = nil
    // ForEach 内构造处：
    MediaThumbnail(
        localIdentifier: idToIdentifier[id],
        date: idToDate[id],
        onTap: { if let lid = idToIdentifier[id] { onMediaTap?(lid) } }
    )
```
`MediaThumbnail` 加 `var onTap: () -> Void = {}`，在 ZStack 外层加：
```swift
        .contentShape(Rectangle())
        .onTapGesture { onTap() }
```

`ChatView` body 的 ScrollView 修饰符后追加 fullScreenCover + 方法：
```swift
            .fullScreenCover(isPresented: Binding(
                get: { previewImage != nil },
                set: { if !$0 { previewImage = nil } }
            )) {
                if let previewImage {
                    ChatImagePreview(image: previewImage)
                }
            }
```
```swift
    /// 媒体卡全屏：localIdentifier → 原图 async 载入后打开预览。
    private func openPreview(localIdentifier: String) {
        Task {
            let image = await ThumbnailLoader.shared.fullResolution(for: localIdentifier)
            await MainActor.run { if let image { previewImage = image } }
        }
    }
```

- [ ] **Step 3: 编译**

```bash
cd iosApp && xcodebuild -workspace PoLang.xcworkspace -scheme PoLang -destination 'generic/platform=iOS' build 2>&1 | tail -2
```
Expected: BUILD SUCCEEDED。

- [ ] **Step 4: Commit**

```bash
git add iosApp/PoLang/Features/Chat/ChatImageAttachment.swift iosApp/PoLang/Features/Chat/ChatView.swift
git commit -m "feat(ios): Chat 图片/媒体卡全屏预览（捏合缩放）(chat 图片消息 Task 5)"
```

---

## Task 6: i18n 三语 + UITest + 全量验证 + 文档

**Files:**
- Modify: `iosApp/PoLang/Resources/Localizable.xcstrings`
- Modify: `iosApp/PoLangUITests/ChatSmokeUITests.swift`

- [ ] **Step 1: xcstrings 加 2 个 key（en/zh-Hans/zh-Hant）**

在 JSON catalog 顶层 `"strings"` 对象内按字母序插入（格式照既有条目）：

```json
"Edit complete. Result saved to Photos." : {
  "localizations" : {
    "en" : { "stringUnit" : { "state" : "translated", "value" : "Edit complete. Result saved to Photos." } },
    "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "编辑完成，结果已保存到相册" } },
    "zh-Hant" : { "stringUnit" : { "state" : "translated", "value" : "編輯完成，結果已儲存到相簿" } }
  }
},
"Image no longer available" : {
  "localizations" : {
    "en" : { "stringUnit" : { "state" : "translated", "value" : "Image no longer available" } },
    "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "图片已失效或被清理" } },
    "zh-Hant" : { "stringUnit" : { "state" : "translated", "value" : "圖片已失效或被清理" } }
  }
},
```

- [ ] **Step 2: UITest（确定性：/editdemo → 图卡出现）**

`ChatSmokeUITests` 的 `testRunScriptDemo` 后加：
```swift
    /// AGENT_EDIT_RESULT 渲染 E2E（确定性）：/editdemo → 生成图落盘 → 编辑结果图卡出现。
    func testEditResultDemo() throws {
        navigateToChat()
        let input = app.textFields["chat_input"].exists
            ? app.textFields["chat_input"]
            : app.textViews["chat_input"]
        XCTAssertTrue(input.waitForExistence(timeout: 12), "chat_input 未找到")
        input.tap()
        input.typeText("/editdemo")
        tapSend()

        let card = app.descendants(matching: .any)["chat_edit_image"].firstMatch
        let appeared = card.waitForExistence(timeout: 15)

        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = appeared ? "editdemo_PASS" : "editdemo_FAIL"
        attachment.lifetime = .keepAlways
        add(attachment)
        XCTAssertTrue(appeared, "❌ /editdemo 未渲染编辑结果图卡")
    }
```

- [ ] **Step 3: 全量验证**

```bash
cd iosApp && xcodegen generate
xcodebuild -workspace PoLang.xcworkspace -scheme PoLang -destination 'generic/platform=iOS' build 2>&1 | tail -2
xcodebuild test -workspace PoLang.xcworkspace -scheme PoLang \
  -destination 'id=00008120-000105443AD2201E' \
  -only-testing:PoLangTests/ChatMessageTypeTests \
  -only-testing:PoLangTests/JsCoreEngineTest \
  -only-testing:PoLangUITests/ChatSmokeUITests/testEditResultDemo 2>&1 | grep -E "Test Case.*(passed|failed)|TEST (SUCCEEDED|FAILED)"
```
Expected: BUILD SUCCEEDED；测试全 PASS（含既有 JsCoreEngineTest 9 用例防回归）。

- [ ] **Step 4: 文档同步 + Commit**

更新 `docs/superpowers/plans/2026-08-13-ios-chat-rich-features.md` 执行进展段：Task 3 标注「✅ 以字段对齐方式完成（批次②，iOS Swift-native 消费 type/imageUri，非 KMP 直连）」。

```bash
git add iosApp/PoLang/Resources/Localizable.xcstrings iosApp/PoLangUITests/ChatSmokeUITests.swift docs/superpowers/plans/2026-08-13-ios-chat-rich-features.md
git commit -m "feat(ios): Chat 图片消息 i18n 三语 + testEditResultDemo E2E (chat 图片消息 Task 6)"
```

---

## 风险提示（执行方注意）

- **`xcodegen generate` 仅在新增 .swift 文件后必须**（Task 1/3/4 有新文件；Task 2/5/6 改既有文件可跳过）。
- **`xcodebuild build` 不编译测试 target**——单测须用 `test` 动作跑（批次①教训）。
- **`String(localized:)` 新 key** 在 xcstrings 未加条目时显示 key 原文（不崩）；三语必须同步（[I18N] 红线）。
- **编辑器 save() 并发**：`editResultPath` 为 Task 局部变量（非实例态），无竞态；`onEditResult` 在 MainActor 回调（与 onSaved 同点）。
- **回链闭包生命周期**：`ChatEditResultBridge.onEditResult` 由 ChatViewModel.configure 以 `[weak self]` 设置——ChatView 销毁后闭包失效安全（nil 弱引用 → no-op），下次 configure 重设（与 ChartRendererBridge.onChart 同生命周期，已验证范式）。
- **老会话历史兼容**：`init(from:)` 推断路径由 Task 1 单测锁定；若历史 JSON 缺 `isStreaming` 等字段（理论上不存在——旧 CodingKeys 恒编码）会 decode 失败 → ChatHistoryStore 兜底 `?? []`，不崩。
