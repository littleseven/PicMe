package com.mamba.picme.agent.core.model

import com.mamba.picme.agent.core.model.command.AgentCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RefineMediaSearchTest {
    @Test
    fun `constraint is retained and commandId auto-assigned`() {
        val cmd = AgentCommand.RefineMediaSearch(constraint = "海边的")
        assertEquals("海边的", cmd.constraint)
        assertNotEquals(0, cmd.commandId)
    }

    @Test
    fun `two commands get distinct ids`() {
        val a = AgentCommand.RefineMediaSearch(constraint = "夜景")
        val b = AgentCommand.RefineMediaSearch(constraint = "夜景")
        assertNotEquals(a.commandId, b.commandId)
    }

    @Test
    fun `is part of AgentCommand sealed hierarchy`() {
        val cmd: AgentCommand = AgentCommand.RefineMediaSearch(constraint = "海边")
        assertEquals("海边", (cmd as AgentCommand.RefineMediaSearch).constraint)
    }
}
