# Chat 多轮图片发现卡片反馈实现计划

> **For agentic workers:** REQUIRED SUB-LEVEL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 Chat 相册搜索结果横滑卡片上增加 👍/👎/🔁 反馈按钮，把反馈持久化到本地 Room，并在同一查询的后续排序中提升/降低相关图片权重；🔁 复用现有 refine 搜索返回下一轮结果。

**Architecture:** 采用标量反馈权重方案。新增 `media_feedback` 表记录用户对具体图片在特定查询下的反馈；`MediaFeedbackUseCase` 负责聚合反馈分数；`MediaSearchEngine.mergeAndRank()` 在最终排序前读取反馈分数并调整权重；UI 层在 `MediaResultsCarousel` 上叠加反馈按钮，通过 `ChatViewModel` 调用 UseCase 并刷新当前结果顺序。

**Tech Stack:** Kotlin, Jetpack Compose, Room, Coroutines/Flow, JUnit4, MockK, Robolectric（测试）, Gradle

---

## 文件结构

### 新建文件

| 文件 | 职责 |
|------|------|
| `app/src/main/java/com/mamba/picme/data/local/entity/MediaFeedbackEntity.kt` | Room 反馈记录实体 |
| `app/src/main/java/com/mamba/picme/data/local/dao/MediaFeedbackDao.kt` | 反馈数据访问对象 |
| `app/src/main/java/com/mamba/picme/data/repository/MediaFeedbackRepository.kt` | 仓库接口 |
| `app/src/main/java/com/mamba/picme/data/repository/MediaFeedbackRepositoryImpl.kt` | 仓库实现 |
| `app/src/main/java/com/mamba/picme/domain/search/MediaFeedbackUseCase.kt` | 反馈业务逻辑 + `FeedbackAction` 枚举 |
| `app/src/main/java/com/mamba/picme/domain/search/FeedbackScore.kt` | 反馈分数数据类 |
| `app/src/test/java/com/mamba/picme/domain/search/MediaFeedbackUseCaseTest.kt` | UseCase 单元测试 |
| `app/src/test/java/com/mamba/picme/domain/search/MediaSearchEngineFeedbackTest.kt` | 搜索反馈排序单元测试 |
| `app/src/test/java/com/mamba/picme/data/local/dao/MediaFeedbackDaoTest.kt` | DAO 数据库测试 |

### 修改文件

| 文件 | 修改内容 |
|------|----------|
| `app/src/main/java/com/mamba/picme/data/local/AppDatabase.kt` | 新增 entity/dao，版本 9 → 10，新增 Migration 9 → 10 |
| `app/src/main/java/com/mamba/picme/domain/search/MediaSearchEngine.kt` | 注入 `MediaFeedbackUseCase`，`mergeAndRankWithScores` 叠加反馈分 |
| `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt` | `MediaResultsUi` 增加 `feedbackState`；`ChatMessageUi` 映射保持不变 |
| `app/src/main/java/com/mamba/picme/features/chat/components/MediaResultsCarousel.kt` | 新增 `onFeedback` 回调与反馈按钮 UI |
| `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt` | 处理反馈事件、重排当前结果、触发 refine |
| `app/src/main/java/com/mamba/picme/features/chat/ChatViewModelDependencies.kt` | 增加 `mediaFeedbackRepository` |
| `app/src/main/java/com/mamba/picme/di/AppContainer.kt` | 创建 Repository 并注入到 `ChatViewModelDependencies` |
| `app/src/main/res/values/strings.xml` | 新增反馈按钮字符串 |
| `app/src/main/res/values-zh/strings.xml` | 新增反馈按钮中文字符串 |
| `app/src/main/res/values-zh-rCN/strings.xml` | 新增反馈按钮简体中文字符串 |
| `app/src/main/res/values-zh-rTW/strings.xml` | 新增反馈按钮繁体中文字符串 |

---

## Task 1：创建反馈实体 `MediaFeedbackEntity`

**Files:**
- Create: `app/src/main/java/com/mamba/picme/data/local/entity/MediaFeedbackEntity.kt`

- [ ] **Step 1：编写实体类**

```kotlin
package com.mamba.picme.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "media_feedback",
    indices = [
        Index(value = ["media_id", "query_text", "feedback_type"], name = "index_media_feedback_lookup")
    ]
)
data class MediaFeedbackEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "media_id") val mediaId: String,
    @ColumnInfo(name = "feedback_type") val feedbackType: String,
    @ColumnInfo(name = "query_text") val queryText: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
```

- [ ] **Step 2：提交**

```bash
git add app/src/main/java/com/mamba/picme/data/local/entity/MediaFeedbackEntity.kt
git commit -m "feat(chat): add MediaFeedbackEntity for image feedback persistence"
```

---

## Task 2：创建 `MediaFeedbackDao`

**Files:**
- Create: `app/src/main/java/com/mamba/picme/data/local/dao/MediaFeedbackDao.kt`

- [ ] **Step 1：编写 DAO**

```kotlin
package com.mamba.picme.data.local.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mamba.picme.data.local.entity.MediaFeedbackEntity

@Dao
interface MediaFeedbackDao {

    @Insert
    suspend fun insert(feedback: MediaFeedbackEntity)

    @Query(
        """
        SELECT media_id, 
               SUM(CASE WHEN feedback_type = 'like' THEN 1 ELSE 0 END) as likeCount,
               SUM(CASE WHEN feedback_type = 'dislike' THEN 1 ELSE 0 END) as dislikeCount
        FROM media_feedback
        WHERE query_text = :queryText
        GROUP BY media_id
        """
    )
    suspend fun getFeedbackScoresForQuery(queryText: String): List<FeedbackScoreRow>

    @Query("SELECT * FROM media_feedback WHERE media_id = :mediaId AND query_text = :queryText")
    suspend fun getFeedbackForMediaAndQuery(mediaId: String, queryText: String): List<MediaFeedbackEntity>
}

data class FeedbackScoreRow(
    @ColumnInfo(name = "media_id") val mediaId: String,
    @ColumnInfo(name = "likeCount") val likeCount: Int,
    @ColumnInfo(name = "dislikeCount") val dislikeCount: Int
)
```

- [ ] **Step 2：提交**

```bash
git add app/src/main/java/com/mamba/picme/data/local/dao/MediaFeedbackDao.kt
git commit -m "feat(chat): add MediaFeedbackDao with query aggregation"
```

---

## Task 3：AppDatabase 升级到版本 10

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/data/local/AppDatabase.kt`

- [ ] **Step 1：添加 entity 和 DAO 抽象方法**

在 `@Database` 注解的 `entities` 数组末尾增加 `MediaFeedbackEntity::class`：

```kotlin
@Database(
    entities = [
        MediaEntity::class,
        ChatMessageEntity::class,
        ChatSessionEntity::class,
        PersonEntity::class,
        FaceEmbeddingEntity::class,
        PhotoEditRecipeEntity::class,
        TagEntity::class,
        MediaTagCrossRef::class,
        OcrWordEntity::class,
        OcrWordOccurrence::class,
        LocationHierarchyEntity::class,
        MediaLocationEntity::class,
        TagScanTaskEntity::class,
        MediaFeedbackEntity::class
    ],
    version = 10,
    exportSchema = false
)
```

在 `AppDatabase` 抽象类中增加：

```kotlin
abstract fun mediaFeedbackDao(): MediaFeedbackDao
```

- [ ] **Step 2：新增 Migration 9 → 10**

在 `addMigrations(...)` 调用中追加 `MIGRATION_9_10`：

```kotlin
.addMigrations(
    MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
    MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10
)
```

在 `companion object` 末尾增加：

```kotlin
/**
 * Migration 9 → 10：新增 media_feedback 表，保存用户对搜索结果的点赞/点踩反馈
 */
private val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `media_feedback` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `media_id` TEXT NOT NULL,
                `feedback_type` TEXT NOT NULL,
                `query_text` TEXT NOT NULL,
                `session_id` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_media_feedback_lookup` ON `media_feedback` (`media_id`, `query_text`, `feedback_type`)"
        )
    }
}
```

- [ ] **Step 3：编译验证**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4：提交**

```bash
git add app/src/main/java/com/mamba/picme/data/local/AppDatabase.kt
git commit -m "feat(chat): add media_feedback table and database migration 9→10"
```

---

## Task 4：创建 `MediaFeedbackRepository`

**Files:**
- Create: `app/src/main/java/com/mamba/picme/data/repository/MediaFeedbackRepository.kt`
- Create: `app/src/main/java/com/mamba/picme/data/repository/MediaFeedbackRepositoryImpl.kt`

- [ ] **Step 1：编写接口**

```kotlin
package com.mamba.picme.data.repository

import com.mamba.picme.domain.search.FeedbackAction
import com.mamba.picme.domain.search.FeedbackScore

interface MediaFeedbackRepository {
    suspend fun recordFeedback(
        mediaId: String,
        queryText: String,
        sessionId: String,
        action: FeedbackAction
    )

    suspend fun getFeedbackScores(queryText: String): List<FeedbackScore>
}
```

- [ ] **Step 2：编写实现**

```kotlin
package com.mamba.picme.data.repository

import com.mamba.picme.data.local.dao.MediaFeedbackDao
import com.mamba.picme.data.local.entity.MediaFeedbackEntity
import com.mamba.picme.domain.search.FeedbackAction
import com.mamba.picme.domain.search.FeedbackScore

class MediaFeedbackRepositoryImpl(
    private val dao: MediaFeedbackDao
) : MediaFeedbackRepository {

    override suspend fun recordFeedback(
        mediaId: String,
        queryText: String,
        sessionId: String,
        action: FeedbackAction
    ) {
        dao.insert(
            MediaFeedbackEntity(
                mediaId = mediaId,
                feedbackType = action.name.lowercase(),
                queryText = queryText,
                sessionId = sessionId,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    override suspend fun getFeedbackScores(queryText: String): List<FeedbackScore> {
        return dao.getFeedbackScoresForQuery(queryText).map { row ->
            FeedbackScore(
                mediaId = row.mediaId,
                likeCount = row.likeCount,
                dislikeCount = row.dislikeCount
            )
        }
    }
}
```

- [ ] **Step 3：提交**

```bash
git add app/src/main/java/com/mamba/picme/data/repository/MediaFeedbackRepository.kt app/src/main/java/com/mamba/picme/data/repository/MediaFeedbackRepositoryImpl.kt
git commit -m "feat(chat): add MediaFeedbackRepository and implementation"
```

---

## Task 5：创建 `FeedbackAction` 枚举与 `FeedbackScore`

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/search/FeedbackAction.kt`
- Create: `app/src/main/java/com/mamba/picme/domain/search/FeedbackScore.kt`

- [ ] **Step 1：编写枚举**

```kotlin
package com.mamba.picme.domain.search

enum class FeedbackAction {
    LIKE,
    DISLIKE,
    MORE_LIKE_THIS
}
```

- [ ] **Step 2：编写分数数据类**

```kotlin
package com.mamba.picme.domain.search

data class FeedbackScore(
    val mediaId: String,
    val likeCount: Int,
    val dislikeCount: Int
)
```

- [ ] **Step 3：提交**

```bash
git add app/src/main/java/com/mamba/picme/domain/search/FeedbackAction.kt app/src/main/java/com/mamba/picme/domain/search/FeedbackScore.kt
git commit -m "feat(chat): add FeedbackAction and FeedbackScore domain models"
```

---

## Task 6：创建 `MediaFeedbackUseCase` 与单元测试

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/search/MediaFeedbackUseCase.kt`
- Create: `app/src/test/java/com/mamba/picme/domain/search/MediaFeedbackUseCaseTest.kt`

- [ ] **Step 1：编写 UseCase**

```kotlin
package com.mamba.picme.domain.search

import com.mamba.picme.data.repository.MediaFeedbackRepository

class MediaFeedbackUseCase(
    private val repository: MediaFeedbackRepository
) {
    suspend fun record(
        mediaId: String,
        queryText: String,
        sessionId: String,
        action: FeedbackAction
    ) {
        repository.recordFeedback(mediaId, queryText, sessionId, action)
    }

    suspend fun getScoresForQuery(queryText: String): Map<String, FeedbackScore> {
        return repository.getFeedbackScores(queryText).associateBy { it.mediaId }
    }

    fun calculateScoreDelta(score: FeedbackScore?): Float {
        if (score == null) return 0f
        return score.likeCount * LIKE_BONUS - score.dislikeCount * DISLIKE_PENALTY
    }

    companion object {
        const val LIKE_BONUS = 0.15f
        const val DISLIKE_PENALTY = 0.15f
    }
}
```

- [ ] **Step 2：编写失败测试**

```kotlin
package com.mamba.picme.domain.search

import com.mamba.picme.data.repository.MediaFeedbackRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaFeedbackUseCaseTest {

    private val repository: MediaFeedbackRepository = mockk(relaxed = true)
    private val useCase = MediaFeedbackUseCase(repository)

    @Test
    fun `record should call repository with mapped action`() = runTest {
        useCase.record("media_1", "海边日落", "session_1", FeedbackAction.LIKE)

        coVerify {
            repository.recordFeedback("media_1", "海边日落", "session_1", FeedbackAction.LIKE)
        }
    }

    @Test
    fun `calculateScoreDelta returns bonus for likes only`() {
        val score = FeedbackScore("media_1", likeCount = 2, dislikeCount = 0)

        val delta = useCase.calculateScoreDelta(score)

        assertEquals(0.30f, delta, 0.001f)
    }

    @Test
    fun `calculateScoreDelta returns negative for dislikes only`() {
        val score = FeedbackScore("media_1", likeCount = 0, dislikeCount = 1)

        val delta = useCase.calculateScoreDelta(score)

        assertEquals(-0.15f, delta, 0.001f)
    }

    @Test
    fun `calculateScoreDelta returns zero for null`() {
        assertEquals(0f, useCase.calculateScoreDelta(null), 0.001f)
    }

    @Test
    fun `getScoresForQuery returns map keyed by media id`() = runTest {
        coEvery { repository.getFeedbackScores("海边日落") } returns listOf(
            FeedbackScore("media_1", 1, 0),
            FeedbackScore("media_2", 0, 1)
        )

        val scores = useCase.getScoresForQuery("海边日落")

        assertEquals(2, scores.size)
        assertEquals(1, scores["media_1"]?.likeCount)
        assertEquals(1, scores["media_2"]?.dislikeCount)
    }
}
```

- [ ] **Step 3：运行测试**

```bash
./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.search.MediaFeedbackUseCaseTest"
```

Expected: BUILD SUCCESSFUL, tests passed

- [ ] **Step 4：提交**

```bash
git add app/src/main/java/com/mamba/picme/domain/search/MediaFeedbackUseCase.kt app/src/test/java/com/mamba/picme/domain/search/MediaFeedbackUseCaseTest.kt
git commit -m "feat(chat): add MediaFeedbackUseCase with unit tests"
```

---

## Task 7：修改 `MediaSearchEngine` 注入反馈权重

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/search/MediaSearchEngine.kt`
- Create: `app/src/test/java/com/mamba/picme/domain/search/MediaSearchEngineFeedbackTest.kt`

- [ ] **Step 1：在主构造函数增加 repository 参数**

```kotlin
class MediaSearchEngine(
    private val mediaDao: MediaDao,
    private val tagDao: TagDao? = null,
    private val ocrWordDao: OcrWordDao? = null,
    private val locationDao: LocationDao? = null,
    private val userSettingsRepository: UserSettingsRepository? = null,
    private val tagTranslator: TagTranslator = TagTranslator(BilingualVocab.empty()),
    private val semanticSearchEngine: SemanticSearchEngine? = null,
    private val explicitFirstPipeline: ExplicitFirstSearchPipeline? = null,
    private val mediaFeedbackUseCase: MediaFeedbackUseCase? = null
) {
```

- [ ] **Step 2：修改 `search` 方法把 query 传递给 mergeAndRank**

把三个调用 `mergeAndRank(results, semanticResults)` 的地方改成：

```kotlin
val merged = mergeAndRank(results, semanticResults, query)
```

- [ ] **Step 3：修改 mergeAndRank 签名与实现**

```kotlin
private fun mergeAndRank(
    sqlResults: List<MediaAsset>,
    semanticResults: List<SemanticScoredMedia>,
    query: String = ""
): List<MediaAsset> = mergeAndRankWithScores(sqlResults, semanticResults, query).map { it.media }
```

```kotlin
private fun mergeAndRankWithScores(
    sqlResults: List<MediaAsset>,
    semanticResults: List<SemanticScoredMedia>,
    query: String = ""
): List<ScoredMediaAsset> {
    val scoreMap = mutableMapOf<Long, Float>()
    val mediaMap = mutableMapOf<Long, MediaAsset>()

    sqlResults.forEachIndexed { index, media ->
        mediaMap[media.id] = media
        val baseScore = 1.0f - (index.toFloat() / (sqlResults.size + 1))
        scoreMap[media.id] = baseScore * SQL_SCORE_WEIGHT
    }

    semanticResults.forEach { scored ->
        mediaMap[scored.media.id] = scored.media
        val existingScore = scoreMap.getOrDefault(scored.media.id, 0f)
        scoreMap[scored.media.id] = existingScore + scored.score * SEMANTIC_SCORE_WEIGHT
    }

    val now = System.currentTimeMillis()
    scoreMap.keys.forEach { id ->
        val media = mediaMap[id] ?: return@forEach
        val daysSinceCapture = (now - media.captureDate) / MS_PER_DAY
        val timeBoost = when {
            daysSinceCapture < TIME_BOOST_RECENT_DAYS -> TIME_BOOST_RECENT
            daysSinceCapture < TIME_BOOST_YEAR_DAYS -> TIME_BOOST_YEAR
            else -> NO_TIME_BOOST
        }
        scoreMap[id] = scoreMap.getOrDefault(id, 0f) + timeBoost * TIME_SCORE_WEIGHT
    }

    // 叠加反馈权重
    applyFeedbackScores(scoreMap, mediaMap, query)

    return scoreMap.entries
        .sortedByDescending { it.value }
        .mapNotNull { mediaMap[it.key]?.let { media -> ScoredMediaAsset(media, it.value) } }
}
```

- [ ] **Step 4：新增 applyFeedbackScores 方法**

```kotlin
@androidx.annotation.VisibleForTesting
internal suspend fun applyFeedbackScores(
    scoreMap: MutableMap<Long, Float>,
    mediaMap: Map<Long, MediaAsset>,
    query: String
) {
    if (query.isBlank() || mediaFeedbackUseCase == null) return

    try {
        val scores = mediaFeedbackUseCase.getScoresForQuery(query)

        scoreMap.keys.forEach { id ->
            val mediaId = mediaMap[id]?.id?.toString() ?: return@forEach
            val score = scores[mediaId]
            val delta = mediaFeedbackUseCase.calculateScoreDelta(score)
            if (delta != 0f) {
                scoreMap[id] = scoreMap.getOrDefault(id, 0f) + delta
            }
        }
    } catch (e: Exception) {
        Logger.w(TAG, "Failed to apply feedback scores", e)
    }
}
```

注意：`mergeAndRankWithScores` 当前不是 `suspend`，但 `applyFeedbackScores` 需要 `suspend`。把 `mergeAndRankWithScores` 改为 `suspend`，并把 `applyFeedbackScores` 改为 `internal` 以便测试访问。

- [ ] **Step 5：更新 searchWithDiagnostics / executeDiagnosticsSearch 里的 mergeAndRankWithScores 调用**

这些调用也需要传入 `query` 参数：

```kotlin
val scoredMerged = mergeAndRankWithScores(
    sqlResults.map { it.media },
    semanticResults.map { SemanticScoredMedia(it.media, it.score) },
    query
)
```

- [ ] **Step 6：编写测试**

```kotlin
package com.mamba.picme.domain.search

import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.data.local.MediaDao
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaSearchEngineFeedbackTest {

    private val mediaDao: MediaDao = mockk(relaxed = true)
    private val feedbackUseCase: MediaFeedbackUseCase = mockk()

    private val engine = MediaSearchEngine(
        mediaDao = mediaDao,
        mediaFeedbackUseCase = feedbackUseCase
    )

    @Test
    fun `liked media ranks higher for same query`() = runTest {
        coEvery { feedbackUseCase.getScoresForQuery("海边") } returns mapOf(
            "2" to FeedbackScore(mediaId = "2", likeCount = 1, dislikeCount = 0)
        )
        every { feedbackUseCase.calculateScoreDelta(any()) } answers {
            val score = it.invocation.args[0] as FeedbackScore?
            if (score == null) 0f else score.likeCount * 0.15f - score.dislikeCount * 0.15f
        }

        val media1 = createMediaAsset(id = 1, mediaId = "1")
        val media2 = createMediaAsset(id = 2, mediaId = "2")

        val scoreMap = mutableMapOf(1L to 0.5f, 2L to 0.5f)
        val mediaMap = mapOf(1L to media1, 2L to media2)

        engine.applyFeedbackScores(scoreMap, mediaMap, "海边")

        assertEquals(true, scoreMap[2L]!! > scoreMap[1L]!!)
    }

    private fun createMediaAsset(id: Long, mediaId: String): MediaAsset {
        return MediaAsset(
            id = id,
            uri = "",
            type = MediaType.PHOTO,
            captureDate = System.currentTimeMillis(),
            fileName = "",
            duration = 0,
            hasFace = false,
            faceId = null,
            source = "",
            labels = null,
            ocrText = null,
            latitude = null,
            longitude = null,
            locationName = null,
            indexedAt = 0
        )
    }
}
```

- [ ] **Step 7：编译并运行测试**

```bash
./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.search.MediaSearchEngineFeedbackTest"
```

Expected: BUILD SUCCESSFUL, tests passed

- [ ] **Step 8：提交**

```bash
git add app/src/main/java/com/mamba/picme/domain/search/MediaSearchEngine.kt app/src/test/java/com/mamba/picme/domain/search/MediaSearchEngineFeedbackTest.kt
git commit -m "feat(chat): integrate feedback scores into MediaSearchEngine ranking"
```

---

## Task 8：更新 `MediaResultsUi` 与 `ChatMessageUi` 映射

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt`

- [ ] **Step 1：在 `MediaResultsUi` 增加 feedbackState**

```kotlin
data class MediaResultsUi(
    val query: String,
    val assets: List<MediaAsset>,
    val totalCount: Int,
    val isRefinement: Boolean,
    val feedbackState: Map<String, FeedbackAction> = emptyMap()
)
```

注意：`FeedbackAction` 已定义在 `com.mamba.picme.domain.search` 包，需要在 `ChatScreen.kt` 中 import。

- [ ] **Step 2：提交**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt
git commit -m "feat(chat): add feedbackState to MediaResultsUi"
```

---

## Task 9：修改 `MediaResultsCarousel` 增加反馈按钮

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/components/MediaResultsCarousel.kt`

- [ ] **Step 1：增加 import 与参数**

```kotlin
import androidx.compose.animation.animateItemPlacement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mamba.picme.R
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.domain.search.FeedbackAction
import com.mamba.picme.features.chat.MediaResultsUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
```

- [ ] **Step 2：修改 `MediaResultsCarousel` 签名与实现**

```kotlin
@Composable
fun MediaResultsCarousel(
    mediaResults: MediaResultsUi,
    onCardClick: (Int) -> Unit,
    onViewAll: () -> Unit = {},
    onFeedback: (mediaId: String, action: FeedbackAction) -> Unit = { _, _ -> }
) {
    val mr = mediaResults
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(
            text = if (mr.isRefinement) "细化：${mr.query}（${mr.assets.size} 张）"
                   else "找到 ${mr.totalCount} 张「${mr.query}」的照片",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        if (mr.assets.isEmpty()) {
            Text(
                text = "未找到「${mr.query}」的照片，换个词试试",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            return@Column
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            itemsIndexed(mr.assets, key = { _, asset -> asset.id }) { index, asset ->
                MediaCard(
                    asset = asset,
                    selectedAction = mr.feedbackState[asset.id.toString()],
                    onClick = { onCardClick(index) },
                    onFeedback = { action -> onFeedback(asset.id.toString(), action) },
                    modifier = Modifier.animateItemPlacement()
                )
            }
            if (mr.totalCount > mr.assets.size) {
                item {
                    ViewAllCard(onClick = onViewAll)
                }
            }
        }
    }
}
```

- [ ] **Step 3：重写 `MediaCard` 以支持反馈按钮**

```kotlin
@Composable
private fun MediaCard(
    asset: MediaAsset,
    selectedAction: FeedbackAction?,
    onClick: () -> Unit,
    onFeedback: (FeedbackAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val dateText = runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(asset.captureDate))
    }.getOrDefault("")

    Card(
        modifier = modifier.size(width = 120.dp, height = 150.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = asset.uri,
                contentDescription = asset.fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                )
            )

            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                FeedbackIconButton(
                    icon = Icons.Rounded.ThumbUp,
                    contentDescription = stringResource(R.string.feedback_like),
                    isSelected = selectedAction == FeedbackAction.LIKE,
                    onClick = { onFeedback(FeedbackAction.LIKE) }
                )
                FeedbackIconButton(
                    icon = Icons.Rounded.ThumbDown,
                    contentDescription = stringResource(R.string.feedback_dislike),
                    isSelected = selectedAction == FeedbackAction.DISLIKE,
                    onClick = { onFeedback(FeedbackAction.DISLIKE) }
                )
                FeedbackIconButton(
                    icon = Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.feedback_more_like_this),
                    isSelected = false,
                    onClick = { onFeedback(FeedbackAction.MORE_LIKE_THIS) }
                )
            }

            Text(
                text = dateText,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomCenter).padding(4.dp)
            )
        }
    }
}

@Composable
private fun FeedbackIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        Color.Black.copy(alpha = 0.4f)
    }

    Box(
        modifier = Modifier
            .size(24.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(16.dp)
        )
    }
}
```

- [ ] **Step 4：编译验证**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5：提交**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/components/MediaResultsCarousel.kt
git commit -m "feat(chat): add like/dislike/more-like feedback buttons to MediaResultsCarousel"
```

---

## Task 10：在 `ChatScreen` 中传递 `onFeedback` 回调

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt`

- [ ] **Step 1：找到 `MediaResultsCarousel` 调用点并添加回调**

在 `ChatScreen.kt` 第 265 行附近：

```kotlin
MediaResultsCarousel(
    mediaResults = mr,
    onCardClick = { index ->
        previewAssets = mr.assets
        previewIndex = index
    },
    onViewAll = {
        onNavigateToGallery(mr.query)
    },
    onFeedback = { mediaId, action ->
        chatViewModel.onMediaFeedback(mediaId, mr.query, action)
    }
)
```

- [ ] **Step 2：提交**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt
git commit -m "feat(chat): wire MediaResultsCarousel feedback callback to ChatViewModel"
```

---

## Task 11：在 `ChatViewModel` 中实现 `onMediaFeedback`

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`

- [ ] **Step 1：增加 import**

```kotlin
import com.mamba.picme.data.repository.MediaFeedbackRepository
import com.mamba.picme.domain.search.FeedbackAction
import com.mamba.picme.domain.search.MediaFeedbackUseCase
```

- [ ] **Step 2：在构造函数中初始化 UseCase**

```kotlin
class ChatViewModel(
    dependencies: ChatViewModelDependencies
) : ViewModel(), ChatSearchCapability.Delegate {

    private val context = dependencies.context.applicationContext
    private val chatMessageDao = dependencies.chatMessageDao
    private val chatSessionDao = dependencies.chatSessionDao
    private val userSettingsRepository = dependencies.userSettingsRepository
    private val mediaSearchEngine = dependencies.mediaSearchEngine
    private val mediaFeedbackRepository = dependencies.mediaFeedbackRepository

    private val mediaFeedbackUseCase = MediaFeedbackUseCase(mediaFeedbackRepository)
```

- [ ] **Step 3：增加去重状态与处理方法**

```kotlin
private val pendingFeedbackActions = mutableSetOf<String>()

fun onMediaFeedback(mediaId: String, query: String, action: FeedbackAction) {
    val key = "$mediaId-$query-${action.name}"
    if (pendingFeedbackActions.contains(key)) return
    pendingFeedbackActions.add(key)

    viewModelScope.launch {
        try {
            when (action) {
                FeedbackAction.LIKE, FeedbackAction.DISLIKE -> {
                    mediaFeedbackUseCase.record(
                        mediaId = mediaId,
                        queryText = query,
                        sessionId = _currentSessionId.value,
                        action = action
                    )
                    updateCurrentResultsFeedback(mediaId, action, query)
                }
                FeedbackAction.MORE_LIKE_THIS -> {
                    triggerMoreLikeThis(mediaId, query)
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to record media feedback", e)
        } finally {
            pendingFeedbackActions.remove(key)
        }
    }
}

private fun updateCurrentResultsFeedback(mediaId: String, action: FeedbackAction, query: String) {
    val currentMessages = _messages.value
    val updatedMessages = currentMessages.map { message ->
        val mr = message.mediaResults
        if (message.type == ChatMessageType.MEDIA_RESULTS && mr != null && mr.query == query) {
            val updatedState = mr.feedbackState.toMutableMap().apply {
                when (action) {
                    FeedbackAction.LIKE -> put(mediaId, FeedbackAction.LIKE)
                    FeedbackAction.DISLIKE -> put(mediaId, FeedbackAction.DISLIKE)
                    else -> { /* no-op */ }
                }
            }
            val reorderedAssets = reorderAssetsByFeedback(mr.assets, updatedState, query)
            message.copy(
                mediaResults = mr.copy(
                    assets = reorderedAssets,
                    feedbackState = updatedState
                )
            )
        } else {
            message
        }
    }
    _messages.value = updatedMessages
}

private suspend fun reorderAssetsByFeedback(
    assets: List<MediaAsset>,
    feedbackState: Map<String, FeedbackAction>,
    query: String
): List<MediaAsset> {
    val scores = mediaFeedbackUseCase.getScoresForQuery(query)
    return assets.sortedByDescending { asset ->
        val score = scores[asset.id.toString()]
        val delta = mediaFeedbackUseCase.calculateScoreDelta(score)
        // 保留原始顺序作为基础分：索引越靠前基础分越高
        val baseIndex = assets.indexOf(asset)
        val baseScore = (assets.size - baseIndex).toFloat()
        baseScore + delta * 100f
    }
}

private suspend fun triggerMoreLikeThis(mediaId: String, query: String) {
    val asset = lastResultAssets[_currentSessionId.value]?.find { it.id.toString() == mediaId }
        ?: return
    val tags = asset.labels?.let { parseLabels(it) }?.take(3) ?: emptyList()
    val constraint = if (tags.isNotEmpty()) {
        "和这张照片类似的：${tags.joinToString("、")}"
    } else {
        "更多类似这张照片的"
    }
    val outcome = onRefineMediaSearch(constraint)
    val refinedAssets = lastResultAssets[_currentSessionId.value].orEmpty().take(MAX_CARDS)
    if (refinedAssets.isNotEmpty()) {
        insertMediaResultsMessage(
            _currentSessionId.value,
            MediaResultsUi(
                query = constraint,
                assets = refinedAssets,
                totalCount = outcome.totalCount,
                isRefinement = true
            )
        )
    } else {
        insertAgentMessage(
            _currentSessionId.value,
            "没有找到更多类似的照片",
            "gallery_search"
        )
    }
}

private fun parseLabels(labelsJson: String): List<String> {
    return try {
        val json = org.json.JSONObject(labelsJson)
        val tags = json.optJSONArray("tags")
        (0 until (tags?.length() ?: 0)).map { tags!!.getString(it) }
    } catch (e: Exception) {
        emptyList()
    }
}
```

- [ ] **Step 4：编译验证**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5：提交**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt
git commit -m "feat(chat): implement onMediaFeedback in ChatViewModel"
```

---

## Task 12：更新 `ChatViewModelDependencies` 与 `AppContainerImpl`

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatViewModelDependencies.kt`
- Modify: `app/src/main/java/com/mamba/picme/di/AppContainer.kt`

- [ ] **Step 1：更新 `ChatViewModelDependencies`**

```kotlin
package com.mamba.picme.features.chat

import android.content.Context
import com.mamba.picme.data.local.ChatMessageDao
import com.mamba.picme.data.local.ChatSessionDao
import com.mamba.picme.data.repository.MediaFeedbackRepository
import com.mamba.picme.domain.repository.UserSettingsRepository
import com.mamba.picme.domain.search.MediaSearchEngine

class ChatViewModelDependencies(
    val context: Context,
    val chatMessageDao: ChatMessageDao,
    val chatSessionDao: ChatSessionDao,
    val userSettingsRepository: UserSettingsRepository,
    val mediaSearchEngine: MediaSearchEngine,
    val mediaFeedbackRepository: MediaFeedbackRepository
)
```

- [ ] **Step 2：在 `AppContainerImpl` 中创建 Repository 并更新 mediaSearchEngine 构造**

在 `AppContainerImpl` 中新增 Repository 和 UseCase：

```kotlin
private val mediaFeedbackRepository: MediaFeedbackRepository by lazy {
    MediaFeedbackRepositoryImpl(database.mediaFeedbackDao())
}

private val mediaFeedbackUseCase: MediaFeedbackUseCase by lazy {
    MediaFeedbackUseCase(mediaFeedbackRepository)
}
```

修改 `mediaSearchEngine` 的构造，注入 UseCase：

```kotlin
override val mediaSearchEngine: MediaSearchEngine by lazy {
    MediaSearchEngine(
        mediaDao = database.mediaDao(),
        tagDao = database.tagDao(),
        ocrWordDao = database.ocrWordDao(),
        locationDao = database.locationDao(),
        userSettingsRepository = userPreferencesRepository,
        tagTranslator = TagTranslator(bilingualVocab, opusMtTranslator, controlledVocab),
        semanticSearchEngine = semanticSearchEngine,
        explicitFirstPipeline = explicitFirstSearchPipeline,
        mediaFeedbackUseCase = mediaFeedbackUseCase
    )
}
```

修改 `chatViewModelDependencies`：

```kotlin
private val chatViewModelDependencies: ChatViewModelDependencies by lazy {
    ChatViewModelDependencies(
        context = context,
        chatMessageDao = database.chatMessageDao(),
        chatSessionDao = database.chatSessionDao(),
        userSettingsRepository = userPreferencesRepository,
        mediaSearchEngine = mediaSearchEngine,
        mediaFeedbackRepository = mediaFeedbackRepository
    )
}
```

- [ ] **Step 3：编译验证**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4：提交**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ChatViewModelDependencies.kt app/src/main/java/com/mamba/picme/di/AppContainer.kt
git commit -m "feat(chat): wire MediaFeedbackRepository into DI graph"
```

---

## Task 13：添加 I18N 字符串

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`
- Modify: `app/src/main/res/values-zh-rTW/strings.xml`

- [ ] **Step 1：在 `values/strings.xml` 末尾添加**

```xml
    <string name="feedback_like">Like</string>
    <string name="feedback_dislike">Dislike</string>
    <string name="feedback_more_like_this">More like this</string>
```

- [ ] **Step 2：在 `values-zh/strings.xml` 添加**

```xml
    <string name="feedback_like">喜欢</string>
    <string name="feedback_dislike">不喜欢</string>
    <string name="feedback_more_like_this">更多类似</string>
```

- [ ] **Step 3：在 `values-zh-rCN/strings.xml` 添加**

```xml
    <string name="feedback_like">喜欢</string>
    <string name="feedback_dislike">不喜欢</string>
    <string name="feedback_more_like_this">更多类似</string>
```

- [ ] **Step 4：在 `values-zh-rTW/strings.xml` 添加**

```xml
    <string name="feedback_like">喜歡</string>
    <string name="feedback_dislike">不喜歡</string>
    <string name="feedback_more_like_this">更多類似</string>
```

- [ ] **Step 5：提交**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-zh/strings.xml app/src/main/res/values-zh-rCN/strings.xml app/src/main/res/values-zh-rTW/strings.xml
git commit -m "feat(chat): add feedback button strings in all supported locales"
```

---

## Task 14：DAO 数据库测试

**Files:**
- Create: `app/src/test/java/com/mamba/picme/data/local/dao/MediaFeedbackDaoTest.kt`

- [ ] **Step 1：编写测试**

项目已配置 Robolectric，使用 JVM 单元测试验证 Room DAO：

```kotlin
package com.mamba.picme.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.local.entity.MediaFeedbackEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MediaFeedbackDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: MediaFeedbackDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.mediaFeedbackDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `getFeedbackScoresForQuery aggregates likes and dislikes`() = runTest {
        dao.insert(MediaFeedbackEntity(mediaId = "1", feedbackType = "like", queryText = "海边", sessionId = "s1", createdAt = 1))
        dao.insert(MediaFeedbackEntity(mediaId = "1", feedbackType = "like", queryText = "海边", sessionId = "s1", createdAt = 2))
        dao.insert(MediaFeedbackEntity(mediaId = "1", feedbackType = "dislike", queryText = "海边", sessionId = "s1", createdAt = 3))
        dao.insert(MediaFeedbackEntity(mediaId = "2", feedbackType = "like", queryText = "海边", sessionId = "s1", createdAt = 4))
        dao.insert(MediaFeedbackEntity(mediaId = "1", feedbackType = "like", queryText = "山", sessionId = "s1", createdAt = 5))

        val scores = dao.getFeedbackScoresForQuery("海边")

        assertEquals(2, scores.size)
        val score1 = scores.first { it.mediaId == "1" }
        assertEquals(2, score1.likeCount)
        assertEquals(1, score1.dislikeCount)
    }
}
```

- [ ] **Step 2：运行测试**

```bash
./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.data.local.dao.MediaFeedbackDaoTest"
```

Expected: BUILD SUCCESSFUL, tests passed

- [ ] **Step 3：提交**

```bash
git add app/src/test/java/com/mamba/picme/data/local/dao/MediaFeedbackDaoTest.kt
git commit -m "test(chat): add MediaFeedbackDao tests"
```

---

## Task 15：全量编译与单元测试

**Files:**
- All modified files

- [ ] **Step 1：全量编译**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2：运行新增单元测试**

```bash
./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.search.*" --tests "com.mamba.picme.data.local.dao.MediaFeedbackDaoTest"
```

Expected: BUILD SUCCESSFUL, tests passed

- [ ] **Step 3：提交**

```bash
git commit -m "test(chat): full compile and feedback-related unit tests pass" --allow-empty
```

---

## Task 16：可选 — 添加 Compose UI 测试

**Files:**
- Create: `app/src/androidTest/java/com/mamba/picme/features/chat/components/MediaResultsCarouselFeedbackTest.kt`

- [ ] **Step 1：编写 Compose UI 测试**

```kotlin
package com.mamba.picme.features.chat.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.domain.search.FeedbackAction
import com.mamba.picme.features.chat.MediaResultsUi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MediaResultsCarouselFeedbackTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `clicking like triggers onFeedback callback`() {
        var capturedAction: FeedbackAction? = null

        composeTestRule.setContent {
            MediaResultsCarousel(
                mediaResults = MediaResultsUi(
                    query = "海边",
                    assets = listOf(
                        MediaAsset(
                            id = 1,
                            uri = "",
                            type = MediaType.PHOTO,
                            captureDate = System.currentTimeMillis(),
                            fileName = "test.jpg",
                            duration = 0,
                            hasFace = false,
                            faceId = null,
                            source = "",
                            labels = null,
                            ocrText = null,
                            latitude = null,
                            longitude = null,
                            locationName = null,
                            indexedAt = 0
                        )
                    ),
                    totalCount = 1,
                    isRefinement = false
                ),
                onCardClick = {},
                onFeedback = { _, action -> capturedAction = action }
            )
        }

        composeTestRule.onNodeWithContentDescription("喜欢").performClick()

        assertEquals(FeedbackAction.LIKE, capturedAction)
    }
}
```

- [ ] **Step 2：运行 instrumentation 测试**

```bash
./gradlew :app:connectedDebugAndroidTest --tests "com.mamba.picme.features.chat.components.MediaResultsCarouselFeedbackTest"
```

Expected: 需要连接设备或模拟器，测试通过

- [ ] **Step 3：提交**

```bash
git add app/src/androidTest/java/com/mamba/picme/features/chat/components/MediaResultsCarouselFeedbackTest.kt
git commit -m "test(chat): add MediaResultsCarousel feedback UI test"
```

---

## 自我审查清单

- [ ] Spec coverage：每个设计章节都有对应任务
- [ ] Placeholder scan：无 TBD/TODO/"实现 later"
- [ ] Type consistency：`FeedbackAction` 在 UI/Domain/Data 层命名一致；`MediaFeedbackRepository` 接口与实现签名一致
- [ ] 编译路径：MediaSearchEngine 的 `mergeAndRankWithScores` 已改为 `suspend` 以支持 feedback 查询
- [ ] I18N：四语字符串齐全
- [ ] Privacy：反馈仅本地存储
