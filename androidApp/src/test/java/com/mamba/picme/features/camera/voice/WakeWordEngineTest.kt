package com.mamba.picme.features.camera.voice

import com.mamba.picme.agent.core.platform.voice.AsrEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * WakeWordEngine 唤醒词过滤逻辑单元测试
 *
 * 测试 [WakeWordEngine.stripWakeWord] 和 [WakeWordEngine.findMatchedWakeWord] 方法。
 */
class WakeWordEngineTest {

    // 通过协程 scope 和 mock asrEngine 构造实例仅用于测试 internal 方法

    // ── findMatchedWakeWord 测试：同音字变体识别 ──────────────────────────

    @Test
    fun `findMatchedWakeWord matches standard wake word`() {
        val engine = createEngine()
        assertEquals("小浪", engine.findMatchedWakeWord("小浪拍张照"))
    }

    @Test
    fun `findMatchedWakeWord matches homophone xiao mi`() {
        val engine = createEngine()
        // 小蜜是 ASR 最常见的误识变体
        assertEquals("小蜜", engine.findMatchedWakeWord("小蜜拍张照"))
    }

    @Test
    fun `findMatchedWakeWord matches homophone xiao mi secretary`() {
        val engine = createEngine()
        assertEquals("小秘", engine.findMatchedWakeWord("小秘调高美颜"))
    }

    @Test
    fun `findMatchedWakeWord matches xiao mi rice`() {
        val engine = createEngine()
        // 小米是极常见的词，ASR 可能输出此变体
        assertEquals("小米", engine.findMatchedWakeWord("小米打开前置"))
    }

    @Test
    fun `findMatchedWakeWord matches xiao mi cat`() {
        val engine = createEngine()
        assertEquals("小咪", engine.findMatchedWakeWord("小咪换个冷调滤镜"))
    }

    @Test
    fun `findMatchedWakeWord returns null for no match`() {
        val engine = createEngine()
        assertNull("无关文本不应匹配", engine.findMatchedWakeWord("今天天气不错"))
    }

    @Test
    fun `findMatchedWakeWord returns null for empty string`() {
        val engine = createEngine()
        assertNull("空字符串不应匹配", engine.findMatchedWakeWord(""))
    }

    // ── stripWakeWord 测试：基本唤醒词移除 ──────────────────────────────

    @Test
    fun `stripWakeWord removes wake word prefix`() {
        val engine = createEngine()
        val result = engine.stripWakeWord("小浪拍张照", "小浪")
        assertEquals("拍张照", result)
    }

    @Test
    fun `stripWakeWord removes wake word suffix`() {
        val engine = createEngine()
        val result = engine.stripWakeWord("拍张照小浪", "小浪")
        assertEquals("拍张照", result)
    }

    @Test
    fun `stripWakeWord removes wake word infix`() {
        val engine = createEngine()
        val result = engine.stripWakeWord("帮我小浪拍张照", "小浪")
        assertEquals("帮我拍张照", result)
    }

    @Test
    fun `stripWakeWord removes multiple wake words`() {
        val engine = createEngine()
        val result = engine.stripWakeWord("小浪小浪拍张照", "小浪")
        assertEquals("拍张照", result)
    }

    @Test
    fun `stripWakeWord handles wake word only`() {
        val engine = createEngine()
        val result = engine.stripWakeWord("小浪", "小浪")
        assertEquals("", result)
    }

    @Test
    fun `stripWakeWord returns original when no wake word`() {
        val engine = createEngine()
        val result = engine.stripWakeWord("拍张照", "小浪")
        assertEquals("拍张照", result)
    }

    @Test
    fun `stripWakeWord handles empty string`() {
        val engine = createEngine()
        val result = engine.stripWakeWord("", "小浪")
        assertEquals("", result)
    }

    @Test
    fun `stripWakeWord handles transcript with whitespace around wake word`() {
        val engine = createEngine()
        val result = engine.stripWakeWord("小浪 拍张照", "小浪")
        assertEquals("拍张照", result)
    }

    @Test
    fun `stripWakeWord handles complex command`() {
        val engine = createEngine()
        val result = engine.stripWakeWord("小浪调高美颜磨皮80", "小浪")
        assertEquals("调高美颜磨皮80", result)
    }

    @Test
    fun `stripWakeWord handles wake word at both ends`() {
        val engine = createEngine()
        val result = engine.stripWakeWord("小浪拍张照小浪", "小浪")
        assertEquals("拍张照", result)
    }

    // ── stripWakeWord 测试：同音字变体移除 ──────────────────────────────

    @Test
    fun `stripWakeWord removes homophone xiao mi variant`() {
        val engine = createEngine()
        val result = engine.stripWakeWord("小蜜拍张照", "小蜜")
        assertEquals("拍张照", result)
    }

    @Test
    fun `stripWakeWord removes homophone xiao mi rice variant`() {
        val engine = createEngine()
        val result = engine.stripWakeWord("小米调高美颜", "小米")
        assertEquals("调高美颜", result)
    }

    @Test
    fun `stripWakeWord uses default variant when not specified`() {
        val engine = createEngine()
        val result = engine.stripWakeWord("小浪拍张照")
        assertEquals("拍张照", result)
    }

    // ── findMatchedWakeWordWithScore 测试：新增带权重的匹配方法 ──────────────────

    @Test
    fun `findMatchedWakeWordWithScore returns standard wake word with perfect score`() {
        val engine = createEngine()
        val result = engine.findMatchedWakeWordWithScore("小浪拍张照")
        assertNotNull("应该匹配到小浪", result)
        assertEquals("小浪", result?.first)
        assertEquals(1.0f, result?.second)
    }

    @Test
    fun `findMatchedWakeWordWithScore returns homophone with high confidence`() {
        val engine = createEngine()
        val result = engine.findMatchedWakeWordWithScore("小蜜拍张照")
        assertNotNull("应该匹配到小蜜", result)
        assertEquals("小蜜", result?.first)
        // 小蜜的信心度应该接近 0.95
        assertEquals(0.95f, result?.second)
    }

    @Test
    fun `findMatchedWakeWordWithScore prefers higher confidence match`() {
        val engine = createEngine()
        // 小米同时包含"小"和"米"，但应该匹配整个"小米"而不是部分
        val result = engine.findMatchedWakeWordWithScore("小米打开")
        assertNotNull("应该匹配到小米", result)
        assertEquals("小米", result?.first)
        // 小米的信心度应该是 0.88
        assertEquals(0.88f, result?.second)
    }

    @Test
    fun `findMatchedWakeWordWithScore returns null for no match`() {
        val engine = createEngine()
        val result = engine.findMatchedWakeWordWithScore("今天天气不错")
        assertNull("无关文本应该返回 null", result)
    }

    // ── 新增关键词测试：口语启动词 ────────────────────────────────────────────

    @Test
    fun `findMatchedWakeWord matches oral prefix variant hey`() {
        val engine = createEngine()
        // 嘿小浪 - 口语启动词 + 唤醒词
        assertEquals("嘿小浪", engine.findMatchedWakeWord("嘿小浪拍照"))
    }

    @Test
    fun `findMatchedWakeWord matches oral prefix variant call`() {
        val engine = createEngine()
        // 哎小浪 - 口语启动词
        assertEquals("哎小浪", engine.findMatchedWakeWord("哎小浪换个美颜"))
    }

    @Test
    fun `findMatchedWakeWord matches greeting variant`() {
        val engine = createEngine()
        // 小浪你好 - 打招呼表达
        assertEquals("小浪你好", engine.findMatchedWakeWord("小浪你好拍张照"))
    }

    @Test
    fun `stripWakeWord removes oral prefix variant`() {
        val engine = createEngine()
        // 嘿小浪拍照 → 拍照
        val result = engine.stripWakeWord("嘿小浪拍照", "嘿小浪")
        assertEquals("拍照", result)
    }

    @Test
    fun `stripWakeWord handles mixed wake word variants`() {
        val engine = createEngine()
        // 复杂场景：同时有前缀和唤醒词
        // 嘿小浪 被识别为唤醒词，需要彻底移除
        val result = engine.stripWakeWord("嘿小浪拍照", "嘿小浪")
        assertEquals("拍照", result)
    }

    @Test
    fun `stripWakeWord removes all variant occurrences`() {
        val engine = createEngine()
        // 测试移除多个不同的唤醒词变体
        // "小蜜拍照小米" → 应该移除 小蜜 和 小米，留下 拍照
        val result = engine.stripWakeWord("小蜜拍照小米", "小蜜")
        // 先移除 "小蜜"，再移除其他变体如 "小米"
        assertEquals("拍照", result)
    }

    @Test
    fun `stripWakeWord handles variant with tone particle`() {
        val engine = createEngine()
        // 小浪啊 - 带语气助词的唤醒词
        val result = engine.stripWakeWord("小浪啊拍张照", "小浪啊")
        assertEquals("拍张照", result)
    }

    @Test
    fun `stripWakeWord handles close sound variant`() {
        val engine = createEngine()
        // 小妹 - 近音变体（可能被误识）
        val result = engine.stripWakeWord("小妹调整美颜", "小妹")
        assertEquals("调整美颜", result)
    }

    /**
     * 创建测试用 WakeWordEngine 实例
     * 需要传入非空 scope，但 stripWakeWord/findMatchedWakeWord 不依赖 scope
     */
    private fun createEngine(): WakeWordEngine {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val asrEngine = object : AsrEngine {
            override suspend fun transcribe(audioData: ByteArray): Result<String> = Result.success("")
            override fun isAvailable(): Boolean = true
        }
        return WakeWordEngine(asrEngine, scope)
    }
}
