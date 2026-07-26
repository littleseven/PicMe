package com.mamba.picme.features.chat.capability

import com.mamba.picme.agent.core.capability.BaseCapability
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.runtime.state.SceneManager
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.memory.MemoryRepository
import com.mamba.picme.domain.memory.MemorySource
import com.mamba.model.chat.request.json.JsonObjectSchema

/**
 * 通用事实记忆 Capability（CHAT 场景）。
 *
 * - remember_fact：落库 + 确认文本（"已记住：…"）
 * - recall_memory：LIKE 检索，返回含 factId 的列表（供后续 forget_fact 精确删除）
 * - forget_fact：按 factId 精确删，或按 query 唯一匹配删（多条返回候选不删）
 *
 * 数据操作全部收口到 [MemoryRepository]（构造注入，显式依赖）。
 */
class MemoryCapability(
    private val memoryRepository: MemoryRepository
) : BaseCapability() {

    private val tag = "PoLang:MemoryCapability"

    override val name: String = "memory_facts"
    override val description: String = "记住/检索/遗忘用户显式声明的事实（如「小宝对花粉过敏」）"

    override fun activeScenes(): List<SceneManager.Scene> = listOf(SceneManager.Scene.CHAT)

    override fun supportedCommands(): List<String> = listOf(
        "remember_fact",
        "forget_fact",
        "recall_memory"
    )

    override fun getCommandDescription(command: String): String = when (command) {
        "remember_fact" -> "记住一条事实，参数: content (事实内容), category (可选分类)"
        "forget_fact" -> "遗忘一条事实，参数: fact_id (优先) 或 query (唯一匹配)"
        "recall_memory" -> "检索事实记忆，参数: query (模糊匹配，空串返回全部)"
        else -> "未知命令"
    }

    override fun getCommandParameterSchema(command: String): JsonObjectSchema =
        when (command) {
            "remember_fact" -> JsonObjectSchema.builder()
                .description("记住一条事实")
                .addStringProperty("content", "事实内容（原子化，一条一个事实）")
                .addStringProperty("category", "可选分类，如 健康/偏好")
                .required("content")
                .build()
            "forget_fact" -> JsonObjectSchema.builder()
                .description("遗忘一条事实")
                .addStringProperty("fact_id", "事实 id（recall_memory 返回里有，优先）")
                .addStringProperty("query", "内容模糊匹配（恰好一条才删）")
                .build()
            "recall_memory" -> JsonObjectSchema.builder()
                .description("检索事实记忆")
                .addStringProperty("query", "模糊匹配关键词，空串返回全部")
                .required("query")
                .build()
            else -> JsonObjectSchema.builder().build()
        }

    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        return try {
            when (command) {
                is AgentCommand.RememberFact -> rememberFact(command)
                is AgentCommand.ForgetFact -> forgetFact(command)
                is AgentCommand.RecallMemory -> recallMemory(command)
                else -> Result.success(
                    AgentAction.Error(
                        commandId = command.commandId,
                        errorCode = AgentErrorCode.METHOD_NOT_FOUND,
                        message = "MemoryCapability 不支持此命令"
                    )
                )
            }
        } catch (e: Exception) {
            Logger.e(tag, "execute failed", e)
            Result.success(
                AgentAction.Error(
                    commandId = command.commandId,
                    errorCode = AgentErrorCode.INTERNAL_ERROR,
                    message = "操作失败：${e.message ?: "未知错误"}"
                )
            )
        }
    }

    private suspend fun rememberFact(command: AgentCommand.RememberFact): Result<AgentAction> {
        val content = command.content.trim()
        if (content.isEmpty()) {
            return error(command, "事实内容为空，未记住")
        }
        val source = MemorySource.fromStored(command.source) ?: MemorySource.CHAT_TOOL
        val factId = memoryRepository.rememberFact(
            content = content,
            category = command.category?.trim()?.ifEmpty { null },
            source = source
        )
        Logger.i(tag, "fact remembered: factId=$factId source=$source")
        return Result.success(
            AgentAction.TextReply(
                commandId = command.commandId,
                message = "已记住：$content"
            )
        )
    }

    private suspend fun forgetFact(command: AgentCommand.ForgetFact): Result<AgentAction> {
        val factId = command.factId
        if (factId != null) {
            val deleted = memoryRepository.forgetFact(factId)
            Logger.i(tag, "forget fact by id: factId=$factId deleted=$deleted")
            return Result.success(
                AgentAction.TextReply(
                    commandId = command.commandId,
                    message = if (deleted) "已忘记该条记忆" else "没有找到这条记忆（可能已被删除）"
                )
            )
        }

        val query = command.query?.trim().orEmpty()
        if (query.isEmpty()) {
            return error(command, "请提供 fact_id 或 query 来定位要忘记的记忆")
        }
        return when (val result = memoryRepository.forgetByUniqueMatch(query)) {
            is MemoryRepository.ForgetByMatchResult.Deleted -> {
                Logger.i(tag, "forget fact by unique match: factId=${result.fact.factId}")
                Result.success(
                    AgentAction.TextReply(
                        commandId = command.commandId,
                        message = "已忘记：${result.fact.content}"
                    )
                )
            }
            MemoryRepository.ForgetByMatchResult.NotFound -> Result.success(
                AgentAction.TextReply(
                    commandId = command.commandId,
                    message = "没有找到与「$query」相关的记忆"
                )
            )
            is MemoryRepository.ForgetByMatchResult.MultipleCandidates -> {
                val candidates = result.candidates
                    .mapIndexed { index, fact -> "${index + 1}. [factId=${fact.factId}] ${fact.content}" }
                    .joinToString("\n")
                Result.success(
                    AgentAction.TextReply(
                        commandId = command.commandId,
                        message = "找到多条与「$query」相关的记忆，请告诉我要忘记哪一条：\n$candidates"
                    )
                )
            }
        }
    }

    private suspend fun recallMemory(command: AgentCommand.RecallMemory): Result<AgentAction> {
        val facts = memoryRepository.findFacts(command.query.trim())
        val message = if (facts.isEmpty()) {
            if (command.query.isBlank()) "还没有记住任何事实" else "没有找到与「${command.query}」相关的记忆"
        } else {
            val lines = facts.mapIndexed { index, fact ->
                val category = fact.category?.let { "（$it）" }.orEmpty()
                "${index + 1}. [factId=${fact.factId}] ${fact.content}$category"
            }
            "找到 ${facts.size} 条记忆：\n${lines.joinToString("\n")}"
        }
        Logger.i(tag, "recall: query='${command.query}' hits=${facts.size}")
        return Result.success(AgentAction.TextReply(commandId = command.commandId, message = message))
    }

    private fun error(command: AgentCommand, message: String): Result<AgentAction> =
        Result.success(
            AgentAction.Error(
                commandId = command.commandId,
                errorCode = AgentErrorCode.INVALID_PARAMS,
                message = message
            )
        )
}
