# Chat 编辑图片隔离与 LRU 缓存 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** chat 页编辑/优化结果图不再直写 MediaStore，未经确认对相册不可见；预览页「保存到相册」后重指向 `content://` URI 并释放私有文件；中间结果按总磁盘容量 LRU 回收，被清理气泡显示「图片已过期·不可见」占位。

**Architecture:** 新增 `ChatImageStore` 仓储（接口 domain/repository，实现在 data/repository）拥有专用缓存目录 `filesDir/chat_edit_cache/` + Room 表 `chat_image_cache`，承担写盘/LRU/保存复制/重指向协调；两条渲染链路（`ChatEditProcessor`、`ChatImageRenderer`）改走 store 落盘；`SaveChatEditResultUseCase` 编排「复制→重指向消息 metadata→释放文件」；UI 用 scheme 感知的 `File.exists()` 做懒过期检测。

**Tech Stack:** Kotlin、Room (v14→v15 迁移)、Compose、Coil、Coroutines、MockK + runTest（JVM 单测）。

**Spec:** `docs/superpowers/specs/2026-07-27-chat-edit-image-isolation-design.md`

---

## 文件结构

### 新增
- `app/src/main/java/com/mamba/picme/data/local/entity/ChatImageCacheEntity.kt` — 缓存表实体（PK=filePath）。
- `app/src/main/java/com/mamba/picme/data/local/dao/ChatImageCacheDao.kt` — 缓存表 DAO。
- `app/src/main/java/com/mamba/picme/domain/repository/ChatImageStore.kt` — 仓储接口 + 状态常量 + 默认 cap。
- `app/src/main/java/com/mamba/picme/data/repository/ChatImageStoreImpl.kt` — 仓储实现（文件 + DAO + MediaStore 写）。
- `app/src/main/java/com/mamba/picme/domain/usecase/SaveChatEditResultUseCase.kt` — 保存编排。
- `app/src/main/java/com/mamba/picme/features/chat/ChatImageLive.kt` — 纯函数 `chatImageIsLive`（可单测）。
- 测试：`ChatImageStoreImplTest.kt`、`SaveChatEditResultUseCaseTest.kt`、`ChatImageLiveTest.kt`、`FakeChatImageCacheDao.kt`。

### 修改
- `app/src/main/java/com/mamba/picme/data/local/AppDatabase.kt` — 注册 entity/dao，v14→v15，`MIGRATION_14_15`。
- `app/src/main/java/com/mamba/picme/domain/usecase/ChatEditProcessor.kt` — `saveBitmapToMediaStore` → `store.writeResult`；签名增 `chatImageStore`、增 sessionId、删 `mediaRepository`/`outputCollectionUri`/`sdkInt`。
- `app/src/main/java/com/mamba/picme/features/chat/ChatImageRenderer.kt` — `saveBitmap` 走 store；公开方法增 sessionId。
- `app/src/main/java/com/mamba/picme/domain/agent/capability/ImageEditCapability.kt` — 透传 sessionId 给 processor。
- `app/src/main/java/com/mamba/picme/di/AppContainer.kt` — 构造 store + usecase，注入两个渲染器与 deps。
- `app/src/main/java/com/mamba/picme/features/chat/ChatViewModelDependencies.kt` — 增 `chatImageStore`、`saveChatEditResultUseCase`。
- `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt` — 持有 store/usecase；`saveEditResult`/`touchEditImage`；`deleteSession` 钩 `evictForSession`；插入消息 metadata 加 `saved=false`；`toUiModel` 解析 `saved`；预览态升级。
- `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt` — `PreviewImageState` + `ImagePreviewOverlay` 保存按钮；`ChatMessageItem` 过期占位；`chatImageIsLive`。
- `app/src/main/res/values/strings.xml`、`values-zh-rCN/`、`values-zh-rTW/` — 新增 4 条文案 + 无障碍描述。
- `app/src/test/java/com/mamba/picme/domain/usecase/ChatEditProcessorTest.kt` — 更新构造与断言。
- `app/src/test/java/com/mamba/picme/domain/agent/capability/ImageEditCapabilityTest.kt` — mock 签名增一参。

---

## Task 1: 缓存表 Entity + DAO + Room 迁移

**Files:**
- Create: `app/src/main/java/com/mamba/picme/data/local/entity/ChatImageCacheEntity.kt`
- Create: `app/src/main/java/com/mamba/picme/data/local/dao/ChatImageCacheDao.kt`
- Modify: `app/src/main/java/com/mamba/picme/data/local/AppDatabase.kt`

- [ ] **Step 1: 创建 Entity**

Create `app/src/main/java/com/mamba/picme/data/local/entity/ChatImageCacheEntity.kt`:

```kotlin
package com.mamba.picme.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** chat 编辑/优化结果图的私有缓存登记行。文件存于 filesDir/chat_edit_cache/。 */
@Entity(
    tableName = "chat_image_cache",
    indices = [Index("lastAccessedAt"), Index("sessionId")]
)
data class ChatImageCacheEntity(
    /** filesDir/chat_edit_cache/ 下绝对路径，唯一。 */
    @PrimaryKey val filePath: String,
    val sessionId: String,
    val createdAt: Long,
    val lastAccessedAt: Long,
    val sizeBytes: Long,
    /** ACTIVE | SAVED | EVICTED，见 [com.mamba.picme.domain.repository.ChatImageStore.Status]。 */
    val status: String
)
```

- [ ] **Step 2: 创建 DAO**

Create `app/src/main/java/com/mamba/picme/data/local/dao/ChatImageCacheDao.kt`:

```kotlin
package com.mamba.picme.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mamba.picme.data.local.entity.ChatImageCacheEntity

@Dao
interface ChatImageCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ChatImageCacheEntity)

    @Query("UPDATE chat_image_cache SET status = :status WHERE filePath = :filePath")
    suspend fun updateStatus(filePath: String, status: String)

    @Query("UPDATE chat_image_cache SET lastAccessedAt = :ts WHERE filePath = :filePath")
    suspend fun updateLastAccessed(filePath: String, ts: Long)

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM chat_image_cache WHERE status = 'ACTIVE'")
    suspend fun sumSizeWhereActive(): Long

    @Query("SELECT * FROM chat_image_cache WHERE status = 'ACTIVE' ORDER BY lastAccessedAt ASC LIMIT :limit")
    suspend fun oldestActive(limit: Int): List<ChatImageCacheEntity>

    @Query("SELECT * FROM chat_image_cache WHERE filePath = :filePath")
    suspend fun getByPath(filePath: String): ChatImageCacheEntity?

    @Query("SELECT * FROM chat_image_cache WHERE sessionId = :sessionId AND status = 'ACTIVE'")
    suspend fun getActiveBySession(sessionId: String): List<ChatImageCacheEntity>

    @Query("SELECT * FROM chat_image_cache")
    suspend fun allRows(): List<ChatImageCacheEntity>

    @Query("SELECT filePath FROM chat_image_cache")
    suspend fun allFilePaths(): List<String>

    @Query("DELETE FROM chat_image_cache WHERE filePath = :filePath")
    suspend fun deleteByPath(filePath: String)

    @Query("DELETE FROM chat_image_cache WHERE status IN ('SAVED','EVICTED')")
    suspend fun pruneTerminalRows()
}
```

- [ ] **Step 3: 注册到 AppDatabase（v15 + 迁移）**

Modify `app/src/main/java/com/mamba/picme/data/local/AppDatabase.kt`:

3a. 在 `entities = [...]` 末尾追加 `ChatImageCacheEntity::class`（注意在 `MemoryFactEntity::class` 后加逗号再加）。

3b. `version = 14` → `version = 15`。

3c. 新增 import：
```kotlin
import com.mamba.picme.data.local.dao.ChatImageCacheDao
import com.mamba.picme.data.local.entity.ChatImageCacheEntity
```

3d. 在 `abstract class AppDatabase` 内（`memoryFactDao()` 之后）加：
```kotlin
    abstract fun chatImageCacheDao(): ChatImageCacheDao
```

3e. 在 `addMigrations(...)` 列表末尾追加 `, MIGRATION_14_15`。

3f. 在 companion object 内（`MIGRATION_10_11` 之后）加：
```kotlin
        /**
         * Migration 14 → 15：新增 chat_image_cache 表，登记 chat 编辑/优化结果图的私有缓存行
         */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `chat_image_cache` (
                        `filePath` TEXT NOT NULL PRIMARY KEY,
                        `sessionId` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `lastAccessedAt` INTEGER NOT NULL,
                        `sizeBytes` INTEGER NOT NULL,
                        `status` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chat_image_cache_lastAccessedAt` ON `chat_image_cache` (`lastAccessedAt`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chat_image_cache_sessionId` ON `chat_image_cache` (`sessionId`)"
                )
            }
        }
```

- [ ] **Step 4: 编译验证（Room 在编译期校验 SQL 与迁移）**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。Room codegen 校验所有 @Query 语法 + 迁移与 entity schema 一致。若失败，按报错修正 SQL/字段名（注意 `index_<table>_<col>` 命名须与 `@Entity(indices=...)` 一致）。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/data/local/entity/ChatImageCacheEntity.kt \
        app/src/main/java/com/mamba/picme/data/local/dao/ChatImageCacheDao.kt \
        app/src/main/java/com/mamba/picme/data/local/AppDatabase.kt
git commit -m "feat(chat): 新增 chat_image_cache 表与 DAO (v14→v15)"
```

---

## Task 2: ChatImageStore 接口 + writeResult/enforceCap/touch 实现（TDD）

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/repository/ChatImageStore.kt`
- Create: `app/src/main/java/com/mamba/picme/data/repository/ChatImageStoreImpl.kt`
- Create: `app/src/test/java/com/mamba/picme/data/repository/FakeChatImageCacheDao.kt`
- Create: `app/src/test/java/com/mamba/picme/data/repository/ChatImageStoreImplTest.kt`

- [ ] **Step 1: 写接口（含状态常量与默认 cap）**

Create `app/src/main/java/com/mamba/picme/domain/repository/ChatImageStore.kt`:

```kotlin
package com.mamba.picme.domain.repository

import android.graphics.Bitmap

/**
 * chat 编辑/优化结果图的私有缓存仓储。
 *
 * 所有 chat 内生成的结果图经 [writeResult] 落盘到 filesDir/chat_edit_cache/，
 * 不直接写 MediaStore；用户在预览页主动保存后才复制进相册（[copyToGallery]），
 * 并把消息 imageUri 重指向 content:// URI、释放私有文件（[markSaved]）。
 * 未经保存的结果按总磁盘容量做 LRU 回收（[enforceCap]）。
 */
interface ChatImageStore {

    /** 渲染结果落盘到缓存目录、写 ACTIVE 行、触发 enforceCap，返回 file:// 路径。 */
    suspend fun writeResult(sessionId: String, bitmap: Bitmap, mimeType: String): String

    /** 复制私有文件到 Pictures/PoLang，返回新 content:// URI；不动文件、不动表。失败返回 null。 */
    suspend fun copyToGallery(filePath: String): String?

    /** 删私有文件 + 行置 SAVED（消息已重指向后调用）。 */
    suspend fun markSaved(filePath: String)

    /** 刷新 lastAccessedAt（打开预览 / 再编辑时调用）。 */
    suspend fun touch(filePath: String)

    /** 超 cap 则按 lastAccessedAt 最旧的 ACTIVE 逐个删文件 + 置 EVICTED。 */
    suspend fun enforceCap()

    /** 冷启对账：修缺文件行 / 孤儿文件 / 终态行，再 enforceCap。 */
    suspend fun reconcileColdStart()

    /** 删会话时调用，清理该会话的 ACTIVE 文件。 */
    suspend fun evictForSession(sessionId: String)

    object Status {
        const val ACTIVE = "ACTIVE"
        const val SAVED = "SAVED"
        const val EVICTED = "EVICTED"
    }

    companion object {
        const val DEFAULT_MAX_SIZE_BYTES: Long = 200L * 1024 * 1024
    }
}
```

- [ ] **Step 2: 写 FakeChatImageCacheDao（测试用，内存实现）**

Create `app/src/test/java/com/mamba/picme/data/repository/FakeChatImageCacheDao.kt`:

```kotlin
package com.mamba.picme.data.repository

import com.mamba.picme.data.local.dao.ChatImageCacheDao
import com.mamba.picme.data.local.entity.ChatImageCacheEntity

/** 内存版 DAO，供 ChatImageStoreImplTest 验证 LRU/对账逻辑（无需 Room/Robolectric）。 */
class FakeChatImageCacheDao : ChatImageCacheDao {
    private val rows = mutableMapOf<String, ChatImageCacheEntity>()

    override suspend fun upsert(row: ChatImageCacheEntity) { rows[row.filePath] = row }
    override suspend fun updateStatus(filePath: String, status: String) {
        rows[filePath]?.let { rows[filePath] = it.copy(status = status) }
    }
    override suspend fun updateLastAccessed(filePath: String, ts: Long) {
        rows[filePath]?.let { rows[filePath] = it.copy(lastAccessedAt = ts) }
    }
    override suspend fun sumSizeWhereActive(): Long =
        rows.values.filter { it.status == "ACTIVE" }.sumOf { it.sizeBytes }
    override suspend fun oldestActive(limit: Int): List<ChatImageCacheEntity> =
        rows.values.filter { it.status == "ACTIVE" }.sortedBy { it.lastAccessedAt }.take(limit)
    override suspend fun getByPath(filePath: String): ChatImageCacheEntity? = rows[filePath]
    override suspend fun getActiveBySession(sessionId: String): List<ChatImageCacheEntity> =
        rows.values.filter { it.sessionId == sessionId && it.status == "ACTIVE" }
    override suspend fun allRows(): List<ChatImageCacheEntity> = rows.values.toList()
    override suspend fun allFilePaths(): List<String> = rows.keys.toList()
    override suspend fun deleteByPath(filePath: String) { rows.remove(filePath) }
    override suspend fun pruneTerminalRows() {
        rows.values.removeAll { it.status in setOf("SAVED", "EVICTED") }
    }
}
```

- [ ] **Step 3: 写失败测试 — writeResult / enforceCap / touch**

Create `app/src/test/java/com/mamba/picme/data/repository/ChatImageStoreImplTest.kt`:

```kotlin
package com.mamba.picme.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.mamba.picme.domain.repository.ChatImageStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ChatImageStoreImplTest {

    private lateinit var cacheDir: File
    private lateinit var context: Context
    private lateinit var dao: FakeChatImageCacheDao
    private lateinit var collectionUri: Uri

    @Before
    fun setUp() {
        cacheDir = File(System.getProperty("java.io.tmpdir"), "chat_edit_cache_test_${System.nanoTime()}")
        cacheDir.mkdirs()
        context = mockk(relaxed = true)
        every { context.filesDir } returns File(System.getProperty("java.io.tmpdir"))
        dao = FakeChatImageCacheDao()
        // 显式传入 collectionUri，避免构造器默认值 MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        // 在无 Robolectric 的 JVM 单测里触发 "not mocked"
        collectionUri = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkAll()
        cacheDir.deleteRecursively()
    }

    private fun store(
        maxBytes: Long = ChatImageStore.DEFAULT_MAX_SIZE_BYTES,
        sdkInt: Int = 21 // 预 Q：copyToGallery 走 outputCollectionUri 分支，避开 MediaStore.getContentUri 静态调用
    ) = ChatImageStoreImpl(
        context = context,
        dao = dao,
        cacheDir = cacheDir,
        maxSizeBytes = maxBytes,
        outputCollectionUri = collectionUri,
        sdkInt = sdkInt
    )

    private fun bitmap(size: Int = 100): Bitmap {
        val bmp = mockk<Bitmap>(relaxed = true)
        every { bmp.width } returns size
        every { bmp.height } returns size
        // 让 compress 真正写字节，便于断言文件存在与大小
        every { bmp.compress(any(), any(), any()) } answers {
            val out = thirdArg<java.io.OutputStream>()
            out.write(ByteArray(50))
            true
        }
        return bmp
    }

    @Test
    fun `writeResult writes file and ACTIVE row`() = runTest {
        val path = store().writeResult("default", bitmap(), "image/jpeg")
        assertTrue(path.startsWith("file://"))
        val abs = path.removePrefix("file://")
        assertTrue(File(abs).exists())
        val row = dao.getByPath(abs)
        assertEquals("default", row?.sessionId)
        assertEquals(ChatImageStore.Status.ACTIVE, row?.status)
    }

    @Test
    fun `enforceCap evicts oldest ACTIVE by lastAccessedAt until under cap`() = runTest {
        // 预置三行 ACTIVE 文件，cap 设到只能容纳两行（每行 50 字节）
        val store = store(maxBytes = 90)
        seed(absPath("a"), lastAccessedAt = 100)
        seed(absPath("b"), lastAccessedAt = 200)
        seed(absPath("c"), lastAccessedAt = 300)
        store.enforceCap()
        // 最旧的 a 应被 EVICTED 且文件删除；b/c 仍在
        assertEquals(ChatImageStore.Status.EVICTED, dao.getByPath(absPath("a"))?.status)
        assertFalse(File(absPath("a")).exists())
        assertEquals(ChatImageStore.Status.ACTIVE, dao.getByPath(absPath("b"))?.status)
        assertTrue(File(absPath("b")).exists())
        assertTrue(dao.sumSizeWhereActive() <= 90)
    }

    @Test
    fun `touch bumps lastAccessedAt so row survives longer`() = runTest {
        val store = store(maxBytes = 90)
        seed(absPath("a"), lastAccessedAt = 100)
        seed(absPath("b"), lastAccessedAt = 200)
        seed(absPath("c"), lastAccessedAt = 300)
        store.touch(absPath("a")) // a 变最新
        store.enforceCap()
        // 现在 b 最旧，应被淘汰；a 保留
        assertEquals(ChatImageStore.Status.EVICTED, dao.getByPath(absPath("b"))?.status)
        assertEquals(ChatImageStore.Status.ACTIVE, dao.getByPath(absPath("a"))?.status)
    }

    @Test
    fun `enforceCap does not evict a single file larger than cap`() = runTest {
        val store = store(maxBytes = 10)
        seed(absPath("big"), lastAccessedAt = 100, size = 50)
        store.enforceCap()
        assertEquals(ChatImageStore.Status.ACTIVE, dao.getByPath(absPath("big"))?.status)
        assertTrue(File(absPath("big")).exists())
    }

    private fun absPath(name: String): String = File(cacheDir, "$name.jpg").apply { writeBytes(ByteArray(50)) }.absolutePath

    private suspend fun seed(path: String, lastAccessedAt: Long, size: Long = 50) {
        dao.upsert(
            com.mamba.picme.data.local.entity.ChatImageCacheEntity(
                filePath = path,
                sessionId = "default",
                createdAt = lastAccessedAt,
                lastAccessedAt = lastAccessedAt,
                sizeBytes = size,
                status = ChatImageStore.Status.ACTIVE
            )
        )
    }
}
```

- [ ] **Step 4: 运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.data.repository.ChatImageStoreImplTest"`
Expected: FAIL（`ChatImageStoreImpl` 未实现 / 编译错误）。

- [ ] **Step 5: 实现 writeResult / enforceCap / touch**

Create `app/src/main/java/com/mamba/picme/data/repository/ChatImageStoreImpl.kt`:

```kotlin
package com.mamba.picme.data.repository

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.local.dao.ChatImageCacheDao
import com.mamba.picme.data.local.entity.ChatImageCacheEntity
import com.mamba.picme.domain.repository.ChatImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private const val TAG = "PoLang:ChatImageStore"
private const val JPEG_QUALITY = 95
private const val GALLERY_RELATIVE_PATH = "Pictures/PoLang"

class ChatImageStoreImpl(
    private val context: Context,
    private val dao: ChatImageCacheDao,
    private val cacheDir: File = File(context.filesDir, "chat_edit_cache"),
    private val maxSizeBytes: Long = ChatImageStore.DEFAULT_MAX_SIZE_BYTES,
    private val outputCollectionUri: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
    private val sdkInt: Int = Build.VERSION.SDK_INT
) : ChatImageStore {

    init { if (!cacheDir.exists()) cacheDir.mkdirs() }

    override suspend fun writeResult(sessionId: String, bitmap: Bitmap, mimeType: String): String =
        withContext(Dispatchers.IO) {
            cacheDir.mkdirs()
            val ext = if (mimeType.contains("png")) "png" else "jpg"
            val file = File(cacheDir, "edit_${UUID.randomUUID()}.$ext")
            java.io.FileOutputStream(file).use { out ->
                val format = if (ext == "png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                bitmap.compress(format, JPEG_QUALITY, out)
            }
            val now = System.currentTimeMillis()
            dao.upsert(
                ChatImageCacheEntity(
                    filePath = file.absolutePath,
                    sessionId = sessionId,
                    createdAt = now,
                    lastAccessedAt = now,
                    sizeBytes = file.length(),
                    status = ChatImageStore.Status.ACTIVE
                )
            )
            enforceCap()
            "file://${file.absolutePath}"
        }

    override suspend fun copyToGallery(filePath: String): String? = withContext(Dispatchers.IO) {
        // 见 Task 3 实现
        null
    }

    override suspend fun markSaved(filePath: String) {
        // 见 Task 3 实现
    }

    override suspend fun touch(filePath: String) {
        dao.updateLastAccessed(filePath, System.currentTimeMillis())
    }

    override suspend fun enforceCap() {
        var guard = 0
        while (dao.sumSizeWhereActive() > maxSizeBytes && guard < 10000) {
            guard++
            val victims = dao.oldestActive(1)
            if (victims.isEmpty()) break
            val v = victims.first()
            // 单文件 >= cap 时不自删（避免删掉唯一/最新的大图），仅 log
            if (v.sizeBytes >= maxSizeBytes) {
                Logger.w(TAG, "Single file ${v.filePath} (${v.sizeBytes}B) >= cap $maxSizeBytes; skip eviction")
                break
            }
            runCatching { File(v.filePath).delete() }
            dao.updateStatus(v.filePath, ChatImageStore.Status.EVICTED)
            Logger.i(TAG, "LRU evicted ${v.filePath}")
        }
    }

    override suspend fun reconcileColdStart() {
        // 见 Task 4 实现
    }

    override suspend fun evictForSession(sessionId: String) {
        // 见 Task 4 实现
    }
}
```

- [ ] **Step 6: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.data.repository.ChatImageStoreImplTest"`
Expected: PASS（4 个测试）。

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/repository/ChatImageStore.kt \
        app/src/main/java/com/mamba/picme/data/repository/ChatImageStoreImpl.kt \
        app/src/test/java/com/mamba/picme/data/repository/FakeChatImageCacheDao.kt \
        app/src/test/java/com/mamba/picme/data/repository/ChatImageStoreImplTest.kt
git commit -m "feat(chat): ChatImageStore writeResult/enforceCap/touch + 单测"
```

---

## Task 3: ChatImageStore — copyToGallery / markSaved（TDD）

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/data/repository/ChatImageStoreImpl.kt`
- Modify: `app/src/test/java/com/mamba/picme/data/repository/ChatImageStoreImplTest.kt`

- [ ] **Step 1: 追加失败测试**

在 `ChatImageStoreImplTest.kt` 类内追加：

```kotlin
    @Test
    fun `copyToGallery writes to MediaStore and returns content uri`() = runTest {
        // ContentValues 是 Android 类，需 mockkConstructor（否则 "not mocked"）
        io.mockk.mockkConstructor(android.content.ContentValues::class)
        every { io.mockk.anyConstructed<android.content.ContentValues>().put(any<String>(), any<String>()) } just io.mockk.Runs

        val contentResolver = mockk<android.content.ContentResolver>(relaxed = true)
        every { context.contentResolver } returns contentResolver
        val itemUri = mockk<Uri>(relaxed = true)
        every { contentResolver.insert(any(), any()) } returns itemUri
        every { contentResolver.openOutputStream(any()) } returns java.io.ByteArrayOutputStream()
        every { itemUri.toString() } returns "content://media/external/images/media/99"

        val src = absPath("src")
        // sdkInt 默认 21（预 Q），走 outputCollectionUri(collectionUri) 分支
        val result = store().copyToGallery(src)
        assertEquals("content://media/external/images/media/99", result)
    }

    @Test
    fun `markSaved deletes file and sets SAVED`() = runTest {
        val path = absPath("toSave")
        assertEquals(ChatImageStore.Status.ACTIVE, dao.getByPath(path)?.status)
        store().markSaved(path)
        assertEquals(ChatImageStore.Status.SAVED, dao.getByPath(path)?.status)
        assertFalse(File(path).exists())
    }
```

类顶部加 import（若 IDE 报缺）：`import io.mockk.mockkStatic`。

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.data.repository.ChatImageStoreImplTest"`
Expected: FAIL（`copyToGallery` 返回 null / `markSaved` 空实现）。

- [ ] **Step 3: 实现 copyToGallery / markSaved**

替换 `ChatImageStoreImpl.kt` 中两个 TODO 方法：

```kotlin
    override suspend fun copyToGallery(filePath: String): String? = withContext(Dispatchers.IO) {
        val src = File(filePath.removePrefix("file://"))
        if (!src.exists()) {
            Logger.w(TAG, "copyToGallery: source missing $filePath")
            return@withContext null
        }
        val displayName = "PoLang_edit_${UUID.randomUUID()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (sdkInt >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, GALLERY_RELATIVE_PATH)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val collection = if (sdkInt >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            outputCollectionUri
        }
        val itemUri = context.contentResolver.insert(collection, values) ?: return@withContext null
        var ok = false
        context.contentResolver.openOutputStream(itemUri)?.use { out ->
            src.inputStream().use { it.copyTo(out) }
            ok = true
        }
        if (ok && sdkInt >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(itemUri, values, null, null)
        }
        if (ok) itemUri.toString() else null
    }

    override suspend fun markSaved(filePath: String) {
        val abs = filePath.removePrefix("file://")
        runCatching { File(abs).delete() }
        dao.updateStatus(abs, ChatImageStore.Status.SAVED)
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.data.repository.ChatImageStoreImplTest"`
Expected: PASS（全部）。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/data/repository/ChatImageStoreImpl.kt \
        app/src/test/java/com/mamba/picme/data/repository/ChatImageStoreImplTest.kt
git commit -m "feat(chat): ChatImageStore copyToGallery/markSaved + 单测"
```

---

## Task 4: ChatImageStore — reconcileColdStart / evictForSession（TDD）

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/data/repository/ChatImageStoreImpl.kt`
- Modify: `app/src/test/java/com/mamba/picme/data/repository/ChatImageStoreImplTest.kt`

- [ ] **Step 1: 追加失败测试**

在 `ChatImageStoreImplTest.kt` 类内追加：

```kotlin
    @Test
    fun `reconcileColdStart marks ACTIVE rows whose file is missing as EVICTED`() = runTest {
        val store = store()
        val live = absPath("live")
        val gone = absPath("gone") // 有行
        File(gone).delete() // 但文件被外部删了
        seed(live, lastAccessedAt = 100)
        seed(gone, lastAccessedAt = 200)
        store.reconcileColdStart()
        assertEquals(ChatImageStore.Status.ACTIVE, dao.getByPath(live)?.status)
        assertEquals(ChatImageStore.Status.EVICTED, dao.getByPath(gone)?.status)
    }

    @Test
    fun `reconcileColdStart deletes orphan files not tracked by any row`() = runTest {
        val store = store()
        val tracked = absPath("tracked")
        seed(tracked, lastAccessedAt = 100)
        val orphan = File(cacheDir, "orphan.jpg").apply { writeBytes(ByteArray(10)) }
        assertTrue(orphan.exists())
        store.reconcileColdStart()
        assertFalse(orphan.exists())
        assertTrue(File(tracked).exists())
    }

    @Test
    fun `reconcileColdStart prunes terminal rows and deletes leftover SAVED files`() = runTest {
        val store = store()
        val savedLeftover = absPath("savedLeftover") // 行 SAVED 但文件还在
        seed(savedLeftover, lastAccessedAt = 100)
        dao.updateStatus(savedLeftover, ChatImageStore.Status.SAVED)
        store.reconcileColdStart()
        assertFalse(File(savedLeftover).exists())
        assertEquals(null, dao.getByPath(savedLeftover)) // 终态行被 prune
    }

    @Test
    fun `evictForSession deletes ACTIVE files of the session`() = runTest {
        val store = store()
        val s1 = absPath("s1")
        val s2 = absPath("s2")
        dao.upsert(com.mamba.picme.data.local.entity.ChatImageCacheEntity(s1, "sessA", 1, 1, 50, ChatImageStore.Status.ACTIVE))
        dao.upsert(com.mamba.picme.data.local.entity.ChatImageCacheEntity(s2, "sessB", 1, 1, 50, ChatImageStore.Status.ACTIVE))
        store.evictForSession("sessA")
        assertEquals(ChatImageStore.Status.EVICTED, dao.getByPath(s1)?.status)
        assertFalse(File(s1).exists())
        assertEquals(ChatImageStore.Status.ACTIVE, dao.getByPath(s2)?.status)
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.data.repository.ChatImageStoreImplTest"`
Expected: FAIL（reconcile/evict 空实现）。

- [ ] **Step 3: 实现 reconcileColdStart / evictForSession**

替换 `ChatImageStoreImpl.kt` 中两个 TODO 方法：

```kotlin
    override suspend fun reconcileColdStart() = withContext(Dispatchers.IO) {
        // 1) ACTIVE 行文件缺失 → EVICTED；SAVED/EVICTED 行文件还在 → 删
        dao.allRows().forEach { row ->
            val f = File(row.filePath)
            when (row.status) {
                ChatImageStore.Status.ACTIVE -> if (!f.exists()) dao.updateStatus(row.filePath, ChatImageStore.Status.EVICTED)
                ChatImageStore.Status.SAVED, ChatImageStore.Status.EVICTED -> if (f.exists()) runCatching { f.delete() }
            }
        }
        // 2) 删孤儿文件
        val known = dao.allFilePaths().toHashSet()
        cacheDir.listFiles()?.forEach { f -> if (f.isFile && f.absolutePath !in known) runCatching { f.delete() } }
        // 3) prune 终态行
        dao.pruneTerminalRows()
        // 4) 重新执行容量约束
        enforceCap()
    }

    override suspend fun evictForSession(sessionId: String) {
        dao.getActiveBySession(sessionId).forEach { row ->
            runCatching { File(row.filePath).delete() }
            dao.updateStatus(row.filePath, ChatImageStore.Status.EVICTED)
        }
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.data.repository.ChatImageStoreImplTest"`
Expected: PASS（全部）。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/data/repository/ChatImageStoreImpl.kt \
        app/src/test/java/com/mamba/picme/data/repository/ChatImageStoreImplTest.kt
git commit -m "feat(chat): ChatImageStore reconcileColdStart/evictForSession + 单测"
```

---

## Task 5: SaveChatEditResultUseCase（TDD）

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/usecase/SaveChatEditResultUseCase.kt`
- Create: `app/src/test/java/com/mamba/picme/domain/usecase/SaveChatEditResultUseCaseTest.kt`

- [ ] **Step 1: 写失败测试**

Create `app/src/test/java/com/mamba/picme/domain/usecase/SaveChatEditResultUseCaseTest.kt`:

```kotlin
package com.mamba.picme.domain.usecase

import com.mamba.picme.data.local.ChatMessageDao
import com.mamba.picme.data.local.ChatMessageEntity
import com.mamba.picme.domain.repository.ChatImageStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveChatEditResultUseCaseTest {

    @After
    fun tearDown() = unmockkAll()

    private fun msg(metadata: String) = ChatMessageEntity(
        id = "m1", sessionId = "default", type = "agent_edit_result",
        content = "已提亮", timestamp = 1, modelUsed = "m", metadata = metadata
    )

    @Test
    fun `happy path copies to gallery, repoints metadata, marks saved`() = runTest {
        val dao = mockk<ChatMessageDao>(relaxed = true)
        val store = mockk<ChatImageStore>(relaxed = true)
        coEvery { dao.getMessageById("m1") } returns msg("""{"imageUri":"file:///x/edit_a.jpg"}""")
        coEvery { store.copyToGallery(any()) } returns "content://media/external/images/media/77"

        val result = SaveChatEditResultUseCase(store, dao).execute("m1")

        assertTrue(result.isSuccess)
        assertEquals("content://media/external/images/media/77", result.getOrNull())
        coVerify { store.copyToGallery("/x/edit_a.jpg") }
        coVerify { store.markSaved("/x/edit_a.jpg") }
        // 消息被重写：imageUri 改为 content://，saved=true
        coVerify {
            dao.insertMessage(match {
                it.id == "m1" &&
                    it.metadata!!.contains("\"imageUri\":\"content://media/external/images/media/77\"") &&
                    it.metadata!!.contains("\"saved\":true")
            })
        }
    }

    @Test
    fun `idempotent when already saved returns existing content uri and does not copy again`() = runTest {
        val dao = mockk<ChatMessageDao>(relaxed = true)
        val store = mockk<ChatImageStore>(relaxed = true)
        coEvery { dao.getMessageById("m1") } returns
            msg("""{"imageUri":"content://media/external/images/media/77","saved":true,"savedAt":123}""")

        val result = SaveChatEditResultUseCase(store, dao).execute("m1")

        assertTrue(result.isSuccess)
        assertEquals("content://media/external/images/media/77", result.getOrNull())
        coVerify(exactly = 0) { store.copyToGallery(any()) }
        coVerify(exactly = 0) { store.markSaved(any()) }
    }

    @Test
    fun `fails when source file evicted and does not mutate message`() = runTest {
        val dao = mockk<ChatMessageDao>(relaxed = true)
        val store = mockk<ChatImageStore>(relaxed = true)
        coEvery { dao.getMessageById("m1") } returns msg("""{"imageUri":"file:///x/gone.jpg"}""")
        coEvery { store.copyToGallery(any()) } returns null // 文件已不在 → 复制失败

        val result = SaveChatEditResultUseCase(store, dao).execute("m1")

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { dao.insertMessage(any()) }
        coVerify(exactly = 0) { store.markSaved(any()) }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.usecase.SaveChatEditResultUseCaseTest"`
Expected: FAIL（类未创建）。

- [ ] **Step 3: 实现 UseCase**

Create `app/src/main/java/com/mamba/picme/domain/usecase/SaveChatEditResultUseCase.kt`:

```kotlin
package com.mamba.picme.domain.usecase

import com.mamba.picme.data.local.ChatMessageDao
import com.mamba.picme.domain.repository.ChatImageStore
import org.json.JSONObject
import java.io.File

/**
 * 把一条 chat 编辑/优化结果消息保存进相册。
 *
 * 顺序保证「文件不先于消息重指向被删」：
 * 1. copyToGallery 得 content:// URI（失败则直接返回，不动文件、不改消息）；
 * 2. 更新消息 metadata：imageUri = contentUri、saved=true、savedAt；
 * 3. markSaved 删私有文件 + 行置 SAVED。
 *
 * 幂等：metadata.saved=true 时直接返回既有 content:// URI。
 */
class SaveChatEditResultUseCase(
    private val store: ChatImageStore,
    private val chatMessageDao: ChatMessageDao
) {
    suspend fun execute(messageId: String): Result<String> {
        val msg = chatMessageDao.getMessageById(messageId)
            ?: return Result.failure(IllegalStateException("消息不存在"))
        val meta = msg.metadata?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()

        // 幂等
        if (meta.optBoolean("saved", false)) {
            val existing = meta.optString("imageUri").takeIf { it.startsWith("content://") } ?: ""
            return Result.success(existing)
        }

        val imageUri = meta.optString("imageUri")
        if (!imageUri.startsWith("file://") || !File(imageUri.removePrefix("file://")).exists()) {
            return Result.failure(IllegalStateException("图片已过期，无法保存"))
        }
        val filePath = imageUri.removePrefix("file://")
        val contentUri = store.copyToGallery(filePath)
            ?: return Result.failure(IllegalStateException("保存到相册失败"))

        meta.put("imageUri", contentUri)
        meta.put("saved", true)
        meta.put("savedAt", System.currentTimeMillis())
        chatMessageDao.insertMessage(msg.copy(metadata = meta.toString()))
        store.markSaved(filePath)
        return Result.success(contentUri)
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.usecase.SaveChatEditResultUseCaseTest"`
Expected: PASS（3 个）。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/usecase/SaveChatEditResultUseCase.kt \
        app/src/test/java/com/mamba/picme/domain/usecase/SaveChatEditResultUseCaseTest.kt
git commit -m "feat(chat): SaveChatEditResultUseCase 保存编排 + 单测"
```

---

## Task 6: 重构 ChatEditProcessor 走 store（更新其测试）

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/usecase/ChatEditProcessor.kt`
- Modify: `app/src/main/java/com/mamba/picme/domain/agent/capability/ImageEditCapability.kt`
- Modify: `app/src/test/java/com/mamba/picme/domain/usecase/ChatEditProcessorTest.kt`
- Modify: `app/src/test/java/com/mamba/picme/domain/agent/capability/ImageEditCapabilityTest.kt`

- [ ] **Step 1: 更新 ChatEditProcessorTest 的预期（先改测试）**

把 `ChatEditProcessorTest.kt` 改为：

```kotlin
package com.mamba.picme.domain.usecase

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.mamba.picme.beauty.api.PhotoProcessor
import com.mamba.picme.beauty.api.facedetect.FaceDetector
import com.mamba.picme.domain.repository.ChatImageStore
import com.mamba.picme.features.editor.EditRecipe
import com.mamba.picme.features.editor.RecipeApplier
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class ChatEditProcessorTest {

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `process writes via ChatImageStore and returns file uri`() = runTest {
        mockkStatic(Uri::class)
        mockkStatic(BitmapFactory::class)

        val context = mockk<Context>(relaxed = true)
        val photoProcessor = mockk<PhotoProcessor>(relaxed = true)
        val faceDetector = mockk<FaceDetector>(relaxed = true)
        val store = mockk<ChatImageStore>(relaxed = true)
        val applier = mockk<RecipeApplier>(relaxed = true)
        val bitmap = mockk<Bitmap>(relaxed = true)

        every { Uri.parse(any<String>()) } returns mockk(relaxed = true)
        every { BitmapFactory.decodeStream(any()) } returns bitmap
        every { context.contentResolver.openInputStream(any()) } returns ByteArrayInputStream(byteArrayOf())
        every { applier.applyCrop(any(), any()) } returns bitmap
        coEvery { applier.applyGpuEffects(any(), any(), any()) } returns bitmap
        coEvery { store.writeResult(any(), any(), any()) } returns "file:///cache/edit_x.jpg"

        val processor = ChatEditProcessor(
            photoProcessor, faceDetector, store,
            recipeApplierFactory = { _, _ -> applier }
        )
        val result = processor.execute(context, "file:///test.jpg", EditRecipe(sourceUri = "file:///test.jpg"), "default")

        assertTrue(result.isSuccess)
        assertEquals("file:///cache/edit_x.jpg", result.getOrNull())
        coVerify { store.writeResult("default", bitmap, "image/jpeg") }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.usecase.ChatEditProcessorTest"`
Expected: FAIL（构造签名不匹配）。

- [ ] **Step 3: 改 ChatEditProcessor**

修改 `ChatEditProcessor.kt`：

3a. 构造参数替换——把
```kotlin
class ChatEditProcessor(
    private val photoProcessor: PhotoProcessor,
    private val faceDetector: FaceDetector,
    private val mediaRepository: MediaRepository,
    private val userSettingsRepository: UserSettingsRepository? = null,
    private val outputCollectionUri: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val recipeApplierFactory: (PhotoProcessor, CoroutineDispatcher) -> RecipeApplier = ::RecipeApplier
)
```
改为
```kotlin
class ChatEditProcessor(
    private val photoProcessor: PhotoProcessor,
    private val faceDetector: FaceDetector,
    private val chatImageStore: ChatImageStore,
    private val userSettingsRepository: UserSettingsRepository? = null,
    private val recipeApplierFactory: (PhotoProcessor, CoroutineDispatcher) -> RecipeApplier = ::RecipeApplier
)
```

3b. 删除 `import android.net.Uri`、`import android.os.Build`、`import android.provider.MediaStore`、`import com.mamba.picme.domain.repository.MediaRepository` 中已不再使用者（`MediaRepository` 与 `Uri`/`Build`/`MediaStore` 全部不再用）；新增 `import com.mamba.picme.domain.repository.ChatImageStore`。

3c. `execute` 增加 `sessionId` 形参，并替换保存调用——把
```kotlin
    suspend fun execute(context: Context, sourceUri: String, recipe: EditRecipe): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                ensureFacePipeline()
                val normalizedUri = normalizeSourceUri(sourceUri)
                val fullBitmap = decodeFullBitmap(context, Uri.parse(normalizedUri))
                    ?: return@withContext Result.failure(IllegalStateException("无法加载原图: $sourceUri"))

                val applier = recipeApplierFactory(photoProcessor, photoProcessingDispatcher)
                val cropped = withContext(Dispatchers.Default) { applier.applyCrop(fullBitmap, recipe.crop) }
                val faceData = detectFace(cropped)
                val processed = applier.applyGpuEffects(cropped, recipe, faceData)
                val outputUri = saveBitmapToMediaStore(context, processed)

                if (outputUri != null) {
                    mediaRepository.refreshMediaLibrary()
                    Result.success(outputUri)
                } else {
                    Result.failure(IllegalStateException("保存结果图失败"))
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Chat edit failed", e)
                Result.failure(e)
            }
        }
    }
```
改为
```kotlin
    suspend fun execute(context: Context, sourceUri: String, recipe: EditRecipe, sessionId: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                ensureFacePipeline()
                val normalizedUri = normalizeSourceUri(sourceUri)
                val fullBitmap = decodeFullBitmap(context, Uri.parse(normalizedUri))
                    ?: return@withContext Result.failure(IllegalStateException("无法加载原图: $sourceUri"))

                val applier = recipeApplierFactory(photoProcessor, photoProcessingDispatcher)
                val cropped = withContext(Dispatchers.Default) { applier.applyCrop(fullBitmap, recipe.crop) }
                val faceData = detectFace(cropped)
                val processed = applier.applyGpuEffects(cropped, recipe, faceData)
                val outputUri = chatImageStore.writeResult(sessionId, processed, "image/jpeg")
                Result.success(outputUri)
            } catch (e: Exception) {
                Logger.e(TAG, "Chat edit failed", e)
                Result.failure(e)
            }
        }
    }
```

3d. 删除整个 `private fun saveBitmapToMediaStore(...)` 方法（不再使用）。

- [ ] **Step 4: 改 ImageEditCapability 透传 sessionId**

修改 `ImageEditCapability.kt` 第 121 行，把
```kotlin
            chatEditProcessor.execute(this.context, targetUri, recipe)
```
改为
```kotlin
            chatEditProcessor.execute(this.context, targetUri, recipe, sessionId)
```
（`sessionId` 在该方法内已于第 96 行定义 `val sessionId = context.memorySessionId`。）

- [ ] **Step 5: 更新 ImageEditCapabilityTest 的 mock 签名**

把 `ImageEditCapabilityTest.kt` 中所有
```kotlin
coEvery { processor.execute(any(), any(), any()) } ...
```
改为
```kotlin
coEvery { processor.execute(any(), any(), any(), any()) } ...
```
把
```kotlin
coVerify { processor.execute(context, "file:///input.jpg", any()) }
```
改为
```kotlin
coVerify { processor.execute(context, "file:///input.jpg", any(), any()) }
```
（同处 `coVerify { processor.execute(context, "file:///state.jpg", any()) }` 也加一个 `, any()`。）共 3 处 `coEvery` + 2 处 `coVerify`。

- [ ] **Step 6: 运行相关测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.usecase.ChatEditProcessorTest" --tests "com.mamba.picme.domain.agent.capability.ImageEditCapabilityTest"`
Expected: PASS。

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/usecase/ChatEditProcessor.kt \
        app/src/main/java/com/mamba/picme/domain/agent/capability/ImageEditCapability.kt \
        app/src/test/java/com/mamba/picme/domain/usecase/ChatEditProcessorTest.kt \
        app/src/test/java/com/mamba/picme/domain/agent/capability/ImageEditCapabilityTest.kt
git commit -m "refactor(chat): ChatEditProcessor 走 ChatImageStore 落盘（不入相册）"
```

---

## Task 7: 重构 ChatImageRenderer 走 store

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatImageRenderer.kt`

- [ ] **Step 1: 改构造与公开方法签名**

修改 `ChatImageRenderer.kt`：

1a. 构造增 `chatImageStore`：
```kotlin
class ChatImageRenderer(
    private val context: Context,
    private val photoProcessor: PhotoProcessor,
    private val mattingEngine: MattingEngine,
    private val optimizeUseCase: AiOptimizeUseCase,
    private val chatImageStore: ChatImageStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
)
```
新增 import：`import com.mamba.picme.domain.repository.ChatImageStore`。删除现已不用的 import（`java.util.UUID` 若仅 saveBitmap 用到则删；保留decode 用的）。

1b. 三个公开方法 + `renderRecipe` + `saveBitmap` 末尾追加 `sessionId: String` 形参，并把内部 `saveBitmap(marked)` 改为 `chatImageStore.writeResult(sessionId, marked, "image/jpeg")`：

`adjustImage` 签名：
```kotlin
    suspend fun adjustImage(
        imageUri: String,
        brightness: Float? = null,
        contrast: Float? = null,
        saturation: Float? = null,
        temperature: Float? = null,
        sessionId: String
    ): Outcome = withContext(dispatcher) {
        ...
        val rendered = renderRecipe(imageUri, recipe, sessionId)
        ...
    }
```
`aiOptimize` 签名：
```kotlin
    suspend fun aiOptimize(imageUri: String, sessionId: String): Outcome = withContext(dispatcher) {
        ...
        val rendered = renderRecipe(imageUri, result.editRecipe, sessionId)
        ...
    }
```
`renderRecipe` 签名与保存：
```kotlin
    suspend fun renderRecipe(imageUri: String, recipe: EditRecipe, sessionId: String): String? = withContext(dispatcher) {
        try {
            ...
            val marked = applier.applyMarkup(cutout, recipe.markup)
            val saved = chatImageStore.writeResult(sessionId, marked, "image/jpeg")
            Logger.i(TAG, "renderRecipe: saved=$saved")
            saved
        } catch (e: Exception) { ... }
    }
```

1c. **删除整个 `private fun saveBitmap(bitmap: Bitmap): String?` 方法**（不再使用，落盘已交给 store）。

- [ ] **Step 2: 更新调用点（ChatViewModel）**

修改 `ChatViewModel.kt`：

2a. `adjustImage` 回调（约 422-441 行），把
```kotlin
        ChatToolService.getInstance().adjustImageHandler = { uri, brightness, contrast, saturation, temperature ->
            val renderer = chatImageRenderer
            if (renderer == null) {
                "Error: 图片渲染器暂不可用"
            } else {
                val outcome = renderer.adjustImage(uri, brightness, contrast, saturation, temperature)
                ...
                if (outcome.imageUri != null) {
                    insertAgentImageMessage(
                        sessionId = "default",
                        ...
```
改为（渲染与会话归属用同一个 sid，保证缓存行 sessionId 与消息一致，便于删会话清理）：
```kotlin
        ChatToolService.getInstance().adjustImageHandler = { uri, brightness, contrast, saturation, temperature ->
            val renderer = chatImageRenderer
            if (renderer == null) {
                "Error: 图片渲染器暂不可用"
            } else {
                val sid = _currentSessionId.value
                val outcome = renderer.adjustImage(uri, brightness, contrast, saturation, temperature, sid)
                ...
                if (outcome.imageUri != null) {
                    insertAgentImageMessage(
                        sessionId = sid,
                        ...
```

2b. `aiOptimize` 调用（约 965 行），把
```kotlin
                                val outcome = renderer.aiOptimize(targetUri)
```
改为
```kotlin
                                val outcome = renderer.aiOptimize(targetUri, sessionId)
```
（该作用域内已有 `sessionId`，见 958/969 行。）

- [ ] **Step 3: 编译验证**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL（DI 还未注入 store，构造会在 Task 8 完成；若此处因 AppContainer 构造报错，先在 Task 8 修。若编译因未注入失败，跳到 Task 8 完成后再回来跑此步）。

> 实操提示：Task 7 与 Task 8 必须一起编译通过。可先做 Task 8 的 DI 注入，再回头跑本步编译。

- [ ] **Step 4: Commit（与 Task 8 合并提交亦可）**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ChatImageRenderer.kt \
        app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt
git commit -m "refactor(chat): ChatImageRenderer 走 ChatImageStore 落盘 + sessionId 透传"
```

---

## Task 8: DI 注入（AppContainer + Dependencies）

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/di/AppContainer.kt`
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatViewModelDependencies.kt`
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`

- [ ] **Step 1: AppContainer 构造 store + usecase，注入渲染器**

修改 `AppContainer.kt`：

1a. 新增 lazy 属性（放在 `chatEditStateHolder` 之后、`chatEditProcessor` 之前）：
```kotlin
    private val chatImageStore: com.mamba.picme.domain.repository.ChatImageStore by lazy {
        com.mamba.picme.data.repository.ChatImageStoreImpl(
            context = context,
            dao = database.chatImageCacheDao()
        )
    }

    private val saveChatEditResultUseCase: com.mamba.picme.domain.usecase.SaveChatEditResultUseCase by lazy {
        com.mamba.picme.domain.usecase.SaveChatEditResultUseCase(
            store = chatImageStore,
            chatMessageDao = database.chatMessageDao()
        )
    }
```
（用全限定名省一次 import 往返；后续可按团队习惯改为 import。注意 `checkNoFullyQualifiedName` 任务约束的是 `com.mamba.picme.*` 源码——**这里必须改为 import 写法**：）
实际写法——在文件顶部加 import：
```kotlin
import com.mamba.picme.data.repository.ChatImageStoreImpl
import com.mamba.picme.domain.repository.ChatImageStore
import com.mamba.picme.domain.usecase.SaveChatEditResultUseCase
```
然后属性写为：
```kotlin
    private val chatImageStore: ChatImageStore by lazy {
        ChatImageStoreImpl(context = context, dao = database.chatImageCacheDao())
    }

    private val saveChatEditResultUseCase: SaveChatEditResultUseCase by lazy {
        SaveChatEditResultUseCase(store = chatImageStore, chatMessageDao = database.chatMessageDao())
    }
```

1b. `chatEditProcessor` 构造改为注入 store（删 mediaRepository）：
```kotlin
    private val chatEditProcessor: ChatEditProcessor by lazy {
        ChatEditProcessor(
            photoProcessor = photoProcessor,
            faceDetector = faceDetector,
            chatImageStore = chatImageStore,
            userSettingsRepository = userPreferencesRepository
        )
    }
```

1c. `chatImageRenderer` 构造改为注入 store：
```kotlin
    private val chatImageRenderer: ChatImageRenderer by lazy {
        ChatImageRenderer(context, photoProcessor, mattingEngine, aiOptimizeUseCase, chatImageStore)
    }
```

1d. `chatViewModelDependencies` 构造增两个字段（在 `chatImageRenderer = chatImageRenderer` 之后）：
```kotlin
            chatImageStore = chatImageStore,
            saveChatEditResultUseCase = saveChatEditResultUseCase
```

- [ ] **Step 2: ChatViewModelDependencies 增字段**

修改 `ChatViewModelDependencies.kt`，在 `chatImageRenderer` 之后加：
```kotlin
    val chatImageStore: ChatImageStore,
    val saveChatEditResultUseCase: SaveChatEditResultUseCase
```
并加 import：
```kotlin
import com.mamba.picme.domain.repository.ChatImageStore
import com.mamba.picme.domain.usecase.SaveChatEditResultUseCase
```

- [ ] **Step 3: ChatViewModel 持有 store + usecase 字段**

修改 `ChatViewModel.kt`，在 `private val chatEditProcessor = dependencies.chatEditProcessor`（约 138 行）之后加：
```kotlin
    private val chatImageStore = dependencies.chatImageStore
    private val saveChatEditResultUseCase = dependencies.saveChatEditResultUseCase
```

- [ ] **Step 4: 编译验证**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。此时 Task 7 的渲染器改造与 DI 一并打通。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/di/AppContainer.kt \
        app/src/main/java/com/mamba/picme/features/chat/ChatViewModelDependencies.kt \
        app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt
git commit -m "feat(chat): DI 注入 ChatImageStore / SaveChatEditResultUseCase"
```

---

## Task 9: ViewModel — saveEditResult / touchEditImage / deleteSession 钩子

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`

- [ ] **Step 1: 新增 saveEditResult 与 touchEditImage**

在 `ChatViewModel.kt` 内（`deleteSession` 之前）加：
```kotlin
    /**
     * 把指定编辑/优化结果消息保存进相册。成功后消息 imageUri 重指向 content://，UI 经 Flow 自动刷新。
     * @param onResult 成功/失败回调，供 UI 切换按钮状态 / 提示。
     */
    fun saveEditResult(messageId: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val res = saveChatEditResultUseCase.execute(messageId)
            if (res.isFailure) Logger.w(TAG, "saveEditResult failed: ${res.exceptionOrNull()}")
            onResult(res.isSuccess)
        }
    }

    /** 打开编辑结果预览时刷新 LRU recency（仅对私有 file:// 路径有意义）。 */
    fun touchEditImage(imageUri: String?) {
        if (imageUri == null || !imageUri.startsWith("file://")) return
        val path = imageUri.removePrefix("file://")
        viewModelScope.launch { runCatching { chatImageStore.touch(path) } }
    }
```

- [ ] **Step 2: deleteSession 钩入 evictForSession**

把 `deleteSession`（约 597-611 行）内的 try 块改为：
```kotlin
            try {
                chatImageStore.evictForSession(sessionId)
                chatMessageDao.deleteAllMessagesBySession(sessionId)
                chatSessionDao.deleteSession(sessionId)
                if (_currentSessionId.value == sessionId) {
                    _currentSessionId.value = "default"
                    userSettingsRepository.updateChatCurrentSessionId("default")
                }
                Logger.i(TAG, "Deleted session: $sessionId")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to delete session", e)
            }
```

- [ ] **Step 3: 冷启对账（应用启动跑一次）**

在 `init { ... }` 块内（任意一处，建议末尾）加：
```kotlin
        viewModelScope.launch { runCatching { chatImageStore.reconcileColdStart() } }
```

- [ ] **Step 4: 编译验证**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt
git commit -m "feat(chat): ViewModel saveEditResult/touchEditImage + 删会话/冷启缓存维护"
```

---

## Task 10: 消息 metadata saved 字段 + toUiModel

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt`

- [ ] **Step 1: ChatMessageUi 增 imageSaved**

在 `ChatScreen.kt` 的 `data class ChatMessageUi(...)` 内（`chartSvg` 之后）加：
```kotlin
    /** agent_image / agent_edit_result 是否已保存到相册（来自 metadata.saved）。 */
    val imageSaved: Boolean = false
```

- [ ] **Step 2: toUiModel 解析 saved**

在 `ChatViewModel.kt` 的 `toUiModel()` 内，`return ChatMessageUi(...)` 调用中追加一个参数（放在 `chartSvg = ...` 之后）：
```kotlin
            imageSaved = (type == "agent_image" || type == "agent_edit_result") &&
                (metadata?.let { runCatching { org.json.JSONObject(it).optBoolean("saved", false) }.getOrDefault(false) } ?: false)
```

- [ ] **Step 3: 插入消息时写 saved=false**

在 `insertAgentImageMessage`（约 1683 行 `chatMessageDao.insertMessage(...)`）与 `insertEditResultMessage`（约 1727 行）两处的 metadata 构建里，确保有 `put("saved", false)`。

`insertAgentImageMessage` 的 `val metadata = JSONObject().apply { put("imageUri", imageUri); ... }` 块内加：
```kotlin
            put("saved", false)
```
`insertEditResultMessage` 的 metadata 块（已有 `put("imageUri", imageUri)`、`put("explanation", ...)` 等）内同样加：
```kotlin
            put("saved", false)
```

- [ ] **Step 4: 编译验证**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt \
        app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt
git commit -m "feat(chat): 消息 metadata 携带 saved，toUiModel 解析"
```

---

## Task 11: i18n 文案（三语种同步）

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`
- Modify: `app/src/main/res/values-zh-rTW/strings.xml`

- [ ] **Step 1: 英文（默认）**

在 `values/strings.xml` 内 `<string name="cd_image_preview">...` 行附近追加：
```xml
    <string name="chat_edit_save_to_gallery">Save to gallery</string>
    <string name="chat_edit_saved_to_gallery">✓ Saved to gallery</string>
    <string name="chat_edit_image_expired">Image expired · not visible</string>
    <string name="chat_edit_save_expired_failed">Image expired, cannot save</string>
    <string name="cd_chat_edit_save">Save edited image to gallery</string>
```

- [ ] **Step 2: 简体中文**

在 `values-zh-rCN/strings.xml` 对应位置追加：
```xml
    <string name="chat_edit_save_to_gallery">保存到相册</string>
    <string name="chat_edit_saved_to_gallery">✓ 已保存到相册</string>
    <string name="chat_edit_image_expired">图片已过期·不可见</string>
    <string name="chat_edit_save_expired_failed">图片已过期，无法保存</string>
    <string name="cd_chat_edit_save">保存编辑后的图片到相册</string>
```

- [ ] **Step 3: 繁体中文**

在 `values-zh-rTW/strings.xml` 对应位置追加：
```xml
    <string name="chat_edit_save_to_gallery">儲存到相簿</string>
    <string name="chat_edit_saved_to_gallery">✓ 已儲存到相簿</string>
    <string name="chat_edit_image_expired">圖片已過期·不可見</string>
    <string name="chat_edit_save_expired_failed">圖片已過期，無法儲存</string>
    <string name="cd_chat_edit_save">儲存編輯後的圖片到相簿</string>
```

- [ ] **Step 4: 校验三文件 key 一致**

Run: `for f in values values-zh-rCN values-zh-rTW; do echo "== $f =="; grep -c "chat_edit_save_to_gallery\|chat_edit_saved_to_gallery\|chat_edit_image_expired\|chat_edit_save_expired_failed\|cd_chat_edit_save" app/src/main/res/$f/strings.xml; done`
Expected: 每个文件输出 5。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-zh-rCN/strings.xml app/src/main/res/values-zh-rTW/strings.xml
git commit -m "i18n(chat): 编辑结果保存/过期文案（中/英/繁）"
```

---

## Task 12: ChatImageLive 纯函数 + 单测

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/chat/ChatImageLive.kt`
- Create: `app/src/test/java/com/mamba/picme/features/chat/ChatImageLiveTest.kt`

- [ ] **Step 1: 写失败测试**

Create `app/src/test/java/com/mamba/picme/features/chat/ChatImageLiveTest.kt`:

```kotlin
package com.mamba.picme.features.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChatImageLiveTest {
    @Test
    fun `content uri is always live`() {
        assertTrue(chatImageIsLive("content://media/external/images/media/1"))
    }

    @Test
    fun `file uri live when file exists`() {
        val f = File(System.getProperty("java.io.tmpdir"), "polang_live_test_${System.nanoTime()}.jpg")
        f.writeBytes(ByteArray(1))
        try {
            assertTrue(chatImageIsLive("file://${f.absolutePath}"))
        } finally { f.delete() }
    }

    @Test
    fun `file uri dead when file missing`() {
        assertFalse(chatImageIsLive("file:///nope/missing_${System.nanoTime()}.jpg"))
    }

    @Test
    fun `null or blank is dead`() {
        assertFalse(chatImageIsLive(null))
        assertFalse(chatImageIsLive(""))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.ChatImageLiveTest"`
Expected: FAIL（函数未定义）。

- [ ] **Step 3: 实现纯函数**

Create `app/src/main/java/com/mamba/picme/features/chat/ChatImageLive.kt`:

```kotlin
package com.mamba.picme.features.chat

import java.io.File

/**
 * 判定 chat 内一张结果图当前是否可展示（未过期）。
 *
 * - content://（已保存到相册）：恒为存活，免疫 LRU；
 * - file:// 或裸路径（私有缓存）：取决于文件是否仍存在；
 * - null/空：不存活。
 *
 * 判定基于 [File.exists]（stat），不依赖 Coil 内存缓存，确定性强。
 */
fun chatImageIsLive(uri: String?): Boolean {
    if (uri.isNullOrBlank()) return false
    if (uri.startsWith("content://")) return true
    val path = uri.removePrefix("file://")
    return File(path).exists()
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.ChatImageLiveTest"`
Expected: PASS（4 个）。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ChatImageLive.kt \
        app/src/test/java/com/mamba/picme/features/chat/ChatImageLiveTest.kt
git commit -m "feat(chat): chatImageIsLive scheme 感知存活判定 + 单测"
```

---

## Task 13: ChatScreen — 预览保存按钮 + 过期占位

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt`

- [ ] **Step 1: 新增 PreviewImageState，替换 previewImageUri**

1a. 在文件顶层（`ChatMessageUi` 附近）加：
```kotlin
/** 全屏预览态：携带保存所需的 messageId / 类型 / 已保存标记。 */
data class PreviewImageState(
    val uri: Uri,
    val messageId: String,
    val isEditableResult: Boolean, // agent_image / agent_edit_result 才显示保存按钮
    val isSaved: Boolean
)
```

1b. 把 `var previewImageUri by remember { mutableStateOf<Uri?>(null) }`（约 198 行）改为：
```kotlin
    var previewImage by remember { mutableStateOf<PreviewImageState?>(null) }
```

1c. `BackHandler`（约 364-369 行）把 `previewImageUri != null` 改为 `previewImage != null`，分支内 `previewImageUri = null` 改为 `previewImage = null`。

1d. 预览栏位判断（约 411 行）`previewImageUri == null` 改为 `previewImage == null`（合取条件里替换）。

- [ ] **Step 2: onImageClick 改传消息，并接 touch**

把约 473 行：
```kotlin
                                    onImageClick = { imageUri -> previewImageUri = imageUri }
```
改为：
```kotlin
                                    onImageClick = { msg ->
                                        val isEdit = msg.type == ChatMessageType.AGENT_IMAGE ||
                                            msg.type == ChatMessageType.AGENT_EDIT_RESULT
                                        if (isEdit) viewModel.touchEditImage(msg.imageUri)
                                        val iu = Uri.parse(msg.imageUri ?: "")
                                        val resolved = if (iu.scheme != null) iu
                                            else java.io.File(msg.imageUri ?: "").toUri()
                                        previewImage = PreviewImageState(
                                            uri = resolved,
                                            messageId = msg.id,
                                            isEditableResult = isEdit,
                                            isSaved = msg.imageSaved
                                        )
                                    }
```

- [ ] **Step 3: 改 ImagePreviewOverlay 调用与签名**

把约 517-520 行：
```kotlin
            ImagePreviewOverlay(
                imageUri = previewImageUri,
                onDismiss = { previewImageUri = null }
            )
```
改为：
```kotlin
            ImagePreviewOverlay(
                state = previewImage,
                onSave = { messageId, onDone ->
                    viewModel.saveEditResult(messageId) { ok ->
                        if (ok) previewImage = previewImage?.copy(isSaved = true)
                        onDone(ok)
                    }
                },
                onDismiss = { previewImage = null }
            )
```

把 `ImagePreviewOverlay`（约 1768 行起）签名与内部改为：
```kotlin
@Composable
private fun ImagePreviewOverlay(
    state: PreviewImageState?,
    onSave: (messageId: String, onDone: (Boolean) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val expiredToast = stringResource(R.string.chat_edit_save_expired_failed)
    AnimatedVisibility(
        visible = state != null,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = state?.uri,
                contentDescription = stringResource(R.string.cd_image_preview),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentScale = ContentScale.Fit
            )

            // 关闭按钮（右上）
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
            ) {
                Icon(Icons.Rounded.Close, stringResource(R.string.close), tint = Color.White, modifier = Modifier.size(24.dp))
            }

            // 保存按钮（仅编辑/优化结果，底部居中）
            if (state?.isEditableResult == true) {
                val isSaved = state.isSaved
                Button(
                    onClick = {
                        if (!isSaved) onSave(state.messageId) { ok ->
                            if (!ok) Toast.makeText(context, expiredToast, Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = !isSaved,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(24.dp)
                        .clip(CircleShape)
                ) {
                    Text(
                        text = stringResource(
                            if (isSaved) R.string.chat_edit_saved_to_gallery else R.string.chat_edit_save_to_gallery
                        )
                    )
                }
            }
        }
    }
}
```
确保已 import：`androidx.compose.material3.Button`、`androidx.compose.ui.layout.ContentScale`（多半已有）、`android.widget.Toast`、`androidx.compose.ui.platform.LocalContext`、`androidx.compose.foundation.layout.navigationBarsPadding`。

- [ ] **Step 4: ChatMessageItem — 过期占位**

4a. 把 `ChatMessageItem` 的 `onImageClick` 形参类型改为 `(ChatMessageUi) -> Unit`（约 742 行）：
```kotlin
private fun ChatMessageItem(message: ChatMessageUi, onImageClick: (ChatMessageUi) -> Unit = {}) {
```

4b. `isImage` 分支（约 814-831 行）拆分：把
```kotlin
                isImage -> {
                    AsyncImage(
                        model = message.imageUri ?: message.content,
                        ...
                        .clickable {
                            val imgSrc = message.imageUri ?: message.content
                            val uri = Uri.parse(imgSrc)
                            val resolvedUri = if (uri.scheme != null) uri
                                else java.io.File(imgSrc).toUri()
                            onImageClick(resolvedUri)
                        }
                    )
                }
```
改为
```kotlin
                isImage -> {
                    val imgSrc = message.imageUri ?: message.content
                    if (message.type == ChatMessageType.AGENT_IMAGE && !chatImageIsLive(imgSrc)) {
                        ExpiredImagePlaceholder()
                    } else {
                        AsyncImage(
                            model = imgSrc,
                            contentDescription = stringResource(R.string.photo),
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onImageClick(message) }
                        )
                    }
                }
```

4c. `isEditResult` 分支（约 832-861 行）的图片块加过期判断：把
```kotlin
                    val imageUri = message.imageUri.orEmpty()
                    if (imageUri.isNotBlank()) {
                        AsyncImage(
                            model = imageUri,
                            ...
                            .clickable {
                                val uri = Uri.parse(imageUri)
                                val resolvedUri = if (uri.scheme != null) uri
                                    else java.io.File(imageUri).toUri()
                                onImageClick(resolvedUri)
                            }
                        )
                    }
```
改为
```kotlin
                    val imageUri = message.imageUri.orEmpty()
                    if (imageUri.isNotBlank()) {
                        if (!chatImageIsLive(imageUri)) {
                            ExpiredImagePlaceholder(height = 200.dp)
                        } else {
                            AsyncImage(
                                model = imageUri,
                                contentDescription = stringResource(R.string.photo),
                                contentScale = ContentScale.FillHeight,
                                modifier = Modifier
                                    .height(200.dp)
                                    .widthIn(max = 260.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onImageClick(message) }
                            )
                        }
                    }
```

4d. `isImageText` 分支（约 790-805 行）的 `onImageClick(resolvedUri)` 改为 `onImageClick(message)`（USER_IMAGE_TEXT 不参与过期/保存，但回调签名已变，传 message 即可）。

4e. 新增占位 Composable（放在 `ChatMessageItem` 之后）：
```kotlin
@Composable
private fun ExpiredImagePlaceholder(height: androidx.compose.ui.unit.Dp = 180.dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = androidx.compose.material.icons.Icons.Rounded.ImageNotSupported,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(36.dp)
            )
            Text(
                text = stringResource(R.string.chat_edit_image_expired),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}
```
确保 import：`androidx.compose.material.icons.Icons`、`androidx.compose.material.icons.rounded.ImageNotSupported`、`androidx.compose.foundation.layout.height`、`androidx.compose.foundation.layout.Column`。

- [ ] **Step 5: 编译验证**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。修复 import / 类型不匹配。

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt
git commit -m "feat(chat): 预览页保存按钮 + 过期占位气泡"
```

---

## Task 14: 全量验证

**Files:** 无（验证步）

- [ ] **Step 1: 全量 JVM 单测**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，0 失败（含新增 ChatImageStore/SaveChatEditResultUseCase/ChatImageLive 与改写的 ChatEditProcessor/ImageEditCapability 测试）。

- [ ] **Step 2: Debug APK 构建**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: 规范检查（红线：无全限定名 / 无通配 import / 无 it）**

Run: `./gradlew :app:lint` （若 ktlint/detekt 任务可用也一并跑：`./gradlew :app:ktlintCheck :app:detekt`）
Expected: 新增/修改文件无 `com.mamba.picme.*` 全限定名（用 import）、无 `*` 通配、lambda 形参显式命名。

- [ ] **Step 4: 手工验收（设备/模拟器，按 spec §7 流程）**

1. chat 内对一张图说「调亮一点」→ 结果图出现在气泡；打开系统相册 → **不可见**。✅ 隔离
2. 点结果图进预览 → 底部「保存到相册」→ 点 → 变「✓ 已保存到相册」；查系统相册 `Pictures/PoLang` → **可见**。✅ 保存
3. 反复编辑多张（或调小 cap 验证）→ 触发 LRU；最旧结果气泡变「图片已过期·不可见」灰框，说明文字保留。✅ 回收
4. 长按/多轮后删除该会话 → 其 ACTIVE 缓存文件被清。✅ 会话清理
5. 重启 App → 已保存气泡仍显示（content://），未保存且未过期的仍显示，过期的仍为占位。✅ 冷启对账

- [ ] **Step 5: 文档同步（spec/CLAUDE.md 命令路由等若有引用）**

确认 `docs/04-AGENT-CAPABILITIES/CAPABILITY_REGISTRY.md` 中 `edit_image`/`ImageEditCapability` 描述与「结果不入相册、需主动保存」一致；如不一致，按「代码+文档同提交」原则补一句说明。

Run: `grep -rn "CHAT_EDIT\|Pictures/PoLang\|refreshMediaLibrary" docs/` （核对是否有过时描述）
Expected: 无过时描述或已修正。

- [ ] **Step 6: 最终 commit（如有文档/收尾改动）**

```bash
git add -A
git commit -m "test(chat): 全量验证通过 + 文档同步"
```

---

## Self-Review（计划完成后自查记录）

- **Spec 覆盖**：§5 组件 → Task 2-5；§6 数据模型 → Task 1,10；§7(a) 隔离 → Task 6,7；§7(b) 保存重指向 → Task 5,9,13；§7(c) LRU → Task 2-4；§7(d) 过期检测 → Task 12,13；§8 边界（幂等/缺文件/终态行/会话清理/单文件>cap）→ Task 4,5；§9 i18n → Task 11。无遗漏。
- **占位扫描**：无 TBD/TODO；每个代码步骤均给出完整代码或精确 old→new。
- **类型一致**：`ChatImageStore.writeResult(sessionId, bitmap, mimeType)`、`copyToGallery(filePath)`、`markSaved(filePath)`、`touch(filePath)`、`Status.ACTIVE/SAVED/EVICTED`、`DEFAULT_MAX_SIZE_BYTES` 在所有任务中一致；`SaveChatEditResultUseCase(store, chatMessageDao).execute(messageId)` 一致；`chatImageIsLive(uri): Boolean` 一致；`PreviewImageState(uri, messageId, isEditableResult, isSaved)` 一致。
