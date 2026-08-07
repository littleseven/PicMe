package com.mamba.picme.data.indexing.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackfillSelectorTest {

    @Test
    fun `resolve fills city and display name when offline matches`() {
        val offline = OfflineGeocoder(
            AdminCentroidIndex(listOf(Centroid("广东省", "深圳市", "福田区", 22.54, 114.06)))
        )
        val (city, name) = BackfillResolver.resolve(22.53, 113.97, offline)!!
        assertEquals("深圳市", city)
        assertEquals("广东省 深圳市 福田区", name)
    }

    @Test
    fun `resolve null when coords missing`() {
        assertNull(BackfillResolver.resolve(null, null, OfflineGeocoder(AdminCentroidIndex(emptyList()))))
    }

    @Test
    fun `resolve null when offline empty`() {
        assertNull(
            BackfillResolver.resolve(22.53, 113.97, OfflineGeocoder(AdminCentroidIndex(emptyList())))
        )
    }
}
