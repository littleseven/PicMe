# 相册去重 2.0（Android V1）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 `docs/superpowers/specs/2026-08-25-album-dedup-design.md` 在 Android 端落地去重 2.0：三级尺度可选、四种保留规则 + 组内改选、Flow 流式渐进扫描（可暂停/取消）、回收站删除与撤销。

**Architecture:** 新增自包含包 `domain/dedup/`（纯 Kotlin 模型与保留规则引擎 + Android 扫描器/回收站管理器），Room 新增 `dedup_hash` 表缓存 MD5/pHash 实现增量扫描；独立 `DedupViewModel`（状态机 Config→Scanning→Results→Cleaned）挂在 AppContainer；新导航路由 `dedup_home` 承载全部 6 屏 UI；完成后替换旧 `DuplicateManager` 并下线 `MediaViewModel` 中的旧去重代码。

**Tech Stack:** Kotlin / Jetpack Compose (Material3) / Room(KSP) / Coil / coroutines Flow / JUnit4 + kotlinx-coroutines-test + mockk。

**V1 明确不做（记入 spec 后续版本）：** 扫描结果组持久化（进程重建后重扫，但哈希缓存在 Room，增量重扫很快）、前台 Service 转后台通知（V1 转后台=VM 存活继续跑）、视频去重、iOS 端。

**关键现状事实（执行者必须知道）：**
- 纯算法：`core/common/PerceptualHash.kt`（`md5Hex(InputStream)` / `phash(DoubleArray, size=32): Long` / `hammingDistance(Long,Long): Int` / `clusterByHamming(List<Long>, threshold=5)`），`SIMILAR_HAMMING_THRESHOLD=5` 为 const。
- 检测器：`core/common/DuplicateImageDetector.kt`（object，`DedupItem(uri,sizeBytes,mime,captureDate,aestheticScore)`，`findDuplicates(context, items)` 阻塞式）。
- 旧 UI：`features/gallery/components/DuplicateManager.kt` + `GalleryTopBar.kt:121-138`；旧 VM 方法在 `features/gallery/MediaViewModel.kt:62-63,265-266,364-424`。
- Room：`data/local/AppDatabase.kt` version=20，migration 手写 `Migration(n,n+1)` 风格（L419-445 有最近范例）；DAO 在 `data/local/`；KSP/room 依赖已配好。
- DI：`di/AppContainer.kt`，`MediaViewModelFactory`(L109-129) + `createMediaViewModelFactory()`(L701) 为仿照点；`database` lazy 在 L220。
- 导航：`navigation/Screen.kt:47`（`data object DuplicateManager : Screen("duplicate_manager")`）；`MainActivity.kt:350-355` 注册、L335/L405/L476 入口。
- 删除授权流：`MediaViewModel.deleteMediaByIds`→`_deleteAuthRequest`(Api29 IntentSender / Api30 pendingUris) 模式；回收站在 Android 11+ 用 `MediaStore.createTrashRequest`，同样返回需用户确认的 PendingIntent。
- 缩略图：Coil `AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(uri).size(360).crossfade(false).build(), contentScale = ContentScale.Crop)`。
- 三语 strings：`values/strings.xml:349-362`、`values-zh-rCN/strings.xml:334-346`、`values-zh-rTW/strings.xml:334-346` 有旧 duplicate 键块（`<!-- Duplicate Management -->`）。
- 测试范式：`androidApp/src/test/java/com/mamba/picme/core/common/PerceptualHashTest.kt`，JUnit4 + 反引号命名 + 确定性夹具。

---

### Task 1: Dedup 领域模型 + 保留规则引擎（纯 Kotlin，TDD）

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/domain/dedup/DedupModels.kt`
- Create: `androidApp/src/main/java/com/mamba/picme/domain/dedup/KeepPolicyEngine.kt`
- Test: `androidApp/src/test/java/com/mamba/picme/domain/dedup/KeepPolicyEngineTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.mamba.picme.domain.dedup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepPolicyEngineTest {

    private fun member(
        uri: String, sizeBytes: Long = 1_000_000, pixelArea: Int = 12_000_000,
        captureDate: Long = 1_000L, modifiedAt: Long = captureDate,
        aestheticScore: Float? = null,
    ) = DedupMember(
        uri = uri, sizeBytes = sizeBytes, mime = "image/jpeg",
        captureDate = captureDate, modifiedAt = modifiedAt,
        pixelArea = pixelArea, aestheticScore = aestheticScore,
        role = VersionRole.UNKNOWN, md5 = null, phash = null,
    )

    @Test
    fun `classify marks smallest copy as COMPRESSED`() {
        val big = member("a", sizeBytes = 4_000_000, pixelArea = 12_000_000)
        val small = member("b", sizeBytes = 900_000, pixelArea = 3_000_000)
        val out = KeepPolicyEngine.classify(listOf(big, small))
        assertEquals(VersionRole.ORIGINAL, out.first { it.uri == "a" }.role)
        assertEquals(VersionRole.COMPRESSED, out.first { it.uri == "b" }.role)
    }

    @Test
    fun `classify marks edited version when modified long after capture`() {
        val original = member("a")
        val edited = member("b", modifiedAt = 1_000L + 48 * 3600_000L)
        val out = KeepPolicyEngine.classify(listOf(original, edited))
        assertEquals(VersionRole.EDITED, out.first { it.uri == "b" }.role)
    }

    @Test
    fun `BEST_QUALITY keeps largest pixelArea then size`() {
        val a = member("a", sizeBytes = 4_000_000, pixelArea = 12_000_000)
        val b = member("b", sizeBytes = 5_000_000, pixelArea = 8_000_000)
        val sorted = KeepPolicyEngine.recommend(KeepPolicy.BEST_QUALITY, KeepPolicyEngine.classify(listOf(a, b)))
        assertEquals("a", sorted.first().uri)
    }

    @Test
    fun `ORIGINAL policy keeps original over edited and compressed`() {
        val original = member("a")
        val edited = member("b", modifiedAt = 1_000L + 48 * 3600_000L)
        val compressed = member("c", sizeBytes = 500_000, pixelArea = 2_000_000)
        val sorted = KeepPolicyEngine.recommend(KeepPolicy.ORIGINAL, KeepPolicyEngine.classify(listOf(edited, compressed, original)))
        assertEquals("a", sorted.first().uri)
    }

    @Test
    fun `EDITED policy prefers edited version`() {
        val original = member("a")
        val edited = member("b", modifiedAt = 1_000L + 48 * 3600_000L)
        val sorted = KeepPolicyEngine.recommend(KeepPolicy.EDITED, KeepPolicyEngine.classify(listOf(original, edited)))
        assertEquals("b", sorted.first().uri)
    }

    @Test
    fun `LATEST policy keeps most recently modified`() {
        val a = member("a", modifiedAt = 100L)
        val b = member("b", modifiedAt = 200L)
        val sorted = KeepPolicyEngine.recommend(KeepPolicy.LATEST, KeepPolicyEngine.classify(listOf(a, b)))
        assertEquals("b", sorted.first().uri)
    }

    @Test
    fun `recommend always keeps exactly one and never empties group`() {
        val members = listOf(member("a"), member("b"), member("c"))
        KeepPolicy.entries.forEach { policy ->
            val sorted = KeepPolicyEngine.recommend(policy, members)
            assertEquals(3, sorted.size)
            assertTrue(sorted.first().uri.isNotEmpty())
        }
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.domain.dedup.KeepPolicyEngineTest"`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 实现模型与引擎**

`DedupModels.kt`：

```kotlin
package com.mamba.picme.domain.dedup

enum class DedupLevel { EXACT, VISUAL, SCENE }

enum class VersionRole { ORIGINAL, EDITED, COMPRESSED, UNKNOWN }

enum class KeepPolicy { BEST_QUALITY, ORIGINAL, EDITED, LATEST }

data class DedupMember(
    val uri: String,
    val sizeBytes: Long,
    val mime: String,
    val captureDate: Long,
    val modifiedAt: Long,
    val pixelArea: Int,
    val aestheticScore: Float?,
    val role: VersionRole,
    val md5: String?,
    val phash: Long?,
)

data class DedupGroup(
    val id: String,
    val level: DedupLevel,
    val members: List<DedupMember>,
    val keepUri: String,
    val userOverride: Boolean = false,
) {
    val deleteUris: List<String> get() = members.map { it.uri }.filter { it != keepUri }
    val reclaimBytes: Long get() = members.filter { it.uri != keepUri }.sumOf { it.sizeBytes }

    companion object {
        fun stableId(level: DedupLevel, uris: List<String>): String =
            level.name.lowercase() + ":" + uris.sorted().joinToString("|").hashCode().toString(36)
    }
}

data class DedupScanConfig(
    val levels: Set<DedupLevel> = setOf(DedupLevel.EXACT, DedupLevel.VISUAL),
    val visualThreshold: Int = 5,
    val sceneThreshold: Int = 8,
    val sceneTimeWindowMs: Long = 10_000L,
)
```

`KeepPolicyEngine.kt`：

```kotlin
package com.mamba.picme.domain.dedup

object KeepPolicyEngine {

    private const val EDITED_GAP_MS = 6 * 3600_000L
    private const val COMPRESSED_RATIO = 0.5

    fun classify(members: List<DedupMember>): List<DedupMember> {
        val maxPixel = members.maxOf { it.pixelArea }.coerceAtLeast(1)
        val maxSize = members.maxOf { it.sizeBytes }.coerceAtLeast(1L)
        return members.map { m ->
            val role = when {
                m.pixelArea < maxPixel * COMPRESSED_RATIO || m.sizeBytes < maxSize * COMPRESSED_RATIO ->
                    VersionRole.COMPRESSED
                m.modifiedAt - m.captureDate > EDITED_GAP_MS -> VersionRole.EDITED
                else -> VersionRole.ORIGINAL
            }
            m.copy(role = role)
        }
    }

    fun recommend(policy: KeepPolicy, members: List<DedupMember>): List<DedupMember> {
        val roleRank = when (policy) {
            KeepPolicy.ORIGINAL -> mapOf(
                VersionRole.ORIGINAL to 0, VersionRole.EDITED to 1,
                VersionRole.UNKNOWN to 2, VersionRole.COMPRESSED to 3
            )
            KeepPolicy.EDITED -> mapOf(
                VersionRole.EDITED to 0, VersionRole.ORIGINAL to 1,
                VersionRole.UNKNOWN to 2, VersionRole.COMPRESSED to 3
            )
            else -> emptyMap()
        }
        val quality: Comparator<DedupMember> =
            compareByDescending<DedupMember> { it.pixelArea }
                .thenByDescending { it.sizeBytes }
                .thenByDescending { it.aestheticScore ?: -1f }
                .thenByDescending { it.captureDate }
        return when (policy) {
            KeepPolicy.BEST_QUALITY -> members.sortedWith(quality)
            KeepPolicy.LATEST -> members.sortedWith(compareByDescending { it.modifiedAt })
            else -> members.sortedWith(
                compareBy<DedupMember> { roleRank[it.role] ?: 2 }.then(quality)
            )
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.domain.dedup.KeepPolicyEngineTest"`
Expected: 7 tests PASS。

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/domain/dedup/ androidApp/src/test/java/com/mamba/picme/domain/dedup/
git commit -m "feat(dedup): add dedup 2.0 domain models and keep-policy engine"
```

---

### Task 2: 哈希缓存 Room 表（DedupHashEntity + Migration 20→21）

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/data/local/DedupHashEntity.kt`
- Create: `androidApp/src/main/java/com/mamba/picme/data/local/DedupHashDao.kt`
- Modify: `androidApp/src/main/java/com/mamba/picme/data/local/AppDatabase.kt`（entities + version 21 + dao + migration，仿 L419-445 风格）

- [ ] **Step 1: 新增 Entity 与 Dao**

`DedupHashEntity.kt`：

```kotlin
package com.mamba.picme.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "dedup_hash")
data class DedupHashEntity(
    @PrimaryKey val uri: String,
    val sizeBytes: Long,
    val mime: String,
    val modifiedAt: Long,
    val md5: String?,
    val phash: Long?,
    val pixelArea: Int,
    val hashedAt: Long,
)
```

`DedupHashDao.kt`：

```kotlin
package com.mamba.picme.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DedupHashDao {
    @Query("SELECT * FROM dedup_hash WHERE uri IN (:uris)")
    suspend fun getByUris(uris: List<String>): List<DedupHashEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<DedupHashEntity>)

    @Query("DELETE FROM dedup_hash WHERE uri NOT IN (SELECT uri FROM (SELECT uri FROM dedup_hash) LIMIT 0)")
    suspend fun noop()

    @Query("DELETE FROM dedup_hash WHERE uri = :uri")
    suspend fun deleteByUri(uri: String)
}
```

- [ ] **Step 2: AppDatabase 注册 + migration**

在 `AppDatabase.kt`：
- `@Database(entities = [..., DedupHashEntity::class], version = 21)`（entities 列表追加，version 20→21）
- 增加 `abstract fun dedupHashDao(): DedupHashDao`
- 增加 migration 并加入 `addMigrations(...)` 链尾：

```kotlin
private val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `dedup_hash` (
                `uri` TEXT NOT NULL PRIMARY KEY,
                `sizeBytes` INTEGER NOT NULL,
                `mime` TEXT NOT NULL,
                `modifiedAt` INTEGER NOT NULL,
                `md5` TEXT,
                `phash` INTEGER,
                `pixelArea` INTEGER NOT NULL,
                `hashedAt` INTEGER NOT NULL
            )"""
        )
    }
}
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew :androidApp:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（KSP 生成 DedupHashDao_Impl）。

- [ ] **Step 4: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/data/local/
git commit -m "feat(dedup): add dedup_hash cache table (db v21)"
```

---

### Task 3: DedupScanner 流式扫描器

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/domain/dedup/DedupScanEvent.kt`
- Create: `androidApp/src/main/java/com/mamba/picme/domain/dedup/DedupScanner.kt`
- Test: `androidApp/src/test/java/com/mamba/picme/domain/dedup/DedupClusteringTest.kt`

设计要点：
- `DedupScanner(context, hashDao)`，入口 `fun scan(items: List<DedupItem>, config: DedupScanConfig): Flow<DedupScanEvent>`。
- 批处理 500 张一批；每批结束发 `Progress(phase, scanned, total)`；检查 `currentCoroutineContext().isActive` 与 `pauseFlag` 实现取消/暂停（暂停：挂起直到 resume）。
- 缓存失效判断：`modifiedAt` 或 `sizeBytes` 变化则重算。
- 阶段顺序：EXACT（size+mime 分桶→MD5 成组）→ VISUAL（pHash ≤ config.visualThreshold 并查集，组内 ≥2 个不同 MD5）→ SCENE（pHash ≤ sceneThreshold 聚类后按 captureDate 10s 窗口再切分）。每组完成即发 `GroupFound(group)`。
- 复用 `PerceptualHash`（`md5Hex`/`phash`/`clusterByHamming`）；缩略解码仿 `DuplicateImageDetector.phashFromUri`（32×32 灰度）。
- 组 id 用 `DedupGroup.stableId`；成员经 `KeepPolicyEngine.classify` + `recommend(BEST_QUALITY)` 排序，`keepUri` = 首张。

`DedupScanEvent.kt`：

```kotlin
package com.mamba.picme.domain.dedup

sealed interface DedupScanEvent {
    data class Progress(val phase: DedupLevel, val scanned: Int, val total: Int) : DedupScanEvent
    data class GroupFound(val group: DedupGroup) : DedupScanEvent
    data class PhaseChanged(val phase: DedupLevel, val phaseIndex: Int, val phaseCount: Int) : DedupScanEvent
    data class Done(val groups: List<DedupGroup>) : DedupScanEvent
    data object Cancelled : DedupScanEvent
}
```

- [ ] **Step 1: 写聚类纯逻辑测试（SCENE 时间窗切分 + 阈值参数化）**

把「pHash 聚类 → DedupGroup 列表」提成纯函数 `internal fun clusterVisual(hashed: List<DedupMember>, threshold: Int, timeWindowMs: Long?, level: DedupLevel): List<DedupGroup>` 放在 `DedupScanner.kt` 顶层（internal，便于 JVM 单测）：

```kotlin
package com.mamba.picme.domain.dedup

import org.junit.Assert.assertEquals
import org.junit.Test

class DedupClusteringTest {

    private fun hashed(uri: String, phash: Long, captureDate: Long = 0L) = DedupMember(
        uri = uri, sizeBytes = 1_000_000, mime = "image/jpeg",
        captureDate = captureDate, modifiedAt = captureDate,
        pixelArea = 12_000_000, aestheticScore = null,
        role = VersionRole.UNKNOWN, md5 = null, phash = phash,
    )

    @Test
    fun `visual clustering groups hashes within threshold`() {
        val items = listOf(
            hashed("a", 0b1111L), hashed("b", 0b1110L), // distance 1
            hashed("c", 0L), hashed("d", 0L),           // identical pair
        )
        val groups = clusterVisual(items, threshold = 5, timeWindowMs = null, level = DedupLevel.VISUAL)
        assertEquals(2, groups.size)
        assertTrue(groups.all { it.level == DedupLevel.VISUAL })
    }

    @Test
    fun `scene clustering splits by capture time window`() {
        val items = listOf(
            hashed("a", 0L, captureDate = 0L),
            hashed("b", 0L, captureDate = 5_000L),      // within 10s of a
            hashed("c", 0L, captureDate = 3_600_000L),  // 1h later
            hashed("d", 0L, captureDate = 3_605_000L),  // within 10s of c
        )
        val groups = clusterVisual(items, threshold = 8, timeWindowMs = 10_000L, level = DedupLevel.SCENE)
        assertEquals(2, groups.size)
        assertEquals(setOf("a", "b"), groups[0].members.map { it.uri }.toSet())
        assertEquals(setOf("c", "d"), groups[1].members.map { it.uri }.toSet())
    }

    @Test
    fun `group id is stable regardless of member order`() {
        val g1 = DedupGroup.stableId(DedupLevel.EXACT, listOf("u1", "u2", "u3"))
        val g2 = DedupGroup.stableId(DedupLevel.EXACT, listOf("u3", "u1", "u2"))
        assertEquals(g1, g2)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.domain.dedup.DedupClusteringTest"`
Expected: 编译失败（`clusterVisual` 不存在）。

- [ ] **Step 3: 实现 DedupScanner（含 clusterVisual）**

```kotlin
package com.mamba.picme.domain.dedup

import android.content.Context
import android.graphics.BitmapFactory
import com.mamba.picme.core.common.DuplicateImageDetector.DedupItem
import com.mamba.picme.core.common.PerceptualHash
import com.mamba.picme.data.local.DedupHashDao
import com.mamba.picme.data.local.DedupHashEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

internal fun clusterVisual(
    hashed: List<DedupMember>,
    threshold: Int,
    timeWindowMs: Long?,
    level: DedupLevel,
): List<DedupGroup> {
    val clusters = PerceptualHash.clusterByHamming(hashed.map { it.phash ?: 0L }, threshold)
    val groups = mutableListOf<DedupGroup>()
    for (cluster in clusters) {
        var members = cluster.map { hashed[it] }
        if (timeWindowMs != null) {
            // 场景相似：按拍摄时间窗口再切分
            members = members.sortedBy { it.captureDate }
            var bucket = mutableListOf<DedupMember>()
            val buckets = mutableListOf<List<DedupMember>>()
            for (m in members) {
                if (bucket.isNotEmpty() && m.captureDate - bucket.last().captureDate > timeWindowMs) {
                    buckets += bucket; bucket = mutableListOf()
                }
                bucket += m
            }
            if (bucket.isNotEmpty()) buckets += bucket
            buckets.filter { it.size >= 2 }.forEach { bucketMembers ->
                groups += buildGroup(level, bucketMembers)
            }
        } else {
            groups += buildGroup(level, members)
        }
    }
    return groups
}

private fun buildGroup(level: DedupLevel, members: List<DedupMember>): DedupGroup {
    val classified = KeepPolicyEngine.classify(members)
    val sorted = KeepPolicyEngine.recommend(KeepPolicy.BEST_QUALITY, classified)
    return DedupGroup(
        id = DedupGroup.stableId(level, sorted.map { it.uri }),
        level = level, members = sorted, keepUri = sorted.first().uri,
    )
}

class DedupScanner(
    private val context: Context,
    private val hashDao: DedupHashDao,
) {
    @Volatile var pauseRequested = false
    private var resumeSignal: (() -> Unit)? = null

    fun resume() { pauseRequested = false; resumeSignal?.invoke(); resumeSignal = null }

    fun scan(items: List<DedupItem>, config: DedupScanConfig): Flow<DedupScanEvent> = flow {
        // 详见步骤内实现说明：分批 hash → EXACT 阶段 → VISUAL 阶段 → SCENE 阶段
        TODO("implement per plan steps below")
    }
}
```

`scan` 的完整实现步骤（在同一个 Step 3 内完成，不另拆任务）：

1. `val phases = config.levels.sorted()`；`emit(PhaseChanged(phase, index, phases.size))`。
2. 分批（500）：每批先 `hashDao.getByUris(uris)` 命中且 `modifiedAt/sizeBytes` 未变则复用，否则计算：
   - EXACT 阶段算 MD5（`context.contentResolver.openInputStream(Uri.parse(uri))` + `PerceptualHash.md5Hex`）
   - VISUAL/SCENE 阶段算 pHash（decode 32×32 灰度 + `PerceptualHash.phash`，同时拿 `outWidth*outHeight` 作 pixelArea——`BitmapFactory.Options.inJustDecodeBounds` 先取尺寸，再 `inSampleSize` 解码小图）
   - `hashDao.upsertAll(...)` 写缓存
   - 每批结束 `emit(Progress(phase, scanned, items.size))`；`while (pauseRequested) kotlinx.coroutines.delay(200)`；`if (!currentCoroutineContext().isActive) { emit(Cancelled); return@flow }`
3. EXACT 阶段：`members.groupBy { it.sizeBytes to it.mime }.values.filter { it.size >= 2 }`，桶内按 md5 再分组成 `DedupGroup(EXACT)`，逐组 `emit(GroupFound(g))`。
4. VISUAL 阶段：`clusterVisual(members, config.visualThreshold, null, DedupLevel.VISUAL)`，过滤掉组内全是同一 MD5 的（与 EXACT 重复），逐组 emit。
5. SCENE 阶段（若启用）：`clusterVisual(members, config.sceneThreshold, config.sceneTimeWindowMs, DedupLevel.SCENE)`，逐组 emit。
6. 结束 `emit(Done(allGroups))`。
7. 整个 flow 在调用方 `flowOn(Dispatchers.IO)`。

- [ ] **Step 4: 跑测试 + 编译**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.domain.dedup.*" && ./gradlew :androidApp:compileDebugKotlin`
Expected: 全部 PASS + BUILD SUCCESSFUL。

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/domain/dedup/ androidApp/src/test/java/com/mamba/picme/domain/dedup/
git commit -m "feat(dedup): streaming dedup scanner with hash cache and pause/cancel"
```

---

### Task 4: 回收站管理器（Trash/Undo）

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/domain/dedup/DedupTrashManager.kt`
- Test: `androidApp/src/test/java/com/mamba/picme/domain/dedup/DedupTrashManagerTest.kt`（Robolectric，验证 API 分支逻辑与 uri 解析）

```kotlin
package com.mamba.picme.domain.dedup

import android.content.Context
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

class DedupTrashManager(private val context: Context) {

    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun buildTrashIntent(uris: List<String>): IntentSender =
        MediaStore.createTrashRequest(
            context.contentResolver, uris.map { Uri.parse(it) }, true
        ).intentSender

    fun buildRestoreIntent(uris: List<String>): IntentSender =
        MediaStore.createTrashRequest(
            context.contentResolver, uris.map { Uri.parse(it) }, true.let { false }
        ).intentSender

    fun queryExisting(uris: List<String>): List<String> {
        val existing = mutableListOf<String>()
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        for (u in uris) {
            runCatching {
                context.contentResolver.query(Uri.parse(u), projection, null, null, null)?.use { c ->
                    if (c.moveToFirst()) existing += u
                }
            }
        }
        return existing
    }
}
```

（`buildRestoreIntent` 里 `true.let { false }` 是笔误，应为 `MediaStore.createTrashRequest(context.contentResolver, uris.map { Uri.parse(it) }, false)`——实现时写正确版本。）

Robolectric 测试只覆盖 `queryExisting` 空列表与 `isSupported` 分支，不 mock MediaStore 静态调用：

```kotlin
package com.mamba.picme.domain.dedup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DedupTrashManagerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `queryExisting returns empty for unknown uris`() {
        val mgr = DedupTrashManager(context)
        assertTrue(mgr.queryExisting(listOf("content://media/external/images/media/999999")).isEmpty())
    }
}
```

- [ ] **Step 1: 写测试**（如上）
- [ ] **Step 2: 跑测试确认失败** `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.domain.dedup.DedupTrashManagerTest"` → 编译失败
- [ ] **Step 3: 实现 DedupTrashManager**（如上，修正 restore 的 `false` 参数）
- [ ] **Step 4: 跑测试确认通过**（同上命令，PASS）
- [ ] **Step 5: Commit** `git commit -m "feat(dedup): trash manager with restore support (API 30+)"`

---

### Task 5: DedupViewModel 状态机

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/features/gallery/dedup/DedupViewModel.kt`
- Test: `androidApp/src/test/java/com/mamba/picme/features/gallery/dedup/DedupViewModelTest.kt`

```kotlin
package com.mamba.picme.features.gallery.dedup

import androidx.lifecycle.ViewModel
import com.mamba.picme.domain.dedup.*

sealed interface DedupUiState {
    data class Config(val config: DedupScanConfig = DedupScanConfig()) : DedupUiState
    data class Scanning(
        val phase: DedupLevel, val phaseIndex: Int, val phaseCount: Int,
        val scanned: Int, val total: Int, val paused: Boolean,
        val foundGroups: List<DedupGroup>,
    ) : DedupUiState
    data class Results(
        val groups: List<DedupGroup>, val selectedTab: DedupLevel,
        val policy: KeepPolicy,
    ) : DedupUiState
    data class Cleaned(
        val deletedCount: Int, val reclaimedBytes: Long, val trashedUris: List<String>,
    ) : DedupUiState
}
```

职责（在 VM 内实现，供 UI 调用）：
- `startScan(config)`：从 `MediaRepository.allMedia` 取 PHOTO 列表组装 DedupItem（仿 `FindDuplicateMediaUseCase` 的 `toDedupItem`），`scanner.scan(...).flowOn(Dispatchers.IO).collect { event -> reduce }`；GroupFound 即时插入 foundGroups。
- `pauseScan()/resumeScan()/cancelScan()`：操作 `scanner.pauseRequested` 与 job cancel。
- `setKeep(groupId, uri)`：组内改选（`userOverride=true`）。
- `applyPolicy(policy)`：对 Results 中 `!userOverride` 的组用 `KeepPolicyEngine.recommend` 重算 keepUri。
- `smartSelectAll()`：仅对 EXACT/VISUAL 组恢复默认推荐（SCENE 组不碰）。
- `deleteSelected()`：聚合 Results 各组 deleteUris → API30+ 走 `trashManager.buildTrashIntent` → `_pendingTrash: MutableStateFlow<PendingTrash?>`，UI 用 `rememberLauncherForActivityResult(StartIntentSenderForResult)` 发起；RESULT_OK 后 `trashManager.queryExisting` 校验仍存在的=用户拒绝了部分；只有确认消失的 uri 才从组里移除并进入 Cleaned。**授权失败绝不清列表**（修复现状 bug）。
- `undoTrash()`：对 `trashedUris` 调 `buildRestoreIntent`。
- API < 30：`deleteSelected` 回调到传入的 legacy delete lambda（复用 `MediaViewModel.deleteMediaByIds` 通道，由 Route 注入）。

单元测试（kotlinx-coroutines-test `runTest`，fake scanner 发事件流）：
- `GroupFound events appear in Scanning state progressively`
- `setKeep marks userOverride and changes deleteUris`
- `applyPolicy skips userOverride groups`
- `smartSelectAll does not touch SCENE groups`
- `pause sets paused=true and resume clears it`

- [ ] **Step 1: 写失败测试**（上述 5 条，fake `DedupScanner` 需把 scan 做成可注入——构造函数参数 `scannerFactory: () -> DedupScanner` 或把 scan 抽象为 interface `DedupScanSource`）
- [ ] **Step 2: 跑测试确认失败** `./gradlew :androidApp:testDebugUnitTest --tests "...DedupViewModelTest"` → 编译失败
- [ ] **Step 3: 实现 DedupViewModel**
- [ ] **Step 4: 跑测试确认通过**
- [ ] **Step 5: Commit** `git commit -m "feat(dedup): DedupViewModel state machine with progressive results"`

---

### Task 6: UI 组件（badges / 组卡片 / 缩略图标记）

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/features/gallery/dedup/DedupComponents.kt`

组件（Compose，Material3 主题色，对应 Ardot 稿）：
- `LevelBadge(level: DedupLevel)`：完全重复=绿色 tint、视觉重复=蓝色 tint、场景相似=橙色 tint。
- `RoleBadge(role: VersionRole)`：原图/已编辑/已压缩 半透明黑底白字小标。
- `DedupThumb(uri, isKept, role, modifier)`：Coil AsyncImage（`size(360).crossfade(false)`）+ 保留绿框 +「保留」角标 + 删除「×」角标 + 底部 RoleBadge。
- `DedupGroupCard(group, onOpenDetail)`：header（LevelBadge + "N 张 · 可省 X MB" + 已手动选择标）+ 缩略图行（≤3 张）+ footer（规则说明文案）。

无单测；验证靠编译 + 后续截图闭环。

- [ ] **Step 1: 实现组件**
- [ ] **Step 2: 编译** `./gradlew :androidApp:compileDebugKotlin` → SUCCESS
- [ ] **Step 3: Commit** `git commit -m "feat(dedup): dedup UI components (badges, group card)"`

---

### Task 7: 主屏 4 态（Config / Scanning / Results / Cleaned）

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/features/gallery/dedup/DedupHomeScreen.kt`

按 Ardot `dedup/overview`、`dedup/scanning`、`dedup/results`、`dedup/cleaned` 四屏实现为一个 Route 内的状态切换：
- `DedupHomeRoute(viewModel, onNavigateBack, onOpenGroupDetail)`：`collectAsState` DedupUiState + `_pendingTrash` 的 IntentSender 发起（`StartIntentSenderForResult`）。
- Config：hero 卡（预计可释放=上次结果聚合，无则显示相册规模）、三级尺度 checkbox、保留规则行、开始扫描按钮、隐私说明。
- Scanning：阶段 chip + 百分比 + 进度条 + 三段阶段条 + 实时发现列表（LazyColumn，GroupFound 即插入）+ 暂停/继续 + 取消 + 转后台（直接 `onNavigateBack()`，VM 存活继续扫）。
- Results：summary + 智能全选 chip + 三级 Tab + 组卡片 LazyColumn（`key = { it.id }` 稳定 key）+ 底部删除 CTA（红）+ 回收站说明 caption。
- Cleaned：成功图标 + 统计 + 回收站卡（前 3 张缩略 + 全部撤销）+ 完成/查看回收站。
- 所有文案走 `stringResource`，key 见 Task 9。

- [ ] **Step 1: 实现 DedupHomeScreen（可先硬编码字符串占位，Task 9 统一替换为 stringResource——不，直接写 stringResource，key 先定好）**
- [ ] **Step 2: 编译** → SUCCESS
- [ ] **Step 3: Commit** `git commit -m "feat(dedup): dedup home screen with 4-state flow"`

---

### Task 8: 组详情 + 保留规则弹层

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/features/gallery/dedup/DedupGroupDetailScreen.kt`

- `DedupGroupDetailSheet(group, policy, onSetKeep, onApply)`：ModalBottomSheet 或全屏 Dialog（组数据在 VM 里，用 navigation 参数传 groupId，VM 提供 `getGroup(id)`）。成员两列对比：DedupThumb + 分辨率/大小/时间 + 单选「保留这张」radio；含编辑版本时显示提示条；底部「保留所选 · 删除其余 N 张」。
- `KeepRulesSheet(current, onSelect)`：ModalBottomSheet，四个 radio 行 + 说明 + 应用。

- [ ] **Step 1: 实现**
- [ ] **Step 2: 编译** → SUCCESS
- [ ] **Step 3: Commit** `git commit -m "feat(dedup): group detail and keep-rules sheet"`

---

### Task 9: 三语文案

**Files:**
- Modify: `androidApp/src/main/res/values/strings.xml`、`values-zh-rCN/strings.xml`、`values-zh-rTW/strings.xml`（新块 `<!-- Dedup 2.0 -->`，紧跟旧 Duplicate Management 块后）

keys（en / zh-CN / zh-TW 三语同步，示例）：
`dedup_title`(Duplicate cleanup/重复照片清理/重複照片清理）、`dedup_estimate_label`、`dedup_last_scan`、`dedup_scale_section`、`dedup_scale_exact`(+_desc)、`dedup_scale_visual`(+_desc)、`dedup_scale_scene`(+_desc)、`dedup_keep_rule`、`dedup_policy_quality/original/edited/latest`(+_desc)、`dedup_start_scan`、`dedup_privacy_caption`、`dedup_scanning_title`、`dedup_phase_format`(%1$d/%2$d)、`dedup_scan_progress`(%1$d/%2$d)、`dedup_live_found`(%1$d)、`dedup_pause/resume/cancel/run_background`、`dedup_results_summary`(%1$d 组 %2$s)、`dedup_smart_select_all`、`dedup_tab_exact/visual/scene`、`dedup_group_meta`(%1$d 张 · 可省 %2$s)、`dedup_keep_badge`、`dedup_role_original/edited/compressed`、`dedup_manual_mark`、`dedup_delete_cta`（删除 %1$d 张 · 释放 %2$s)、`dedup_recycle_caption`、`dedup_detail_title`、`dedup_keep_this`、`dedup_hint_edited`、`dedup_confirm_keep`(%1$d)、`dedup_rules_title`、`dedup_rules_note`、`dedup_apply`、`dedup_cleaned_title`(%1$d)、`dedup_cleaned_sub`(%1$s)、`dedup_recycle_bin`、`dedup_auto_clear`、`dedup_undo_all`、`done`（已有）、`dedup_view_recycle`。

- [ ] **Step 1: 三语 keys 全部补齐**
- [ ] **Step 2: 编译 + i18n 检查** `./gradlew :androidApp:compileDebugKotlin`；确认三个 values 目录键数一致
- [ ] **Step 3: Commit** `git commit -m "feat(dedup): trilingual dedup 2.0 strings"`

---

### Task 10: DI + 导航 + 入口切换

**Files:**
- Modify: `di/AppContainer.kt`（`dedupHashDao` lazy、`DedupScanner`、`DedupTrashManager`、`DedupViewModelFactory` + `createDedupViewModelFactory()`，仿 L605/L614/L701）
- Modify: `navigation/Screen.kt`（`data object DedupHome : Screen("dedup_home")`）
- Modify: `MainActivity.kt`（注册 `composable(Screen.DedupHome.route)`；L335/L405/L476 三处入口改导航到 DedupHome；旧 `DuplicateManager.route` 注册保留到 Task 11 删除）

- [ ] **Step 1: 接线**
- [ ] **Step 2: 编译** → SUCCESS
- [ ] **Step 3: 真机冒烟**（`adb install` + 进入设置→相册→管理重复照片，走一遍扫描→结果→删除→撤销）
- [ ] **Step 4: Commit** `git commit -m "feat(dedup): wire dedup 2.0 into DI and navigation"`

---

### Task 11: 下线旧实现 + 文档同步

**Files:**
- Delete: `features/gallery/components/DuplicateManager.kt`、`domain/usecase/FindDuplicateMediaUseCase.kt`
- Modify: `features/gallery/MediaViewModel.kt`（删除 L62-63、265-266、364-424 去重段及 `findDuplicateMediaUseCase` 构造参数）、`di/AppContainer.kt`（MediaViewModelDependencies 去掉 findDuplicateMediaUseCase）、`GalleryTopBar.kt`（删 `DuplicateManagerTopBar`）、`navigation/Screen.kt`（删 DuplicateManager）、`MainActivity.kt`（删旧 composable 注册）
- Modify: `androidApp/src/main/java/com/mamba/picme/features/gallery/AGENTS.md` §2.4 重写为去重 2.0 实现事实；`docs/01-PRODUCT/FEATURES.md:284` 更新描述

- [ ] **Step 1: 删除与清理（保留 PerceptualHash/DuplicateImageDetector——DedupScanner 复用前者；后者若无其他调用方也一并删）**
- [ ] **Step 2: 全量测试 + 编译** `./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug` → PASS + SUCCESS
- [ ] **Step 3: 文档同步**
- [ ] **Step 4: Commit** `git commit -m "refactor(dedup): retire legacy duplicate manager in favor of dedup 2.0"`

---

### Task 12: 闭环验证

- [ ] **Step 1:** `./gradlew :androidApp:testDebugUnitTest` 全绿
- [ ] **Step 2:** `./gradlew :androidApp:assembleDebug` 成功
- [ ] **Step 3:** `scripts/auto-dev-loop.sh` 或手动：安装 → 设置页进入 → 扫描（观察渐进上屏/暂停/取消）→ 结果三 Tab → 组详情改选 → 规则切换 → 删除（系统回收站确认弹窗）→ 完成页撤销 → 截图 6 张与 Ardot 稿对比
- [ ] **Step 4:** 9,000 张级相册二次扫描验证增量缓存（首次慢、二次快）

---

## Self-Review 记录

- **Spec 覆盖**：§3 三级尺度→Task 1/3/7；§4 保留规则→Task 1/5/8；§5 渐进扫描→Task 3/5/7（持久化结果组降级为哈希缓存，已声明）；§6 回收站→Task 4/5；§7 UI→Task 6-9；§8 技术要点→Task 1-11（稳定 key/组 id/授权失败 bug 均覆盖；预览文件名 bug 随旧 UI 删除消除）。
- **已知取舍**：DedupTrashManager 示例代码中 restore 参数笔误已在 Task 4 正文标注修正；`DedupHashDao.noop()` 是占位，实现时可删。
- **类型一致性**：`DedupGroup/DedupMember/KeepPolicy/DedupUiState` 命名在 Task 1/3/5/6/7/8 间已对齐。
