package com.mamba.picme.agent.core.platform.voice

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * KeywordSpotterEngine 单元测试
 *
 * 由于 native KeywordSpotter 在 JVM 单元测试环境中不可用，
 * 本测试专注于文件验证、关键词加载等不依赖 native 初始化的逻辑。
 *
 * Task 14 落点说明：被测 [KeywordSpotterEngine] 在 :shared androidMain（KMP android library
 * 无 androidUnitTest source set），故留 androidApp 单测经 :shared 依赖解析符号。
 */
class KeywordSpotterEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /**
     * KWS 模型必需文件列表（与 KeywordSpotterEngine 内部保持一致）
     */
    private val requiredKwsFiles = listOf(
        "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
        "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
        "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
        "tokens.txt",
        "keywords.txt"
    )

    @Test
    fun `isAvailable returns false when model directory does not exist`() {
        val nonExistentDir = File(tempFolder.root, "missing-kws-model")
        val engine = KeywordSpotterEngine(nonExistentDir.absolutePath)

        assertFalse("模型目录不存在时应返回不可用", engine.isAvailable())
    }

    @Test
    fun `isAvailable returns false when required files are missing`() {
        val modelDir = tempFolder.newFolder("incomplete-kws-model")
        // 只创建部分文件，缺少 joiner 和 keywords
        File(modelDir, "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx").writeText("dummy")
        File(modelDir, "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx").writeText("dummy")

        val engine = KeywordSpotterEngine(modelDir.absolutePath)

        assertFalse("缺少必需文件时应返回不可用", engine.isAvailable())
    }

    @Test
    fun `getKeywords returns empty list when keywords file is missing`() {
        val modelDir = tempFolder.newFolder("kws-no-keywords")
        val engine = KeywordSpotterEngine(modelDir.absolutePath)

        assertTrue("keywords.txt 不存在时应返回空列表", engine.getKeywords().isEmpty())
    }

    @Test
    fun `getKeywords parses keywords file correctly`() {
        val modelDir = tempFolder.newFolder("kws-with-keywords")
        File(modelDir, "keywords.txt").writeText(
            """
            小浪
            小蜜
            小秘
            # 这是注释，应被忽略

            小米
            """.trimIndent()
        )

        val engine = KeywordSpotterEngine(modelDir.absolutePath)
        val keywords = engine.getKeywords()

        assertEquals("应正确解析并过滤空行和注释", listOf("小浪", "小蜜", "小秘", "小米"), keywords)
    }

    @Test
    fun `getKeywords trims whitespace and ignores empty lines`() {
        val modelDir = tempFolder.newFolder("kws-whitespace")
        File(modelDir, "keywords.txt").writeText(
            """
              小浪  

              小蜜
            """.trimIndent()
        )

        val engine = KeywordSpotterEngine(modelDir.absolutePath)
        val keywords = engine.getKeywords()

        assertEquals("应去除空白并忽略空行", listOf("小浪", "小蜜"), keywords)
    }

    @Test
    fun `getKeywords caches loaded result`() {
        val modelDir = tempFolder.newFolder("kws-cache")
        val keywordsFile = File(modelDir, "keywords.txt").apply {
            writeText("小浪\n小蜜\n")
        }

        val engine = KeywordSpotterEngine(modelDir.absolutePath)
        val first = engine.getKeywords()
        val second = engine.getKeywords()

        assertEquals("两次读取结果应一致", first, second)

        // 修改文件后再次读取，验证缓存生效（仍返回旧结果）
        keywordsFile.writeText("小米\n")
        val third = engine.getKeywords()
        assertEquals("缓存应生效，不应反映文件修改", first, third)
    }

}
