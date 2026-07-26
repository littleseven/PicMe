package com.mamba.picme.features.chat.capability

import com.mamba.picme.agent.core.capability.BaseCapability
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.runtime.state.SceneManager
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.person.KinshipLexicon
import com.mamba.picme.domain.person.PersonRepository
import com.mamba.picme.domain.person.RelationPredicate
import com.mamba.picme.domain.person.RelationSource
import com.mamba.model.chat.request.json.JsonObjectSchema

/**
 * 人物关系声明 Capability（CHAT 场景）。
 *
 * 聊天通路："记住小宝是我女儿" → remember_person_relation；"忘掉小宝的关系" → forget_person_relation。
 * 声明幂等覆盖（重复声明 = 纠错）；人名未解析返回引导性错误（先去人物分组命名）。
 * 数据操作全部收口到 [PersonRepository]（构造注入，显式依赖）。
 */
class PersonRelationCapability(
    private val personRepository: PersonRepository
) : BaseCapability() {

    private val tag = "PoLang:PersonRelationCapability"

    override val name: String = "person_relation"
    override val description: String = "在聊天中声明/遗忘人物与「我」的关系（如「小宝是我女儿」），声明后可用称谓搜合照"

    override fun activeScenes(): List<SceneManager.Scene> = listOf(SceneManager.Scene.CHAT)

    override fun supportedCommands(): List<String> = listOf(
        "remember_person_relation",
        "forget_person_relation"
    )

    override fun getCommandDescription(command: String): String = when (command) {
        "remember_person_relation" -> "声明人物关系，参数: name (已命名人物名), relation (谓词枚举名或中文称谓)"
        "forget_person_relation" -> "遗忘与某人物的全部关系，参数: name (人物名)"
        else -> "未知命令"
    }

    override fun getCommandParameterSchema(command: String): JsonObjectSchema =
        when (command) {
            "remember_person_relation" -> JsonObjectSchema.builder()
                .description("声明人物与「我」的关系")
                .addStringProperty("name", "已命名人物的名字")
                .addStringProperty("relation", "关系谓词：spouse/child/parent/sibling/grandparent/grandchild/other_family/friend/colleague/other，或中文称谓如 女儿/老公")
                .required("name", "relation")
                .build()
            "forget_person_relation" -> JsonObjectSchema.builder()
                .description("遗忘与某人物的全部关系")
                .addStringProperty("name", "人物名字")
                .required("name")
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
                is AgentCommand.RememberPersonRelation -> rememberRelation(command)
                is AgentCommand.ForgetPersonRelation -> forgetRelation(command)
                else -> Result.success(
                    AgentAction.Error(
                        commandId = command.commandId,
                        errorCode = AgentErrorCode.METHOD_NOT_FOUND,
                        message = "PersonRelationCapability 不支持此命令"
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

    private suspend fun rememberRelation(
        command: AgentCommand.RememberPersonRelation
    ): Result<AgentAction> {
        // 关系归一：优先枚举名，再试中文称谓词表
        val predicate = RelationPredicate.fromStored(command.relation.trim().uppercase())
            ?: KinshipLexicon.predicateFor(command.relation.trim())
            ?: return error(
                command,
                "无法识别关系「${command.relation}」，支持：配偶/子女/父母/兄弟姐妹/祖辈/孙辈/其他亲属/朋友/同事/其他"
            )

        val person = personRepository.resolveByName(command.name.trim())
            ?: return error(
                command,
                "还没有叫「${command.name}」的人物，请先在相册人物分组里给 TA 命名，然后再告诉我"
            )

        return when (val result = personRepository.declareRelation(
            subjectPersonId = person.personId,
            predicate = predicate,
            source = RelationSource.CHAT_DECLARATION
        )) {
            is PersonRepository.DeclareRelationResult.Declared -> {
                val personName = person.name ?: command.name
                Logger.i(tag, "relation declared: $personName is user's ${predicate.name}")
                Result.success(
                    AgentAction.TextReply(
                        commandId = command.commandId,
                        message = "已记住：${personName}是你的${predicate.labelZh}"
                    )
                )
            }
            PersonRepository.DeclareRelationResult.SelfNotDeclared -> error(
                command,
                "还没有标记哪个人物是你本人，请先在相册人物分组编辑你自己的分组，打开「这是我」后再声明关系"
            )
            PersonRepository.DeclareRelationResult.SubjectNotFound -> error(
                command,
                "还没有叫「${command.name}」的人物，请先在相册人物分组里给 TA 命名"
            )
        }
    }

    private suspend fun forgetRelation(
        command: AgentCommand.ForgetPersonRelation
    ): Result<AgentAction> {
        val person = personRepository.resolveByName(command.name.trim())
            ?: return error(command, "没有找到叫「${command.name}」的人物")

        val removed = personRepository.removeAllRelationsOf(person.personId)
        val personName = person.name ?: command.name
        val message = if (removed > 0) {
            "已忘记你与「$personName」的关系"
        } else {
            "「$personName」本来就没有已记住的关系"
        }
        Logger.i(tag, "forget relation: $personName removed=$removed")
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
