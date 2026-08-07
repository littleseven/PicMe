package com.mamba.picme.domain.matting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IDPhotoSpecsTest {

    @Test
    fun `sizes are portrait orientation and non-empty`() {
        assertTrue(IDPhotoSpecs.SIZES.isNotEmpty())
        IDPhotoSpecs.SIZES.forEach {
            assertTrue("${it.labelCn} w>0", it.widthPx > 0)
            assertTrue("${it.labelCn} h>0", it.heightPx > 0)
            assertTrue("${it.labelCn} should be portrait", it.heightPx > it.widthPx)
        }
    }

    @Test
    fun `colors include blue red white`() {
        assertEquals(3, IDPhotoSpecs.COLORS.size)
        assertTrue(IDPhotoSpecs.COLORS.any { it.labelCn == "标准蓝" })
        assertTrue(IDPhotoSpecs.COLORS.any { it.labelCn == "标准红" })
        assertTrue(IDPhotoSpecs.COLORS.any { it.labelCn == "白" })
    }

    @Test
    fun `1in size matches national standard 295x413`() {
        val one = IDPhotoSpecs.SIZES.first { it.labelCn == "1寸" }
        assertEquals(295, one.widthPx)
        assertEquals(413, one.heightPx)
    }
}
