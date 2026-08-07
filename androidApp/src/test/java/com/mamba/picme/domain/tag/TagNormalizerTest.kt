package com.mamba.picme.domain.tag

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagNormalizerTest {

    /**
     * 复现 SmolVLM 中文标签被编辑距离容错误映射的 vocab：
     * 「宝宝/佛像/商场/毕业/旅行/办公室」在词表，「珠宝/职场/人像/专业/执行」不在。
     * 修复前 bestMatch 用编辑距离≤1 容错，把「珠宝」→「宝宝」、「职场」→「商场」等
     * 近形词误映射（中文 2 字词替换 1 字语义全变，但 levenshtein=1）。
     */
    private val vocab = ControlledVocab(
        people = listOf("女性", "男性", "成年人", "宝宝"),
        architecture = listOf("商场", "办公室", "佛像"),
        activity = listOf("自拍", "毕业", "旅行"),
        objects = listOf("室内", "电脑", "文件"),
        synonyms = mapOf("女" to "女性")
    )
    private val normalizer = TagNormalizer(vocab)

    @Test
    fun `中文两字词不被编辑距离容错映射到近形词`() {
        val result = normalizer.normalize(
            QwenTags(tags = listOf("珠宝", "职场", "人像", "专业", "执行"))
        )
        // SmolVLM 输出的规范标签应保留原词
        listOf("珠宝", "职场", "人像", "专业", "执行").forEach { word ->
            assertTrue("$word 应保留原词，实际 tags=${result.tags}", word in result.tags)
        }
        // 不应被编辑距离≤1 误映射到词表里的近形词
        listOf("宝宝", "商场", "佛像", "毕业", "旅行").forEach { wrong ->
            assertFalse("$wrong 不应出现（编辑距离误映射），实际 tags=${result.tags}", wrong in result.tags)
        }
    }

    @Test
    fun `精确匹配与同义词映射仍正常`() {
        val result = normalizer.normalize(QwenTags(tags = listOf("女", "自拍")))
        assertTrue("「女」应经同义词映射为「女性」", "女性" in result.tags)
        assertTrue("「自拍」精确匹配应保留", "自拍" in result.tags)
    }
}
