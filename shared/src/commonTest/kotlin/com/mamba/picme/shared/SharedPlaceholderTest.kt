package com.mamba.picme.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class SharedPlaceholderTest {
    @Test
    fun pingReturnsPong() {
        assertEquals("pong", SharedPlaceholder.ping())
    }
}
