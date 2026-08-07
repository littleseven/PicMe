package com.mamba.picme.domain.search

import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.data.local.MediaDao
import com.mamba.picme.data.local.dao.PersonDao
import com.mamba.picme.data.local.entity.PersonEntity
import com.mamba.picme.data.model.MediaEntity
import com.mamba.picme.domain.model.TimeRange
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

private fun explicitFilter(timeRange: TimeRange? = null) = ExplicitFilter(
    timeRange = timeRange,
    locationKeywords = emptyList(),
    hasFaces = null,
    personKeywords = emptyList()
)

private fun contentFilter(vararg keywords: String) = ContentFilter(
    keywords = keywords.toList(),
    ocrKeywords = emptyList(),
    semanticQuery = keywords.joinToString("")
)

/**
 * [QA] ExplicitFirstSearchPipeline 单元测试
 *
 * 验证显式约束优先搜索管道在候选集内召回内容关键词的行为，
 * 包括自定义人物分组名称的召回。
 */
class ExplicitFirstSearchPipelineTest {

    private val mediaDao: MediaDao = mockk(relaxed = true)

    @Test
    fun `search by custom person group name with time range returns intersected media`() = runTest {
        val personDao: PersonDao = mockk(relaxed = true)
        val pipeline = ExplicitFirstSearchPipeline(
            mediaDao = mediaDao,
            personDao = personDao
        )

        val person = PersonEntity(personId = 42L, name = "大宝")

        // 显式约束：时间范围返回 {100, 200}
        val timeRange = TimeRange(startMs = 0, endMs = 1000)
        coEvery { mediaDao.getMediaIdsByTimeRange(timeRange.startMs, timeRange.endMs) } returns listOf(100L, 200L)

        // 人物分组名称命中：person 42 拥有 {100, 300}
        coEvery { personDao.findPersonByName("大宝") } returns person
        coEvery { personDao.getMediaByPerson(42L) } returns listOf(mediaEntity(100L), mediaEntity(300L))

        // 标签/OCR/文件名搜索均无命中
        coEvery { mediaDao.searchLabelsAllFieldsInIds(any(), any()) } returns emptyList()
        coEvery { mediaDao.searchFileNameInIds(any(), any()) } returns emptyList()

        // 期望结果：时间范围 {100, 200} ∩ 人物 {100, 300} = {100}
        coEvery { mediaDao.getMediaByIds(listOf(100L)) } returns listOf(mediaEntity(100L))

        val result = pipeline.search(
            explicit = explicitFilter(timeRange = timeRange),
            content = contentFilter("大宝")
        )

        assertEquals(listOf(100L), result.media.map { it.id })
    }

    @Test
    fun `search by custom person group name without explicit constraint returns person media`() = runTest {
        val personDao: PersonDao = mockk(relaxed = true)
        val pipeline = ExplicitFirstSearchPipeline(
            mediaDao = mediaDao,
            personDao = personDao
        )

        val person = PersonEntity(personId = 42L, name = "大宝")

        // 人物分组名称命中
        coEvery { personDao.findPersonByName("大宝") } returns person
        coEvery { personDao.getMediaByPerson(42L) } returns listOf(mediaEntity(100L), mediaEntity(101L))

        // 标签/OCR/文件名搜索均无命中
        coEvery { mediaDao.searchByLabelAllFields(any()) } returns emptyList()

        coEvery { mediaDao.getMediaByIds(listOf(100L, 101L)) } returns listOf(
            mediaEntity(100L),
            mediaEntity(101L)
        )

        val result = pipeline.search(
            explicit = explicitFilter(),
            content = contentFilter("大宝")
        )

        assertEquals(setOf(100L, 101L), result.media.map { it.id }.toSet())
    }

    private fun mediaEntity(id: Long): MediaEntity {
        return MediaEntity(
            id = id,
            uri = "uri_$id",
            type = MediaType.PHOTO,
            captureDate = System.currentTimeMillis(),
            fileName = "img_$id.jpg"
        )
    }
}
