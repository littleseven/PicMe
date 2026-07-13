package com.mamba.picme.server.llm

import com.mamba.picme.server.db.Db
import com.mamba.picme.server.db.LlmChannels
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

/** 后台列表/表单用的渠道行（token 掩码，不含明文）。 */
data class ChannelRow(
    val id: Int,
    val name: String,
    val kind: String,
    val baseUrl: String,
    val authStyle: String,
    val apiTokenMasked: String,
    val modelMap: Map<String, String>,
    val enabled: Boolean,
    val isActive: Boolean,
    val defaultModel: String,
    val hasToken: Boolean,
)

/** 创建/更新渠道的输入（后台表单）。apiToken 空串 = 更新时保持原值。 */
data class ChannelInput(
    val name: String,
    val kind: String,            // gateway | direct
    val baseUrl: String,
    val authStyle: String,       // bearer | cf_aig
    val apiToken: String,
    val modelMap: Map<String, String>,
    val enabled: Boolean,
    val defaultModel: String = "",
)

object ChannelRepository {

    suspend fun list(): List<ChannelRow> = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        LlmChannels.selectAll().orderBy(LlmChannels.id to SortOrder.ASC).map { it.toRow() }
    }

    suspend fun get(id: Int): ChannelRow? = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        LlmChannels.selectAll().where { LlmChannels.id eq id }.firstOrNull()?.toRow()
    }

    /** 取渠道完整 token（仅供后台「复制」端点，鉴权后返回，不进列表 HTML）。 */
    suspend fun rawToken(id: Int): String? = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        LlmChannels.selectAll().where { LlmChannels.id eq id }.firstOrNull()?.let { it[LlmChannels.apiToken] }
    }

    /** 取生效渠道（含完整 token），供 ChannelRegistry 加载。 */
    suspend fun loadActive(): ChannelConfig? = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        // 不变量：≤ 一个 is_active=1。再过滤 enabled=1 防御停用未清 is_active 的异常态。
        LlmChannels.selectAll().where { LlmChannels.isActive eq 1 }
            .firstOrNull { it[LlmChannels.enabled] == 1 }?.toConfig()
    }

    suspend fun create(input: ChannelInput): Int = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        val now = Instant.now().toEpochMilli()
        (LlmChannels.insert {
            it[LlmChannels.name] = input.name
            it[LlmChannels.kind] = input.kind
            it[LlmChannels.baseUrl] = input.baseUrl
            it[LlmChannels.authStyle] = input.authStyle
            it[LlmChannels.apiToken] = input.apiToken
            it[LlmChannels.modelMapJson] = serializeModelMap(input.modelMap)
            it[LlmChannels.defaultModel] = input.defaultModel
            it[LlmChannels.enabled] = if (input.enabled) 1 else 0
            it[LlmChannels.isActive] = 0
            it[LlmChannels.createdAt] = now
            it[LlmChannels.updatedAt] = now
        } get LlmChannels.id)
    }

    /** 更新；apiToken 空串 = 保持原值。 */
    suspend fun update(id: Int, input: ChannelInput): Boolean = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        val rows = LlmChannels.update({ LlmChannels.id eq id }) {
            it[LlmChannels.name] = input.name
            it[LlmChannels.kind] = input.kind
            it[LlmChannels.baseUrl] = input.baseUrl
            it[LlmChannels.authStyle] = input.authStyle
            if (input.apiToken.isNotEmpty()) it[LlmChannels.apiToken] = input.apiToken
            it[LlmChannels.modelMapJson] = serializeModelMap(input.modelMap)
            it[LlmChannels.defaultModel] = input.defaultModel
            it[LlmChannels.enabled] = if (input.enabled) 1 else 0
            it[LlmChannels.updatedAt] = Instant.now().toEpochMilli()
        }
        rows > 0
    }

    /** 设为生效：清所有 is_active（≤ 一个），置目标（必须 enabled）。 */
    suspend fun setActive(id: Int): Boolean = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        val target = LlmChannels.selectAll().where { LlmChannels.id eq id }.firstOrNull()
            ?: return@newSuspendedTransaction false
        if (target[LlmChannels.enabled] != 1) return@newSuspendedTransaction false
        LlmChannels.update({ LlmChannels.isActive eq 1 }) { it[LlmChannels.isActive] = 0 }
        LlmChannels.update({ LlmChannels.id eq id }) { it[LlmChannels.isActive] = 1 }
        true
    }

    /** 启用/停用。停用生效渠道会清 is_active。 */
    suspend fun setEnabled(id: Int, enabled: Boolean): Boolean = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        val target = LlmChannels.selectAll().where { LlmChannels.id eq id }.firstOrNull()
            ?: return@newSuspendedTransaction false
        val wasActive = target[LlmChannels.isActive] == 1
        val rows = LlmChannels.update({ LlmChannels.id eq id }) {
            it[LlmChannels.enabled] = if (enabled) 1 else 0
            it[LlmChannels.updatedAt] = Instant.now().toEpochMilli()
        }
        if (rows > 0 && !enabled && wasActive) {
            LlmChannels.update({ LlmChannels.id eq id }) { it[LlmChannels.isActive] = 0 }
        }
        rows > 0
    }

    /** 删除；生效渠道拒绝删除。 */
    suspend fun delete(id: Int): Boolean = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        val target = LlmChannels.selectAll().where { LlmChannels.id eq id }.firstOrNull()
            ?: return@newSuspendedTransaction false
        if (target[LlmChannels.isActive] == 1) return@newSuspendedTransaction false
        LlmChannels.deleteWhere { with(SqlExpressionBuilder) { LlmChannels.id eq id } }
        true
    }

    private fun ResultRow.toRow(): ChannelRow = ChannelRow(
        id = this[LlmChannels.id],
        name = this[LlmChannels.name],
        kind = this[LlmChannels.kind],
        baseUrl = this[LlmChannels.baseUrl],
        authStyle = this[LlmChannels.authStyle],
        apiTokenMasked = maskToken(this[LlmChannels.apiToken]),
        modelMap = parseModelMap(this[LlmChannels.modelMapJson]),
        enabled = this[LlmChannels.enabled] == 1,
        isActive = this[LlmChannels.isActive] == 1,
        defaultModel = this[LlmChannels.defaultModel],
        hasToken = this[LlmChannels.apiToken].isNotEmpty(),
    )

    private fun ResultRow.toConfig(): ChannelConfig = ChannelConfig(
        id = this[LlmChannels.id],
        name = this[LlmChannels.name],
        kind = this[LlmChannels.kind],
        baseUrl = this[LlmChannels.baseUrl],
        authStyle = AuthStyle.valueOf(this[LlmChannels.authStyle].uppercase()),
        apiToken = this[LlmChannels.apiToken],
        modelMap = parseModelMap(this[LlmChannels.modelMapJson]),
        defaultModel = this[LlmChannels.defaultModel],
    )

    private fun maskToken(token: String): String = when {
        token.isEmpty() -> "（未配置）"
        token.length <= 8 -> "••••" + token.takeLast(4)
        else -> token.take(4) + "••••" + token.takeLast(4) // 前4位便于辨认 + 后4位
    }
}
