package com.mamba.picme.server.recommend

import com.mamba.picme.server.db.Db
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.sql.VarCharColumnType
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class RecommendRequest(
    val scene: String,
    val locale: String,
    val clientVersion: String? = null,
)

@Serializable
data class RecommendResponse(
    val ruleVersion: Int,
    val params: JsonElement,
)

/**
 * 非个性化、纯规则（规避算法备案，隐私友好）。改库即热更。
 * 用 exec(sql, args, statementType, transform) 走参数绑定查询。
 */
class RuleEngine(private val json: Json) {
    fun recommend(req: RecommendRequest): RecommendResponse? {
        val sql = """
            SELECT version, params_json
            FROM rule
            WHERE scene = ? AND locale = ? AND enabled = 1
            ORDER BY version DESC
            LIMIT 1
        """.trimIndent()
        val hit: Pair<Int, String>? = transaction(Db.instance) {
            exec(
                sql,
                listOf(
                    VarCharColumnType() to req.scene,
                    VarCharColumnType() to req.locale,
                ),
                StatementType.SELECT,
            ) { rs ->
                if (rs.next()) rs.getInt("version") to rs.getString("params_json") else null
            }
        }
        return hit?.let { (version, paramsJson) ->
            RecommendResponse(ruleVersion = version, params = json.parseToJsonElement(paramsJson))
        }
    }
}
