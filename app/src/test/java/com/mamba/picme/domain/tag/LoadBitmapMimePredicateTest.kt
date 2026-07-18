package com.mamba.picme.domain.tag

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * isImageMimeType 谓词测试：Tag 管道在解码前按 MIME 拦截非图片媒体，
 * 避免视频/音频进入图像解码导致 OOM（参见 TagGenerationPipeline.loadBitmap）。
 */
class LoadBitmapMimePredicateTest {

    @Test
    fun `image mime types are accepted`() {
        assertTrue(isImageMimeType("image/jpeg"))
        assertTrue(isImageMimeType("image/png"))
        assertTrue(isImageMimeType("image/heif"))
        assertTrue(isImageMimeType("image/webp"))
    }

    @Test
    fun `image mime is case-insensitive`() {
        assertTrue(isImageMimeType("IMAGE/JPEG"))
        assertTrue(isImageMimeType("Image/Png"))
    }

    @Test
    fun `video mime types are rejected`() {
        assertFalse(isImageMimeType("video/mp4"))
        assertFalse(isImageMimeType("video/quicktime"))
    }

    @Test
    fun `audio and other mime types are rejected`() {
        assertFalse(isImageMimeType("audio/ogg"))
        assertFalse(isImageMimeType("application/octet-stream"))
    }

    @Test
    fun `null mime is rejected (not deterministically an image)`() {
        assertFalse(isImageMimeType(null))
    }
}
