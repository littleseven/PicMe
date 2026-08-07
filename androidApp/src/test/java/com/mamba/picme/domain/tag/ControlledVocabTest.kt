package com.mamba.picme.domain.tag

import com.mamba.picme.domain.model.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ControlledVocab 解析与语言感知候选测试
 */
class ControlledVocabTest {

    @Test
    fun `parseJson loads both Chinese and English categories`() {
        val json = """
            {
              "scene": ["室内", "户外"],
              "scene_en": ["indoor", "outdoor"],
              "objects": ["猫", "狗"],
              "objects_en": ["cat", "dog"],
              "people": ["男性", "女性"],
              "people_en": ["male", "female"],
              "activity": ["吃饭"],
              "activity_en": ["eating"],
              "atmosphere": ["白天"],
              "atmosphere_en": ["daytime"],
              "clothing": ["衬衫"],
              "clothing_en": ["shirt"],
              "animal": ["鸟"],
              "animal_en": ["bird"],
              "food_drink": ["咖啡"],
              "food_drink_en": ["coffee"],
              "architecture": ["教堂"],
              "architecture_en": ["church"],
              "nature": ["树"],
              "nature_en": ["tree"],
              "transport": ["汽车"],
              "transport_en": ["car"],
              "style": ["性感"],
              "style_en": ["sexy"],
              "synonyms": { "帅哥": "男性" }
            }
        """.trimIndent()

        val vocab = ControlledVocab.parseJsonForTest(json)

        assertEquals(listOf("室内", "户外"), vocab.scene)
        assertEquals(listOf("indoor", "outdoor"), vocab.sceneEn)
        assertEquals(listOf("猫", "狗"), vocab.objects)
        assertEquals(listOf("cat", "dog"), vocab.objectsEn)
        assertEquals(listOf("性感"), vocab.style)
        assertEquals(listOf("sexy"), vocab.styleEn)
        assertEquals(mapOf("帅哥" to "男性"), vocab.synonyms)
    }

    @Test
    fun `sceneCandidates returns correct language`() {
        val vocab = ControlledVocab(
            scene = listOf("室内"),
            sceneEn = listOf("indoor")
        )

        assertEquals(listOf("室内"), vocab.sceneCandidates(AppLanguage.CHINESE))
        assertEquals(listOf("indoor"), vocab.sceneCandidates(AppLanguage.ENGLISH))
        assertEquals(emptyList<String>(), vocab.sceneCandidates(AppLanguage.SYSTEM))
    }

    @Test
    fun `objectCandidates returns correct language`() {
        val vocab = ControlledVocab(
            objects = listOf("猫"),
            objectsEn = listOf("cat")
        )

        assertEquals(listOf("猫"), vocab.objectCandidates(AppLanguage.CHINESE))
        assertEquals(listOf("cat"), vocab.objectCandidates(AppLanguage.ENGLISH))
        assertEquals(emptyList<String>(), vocab.objectCandidates(AppLanguage.TRADITIONAL_CHINESE))
    }

    @Test
    fun `tagCandidates combines correct categories per language`() {
        val vocab = ControlledVocab(
            people = listOf("男性"),
            peopleEn = listOf("male"),
            atmosphere = listOf("白天"),
            atmosphereEn = listOf("daytime"),
            style = listOf("性感"),
            styleEn = listOf("sexy")
        )

        assertEquals(listOf("男性", "白天", "性感"), vocab.tagCandidates(AppLanguage.CHINESE))
        assertEquals(listOf("male", "daytime", "sexy"), vocab.tagCandidates(AppLanguage.ENGLISH))
    }
}
