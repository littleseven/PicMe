package com.mamba.picme.domain.search

import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.data.local.MediaDao
import com.mamba.picme.data.local.dao.PersonDao
import com.mamba.picme.data.local.entity.PersonEntity
import com.mamba.picme.data.model.MediaEntity
import com.mamba.picme.domain.model.StructuredFilter
import com.mamba.picme.domain.model.TimeRange
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 回归测试：MediaSearchEngine.executeFilter 必须按"维度间交集、关键词内并集"语义召回。
 *
 * 历史 bug：各维度结果累积到同一 map，时间约束与关键词约束变成并集，
 * 导致 2003 年/2023 年等非半年内结果只要命中关键词就被召回。
 */
class MediaSearchEngineFilterTest {

    private val mediaDao: MediaDao = mockk(relaxed = true)

    private val engine = MediaSearchEngine(mediaDao = mediaDao)

    @Test
    fun `time range and keyword intersection returns only ids in both sets`() = runTest {
        // 时间范围返回 {1,2}，关键词返回 {2,3}，结果只能是 {2}
        val timeRange = TimeRange(startMs = 0, endMs = 1000)
        val filter = StructuredFilter(
            timeRange = timeRange,
            keywords = listOf("小孩"),
            hasFaces = true
        )

        coEvery { mediaDao.getMediaIdsByTimeRange(timeRange.startMs, timeRange.endMs) } returns listOf(1L, 2L)
        coEvery { mediaDao.getHasFaceIds() } returns listOf(1L, 2L, 3L)

        // 内容关键词在显式候选集 {1,2} 内搜索，仅 2 命中
        coEvery { mediaDao.searchLabelsInIds(listOf(1L, 2L), any()) } answers {
            val keyword = it.invocation.args[1] as String
            if (keyword == "小孩" || keyword == "child") listOf(mediaEntity(2L)) else emptyList()
        }
        coEvery { mediaDao.searchMlKitLabelsInIds(listOf(1L, 2L), any()) } returns emptyList()
        coEvery { mediaDao.searchMlKitLabelsZhInIds(listOf(1L, 2L), any()) } returns emptyList()
        coEvery { mediaDao.searchFileNameInIds(listOf(1L, 2L), any()) } returns emptyList()

        coEvery { mediaDao.getMediaByIds(listOf(2L)) } returns listOf(mediaEntity(2L))

        val result = engine.search(filter)

        assertEquals(listOf(2L), result.media.map { it.id })
    }

    @Test
    fun `keyword outside time range is excluded`() = runTest {
        // 旧照片 id=3 在关键词结果里但不在时间范围内，必须被过滤掉
        val timeRange = TimeRange(startMs = 100, endMs = 200)
        val filter = StructuredFilter(
            timeRange = timeRange,
            keywords = listOf("小孩")
        )

        coEvery { mediaDao.getMediaIdsByTimeRange(timeRange.startMs, timeRange.endMs) } returns listOf(1L, 2L)
        coEvery { mediaDao.searchByLabel(any()) } returns listOf(mediaEntity(3L))
        coEvery { mediaDao.searchByMlKitLabel(any()) } returns emptyList()
        coEvery { mediaDao.searchByMlKitLabelZh(any()) } returns emptyList()
        coEvery { mediaDao.searchByFileName(any()) } returns emptyList()

        // 期望：因为 timeRange 存在，关键词搜索走候选集内查询；候选集 {1,2} 里没有 3
        coEvery { mediaDao.searchLabelsInIds(listOf(1L, 2L), any()) } returns emptyList()
        coEvery { mediaDao.searchMlKitLabelsInIds(listOf(1L, 2L), any()) } returns emptyList()
        coEvery { mediaDao.searchMlKitLabelsZhInIds(listOf(1L, 2L), any()) } returns emptyList()
        coEvery { mediaDao.searchFileNameInIds(listOf(1L, 2L), any()) } returns emptyList()

        val result = engine.search(filter)

        assertEquals(emptyList<Long>(), result.media.map { it.id })
    }

    @Test
    fun `multiple keywords in same dimension use union`() = runTest {
        val filter = StructuredFilter(
            keywords = listOf("小孩", "猫")
        )

        coEvery { mediaDao.searchByLabel("小孩") } returns listOf(mediaEntity(1L))
        coEvery { mediaDao.searchByLabel("猫") } returns listOf(mediaEntity(2L))
        coEvery { mediaDao.searchByMlKitLabel(any()) } returns emptyList()
        coEvery { mediaDao.searchByMlKitLabelZh(any()) } returns emptyList()
        coEvery { mediaDao.searchByFileName(any()) } returns emptyList()
        coEvery { mediaDao.getMediaByIds(listOf(1L, 2L)) } returns listOf(mediaEntity(1L), mediaEntity(2L))

        val result = engine.search(filter)

        assertEquals(setOf(1L, 2L), result.media.map { it.id }.toSet())
    }

    @Test
    fun `face filter intersects with time range`() = runTest {
        val timeRange = TimeRange(startMs = 0, endMs = 1000)
        val filter = StructuredFilter(
            timeRange = timeRange,
            hasFaces = true
        )

        coEvery { mediaDao.getMediaIdsByTimeRange(timeRange.startMs, timeRange.endMs) } returns listOf(1L, 2L)
        coEvery { mediaDao.getHasFaceIds() } returns listOf(2L, 3L)
        coEvery { mediaDao.getMediaByIds(listOf(2L)) } returns listOf(mediaEntity(2L))

        val result = engine.search(filter)

        assertEquals(listOf(2L), result.media.map { it.id })
    }

    @Test
    fun `person name filter returns media linked to matched person`() = runTest {
        val personDao: PersonDao = mockk(relaxed = true)
        val engineWithPerson = MediaSearchEngine(mediaDao = mediaDao, personDao = personDao)

        val filter = StructuredFilter(personName = "古力娜扎")
        val person = PersonEntity(personId = 19L, name = "古力娜扎")

        coEvery { personDao.findPersonByName("古力娜扎") } returns person
        coEvery { personDao.getMediaByPerson(19L) } returns listOf(mediaEntity(100L), mediaEntity(101L))
        coEvery { mediaDao.getMediaByIds(listOf(100L, 101L)) } returns listOf(mediaEntity(100L), mediaEntity(101L))

        val result = engineWithPerson.search(filter)

        assertEquals(setOf(100L, 101L), result.media.map { it.id }.toSet())
    }

    @Test
    fun `person name filter intersects with time range`() = runTest {
        val personDao: PersonDao = mockk(relaxed = true)
        val engineWithPerson = MediaSearchEngine(mediaDao = mediaDao, personDao = personDao)

        val timeRange = TimeRange(startMs = 0, endMs = 1000)
        val filter = StructuredFilter(
            timeRange = timeRange,
            personName = "古力娜扎"
        )
        val person = PersonEntity(personId = 19L, name = "古力娜扎")

        coEvery { mediaDao.getMediaIdsByTimeRange(timeRange.startMs, timeRange.endMs) } returns listOf(100L, 200L)
        coEvery { personDao.findPersonByName("古力娜扎") } returns person
        coEvery { personDao.getMediaByPerson(19L) } returns listOf(mediaEntity(100L), mediaEntity(300L))
        coEvery { mediaDao.getMediaByIds(listOf(100L)) } returns listOf(mediaEntity(100L))

        val result = engineWithPerson.search(filter)

        assertEquals(listOf(100L), result.media.map { it.id })
    }

    /**
     * 回归测试：用户给人物分组命名（如"大宝"）后，在相册搜索框输入该名称，
     * 应返回该人物分组下的照片。
     *
     * 历史 bug：QueryParser 不识别自定义人名，搜索仅命中标签/OCR/文件名，
     * 导致命名分组无法被搜索召回。
     */
    @Test
    fun `search by custom person group name returns matching person media`() = runTest {
        val personDao: PersonDao = mockk(relaxed = true)
        val engineWithPerson = MediaSearchEngine(mediaDao = mediaDao, personDao = personDao)

        val person = PersonEntity(personId = 42L, name = "大宝")

        // 标签/OCR/文件名搜索均无命中
        coEvery { mediaDao.searchByLabel(any()) } returns emptyList()
        coEvery { mediaDao.searchByOcrText(any()) } returns emptyList()
        coEvery { mediaDao.searchByFileName(any()) } returns emptyList()

        // 人物分组名称命中
        coEvery { personDao.findPersonByName("大宝") } returns person
        coEvery { personDao.getMediaByPerson(42L) } returns listOf(mediaEntity(100L), mediaEntity(101L))
        coEvery { mediaDao.getMediaByIds(listOf(100L, 101L)) } returns listOf(mediaEntity(100L), mediaEntity(101L))

        val result = engineWithPerson.search("大宝")

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
