package com.mamba.picme.data.indexing.geo

import android.location.Address
import com.mamba.picme.data.indexing.toResolvedLocation
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MetadataExtractorMappingTest {

    @Test
    fun `Address maps to ResolvedLocation with coords`() {
        val addr = Address(Locale.CHINA).apply {
            countryName = "中国"
            adminArea = "广东省"
            locality = "深圳市"
            subLocality = "南山区"
            featureName = "世界之窗"
        }
        val r = addr.toResolvedLocation(22.53, 113.97)
        assertEquals("中国", r.country)
        assertEquals("广东省", r.province)
        assertEquals("深圳市", r.city)
        assertEquals("南山区", r.district)
        assertEquals("世界之窗", r.poi)
        assertEquals(22.53, r.latitude!!, 0.0)
        assertEquals(113.97, r.longitude!!, 0.0)
    }

    @Test
    fun `display string from Address mapping dedupes`() {
        val addr = Address(Locale.CHINA).apply {
            adminArea = "北京市"
            locality = "北京市"
            subLocality = "海淀区"
        }
        assertEquals("北京市 海淀区", addr.toResolvedLocation(39.96, 116.30).toDisplayString())
    }
}
