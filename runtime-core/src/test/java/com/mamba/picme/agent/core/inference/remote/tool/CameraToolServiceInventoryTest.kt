package com.mamba.picme.agent.core.inference.remote.tool

import ai.koog.agents.core.tools.annotations.Tool as KoogTool
import ai.koog.agents.core.tools.reflect.asToolsByClass
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 相机 ToolService 工具清单与 prompt 一致性单测。
 *
 * 门禁：相机远程 agent 的 system prompt 必须覆盖 [CameraToolService] 的全部 @Tool，
 * 手写清单漂移（漏加/漏删工具）在此 fail-fast。与 [ToolInventoryTest]（chat 链路）同约。
 */
class CameraToolServiceInventoryTest {

    /** 相机场景 capability 命令全集（与 CameraCapability.supportedCommands 对齐）。 */
    private val cameraCommands = setOf(
        "capture", "toggle_recording", "flip_camera", "switch_mode",
        "adjust_beauty", "switch_filter", "switch_style",
        "switch_scene", "switch_ratio", "adjust_exposure", "adjust_zoom", "delay"
    )

    @Test
    fun `CameraToolService exposes every camera capability command as @Tool`() {
        val toolNames = CameraToolService::class.java.declaredMethods
            .mapNotNull { it.getAnnotation(KoogTool::class.java)?.customName?.takeIf { name -> name.isNotBlank() } }
            .toSet()

        val missing = cameraCommands - toolNames
        assertEquals("CameraToolService 缺少相机命令工具：$missing", emptySet<String>(), missing)
    }

    @Test
    fun `inventory contains every @Tool of CameraToolService`() {
        val toolNames = CameraToolService::class.java.declaredMethods
            .mapNotNull { it.getAnnotation(KoogTool::class.java)?.customName?.takeIf { name -> name.isNotBlank() } }
        assertTrue("CameraToolService 应暴露多个 @Tool", toolNames.size >= cameraCommands.size)

        val inventory = ToolInventory.build(cameraDescriptors())
        val missing = toolNames.filter { !inventory.contains("- $it:") }
        assertEquals("工具清单遗漏：$missing", emptyList<String>(), missing)
    }

    private fun cameraDescriptors() =
        CameraToolService.getInstance().asToolsByClass().map { it.descriptor }

    @Test
    fun `camera system prompt covers every @Tool of CameraToolService`() {
        val prompt = AgentOrchestrator.buildCameraSystemPrompt(cameraDescriptors())
        val toolNames = CameraToolService::class.java.declaredMethods
            .mapNotNull { it.getAnnotation(KoogTool::class.java)?.customName?.takeIf { name -> name.isNotBlank() } }

        val missing = toolNames.filter { !prompt.contains(it) }
        assertEquals("system prompt 未覆盖工具：$missing", emptyList<String>(), missing)
    }

    @Test
    fun `camera system prompt has no indented lines`() {
        // 回归：raw string 内插值零缩进行会让 trimIndent 失效、手写段残留前导空格
        val indented = AgentOrchestrator.buildCameraSystemPrompt(cameraDescriptors()).lines()
            .filter { it.startsWith(" ") || it.startsWith("\t") }
        assertEquals("system prompt 存在前导缩进行：$indented", emptyList<String>(), indented)
    }
}
