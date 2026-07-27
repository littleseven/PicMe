package com.mamba.picme.data.indexing.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdminCentroidIndexTest {

    private val idx = AdminCentroidIndex(
        listOf(
            Centroid("北京市", "北京市", "东城区", 39.90, 116.40),
            Centroid("上海市", "上海市", "黄浦区", 31.23, 121.47),
            Centroid("广东省", "深圳市", "福田区", 22.54, 114.06)
        )
    )

    @Test
    fun `beijing coord resolves to beijing`() {
        assertEquals("北京市", idx.nearest(39.95, 116.32)!!.city)
    }

    @Test
    fun `shanghai coord resolves to shanghai`() {
        assertEquals("上海市", idx.nearest(31.10, 121.50)!!.city)
    }

    @Test
    fun `empty index returns null`() {
        assertNull(AdminCentroidIndex(emptyList()).nearest(30.0, 120.0))
    }
}
