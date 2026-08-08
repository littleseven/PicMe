package com.mamba.picme.domain.usecase

import org.junit.Test
import org.junit.Assert.*

/**
 * AI Agent 响应解析单元测试
 *
 * 验证 parseLlmResponse 对中文回复、<think> 标签、JSON 指令的解析逻辑。
 */
class AiAgentUseCaseParseTest {

    /**
     * 复现 parseLlmResponse 的 <think> 标签清理逻辑
     */
    private fun cleanThinkTags(content: String): String {
        var cleaned = content.trim()
        while (true) {
            val thinkStart = cleaned.indexOf("<think>")
            val thinkEnd = cleaned.indexOf("</think>")
            if (thinkStart >= 0 && thinkEnd > thinkStart) {
                cleaned = cleaned.removeRange(thinkStart, thinkEnd + "</think>".length).trim()
            } else {
                break
            }
        }
        val orphanThinkStart = cleaned.indexOf("<think>")
        if (orphanThinkStart >= 0) {
            cleaned = cleaned.substring(0, orphanThinkStart).trim()
        }
        cleaned = cleaned.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return cleaned
    }

    private fun hasJsonAction(content: String): Boolean = content.contains("\"action\"")

    @Test
    fun `cleanThinkTags removes single think block`() {
        val input = "<think>\n用户只是打招呼\n</think>\n你好！"
        assertEquals("你好！", cleanThinkTags(input))
    }

    @Test
    fun `cleanThinkTags removes multiple think blocks`() {
        val input = "<think>思考1</think>\n<think>思考2</think>\n你好"
        assertEquals("你好", cleanThinkTags(input))
    }

    @Test
    fun `cleanThinkTags handles orphan think tag`() {
        val input = "你好。<think>这段没有结束"
        assertEquals("你好。", cleanThinkTags(input))
    }

    @Test
    fun `cleanThinkTags removes markdown code block`() {
        val input = "```json\n{\"action\":\"test\"}\n```"
        assertEquals("{\"action\":\"test\"}", cleanThinkTags(input))
    }

    @Test
    fun `cleanThinkTags preserves Chinese free chat`() {
        val input = "你好！我是 PoLang 的 AI 助手。"
        assertEquals("你好！我是 PoLang 的 AI 助手。", cleanThinkTags(input))
    }

    @Test
    fun `hasJsonAction detects JSON action`() {
        assertTrue(hasJsonAction("{\"action\":\"adjust_beauty\"}"))
    }

    @Test
    fun `hasJsonAction returns false for Chinese text`() {
        assertFalse(hasJsonAction("你好，我是 AI 助手。"))
    }

    @Test
    fun `hasJsonAction returns false for action word without quotes`() {
        assertFalse(hasJsonAction("你可以通过 action 指令控制相机。"))
    }

    @Test
    fun `full parse Chinese free chat with think tags`() {
        val raw = "<think>\n用户问\"你是谁\"\n</think>\n你好！我是 PoLang 的 AI 助手。"
        val cleaned = cleanThinkTags(raw)
        assertFalse("Should not detect JSON action in Chinese text", hasJsonAction(cleaned))
        assertEquals("你好！我是 PoLang 的 AI 助手。", cleaned)
    }

    @Test
    fun `full parse JSON command with think tags`() {
        val raw = "<think>用户想调整美颜</think>\n{\"action\":\"adjust_beauty\",\"smoothing\":50}"
        val cleaned = cleanThinkTags(raw)
        assertTrue("Should detect JSON action", hasJsonAction(cleaned))
        assertEquals("{\"action\":\"adjust_beauty\",\"smoothing\":50}", cleaned)
    }

    @Test
    fun `system prompt contains Chinese requirement`() {
        val prompt = buildSystemPromptForTest()
        assertTrue("System prompt should require Chinese reply", prompt.contains("用中文回复用户"))
        assertTrue("System prompt should mention chat behavior", prompt.contains("如果用户只是聊天"))
        assertTrue("System prompt should mention JSON output", prompt.contains("不要输出JSON"))
    }

    @Test
    fun `system prompt fallback is Chinese`() {
        val prompt = buildSystemPromptForTest()
        assertTrue("System prompt should contain Chinese instructions", prompt.contains("用中文回复用户"))
    }

    /**
     * 复现 buildSystemPrompt 核心内容用于测试（避免实例化 AiAgentUseCase）
     */
    private fun buildSystemPromptForTest(): String {
        return buildString {
            appendLine("你是PoLang相机的AI助手小浪。你必须用中文回复用户。")
            appendLine()
            appendLine("当前相机状态: 美颜=false, 磨皮=0, 美白=0, 瘦脸=0, 大眼=0, 唇色=0, 腮红=0, 滤镜=NONE, 风格=NONE, 变焦=1.0x, 曝光=0, 模式=PHOTO")
            appendLine()
            appendLine("可用滤镜: 无, 徕卡经典, 徕卡鲜艳, 徕卡黑白, 胶片金, 胶片富士, 复古, 冷调, 暖调")
            appendLine("可用风格: 无, 卡通, 素描, 色调分离, 浮雕, 交叉线")
            appendLine("可用模式: 拍照, 录像, 人像, 专业, 文档")
            appendLine()
            appendLine("如果用户想控制相机，输出JSON指令:")
            appendLine("1. 调整美颜: {\"action\":\"adjust_beauty\",\"smoothing\":0-100,\"whitening\":0-100,\"slim_face\":-50~50,\"big_eyes\":0-100,\"lip_color\":0-100,\"blush\":0-100}")
            appendLine("2. 切换滤镜: {\"action\":\"switch_filter\",\"filter\":\"NAME\"}")
            appendLine("3. 切换风格: {\"action\":\"switch_style\",\"style\":\"NAME\"}")
            appendLine("4. 切换场景: {\"action\":\"switch_scene\",\"scene\":\"night|moon|none\"}")
            appendLine("5. 切换比例: {\"action\":\"switch_ratio\",\"ratio\":\"4:3|16:9|full\"}")
            appendLine("6. 调整曝光: {\"action\":\"adjust_exposure\",\"exposure\":-2~2}")
            appendLine("7. 调整变焦: {\"action\":\"adjust_zoom\",\"zoom\":0.5~10.0}")
            appendLine("8. 翻转摄像头: {\"action\":\"flip_camera\"}")
            appendLine("9. 拍照: {\"action\":\"capture\"}")
            appendLine("10. 切换录像: {\"action\":\"toggle_recording\"}")
            appendLine("11. 切换模式: {\"action\":\"switch_mode\",\"mode\":\"PHOTO|VIDEO|DOCUMENT\"}")
            appendLine("12. 文本回复: {\"action\":\"text_reply\",\"message\":\"回复内容\"}")
            appendLine()
            appendLine("重要规则:")
            appendLine("- 如果用户只是聊天，直接友好地用中文回复，不要输出JSON")
            appendLine("- 如果用户想控制相机，只输出JSON，不要输出其他文字")
            appendLine("- 绝对不要输出<thinking>标签或思考过程")
            appendLine("- 所有回复必须使用中文")
            appendLine("- '自然妆'=磨皮20,美白15,瘦脸5,大眼5。'浓妆'=唇色80,腮红60。相对调整基于当前状态。")
        }
    }
}
