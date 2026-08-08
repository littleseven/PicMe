package com.mamba.picme.agent.core.model.command

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [CommandRisk.ofMethod] 映射单测（纯数据表，全分支覆盖）。
 */
class CommandRiskTest {

    @Test
    fun `delete_media and share_media are DESTRUCTIVE`() {
        assertEquals(CommandRisk.DESTRUCTIVE, CommandRisk.ofMethod("delete_media"))
        assertEquals(CommandRisk.DESTRUCTIVE, CommandRisk.ofMethod("share_media"))
    }

    @Test
    fun `favorite_media and select_media are REVERSIBLE_WRITE`() {
        assertEquals(CommandRisk.REVERSIBLE_WRITE, CommandRisk.ofMethod("favorite_media"))
        assertEquals(CommandRisk.REVERSIBLE_WRITE, CommandRisk.ofMethod("select_media"))
    }

    @Test
    fun `read methods and unknown methods are READ_ONLY`() {
        assertEquals(CommandRisk.READ_ONLY, CommandRisk.ofMethod("get_gallery_summary"))
        assertEquals(CommandRisk.READ_ONLY, CommandRisk.ofMethod("search_media"))
        assertEquals(CommandRisk.READ_ONLY, CommandRisk.ofMethod("run_gallery_script"))
        assertEquals(CommandRisk.READ_ONLY, CommandRisk.ofMethod("no_such_method"))
        assertEquals(CommandRisk.READ_ONLY, CommandRisk.ofMethod(""))
    }
}
