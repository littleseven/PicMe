# 渠道默认模型兜底 Implementation Plan

> **状态**：✅ 已完成（server v0.6.1+ 已落地）
> **实现位置**：`server/src/main/kotlin/com/mamba/picme/server/llm/LlmProxy.kt` 等
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** 给每个 LLM 渠道加 `default_model`；App 请求的 model 不在渠道 `model_map` 里时，回落到默认模型转发而非 400；后台可编辑。只改服务端，不发 App。

**Architecture:** `llm_channel` 加 `default_model` 列；`LlmProxy.forward` 模型解析增加「map 命中 → 用映射；否则 default_model 非空 → 回落；否则 400」三路；`Migrations` 用 `createMissingTablesAndColumns` 给现存表补列 + 按渠道名回填默认值（幂等），保证 prod 现有 5 渠道升级即生效。spec：`docs/superpowers/specs/2026-07-13-channel-default-model-fallback-design.md`。

**Tech Stack:** Kotlin / Ktor 3.0.3 / Exposed 0.55.0 / SQLite / kotlinx.html / JUnit 4 / Ktor MockEngine。构建：`gradlew -p server <task>`。

---

## File Structure

**新增：** `server/migrations/004_llm_channel_default_model.sql`（参考 DDL）。

**修改（main）：**
- `db/Tables.kt` — `LlmChannels` 加 `defaultModel` 列。
- `db/Migrations.kt` — `createMissingTablesAndColumns(LlmChannels)`；`seedChannels` 写 `defaultModel`；新增 `backfillDefaultModels()`（internal，幂等）。
- `llm/ChannelConfig.kt` — `ChannelConfig` 加 `defaultModel`。
- `llm/ChannelRepository.kt` — `ChannelInput`/`ChannelRow` 加 `defaultModel`；create/update/toRow/toConfig 读写。
- `llm/LlmProxy.kt` — `forward()` 兜底分支。
- `admin/AdminRoutes.kt` — `parseChannelInput` 读 `default_model`。
- `admin/AdminViews.kt` — `channelFormPage` 加文本框；`channelsPage` 加列。
- `build.gradle.kts` — `0.6.0` → `0.6.1`。

**修改（test）：** `LlmChannelsTableTest`、`ChannelRepositoryTest`、`LlmProxyChannelTest`、`AdminChannelsRoutesTest`、`MigrationsSeedChannelsTest` 增用例。

---

## Task 1: `default_model` 列 + DDL + 迁移

**Files:** Modify `db/Tables.kt`, `db/Migrations.kt`; Create `migrations/004_llm_channel_default_model.sql`; Test `LlmChannelsTableTest`.

- [ ] **Step 1: 写失败测试** — 在 `server/src/test/kotlin/com/mamba/picme/server/db/LlmChannelsTableTest.kt` 现有测试后追加：

```kotlin
    @Test
    fun `default_model column round-trips`() {
        TestDb.init(LlmChannels)
        transaction(Db.instance) {
            LlmChannels.insert {
                it[name] = "T"
                it[kind] = "direct"
                it[baseUrl] = "https://x"
                it[authStyle] = "bearer"
                it[apiToken] = "k"
                it[modelMapJson] = "{}"
                it[defaultModel] = "deepseek-v4-flash"
                it[enabled] = 1
                it[isActive] = 0
                it[createdAt] = 1L
                it[updatedAt] = 1L
            }
        }
        val row = transaction(Db.instance) { LlmChannels.selectAll().first() }
        assertEquals("deepseek-v4-flash", row[LlmChannels.defaultModel])
    }
```

- [ ] **Step 2: 运行确认失败** — `gradlew -p server test --tests "com.mamba.picme.server.db.LlmChannelsTableTest"` → FAIL（`defaultModel` 未解析）。

- [ ] **Step 3: 加列** — 在 `Tables.kt` 的 `LlmChannels` 对象里，`modelMapJson` 之后加：

```kotlin
    val defaultModel = varchar("default_model", 128).default("")
```

- [ ] **Step 4: 迁移补列** — 在 `Migrations.kt` 的 `run()` 内、`SchemaUtils.create(...)` 之后、`seedRules()` 之前，加一行（给现存表补缺失列）：

```kotlin
            SchemaUtils.createMissingTablesAndColumns(LlmChannels)
```

并在文件顶部 import 区加（若无）：

```kotlin
import org.jetbrains.exposed.sql.SchemaUtils
```
（`SchemaUtils` 已 import；`createMissingTablesAndColumns` 是其同包函数，无需额外 import。）

- [ ] **Step 5: 参考 DDL** — Create `server/migrations/004_llm_channel_default_model.sql`：

```sql
-- 参考 DDL（运行时由 Exposed createMissingTablesAndColumns 自动补列）
ALTER TABLE llm_channel ADD COLUMN default_model TEXT NOT NULL DEFAULT '';
```

- [ ] **Step 6: 运行确认通过** — `gradlew -p server test --tests "com.mamba.picme.server.db.LlmChannelsTableTest"` → PASS。

- [ ] **Step 7: 提交**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/db/Tables.kt \
        server/src/main/kotlin/com/mamba/picme/server/db/Migrations.kt \
        server/migrations/004_llm_channel_default_model.sql \
        server/src/test/kotlin/com/mamba/picme/server/db/LlmChannelsTableTest.kt
git commit -m "feat(server): add llm_channel.default_model column + migration"
```

---

## Task 2: `defaultModel` 贯穿 ChannelConfig / Repository

**Files:** Modify `llm/ChannelConfig.kt`, `llm/ChannelRepository.kt`; Test `ChannelRepositoryTest`.

- [ ] **Step 1: 写失败测试** — 在 `ChannelRepositoryTest` 的 `input()` helper 之后加默认参数，并加一个用例。先把 helper 改为：

```kotlin
    private fun input(name: String = "C1", enabled: Boolean = true) = ChannelInput(
        name = name,
        kind = "direct",
        baseUrl = "https://example.com/chat",
        authStyle = "bearer",
        apiToken = "tok-123456",
        modelMap = mapOf("deepseek-chat" to "glm-5.2"),
        enabled = enabled,
        defaultModel = "deepseek-v4-flash",
    )
```

并在类内追加：

```kotlin
    @Test
    fun `create and update carry defaultModel`() = runBlocking {
        val id = ChannelRepository.create(input())
        assertEquals("deepseek-v4-flash", ChannelRepository.get(id)!!.defaultModel)
        ChannelRepository.update(id, input().copy(defaultModel = "glm-5.2"))
        assertEquals("glm-5.2", ChannelRepository.get(id)!!.defaultModel)
        // 空串能清空
        ChannelRepository.update(id, input().copy(defaultModel = ""))
        assertEquals("", ChannelRepository.get(id)!!.defaultModel)
    }
```

- [ ] **Step 2: 运行确认失败** — `gradlew -p server test --tests "com.mamba.picme.server.llm.ChannelRepositoryTest"` → FAIL（`ChannelInput` 无 `defaultModel`）。

- [ ] **Step 3: ChannelConfig 加字段** — 在 `ChannelConfig.kt` 的 data class 末尾加（带默认值，向后兼容现有测试构造）：

```kotlin
data class ChannelConfig(
    val id: Int,
    val name: String,
    val kind: String,
    val baseUrl: String,
    val authStyle: AuthStyle,
    val apiToken: String,
    val modelMap: Map<String, String>,
    val defaultModel: String = "",
)
```

- [ ] **Step 4: ChannelRepository 加字段** — 在 `ChannelRepository.kt`：

(a) `ChannelRow` 末尾加 `val defaultModel: String,`（在 `isActive` 之后）；`ChannelInput` 末尾加 `val defaultModel: String = "",`。

(b) `create` 的 insert 块加（`modelMapJson` 之后）：

```kotlin
            it[LlmChannels.defaultModel] = input.defaultModel
```

(c) `update` 的 update 块加（`modelMapJson` 之后）：

```kotlin
            it[LlmChannels.defaultModel] = input.defaultModel
```

(d) `toRow()` 的 `ChannelRow(...)` 加 `defaultModel = this[LlmChannels.defaultModel],`；`toConfig()` 的 `ChannelConfig(...)` 加 `defaultModel = this[LlmChannels.defaultModel],`。

- [ ] **Step 5: 运行确认通过** — `gradlew -p server test --tests "com.mamba.picme.server.llm.ChannelRepositoryTest"` → PASS（含新用例 + 旧 10 个）。

- [ ] **Step 6: 提交**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/llm/ChannelConfig.kt \
        server/src/main/kotlin/com/mamba/picme/server/llm/ChannelRepository.kt \
        server/src/test/kotlin/com/mamba/picme/server/llm/ChannelRepositoryTest.kt
git commit -m "feat(server): thread defaultModel through ChannelConfig + ChannelRepository"
```

---

## Task 3: `LlmProxy` 兜底分支

**Files:** Modify `llm/LlmProxy.kt`; Test `LlmProxyChannelTest`.

- [ ] **Step 1: 写失败测试** — 在 `LlmProxyChannelTest`：

(a) `cfg()` helper 加 `defaultModel` 参数并传入构造（默认设 `"glm-5.2"` 以便回落测试）：

```kotlin
    private fun cfg(
        authStyle: AuthStyle = AuthStyle.BEARER,
        token: String = "tok-abc",
        modelMap: Map<String, String> = mapOf("deepseek-chat" to "glm-5.2"),
        defaultModel: String = "glm-5.2",
    ) = ChannelConfig(1, "TestChan", "direct", "http://up.example/chat", authStyle, token, modelMap, defaultModel)
```

(b) 把现有 `` `unsupported model returns 400 with logStatus unsupported_model` `` 用例改为显式 `defaultModel = ""`（测 strict 不回归）：

```kotlin
    @Test
    fun `unsupported model with blank default returns 400`() = runBlocking {
        val engine = MockEngine { respond("""{}""", HttpStatusCode.OK) }
        ChannelRegistry.setActiveForTesting(cfg(modelMap = mapOf("a" to "b"), defaultModel = ""))
        val result = proxy(engine).forward("1.2.3.4", buildJsonObject { put("model", "deepseek-chat") })
        assertTrue(result is ProxyResult.Error)
        result as ProxyResult.Error
        assertEquals(HttpStatusCode.BadRequest, result.status)
        assertEquals("unsupported_model", result.logStatus)
    }
```

(c) 追加两个新用例：

```kotlin
    @Test
    fun `unmapped model falls back to default_model`() = runBlocking {
        var captured: HttpRequestData? = null
        val engine = MockEngine { req ->
            captured = req
            respond(usageBody, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        // map 不含 kimi-k2.6，但 defaultModel=glm-5.2
        ChannelRegistry.setActiveForTesting(cfg(modelMap = mapOf("a" to "b"), defaultModel = "glm-5.2"))
        val result = proxy(engine).forward("1.2.3.4", buildJsonObject { put("model", "kimi-k2.6") })
        assertTrue(result is ProxyResult.Success)
        result as ProxyResult.Success
        assertEquals("glm-5.2", result.model) // 用了默认模型
        val sent = (captured!!.body as TextContent).text
        assertTrue(sent.contains("\"model\":\"glm-5.2\""))
    }

    @Test
    fun `mapped model takes precedence over default`() = runBlocking {
        val engine = MockEngine { respond(usageBody, HttpStatusCode.OK, headersOf("Content-Type", "application/json")) }
        ChannelRegistry.setActiveForTesting(cfg(modelMap = mapOf("deepseek-chat" to "mapped-x"), defaultModel = "fallback-y"))
        val result = proxy(engine).forward("1.2.3.4", buildJsonObject { put("model", "deepseek-chat") })
        assertTrue(result is ProxyResult.Success)
        assertEquals("mapped-x", (result as ProxyResult.Success).model)
    }
```

（需确保 import 了 `io.ktor.client.request.HttpRequestData`、`io.ktor.http.content.TextContent`——文件已有。）

- [ ] **Step 2: 运行确认失败** — `gradlew -p server test --tests "com.mamba.picme.server.llm.LlmProxyChannelTest"` → FAIL（回落用例：现在仍返回 400）。

- [ ] **Step 3: 改 LlmProxy.forward** — 在 `LlmProxy.kt` 把模型解析块：

```kotlin
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
```

替换为：

```kotlin
        val mapped = channel.modelMap[requestedModel]
        val upstreamModel: String = when {
            mapped != null -> mapped
            channel.defaultModel.isNotBlank() -> channel.defaultModel.also {
                logger.info(
                    "Model {} not in map of channel {}, fell back to default {}",
                    requestedModel, channel.name, it,
                )
            }
            else -> return ProxyResult.Error(
                HttpStatusCode.BadRequest,
                buildJsonObject {
                    put("error", "unsupported_model")
                    put("active_channel", channel.name)
                    put("supported", channel.modelMap.keys.sorted().joinToString(","))
                    put("default_model", channel.defaultModel)
                },
                logStatus = "unsupported_model",
            )
        }
```

（`logger` 已是文件顶部 `LoggerFactory.getLogger("picme-llm")`；无需新增 import。）

- [ ] **Step 4: 运行确认通过** — `gradlew -p server test --tests "com.mamba.picme.server.llm.LlmProxyChannelTest"` → PASS（含新 2 + 改 1 + 旧 7）。

- [ ] **Step 5: 提交**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/llm/LlmProxy.kt \
        server/src/test/kotlin/com/mamba/picme/server/llm/LlmProxyChannelTest.kt
git commit -m "feat(server): LlmProxy falls back to channel default_model when model unmapped"
```

---

## Task 4: 后台「默认模型」字段 + 列表列

**Files:** Modify `admin/AdminRoutes.kt`, `admin/AdminViews.kt`; Test `AdminChannelsRoutesTest`.

- [ ] **Step 1: 写失败测试** — 在 `AdminChannelsRoutesTest` 的 `create channel then it appears and token is masked` 用例里，把 `formBody()` 调用改为带 `defaultModel`，并断言页含该值。先把 `formBody` helper 加参数：

```kotlin
    private fun formBody(
        name: String = "DeepSeek 直连",
        kind: String = "direct",
        baseUrl: String = "https://api.deepseek.com/v1/chat/completions",
        authStyle: String = "bearer",
        apiToken: String = "sk-test-1234",
        modelMap: String = "deepseek-v4-flash=deepseek-v4-flash",
        enabled: String = "1",
        defaultModel: String = "deepseek-v4-flash",
    ) = "name=$name&kind=$kind&base_url=$baseUrl&auth_style=$authStyle" +
        "&api_token=$apiToken&model_map=$modelMap&enabled=$enabled&default_model=$defaultModel"
```

并在 `` `create channel then it appears and token is masked` `` 用例的断言区追加：

```kotlin
        assertTrue(html.contains("deepseek-v4-flash"))
```

- [ ] **Step 2: 运行确认失败** — `gradlew -p server test --tests "com.mamba.picme.server.admin.AdminChannelsRoutesTest"` → 可能仍 PASS（断言宽松）；先继续实现，Step 5 统一验证。若想确保字段被读，在用例里加一条更严断言（见下）。

在 `create channel then it appears` 用例末尾再加（更严，确认 default_model 落库后被渲染）：

```kotlin
        // 列表/编辑应显示默认模型
        val editHtml = c.get("/admin/channels/1/edit") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }.bodyAsText()
        assertTrue(editHtml.contains("deepseek-v4-flash"))
```

- [ ] **Step 3: AdminRoutes 解析字段** — 在 `AdminRoutes.kt` 的 `parseChannelInput()` 返回的 `ChannelInput(...)` 末尾加：

```kotlin
        defaultModel = (params["default_model"] ?: "").trim(),
```

- [ ] **Step 4: AdminViews 表单 + 列** — 在 `AdminViews.kt`：

(a) `channelFormPage` 里、`model_map` 的 `p { ... }` 块之后，加默认模型字段：

```kotlin
                p {
                    label { +"默认模型（留空=严格校验，请求不支持的模型时返回 400）" }
                    br()
                    textInput(name = "default_model") {
                        value = existing?.defaultModel ?: ""
                        placeholder = "如 deepseek-v4-flash"
                    }
                }
```

(b) `channelsPage` 的表头加一列 `th { +"默认模型" }`（在「Token」之后），表体每行加：

```kotlin
                        td { +(ch.defaultModel.ifBlank { "严格" }) }
```

- [ ] **Step 5: 运行确认通过** — `gradlew -p server test --tests "com.mamba.picme.server.admin.AdminChannelsRoutesTest"` → PASS（含新断言）。

- [ ] **Step 6: 提交**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/admin/AdminRoutes.kt \
        server/src/main/kotlin/com/mamba/picme/server/admin/AdminViews.kt \
        server/src/test/kotlin/com/mamba/picme/server/admin/AdminChannelsRoutesTest.kt
git commit -m "feat(server): admin default_model field + column in /admin/channels"
```

---

## Task 5: 播种默认值 + 现有渠道回填

**Files:** Modify `db/Migrations.kt`; Test `MigrationsSeedChannelsTest`.

- [ ] **Step 1: 写失败测试** — 在 `MigrationsSeedChannelsTest` 追加：

```kotlin
    @Test
    fun `seeded channels carry default_model`() = runBlocking {
        Migrations.seedChannels(config)
        val byName = ChannelRepository.list().associateBy { it.name }
        assertEquals("deepseek/deepseek-chat", byName["Cloudflare"]!!.defaultModel)
        assertEquals("deepseek-v4-flash-202605", byName["TokenHub"]!!.defaultModel)
        assertEquals("deepseek-v4-flash", byName["DeepSeek 直连"]!!.defaultModel)
        assertEquals("glm-5.2", byName["GLM 直连"]!!.defaultModel)
        assertEquals("kimi-k2.7-code", byName["Kimi 直连"]!!.defaultModel)
    }

    @Test
    fun `backfill populates blank default_model for known channels idempotently`() = runBlocking {
        // 模拟 prod 现状：5 渠道 default_model 为空（老版本播种）
        ChannelRepository.create(ChannelInput("Cloudflare", "gateway", "https://x", "cf_aig", "", emptyMap(), true, ""))
        Migrations.backfillDefaultModels()
        assertEquals("deepseek/deepseek-chat", ChannelRepository.list().first { it.name == "Cloudflare" }.defaultModel)
        // 再跑不变
        Migrations.backfillDefaultModels()
        assertEquals("deepseek/deepseek-chat", ChannelRepository.list().first { it.name == "Cloudflare" }.defaultModel)
    }
```

- [ ] **Step 2: 运行确认失败** — `gradlew -p server test --tests "com.mamba.picme.server.db.MigrationsSeedChannelsTest"` → FAIL（`backfillDefaultModels` 未定义 / seed 未写 defaultModel）。

- [ ] **Step 3: 改 Migrations** — 在 `Migrations.kt`：

(a) 文件底部 `TOKENHUB_SEED_MODELS` 旁加常量：

```kotlin
/** 渠道名 → 默认上游模型。播种与回填共用；用户新建渠道默认留空（strict）。 */
internal val CHANNEL_DEFAULT_MODEL = mapOf(
    "Cloudflare" to "deepseek/deepseek-chat",
    "TokenHub" to "deepseek-v4-flash-202605",
    "DeepSeek 直连" to "deepseek-v4-flash",
    "GLM 直连" to "glm-5.2",
    "Kimi 直连" to "kimi-k2.7-code",
)
```

(b) `seedChannels` 的 5 个 insert 块，每个在 `it[modelMapJson] = ...` 之后加：

```kotlin
            it[LlmChannels.defaultModel] = CHANNEL_DEFAULT_MODEL.getValue("Cloudflare")  // 按渠道名换
```

（Cloudflare→"Cloudflare"、TokenHub→"TokenHub"、DeepSeek 直连→"DeepSeek 直连"、GLM 直连→"GLM 直连"、Kimi 直连→"Kimi 直连"。）

(c) `run()` 里 `seedChannels(config)` 之后加一行：

```kotlin
        backfillDefaultModels()
```

(d) 新增函数（`seedChannels` 之后）：

```kotlin
    /**
     * 幂等回填：现存渠道若 default_model 为空且名字命中 CHANNEL_DEFAULT_MODEL，则补默认值。
     * 让 prod 老版本播种的渠道升级后立即有兜底，无需手改。每版启动跑一次，已填则跳过。
     */
    internal fun backfillDefaultModels() {
        transaction(Db.instance) {
            LlmChannels.selectAll().toList().forEach { row ->
                if (row[LlmChannels.defaultModel].isBlank()) {
                    val dm = CHANNEL_DEFAULT_MODEL[row[LlmChannels.name]] ?: return@forEach
                    LlmChannels.update({ LlmChannels.id eq row[LlmChannels.id] }) {
                        it[LlmChannels.defaultModel] = dm
                    }
                }
            }
        }
    }
```

（`update` 的 where lambda 是 `SqlExpressionBuilder.() -> Op<Boolean>`，`eq` 可用；`toList()` 避免迭代中修改。）

- [ ] **Step 4: 运行确认通过** — `gradlew -p server test --tests "com.mamba.picme.server.db.MigrationsSeedChannelsTest"` → PASS（含新 2 + 旧 4）。

- [ ] **Step 5: 提交**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/db/Migrations.kt \
        server/src/test/kotlin/com/mamba/picme/server/db/MigrationsSeedChannelsTest.kt
git commit -m "feat(server): seed + idempotent backfill of channel default_model"
```

---

## Task 6: 版本号 0.6.1 + 全量回归 + 部署

**Files:** Modify `build.gradle.kts`.

- [ ] **Step 1: 版本号** — `build.gradle.kts` 把 `version = "0.6.0"` 改为 `version = "0.6.1"`。

- [ ] **Step 2: 全量回归** — `gradlew -p server test` → BUILD SUCCESSFUL（所有用例绿）。

- [ ] **Step 3: 全量构建** — `gradlew -p server build` → BUILD SUCCESSFUL。

- [ ] **Step 4: 部署** — `./server/deploy.sh`（蓝绿 + healthz + 回滚）。healthz.version 应为 `0.6.1`。

- [ ] **Step 5: 验证 prod** — 
```bash
curl -s "https://api.polang.net/healthz?cb=$RANDOM"   # version=0.6.1
ssh ubuntu@43.161.201.142 'sqlite3 /var/lib/picme/picme.db "SELECT name, default_model FROM llm_channel;"'
```
预期：5 渠道均有 default_model（回填生效）。

- [ ] **Step 6: 提交 + push**

```bash
git add server/build.gradle.kts
git commit -m "chore(server): bump 0.6.0 → 0.6.1 (channel default_model fallback)"
git push origin main
```

---

## Self-Review Notes

- **Spec 覆盖**：列+迁移（T1）、config/repo 贯穿（T2）、兜底分支（T3）、后台编辑（T4）、播种+回填（T5）、版本+回归+部署（T6）——spec 各节均有任务。
- **类型一致**：`defaultModel: String`（默认 `""`）在 ChannelConfig/ChannelInput/ChannelRow 一致；`CHANNEL_DEFAULT_MODEL` 在 seed 与 backfill 共用；`LlmProxy` 读 `channel.defaultModel`；`parseChannelInput` 产 `defaultModel`。
- **strict 不回归**：T3 用 `defaultModel=""` 显式测 400；Task 2/4 用空串能清空。
- **迁移**：`createMissingTablesAndColumns` 给现存表补列；回填幂等。prod 现有 5 渠道升级即有兜底。
