package com.mamba.picme.data.model

import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.domain.model.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * labelsForLanguage 语言路由防回归：英文/西语/法语 UI → labelsEn，其余 → labelsZh，
 * 目标字段为空时回退 labels（2026-09 五语支持：西/法无独立词表，回退英文）。
 */
class MediaEntityLabelsLanguageTest {

    private fun entity(
        labels: String? = """["混合"]""",
        labelsZh: String? = """["猫"]""",
        labelsEn: String? = """["cat"]"""
    ) = MediaEntity(
        id = 1L,
        uri = "content://media/1",
        type = MediaType.PHOTO,
        captureDate = 1_000L,
        fileName = "img.jpg",
        labels = labels,
        labelsZh = labelsZh,
        labelsEn = labelsEn
    )

    @Test
    fun `西法语言取 labelsEn`() {
        val e = entity()
        assertEquals("""["cat"]""", e.labelsForLanguage(AppLanguage.SPANISH))
        assertEquals("""["cat"]""", e.labelsForLanguage(AppLanguage.FRENCH))
        assertEquals("""["cat"]""", e.labelsForLanguage(AppLanguage.ENGLISH))
    }

    @Test
    fun `中文取 labelsZh`() {
        val e = entity()
        assertEquals("""["猫"]""", e.labelsForLanguage(AppLanguage.CHINESE))
    }

    @Test
    fun `labelsEn 为空时西法语言回退 labels`() {
        val e = entity(labelsEn = null)
        assertEquals("""["混合"]""", e.labelsForLanguage(AppLanguage.SPANISH))
        assertEquals("""["混合"]""", e.labelsForLanguage(AppLanguage.FRENCH))
    }
}
