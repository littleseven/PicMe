# AI 网关开关 / 渠道管理 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `server/` 管理后台新增「渠道」管理，支持运行时切换 AI 上游（TokenHub / CloudFlare 网关 + DeepSeek / GLM / Kimi 直连），无需重启。

**Architecture:** 新增 `llm_channel` 表存渠道配置（DB 为唯一事实源）；`ChannelRegistry` 内存 holder 持 `@Volatile active`，热路径零 DB；`LlmProxy` 重构为读 `ChannelRegistry.active()`，按渠道 `model_map` 透明映射模型名、按 `auth_style` 发 auth header；首次启动从 env 播种 5 个渠道。spec：`docs/superpowers/specs/2026-07-13-ai-gateway-switch-design.md`。

**Tech Stack:** Kotlin 2.0.21 / Ktor 3.0.3 / Exposed 0.55.0 / SQLite / kotlinx.html / JUnit 4 / Ktor MockEngine。

---

## File Structure

**新增（main）：**
- `server/src/main/kotlin/com/mamba/picme/server/llm/ChannelConfig.kt` — `ChannelConfig` data class、`AuthStyle` 枚举、`model_map` 解析/序列化/行格式 helpers。
- `server/src/main/kotlin/com/mamba/picme/server/llm/ChannelRepository.kt` — DB CRUD + `setActive` + `loadActive` + `ChannelRow`/`ChannelInput` DTO。
- `server/src/main/kotlin/com/mamba/picme/server/llm/ChannelRegistry.kt` — 内存 holder。
- `server/migrations/003_llm_channel.sql` — 参考 DDL（文档一致性，运行时不执行）。

**新增（test）：**
- `server/src/test/kotlin/com/mamba/picme/server/llm/ChannelConfigTest.kt`
- `server/src/test/kotlin/com/mamba/picme/server/llm/ChannelRepositoryTest.kt`
- `server/src/test/kotlin/com/mamba/picme/server/llm/ChannelRegistryTest.kt`
- `server/src/test/kotlin/com/mamba/picme/server/llm/LlmProxyChannelTest.kt`（替换 `LlmProxyUsageTest.kt`）
- `server/src/test/kotlin/com/mamba/picme/server/admin/AdminChannelsRoutesTest.kt`

**修改（main）：**
- `server/src/main/kotlin/com/mamba/picme/server/db/Tables.kt` — 加 `LlmChannels` 表对象。
- `server/src/main/kotlin/com/mamba/picme/server/db/Migrations.kt` — `SchemaUtils.create` 纳入 `LlmChannels`；`run(config)` 播种 5 渠道。
- `server/src/main/kotlin/com/mamba/picme/server/llm/LlmProxy.kt` — 重构为 `ChannelRegistry` 驱动；删 `LlmProvider`/`MODEL_ROUTES`/`MODEL_ALIASES`/`TOKENHUB_MODELS`/`forceProvider`/两个 `forwardTo*`。
- `server/src/main/kotlin/com/mamba/picme/server/llm/LlmRoute.kt` — `provider` 改 String；Error 分支用 `result.logStatus`。
- `server/src/main/kotlin/com/mamba/picme/server/Application.kt` — `LlmProxy` 构造简化；`Migrations.run(config)` + `ChannelRegistry.reload()`。
- `server/src/main/kotlin/com/mamba/picme/server/admin/AdminRoutes.kt` — 加 `/admin/channels` 路由组。
- `server/src/main/kotlin/com/mamba/picme/server/admin/AdminViews.kt` — 加 `channelsPage`/`channelFormPage` + 导航「渠道」链接。
- `server/.env.example` — 三个 env 标注「仅首次播种用」。
- `server/build.gradle.kts` — `version = "0.5.0"` → `"0.6.0"`。

**删除（test）：** `server/src/test/kotlin/com/mamba/picme/server/llm/LlmProxyUsageTest.kt`（Task 6 替换）。

---

## Task 1: `llm_channel` 表 + 参考 DDL

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/db/Tables.kt`
- Modify: `server/src/main/kotlin/com/mamba/picme/server/db/Migrations.kt`
- Create: `server/migrations/003_llm_channel.sql`
- Test: `server/src/test/kotlin/com/mamba/picme/server/db/LlmChannelsTableTest.kt`

- [ ] **Step 1: 写失败测试**

Create `server/src/test/kotlin/com/mamba/picme/server/db/LlmChannelsTableTest.kt`:

```kotlin
package com.mamba.picme.server.db

import com.mamba.picme.server.util.TestDb
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Test

class LlmChannelsTableTest {
    @Test
    fun `insert and read a channel row`() {
        TestDb.init(LlmChannels)
        transaction(Db.instance) {
            LlmChannels.insert {
                it[name] = "TestChannel"
                it[kind] = "direct"
                it[baseUrl] = "https://example.com/chat"
                it[authStyle] = "bearer"
                it[apiToken] = "secret"
                it[modelMapJson] = """{"deepseek-chat":"glm-5.2"}"""
                it[enabled] = 1
                it[isActive] = 0
                it[createdAt] = 1_700_000_000_000L
                it[updatedAt] = 1_700_000_000_000L
            }
        }
        val row = transaction(Db.instance) { LlmChannels.selectAll().first() }
        assertEquals("TestChannel", row[LlmChannels.name])
        assertEquals("bearer", row[LlmChannels.authStyle])
        assertEquals(1, row[LlmChannels.enabled])
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :server:test --tests "com.mamba.picme.server.db.LlmChannelsTableTest"`
Expected: FAIL — `LlmChannels` 未解析（尚未定义）。

- [ ] **Step 3: 加表对象**

在 `server/src/main/kotlin/com/mamba/picme/server/db/Tables.kt` 末尾追加（`LlmCallLogs` 对象之后）:

```kotlin
// ── LLM 渠道配置（管理后台 /admin/channels 管理，运行时热切换）─────────
object LlmChannels : Table("llm_channel") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 32)                 // 同时写入 llm_call_log.provider，故限 32
    val kind = varchar("kind", 16)                 // gateway | direct
    val baseUrl = varchar("base_url", 512)
    val authStyle = varchar("auth_style", 16)      // bearer | cf_aig
    val apiToken = text("api_token")               // 明文；UI 掩码
    val modelMapJson = text("model_map_json")      // {"请求名":"上游名"}
    val enabled = integer("enabled").default(1)
    val isActive = integer("is_active").default(0) // 不变量：≤ 一个为 1
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(name)
    }
}
```

- [ ] **Step 4: 纳入 SchemaUtils.create**

在 `server/src/main/kotlin/com/mamba/picme/server/db/Migrations.kt` 的 `run()` 内 `SchemaUtils.create(...)` 列表末尾加 `LlmChannels`:

```kotlin
fun run() {
    transaction(Db.instance) {
        SchemaUtils.create(
            Rules, Assets, TelemetryEvents, LlmDailyCounters,
            Accounts, EmailVerifications, LlmCallLogs, LlmChannels,
        )
        seedRules()
    }
}
```

（仅加 `LlmChannels` 一项；其余行不动。）

- [ ] **Step 5: 写参考 DDL**

Create `server/migrations/003_llm_channel.sql`:

```sql
-- 参考 DDL（运行时由 Exposed SchemaUtils.create 自动建表；此处供手动初始化/核对）
CREATE TABLE IF NOT EXISTS llm_channel (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  name            TEXT    NOT NULL,                 -- ≤32 字符；同时写入 llm_call_log.provider
  kind            TEXT    NOT NULL,                 -- 'gateway' | 'direct'
  base_url        TEXT    NOT NULL,
  auth_style      TEXT    NOT NULL DEFAULT 'bearer',-- 'bearer' | 'cf_aig'
  api_token       TEXT    NOT NULL DEFAULT '',
  model_map_json  TEXT    NOT NULL DEFAULT '{}',
  enabled         INTEGER NOT NULL DEFAULT 1,
  is_active       INTEGER NOT NULL DEFAULT 0,
  created_at      INTEGER NOT NULL,
  updated_at      INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_llm_channel_name ON llm_channel(name);
```

- [ ] **Step 6: 运行测试确认通过**

Run: `./gradlew :server:test --tests "com.mamba.picme.server.db.LlmChannelsTableTest"`
Expected: PASS。

- [ ] **Step 7: 提交**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/db/Tables.kt \
        server/src/main/kotlin/com/mamba/picme/server/db/Migrations.kt \
        server/migrations/003_llm_channel.sql \
        server/src/test/kotlin/com/mamba/picme/server/db/LlmChannelsTableTest.kt
git commit -m "feat(server): add llm_channel table for AI gateway switch"
```

---

## Task 2: `ChannelConfig` + `model_map` helpers

**Files:**
- Create: `server/src/main/kotlin/com/mamba/picme/server/llm/ChannelConfig.kt`
- Test: `server/src/test/kotlin/com/mamba/picme/server/llm/ChannelConfigTest.kt`

- [ ] **Step 1: 写失败测试**

Create `server/src/test/kotlin/com/mamba/picme/server/llm/ChannelConfigTest.kt`:

```kotlin
package com.mamba.picme.server.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelConfigTest {
    @Test
    fun `parseModelMap parses json object to map`() {
        val map = parseModelMap("""{"deepseek-chat":"glm-5.2","kimi-k2.6":"glm-5.2"}""")
        assertEquals("glm-5.2", map["deepseek-chat"])
        assertEquals("glm-5.2", map["kimi-k2.6"])
    }

    @Test
    fun `parseModelMap empty or bad json returns empty map`() {
        assertTrue(parseModelMap("").isEmpty())
        assertTrue(parseModelMap("not json").isEmpty())
    }

    @Test
    fun `serializeModelMap round trips through parseModelMap`() {
        val original = mapOf("a" to "b", "c" to "d")
        val json = serializeModelMap(original)
        assertEquals(original, parseModelMap(json))
    }

    @Test
    fun `parseModelMapLines parses key=value lines ignoring blanks and comments`() {
        val text = """
            # 注释行
            deepseek-chat=glm-5.2

            kimi-k2.6=glm-5.2
        """.trimIndent()
        val map = parseModelMapLines(text)
        assertEquals("glm-5.2", map["deepseek-chat"])
        assertEquals("glm-5.2", map["kimi-k2.6"])
        assertEquals(2, map.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseModelMapLines throws on line without equals`() {
        parseModelMapLines("deepseek-chat glm-5.2")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseModelMapLines throws on empty value`() {
        parseModelMapLines("deepseek-chat=")
    }

    @Test
    fun `renderModelMapLines produces key=value per line`() {
        val text = renderModelMap(mapOf("a" to "b"))
        assertEquals("a=b", text)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :server:test --tests "com.mamba.picme.server.llm.ChannelConfigTest"`
Expected: FAIL — `parseModelMap` 等未解析。

- [ ] **Step 3: 实现 ChannelConfig.kt**

Create `server/src/main/kotlin/com/mamba/picme/server/llm/ChannelConfig.kt`:

```kotlin
package com.mamba.picme.server.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** 渠道鉴权方式：决定上游请求的 auth header。 */
enum class AuthStyle { BEARER, CF_AIG }

/**
 * 渠道运行时配置（从 llm_channel 行加载到内存，热路径读取）。
 * @param modelMap 请求模型名 → 上游模型名（透明映射）。
 */
data class ChannelConfig(
    val id: Int,
    val name: String,
    val kind: String,
    val baseUrl: String,
    val authStyle: AuthStyle,
    val apiToken: String,
    val modelMap: Map<String, String>,
)

private val mapJson = Json { ignoreUnknownKeys = true }

/** 把 DB 里的 model_map_json 解析为 Map；空/非法 → 空 map。 */
fun parseModelMap(json: String): Map<String, String> {
    if (json.isBlank()) return emptyMap()
    return try {
        mapJson.parseToJsonElement(json).jsonObject.mapValues { (_, v) ->
            (v as? JsonPrimitive)?.content ?: ""
        }
    } catch (e: Exception) {
        emptyMap()
    }
}

/** 把 Map 序列化为 model_map_json。 */
fun serializeModelMap(map: Map<String, String>): String {
    val obj = JsonObject(map.mapValues { JsonPrimitive(it.value) })
    return mapJson.encodeToString(JsonObject.serializer(), obj)
}

/**
 * 把后台 textarea 的「每行 请求名=上游名」文本解析为 Map。
 * 忽略空行与 # 注释行；非法行抛 IllegalArgumentException（含行号）。
 */
fun parseModelMapLines(text: String): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    text.lines().forEachIndexed { i, raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
        val eq = line.indexOf('=')
        if (eq <= 0 || eq == line.length - 1) {
            throw IllegalArgumentException("第 ${i + 1} 行格式错误，应为 请求名=上游名：$raw")
        }
        val key = line.substring(0, eq).trim()
        val value = line.substring(eq + 1).trim()
        if (key.isEmpty() || value.isEmpty()) {
            throw IllegalArgumentException("第 ${i + 1} 行键或值为空：$raw")
        }
        result[key] = value
    }
    return result
}

/** 把 Map 渲染回 textarea 文本（每行 请求名=上游名）。 */
fun renderModelMapLines(map: Map<String, String>): String =
    map.entries.joinToString("\n") { "${it.key}=${it.value}" }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :server:test --tests "com.mamba.picme.server.llm.ChannelConfigTest"`
Expected: PASS（全部 7 个用例）。

- [ ] **Step 5: 提交**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/llm/ChannelConfig.kt \
        server/src/test/kotlin/com/mamba/picme/server/llm/ChannelConfigTest.kt
git commit -m "feat(server): add ChannelConfig + model_map parse/serialize helpers"
```

---

## Task 3: `ChannelRepository`

**Files:**
- Create: `server/src/main/kotlin/com/mamba/picme/server/llm/ChannelRepository.kt`
- Test: `server/src/test/kotlin/com/mamba/picme/server/llm/ChannelRepositoryTest.kt`

- [ ] **Step 1: 写失败测试**

Create `server/src/test/kotlin/com/mamba/picme/server/llm/ChannelRepositoryTest.kt`:

```kotlin
package com.mamba.picme.server.llm

import com.mamba.picme.server.db.Db
import com.mamba.picme.server.db.LlmChannels
import com.mamba.picme.server.util.TestDb
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChannelRepositoryTest {

    private fun input(name: String = "C1", enabled: Boolean = true) = ChannelInput(
        name = name,
        kind = "direct",
        baseUrl = "https://example.com/chat",
        authStyle = "bearer",
        apiToken = "tok-123456",
        modelMap = mapOf("deepseek-chat" to "glm-5.2"),
        enabled = enabled,
    )

    @Before
    fun setUp() {
        TestDb.init(LlmChannels)
    }

    @Test
    fun `create and list`() = runBlocking {
        val id = ChannelRepository.create(input())
        val rows = ChannelRepository.list()
        assertEquals(1, rows.size)
        assertEquals(id, rows[0].id)
        assertEquals("••••3456", rows[0].apiTokenMasked)
    }

    @Test
    fun `update with empty token keeps original`() = runBlocking {
        val id = ChannelRepository.create(input())
        ChannelRepository.update(id, input(name = "C2").copy(apiToken = ""))
        val row = ChannelRepository.get(id)!!
        assertEquals("C2", row.name)
        // 原 token 仍在 DB（mask 不变）
        assertEquals("••••3456", row.apiTokenMasked)
    }

    @Test
    fun `update with new token overwrites`() = runBlocking {
        val id = ChannelRepository.create(input())
        ChannelRepository.update(id, input().copy(apiToken = "new-tok-9999"))
        assertEquals("••••9999", ChannelRepository.get(id)!!.apiTokenMasked)
    }

    @Test
    fun `setActive clears others and only one active`() = runBlocking {
        val a = ChannelRepository.create(input("A"))
        val b = ChannelRepository.create(input("B"))
        ChannelRepository.setActive(a)
        assertTrue(ChannelRepository.get(a)!!.isActive)
        assertFalse(ChannelRepository.get(b)!!.isActive)
        ChannelRepository.setActive(b)
        assertFalse(ChannelRepository.get(a)!!.isActive)
        assertTrue(ChannelRepository.get(b)!!.isActive)
    }

    @Test
    fun `setActive rejects disabled channel`() = runBlocking {
        val id = ChannelRepository.create(input(enabled = false))
        assertFalse(ChannelRepository.setActive(id))
        assertFalse(ChannelRepository.get(id)!!.isActive)
    }

    @Test
    fun `delete active channel rejected`() = runBlocking {
        val id = ChannelRepository.create(input())
        ChannelRepository.setActive(id)
        assertFalse(ChannelRepository.delete(id))
        assertEquals(1, ChannelRepository.list().size)
    }

    @Test
    fun `delete non-active channel succeeds`() = runBlocking {
        val id = ChannelRepository.create(input())
        assertTrue(ChannelRepository.delete(id))
        assertTrue(ChannelRepository.list().isEmpty())
    }

    @Test
    fun `set enabled false clears active on that channel`() = runBlocking {
        val id = ChannelRepository.create(input())
        ChannelRepository.setActive(id)
        ChannelRepository.setEnabled(id, false)
        val row = ChannelRepository.get(id)!!
        assertFalse(row.enabled)
        assertFalse(row.isActive)
    }

    @Test
    fun `loadActive returns active enabled config with token`() = runBlocking {
        val id = ChannelRepository.create(input())
        ChannelRepository.setActive(id)
        val cfg = ChannelRepository.loadActive()
        assertEquals("C1", cfg!!.name)
        assertEquals("tok-123456", cfg.apiToken)
        assertEquals(AuthStyle.BEARER, cfg.authStyle)
    }

    @Test
    fun `loadActive returns null when none active`() = runBlocking {
        ChannelRepository.create(input())
        assertNull(ChannelRepository.loadActive())
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :server:test --tests "com.mamba.picme.server.llm.ChannelRepositoryTest"`
Expected: FAIL — `ChannelRepository` 未定义。

- [ ] **Step 3: 实现 ChannelRepository.kt**

Create `server/src/main/kotlin/com/mamba/picme/server/llm/ChannelRepository.kt`:

```kotlin
package com.mamba.picme.server.llm

import com.mamba.picme.server.db.Db
import com.mamba.picme.server.db.LlmChannels
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.updateAll
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
)

object ChannelRepository {

    suspend fun list(): List<ChannelRow> = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        LlmChannels.selectAll().orderBy(LlmChannels.id to SortOrder.ASC).map { it.toRow() }
    }

    suspend fun get(id: Int): ChannelRow? = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        LlmChannels.selectAll().where { LlmChannels.id eq id }.firstOrNull()?.toRow()
    }

    /** 取生效渠道（含完整 token），供 ChannelRegistry 加载。 */
    suspend fun loadActive(): ChannelConfig? = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        LlmChannels.selectAll().where { (LlmChannels.isActive eq 1) and (LlmChannels.enabled eq 1) }
            .firstOrNull()?.toConfig()
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
            it[LlmChannels.enabled] = if (input.enabled) 1 else 0
            it[LlmChannels.updatedAt] = Instant.now().toEpochMilli()
        }
        rows > 0
    }

    /** 设为生效：清所有 is_active，置目标（必须 enabled）。 */
    suspend fun setActive(id: Int): Boolean = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        val target = LlmChannels.selectAll().where { LlmChannels.id eq id }.firstOrNull()
            ?: return@newSuspendedTransaction false
        if (target[LlmChannels.enabled] != 1) return@newSuspendedTransaction false
        LlmChannels.updateAll { it[LlmChannels.isActive] = 0 }
        LlmChannels.update({ LlmChannels.id eq id }) { it[LlmChannels.isActive] = 1 }
        true
    }

    /** 启用/停用。停用生效渠道会清 is_active。 */
    suspend fun setEnabled(id: Int, enabled: Boolean): Boolean = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        val wasActive = LlmChannels.selectAll()
            .where { (LlmChannels.id eq id) and (LlmChannels.isActive eq 1) }
            .firstOrNull() != null
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
        LlmChannels.deleteWhere { LlmChannels.id eq id }
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
    )

    private fun ResultRow.toConfig(): ChannelConfig = ChannelConfig(
        id = this[LlmChannels.id],
        name = this[LlmChannels.name],
        kind = this[LlmChannels.kind],
        baseUrl = this[LlmChannels.baseUrl],
        authStyle = AuthStyle.valueOf(this[LlmChannels.authStyle].uppercase()),
        apiToken = this[LlmChannels.apiToken],
        modelMap = parseModelMap(this[LlmChannels.modelMapJson]),
    )

    private fun maskToken(token: String): String =
        if (token.length <= 4) "••••" else "••••" + token.takeLast(4)
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :server:test --tests "com.mamba.picme.server.llm.ChannelRepositoryTest"`
Expected: PASS（全部 10 个用例）。

- [ ] **Step 5: 提交**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/llm/ChannelRepository.kt \
        server/src/test/kotlin/com/mamba/picme/server/llm/ChannelRepositoryTest.kt
git commit -m "feat(server): add ChannelRepository for llm_channel CRUD + setActive"
```

---

## Task 4: `ChannelRegistry` 内存 holder

**Files:**
- Create: `server/src/main/kotlin/com/mamba/picme/server/llm/ChannelRegistry.kt`
- Test: `server/src/test/kotlin/com/mamba/picme/server/llm/ChannelRegistryTest.kt`

- [ ] **Step 1: 写失败测试**

Create `server/src/test/kotlin/com/mamba/picme/server/llm/ChannelRegistryTest.kt`:

```kotlin
package com.mamba.picme.server.llm

import com.mamba.picme.server.db.LlmChannels
import com.mamba.picme.server.util.TestDb
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ChannelRegistryTest {

    private fun input(name: String = "C1", enabled: Boolean = true) = ChannelInput(
        name = name,
        kind = "direct",
        baseUrl = "https://example.com/chat",
        authStyle = "bearer",
        apiToken = "tok",
        modelMap = mapOf("deepseek-chat" to "glm-5.2"),
        enabled = enabled,
    )

    @Before
    fun setUp() {
        TestDb.init(LlmChannels)
    }

    @Test
    fun `reload loads the active enabled channel`() = runBlocking {
        val id = ChannelRepository.create(input())
        ChannelRepository.setActive(id)
        ChannelRegistry.reload()
        assertEquals("C1", ChannelRegistry.active()?.name)
        assertEquals("glm-5.2", ChannelRegistry.active()?.modelMap?.get("deepseek-chat"))
    }

    @Test
    fun `reload picks the enabled active channel`() = runBlocking {
        ChannelRepository.create(input("OFF", enabled = false))
        val on = ChannelRepository.create(input("ON", enabled = true))
        ChannelRepository.setActive(on)
        ChannelRegistry.reload()
        assertEquals("ON", ChannelRegistry.active()?.name)
    }

    @Test
    fun `active is null when no active channel`() = runBlocking {
        ChannelRepository.create(input())
        ChannelRegistry.reload()
        assertNull(ChannelRegistry.active())
    }

    @Test
    fun `setActiveForTesting injects config without DB`() {
        val cfg = ChannelConfig(1, "X", "direct", "u", AuthStyle.BEARER, "t", emptyMap())
        ChannelRegistry.setActiveForTesting(cfg)
        assertEquals("X", ChannelRegistry.active()?.name)
        ChannelRegistry.setActiveForTesting(null)
        assertNull(ChannelRegistry.active())
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :server:test --tests "com.mamba.picme.server.llm.ChannelRegistryTest"`
Expected: FAIL — `ChannelRegistry` 未定义。

- [ ] **Step 3: 实现 ChannelRegistry.kt**

Create `server/src/main/kotlin/com/mamba/picme/server/llm/ChannelRegistry.kt`:

```kotlin
package com.mamba.picme.server.llm

/**
 * 内存 holder：持有当前生效渠道，热路径零 DB（volatile 读取）。
 * 启动时与后台每次渠道变更后调 [reload]。
 */
object ChannelRegistry {
    @Volatile
    private var active: ChannelConfig? = null

    fun active(): ChannelConfig? = active

    suspend fun reload() {
        active = ChannelRepository.loadActive()
    }

    /** 测试专用：直接注入活跃渠道，绕过 DB。 */
    internal fun setActiveForTesting(config: ChannelConfig?) {
        active = config
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :server:test --tests "com.mamba.picme.server.llm.ChannelRegistryTest"`
Expected: PASS（全部 4 个用例）。

- [ ] **Step 5: 提交**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/llm/ChannelRegistry.kt \
        server/src/test/kotlin/com/mamba/picme/server/llm/ChannelRegistryTest.kt
git commit -m "feat(server): add ChannelRegistry in-memory holder for active channel"
```

---

## Task 5: 播种 5 渠道 + 接线 `Migrations.run(config)`

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/db/Migrations.kt`
- Modify: `server/src/main/kotlin/com/mamba/picme/server/Application.kt`
- Test: `server/src/test/kotlin/com/mamba/picme/server/db/MigrationsSeedChannelsTest.kt`

- [ ] **Step 1: 写失败测试**

Create `server/src/test/kotlin/com/mamba/picme/server/db/MigrationsSeedChannelsTest.kt`:

```kotlin
package com.mamba.picme.server.db

import com.mamba.picme.server.config.AppConfig
import com.mamba.picme.server.llm.ChannelRepository
import com.mamba.picme.server.util.TestDb
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MigrationsSeedChannelsTest {

    private val config = AppConfig.load()

    @Before
    fun setUp() {
        TestDb.init(LlmChannels)
    }

    @Test
    fun `seedChannels creates 5 channels with one active`() = runBlocking {
        Migrations.seedChannels(config)
        val channels = ChannelRepository.list()
        assertEquals(5, channels.size)
        assertEquals(1, channels.count { it.isActive })
    }

    @Test
    fun `default active channel is Cloudflare when FORCE_PROVIDER unset`() = runBlocking {
        Migrations.seedChannels(config)
        val active = runBlocking { ChannelRepository.list().first { it.isActive } }
        assertEquals("Cloudflare", active.name)
    }

    @Test
    fun `seedChannels is idempotent`() = runBlocking {
        Migrations.seedChannels(config)
        Migrations.seedChannels(config)
        assertEquals(5, ChannelRepository.list().size)
    }

    @Test
    fun `seedChannels seeds direct providers disabled`() = runBlocking {
        Migrations.seedChannels(config)
        val direct = ChannelRepository.list().filter { it.kind == "direct" }
        assertEquals(3, direct.size)
        assertTrue(direct.none { it.enabled })
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :server:test --tests "com.mamba.picme.server.db.MigrationsSeedChannelsTest"`
Expected: FAIL — `Migrations.seedChannels` 未定义 / `run()` 签名不匹配。

- [ ] **Step 3: 实现 seedChannels + 改 run 签名**

把 `server/src/main/kotlin/com/mamba/picme/server/db/Migrations.kt` 整体替换为:

```kotlin
package com.mamba.picme.server.db

import com.mamba.picme.server.config.AppConfig
import com.mamba.picme.server.llm.LlmChannels
import com.mamba.picme.server.llm.serializeModelMap
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

object Migrations {
    fun run(config: AppConfig) {
        transaction(Db.instance) {
            SchemaUtils.create(
                Rules, Assets, TelemetryEvents, LlmDailyCounters,
                Accounts, EmailVerifications, LlmCallLogs, LlmChannels,
            )
            seedRules()
        }
        seedChannels(config)
    }

    /**
     * 幂等加载初始推荐规则。seed_rules.sql 用 INSERT OR IGNORE，重复启动不会重复插入。
     * 若跳过此步，首次启动查不到任何规则，/recommend 必返回 404。
     */
    private fun Transaction.seedRules() {
        val sql = Migrations::class.java.getResource("/seed_rules.sql")?.readText() ?: return
        sql.lines()
            .map { it.substringBefore("--").trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { stmt -> exec(stmt) }
    }

    /**
     * 首次启动播种 5 个 LLM 渠道（2 网关 + 3 直连）。已有渠道则跳过（幂等）。
     * 生效渠道：FORCE_PROVIDER=cloudflare|tokenhub 优先，否则首个 enabled（Cloudflare）。
     * env 仅此处读一次；之后由后台 /admin/channels 管理。
     */
    internal fun seedChannels(config: AppConfig) {
        transaction(Db.instance) {
            if (LlmChannels.selectAll().count() > 0) return@transaction
            val now = System.currentTimeMillis()

            val cloudflareId = (LlmChannels.insert {
                it[LlmChannels.name] = "Cloudflare"
                it[LlmChannels.kind] = "gateway"
                it[LlmChannels.baseUrl] = config.cloudflareAigUrl
                it[LlmChannels.authStyle] = "cf_aig"
                it[LlmChannels.apiToken] = config.cloudflareAigToken
                it[LlmChannels.modelMapJson] = serializeModelMap(mapOf(
                    "deepseek-chat" to "deepseek/deepseek-chat",
                    "deepseek-v4-flash" to "deepseek/deepseek-chat",
                ))
                it[LlmChannels.enabled] = 1
                it[LlmChannels.isActive] = 0
                it[LlmChannels.createdAt] = now
                it[LlmChannels.updatedAt] = now
            } get LlmChannels.id)

            val tokenhubId = (LlmChannels.insert {
                it[LlmChannels.name] = "TokenHub"
                it[LlmChannels.kind] = "gateway"
                it[LlmChannels.baseUrl] = config.tokenhubUrl
                it[LlmChannels.authStyle] = "bearer"
                it[LlmChannels.apiToken] = config.tokenhubApiToken
                it[LlmChannels.modelMapJson] = serializeModelMap(
                    TOKENHUB_SEED_MODELS.associateWith { model -> model }
                )
                it[LlmChannels.enabled] = 1
                it[LlmChannels.isActive] = 0
                it[LlmChannels.createdAt] = now
                it[LlmChannels.updatedAt] = now
            } get LlmChannels.id)

            LlmChannels.insert {
                it[LlmChannels.name] = "DeepSeek 直连"
                it[LlmChannels.kind] = "direct"
                it[LlmChannels.baseUrl] = "https://api.deepseek.com/v1/chat/completions"
                it[LlmChannels.authStyle] = "bearer"
                it[LlmChannels.apiToken] = ""
                it[LlmChannels.modelMapJson] = serializeModelMap(mapOf(
                    "deepseek-v4-flash" to "deepseek-v4-flash",
                    "deepseek-v4-pro" to "deepseek-v4-pro",
                ))
                it[LlmChannels.enabled] = 0
                it[LlmChannels.isActive] = 0
                it[LlmChannels.createdAt] = now
                it[LlmChannels.updatedAt] = now
            }

            LlmChannels.insert {
                it[LlmChannels.name] = "GLM 直连"
                it[LlmChannels.kind] = "direct"
                it[LlmChannels.baseUrl] = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
                it[LlmChannels.authStyle] = "bearer"
                it[LlmChannels.apiToken] = ""
                it[LlmChannels.modelMapJson] = serializeModelMap(mapOf(
                    "deepseek-chat" to "glm-5.2",
                    "kimi-k2.6" to "glm-5.2",
                ))
                it[LlmChannels.enabled] = 0
                it[LlmChannels.isActive] = 0
                it[LlmChannels.createdAt] = now
                it[LlmChannels.updatedAt] = now
            }

            LlmChannels.insert {
                it[LlmChannels.name] = "Kimi 直连"
                it[LlmChannels.kind] = "direct"
                it[LlmChannels.baseUrl] = "https://api.moonshot.cn/v1/chat/completions"
                it[LlmChannels.authStyle] = "bearer"
                it[LlmChannels.apiToken] = ""
                it[LlmChannels.modelMapJson] = serializeModelMap(mapOf(
                    "kimi-k2.6" to "kimi-k2.7-code",
                    "deepseek-chat" to "kimi-k2.7-code",
                ))
                it[LlmChannels.enabled] = 0
                it[LlmChannels.isActive] = 0
                it[LlmChannels.createdAt] = now
                it[LlmChannels.updatedAt] = now
            }

            val activeId = when (config.forceProvider.trim().lowercase()) {
                "tokenhub" -> tokenhubId
                "cloudflare" -> cloudflareId
                else -> cloudflareId
            }
            LlmChannels.update({ LlmChannels.id eq activeId }) { it[LlmChannels.isActive] = 1 }
        }
    }
}

private val TOKENHUB_SEED_MODELS = listOf(
    "deepseek-v4-flash-202605", "kimi-k2.7-code", "kimi-k2.6", "deepseek-v4-flash",
    "hy3", "kimi-k2.7-code-highspeed", "glm-5.2", "minimax-m3", "hy-role",
    "deepseek-v4-pro-202606", "hy-mt2-pro", "hy-mt2-lite", "hy-mt2-plus",
    "hunyuan-role-latest", "deepseek-v4-pro", "hy3-preview", "glm-5.1",
    "glm-5v-turbo", "minimax-m2.7", "glm-5-turbo", "qwen3.5-flash",
    "qwen3.5-plus", "minimax-m2.5", "glm-5", "kimi-k2.5",
)
```

- [ ] **Step 4: 改 Application.kt 调用点**

在 `server/src/main/kotlin/com/mamba/picme/server/Application.kt` 的 `main()` 中，把：

```kotlin
    val config = AppConfig.load()
    Db.init(config.dbPath)
    Migrations.run()
    embeddedServer(CIO, port = config.port, host = config.host) {
```

改为（加 `config` 参数 + 启动加载活跃渠道；`reload` 是 suspend，用 `runBlocking`）:

```kotlin
    val config = AppConfig.load()
    Db.init(config.dbPath)
    Migrations.run(config)
    runBlocking { ChannelRegistry.reload() }
    embeddedServer(CIO, port = config.port, host = config.host) {
```

并在文件顶部 import 区加（与现有 `import com.mamba.picme.server.llm.LlmProxy` 同组）:

```kotlin
import com.mamba.picme.server.llm.ChannelRegistry
import kotlinx.coroutines.runBlocking
```

> 注意：本任务**不**改 `LlmProxy` 构造（仍是旧签名）——`ChannelRegistry.reload()` 已就位但 `LlmProxy` 尚未读它，运行时仍走旧路径。下一任务做 cutover。

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew :server:test --tests "com.mamba.picme.server.db.MigrationsSeedChannelsTest"`
Expected: PASS（全部 4 个用例）。

并确认全量编译（Application.kt 改了）:
Run: `./gradlew :server:compileKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 6: 提交**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/db/Migrations.kt \
        server/src/main/kotlin/com/mamba/picme/server/Application.kt \
        server/src/test/kotlin/com/mamba/picme/server/db/MigrationsSeedChannelsTest.kt
git commit -m "feat(server): seed 5 LLM channels on first boot + ChannelRegistry.reload at startup"
```

---

## Task 6: 重构 `LlmProxy` + 改 `LlmRoute` + 替换测试（cutover）

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/llm/LlmProxy.kt`（整体替换）
- Modify: `server/src/main/kotlin/com/mamba/picme/server/llm/LlmRoute.kt:64,78`
- Modify: `server/src/main/kotlin/com/mamba/picme/server/Application.kt`（LlmProxy 构造行）
- Delete: `server/src/test/kotlin/com/mamba/picme/server/llm/LlmProxyUsageTest.kt`
- Create: `server/src/test/kotlin/com/mamba/picme/server/llm/LlmProxyChannelTest.kt`

- [ ] **Step 1: 写失败测试（新代理行为）**

Create `server/src/test/kotlin/com/mamba/picme/server/llm/LlmProxyChannelTest.kt`:

```kotlin
package com.mamba.picme.server.llm

import com.mamba.picme.server.analytics.TokenUsage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmProxyChannelTest {

    private fun cfg(
        authStyle: AuthStyle = AuthStyle.BEARER,
        token: String = "tok-abc",
        modelMap: Map<String, String> = mapOf("deepseek-chat" to "glm-5.2"),
    ) = ChannelConfig(1, "TestChan", "direct", "http://up.example/chat", authStyle, token, modelMap)

    private fun proxy(engine: MockEngine) = LlmProxy(HttpClient(engine), maxTokensCap = 4096)

    private val usageBody =
        """{"id":"x","usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}"""

    @After
    fun tearDown() {
        ChannelRegistry.setActiveForTesting(null)
    }

    @Test
    fun `forward maps model and forces stream false`() = runBlocking {
        var captured: HttpRequestData? = null
        val engine = MockEngine { req ->
            captured = req
            respond(usageBody, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        ChannelRegistry.setActiveForTesting(cfg())
        val body = buildJsonObject { put("model", "deepseek-chat") }
        val result = proxy(engine).forward("1.2.3.4", body)
        assertTrue(result is ProxyResult.Success)
        result as ProxyResult.Success
        assertEquals("glm-5.2", result.model)
        assertEquals("TestChan", result.provider)
        assertEquals(TokenUsage(10, 5, 15), result.usage)
        val sent = (captured!!.body as TextContent).text
        assertTrue(sent.contains("\"model\":\"glm-5.2\""))
        assertTrue(sent.contains("\"stream\":false"))
    }

    @Test
    fun `bearer auth style sends Authorization header`() = runBlocking {
        var header: String? = null
        val engine = MockEngine { req ->
            header = req.headers["Authorization"]
            respond(usageBody, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        ChannelRegistry.setActiveForTesting(cfg(authStyle = AuthStyle.BEARER, token = "sk-123"))
        proxy(engine).forward("1.2.3.4", buildJsonObject { put("model", "deepseek-chat") })
        assertEquals("Bearer sk-123", header)
    }

    @Test
    fun `cf_aig auth style sends cf-aig-authorization header`() = runBlocking {
        var header: String? = null
        val engine = MockEngine { req ->
            header = req.headers["cf-aig-authorization"]
            respond(usageBody, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        ChannelRegistry.setActiveForTesting(cfg(authStyle = AuthStyle.CF_AIG, token = "cf-tok"))
        proxy(engine).forward("1.2.3.4", buildJsonObject { put("model", "deepseek-chat") })
        assertEquals("Bearer cf-tok", header)
    }

    @Test
    fun `unsupported model returns 400 with logStatus unsupported_model`() = runBlocking {
        val engine = MockEngine { respond("""{}""", HttpStatusCode.OK) }
        ChannelRegistry.setActiveForTesting(cfg(modelMap = mapOf("a" to "b")))
        val result = proxy(engine).forward("1.2.3.4", buildJsonObject { put("model", "deepseek-chat") })
        assertTrue(result is ProxyResult.Error)
        result as ProxyResult.Error
        assertEquals(HttpStatusCode.BadRequest, result.status)
        assertEquals("unsupported_model", result.logStatus)
    }

    @Test
    fun `blank token returns 500 channel_token_missing`() = runBlocking {
        val engine = MockEngine { respond("""{}""", HttpStatusCode.OK) }
        ChannelRegistry.setActiveForTesting(cfg(token = ""))
        val result = proxy(engine).forward("1.2.3.4", buildJsonObject { put("model", "deepseek-chat") })
        assertTrue(result is ProxyResult.Error)
        result as ProxyResult.Error
        assertEquals(HttpStatusCode.InternalServerError, result.status)
        assertEquals("channel_token_missing", result.logStatus)
    }

    @Test
    fun `no active channel returns 503 no_active_channel`() = runBlocking {
        ChannelRegistry.setActiveForTesting(null)
        val engine = MockEngine { respond("""{}""", HttpStatusCode.OK) }
        val result = proxy(engine).forward("1.2.3.4", buildJsonObject { put("model", "deepseek-chat") })
        assertTrue(result is ProxyResult.Error)
        result as ProxyResult.Error
        assertEquals(HttpStatusCode.ServiceUnavailable, result.status)
        assertEquals("no_active_channel", result.logStatus)
    }

    @Test
    fun `max_tokens over cap returns 400`() = runBlocking {
        val engine = MockEngine { respond("""{}""", HttpStatusCode.OK) }
        ChannelRegistry.setActiveForTesting(cfg())
        val body = buildJsonObject {
            put("model", "deepseek-chat")
            put("max_tokens", 99999)
        }
        val result = proxy(engine).forward("1.2.3.4", body)
        assertTrue(result is ProxyResult.Error)
        result as ProxyResult.Error
        assertEquals(HttpStatusCode.BadRequest, result.status)
        assertEquals("bad_request", result.logStatus)
    }

    @Test
    fun `null usage when upstream omits it`() = runBlocking {
        val engine = MockEngine {
            respond("""{"id":"x"}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        ChannelRegistry.setActiveForTesting(cfg())
        val result = proxy(engine).forward("1.2.3.4", buildJsonObject { put("model", "deepseek-chat") })
        assertTrue(result is ProxyResult.Success)
        assertNull((result as ProxyResult.Success).usage)
    }
}
```

- [ ] **Step 2: 删除旧测试**

```bash
git rm server/src/test/kotlin/com/mamba/picme/server/llm/LlmProxyUsageTest.kt
```

- [ ] **Step 3: 运行新测试确认失败**

Run: `./gradlew :server:test --tests "com.mamba.picme.server.llm.LlmProxyChannelTest"`
Expected: FAIL — `LlmProxy` 仍是旧构造（`LlmProxyChannelTest` 用 `LlmProxy(HttpClient(engine), maxTokensCap=4096)` 调不动旧构造）。

- [ ] **Step 4: 重构 LlmProxy.kt（整体替换）**

把 `server/src/main/kotlin/com/mamba/picme/server/llm/LlmProxy.kt` 整体替换为:

```kotlin
package com.mamba.picme.server.llm

import com.mamba.picme.server.analytics.TokenUsage
import com.mamba.picme.server.analytics.fromUpstreamBytes
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("picme-llm")

/**
 * LLM 代理：把 chat completion 请求转发到当前生效渠道（[ChannelRegistry.active]）。
 * - 模型路由对客户端透明：请求的 model 名按渠道 model_map 映射为上游名。
 * - 真实 API key 只在 DB（渠道配置）里。
 * - 强制 stream=false（usage 解析依赖完整响应体）。
 */
class LlmProxy(
    private val httpClient: HttpClient,
    private val maxTokensCap: Int = 4096,
) {
    suspend fun forward(clientIp: String, body: JsonObject): ProxyResult {
        val channel = ChannelRegistry.active()
            ?: return ProxyResult.Error(
                HttpStatusCode.ServiceUnavailable,
                buildJsonObject { put("error", "no_active_channel") },
                logStatus = "no_active_channel",
            )

        val requestedModel = (body["model"] as? JsonPrimitive)?.contentOrNullSafe()
            ?: return ProxyResult.Error(
                HttpStatusCode.BadRequest,
                buildJsonObject { put("error", "missing model field") },
                logStatus = "bad_request",
            )

        val upstreamModel = channel.modelMap[requestedModel]
            ?: return ProxyResult.Error(
                HttpStatusCode.BadRequest,
                buildJsonObject {
                    put("error", "unsupported_model")
                    put("active_channel", channel.name)
                    put("supported", channel.modelMap.keys.sorted().joinToString(","))
                },
                logStatus = "unsupported_model",
            )

        val maxTokens = (body["max_tokens"] as? JsonPrimitive)?.contentOrNullSafe()?.toIntOrNull()
        if (maxTokens != null && maxTokens > maxTokensCap) {
            return ProxyResult.Error(
                HttpStatusCode.BadRequest,
                buildJsonObject { put("error", "max_tokens exceeds limit of $maxTokensCap") },
                logStatus = "bad_request",
            )
        }

        if (channel.apiToken.isBlank()) {
            return ProxyResult.Error(
                HttpStatusCode.InternalServerError,
                buildJsonObject {
                    put("error", "channel_token_missing")
                    put("channel", channel.name)
                },
                logStatus = "channel_token_missing",
            )
        }

        val payload = buildJsonObject {
            body.forEach { (k, v) -> put(k, v) }
            put("model", upstreamModel)
            put("stream", false)
        }

        val (headerName, headerValue) = when (channel.authStyle) {
            AuthStyle.BEARER -> "Authorization" to "Bearer ${channel.apiToken}"
            AuthStyle.CF_AIG -> "cf-aig-authorization" to "Bearer ${channel.apiToken}"
        }

        logger.info("Forwarding to channel={}, model={}, ip={}", channel.name, upstreamModel, clientIp)

        val resp = httpClient.post(channel.baseUrl) {
            contentType(ContentType.Application.Json)
            header(headerName, headerValue)
            setBody(payload.toString())
        }

        val bytes = resp.bodyAsBytes()
        logger.info("Channel {} response status={}, ip={}", channel.name, resp.status.value, clientIp)
        return ProxyResult.Success(
            status = resp.status,
            bytes = bytes,
            model = upstreamModel,
            provider = channel.name,
            usage = fromUpstreamBytes(bytes),
        )
    }
}

sealed class ProxyResult {
    data class Success(
        val status: HttpStatusCode,
        val bytes: ByteArray,
        val model: String,
        val provider: String,
        val usage: TokenUsage?,
    ) : ProxyResult()

    data class Error(
        val status: HttpStatusCode,
        val body: JsonObject,
        val logStatus: String = "upstream_error",
    ) : ProxyResult()
}

private fun JsonPrimitive.contentOrNullSafe(): String? =
    if (this.isString) this.content else this.content.takeIf { it.isNotEmpty() }
```

- [ ] **Step 5: 改 LlmRoute.kt 两处**

在 `server/src/main/kotlin/com/mamba/picme/server/llm/LlmRoute.kt`：

第 64 行（Success 分支 `provider = result.provider.name,`）改为:

```kotlin
                            provider = result.provider,
```

第 78 行（Error 分支）把:

```kotlin
                        UsageRecorder.log(it, requestedModel, "", null, 0, "upstream_error", null, prices)
```

改为:

```kotlin
                        UsageRecorder.log(it, requestedModel, "", null, 0, result.logStatus, null, prices)
```

- [ ] **Step 6: 改 Application.kt 的 LlmProxy 构造**

在 `server/src/main/kotlin/com/mamba/picme/server/Application.kt` 的 `module(config)` 内，把:

```kotlin
    val llmProxy = LlmProxy(
        httpClient = httpClient,
        cloudflareUrl = config.cloudflareAigUrl,
        cloudflareAigToken = config.cloudflareAigToken,
        tokenhubUrl = config.tokenhubUrl,
        tokenhubApiToken = config.tokenhubApiToken,
        forceProvider = config.forceProvider.takeIf { it.isNotBlank() },
        maxTokensCap = config.maxTokensCap,
    )
```

改为:

```kotlin
    val llmProxy = LlmProxy(
        httpClient = httpClient,
        maxTokensCap = config.maxTokensCap,
    )
```

- [ ] **Step 7: 运行测试确认通过**

Run: `./gradlew :server:test --tests "com.mamba.picme.server.llm.LlmProxyChannelTest"`
Expected: PASS（全部 8 个用例）。

全量编译 + 既有测试回归:
Run: `./gradlew :server:test`
Expected: BUILD SUCCESSFUL（`AppConfigTest`/`AdminViewsTest`/`AdminRoutesTest`/`LlmCallLogsTest` 等仍绿；`LlmProxyUsageTest` 已删）。

- [ ] **Step 8: 提交**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/llm/LlmProxy.kt \
        server/src/main/kotlin/com/mamba/picme/server/llm/LlmRoute.kt \
        server/src/main/kotlin/com/mamba/picme/server/Application.kt \
        server/src/test/kotlin/com/mamba/picme/server/llm/LlmProxyChannelTest.kt
git commit -m "refactor(server): LlmProxy reads active channel from ChannelRegistry + per-error logStatus"
```

---

## Task 7: 后台渠道页（路由 + 视图 + 导航）

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/admin/AdminRoutes.kt`
- Modify: `server/src/main/kotlin/com/mamba/picme/server/admin/AdminViews.kt`
- Test: `server/src/test/kotlin/com/mamba/picme/server/admin/AdminChannelsRoutesTest.kt`

- [ ] **Step 1: 写失败测试**

Create `server/src/test/kotlin/com/mamba/picme/server/admin/AdminChannelsRoutesTest.kt`:

```kotlin
package com.mamba.picme.server.admin

import com.mamba.picme.server.db.LlmChannels
import com.mamba.picme.server.llm.ChannelRegistry
import com.mamba.picme.server.util.TestDb
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.application
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AdminChannelsRoutesTest {

    private val token = "test-admin-token"
    private val cookieVal get() = AdminAuth.expectedCookieValue(token)

    @Before
    fun setUp() {
        TestDb.init(LlmChannels)
        ChannelRegistry.setActiveForTesting(null)
    }

    private fun formBody(
        name: String = "DeepSeek 直连",
        kind: String = "direct",
        baseUrl: String = "https://api.deepseek.com/v1/chat/completions",
        authStyle: String = "bearer",
        apiToken: String = "sk-test-1234",
        modelMap: String = "deepseek-v4-flash=deepseek-v4-flash",
        enabled: String = "1",
    ) = "name=$name&kind=$kind&base_url=$baseUrl&auth_style=$authStyle" +
        "&api_token=$apiToken&model_map=$modelMap&enabled=$enabled"

    @Test
    fun `channels page requires cookie`() = testApplication {
        application { routing { adminRoute(token) } }
        val c = createClient { followRedirects = false }
        val r = c.get("/admin/channels")
        assertEquals(HttpStatusCode.Found, r.status)
        assertEquals("/admin/login", r.headers[HttpHeaders.Location])
    }

    @Test
    fun `create channel then it appears and token is masked`() = testApplication {
        application { routing { adminRoute(token) } }
        val c = createClient { followRedirects = false }

        val r = c.post("/admin/channels") {
            cookie(AdminAuth.COOKIE_NAME, cookieVal)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(formBody())
        }
        assertEquals(HttpStatusCode.Found, r.status)

        val page = c.get("/admin/channels") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.OK, page.status)
        val html = page.bodyAsText()
        assertTrue(html.contains("DeepSeek 直连"))
        assertTrue("token 不得明文出现", !html.contains("sk-test-1234"))
        assertTrue("应显示掩码", html.contains("••••"))
    }

    @Test
    fun `activate sets channel active`() = testApplication {
        application { routing { adminRoute(token) } }
        val c = createClient { followRedirects = false }
        c.post("/admin/channels") {
            cookie(AdminAuth.COOKIE_NAME, cookieVal)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(formBody())
        }
        // id=1（首条）
        c.post("/admin/channels/1/activate") {
            cookie(AdminAuth.COOKIE_NAME, cookieVal)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("")
        }
        val html = c.get("/admin/channels") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }.bodyAsText()
        assertTrue(html.contains("生效中"))
    }

    @Test
    fun `delete active channel is rejected`() = testApplication {
        application { routing { adminRoute(token) } }
        val c = createClient { followRedirects = false }
        c.post("/admin/channels") {
            cookie(AdminAuth.COOKIE_NAME, cookieVal)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(formBody())
        }
        c.post("/admin/channels/1/activate") {
            cookie(AdminAuth.COOKIE_NAME, cookieVal)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("")
        }
        c.post("/admin/channels/1/delete") {
            cookie(AdminAuth.COOKIE_NAME, cookieVal)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("")
        }
        val html = c.get("/admin/channels") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }.bodyAsText()
        assertTrue("生效渠道应仍存在", html.contains("DeepSeek 直连"))
    }

    @Test
    fun `nav has channels link`() = testApplication {
        application { routing { adminRoute(token) } }
        val c = createClient { followRedirects = false }
        val html = c.get("/admin") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }.bodyAsText()
        assertTrue(html.contains("/admin/channels"))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :server:test --tests "com.mamba.picme.server.admin.AdminChannelsRoutesTest"`
Expected: FAIL — `/admin/channels` 路由不存在（404/redirect 行为不符）。

- [ ] **Step 3: 加渠道路由到 AdminRoutes.kt**

在 `server/src/main/kotlin/com/mamba/picme/server/admin/AdminRoutes.kt`：

(a) 顶部 import 区加（`ApplicationCall`/`call`/`receiveParameters`/`respondRedirect`/`respondText`/`get`/`post`/`route`/`HttpStatusCode`/`ContentType` 已在文件中 import，勿重复）:

```kotlin
import com.mamba.picme.server.llm.ChannelInput
import com.mamba.picme.server.llm.ChannelRegistry
import com.mamba.picme.server.llm.ChannelRepository
import com.mamba.picme.server.llm.parseModelMapLines
```

(b) 在 `route("/admin") { ... }` 块内、`get("/traffic") { ... }` 之后、块的闭合 `}` 之前，插入:

```kotlin
        get("/channels") {
            if (!call.adminGuard(adminToken)) return@get
            val channels = ChannelRepository.list()
            call.respondText(AdminViews.channelsPage(channels), ContentType.Text.Html)
        }

        get("/channels/new") {
            if (!call.adminGuard(adminToken)) return@get
            call.respondText(AdminViews.channelFormPage(), ContentType.Text.Html)
        }

        get("/channels/{id}/edit") {
            if (!call.adminGuard(adminToken)) return@get
            val id = call.parameters["id"]?.toIntOrNull()
            val row = if (id != null) ChannelRepository.get(id) else null
            if (id == null || row == null) {
                call.respondText("not found", contentType = ContentType.Text.Plain, status = HttpStatusCode.NotFound)
                return@get
            }
            call.respondText(AdminViews.channelFormPage(row), ContentType.Text.Html)
        }

        post("/channels") {
            if (!call.adminGuard(adminToken)) return@post
            val input = call.parseChannelInput()
            if (input == null) {
                call.respondText(
                    AdminViews.channelsPage(ChannelRepository.list(), error = "表单参数错误：检查 model_map 格式（每行 请求名=上游名）"),
                    ContentType.Text.Html,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }
            try {
                ChannelRepository.create(input)
            } catch (e: Exception) {
                call.respondText(
                    AdminViews.channelsPage(ChannelRepository.list(), error = "创建失败：名称可能重复"),
                    ContentType.Text.Html,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }
            ChannelRegistry.reload()
            call.respondRedirect("/admin/channels")
        }

        post("/channels/{id}") {
            if (!call.adminGuard(adminToken)) return@post
            val id = call.parameters["id"]?.toIntOrNull()
            val input = call.parseChannelInput()
            if (id != null && input != null) {
                try {
                    ChannelRepository.update(id, input)
                } catch (e: Exception) {
                    // 唯一约束冲突等：忽略，回列表
                }
                ChannelRegistry.reload()
            }
            call.respondRedirect("/admin/channels")
        }

        post("/channels/{id}/activate") {
            if (!call.adminGuard(adminToken)) return@post
            val id = call.parameters["id"]?.toIntOrNull()
            if (id != null) {
                ChannelRepository.setActive(id)
                ChannelRegistry.reload()
            }
            call.respondRedirect("/admin/channels")
        }

        post("/channels/{id}/toggle") {
            if (!call.adminGuard(adminToken)) return@post
            val id = call.parameters["id"]?.toIntOrNull()
            if (id != null) {
                val current = ChannelRepository.get(id)
                if (current != null) ChannelRepository.setEnabled(id, !current.enabled)
                ChannelRegistry.reload()
            }
            call.respondRedirect("/admin/channels")
        }

        post("/channels/{id}/delete") {
            if (!call.adminGuard(adminToken)) return@post
            val id = call.parameters["id"]?.toIntOrNull()
            if (id != null) {
                ChannelRepository.delete(id)
                ChannelRegistry.reload()
            }
            call.respondRedirect("/admin/channels")
        }
```

(c) 在文件底部（`isHttps()` 函数之后）加表单解析 helper:

```kotlin
/** 解析渠道表单为 ChannelInput；model_map 解析失败返回 null。 */
private suspend fun ApplicationCall.parseChannelInput(): ChannelInput? {
    val params = receiveParameters()
    val modelMap = try {
        parseModelMapLines(params["model_map"] ?: "")
    } catch (e: IllegalArgumentException) {
        return null
    }
    val name = (params["name"] ?: "").trim()
    val baseUrl = (params["base_url"] ?: "").trim()
    if (name.isEmpty() || baseUrl.isEmpty()) return null
    if (name.length > 32) return null
    return ChannelInput(
        name = name,
        kind = (params["kind"] ?: "direct").trim(),
        baseUrl = baseUrl,
        authStyle = (params["auth_style"] ?: "bearer").trim(),
        apiToken = (params["api_token"] ?: "").trim(),
        modelMap = modelMap,
        enabled = (params["enabled"] ?: "0") == "1",
    )
}
```

- [ ] **Step 4: 加渠道视图 + 导航链接到 AdminViews.kt**

在 `server/src/main/kotlin/com/mamba/picme/server/admin/AdminViews.kt`：

(a) 顶部 import 区补（仅列尚未 import 的；`a`/`form`/`h1`/`input`/`textInput`/`table`/`td`/`th`/`tr`/`p`/`InputType`/`FormMethod`/`FlowContent`/`HTML`/`createHTML` 等已有，勿重复）:

```kotlin
import com.mamba.picme.server.llm.ChannelRow
import com.mamba.picme.server.llm.renderModelMapLines
import kotlinx.html.br
import kotlinx.html.label
import kotlinx.html.option
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.textArea
```

(b) 在 `navBar()` 函数的 `div("nav-links") { ... }` 内，`a("/admin/traffic", ...)` 之后加一行:

```kotlin
                a("/admin/channels", classes = "nav-link") { +"渠道" }
```

(c) 在 `trafficPage(...)` 函数之后、「// ── 公共片段 ──」注释之前，加两个函数:

```kotlin
    fun channelsPage(channels: List<ChannelRow>, error: String? = null): String = createHTML().html {
        adminHead("渠道 · PicMe 管理后台")
        body {
            navBar()
            h1 { +"渠道" }
            if (error != null) p("err") { +error }
            p {
                a("/admin/channels/new", classes = "btn") { +"新增渠道" }
            }
            table {
                tr {
                    th { +"名称" }
                    th { +"类型" }
                    th { +"BaseURL" }
                    th { +"Token" }
                    th { +"启用" }
                    th { +"生效" }
                    th { +"操作" }
                }
                channels.forEach { ch ->
                    tr {
                        td { +ch.name }
                        td { +ch.kind }
                        td { +ch.baseUrl }
                        td { +ch.apiTokenMasked }
                        td { +(if (ch.enabled) "启用" else "停用") }
                        td { if (ch.isActive) span("active-badge") { +"生效中" } }
                        td {
                            a("/admin/channels/${ch.id}/edit", classes = "btn-sm") { +"编辑" }
                            +" "
                            form(action = "/admin/channels/${ch.id}/activate", method = FormMethod.post, classes = "inline") {
                                input(type = InputType.submit, classes = "btn-sm") { value = "设为生效" }
                            }
                            +" "
                            form(action = "/admin/channels/${ch.id}/toggle", method = FormMethod.post, classes = "inline") {
                                input(type = InputType.submit, classes = "btn-sm") { value = if (ch.enabled) "停用" else "启用" }
                            }
                            +" "
                            form(action = "/admin/channels/${ch.id}/delete", method = FormMethod.post, classes = "inline") {
                                input(type = InputType.submit, classes = "btn-sm btn-danger") { value = "删除" }
                            }
                        }
                    }
                }
            }
        }
    }

    fun channelFormPage(existing: ChannelRow? = null): String = createHTML().html {
        val title = if (existing == null) "新增渠道" else "编辑渠道"
        val action = if (existing == null) "/admin/channels" else "/admin/channels/${existing.id}"
        adminHead("$title · PicMe 管理后台")
        body {
            navBar()
            h1 { +title }
            form(action = action, method = FormMethod.post, classes = "chan-form") {
                p {
                    label { +"名称（≤32）" }
                    br()
                    textInput(name = "name") {
                        value = existing?.name ?: ""
                        placeholder = "如 DeepSeek 直连"
                    }
                }
                p {
                    label { +"类型" }
                    br()
                    select {
                        name = "kind"
                        option {
                            value = "gateway"
                            if (existing?.kind == "gateway") selected = true
                            +"gateway"
                        }
                        option {
                            value = "direct"
                            if (existing == null || existing.kind == "direct") selected = true
                            +"direct"
                        }
                    }
                }
                p {
                    label { +"BaseURL" }
                    br()
                    textInput(name = "base_url") {
                        value = existing?.baseUrl ?: ""
                        placeholder = "https://..."
                    }
                }
                p {
                    label { +"鉴权方式" }
                    br()
                    select {
                        name = "auth_style"
                        option {
                            value = "bearer"
                            if (existing?.authStyle != "cf_aig") selected = true
                            +"bearer (Authorization: Bearer)"
                        }
                        option {
                            value = "cf_aig"
                            if (existing?.authStyle == "cf_aig") selected = true
                            +"cf_aig (cf-aig-authorization)"
                        }
                    }
                }
                p {
                    label { +"API Token（编辑时留空=保持不变）" }
                    br()
                    input(type = InputType.password, name = "api_token") {
                        placeholder = if (existing != null) "••••（留空不变）" else ""
                    }
                }
                p {
                    label { +"模型映射（每行 请求名=上游名）" }
                    br()
                    textArea {
                        name = "model_map"
                        rows = "6"
                        cols = "50"
                        +(existing?.modelMap?.let { renderModelMapLines(it) } ?: "deepseek-chat=glm-5.2")
                    }
                }
                p {
                    label { +"启用" }
                    input(type = InputType.checkbox, name = "enabled") {
                        value = "1"
                        if (existing?.enabled ?: true) checked = true
                    }
                }
                p { input(type = InputType.submit, classes = "btn") { value = "保存" } }
                p { a("/admin/channels") { +"取消" } }
            }
        }
    }
```

(d) 在 `adminHead` 的 `<style>` 块（`@media (max-width:640px){...}` 之前）追加渠道页样式:

```css
.chan-form{max-width:640px;margin:16px auto;padding:0 20px}
.chan-form p{margin:8px 0}
.chan-form label{display:block;font-size:13px;color:#374151;margin-bottom:4px}
.chan-form input[type=text],.chan-form input[type=password],.chan-form select,.chan-form textarea{padding:8px;border:1px solid #d1d5db;border-radius:6px;width:100%;font-size:14px;font-family:inherit}
.chan-form textarea{font-family:monospace}
.inline{display:inline}
.btn-sm{padding:4px 10px;background:#6b7280;color:#fff;border:none;border-radius:5px;font-size:12px;cursor:pointer}
.btn-danger{background:#dc2626}
.active-badge{color:#16a34a;font-weight:600}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew :server:test --tests "com.mamba.picme.server.admin.AdminChannelsRoutesTest"`
Expected: PASS（全部 5 个用例）。

并确认 AdminViewsTest 仍绿:
Run: `./gradlew :server:test --tests "com.mamba.picme.server.admin.AdminViewsTest"`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/admin/AdminRoutes.kt \
        server/src/main/kotlin/com/mamba/picme/server/admin/AdminViews.kt \
        server/src/test/kotlin/com/mamba/picme/server/admin/AdminChannelsRoutesTest.kt
git commit -m "feat(server): admin /admin/channels page — CRUD + activate + token masking"
```

---

## Task 8: `.env.example` 标注 + 版本号 + 全量回归

**Files:**
- Modify: `server/.env.example`
- Modify: `server/build.gradle.kts`

- [ ] **Step 1: 标注 .env.example**

在 `server/.env.example` 中，把「LLM 代理」段（`CLOUDFLARE_AIG_URL` 到 `MAX_TOKENS_CAP=4096`）整段替换为:

```bash
# LLM 代理 —— 上游密钥只在服务端持有，App 不持有
# 注意：以下 CLOUDFLARE_* / TOKENHUB_* / FORCE_PROVIDER 仅用于「首次启动播种」
# llm_channel 表；播种完成后由后台 /admin/channels 管理（DB 为唯一事实源），
# 改 env 不再生效。后台可新增 DeepSeek/GLM/Kimi 直连渠道并填各自 Token。
# Cloudflare AI Gateway (DeepSeek)
CLOUDFLARE_AIG_URL=https://gateway.ai.cloudflare.com/v1/a7656feec717409a19fa5217f0f7b2f9/picme/compat/chat/completions
CLOUDFLARE_AIG_TOKEN=
# 腾讯 TokenHub
TOKENHUB_URL=https://tokenhub.tencentmaas.com/v1/chat/completions
TOKENHUB_API_TOKEN=
# 首次播种的生效渠道（cloudflare|tokenhub），留空 = Cloudflare
FORCE_PROVIDER=
# 单次请求 max_tokens 上限（防盗刷）
MAX_TOKENS_CAP=4096
```

- [ ] **Step 2: 版本号 0.5.0 → 0.6.0**

在 `server/build.gradle.kts` 把:

```kotlin
version = "0.5.0"
```

改为:

```kotlin
version = "0.6.0"
```

- [ ] **Step 3: 全量测试回归**

Run: `./gradlew :server:test`
Expected: BUILD SUCCESSFUL；全部测试绿（含新增 5 个测试文件 + 既有 `AdminRoutesTest`/`AdminViewsTest`/`AppConfigTest`/`LlmCallLogsTest`/`AdminQueriesTest`/`AdminAuthTest`/`TokenUsageTest`/`UsageRecorderTest`/`AccountServiceIdForTokenHashTest`）。

- [ ] **Step 4: 全量构建**

Run: `./gradlew :server:build`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: 本地冒烟（可选但推荐）**

```bash
cd server
./run-local.sh
# 另一终端：登录后台
# 浏览器开 http://127.0.0.1:8080/admin/login，输入 ADMIN_TOKEN
# 进入「渠道」页，确认 5 个渠道已播种、Cloudflare 生效中
# 编辑「DeepSeek 直连」填 Token、设为生效；切回 Cloudflare
```

Expected: 渠道页正常渲染，切换后 `/v1/chat/completions` 走新生效渠道（日志 `Forwarding to channel=...`）。

- [ ] **Step 6: 提交**

```bash
git add server/.env.example server/build.gradle.kts
git commit -m "chore(server): mark gateway env as seed-only + bump 0.5.0 → 0.6.0"
```

---

## Self-Review Notes

- **Spec 覆盖**：表（T1）、model_map helpers（T2）、CRUD+setActive+loadActive（T3）、内存 holder（T4）、播种5渠道+FORCE_PROVIDER（T5）、LlmProxy 重构+透明映射+auth_style+logStatus（T6）、后台 CRUD UI+掩码+导航（T7）、env 标注+版本（T8）——spec 全部章节有对应任务。
- **类型一致**：`ChannelConfig`/`ChannelRow`/`ChannelInput`/`AuthStyle` 在 T2/T3 定义，T5/T6/T7 使用一致。`ProxyResult.Error.logStatus`（T6）与 `LlmRoute` 用法（T6 Step 5）一致。`ChannelRegistry.setActiveForTesting`（T4）在 T6/T7 测试中使用，签名一致。
- **无占位符**：每步含完整代码或确切命令。
