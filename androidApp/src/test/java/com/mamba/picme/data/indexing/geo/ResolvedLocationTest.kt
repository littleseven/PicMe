package com.mamba.picme.data.indexing.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResolvedLocationTest {

    @Test
    fun `display string dedupes municipality province equals city`() {
        val r = ResolvedLocation(province = "北京市", city = "北京市", district = "海淀区", poi = "中关村")
        assertEquals("北京市 海淀区 中关村", r.toDisplayString())
    }

    @Test
    fun `display string keeps distinct province and city`() {
        val r = ResolvedLocation(province = "广东省", city = "深圳市", district = "南山区")
        assertEquals("广东省 深圳市 南山区", r.toDisplayString())
    }

    @Test
    fun `null when all parts empty`() {
        assertNull(ResolvedLocation().toDisplayString())
    }
}
