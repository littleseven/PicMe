# Chat 编辑图片隔离与 LRU 缓存设计

- 日期：2026-07-27
- 状态：已评审，待实现
- 关联：`docs/superpowers/specs/2026-07-18-chat-conversational-image-editing-design.md`（对话式图片编辑，本特性建立在其之上）

## 1. 背景与问题

Chat 页通过对话编辑/优化图片，目前两条渲染链路**在生成结果图时立即写入系统 MediaStore**，导致未经用户确认的中间结果直接出现在相册中：

1. `edit_image` 命令 → `ImageEditCapability` → `ChatEditProcessor.saveBitmapToMediaStore()`：写入 `Pictures/PoLang/CHAT_EDIT_<ts>.jpg` + `mediaRepository.refreshMediaLibrary()`。消息类型 `agent_edit_result`。
2. `aiOptimize` / `adjust_image` → `ChatImageRenderer.saveBitmap()`：私有副本写入 `filesDir/picme_images/edit_<uuid>.jpg`，**同时**写入 `Pictures/PoLang/PoLang_edit_<uuid>.jpg`。消息类型 `agent_image`。

相册数据源 `MediaRepositoryImpl.queryImagesFromMediaStore()` 查询 `MediaStore.Images.Media.EXTERNAL_CONTENT_URI`，覆盖全部共享存储；而应用私有目录 `filesDir/...` 不被 MediaStore 扫描。因此「只写私有目录」天然对相册不可见。

**问题**：未经确认的编辑结果污染相册；中间结果无上限累积，无法回收；删除会话也不清理这些文件。

## 2. 目标

1. **隔离**：chat 编辑/优化产生的结果图，未经用户主动确认，**不在相册可见**。
2. **显式保存**：编辑后的图片在 chat 全屏预览页有「保存到相册」按钮；保存后方在相册可见。
3. **LRU 回收**：未经确认的中间结果按总磁盘容量做 LRU 管理，可被清理；被清理后原展示气泡显示缺省图（「图片已过期·不可见」）。

## 3. 非目标

- 用户主动发送的图（`user_image` / `user_image_text`，存于 `picme_images/`）不在本 LRU 范围，保持现状（其无界增长是既有问题，另案处理）。
- 不提供设置项调节 LRU cap（v1 用常量；设置项留作 future）。
- 不改变相册已有内容的展示与索引。
- 不引入新的跨会话图片管理 UI。

## 4. 关键决策（已与产品确认）

| # | 决策 | 选择 |
|---|---|---|
| D1 | 多轮编辑的 LRU 语义 | **每个气泡独立**：每个编辑结果是独立 LRU 条目，按各自 `lastAccessedAt` 淘汰；保存逐气泡生效。「中间」= 尚未保存的结果 |
| D2 | LRU 淘汰维度 | **按总磁盘容量**，默认 cap `200 MB`（常量，单一来源） |
| D3 | 过期气泡形态 | **灰框 + 图标 + 「图片已过期·不可见」文案**，保留下方说明文字 |
| D4 | 保存后的存储 | **重指向相册 URI**：保存 → 复制到 `Pictures/PoLang` → 消息 `imageUri` 改指向新 `content://` URI → 删私有文件 → 标记 `saved`；此后气泡读 `content://`，免疫 LRU |
| D5 | 整体机制 | **Approach A**：新增 `ChatImageStore` 仓储 + 显式缓存表 + 懒过期检测（`File.exists()`），专用目录与用户图物理隔离 |

## 5. 架构与组件

### 5.1 新增 `ChatImageStore`

- 接口：`domain/repository/ChatImageStore.kt`；实现：`data/repository/ChatImageStoreImpl.kt`。
- 拥有专用缓存目录 `filesDir/chat_edit_cache/`，与用户发自选图的 `picme_images/` **物理隔离**（LRU 永不误伤用户图）。
- 所有 chat 编辑/优化结果图经它落盘，不再直写 MediaStore。
- 关键方法：

```kotlin
interface ChatImageStore {
    /** 渲染结果落盘到 chat_edit_cache/，写 ACTIVE 行，触发 enforceCap，返回 file:// 路径 */
    suspend fun writeResult(sessionId: String, bitmap: Bitmap, mimeType: String): String

    /** 复制私有文件到 Pictures/PoLang，返回新 content:// URI；不动文件、不动表 */
    suspend fun copyToGallery(filePath: String): String?

    /** 删私有文件 + 行置 SAVED（消息已重指向后调用） */
    suspend fun markSaved(filePath: String)

    /** 刷新 lastAccessedAt（打开预览 / 再编辑时调用；非滚动时） */
    suspend fun touch(filePath: String)

    /** 超 cap 则按 lastAccessedAt 最旧的 ACTIVE 逐个删文件 + 置 EVICTED */
    suspend fun enforceCap()

    /** 冷启对账：修缺文件行/孤儿文件/终态行，再 enforceCap */
    suspend fun reconcileColdStart()

    /** 删会话时调用，清理其 ACTIVE 文件 */
    suspend fun evictForSession(sessionId: String)

    companion object {
        const val DEFAULT_MAX_SIZE_BYTES: Long = 200L * 1024 * 1024
    }
}
```

- cap 通过实现类构造参数注入（默认 `DEFAULT_MAX_SIZE_BYTES`），便于测试。
- `writeResult` 落盘文件名用 UUID（不复用，确保行可安全 prune）。

### 5.2 新增 `SaveChatEditResultUseCase`

- 位置：`domain/usecase/SaveChatEditResultUseCase.kt`。
- 编排保存（保证「文件不先于消息重指向被删」）：
  1. 读消息，从 `metadata.imageUri` 解析 `filePath`（剥 `file://`）。
  2. `store.copyToGallery(filePath)` → `contentUri`（失败则返回失败，**不删文件、不改消息**）。
  3. 更新消息 `metadata`：`imageUri = contentUri`、`saved = true`、`savedAt = now`。
  4. `store.markSaved(filePath)`。
- 幂等：`metadata.saved == true` 时直接返回成功（按钮应已禁用，此为兜底）。

### 5.3 修改的组件

- **`ChatEditProcessor`**：`saveBitmapToMediaStore(...)` → `chatImageStore.writeResult(...)`；删除 `mediaRepository.refreshMediaLibrary()`。
- **`ChatImageRenderer.saveBitmap`**：删除 MediaStore 写入块；私有文件写入改走 `chatImageStore.writeResult(...)`（落点从 `picme_images/` 迁至 `chat_edit_cache/`）。
- **`ChatViewModel`**：注入 `ChatImageStore` + `SaveChatEditResultUseCase`；新增 `fun saveEditResult(messageId)`；打开编辑结果预览时 `store.touch(filePath)`；`insertEditResultMessage` / `insertAgentImageMessage` 的 `metadata` 加 `saved=false`；`toUiModel()` 解析 `saved`。
- **`ChatScreen`**：
  - `ImagePreviewOverlay` 增加「保存到相册」按钮（未保存态）→「✓ 已保存到相册」（已保存态，禁用）；需把当前预览的 `messageId + saved + isEditResult` 透传（把裸 `previewImageUri: Uri?` 升级为 `PreviewImageState`）。
  - 编辑结果气泡：**scheme 感知存活判定**（见 §7），存活显示图，否则显示灰框 + 图标 + 「图片已过期·不可见」+ 保留说明文字。

## 6. 数据模型

### 6.1 新增 Room 表（v14 → v15，`MIGRATION_14_15`）

```kotlin
@Entity(
    tableName = "chat_image_cache",
    indices = [Index("lastAccessedAt"), Index("sessionId")]
)
data class ChatImageCacheEntity(
    @PrimaryKey val filePath: String,   // filesDir/chat_edit_cache/ 下绝对路径
    val sessionId: String,
    val createdAt: Long,
    val lastAccessedAt: Long,
    val sizeBytes: Long,
    val status: String                  // ACTIVE | SAVED | EVICTED
)
```

`ChatImageCacheDao`：
- `upsert(row)`、`updateStatus(filePath, status)`、`updateLastAccessed(filePath, ts)`
- `sumSizeWhereActive(): Long`、`oldestActive(limit): List<ChatImageCacheEntity>`
- `deleteByPath(filePath)`、`getBySession(sessionId)`、`pruneTerminalRows()`

`MIGRATION_14_15`：`CREATE TABLE chat_image_cache (...)` + 两个索引。

### 6.2 消息表（不动 schema）

`ChatMessageEntity` 无字段变更。`agent_image` / `agent_edit_result` 的 `metadata` JSON 增字段：
- `saved: Boolean`（默认 `false`）
- `savedAt: Long?`

`ChatMessageUi` 增 `imageSaved: Boolean = false`；`toUiModel()` 解析 `saved`。

## 7. 流程

### (a) 编辑 → 隔离（不入相册）

`edit_image` / `aiOptimize` / `adjust_image` → 渲染 bitmap → `chatImageStore.writeResult(sessionId, bitmap, mime)`：
1. 落盘 `chat_edit_cache/edit_<uuid>.jpg`。
2. 插入缓存行（`ACTIVE`，`sizeBytes`，`lastAccessedAt = now`）。
3. `enforceCap()`（可能淘汰更旧的 `ACTIVE`，新写入最新必存活；单文件 > cap 仅 log 不自删）。
4. 返回 `file://<absPath>`。

→ 消息 `metadata = {imageUri: file://..., saved:false}`。**全程不碰 MediaStore** → 相册不可见。

### (b) 保存 → 重指向

预览页点「保存到相册」→ `viewModel.saveEditResult(messageId)` → `SaveChatEditResultUseCase`：
1. `copyToGallery(filePath)` → `contentUri`（写 `Pictures/PoLang/PoLang_edit_<uuid>.jpg`，Q+ 用 `IS_PENDING` 两段提交）。
2. 更新消息 `metadata`：`imageUri = contentUri`、`saved = true`、`savedAt`。
3. `markSaved(filePath)`：删私有文件 + 行置 `SAVED`。

气泡从此读 `content://`，**免疫 LRU**；预览按钮变「✓ 已保存到相册」；相册含此图。

### (c) LRU 淘汰

触发：每次 `writeResult` 后 + 冷启动 `reconcileColdStart` 内。`enforceCap()`：
- `sumSizeWhereActive()` > cap 时，按 `lastAccessedAt` 升序取 `oldestActive`，逐个删文件 + `updateStatus(EVICTED)`，直到 ≤ cap。
- recency 由 `touch()` 在**有意义使用**时刷新（打开预览为必需；再编辑时 touch 为 v1 可选）。

### (d) 过期检测（UI）

纯函数（可单测）：

```kotlin
fun chatImageIsLive(uri: String?): Boolean =
    !uri.isNullOrBlank() &&
        (uri.startsWith("content://") || File(uri.removePrefix("file://")).exists())
```

`AGENT_IMAGE` / `AGENT_EDIT_RESULT` 气泡：存活 → `AsyncImage`；否则 → 灰框 + 图标 + 「图片已过期·不可见」+ 保留说明文字。判定基于 `File.exists()`（stat），**不依赖 Coil 内存缓存**，确定性强。

## 8. 边界情况

| 情况 | 处理 |
|---|---|
| 保存时文件已被淘汰 | `copyToGallery` 失败 → UseCase 返回失败、不删文件（已不存在）、不改消息 → UI toast「图片已过期，无法保存」并切过期视图 |
| 重复点保存 | 幂等：`saved=true` 直接返回；按钮禁用 |
| 保存中途被杀 | 消息未重指向 → 文件仍 `ACTIVE` → 重试 OK；已重指向但未删文件 → `reconcileColdStart` 修（`SAVED` 行文件仍在 → 删） |
| 终态行堆积 | `reconcileColdStart` 顺手 prune 无文件的 `SAVED`/`EVICTED` 行（UUID 文件名不复用，安全） |
| `content://` 跨重启 | MediaStore URI 稳定，Coil 可直读 |
| 多轮再编辑已保存结果 | `ChatEditProcessor.normalizeSourceUri` 已支持 `content://`；`touch` 对 `content://` 为 no-op |
| `user_image` / `user_image_text` | 不在 LRU 范围，留 `picme_images/`，永不被本特性淘汰 |
| 删除会话 | 调 `store.evictForSession(sessionId)` 清其 `ACTIVE` 文件 |
| 单文件 > cap | `enforceCap` 不自删新写入；log 警告 |

## 9. UI 细节

- **预览保存按钮**：仅对 agent 生成的编辑结果（`AGENT_IMAGE` / `AGENT_EDIT_RESULT`）显示；`user_image` 预览不显示保存按钮。状态机：未保存 → 「保存到相册」（可点）；已保存 → 「✓ 已保存到相册」（禁用）。
- **过期占位气泡**：与原气泡尺寸一致（编辑结果 200dp 高区域），灰底 + 破图/时钟图标 + 「图片已过期·不可见」，下方说明文字保留。点击占位不进预览（或 toast 提示已过期）。
- **i18n**：新增文案同步 `values/`、`values-zh-rCN/`、`values-zh-rTW/`：`保存到相册` / `已保存到相册` / `图片已过期·不可见` / `图片已过期，无法保存` + 相关无障碍描述。

## 10. 测试（JVM，沿用现有 MockK + runTest 风格）

- **`ChatImageStoreImplTest`**（新，临时目录真文件）：`writeResult` 建文件 + 行；`enforceCap` 按 `lastAccessedAt` + size 正确淘汰最旧；`touch` 更新 recency 影响淘汰顺序；`copyToGallery`（mock `ContentResolver`）+ `markSaved` 删文件置 `SAVED`；`reconcileColdStart` 修复缺文件行 / 孤儿文件 / 终态行。
- **`SaveChatEditResultUseCaseTest`**（新）：happy path 重指向 metadata + 释放文件；幂等；缺文件失败且不破坏消息。
- **纯函数测试**：`chatImageIsLive` 对 `content://` / 存在路径 / 不存在路径 / null 的判定。
- **`ChatEditProcessorTest`**（改）：断言改为期望 `file://` 返回、**移除** `refreshMediaLibrary()` 断言；`ChatImageRenderer` 相关测试同步更新。

## 11. 改动清单

### 新增
- `domain/repository/ChatImageStore.kt`
- `data/repository/ChatImageStoreImpl.kt`
- `data/local/entity/ChatImageCacheEntity.kt`
- `data/local/dao/ChatImageCacheDao.kt`
- `domain/usecase/SaveChatEditResultUseCase.kt`
- 3 语种 strings（`保存到相册` / `已保存到相册` / `图片已过期·不可见` / `图片已过期，无法保存` + 无障碍描述）
- 测试：`ChatImageStoreImplTest`、`SaveChatEditResultUseCaseTest`

### 修改
- `data/local/AppDatabase.kt`：v15 + entity + dao + `MIGRATION_14_15`（`CREATE TABLE` + 索引）
- `domain/usecase/ChatEditProcessor.kt`：`saveBitmapToMediaStore` → `store.writeResult`；删 `refreshMediaLibrary`
- `features/chat/ChatImageRenderer.kt`：`saveBitmap` 走 store（落点迁至 `chat_edit_cache/`）
- `features/chat/ChatViewModel.kt`：注入 store + UseCase；`saveEditResult()`；预览 `touch`；插入消息加 `saved=false`；`toUiModel` 解析 `saved`；预览态升级为 `PreviewImageState`
- `features/chat/ChatScreen.kt`：`ImagePreviewOverlay` 保存按钮 + 状态；`ChatMessageItem` 过期占位（`AGENT_IMAGE` + `AGENT_EDIT_RESULT`）；`chatImageIsLive` 工具
- DI：`di/AppContainer.kt` / `features/chat/ChatViewModelDependencies.kt`：提供 store + UseCase + dao，注入两个渲染器
- `app/src/test/.../ChatEditProcessorTest.kt`：更新断言

## 12. 未来（不在本期）

- 设置项暴露 LRU cap（与模型中心/调试页同构）。
- `user_image` / `picme_images/` 的无界增长治理（独立清理策略）。
- 再编辑链路的 `touch` 与「以最新结果为准」的链式语义（如未来需要）。
