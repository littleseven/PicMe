# Pexels API 测试图下载 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Debug 页「数据生成」区改版为双 Tab，新增「Pexels 图库」Tab——通过 Pexels 官方 API 浏览/搜索图片，多选或批量下载为 `TEST_PEXELS_` 前缀测试图，纳入现有测试数据体系。

**Architecture:** 全部新增代码在 `app/src/main/java/com/mamba/picme/features/debug/pexels/`（Retrofit API + KeyStore + ViewModel + Compose UI）；`SampleDataGenerator` 新增公开方法 `savePexelsPhoto()` 复用现有「下载→校验→存相册→插库」链路；`DebugScreen` 改双 Tab。旧批量抓取功能原样保留在 Tab 1。

**Tech Stack:** Kotlin + Jetpack Compose (Material3) + Retrofit/Moshi + Coil + kotlinx-coroutines；测试用 JUnit4 + mockk + Robolectric + kotlinx-coroutines-test（全部为现有依赖）。

**Spec:** `docs/superpowers/specs/2026-08-06-pexels-test-image-download-design.md`

**执行前提（强制）:** 开工前必须先按 `using-git-worktrees` skill 在 `.worktrees/` 建隔离工作区 + 专用分支（当前 main 工作区有其他任务未提交改动，禁止直接动代码）。

---

## 文件结构

| 文件 | 责任 | 新建/修改 |
|------|------|-----------|
| `app/src/main/java/com/mamba/picme/features/debug/pexels/PexelsModels.kt` | Moshi 数据模型 | 新建 |
| `app/src/main/java/com/mamba/picme/features/debug/pexels/PexelsKeyStore.kt` | API Key 本地存取（独立 SharedPreferences） | 新建 |
| `app/src/main/java/com/mamba/picme/features/debug/pexels/PexelsApi.kt` | Retrofit 接口 + 工厂 | 新建 |
| `app/src/main/java/com/mamba/picme/features/debug/pexels/PexelsImageSaver.kt` | 单图保存接口（隔离 SampleDataGenerator 便于测试） | 新建 |
| `app/src/main/java/com/mamba/picme/features/debug/pexels/PexelsUiState.kt` | UI 状态机 + 事件 + 错误枚举 | 新建 |
| `app/src/main/java/com/mamba/picme/features/debug/pexels/PexelsViewModel.kt` | 状态编排（搜索/分页/选择/下载） | 新建 |
| `app/src/main/java/com/mamba/picme/features/debug/pexels/PexelsSection.kt` | Compose UI（Key 输入/搜索栏/网格/下载栏） | 新建 |
| `app/src/main/java/com/mamba/picme/features/debug/SampleDataGenerator.kt` | 新增 `savePexelsPhoto()` 公开方法 | 修改 |
| `app/src/main/java/com/mamba/picme/features/debug/DebugScreen.kt` | 双 Tab 改版 | 修改 |
| `app/src/main/res/values{,-zh,-zh-rCN,-zh-rTW}/strings.xml` | 四语文案 | 修改 |
| `app/src/test/java/com/mamba/picme/features/debug/pexels/PexelsModelsTest.kt` | Moshi 解析测试 | 新建 |
| `app/src/test/java/com/mamba/picme/features/debug/pexels/PexelsKeyStoreTest.kt` | KeyStore Robolectric 测试 | 新建 |
| `app/src/test/java/com/mamba/picme/features/debug/pexels/PexelsViewModelTest.kt` | 状态机测试 | 新建 |
| `app/src/main/java/com/mamba/picme/features/debug/AGENTS.md` | 新增 Pexels 小节 | 修改 |

---

### Task 1: PexelsModels + Moshi 解析测试

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/debug/pexels/PexelsModels.kt`
- Test: `app/src/test/java/com/mamba/picme/features/debug/pexels/PexelsModelsTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.mamba.picme.features.debug.pexels

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PexelsModelsTest {

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private val sampleJson = """
    {
      "page": 1,
      "per_page": 30,
      "total_results": 8000,
      "next_page": "https://api.pexels.com/v1/search/?page=2",
      "photos": [
        {
          "id": 12345,
          "width": 4000,
          "height": 6000,
          "photographer": "Jane Doe",
          "alt": "A woman in sunlight",
          "src": {
            "original": "https://images.pexels.com/photos/12345/original.jpg",
            "large2x": "https://images.pexels.com/photos/12345/large2x.jpg",
            "large": "https://images.pexels.com/photos/12345/large.jpg",
            "medium": "https://images.pexels.com/photos/12345/medium.jpg",
            "small": "https://images.pexels.com/photos/12345/small.jpg"
          }
        }
      ]
    }
    """.trimIndent()

    @Test
    fun `search response parses snake_case fields`() {
        val adapter = moshi.adapter(PexelsSearchResponse::class.java)
        val response = adapter.fromJson(sampleJson)!!

        assertEquals(1, response.page)
        assertEquals(30, response.perPage)
        assertEquals(8000, response.totalResults)
        assertEquals("https://api.pexels.com/v1/search/?page=2", response.nextPage)
        assertEquals(1, response.photos.size)

        val photo = response.photos[0]
        assertEquals(12345L, photo.id)
        assertEquals("Jane Doe", photo.photographer)
        assertEquals("https://images.pexels.com/photos/12345/large2x.jpg", photo.src.large2x)
        assertEquals("https://images.pexels.com/photos/12345/medium.jpg", photo.src.medium)
    }

    @Test
    fun `last page has null next_page`() {
        val adapter = moshi.adapter(PexelsSearchResponse::class.java)
        val response = adapter.fromJson("""{"page": 5, "per_page": 30, "photos": []}""")!!

        assertNull(response.nextPage)
        assertEquals(0, response.photos.size)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.debug.pexels.PexelsModelsTest"`
Expected: FAIL（`PexelsSearchResponse` 未定义，编译错误）

- [ ] **Step 3: 实现数据模型**

```kotlin
package com.mamba.picme.features.debug.pexels

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PexelsPhoto(
    val id: Long,
    val width: Int = 0,
    val height: Int = 0,
    val photographer: String = "",
    val alt: String = "",
    val src: PexelsSrc
)

@JsonClass(generateAdapter = true)
data class PexelsSrc(
    val original: String = "",
    @Json(name = "large2x") val large2x: String = "",
    val large: String = "",
    val medium: String = "",
    val small: String = ""
)

@JsonClass(generateAdapter = true)
data class PexelsSearchResponse(
    val photos: List<PexelsPhoto> = emptyList(),
    val page: Int = 1,
    @Json(name = "per_page") val perPage: Int = 0,
    @Json(name = "total_results") val totalResults: Int = 0,
    @Json(name = "next_page") val nextPage: String? = null
)
```

> 注：Moshi ksp codegen（`moshi-kotlin-codegen`）已在 `app/build.gradle.kts` 配置，`@JsonClass(generateAdapter = true)` 直接可用；测试中用手工 `Moshi.Builder()` + `KotlinJsonAdapterFactory` 反射兜底即可。

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.debug.pexels.PexelsModelsTest"`
Expected: PASS（2 tests）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/debug/pexels/PexelsModels.kt \
        app/src/test/java/com/mamba/picme/features/debug/pexels/PexelsModelsTest.kt
git commit -m "feat(debug): Pexels API 数据模型 + Moshi 解析测试"
```

---

### Task 2: PexelsKeyStore + Robolectric 测试

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/debug/pexels/PexelsKeyStore.kt`
- Test: `app/src/test/java/com/mamba/picme/features/debug/pexels/PexelsKeyStoreTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.mamba.picme.features.debug.pexels

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PexelsKeyStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun cleanUp() {
        PexelsKeyStore(context).clear()
    }

    @Test
    fun `no key saved returns null`() {
        assertNull(PexelsKeyStore(context).getKey())
    }

    @Test
    fun `saved key is readable and trimmed`() {
        val store = PexelsKeyStore(context)
        store.saveKey("  abc123  ")
        assertEquals("abc123", PexelsKeyStore(context).getKey())
    }

    @Test
    fun `blank key is treated as absent`() {
        PexelsKeyStore(context).saveKey("   ")
        assertNull(PexelsKeyStore(context).getKey())
    }

    @Test
    fun `clear removes key`() {
        val store = PexelsKeyStore(context)
        store.saveKey("abc123")
        store.clear()
        assertNull(store.getKey())
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.debug.pexels.PexelsKeyStoreTest"`
Expected: FAIL（`PexelsKeyStore` 未定义，编译错误）

- [ ] **Step 3: 实现 KeyStore**

```kotlin
package com.mamba.picme.features.debug.pexels

import android.content.Context

/**
 * Pexels API Key 本地存取。
 * 独立 SharedPreferences（debug-only），不侵入 UserPreferencesRepository 的 DataStore schema。
 * Key 仅存本地，不进日志、不进 git。
 */
class PexelsKeyStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getKey(): String? =
        prefs.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }

    fun saveKey(key: String) {
        prefs.edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_API_KEY).apply()
    }

    private companion object {
        const val PREFS_NAME = "debug_pexels_prefs"
        const val KEY_API_KEY = "pexels_api_key"
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.debug.pexels.PexelsKeyStoreTest"`
Expected: PASS（4 tests）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/debug/pexels/PexelsKeyStore.kt \
        app/src/test/java/com/mamba/picme/features/debug/pexels/PexelsKeyStoreTest.kt
git commit -m "feat(debug): PexelsKeyStore 本地 Key 存取 + Robolectric 测试"
```

---

### Task 3: PexelsApi + PexelsImageSaver 接口

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/debug/pexels/PexelsApi.kt`
- Create: `app/src/main/java/com/mamba/picme/features/debug/pexels/PexelsImageSaver.kt`

纯接口定义，无单测（Retrofit 接口由编译期 + 真机闭环验证）。

- [ ] **Step 1: 创建 PexelsApi.kt**

```kotlin
package com.mamba.picme.features.debug.pexels

import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

/**
 * Pexels 官方 API（https://www.pexels.com/api/documentation/）。
 * Key 以 @Header 逐请求传入，支持运行时换 Key。
 * 免费档限流：200 次/小时、20,000 次/月（429 由 ViewModel 处理）。
 */
interface PexelsApi {

    @GET("v1/search")
    suspend fun search(
        @Header("Authorization") apiKey: String,
        @Query("query") query: String,
        @Query("page") page: Int,
        @Query("per_page") perPage: Int = PER_PAGE
    ): PexelsSearchResponse

    @GET("v1/curated")
    suspend fun curated(
        @Header("Authorization") apiKey: String,
        @Query("page") page: Int,
        @Query("per_page") perPage: Int = PER_PAGE
    ): PexelsSearchResponse

    companion object {
        const val PER_PAGE = 30

        fun create(): PexelsApi = Retrofit.Builder()
            .baseUrl("https://api.pexels.com/")
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(PexelsApi::class.java)
    }
}
```

- [ ] **Step 2: 创建 PexelsImageSaver.kt**

```kotlin
package com.mamba.picme.features.debug.pexels

/**
 * 单张 Pexels 图片保存抽象：隔离 SampleDataGenerator（Android 依赖），
 * 让 PexelsViewModel 可用 fake 做纯 JVM 单测。
 * 实现侧委托 SampleDataGenerator.savePexelsPhoto()。
 */
fun interface PexelsImageSaver {
    /** @return true=已存相册并插库；false=下载失败或被过滤 */
    suspend fun save(photoId: Long, imageUrl: String): Boolean
}
```

- [ ] **Step 3: 编译确认**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/debug/pexels/PexelsApi.kt \
        app/src/main/java/com/mamba/picme/features/debug/pexels/PexelsImageSaver.kt
git commit -m "feat(debug): PexelsApi Retrofit 接口 + PexelsImageSaver 抽象"
```

---

### Task 4: SampleDataGenerator.savePexelsPhoto()

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/debug/SampleDataGenerator.kt`（在 `populateSexyTestData` 之后、`generateData` 之前插入新方法）

复用现有私有方法，不改任何现有行为。

- [ ] **Step 1: 添加公开方法**

在 `populateSexyTestData(...)` 方法块结束之后插入：

```kotlin
    /**
     * 保存单张 Pexels 图片为测试图：复用现有「下载→校验→存相册→插库」链路。
     * 文件名前缀 TEST_PEXELS_，兼容 clearTestData() 的 TEST_ 前缀清理。
     */
    suspend fun savePexelsPhoto(
        context: Context,
        repository: MediaRepository,
        photoId: Long,
        imageUrl: String
    ): Boolean = withContext(Dispatchers.IO) {
        val fileName = "TEST_PEXELS_${photoId}_${System.currentTimeMillis()}.jpg"
        val file = downloadWithRetry(imageUrl, context, fileName)
        if (file == null) {
            addLog("Pexels download failed: $photoId")
            return@withContext false
        }

        val bitmap = decodeSampledBitmap(file)
        val isValid = bitmap?.let { analyzeContentAndSkin(it).isValidContent } == true
        bitmap?.recycle()
        if (!isValid) {
            file.delete()
            addLog("Pexels filtered (invalid content): $photoId")
            return@withContext false
        }

        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -Random().nextInt(180))
        val savedUri = saveTestImageToAlbum(context, file, fileName, calendar.timeInMillis)
        file.delete()
        if (savedUri == null) {
            addLog("Pexels album save failed: $fileName")
            return@withContext false
        }

        repository.insertMedia(
            MediaAsset(
                uri = savedUri,
                type = MediaType.PHOTO,
                captureDate = calendar.timeInMillis,
                fileName = fileName,
                hasFace = false,
                source = "pexels"
            )
        )
        addLog("Saved to album [PEXELS]: $fileName")
        true
    }
```

> 说明：`downloadWithRetry`、`decodeSampledBitmap`、`analyzeContentAndSkin`、`saveTestImageToAlbum` 均为文件内现有私有方法，直接复用；`Calendar`、`Random`、`Dispatchers`、`withContext`、`MediaAsset`、`MediaType` 均已在文件头部 import。

- [ ] **Step 2: 编译确认**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/debug/SampleDataGenerator.kt
git commit -m "feat(debug): SampleDataGenerator 新增 savePexelsPhoto 复用现有存图链路"
```

---

### Task 5: PexelsUiState + PexelsViewModel + 状态机测试

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/debug/pexels/PexelsUiState.kt`
- Create: `app/src/main/java/com/mamba/picme/features/debug/pexels/PexelsViewModel.kt`
- Test: `app/src/test/java/com/mamba/picme/features/debug/pexels/PexelsViewModelTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.mamba.picme.features.debug.pexels

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException

@OptIn(ExperimentalCoroutinesApi::class)
class PexelsViewModelTest {

    private val api: PexelsApi = mockk()
    private val keyStore: PexelsKeyStore = mockk(relaxUnitFun = true)
    private val imageSaver: PexelsImageSaver = mockk()
    private lateinit var scope: TestScope

    private fun photo(id: Long) = PexelsPhoto(
        id = id,
        src = PexelsSrc(large2x = "https://img/$id/large2x.jpg", medium = "https://img/$id/medium.jpg")
    )

    private fun response(ids: List<Long>, nextPage: String? = "next") =
        PexelsSearchResponse(photos = ids.map(::photo), page = 1, nextPage = nextPage)

    @Before
    fun setUp() {
        scope = TestScope(UnconfinedTestDispatcher())
    }

    private fun newViewModel(): PexelsViewModel =
        PexelsViewModel(api, keyStore, imageSaver, scope)

    @Test
    fun `no key stored starts at NoKey`() {
        every { keyStore.getKey() } returns null
        val vm = newViewModel()
        assertEquals(PexelsUiState.NoKey(), vm.uiState.value)
    }

    @Test
    fun `key stored auto loads curated into Ready`() {
        every { keyStore.getKey() } returns "key"
        coEvery { api.curated("key", 1) } returns response(listOf(1L, 2L))
        val vm = newViewModel()
        val state = vm.uiState.value as PexelsUiState.Ready
        assertEquals(listOf(1L, 2L), state.photos.map { it.id })
        assertFalse(state.endReached)
    }

    @Test
    fun `saveKey stores key and loads curated`() {
        every { keyStore.getKey() } returns null andThen "key"
        coEvery { api.curated("key", 1) } returns response(listOf(1L))
        val vm = newViewModel()
        vm.saveKey("  key  ")
        verify { keyStore.saveKey("  key  ") }
        assertTrue(vm.uiState.value is PexelsUiState.Ready)
    }

    @Test
    fun `search replaces photos and uses query endpoint`() {
        every { keyStore.getKey() } returns "key"
        coEvery { api.curated("key", 1) } returns response(listOf(1L))
        coEvery { api.search("key", "雪山", 1) } returns response(listOf(9L))
        val vm = newViewModel()
        vm.search("雪山")
        val state = vm.uiState.value as PexelsUiState.Ready
        assertEquals(listOf(9L), state.photos.map { it.id })
    }

    @Test
    fun `blank search falls back to curated`() {
        every { keyStore.getKey() } returns "key"
        coEvery { api.curated("key", 1) } returns response(listOf(1L))
        val vm = newViewModel()
        vm.search("   ")
        coVerify(exactly = 2) { api.curated("key", 1) }
    }

    @Test
    fun `loadMore appends next page`() {
        every { keyStore.getKey() } returns "key"
        coEvery { api.curated("key", 1) } returns response(listOf(1L))
        coEvery { api.curated("key", 2) } returns response(listOf(2L), nextPage = null)
        val vm = newViewModel()
        vm.loadMore()
        val state = vm.uiState.value as PexelsUiState.Ready
        assertEquals(listOf(1L, 2L), state.photos.map { it.id })
        assertTrue(state.endReached)
    }

    @Test
    fun `loadMore is no-op when endReached`() {
        every { keyStore.getKey() } returns "key"
        coEvery { api.curated("key", 1) } returns response(listOf(1L), nextPage = null)
        val vm = newViewModel()
        vm.loadMore()
        coVerify(exactly = 1) { api.curated("key", 1) }
    }

    @Test
    fun `toggleSelect adds then removes`() {
        every { keyStore.getKey() } returns "key"
        coEvery { api.curated("key", 1) } returns response(listOf(1L, 2L))
        val vm = newViewModel()
        vm.toggleSelect(1L)
        assertEquals(setOf(1L), (vm.uiState.value as PexelsUiState.Ready).selectedIds)
        vm.toggleSelect(1L)
        assertTrue((vm.uiState.value as PexelsUiState.Ready).selectedIds.isEmpty())
    }

    @Test
    fun `downloadSelected saves each selected photo and clears selection`() {
        every { keyStore.getKey() } returns "key"
        coEvery { api.curated("key", 1) } returns response(listOf(1L, 2L))
        coEvery { imageSaver.save(any(), any()) } returns true
        val vm = newViewModel()
        vm.toggleSelect(2L)
        vm.downloadSelected()
        coVerify(exactly = 1) { imageSaver.save(2L, "https://img/2/large2x.jpg") }
        val state = vm.uiState.value as PexelsUiState.Ready
        assertTrue(state.selectedIds.isEmpty())
        assertFalse(state.downloading)
    }

    @Test
    fun `downloadBatch paginates until enough photos`() {
        every { keyStore.getKey() } returns "key"
        coEvery { api.curated("key", 1) } returns response(listOf(1L))
        coEvery { api.curated("key", 2) } returns response(listOf(2L), nextPage = null)
        coEvery { imageSaver.save(any(), any()) } returns true
        val vm = newViewModel()
        vm.downloadBatch(2)
        coVerify(exactly = 1) { imageSaver.save(1L, any()) }
        coVerify(exactly = 1) { imageSaver.save(2L, any()) }
    }

    @Test
    fun `401 clears key and falls back to NoKey with invalidPrevious`() {
        every { keyStore.getKey() } returns "bad"
        coEvery { api.curated("bad", 1) } throws mockk<HttpException> {
            every { code() } returns 401
        }
        val vm = newViewModel()
        verify { keyStore.clear() }
        assertEquals(PexelsUiState.NoKey(invalidPrevious = true), vm.uiState.value)
    }

    @Test
    fun `429 maps to RATE_LIMITED error`() {
        every { keyStore.getKey() } returns "key"
        coEvery { api.curated("key", 1) } throws mockk<HttpException> {
            every { code() } returns 429
        }
        val vm = newViewModel()
        assertEquals(PexelsUiState.Error(PexelsErrorKind.RATE_LIMITED), vm.uiState.value)
    }

    @Test
    fun `network exception maps to NETWORK error`() {
        every { keyStore.getKey() } returns "key"
        coEvery { api.curated("key", 1) } throws java.io.IOException("timeout")
        val vm = newViewModel()
        assertEquals(PexelsUiState.Error(PexelsErrorKind.NETWORK), vm.uiState.value)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.debug.pexels.PexelsViewModelTest"`
Expected: FAIL（`PexelsViewModel` 等未定义，编译错误）

- [ ] **Step 3: 实现 PexelsUiState.kt**

```kotlin
package com.mamba.picme.features.debug.pexels

/** 错误枚举优于字符串：UI 层映射到 stringResource（[I18N] 红线） */
enum class PexelsErrorKind { INVALID_KEY, RATE_LIMITED, NETWORK }

sealed interface PexelsUiState {

    /** 未配置 API Key；invalidPrevious=true 表示上一个 Key 被 401 拒绝 */
    data class NoKey(val invalidPrevious: Boolean = false) : PexelsUiState

    data object Loading : PexelsUiState

    data class Ready(
        val photos: List<PexelsPhoto>,
        val selectedIds: Set<Long> = emptySet(),
        val page: Int = 1,
        val endReached: Boolean = false,
        val loadingMore: Boolean = false,
        val downloading: Boolean = false,
        val downloadProgress: String = ""
    ) : PexelsUiState

    data class Error(val kind: PexelsErrorKind) : PexelsUiState
}

/** 一次性事件（Toast/Snackbar），不驻留状态 */
sealed interface PexelsEvent {
    data class DownloadCompleted(val success: Int, val total: Int) : PexelsEvent
}
```

- [ ] **Step 4: 实现 PexelsViewModel.kt**

```kotlin
package com.mamba.picme.features.debug.pexels

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException

/**
 * Pexels 图库状态编排。
 * 构造函数显式注入全部依赖（Agent First：显式优于隐式），scope 由调用方注入便于测试。
 */
class PexelsViewModel(
    private val api: PexelsApi,
    private val keyStore: PexelsKeyStore,
    private val imageSaver: PexelsImageSaver,
    private val scope: CoroutineScope
) {
    private val _uiState = MutableStateFlow<PexelsUiState>(PexelsUiState.Loading)
    val uiState = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<PexelsEvent>()
    val events = _events.asSharedFlow()

    /** null = curated 精选；非 null = 搜索关键词 */
    private var currentQuery: String? = null

    init {
        if (keyStore.getKey() == null) {
            _uiState.value = PexelsUiState.NoKey()
        } else {
            loadCurated()
        }
    }

    fun saveKey(key: String) {
        if (key.isBlank()) return
        keyStore.saveKey(key)
        loadCurated()
    }

    fun clearKey() {
        keyStore.clear()
        _uiState.value = PexelsUiState.NoKey()
    }

    fun loadCurated() {
        currentQuery = null
        scope.launch { loadFirstPage() }
    }

    fun search(query: String) {
        currentQuery = query.trim().ifBlank { null }
        scope.launch { loadFirstPage() }
    }

    fun retry() {
        if (_uiState.value is PexelsUiState.Error) {
            scope.launch { loadFirstPage() }
        }
    }

    fun loadMore() {
        val ready = _uiState.value as? PexelsUiState.Ready ?: return
        if (ready.endReached || ready.loadingMore || ready.downloading) return
        scope.launch { loadPage(ready.page + 1, append = true) }
    }

    fun toggleSelect(photoId: Long) {
        val ready = _uiState.value as? PexelsUiState.Ready ?: return
        val selected = ready.selectedIds.toMutableSet()
        if (!selected.remove(photoId)) selected.add(photoId)
        _uiState.value = ready.copy(selectedIds = selected)
    }

    fun downloadSelected() {
        val ready = _uiState.value as? PexelsUiState.Ready ?: return
        if (ready.downloading || ready.selectedIds.isEmpty()) return
        val targets = ready.photos.filter { it.id in ready.selectedIds }
        scope.launch { downloadPhotos(targets) }
    }

    /** 批量下载当前列表前 count 张；已加载不足时自动翻页补足 */
    fun downloadBatch(count: Int) {
        val ready = _uiState.value as? PexelsUiState.Ready ?: return
        if (ready.downloading) return
        scope.launch {
            var current = ready
            while (current.photos.size < count && !current.endReached) {
                loadPage(current.page + 1, append = true)
                current = _uiState.value as? PexelsUiState.Ready ?: return@launch
            }
            downloadPhotos(current.photos.take(count))
        }
    }

    internal suspend fun loadFirstPage() {
        _uiState.value = PexelsUiState.Loading
        loadPage(page = 1, append = false)
    }

    internal suspend fun loadPage(page: Int, append: Boolean) {
        val key = keyStore.getKey()
        if (key == null) {
            _uiState.value = PexelsUiState.NoKey()
            return
        }
        val previous = _uiState.value as? PexelsUiState.Ready
        if (append && previous != null) {
            _uiState.value = previous.copy(loadingMore = true)
        }
        try {
            val query = currentQuery
            val response = if (query == null) {
                api.curated(key, page)
            } else {
                api.search(key, query, page)
            }
            _uiState.value = PexelsUiState.Ready(
                photos = if (append) previous?.photos.orEmpty() + response.photos else response.photos,
                selectedIds = if (append) previous?.selectedIds.orEmpty() else emptySet(),
                page = page,
                endReached = response.nextPage == null || response.photos.isEmpty()
            )
        } catch (e: HttpException) {
            handleHttpError(e.code())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _uiState.value = PexelsUiState.Error(PexelsErrorKind.NETWORK)
        }
    }

    private fun handleHttpError(code: Int) {
        when (code) {
            401 -> {
                keyStore.clear()
                _uiState.value = PexelsUiState.NoKey(invalidPrevious = true)
            }

            429 -> _uiState.value = PexelsUiState.Error(PexelsErrorKind.RATE_LIMITED)
            else -> _uiState.value = PexelsUiState.Error(PexelsErrorKind.NETWORK)
        }
    }

    internal suspend fun downloadPhotos(targets: List<PexelsPhoto>) {
        val ready = _uiState.value as? PexelsUiState.Ready ?: return
        _uiState.value = ready.copy(downloading = true, downloadProgress = "0/${targets.size}")
        var success = 0
        targets.forEachIndexed { index, photo ->
            if (imageSaver.save(photo.id, photo.src.large2x)) success++
            val current = _uiState.value as? PexelsUiState.Ready ?: return
            _uiState.value = current.copy(downloadProgress = "${index + 1}/${targets.size}")
        }
        val current = _uiState.value as? PexelsUiState.Ready ?: return
        _uiState.value = current.copy(
            downloading = false,
            downloadProgress = "",
            selectedIds = emptySet()
        )
        _events.emit(PexelsEvent.DownloadCompleted(success, targets.size))
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.debug.pexels.PexelsViewModelTest"`
Expected: PASS（13 tests）

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/debug/pexels/PexelsUiState.kt \
        app/src/main/java/com/mamba/picme/features/debug/pexels/PexelsViewModel.kt \
        app/src/test/java/com/mamba/picme/features/debug/pexels/PexelsViewModelTest.kt
git commit -m "feat(debug): PexelsViewModel 状态机（搜索/分页/选择/下载）+ 13 项单测"
```

---

### Task 6: strings.xml 四语文案

**Files:**
- Modify: `app/src/main/res/values/strings.xml`（在 `data_generation` 行后插入）
- Modify: `app/src/main/res/values-zh/strings.xml`（同上位置）
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`（同上位置）
- Modify: `app/src/main/res/values-zh-rTW/strings.xml`（同上位置）

- [ ] **Step 1: values/strings.xml（英文）**

在 `<string name="data_generation">Data Generation</string>` 行后插入：

```xml
    <string name="debug_tab_generate">Batch Generate</string>
    <string name="debug_tab_pexels">Pexels Gallery</string>
    <string name="pexels_api_key_hint">Enter Pexels API Key</string>
    <string name="pexels_api_key_save">Save</string>
    <string name="pexels_api_key_configured">API Key configured</string>
    <string name="pexels_api_key_change">Change</string>
    <string name="pexels_api_key_invalid">Invalid API Key, please re-enter</string>
    <string name="pexels_get_key">Get a free key at pexels.com/api</string>
    <string name="pexels_search_hint">Search keywords (empty = curated)</string>
    <string name="pexels_attribution">Photos provided by Pexels</string>
    <string name="pexels_selected_count">%1$d selected</string>
    <string name="pexels_download_selected">Download Selected</string>
    <string name="pexels_download_batch">Batch %1$d</string>
    <string name="pexels_downloading">Downloading %1$s</string>
    <string name="pexels_download_done">Done: %1$d/%2$d saved</string>
    <string name="pexels_error_network">Network error, please retry</string>
    <string name="pexels_error_rate_limited">Pexels rate limit reached (200/hour), try later</string>
    <string name="pexels_empty">No results</string>
    <string name="pexels_retry">Retry</string>
    <string name="pexels_batch_size">Batch size</string>
    <string name="pexels_photo_desc">Photo by %1$s</string>
```

- [ ] **Step 2: values-zh/strings.xml（简体中文）**

在 `<string name="data_generation">数据生成</string>` 行后插入：

```xml
    <string name="debug_tab_generate">批量生成</string>
    <string name="debug_tab_pexels">Pexels 图库</string>
    <string name="pexels_api_key_hint">输入 Pexels API Key</string>
    <string name="pexels_api_key_save">保存</string>
    <string name="pexels_api_key_configured">API Key 已配置</string>
    <string name="pexels_api_key_change">修改</string>
    <string name="pexels_api_key_invalid">Key 无效，请重新输入</string>
    <string name="pexels_get_key">前往 pexels.com/api 免费申请 Key</string>
    <string name="pexels_search_hint">搜索关键词，留空加载精选</string>
    <string name="pexels_attribution">Photos provided by Pexels</string>
    <string name="pexels_selected_count">已选 %1$d 张</string>
    <string name="pexels_download_selected">下载所选</string>
    <string name="pexels_download_batch">批量 %1$d 张</string>
    <string name="pexels_downloading">下载中 %1$s</string>
    <string name="pexels_download_done">完成：成功保存 %1$d/%2$d 张</string>
    <string name="pexels_error_network">网络错误，请重试</string>
    <string name="pexels_error_rate_limited">Pexels 额度已用尽（200 次/小时），请稍后再试</string>
    <string name="pexels_empty">无结果</string>
    <string name="pexels_retry">重试</string>
    <string name="pexels_batch_size">批量数量</string>
    <string name="pexels_photo_desc">%1$s 拍摄的照片</string>
```

- [ ] **Step 3: values-zh-rCN/strings.xml**：与 Step 2 内容完全相同。

- [ ] **Step 4: values-zh-rTW/strings.xml（繁体中文）**

在 `<string name="data_generation">數據生成</string>` 行后插入：

```xml
    <string name="debug_tab_generate">批量產生</string>
    <string name="debug_tab_pexels">Pexels 圖庫</string>
    <string name="pexels_api_key_hint">輸入 Pexels API Key</string>
    <string name="pexels_api_key_save">儲存</string>
    <string name="pexels_api_key_configured">API Key 已配置</string>
    <string name="pexels_api_key_change">修改</string>
    <string name="pexels_api_key_invalid">Key 無效，請重新輸入</string>
    <string name="pexels_get_key">前往 pexels.com/api 免費申請 Key</string>
    <string name="pexels_search_hint">搜尋關鍵詞，留空載入精選</string>
    <string name="pexels_attribution">Photos provided by Pexels</string>
    <string name="pexels_selected_count">已選 %1$d 張</string>
    <string name="pexels_download_selected">下載所選</string>
    <string name="pexels_download_batch">批量 %1$d 張</string>
    <string name="pexels_downloading">下載中 %1$s</string>
    <string name="pexels_download_done">完成：成功儲存 %1$d/%2$d 張</string>
    <string name="pexels_error_network">網路錯誤，請重試</string>
    <string name="pexels_error_rate_limited">Pexels 額度已用盡（200 次/小時），請稍後再試</string>
    <string name="pexels_empty">無結果</string>
    <string name="pexels_retry">重試</string>
    <string name="pexels_batch_size">批量數量</string>
    <string name="pexels_photo_desc">%1$s 拍攝的照片</string>
```

- [ ] **Step 5: 编译确认资源无误**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-zh/strings.xml \
        app/src/main/res/values-zh-rCN/strings.xml app/src/main/res/values-zh-rTW/strings.xml
git commit -m "feat(debug): Pexels 图库四语文案"
```

---

### Task 7: PexelsSection.kt Compose UI

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/debug/pexels/PexelsSection.kt`

布局 A：Key 状态条 + 搜索栏 + 3 列网格 + 底部署名 + 底部固定下载栏。

- [ ] **Step 1: 实现 UI**

```kotlin
package com.mamba.picme.features.debug.pexels

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mamba.picme.R

private val BATCH_SIZES = listOf(10, 20, 50)
private const val DEFAULT_BATCH_SIZE = 20

/** Pexels 图库 Tab 内容（布局 A：搜索栏 + 网格 + 底部下载栏） */
@Composable
fun PexelsSection(
    viewModel: PexelsViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is PexelsEvent.DownloadCompleted -> Toast.makeText(
                    context,
                    context.getString(
                        R.string.pexels_download_done,
                        event.success,
                        event.total
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        when (val s = state) {
            is PexelsUiState.NoKey -> PexelsKeyInput(
                invalidPrevious = s.invalidPrevious,
                onSave = viewModel::saveKey
            )

            else -> {
                PexelsTopBar(
                    onSearch = viewModel::search,
                    onChangeKey = viewModel::clearKey
                )
                Box(modifier = Modifier.weight(1f)) {
                    when (s) {
                        PexelsUiState.Loading -> PexelsLoading()
                        is PexelsUiState.Error -> PexelsError(
                            kind = s.kind,
                            onRetry = viewModel::retry
                        )

                        is PexelsUiState.Ready -> PexelsPhotoGrid(
                            state = s,
                            onToggle = viewModel::toggleSelect,
                            onLoadMore = viewModel::loadMore
                        )

                        else -> Unit
                    }
                }
                PexelsAttribution()
                if (s is PexelsUiState.Ready) {
                    PexelsDownloadBar(
                        state = s,
                        onDownloadSelected = viewModel::downloadSelected,
                        onDownloadBatch = viewModel::downloadBatch
                    )
                }
            }
        }
    }
}

@Composable
private fun PexelsKeyInput(
    invalidPrevious: Boolean,
    onSave: (String) -> Unit
) {
    var key by remember { mutableStateOf("") }
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (invalidPrevious) {
            Text(
                stringResource(R.string.pexels_api_key_invalid),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.pexels_api_key_hint)) },
            singleLine = true
        )
        Button(
            onClick = { onSave(key) },
            enabled = key.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.pexels_api_key_save))
        }
        TextButton(
            onClick = { uriHandler.openUri("https://www.pexels.com/api/") },
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text(stringResource(R.string.pexels_get_key), fontSize = 12.sp)
        }
    }
}

@Composable
private fun PexelsTopBar(
    onSearch: (String) -> Unit,
    onChangeKey: () -> Unit
) {
    var query by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.pexels_api_key_configured),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onChangeKey) {
                Text(stringResource(R.string.pexels_api_key_change), fontSize = 12.sp)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(stringResource(R.string.pexels_search_hint), fontSize = 12.sp)
                },
                singleLine = true,
                shape = RoundedCornerShape(8.dp)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { onSearch(query) }) {
                Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun PexelsLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun PexelsError(
    kind: PexelsErrorKind,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(
                when (kind) {
                    PexelsErrorKind.RATE_LIMITED -> R.string.pexels_error_rate_limited
                    else -> R.string.pexels_error_network
                }
            ),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onRetry) {
            Text(stringResource(R.string.pexels_retry))
        }
    }
}

@Composable
private fun PexelsPhotoGrid(
    state: PexelsUiState.Ready,
    onToggle: (Long) -> Unit,
    onLoadMore: () -> Unit
) {
    if (state.photos.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.pexels_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val gridState = rememberLazyGridState()
    val shouldLoadMore by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 6
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) onLoadMore()
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(state.photos, key = { it.id }) { photo ->
            PexelsPhotoCell(
                photo = photo,
                selected = photo.id in state.selectedIds,
                onClick = { onToggle(photo.id) }
            )
        }
        if (state.loadingMore) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
        }
    }
}

@Composable
private fun PexelsPhotoCell(
    photo: PexelsPhoto,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = photo.src.medium,
            contentDescription = stringResource(R.string.pexels_photo_desc, photo.photographer),
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        if (selected) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
            ) {}
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(22.dp)
            )
        }
    }
}

@Composable
private fun PexelsAttribution() {
    Text(
        stringResource(R.string.pexels_attribution),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        textAlign = TextAlign.Center
    )
}

@Composable
private fun PexelsDownloadBar(
    state: PexelsUiState.Ready,
    onDownloadSelected: () -> Unit,
    onDownloadBatch: (Int) -> Unit
) {
    var batchSize by remember { mutableIntStateOf(DEFAULT_BATCH_SIZE) }
    var batchMenuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            if (state.downloading) {
                stringResource(R.string.pexels_downloading, state.downloadProgress)
            } else {
                stringResource(R.string.pexels_selected_count, state.selectedIds.size)
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        Button(
            onClick = onDownloadSelected,
            enabled = state.selectedIds.isNotEmpty() && !state.downloading
        ) {
            Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.pexels_download_selected), fontSize = 12.sp)
        }
        Box {
            OutlinedButton(
                onClick = { onDownloadBatch(batchSize) },
                enabled = !state.downloading
            ) {
                Text(
                    stringResource(R.string.pexels_download_batch, batchSize),
                    fontSize = 12.sp
                )
            }
        }
        Box {
            TextButton(onClick = { batchMenuOpen = true }, enabled = !state.downloading) {
                Text(batchSize.toString(), fontSize = 12.sp)
            }
            DropdownMenu(
                expanded = batchMenuOpen,
                onDismissRequest = { batchMenuOpen = false }
            ) {
                BATCH_SIZES.forEach { size ->
                    DropdownMenuItem(
                        text = { Text(size.toString()) },
                        onClick = {
                            batchSize = size
                            batchMenuOpen = false
                        }
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 2: 编译确认**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/debug/pexels/PexelsSection.kt
git commit -m "feat(debug): PexelsSection UI（Key 输入/搜索栏/网格/下载栏，布局 A）"
```

---

### Task 8: DebugScreen 双 Tab 改版

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/debug/DebugScreen.kt`

- [ ] **Step 1: DebugScreen 入口创建 PexelsViewModel 并下传**

在 `DebugScreen(...)` 函数体中，`val allMedia by ...` 行之后插入：

```kotlin
    val pexelsViewModel = remember {
        PexelsViewModel(
            api = PexelsApi.create(),
            keyStore = PexelsKeyStore(context.applicationContext),
            imageSaver = PexelsImageSaver { photoId, imageUrl ->
                SampleDataGenerator.savePexelsPhoto(context, app.repository, photoId, imageUrl)
            },
            scope = app.applicationScope
        )
    }
```

`DebugContent(...)` 调用处追加参数 `pexelsViewModel = pexelsViewModel`。

新增 import：

```kotlin
import com.mamba.picme.features.debug.pexels.PexelsApi
import com.mamba.picme.features.debug.pexels.PexelsImageSaver
import com.mamba.picme.features.debug.pexels.PexelsKeyStore
import com.mamba.picme.features.debug.pexels.PexelsSection
import com.mamba.picme.features.debug.pexels.PexelsViewModel
```

- [ ] **Step 2: DebugContent 改双 Tab 结构**

`DebugContent` 签名追加 `pexelsViewModel: PexelsViewModel` 参数。Scaffold 内容区改为：

```kotlin
    ) { innerPadding ->
        var selectedTab by rememberSaveable { mutableIntStateOf(0) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.debug_tab_generate)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.debug_tab_pexels)) }
                )
            }
            when (selectedTab) {
                0 -> GenerateTabContent(
                    isGenerating = isGenerating,
                    isPaused = isPaused,
                    progress = progress,
                    filterText = filterText,
                    filteredLogs = filteredLogs,
                    onFilterTextChange = { filterText = it },
                    onPauseResume = onPauseResume,
                    onStop = onStop,
                    onPopulatePerson = onPopulatePerson,
                    onPopulateLandscape = onPopulateLandscape,
                    onPopulateSwimwear = onPopulateSwimwear,
                    onPopulateSexy = onPopulateSexy,
                    onClearData = onClearData,
                    onScreenshot = onScreenshot
                )

                1 -> PexelsSection(viewModel = pexelsViewModel)
            }
        }
    }
```

新增 import：

```kotlin
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
```

- [ ] **Step 3: 抽出现有内容为 GenerateTabContent**

把原 `DebugContent` 中 `Column(verticalScroll...)` 内的全部内容（GenerationStatusCard / data_generation 标题 / DataGenerationButtons / 截图 / LogWindow）原样移到新私有组件：

```kotlin
@Composable
private fun GenerateTabContent(
    isGenerating: Boolean,
    isPaused: Boolean,
    progress: String,
    filterText: String,
    filteredLogs: List<String>,
    onFilterTextChange: (String) -> Unit,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    onPopulatePerson: () -> Unit,
    onPopulateLandscape: () -> Unit,
    onPopulateSwimwear: () -> Unit,
    onPopulateSexy: () -> Unit,
    onClearData: () -> Unit,
    onScreenshot: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (isGenerating) {
            GenerationStatusCard(
                progress = progress,
                isPaused = isPaused,
                onPauseResume = onPauseResume,
                onStop = onStop
            )
        }

        Text(
            stringResource(R.string.data_generation),
            style = MaterialTheme.typography.titleSmall
        )

        DataGenerationButtons(
            isGenerating = isGenerating,
            onPopulatePerson = onPopulatePerson,
            onPopulateLandscape = onPopulateLandscape,
            onPopulateSwimwear = onPopulateSwimwear,
            onPopulateSexy = onPopulateSexy,
            onClearData = onClearData
        )

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )

        Text(
            stringResource(R.string.screenshot),
            style = MaterialTheme.typography.titleSmall
        )

        Button(
            onClick = onScreenshot,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Save, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.screenshot))
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )

        LogWindow(
            filterText = filterText,
            onFilterTextChange = onFilterTextChange,
            filteredLogs = filteredLogs
        )
    }
}
```

同时删除 `DebugContent` 中对应的 `@Suppress("LongMethod")`（内容已拆分）。`filterText`/`filteredLogs` 的 remember 逻辑保留在 `DebugContent` 内。

- [ ] **Step 4: 编译确认**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/debug/DebugScreen.kt
git commit -m "feat(debug): DebugScreen 双 Tab 改版（批量生成 / Pexels 图库）"
```

---

### Task 9: 全量验证 + 文档更新

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/debug/AGENTS.md`

- [ ] **Step 1: 全量单测**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.debug.pexels.*"`
Expected: PASS（19 tests：2 models + 4 keystore + 13 viewmodel）

- [ ] **Step 2: 完整编译 + 安装**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 更新 debug AGENTS.md**

在 `## 2. 技术实现规范` 中新增小节（插在 `### 2.2 图片搜索与下载` 之后）：

```markdown
### 2.2a Pexels 图库（Pexels API 测试图下载）

**技术规范**:
- **官方 API**: Retrofit 接口 `PexelsApi`（`v1/search` / `v1/curated`），Key 以 `@Header("Authorization")` 逐请求传入
- **Key 存取**: `PexelsKeyStore`（独立 SharedPreferences `debug_pexels_prefs`），仅存本地，不进日志不进 git
- **状态机**: `PexelsViewModel` + sealed `PexelsUiState`（NoKey / Loading / Ready / Error），错误用 `PexelsErrorKind` 枚举（401 清 Key 回 NoKey；429 提示限流）
- **下载链路**: 复用 `SampleDataGenerator.savePexelsPhoto()`，`TEST_PEXELS_` 前缀，兼容 `clearTestData()`
- **限流**: 免费档 200 次/小时、20,000 次/月；per_page=30
- **署名**: 页面底部常驻「Photos provided by Pexels」（API 使用条款要求）
- **设计文档**: `docs/superpowers/specs/2026-08-06-pexels-test-image-download-design.md`
```

并在 `## 4. 常见陷阱检查清单` 追加：

```markdown
- [ ] Pexels API Key 是否仅存本地 SharedPreferences？（严禁入库/进日志）
- [ ] 401/429 是否分别走 NoKey 回退与限流提示？（不可笼统报网络错误）
- [ ] Pexels 下载图片是否带 TEST_PEXELS_ 前缀？（兼容 TEST_ 前缀清理）
- [ ] 页面是否保留「Photos provided by Pexels」署名？（API 条款要求）
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/debug/AGENTS.md
git commit -m "docs(debug): AGENTS.md 新增 Pexels 图库小节与检查清单"
```

- [ ] **Step 5: 真机闭环验证（手动）**

1. `adb install` 装包 → 打开 Debug 页 → 切到「Pexels 图库」Tab
2. 未配 Key → 显示输入框；去 https://www.pexels.com/api/ 申请免费 Key 填入
3. 默认加载精选网格；搜索「雪山」→ 结果刷新
4. 勾选 3 张 →「下载所选」→ Toast 显示成功数；「批量 20」→ 进度 `n/20`
5. 相册确认 `TEST_PEXELS_` 前缀图片已入 `Pictures/PoLang`
6. 回 Tab 1「清除测试数据」→ 确认 Pexels 测试图一并清理
7. （可选）填错误 Key 验证 401 → 回 NoKey 并提示无效

---

## Self-Review 记录

- **Spec 覆盖**：spec §3 全部组件 → Task 1-5、7；spec §3.2 数据流（curated 默认/搜索/翻页/批量补足/下载链路）→ Task 5 Step 4 + 测试；spec §4 布局 A → Task 7；spec §5 错误处理 → Task 5（401/429/网络）+ Task 7（UI 文案）；spec §6 [I18N] → Task 6 四语；spec §7 测试计划 → Task 1/2/5 单测 + Task 9 闭环；spec §8 交付清单（含 AGENTS.md）→ Task 9。
- **类型一致性**：`PexelsUiState.NoKey(invalidPrevious)`、`PexelsErrorKind`、`PexelsEvent.DownloadCompleted`、`PexelsImageSaver.save(photoId, imageUrl)`、`savePexelsPhoto(context, repository, photoId, imageUrl)` 在 Task 5/7/8 间签名一致；`api.curated(key, page)` / `api.search(key, query, page)` 使用默认 per_page，与测试 mock 一致。
- **无占位符**：所有代码步骤含完整实现。
