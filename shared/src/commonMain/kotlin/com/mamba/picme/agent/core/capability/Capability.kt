package com.mamba.picme.agent.core.capability

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.runtime.state.SceneManager

/**
 * Capability 接口 —— Agent 可执行能力的抽象契约
 *
 * 所有业务 Capability（相机、相册、设置等）实现此接口。
 * 通过 [name] 唯一标识，通过 [activeScenes] 声明适用场景。
 */
interface Capability {

    /** Capability 唯一名称（如 "camera", "gallery"） */
    val name: String

    /** Capability 描述（用于 system prompt 生成） */
    val description: String

    /** 支持的命令类型列表（用于快速匹配） */
    fun supportedCommands(): List<String>

    /** 获取命令的详细描述（用于 system prompt） */
    fun getCommandDescription(command: String): String

    /** 当前 Capability 是否可用 */
    fun isAvailable(): Boolean

    /** 此 Capability 在哪些场景下活跃 */
    fun activeScenes(): List<SceneManager.Scene>

    /**
     * 执行命令
     */
    suspend fun execute(command: AgentCommand, context: AgentContext, pageContext: PageContext?): Result<AgentAction>

    /** 检查该 Capability 是否支持指定命令 */
    fun supportsCommand(command: AgentCommand): Boolean {
        return supportedCommands().contains(AgentCommand.getMethodName(command))
    }

    /** 构建 Capability 描述文本（用于 system prompt） */
    fun buildCapabilityDescription(): String {
        val sb = StringBuilder()
        sb.appendLine("- $name: $description")
        supportedCommands().forEach { cmd ->
            sb.appendLine("  • $cmd - ${getCommandDescription(cmd)}")
        }
        return sb.toString()
    }
}

/**
 * Capability 抽象基类
 *
 * 提供默认实现：默认在所有场景可用，命令描述为简单文本。
 * 默认始终可用（适用于应用级 Capability）
 */
abstract class BaseCapability : Capability {

    override fun activeScenes(): List<SceneManager.Scene> {
        return SceneManager.Scene.entries.toList()
    }

    override fun getCommandDescription(command: String): String {
        return "执行 $command 操作"
    }

    override fun isAvailable(): Boolean {
        return true
    }
}
