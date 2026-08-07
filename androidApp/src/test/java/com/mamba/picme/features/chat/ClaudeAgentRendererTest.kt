package com.mamba.picme.features.chat

import com.mamba.picme.data.remote.picme.ClaudeEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ClaudeAgentRenderer 单测：ClaudeEvent（spec §6）→ ClaudeAgentState 的有状态折叠。
 * 纯逻辑，不依赖 Android/Compose。
 */
class ClaudeAgentRendererTest {

    @Test
    fun `assistant text accumulates across deltas`() {
        val r = ClaudeAgentRenderer()
        r.apply(ClaudeEvent.AssistantText("hi"))
        r.apply(ClaudeEvent.AssistantText(" there"))
        assertEquals("hi there", r.state.text)
        assertTrue(r.state.steps.isEmpty())
    }

    @Test
    fun `tool use then result success flips last step to success`() {
        val r = ClaudeAgentRenderer()
        r.apply(ClaudeEvent.ToolUse("Bash", JSONObject("""{"command":"./gradlew test"}""")))
        assertEquals(1, r.state.steps.size)
        assertEquals("Bash", r.state.steps[0].tool)
        assertEquals(ClaudeStepStatus.RUNNING, r.state.steps[0].status)
        assertEquals("./gradlew test", r.state.steps[0].detail)

        r.apply(ClaudeEvent.ToolResult(ok = true, summary = "BUILD SUCCESSFUL"))
        assertEquals(ClaudeStepStatus.SUCCESS, r.state.steps[0].status)
        assertEquals("BUILD SUCCESSFUL", r.state.steps[0].detail)
    }

    @Test
    fun `failed tool result marks last running step failed`() {
        val r = ClaudeAgentRenderer()
        r.apply(ClaudeEvent.ToolUse("Bash", JSONObject("""{"command":"ls"}""")))
        r.apply(ClaudeEvent.ToolResult(ok = false, summary = "exit 1"))
        assertEquals(ClaudeStepStatus.FAILED, r.state.steps[0].status)
        assertEquals("exit 1", r.state.steps[0].detail)
    }

    @Test
    fun `tool result without running step is ignored`() {
        val r = ClaudeAgentRenderer()
        r.apply(ClaudeEvent.ToolResult(ok = true, summary = "late"))
        assertTrue(r.state.steps.isEmpty())
    }

    @Test
    fun `file change adds a step and flags deliver`() {
        val r = ClaudeAgentRenderer()
        r.apply(ClaudeEvent.FileChange(path = "Foo.kt", action = "modified"))
        assertEquals(1, r.state.steps.size)
        assertEquals("file_change", r.state.steps[0].tool)
        assertEquals(ClaudeStepStatus.SUCCESS, r.state.steps[0].status)
        assertTrue(r.state.steps[0].detail.contains("Foo.kt"))
        assertTrue(r.state.hasFileChange)
    }

    @Test
    fun `error appends warning to text`() {
        val r = ClaudeAgentRenderer()
        r.apply(ClaudeEvent.AssistantText("working"))
        r.apply(ClaudeEvent.Error(message = "boom"))
        assertTrue(r.state.text.contains("working"))
        assertTrue(r.state.text.contains("boom"))
    }

    @Test
    fun `session done and cost are no-ops`() {
        val r = ClaudeAgentRenderer()
        r.apply(ClaudeEvent.AssistantText("hi"))
        r.apply(ClaudeEvent.Session(sid = "s1"))
        r.apply(ClaudeEvent.Cost(turns = 3, cents = 12))
        r.apply(ClaudeEvent.Done())
        assertEquals("hi", r.state.text)
        assertTrue(r.state.steps.isEmpty())
        assertFalse(r.state.hasFileChange)
    }

    @Test
    fun `reset clears state`() {
        val r = ClaudeAgentRenderer()
        r.apply(ClaudeEvent.AssistantText("hi"))
        r.apply(ClaudeEvent.ToolUse("Bash", JSONObject()))
        r.reset()
        assertEquals("", r.state.text)
        assertTrue(r.state.steps.isEmpty())
        assertFalse(r.state.hasFileChange)
    }

    @Test
    fun `done with truncation sets sticky reason`() {
        val r = ClaudeAgentRenderer()
        r.apply(ClaudeEvent.AssistantText("partial…"))
        r.apply(ClaudeEvent.Done(turns = 20, truncated = true, reason = "max_turns"))
        assertEquals("max_turns", r.state.truncatedReason)
        // 粘滞：后续无截断的 done 不清除
        r.apply(ClaudeEvent.Done(turns = 20, truncated = false, reason = null))
        assertEquals("max_turns", r.state.truncatedReason)
    }

    @Test
    fun `truncated error sets reason without warning text`() {
        val r = ClaudeAgentRenderer()
        r.apply(ClaudeEvent.AssistantText("partial…"))
        r.apply(ClaudeEvent.Error(message = "phase timeout 300s", truncated = true, reason = "phase_timeout"))
        assertEquals("phase_timeout", r.state.truncatedReason)
        assertFalse(r.state.text.contains("phase timeout"))
    }

    @Test
    fun `non-truncated error still appends warning`() {
        val r = ClaudeAgentRenderer()
        r.apply(ClaudeEvent.AssistantText("working"))
        r.apply(ClaudeEvent.Error(message = "boom"))
        assertTrue(r.state.text.contains("boom"))
        assertNull(r.state.truncatedReason)
    }

    @Test
    fun `truncated reason survives json round-trip`() {
        val r = ClaudeAgentRenderer()
        r.apply(ClaudeEvent.Done(turns = 20, truncated = true, reason = "max_turns"))
        val restored = ClaudeAgentState.fromJson(r.state.toJson())
        assertEquals("max_turns", restored.truncatedReason)
    }

    @Test
    fun `edit tool detail uses file path`() {
        val r = ClaudeAgentRenderer()
        r.apply(ClaudeEvent.ToolUse("Edit", JSONObject("""{"file_path":"Bar.kt"}""")))
        assertTrue(r.state.steps[0].detail.contains("Bar.kt"))
    }

    @Test
    fun `state round-trips through json for room persistence`() {
        val r = ClaudeAgentRenderer()
        r.apply(ClaudeEvent.AssistantText("done text"))
        r.apply(ClaudeEvent.ToolUse("Bash", JSONObject("""{"command":"ls"}""")))
        r.apply(ClaudeEvent.ToolResult(ok = true, summary = "ok"))
        r.apply(ClaudeEvent.FileChange("Foo.kt", "modified"))

        val json = r.state.toJson()
        val restored = ClaudeAgentState.fromJson(json)

        assertEquals(r.state.text, restored.text)
        assertEquals(r.state.hasFileChange, restored.hasFileChange)
        assertEquals(r.state.steps.size, restored.steps.size)
        assertEquals(r.state.steps[0].tool, restored.steps[0].tool)
        assertEquals(r.state.steps[0].status, restored.steps[0].status)
        assertEquals(r.state.steps[0].detail, restored.steps[0].detail)
    }
}
