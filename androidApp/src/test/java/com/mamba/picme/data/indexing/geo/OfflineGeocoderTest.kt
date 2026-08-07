package com.mamba.picme.data.indexing.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineGeocoderTest {

    @Test
    fun `parse centroids json`() {
        val json = """[{"province":"北京市","city":"北京市","district":"海淀区","lat":39.96,"lon":116.30}]"""
        val list = OfflineGeocoder.parseCentroids(json)
        assertEquals(1, list.size)
        assertEquals("海淀区", list[0].district)
        assertEquals(39.96, list[0].lat, 0.0)
    }

    @Test
    fun `lookup maps nearest centroid to resolved location with original coords`() {
        val geo = OfflineGeocoder(
            AdminCentroidIndex(listOf(Centroid("北京市", "北京市", "海淀区", 39.96, 116.30)))
        )
        val r = geo.lookup(39.95, 116.29)!!
        assertEquals("北京市", r.city)
        assertEquals(39.95, r.latitude!!, 0.0)
    }

    @Test
    fun `lookup null on empty index`() {
        assertNull(OfflineGeocoder(AdminCentroidIndex(emptyList())).lookup(30.0, 120.0))
    }
}
