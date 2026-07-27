package com.mamba.picme.features.gallery

import com.mamba.picme.features.gallery.components.buildGeoUri
import org.junit.Assert.assertEquals
import org.junit.Test

class GeoUriTest {

    @Test
    fun `builds geo uri with percent-encoded cjk label`() {
        assertEquals(
            "geo:22.53,113.97?q=22.53,113.97(%E4%B8%96%E7%95%8C%E4%B9%8B%E7%AA%97)",
            buildGeoUri(22.53, 113.97, "世界之窗")
        )
    }

    @Test
    fun `encodes parentheses in label`() {
        assertEquals(
            "geo:1.0,2.0?q=1.0,2.0(a%28b%29)",
            buildGeoUri(1.0, 2.0, "a(b)")
        )
    }
}
