package com.mamba.picme.domain.matting

import org.junit.Assert.assertEquals
import org.junit.Test

class IDPhotoComposerTest {

    @Test
    fun `coverCropRect on square source to portrait returns centered vertical crop`() {
        // 100x100 源 → 50x100 目标（更高）：cover 需裁掉左右，宽取 50 居中
        val rect = IDPhotoComposer.coverCropRect(srcW = 100, srcH = 100, dstW = 50, dstH = 100)
        assertEquals(25, rect.left)
        assertEquals(75, rect.right)
        assertEquals(0, rect.top)
        assertEquals(100, rect.bottom)
    }

    @Test
    fun `coverCropRect on wide source to square returns centered horizontal crop`() {
        // 200x100 源 → 100x100 目标：裁掉左右
        val rect = IDPhotoComposer.coverCropRect(srcW = 200, srcH = 100, dstW = 100, dstH = 100)
        assertEquals(50, rect.left)
        assertEquals(150, rect.right)
        assertEquals(0, rect.top)
        assertEquals(100, rect.bottom)
    }

    @Test
    fun `coverCropRect same aspect returns full source`() {
        val rect = IDPhotoComposer.coverCropRect(srcW = 200, srcH = 300, dstW = 100, dstH = 150)
        assertEquals(0, rect.left)
        assertEquals(200, rect.right)
        assertEquals(0, rect.top)
        assertEquals(300, rect.bottom)
    }
}
