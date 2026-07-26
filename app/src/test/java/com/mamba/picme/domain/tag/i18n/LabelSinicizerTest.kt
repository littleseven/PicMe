package com.mamba.picme.domain.tag.i18n

import com.mamba.picme.domain.tag.ControlledVocab
import com.mamba.picme.domain.tag.FaceTagInfo
import com.mamba.picme.domain.tag.UnifiedTagResult
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 校验 [LabelSinicizer]：英文 [UnifiedTagResult] → 中文 [UnifiedTagResult]。
 *
 * 主路径：ControlledVocab 平行数组（canonical en→zh）。
 * 兜底：BilingualVocab.enToZh。未命中保留英文。summary 走注入的翻译函数。face 原样复制。
 */
class LabelSinicizerTest {

    private val vocab = ControlledVocab(
        scene = listOf("室内", "海边"),
        sceneEn = listOf("indoor", "beach"),
        animal = listOf("猫", "狗"),
        animalEn = listOf("cat", "dog")
    )

    private val bilingual = BilingualVocab(
        zhToEn = emptyMap(),
        enToZh = mapOf("sunset" to "日落"),
        enSynonyms = emptyMap()
    )

    private fun en() = UnifiedTagResult(
        face = FaceTagInfo(count = 1, selfie = true),
        scene = "indoor",
        activity = "walking",
        objects = listOf("cat", "unknown_thing"),
        tags = listOf("cat", "indoor", "sunset"),
        summary = "a cat indoors at sunset"
    )

    @Test
    fun maps_canonical_english_via_parallel_arrays() {
        val zh = LabelSinicizer(vocab, bilingual).sinicize(en())
        assertEquals("室内", zh.scene)
        // cat -> 猫；unknown_thing 无映射保留
        assertEquals(listOf("猫", "unknown_thing"), zh.objects)
    }

    @Test
    fun falls_back_to_bilingual_vocab_for_non_canonical() {
        // sunset 不在 ControlledVocab，但在 BilingualVocab.enToZh -> 日落
        val zh = LabelSinicizer(vocab, bilingual).sinicize(en())
        assertEquals("日落", zh.tags[2])
    }

    @Test
    fun keeps_english_when_no_mapping_exists() {
        val zh = LabelSinicizer(vocab, bilingual).sinicize(en())
        // walking 无任何映射 -> 保留英文
        assertEquals("walking", zh.activity)
    }

    @Test
    fun translates_summary_via_injected_translator() {
        val zh = LabelSinicizer(vocab, bilingual) { "$it[ZH]" }.sinicize(en())
        assertEquals("a cat indoors at sunset[ZH]", zh.summary)
    }

    @Test
    fun copies_face_as_is() {
        val zh = LabelSinicizer(vocab, bilingual).sinicize(en())
        assertEquals(1, zh.face.count)
        assertEquals(true, zh.face.selfie)
    }

    @Test
    fun is_case_insensitive_on_english_keys() {
        val zh = LabelSinicizer(vocab, bilingual).sinicize(en().copy(scene = "INDOOR"))
        assertEquals("室内", zh.scene)
    }
}
