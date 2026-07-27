package com.mamba.picme.domain.usecase

import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.domain.model.GroupTitleType
import com.mamba.picme.domain.model.GroupingMode
import org.junit.Assert.assertEquals
import org.junit.Test

class GetGroupedMediaLocationTest {

    private val usecase = GetGroupedMediaUseCase()

    private fun asset(id: Long, city: String?) = MediaAsset(
        id = id, uri = "u$id", type = MediaType.PHOTO, captureDate = id, fileName = "f$id", city = city
    )

    @Test
    fun `LOCATION groups by city with no-city bucket last`() {
        val groups = usecase(
            listOf(asset(1, "深圳市"), asset(2, "深圳市"), asset(3, "杭州市"), asset(4, null)),
            GroupingMode.LOCATION
        )
        assertEquals(3, groups.size)
        val sz = groups.first { it.titleValue == "深圳市" }
        assertEquals(2, sz.items.size)
        assertEquals(GroupTitleType.LOCATION, sz.titleType)
        assertEquals(GroupTitleType.NO_LOCATION, groups.last().titleType)
        assertEquals(1, groups.last().items.size)
    }

    @Test
    fun `LOCATION with all cities produces no no-location bucket`() {
        val groups = usecase(
            listOf(asset(1, "深圳市"), asset(2, "杭州市")),
            GroupingMode.LOCATION
        )
        assertEquals(2, groups.size)
        groups.forEach { g -> assertEquals(GroupTitleType.LOCATION, g.titleType) }
    }
}
