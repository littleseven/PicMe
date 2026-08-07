package com.mamba.picme.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteChannelTypeTest {
    @Test
    fun parses_valid_name() {
        assertEquals(RemoteChannelType.FEISHU, RemoteChannelType.fromStored("FEISHU"))
        assertEquals(RemoteChannelType.TELEGRAM, RemoteChannelType.fromStored("TELEGRAM"))
        assertEquals(RemoteChannelType.NONE, RemoteChannelType.fromStored("NONE"))
    }

    @Test
    fun lowercase_or_mixedcase_parses() {
        assertEquals(RemoteChannelType.TELEGRAM, RemoteChannelType.fromStored("telegram"))
        assertEquals(RemoteChannelType.FEISHU, RemoteChannelType.fromStored("Feishu"))
    }

    @Test
    fun invalid_or_null_falls_back_to_default() {
        assertEquals(RemoteChannelType.FEISHU, RemoteChannelType.fromStored("garbage"))
        assertEquals(RemoteChannelType.FEISHU, RemoteChannelType.fromStored(null))
    }
}
