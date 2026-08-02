package com.mamba.picme.server.issue

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class IssueSanitizerTest(private val input: String, private val expected: String) {

    companion object {
        @Parameterized.Parameters(name = "{index}")
        @JvmStatic
        fun data(): Collection<Array<out Any>> = listOf(
            arrayOf("请联系 user@example.com 或 admin@test.co.uk", "请联系 <email> 或 <email>"),
            arrayOf("我的 token 是 pl-a1b2c3d4e5f6789012345678", "我的 token 是 <token>"),
            arrayOf(
                "文件在 /storage/emulated/0/DCIM/1.jpg 和 content://media/external/images/media/123",
                "文件在 <path> 和 <path>",
            ),
            arrayOf("定位 39.9042, 116.4074 附近", "定位 <coord> 附近"),
            arrayOf("相册打不开，点击后闪退", "相册打不开，点击后闪退"),
        )
    }

    @Test
    fun sanitize() {
        assertEquals(expected, IssueSanitizer.sanitize(input))
    }
}
