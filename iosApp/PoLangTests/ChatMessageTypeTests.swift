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
