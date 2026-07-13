# 渠道默认模型兜底设计（Channel default_model Fallback）

- **日期**：2026-07-13
- **服务端版本**：0.6.0 → 0.6.1
- **状态**：已对齐方向（评估见上一轮），待实施
- **范围**：给每个 LLM 渠道加一个「默认上游模型」；App 请求的 model 不在该渠道 `model_map` 里时，回落到默认模型转发，而非直接 400。后台可编辑。**只改服务端，不发 App 版本。**

---

## 1. 背景与目标

v0.6.0 渠道管理上线后，App 默认发 `deepseek-v4-flash-202605`（`RemoteModelConfig.PICME_SERVER_DEFAULT`），而生效渠道（如 DeepSeek 直连、甚至默认的 Cloudflare）的 `model_map` 不含这个名字 → `unsupported_model` 400。根因是端上 modelId 与服务端渠道 map 会漂移。

**目标**：让"请求模型不在 map 里"不再硬失败——回落到渠道默认模型；保留 strict 行为（默认模型留空 = 仍 400）给需要严格校验的渠道。立刻消除上述漂移导致的 400，包括切回 Cloudflare 也会撞的同一个。

> 这是「动态下发端上配置」评估里的第 3 层（服务端兜底）。它不依赖端上任何改动，单独可上线；与未来的 `/agent/config` 动态下发（第 2 层）互补。

## 2. 已锁定决策

| 决策点 | 选择 |
|---|---|
| `default_model` 语义 | **原始上游模型名**（直接发给上游，不再过 map）。例：DeepSeek 直连 = `deepseek-v4-flash` |
| 留空行为 | `default_model` 为空 = **strict 模式**，维持现状返回 400 `unsupported_model` |
| 兜底命中时的计费/日志 | 调用正常成功，`llm_call_log.status = "ok"`，`model` 字段记**实际用的默认模型**；额外打一行 INFO 日志标注回落。`usage`/成本照常 |
| 是否新增 logStatus | v1 不加（避免动 `blocked`/`calls` 聚合口径）；如需统计回落频次，后续再加 `ok_default` |
| 列迁移 | Exposed `SchemaUtils.createMissingTablesAndColumns` 自动给现存表补列（SQLite `ALTER TABLE ADD COLUMN ... DEFAULT ''`） |
| 现有渠道回填 | 部署后按渠道名把 5 个播种渠道的 `default_model` 回填（幂等），保证 prod 现有渠道**立即生效**，不用手改 |

## 3. 数据模型

`llm_channel` 加一列：

```sql
ALTER TABLE llm_channel ADD COLUMN default_model TEXT NOT NULL DEFAULT '';
```

`Tables.kt` 的 `LlmChannels` 对象加：

```kotlin
val defaultModel = varchar("default_model", 128).default("")
```

`migrations/004_llm_channel_default_model.sql`（参考 DDL，运行时不执行）记录上述 ALTER。

## 4. 运行时兜底逻辑（LlmProxy.forward）

`ChannelConfig` 增 `defaultModel: String`。`forward()` 里模型解析改为：

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
// 后续 payload/转发/max_tokens/token 校验不变，使用 upstreamModel
```

其余链路（auth_style、stream=false、usage 解析、`provider = channel.name`）不变。

## 5. 后台编辑

- `ChannelRow` / `ChannelInput` 增 `defaultModel: String`；`ChannelRepository` 的 `create/update/toRow/toConfig` 读写该列（更新时空串 = 清空，恢复 strict）。
- `/admin/channels` 编辑表单加一个文本框「默认模型（留空=严格校验，请求不支持的模型时返回 400）」，保存即生效。
- 列表表多一列展示 `defaultModel`（空则显示「严格」）。
- 表单解析 `parseChannelInput` 增 `default_model` 字段（trim，≤128）。

## 6. 播种与现有渠道回填

`Migrations.kt` 引入渠道名→默认模型常量（播种与回填共用）：

```kotlin
private val CHANNEL_DEFAULT_MODEL = mapOf(
    "Cloudflare" to "deepseek/deepseek-chat",
    "TokenHub" to "deepseek-v4-flash-202605",
    "DeepSeek 直连" to "deepseek-v4-flash",
    "GLM 直连" to "glm-5.2",
    "Kimi 直连" to "kimi-k2.7-code",
)
```

- **播种**（首次启动，表空时）：5 行 insert 的 `defaultModel` 取上表。
- **回填**（每次启动幂等跑）：对 `default_model = ''` 且名字命中上表的现存渠道，`UPDATE` 设上表值。这样 prod 现有 5 渠道升级后**立即有兜底**，无需手改；用户新建渠道默认留空（strict）。

## 7. 边界与测试（JVM）

- `LlmProxyChannelTest` 增：① model 不在 map、`defaultModel` 非空 → 转发且 body model=默认；② `defaultModel` 空 → 仍 400 `unsupported_model`（strict 不回归）；③ model 在 map → 仍优先用 mapped（默认不覆盖显式映射）。
- `ChannelRepositoryTest` 增：create/update 读写 `defaultModel`。
- `Migrations` 测试增：回填给名字命中表的空渠道补默认值；幂等（再跑不变）；播种的 5 渠道含 `defaultModel`。
- 既有用例保持绿（`defaultModel` 默认 ""，旧断言不破）。

## 8. 文件清单

- 改：`db/Tables.kt`（加列）、`db/Migrations.kt`（`createMissingTablesAndColumns` + 播种 + 回填）、`llm/ChannelConfig.kt`、`llm/ChannelRepository.kt`、`llm/LlmProxy.kt`（兜底分支）、`admin/AdminRoutes.kt`（表单解析）、`admin/AdminViews.kt`（表单字段 + 列）。
- 新增：`migrations/004_llm_channel_default_model.sql`（参考 DDL）。
- 测试：`LlmProxyChannelTest` / `ChannelRepositoryTest` / `MigrationsSeedChannelsTest` 增用例。

## 9. 部署

`./server/deploy.sh` 蓝绿上线；启动时 `createMissingTablesAndColumns` 给 prod 现表加列，回填 5 渠道默认值。回滚备份 `~/picme-server.prev`。版本 `0.6.0 → 0.6.1`。

## 10. 非目标（v1）

- 不做端上动态下发（第 2 层 `/agent/config`）。
- 不加 `ok_default` 专用 logStatus（回落仍记 `ok`）。
- 不做模糊匹配（仅"map 命中 or 默认值"两路）。
