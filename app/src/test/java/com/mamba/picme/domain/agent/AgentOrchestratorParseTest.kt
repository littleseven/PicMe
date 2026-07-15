package com.mamba.picme.domain.agent

import com.mamba.picme.beauty.api.BeautySettings
import com.mamba.picme.beauty.api.FilterType
import com.mamba.picme.beauty.api.StyleFilter
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.agent.core.runtime.capability.CapabilityRegistry
import com.mamba.picme.agent.core.inference.local.parser.LocalCommandParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import com.mamba.picme.agent.core.runtime.cache.IntentCache

/**
 * LocalCommandParser 响应解析逻辑单元测试
 *
 * 直接测试生产方法 [LocalCommandParser.parseLlmResponse] 和
 * [LocalCommandParser.parseCommandByMethod]，确保测试能够真实反映生产代码行为。
 *
 * 提取为独立 object 的原因：避免实例化 [AgentOrchestrator] 时触发
 * [LocalLlmEngine] -> [MnnLlmClient] 的 JNI 加载，导致纯 JVM 单元测试失败。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentOrchestratorParseTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val defaultContext = AgentContext(
        scene = AgentScene.CAMERA,
        beautySettings = BeautySettings(),
        filterType = FilterType.NONE,
        styleFilter = StyleFilter.NONE,
        zoomRatio = 1f,
        exposureCompensation = 0,
        captureMode = MediaType.PHOTO,
        isRecording = false
    )

    // ------------------------------------------------------------------
    // 直接测试生产方法 parseLlmResponse
    // ------------------------------------------------------------------

    @Test
    fun `parseLlmResponse removes closed think tags`() {
        val input = "<thinking>思考中</thinking>\n{\"action\":\"capture\"}"
        val command = LocalCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(command is AgentCommand.CapturePhoto)
    }

    @Test
    fun `parseLlmResponse removes qwen3 think tags`() {
        val input = "<think>用户想拍照，触发capture</think>\n{\"action\":\"capture\"}"
        val command = LocalCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(command is AgentCommand.CapturePhoto)
    }

    @Test
    fun `parseLlmResponse removes nested think and thinking tags`() {
        val input = "<think>思考1</think>\n<thinking>思考2</thinking>\n{\"action\":\"capture\"}"
        val command = LocalCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(command is AgentCommand.CapturePhoto)
    }

    @Test
    fun `parseLlmResponse removes multiple think tags`() {
        val input = "<thinking>1</thinking>\n<thinking>2</thinking>\n{\"action\":\"capture\"}"
        val command = LocalCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(command is AgentCommand.CapturePhoto)
    }

    @Test
    fun `parseLlmResponse truncates orphan think tag`() {
        val input = "你好。<thinking>未结束"
        val command = LocalCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(command is AgentCommand.TextReply)
        assertEquals("你好。", (command as AgentCommand.TextReply).message)
    }

    @Test
    fun `parseLlmResponse removes markdown code block`() {
        val input = "```json\n{\"action\":\"capture\"}\n```"
        val command = LocalCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(command is AgentCommand.CapturePhoto)
    }

    @Test
    fun `parseLlmResponse handles free chat without method`() {
        val input = "你好！我是小觅，有什么可以帮你的吗？"
        val command = LocalCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(command is AgentCommand.TextReply)
    }

    @Test
    fun `parseLlmResponse full pipeline with think tags and markdown`() {
        val input = "<thinking>用户想拍照</thinking>\n```json\n{\"action\":\"capture\"}\n```"
        val command = LocalCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(command is AgentCommand.CapturePhoto)
    }

    @Test
    fun `parseLlmResponse full pipeline with qwen3 think tags and markdown`() {
        val input = "<think>用户说拍照，需要触发相机快门</think>\n```json\n{\"action\":\"capture\"}\n```"
        val command = LocalCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(command is AgentCommand.CapturePhoto)
    }

    // ------------------------------------------------------------------
    // README 示例场景覆盖测试：确保 4 个核心场景 keyword 兜底能命中
    // ------------------------------------------------------------------

    @Test
    fun `readme scenario paiZhangZhao maps to CapturePhoto via keyword`() {
        val input = "拍张照"
        val command = LocalCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(
            "「拍张照」应解析为 CapturePhoto",
            command is AgentCommand.CapturePhoto
        )
    }

    @Test
    fun `readme scenario diaoGaoMeiYan maps to AdjustBeauty via keyword`() {
        val input = "调高美颜"
        val command = LocalCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(
            "「调高美颜」应解析为 AdjustBeauty",
            command is AgentCommand.AdjustBeauty
        )
        val beauty = command as AgentCommand.AdjustBeauty
        assertTrue("调高美颜后磨皮应 > 0", beauty.settings.smoothing > 0)
        assertTrue("调高美颜后美白应 > 0", beauty.settings.whitening > 0)
        assertTrue("调高美颜后美颜应启用", beauty.settings.enabled)
    }

    @Test
    fun `readme scenario huanLengDiaoLvJing maps to SwitchFilter COOL via keyword`() {
        val input = "换个冷调滤镜"
        val command = LocalCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(
            "「换个冷调滤镜」应解析为 SwitchFilter",
            command is AgentCommand.SwitchFilter
        )
        assertEquals(
            FilterType.COOL,
            (command as AgentCommand.SwitchFilter).filterType
        )
    }

    @Test
    fun `readme scenario daKaiQianZhi maps to FlipCamera via keyword`() {
        val input = "打开前置"
        val command = LocalCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(
            "「打开前置」应解析为 FlipCamera",
            command is AgentCommand.FlipCamera
        )
    }

    @Test
    fun `readme scenario huanLengDiao maps to SwitchFilter COOL via IntentCache`() {
        val input = "换冷调"
        val cache = IntentCache()
        val result = cache.match(input)
        assertNotNull("「换冷调」应在 L1 Cache 中", result)
        assertTrue(result is AgentCommand.SwitchFilter)
        assertEquals(FilterType.COOL, (result as AgentCommand.SwitchFilter).filterType)
    }

    @Test
    fun `parseLlmResponse qwen3 unclosed think tag extracts json after`() {
        val input = "<think>用户想拍照\n{\"action\":\"capture\"}"
        val command = LocalCommandParser.parseLlmResponse(input, defaultContext)
        // 修复后：从未闭合 think 标签后提取 JSON
        assertTrue(
            "未闭合 think 标签后包含 JSON 时应正确解析",
            command is AgentCommand.CapturePhoto
        )
    }

    @Test
    fun `parseLlmResponse orphan think tag with json after is cleaned`() {
        val input = "<think>思考过程...\n{\"action\":\"capture\"}\n</think>\n{\"action\":\"capture\"}"
        val command = LocalCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(command is AgentCommand.CapturePhoto)
    }

    @Test
    fun `parseLlmResponse real qwen3 response with unclosed think and json`() {
        val input = "<think>\n好的，用户发来的\"拍照\"指令。根据规则，当用户想要控制相机时，直接输出JSON。因此，生成对应的拍照指令。\n\n{\"action\":\"capture\"}\n```"
        val command = LocalCommandParser.parseLlmResponse(input, defaultContext)
        // 修复后：从未闭合 think 标签后提取 JSON，并清理 markdown
        assertTrue(
            "真实 Qwen3 响应应正确提取 capture 指令",
            command is AgentCommand.CapturePhoto
        )
    }

    @Test
    fun `parseLlmResponse extracts json inside think tag when no json outside`() {
        // 模拟 Qwen3-0.6B 真实输出：JSON 藏在 think 标签内部，外部没有 JSON
        val input = "<think>\n好的，用户说\"去相册\"，我需要按照规则处理。正确的做法是生成对应的 JSON 对象。\n\n{\"action\":\"navigate_to\",\"destination\":\"gallery\"}\n</think>"
        val command = LocalCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(
            "think 标签内部包含 JSON 时应正确提取",
            command is AgentCommand.NavigateTo
        )
        assertEquals("gallery", (command as AgentCommand.NavigateTo).destination)
    }

    @Test
    fun `parseLlmResponse think tag with only thinking no json falls back to keywords`() {
        // think 标签内没有 JSON，外部也没有，但包含关键词
        val input = "<think>\n用户说去相册，我应该导航到相册页面\n</think>"
        val command = LocalCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(
            "think 标签内无 JSON 时应走关键词兜底",
            command is AgentCommand.NavigateTo
        )
        assertEquals("gallery", (command as AgentCommand.NavigateTo).destination)
    }

    // ------------------------------------------------------------------
    // 关键回归测试：photo 别名映射到 CapturePhoto
    // 这些测试如果之前存在，就能发现 LLM 输出 method=photo 时无法拍照的 bug
    // ------------------------------------------------------------------

    @Test
    fun `parseLlmResponse photo alias maps to CapturePhoto`() {
        val input = "{\"action\":\"photo\"}"
        val command = LocalCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(
            "LLM 输出 method=photo 时必须解析为 CapturePhoto",
            command is AgentCommand.CapturePhoto
        )
    }

    @Test
    fun `parseLlmResponse photo alias with think tags`() {
        val input = "<think>用户说拍照</think>\n{\"action\":\"photo\"}"
        val command = LocalCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(
            "photo 别名在 think 标签清理后必须正确解析",
            command is AgentCommand.CapturePhoto
        )
    }

    @Test
    fun `parseLlmResponse photo alias with markdown block`() {
        val input = "```json\n{\"action\":\"photo\"}\n```"
        val command = LocalCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(
            "markdown 包裹的 photo method 必须解析为 CapturePhoto",
            command is AgentCommand.CapturePhoto
        )
    }

    // ------------------------------------------------------------------
    // 直接测试生产方法 parseCommandByMethod
    // ------------------------------------------------------------------

    @Test
    fun `parseCommandByMethod capture returns CapturePhoto`() {
        val command = LocalCommandParser.parseCommandByMethod(
            method = "capture",
            json = "{\"action\":\"capture\"}",
            context = defaultContext,
            fallbackText = ""
        )
        assertTrue(command is AgentCommand.CapturePhoto)
    }

    @Test
    fun `parseCommandByMethod photo returns CapturePhoto`() {
        val command = LocalCommandParser.parseCommandByMethod(
            method = "photo",
            json = "{\"action\":\"photo\"}",
            context = defaultContext,
            fallbackText = ""
        )
        assertTrue(
            "method=photo 必须映射为 CapturePhoto",
            command is AgentCommand.CapturePhoto
        )
    }

    @Test
    fun `parseCommandByMethod flip_camera returns FlipCamera`() {
        val command = LocalCommandParser.parseCommandByMethod(
            method = "flip_camera",
            json = "{\"action\":\"flip_camera\"}",
            context = defaultContext,
            fallbackText = ""
        )
        assertTrue(command is AgentCommand.FlipCamera)
    }

    @Test
    fun `parseCommandByMethod adjust_beauty returns AdjustBeauty`() {
        val command = LocalCommandParser.parseCommandByMethod(
            method = "adjust_beauty",
            json = "{\"action\":\"adjust_beauty\",\"smoothing\":0.8}",
            context = defaultContext,
            fallbackText = ""
        )
        assertTrue(command is AgentCommand.AdjustBeauty)
        val beautyCommand = command as AgentCommand.AdjustBeauty
        assertEquals(0.8f, beautyCommand.settings.smoothing, 0.001f)
    }

    @Test
    fun `parseCommandByMethod switch_filter returns SwitchFilter`() {
        val command = LocalCommandParser.parseCommandByMethod(
            method = "switch_filter",
            json = "{\"action\":\"switch_filter\",\"filter\":\"VINTAGE\"}",
            context = defaultContext,
            fallbackText = ""
        )
        assertTrue(command is AgentCommand.SwitchFilter)
        assertEquals(FilterType.VINTAGE, (command as AgentCommand.SwitchFilter).filterType)
    }

    @Test
    fun `parseCommandByMethod unknown method returns TextReply`() {
        val command = LocalCommandParser.parseCommandByMethod(
            method = "unknown_action",
            json = "{\"action\":\"unknown_action\"}",
            context = defaultContext,
            fallbackText = "fallback message"
        )
        assertTrue(command is AgentCommand.TextReply)
        assertEquals("fallback message", (command as AgentCommand.TextReply).message)
    }

    // ------------------------------------------------------------------
    // CapabilityRegistry 测试：未注册 Capability 时返回 Success
    // 这个测试如果之前存在，就能发现 CameraCapability 未注册时命令被丢弃的 bug
    // ------------------------------------------------------------------

    @Test
    fun `capabilityRegistry dispatch CapturePhoto without registered capability returns Error`() = runTest(testDispatcher) {
        val registry = CapabilityRegistry.getInstance()
        val command = AgentCommand.CapturePhoto()
        val result = registry.dispatch(command, defaultContext)
        advanceUntilIdle()
        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        // CameraCapability 未注册时，findCapabilityForCommand 返回 null，dispatch 返回 Error
        assertTrue(action is AgentAction.Error)
        assertEquals("暂不支持此操作", (action as AgentAction.Error).message)
    }

    @Test
    fun `capabilityRegistry dispatch TextReply returns TextReply action`() = runTest(testDispatcher) {
        val registry = CapabilityRegistry.getInstance()
        val command = AgentCommand.TextReply(message = "你好")
        val result = registry.dispatch(command, defaultContext)
        advanceUntilIdle()
        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        assertTrue(action is AgentAction.TextReply)
        assertEquals("你好", (action as AgentAction.TextReply).message)
    }

    @Test
    fun `capabilityRegistry dispatch FlipCamera without registered capability returns Error`() = runTest(testDispatcher) {
        val registry = CapabilityRegistry.getInstance()
        val command = AgentCommand.FlipCamera()
        val result = registry.dispatch(command, defaultContext)
        advanceUntilIdle()
        assertTrue(result.isSuccess)
        val action = result.getOrNull()
        // CameraCapability 未注册时，findCapabilityForCommand 返回 null，dispatch 返回 Error
        assertTrue(action is AgentAction.Error)
        assertEquals("暂不支持此操作", (action as AgentAction.Error).message)
    }

    // ------------------------------------------------------------------
    // 端到端场景测试：模拟真实 LLM 输出
    // ------------------------------------------------------------------

    @Test
    fun `end to end qwen3 real response with photo method`() {
        val input = "<think>\n用户说\"拍照\"，需要触发相机拍照。\n\n{\"action\":\"photo\"}"
        val command = LocalCommandParser.parseLlmResponse(input, defaultContext)
        // 修复后：从未闭合 think 标签后提取 JSON
        assertTrue(
            "未闭合 think + photo method 必须解析为 CapturePhoto",
            command is AgentCommand.CapturePhoto
        )
    }

    @Test
    fun `end to end qwen3 closed think with photo method`() {
        val input = "<think>用户说拍照</think>\n{\"action\":\"photo\"}"
        val command = LocalCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(
            "闭合 think 标签 + photo method 必须解析为 CapturePhoto",
            command is AgentCommand.CapturePhoto
        )
    }

    @Test
    fun `end to end markdown wrapped photo method`() {
        val input = "```json\n{\"action\":\"photo\"}\n```"
        val command = LocalCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(
            "markdown 包裹的 photo method 必须解析为 CapturePhoto",
            command is AgentCommand.CapturePhoto
        )
    }

    // ------------------------------------------------------------------
    // L2 本地快速通道 JSON 数组解析测试
    // ------------------------------------------------------------------

    @Test
    fun `parseLlmResponse parses json array with filter and capture`() {
        val input = "[{\"method\":\"switch_filter\",\"params\":{\"filter\":\"COOL\"}},{\"method\":\"capture\",\"params\":{}}]"
        val command = LocalCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(
            "JSON 数组应解析为 BatchExecute",
            command is AgentCommand.BatchExecute
        )
        val batch = command as AgentCommand.BatchExecute
        assertEquals(2, batch.commands.size)
        assertTrue(batch.commands[0] is AgentCommand.SwitchFilter)
        assertEquals(FilterType.COOL, (batch.commands[0] as AgentCommand.SwitchFilter).filterType)
        assertTrue(batch.commands[1] is AgentCommand.CapturePhoto)
    }

    @Test
    fun `parseLlmResponse parses json array with beauty and capture`() {
        val input = "[{\"method\":\"adjust_beauty\",\"params\":{\"smoothing\":60,\"whitening\":30}},{\"method\":\"capture\",\"params\":{}}]"
        val command = LocalCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(
            "美颜+拍照 JSON 数组应解析为 BatchExecute",
            command is AgentCommand.BatchExecute
        )
        val batch = command as AgentCommand.BatchExecute
        assertEquals(2, batch.commands.size)
        assertTrue(batch.commands[0] is AgentCommand.AdjustBeauty)
        assertTrue(batch.commands[1] is AgentCommand.CapturePhoto)
    }

    @Test
    fun `parseLlmResponse parses json array with delay filter and capture`() {
        val input = "[{\"method\":\"delay\",\"params\":{\"delay_ms\":3000}},{\"method\":\"switch_filter\",\"params\":{\"filter\":\"WARM\"}},{\"method\":\"capture\",\"params\":{}}]"
        val command = LocalCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(
            "延迟+滤镜+拍照 JSON 数组应解析为 BatchExecute",
            command is AgentCommand.BatchExecute
        )
        val batch = command as AgentCommand.BatchExecute
        assertEquals(3, batch.commands.size)
        assertTrue(batch.commands[0] is AgentCommand.Delay)
        assertTrue(batch.commands[1] is AgentCommand.SwitchFilter)
        assertTrue(batch.commands[2] is AgentCommand.CapturePhoto)
    }

    @Test
    fun `parseLlmResponse single json object still works`() {
        val input = "{\"method\":\"capture\",\"params\":{}}"
        val command = LocalCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(
            "单个 JSON 对象仍应正常解析",
            command is AgentCommand.CapturePhoto
        )
    }

    // ------------------------------------------------------------------
    // AI 一键优化关键词兜底测试
    // ------------------------------------------------------------------

    @Test
    fun `parseLlmResponse optimize photo keyword maps to AiOptimize`() {
        val input = "优化这张照片"
        val command = LocalCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(
            "「优化这张照片」应解析为 AiOptimize",
            command is AgentCommand.AiOptimize
        )
        val optimize = command as AgentCommand.AiOptimize
        assertEquals("fast", optimize.mode)
    }

    @Test
    fun `parseLlmResponse repair portrait keyword maps to AiOptimize`() {
        val input = "把这张人像修一下"
        val command = LocalCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(
            "「把这张人像修一下」应解析为 AiOptimize",
            command is AgentCommand.AiOptimize
        )
    }

    @Test
    fun `parseLlmResponse beautify keyword maps to AiOptimize`() {
        val input = "帮我美化一下这张图"
        val command = LocalCommandParser.parseLlmResponse(input, defaultContext)
        assertTrue(
            "「帮我美化一下这张图」应解析为 AiOptimize",
            command is AgentCommand.AiOptimize
        )
    }

    @Test
    fun `parseCommandByMethod ai_optimize returns AiOptimize`() {
        val command = LocalCommandParser.parseCommandByMethod(
            method = "ai_optimize",
            json = "{\"action\":\"ai_optimize\",\"image_uri\":\"file:///test.jpg\",\"mode\":\"smart\"}",
            context = defaultContext,
            fallbackText = ""
        )
        assertTrue(command is AgentCommand.AiOptimize)
        val optimize = command as AgentCommand.AiOptimize
        assertEquals("file:///test.jpg", optimize.imageUri)
        assertEquals("smart", optimize.mode)
    }

    // ------------------------------------------------------------------
    // 回归测试：JSON 数组解析需跳过字符串中的花括号
    // ------------------------------------------------------------------

    @Test
    fun `parseL2BatchResponse skips braces inside quoted strings`() {
        val input = "[{\"method\":\"text_reply\",\"params\":{\"message\":\"你好，我是{小觅}\"}}]"
        val commands = LocalCommandParser.parseL2BatchResponse(input, defaultContext)
        assertEquals(1, commands.size)
        assertTrue(commands.first() is AgentCommand.TextReply)
        assertEquals("你好，我是{小觅}", (commands.first() as AgentCommand.TextReply).message)
    }

    @Test
    fun `parseL2BatchResponse skips escaped quotes inside strings`() {
        val input = "[{\"method\":\"text_reply\",\"params\":{\"message\":\"他说\\\"你好\\\"\"}}]"
        val commands = LocalCommandParser.parseL2BatchResponse(input, defaultContext)
        assertEquals(1, commands.size)
        assertTrue(commands.first() is AgentCommand.TextReply)
    }

    @Test
    fun `parseL2BatchResponse text_reply self introduction`() {
        val input = "[{\"method\":\"text_reply\",\"params\":{\"message\":\"你好，我是 PicMe 的摄影助手小觅，可以帮你拍照、搜照片、调整设置等。\"}}]"
        val commands = LocalCommandParser.parseL2BatchResponse(input, defaultContext)
        assertEquals(1, commands.size)
        assertTrue(commands.first() is AgentCommand.TextReply)
        assertEquals(
            "你好，我是 PicMe 的摄影助手小觅，可以帮你拍照、搜照片、调整设置等。",
            (commands.first() as AgentCommand.TextReply).message
        )
    }

}
