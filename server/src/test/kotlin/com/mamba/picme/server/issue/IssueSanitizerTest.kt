package com.mamba.picme.server.issue

import org.junit.Assert.assertEquals
import org.junit.Test

class IssueSanitizerTest {

    @Test
    fun `邮箱被替换`() {
        val input = "请联系 user@example.com 或 admin@test.co.uk"
        val expected = "请联系 <email> 或 <email>"
        assertEquals(expected, IssueSanitizer.sanitize(input))
    }

    @Test
    fun `App Token 被替换`() {
        val input = "我的 token 是 pl-a1b2c3d4e5f6789012345678"
        val expected = "我的 token 是 <token>"
        assertEquals(expected, IssueSanitizer.sanitize(input))
    }

    @Test
    fun `路径和 content URI 被替换`() {
        val input = "文件在 /storage/emulated/0/DCIM/1.jpg 和 content://media/external/images/media/123"
        val expected = "文件在 <path> 和 <path>"
        assertEquals(expected, IssueSanitizer.sanitize(input))
    }

    @Test
    fun `GPS 坐标被替换`() {
        val input = "定位 39.9042, 116.4074 附近"
        val expected = "定位 <coord> 附近"
        assertEquals(expected, IssueSanitizer.sanitize(input))
    }

    @Test
    fun `普通文本不变`() {
        val input = "相册打不开，点击后闪退"
        assertEquals(input, IssueSanitizer.sanitize(input))
    }
}
