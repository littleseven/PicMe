# Chat 多轮图片发现：卡片反馈与本地兴趣学习

> **状态**: 设计已确认，待编写实现计划  
> **最后更新**: 2026-07-16  
> **关联需求**: 通过多轮对话查找用户感兴趣的图片，返回结果形态为横滑卡片  
> **关联文档**: `docs/03-TECHNICAL-SPECS/GALLERY_SEARCH.md`, `docs/04-AGENT-CAPABILITIES/CAPABILITY_REGISTRY.md`

---

## 1. 设计目标

在现有 Chat 相册搜索结果横滑卡片（`MediaResultsCarousel`）基础上，增加用户反馈入口：

- 👍 **喜欢**：当前查询下提升该图片的排序权重。
- 👎 **不喜欢**：当前查询下降低该图片的排序权重。
- 🔁 **更多类似**：触发 Agent/搜索进行下一轮相似图片推荐。

> 第一版采用标量反馈：权重只作用于**同一自然语言查询**下的具体图片。跨查询/语义发现“同类图片”作为后续演进方向（见第 11 节）。

所有反馈数据**仅本地持久化**，不上传云端，符合项目 `[PRIVACY]` 红线。

---

## 2. 设计决策

| 决策项 | 选择 | 原因 |
|--------|------|------|
| 交互形态 | **混合模式** | 保留现有文本多轮对话，同时增加卡片直接反馈 |
| 卡片反馈入口 | **卡片内嵌按钮** | 与现有 `LazyRow` 横滑卡片改动最小，易于发现 |
| 兴趣学习范围 | **本地持久化** | 跨会话生效，隐私安全 |
| 系统响应策略 | **分层响应** | 👍/👎 静默重排当前结果；🔁 触发 Agent 下一轮 |
| 实现路径 | **标量反馈权重** | 最小可行改动，直接复用现有搜索 pipeline |

---

## 3. 架构与组件

```
┌─────────────────────────────────────────────────────────────┐
│  UI Layer                                                   │
│  MediaResultsCarousel ──► ChatScreen ──► ChatViewModel     │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  Domain Layer                                               │
│  MediaFeedbackUseCase ──► MediaSearchEngine.mergeAndRank() │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  Data Layer                                                 │
│  MediaFeedbackRepository ──► MediaFeedbackDao ──► Room     │
└─────────────────────────────────────────────────────────────┘
```

| 组件 | 职责 | 主要文件 |
|------|------|----------|
| `MediaResultsCarousel` | 渲染带反馈按钮的横滑卡片 | `features/chat/components/MediaResultsCarousel.kt` |
| `ChatViewModel` | 处理反馈事件，调用 UseCase，更新消息 | `features/chat/ChatViewModel.kt` |
| `MediaFeedbackUseCase` | 保存/查询反馈，计算排序权重 | `domain/search/MediaFeedbackUseCase.kt`（新建） |
| `MediaFeedbackRepository` | 反馈数据仓库抽象 | `data/repository/MediaFeedbackRepository.kt`（新建） |
| `MediaFeedbackDao` | Room 数据访问 | `data/local/MediaFeedbackDao.kt`（新建） |
| `MediaSearchEngine` | 融合反馈权重到排序分 | `domain/search/MediaSearchEngine.kt` |

---

## 4. 数据流

1. **用户点击反馈**：`MediaResultsCarousel` 回调 `onFeedback(mediaId, action)`。
2. **ViewModel 分发**：
   - 👍 / 👎 → `MediaFeedbackUseCase.record(mediaId, query, action)`
   - 🔁 → `ChatViewModel.onRefineMediaSearch(...)`（第一版复用现有 refine 命令）
3. **持久化**：写入 `media_feedback` 表。
4. **排序影响**：
   - `MediaSearchEngine.mergeAndRank()` 查询该 `query` 下的反馈记录。
   - 对当前结果列表加减分：点赞 `+FEEDBACK_LIKE_BONUS`，点踩 `-FEEDBACK_DISLIKE_PENALTY`。
   - 权重仅作用于**同一自然语言查询**。
5. **UI 更新**：
   - 同一轮结果立即重排（带动画）。
   - 🔁 新增一条 `MEDIA_RESULTS` 消息展示下一轮结果。

---

## 5. 数据模型

### 5.1 新表：`media_feedback`

```sql
CREATE TABLE media_feedback (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    media_id TEXT NOT NULL,
    feedback_type TEXT NOT NULL, -- 'like' | 'dislike' | 'more_like_this'
    query_text TEXT NOT NULL,
    session_id TEXT NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE INDEX idx_media_feedback_lookup 
ON media_feedback(media_id, query_text, feedback_type);
```

### 5.2 Kotlin 实体

```kotlin
@Entity(tableName = "media_feedback")
data class MediaFeedbackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "media_id") val mediaId: String,
    @ColumnInfo(name = "feedback_type") val feedbackType: String,
    @ColumnInfo(name = "query_text") val queryText: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
```

### 5.3 聚合输出

```kotlin
data class FeedbackScore(
    val mediaId: String,
    val likeCount: Int,
    val dislikeCount: Int
)
```

### 5.4 数据库迁移

- `AppDatabase` 从版本 `9` 升级到 `10`。
- 新增 `MediaFeedbackEntity`、`MediaFeedbackDao`。
- Migration `9 → 10`：执行 `CREATE TABLE` 和 `CREATE INDEX`。

---

## 6. UI 改动

### 6.1 `MediaResultsCarousel`

- 新增参数：
  ```kotlin
  onFeedback: (mediaId: String, action: FeedbackAction) -> Unit
  ```
- 每张 `MediaCard` 右上角叠加一列 24dp 反馈按钮：👍 / 👎 / 🔁。
- 按钮背景半透明，避免遮挡图片主体。
- 已选中的 👍 或 👎 高亮显示；两者互斥。
- 卡片主体点击仍进入 `MediaPager` 大图预览。
- 保留「查看全部」入口瓦片。

### 6.2 状态与动画

- `MediaResultsUi` 增加 `feedbackState: Map<String, FeedbackAction>`。
- 使用 `LazyRow` 的 `animateItemPlacement()` 实现重排动画。
- 按钮点击带轻微缩放反馈。

### 6.3 I18N

新增字符串并三语同步：
- `feedback_like`
- `feedback_dislike`
- `feedback_more_like_this`

---

## 7. Agent / Prompt 改动

### 7.1 第一版：轻量实现

不新增 Agent 命令，🔁 复用现有 `refine_media_search`：

```kotlin
val constraint = "和这张照片类似的：${asset.tags.take(3).joinToString("、")}"
onRefineMediaSearch(constraint)
```

### 7.2 未来增强

可考虑新增 `AgentCommand.MoreLikeThis(mediaId: String)`，由 Agent 解析图片 TAG、场景、embedding 最近邻后生成更自然的推荐。

### 7.3 Prompt

第一版无需修改 `LocalPromptBuilder`，因为 🔁 由 UI 直接触发，不走 LLM 解析。

---

## 8. 错误处理

| 场景 | 行为 |
|------|------|
| 反馈写入 Room 失败 | 记录日志，UI 保持选中状态，下次启动时该次反馈丢失 |
| 读取 feedback 排序失败 | 捕获异常，回退到无反馈默认排序，不影响搜索可用性 |
| 重排后结果为空 | 保持原结果不变，避免空白 |
| 🔁 refine 失败 | 显示“没有找到更多类似照片”文本消息 |
| 用户连续快速点击 | ViewModel 500ms 内去重 |

---

## 9. 测试计划

| 测试类型 | 覆盖点 |
|----------|--------|
| 单元测试 | `MediaFeedbackUseCase.record()` 聚合计数；`MediaSearchEngine` 反馈权重加减分 |
| 数据库测试 | `MediaFeedbackDao` 插入/查询/聚合 |
| Compose UI 测试 | 点击 👍 状态变化；点击 🔁 触发回调 |
| 集成测试 | 搜索 → 显示卡片 → dislike → 同查询再次搜索排名下降 |
| 回归测试 | 现有 `refine_media_search`、MediaPager、查看全部跳转不受影响 |

---

## 10. 红线检查

- [PRIVACY]：反馈数据仅本地存储，不上传。
- [PERF]：反馈查询走索引，排序计算增加 < 5ms。
- [I18N]：所有新增字符串三语同步。
- [AGENT-FIRST]：新组件通过构造函数注入依赖，状态显式编码。

---

## 11. 后续演进方向

1. **Embedding 偏好画像**：聚合点赞图片的 MobileCLIP embedding，用于跨查询语义推荐。
2. **Agent 闭环驱动**：新增 `like_result` / `more_like_this` 命令，让 Agent 主动追问和解释。
3. **负反馈过滤**：连续点踩某类 TAG 时，自动在后续搜索中排除。
