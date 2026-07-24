# Gallery Query Handlers (P0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给端侧 JS 沙箱新增两个只读 handler(`gallery.query` / `media.meta`),与已有 `gallery.summary` 形成「全局统计 / 子集过滤 / 单张细节」三层闭环,让远程 LLM 生成的 JS 能做真正的组合计算(占比、分布、跨维度计数)。

**Architecture:** 复用现有 `MediaDao` 的结构化查询(searchByTimeRange / searchByHasFace / getAllMediaNow),多维过滤提取为纯内存函数(纯 JVM 可测)。因 `MediaEntity` 位于 app/data 层(runtime-core 不可见),新 UseCase + JsValue 转换均落在 **app 层**,与已有 `GetGallerySummaryUseCase` 对称。handler 仍走现有 `syncHandler` + `runBlocking` 模式(与 `gallery.summary` 一致),复用 `JsRuntime` 超时熔断。

**Tech Stack:** Kotlin,Mozilla Rhino(已集成),Room `MediaDao`,Coroutines,JsValue(runtime-core)。

---

## 范围与红线

**做(YAGNI 最小闭环)**:
- `gallery.query(filter)` → `{ids, total}`(结构化过滤,只回 id + 计数)
- `media.meta(id)` → 单张白名单元数据
- `gallery.summary` 已有,不动

**不做**:
- ❌ 任何写操作(留 P2)
- ❌ 远程 JS 下载执行(过审红线)
- ❌ `tag.stats` / `gallery.groupBy` / `timeline` —— 用 `gallery.query` + JS 侧循环即可组合,YAGNI
- ❌ 新 UI 字符串(本次无用户可见文案,`@Tool` 描述面向 LLM 不走 i18n)

**隐私白名单(P0 铁律)**:
- `gallery.query` 只回 `ids:Long[]` + `total:Int`,不回任何元数据。
- `media.meta` 白名单:`id, type, captureMs, fileName, labels[], locationName, hasFace, faceId`。
- `media.meta` **不回**:`uri`(本地路径)、`latitude/longitude`(GPS)、`ocrText`(OCR 原文)、`semanticEmbedding/faceRoiResult`(向量/ROI)、`mlKitLabels`(用中文 `labels`/`mlKitLabelsZh` 即可)。

---

## File Structure

| 文件 | 动作 | 职责 |
|---|---|---|
| `app/.../domain/model/GalleryQuery.kt` | 新增 | `QueryFilter` + `GalleryQueryResult` data class + `List<MediaEntity>.applyFilter()` 纯函数 |
| `app/.../domain/usecase/QueryGalleryMediaUseCase.kt` | 新增 | 只读 UseCase:候选集(DAO)→ `applyFilter` → 截断 → Result |
| `app/.../features/chat/js/GalleryJs.kt` | 新增 | JsValue ↔ QueryFilter / Result / MediaEntity 转换(app 层,因依赖 MediaEntity) |
| `app/.../features/chat/ChatViewModelDependencies.kt` | 改 | 加 `queryGalleryMediaUseCase` 字段 |
| `app/.../di/AppContainer.kt` | 改 | 构造 UseCase + 装配进 dependencies |
| `app/.../features/chat/ChatViewModel.kt` | 改 | 字段 + `onRunScript` 注册两个 handler |
| `runtime-core/.../tool/ChatToolService.kt` | 改 | 更新 `run_gallery_script` @Tool 描述(让 LLM 知道新 handler) |
| `app/src/test/.../domain/model/GalleryQueryFilterTest.kt` | 新增 | `applyFilter` 纯逻辑(多维 AND / 边界) |
| `app/src/test/.../features/chat/js/GalleryJsTest.kt` | 新增 | JsValue 解析 + 契约转换 |

---

## 数据契约

**`gallery.query(filter)`** — JS 侧:
```js
bridge.call('gallery.query', {
  label: '猫',        // 可选,labels 子串(大小写不敏感)
  ocr: '生日',        // 可选,ocrText 子串
  location: '北京',   // 可选,locationName 子串
  fromMs: 1717200000000,  // 可选,captureDate >=
  toMs:   1719792000000,  // 可选,captureDate <=
  hasFace: true,      // 可选
  limit: 100          // 可选,默认 200
})
// → { ids: [12, 34, ...], total: 87 }   // ids 已截断到 limit;total 为未截断真实命中数
```

**`media.meta(id)`** — JS 侧:
```js
bridge.call('media.meta', 12)
// → { id:12, type:'PHOTO', captureMs:1717200000000, fileName:'IMG_001.jpg',
//     labels:['猫','户外'], locationName:'北京·颐和园', hasFace:true, faceId:'p_3' }
```

**`gallery.summary`** — 已有,不变。

---

## Task 1: QueryFilter 模型 + applyFilter 纯函数(TDD)

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/model/GalleryQuery.kt`
- Test: `app/src/test/java/com/mamba/picme/domain/model/GalleryQueryFilterTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/mamba/picme/domain/model/GalleryQueryFilterTest.kt`:

```kotlin
package com.mamba.picme.domain.model

import com.mamba.picme.data.model.MediaEntity
import com.mamba.picme.agent.core.model.context.MediaType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GalleryQueryFilterTest {

    private fun media(
        id: Long,
        labels: String? = null,
        ocr: String? = null,
        location: String? = null,
        captureDate: Long = 1_000L,
        hasFace: Boolean = false,
    ) = MediaEntity(
        id = id, uri = "uri$id", type = MediaType.PHOTO, captureDate = captureDate,
        fileName = "f$id", labels = labels, ocrText = ocr, locationName = location, hasFace = hasFace,
    )

    @Test
    fun `empty filter returns all candidates`() {
        val list = listOf(media(1), media(2))
        assertEquals(listOf(1L, 2L), list.applyFilter(QueryFilter()).map { it.id })
    }

    @Test
    fun `label filter is case-insensitive substring`() {
        val list = listOf(media(1, labels = """["猫","户外"]"""), media(2, labels = """["食物"]"""))
        val got = list.applyFilter(QueryFilter(label = "猫"))
        assertEquals(listOf(1L), got.map { it.id })
    }

    @Test
    fun `time range filter inclusive`() {
        val list = listOf(media(1, captureDate = 500L), media(2, captureDate = 1_500L))
        val got = list.applyFilter(QueryFilter(fromMs = 1_000L, toMs = 2_000L))
        assertEquals(listOf(2L), got.map { it.id })
    }

    @Test
    fun `hasFace filter`() {
        val list = listOf(media(1, hasFace = true), media(2, hasFace = false))
        val got = list.applyFilter(QueryFilter(hasFace = true))
        assertEquals(listOf(1L), got.map { it.id })
    }

    @Test
    fun `multi-dimension AND`() {
        val list = listOf(
            media(1, labels = """["猫"]""", captureDate = 1_500L, hasFace = true),
            media(2, labels = """["猫"]""", captureDate = 1_500L, hasFace = false), // 落选:无脸
            media(3, labels = """["猫"]""", captureDate = 500L, hasFace = true),    // 落选:超时间
            media(4, labels = """["食物"]""", captureDate = 1_500L, hasFace = true),// 落选:非猫
        )
        val got = list.applyFilter(QueryFilter(label = "猫", fromMs = 1_000L, hasFace = true))
        assertEquals(listOf(1L), got.map { it.id })
    }

    @Test
    fun `no match returns empty`() {
        val list = listOf(media(1, labels = """["猫"]"""))
        assertTrue(list.applyFilter(QueryFilter(label = "不存在")).isEmpty())
    }

    @Test
    fun `null fields do not match substring filters`() {
        val list = listOf(media(1, labels = null))
        assertTrue(list.applyFilter(QueryFilter(label = "猫")).isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.model.GalleryQueryFilterTest"`
Expected: FAIL — `Unresolved reference: QueryFilter` / `applyFilter`.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/mamba/picme/domain/model/GalleryQuery.kt`:

```kotlin
package com.mamba.picme.domain.model

import com.mamba.picme.data.model.MediaEntity

/**
 * JS `gallery.query` 的过滤参数。全字段可选,多维 **AND** 组合。
 *
 * @param label    labels 子串匹配(大小写不敏感)。
 * @param ocr      ocrText 子串匹配。
 * @param location locationName 子串匹配。
 * @param fromMs   captureDate >= fromMs(毫秒)。
 * @param toMs     captureDate <= toMs(毫秒)。
 * @param hasFace  是否含人脸。
 * @param limit    返回 id 截断上限(防止爆量);[total] 仍为未截断真实命中数。
 */
data class QueryFilter(
    val label: String? = null,
    val ocr: String? = null,
    val location: String? = null,
    val fromMs: Long? = null,
    val toMs: Long? = null,
    val hasFace: Boolean? = null,
    val limit: Int = DEFAULT_LIMIT,
) {
    companion object {
        const val DEFAULT_LIMIT = 200
    }
}

/** `gallery.query` 结果:命中 id(已截断到 [QueryFilter.limit])+ 未截断的真实总数。 */
data class GalleryQueryResult(
    val ids: List<Long>,
    val total: Int,
)

/**
 * 在内存按 [filter] 过滤候选媒体(纯逻辑,多维 AND)。便于纯 JVM 单测,不触碰 Room/Android。
 *
 * 注:时间范围在 [QueryGalleryMediaUseCase] 已尽量由 DAO 预筛(走 searchByTimeRange 分支);
 * 此处仍兜底二次过滤——候选来自 searchByHasFace / getAllMediaNow 分支时需在此补齐时间条件。
 */
fun List<MediaEntity>.applyFilter(filter: QueryFilter): List<MediaEntity> =
    filter { m ->
        (filter.label == null || m.labels?.contains(filter.label, ignoreCase = true) == true) &&
            (filter.ocr == null || m.ocrText?.contains(filter.ocr, ignoreCase = true) == true) &&
            (filter.location == null || m.locationName?.contains(filter.location, ignoreCase = true) == true) &&
            (filter.fromMs == null || m.captureDate >= filter.fromMs) &&
            (filter.toMs == null || m.captureDate <= filter.toMs) &&
            (filter.hasFace == null || m.hasFace == filter.hasFace)
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.model.GalleryQueryFilterTest"`
Expected: PASS(7 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/model/GalleryQuery.kt \
        app/src/test/java/com/mamba/picme/domain/model/GalleryQueryFilterTest.kt
git commit -m "$(cat <<'EOF'
feat(gallery): QueryFilter 模型 + applyFilter 纯函数（JS gallery.query 数据面）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: QueryGalleryMediaUseCase

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/usecase/QueryGalleryMediaUseCase.kt`

> 说明:UseCase 调真实 `MediaDao`(Room),集成测试需设备(androidTest)。核心过滤逻辑已在 Task 1 纯 JVM 覆盖,本 Task 不加新单测(YAGNI;真机手验见 Task 7)。

- [ ] **Step 1: Write implementation**

Create `app/src/main/java/com/mamba/picme/domain/usecase/QueryGalleryMediaUseCase.kt`:

```kotlin
package com.mamba.picme.domain.usecase

import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.domain.model.GalleryQueryResult
import com.mamba.picme.domain.model.QueryFilter
import com.mamba.picme.domain.model.applyFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 只读相册结构化查询,供 JS `gallery.query` handler 使用。
 *
 * - 读-only:不写库、不触发扫描。
 * - 候选集策略:有时间范围 → searchByTimeRange(DAO 数字范围高效);
 *   否则有 hasFace=true → searchByHasFace;否则 getAllMediaNow。
 *   其余维度(label/ocr/location)在内存由 [applyFilter] 过滤。
 * - [GalleryQueryResult.ids] 截断到 [QueryFilter.limit];[total] 为未截断真实命中数。
 */
class QueryGalleryMediaUseCase(
    private val db: AppDatabase,
) {
    suspend operator fun invoke(filter: QueryFilter): GalleryQueryResult =
        withContext(Dispatchers.IO) {
            val candidates = when {
                filter.fromMs != null || filter.toMs != null ->
                    db.mediaDao().searchByTimeRange(
                        filter.fromMs ?: 0L,
                        filter.toMs ?: Long.MAX_VALUE,
                    )
                filter.hasFace == true ->
                    db.mediaDao().searchByHasFace()
                else ->
                    db.mediaDao().getAllMediaNow()
            }
            val matched = candidates.applyFilter(filter)
            GalleryQueryResult(
                ids = matched.take(filter.limit).map { it.id },
                total = matched.size,
            )
        }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/usecase/QueryGalleryMediaUseCase.kt
git commit -m "$(cat <<'EOF'
feat(gallery): QueryGalleryMediaUseCase 只读结构化查询

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: JsValue 转换层(TDD)

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/chat/js/GalleryJs.kt`
- Test: `app/src/test/java/com/mamba/picme/features/chat/js/GalleryJsTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/mamba/picme/features/chat/js/GalleryJsTest.kt`:

```kotlin
package com.mamba.picme.features.chat.js

import com.mamba.picme.agent.core.js.JsValue
import com.mamba.picme.data.model.MediaEntity
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.domain.model.GalleryQueryResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GalleryJsTest {

    @Test
    fun `parseQueryFilter reads all fields`() {
        val args = JsValue.Obj(
            linkedMapOf(
                "label" to JsValue.Str("猫"),
                "ocr" to JsValue.Str("生日"),
                "location" to JsValue.Str("北京"),
                "fromMs" to JsValue.Num(1000.0),
                "toMs" to JsValue.Num(2000.0),
                "hasFace" to JsValue.Bool(true),
                "limit" to JsValue.Num(50.0),
            )
        )
        val f = parseQueryFilter(args)
        assertEquals("猫", f.label)
        assertEquals(1000L, f.fromMs)
        assertEquals(2000L, f.toMs)
        assertEquals(true, f.hasFace)
        assertEquals(50, f.limit)
    }

    @Test
    fun `parseQueryFilter blank strings become null`() {
        val args = JsValue.Obj(linkedMapOf("label" to JsValue.Str("   ")))
        val f = parseQueryFilter(args)
        assertEquals(null, f.label)
        assertEquals(200, f.limit) // 默认
    }

    @Test
    fun `parseQueryFilter non-obj returns defaults`() {
        val f = parseQueryFilter(JsValue.Str("oops"))
        assertEquals(null, f.label)
        assertEquals(200, f.limit)
    }

    @Test
    fun `GalleryQueryResult toJsValue shape`() {
        val v = GalleryQueryResult(ids = listOf(1L, 2L), total = 2).toJsValue()
        val obj = (v as JsValue.Obj).entries
        assertEquals(listOf(1.0, 2.0), (obj["ids"] as JsValue.Arr).items.map { (it as JsValue.Num).value })
        assertEquals(2.0, (obj["total"] as JsValue.Num).value)
    }

    @Test
    fun `MediaEntity toMetaJsValue whitelist and labels parse`() {
        val m = MediaEntity(
            id = 12, uri = "content://x/12", type = MediaType.PHOTO, captureDate = 1_000L,
            fileName = "IMG_1.jpg", labels = """["猫","户外"]""", locationName = "北京",
            hasFace = true, faceId = "p_3",
        )
        val obj = (m.toMetaJsValue() as JsValue.Obj).entries
        assertEquals(12.0, (obj["id"] as JsValue.Num).value)
        assertEquals("PHOTO", (obj["type"] as JsValue.Str).value)
        assertEquals(listOf("猫", "户外"), (obj["labels"] as JsValue.Arr).items.map { (it as JsValue.Str).value })
        assertEquals("北京", (obj["locationName"] as JsValue.Str).value)
        assertEquals(true, (obj["hasFace"] as JsValue.Bool).value)
        assertEquals("p_3", (obj["faceId"] as JsValue.Str).value)
        // 隐私白名单:不含 uri / GPS / ocrText
        assert(!obj.containsKey("uri"))
        assert(!obj.containsKey("latitude"))
        assert(!obj.containsKey("longitude"))
        assert(!obj.containsKey("ocrText"))
    }

    @Test
    fun `MediaEntity toMetaJsValue null fields`() {
        val m = MediaEntity(id = 1, uri = "u", type = MediaType.PHOTO, captureDate = 1L, fileName = "f")
        val obj = (m.toMetaJsValue() as JsValue.Obj).entries
        assertEquals(JsValue.Null, obj["labels"])
        assertEquals(JsValue.Null, obj["locationName"])
        assertEquals(JsValue.Null, obj["faceId"])
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.js.GalleryJsTest"`
Expected: FAIL — `Unresolved reference: parseQueryFilter` / `toJsValue` / `toMetaJsValue`.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/mamba/picme/features/chat/js/GalleryJs.kt`:

```kotlin
package com.mamba.picme.features.chat.js

import com.mamba.picme.agent.core.js.JsValue
import com.mamba.picme.data.model.MediaEntity
import com.mamba.picme.domain.model.GalleryQueryResult
import com.mamba.picme.domain.model.QueryFilter

/**
 * JS ↔ 只读查询模型的双向转换(app 层)。
 *
 * 落在 app 层的原因:依赖 [MediaEntity](app/data 层),runtime-core 不可见
 * (对照 `GallerySummary.toJsValue()` 能放 runtime-core,因 GallerySummary 本就在 runtime-core)。
 *
 * - [parseQueryFilter]:JS `bridge.call('gallery.query', {...})` 的第二参 → [QueryFilter]。
 * - [toJsValue]:结果/元数据 → JsValue(回传 JS)。
 * 字段名小驼峰;数值转 Double(JS number)。
 */

/** JS 传入的 filter 对象 → QueryFilter(全可选,缺省/空串/类型不符一律走默认)。 */
fun parseQueryFilter(args: JsValue): QueryFilter {
    val obj = args as? JsValue.Obj ?: return QueryFilter()
    val e = obj.entries
    fun str(k: String) = (e[k] as? JsValue.Str)?.value?.takeIf { it.isNotBlank() }
    fun num(k: String) = (e[k] as? JsValue.Num)?.value?.toLong()
    fun bool(k: String) = (e[k] as? JsValue.Bool)?.value
    val limit = (e["limit"] as? JsValue.Num)?.value?.toInt()
    return QueryFilter(
        label = str("label"),
        ocr = str("ocr"),
        location = str("location"),
        fromMs = num("fromMs"),
        toMs = num("toMs"),
        hasFace = bool("hasFace"),
        limit = limit ?: QueryFilter.DEFAULT_LIMIT,
    )
}

/** GalleryQueryResult → `{ids:[...], total:N}`。 */
fun GalleryQueryResult.toJsValue(): JsValue.Obj = JsValue.Obj(
    linkedMapOf(
        "ids" to JsValue.Arr(ids.map { JsValue.Num(it.toDouble()) }),
        "total" to JsValue.Num(total.toDouble()),
    )
)

/**
 * MediaEntity → media.meta 白名单元数据。
 * **不回**:uri / latitude / longitude / ocrText / 任何 embedding/ROI(隐私红线)。
 */
fun MediaEntity.toMetaJsValue(): JsValue.Obj = JsValue.Obj(
    linkedMapOf(
        "id" to JsValue.Num(id.toDouble()),
        "type" to JsValue.Str(type.name),
        "captureMs" to JsValue.Num(captureDate.toDouble()),
        "fileName" to JsValue.Str(fileName),
        "labels" to parseStringArray(labels),
        "locationName" to (locationName?.let { JsValue.Str(it) } ?: JsValue.Null),
        "hasFace" to JsValue.Bool(hasFace),
        "faceId" to (faceId?.let { JsValue.Str(it) } ?: JsValue.Null),
    )
)

/**
 * 解析 MediaEntity.labels 的 JSON 数组字符串(存储格式固定为 `["猫","户外"]`)→ JsValue.Arr。
 * P0 简易解析(去括号 + 逗号切分 + 去引号);异常或空 → 空数组。配纯 JVM 测试。
 * 注:labels 内部不含逗号(均为短标签);若后续标签可能含逗号,改用 Moshi。
 */
private fun parseStringArray(raw: String?): JsValue {
    if (raw.isNullOrBlank()) return JsValue.Arr(emptyList())
    val trimmed = raw.trim().removeSurrounding("[", "]").trim()
    if (trimmed.isBlank()) return JsValue.Arr(emptyList())
    val items = trimmed.split(",").mapNotNull { seg ->
        seg.trim().trim('"').trim('\'').trim().takeIf { it.isNotBlank() }
    }
    return JsValue.Arr(items.map { JsValue.Str(it) })
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.js.GalleryJsTest"`
Expected: PASS(6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/js/GalleryJs.kt \
        app/src/test/java/com/mamba/picme/features/chat/js/GalleryJsTest.kt
git commit -m "$(cat <<'EOF'
feat(js): JsValue↔QueryFilter/MediaEntity 转换层（gallery.query/media.meta）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: 依赖注入(Dependencies + AppContainer)

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatViewModelDependencies.kt:22`
- Modify: `app/src/main/java/com/mamba/picme/di/AppContainer.kt:436`(构造) + `createChatViewModelFactory` 装配处

- [ ] **Step 1: 加 Dependencies 字段**

在 `ChatViewModelDependencies.kt`,`startTagScanUseCase` 字段后新增:

```kotlin
val queryGalleryMediaUseCase: QueryGalleryMediaUseCase,
```
并补 import:`import com.mamba.picme.domain.usecase.QueryGalleryMediaUseCase`

- [ ] **Step 2: AppContainer 构造 UseCase**

在 `AppContainer.kt` `getGallerySummaryUseCase` 的 `by lazy { ... }`(约 :436)后新增:

```kotlin
private val queryGalleryMediaUseCase: QueryGalleryMediaUseCase by lazy {
    QueryGalleryMediaUseCase(db = database)
}
```
并补 import:`import com.mamba.picme.domain.usecase.QueryGalleryMediaUseCase`

- [ ] **Step 3: 装配进 ChatViewModelDependencies**

在 `createChatViewModelFactory` 构造 `ChatViewModelDependencies(...)` 处,加入实参(与 `getGallerySummaryUseCase = getGallerySummaryUseCase` 并排):

```kotlin
queryGalleryMediaUseCase = queryGalleryMediaUseCase,
```

> 若 `createChatViewModelFactory` 内 `getGallerySummaryUseCase` 装配行不是字面 `getGallerySummaryUseCase = getGallerySummaryUseCase`,以现有写法为模板对称添加。

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ChatViewModelDependencies.kt \
        app/src/main/java/com/mamba/picme/di/AppContainer.kt
git commit -m "$(cat <<'EOF'
feat(gallery): 注入 QueryGalleryMediaUseCase 到 ChatViewModel

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: ChatViewModel.onRunScript 注册 handler

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`(字段 ~:118 + `onRunScript` ~:1212)

- [ ] **Step 1: 加 ViewModel 字段**

在 `ChatViewModel` 字段区(约 :118,`startTagScanUseCase` 行旁)新增:

```kotlin
private val queryGalleryMediaUseCase = dependencies.queryGalleryMediaUseCase
```

- [ ] **Step 2: 补 import**

`ChatViewModel.kt` 顶部 import 区加(若缺):

```kotlin
import com.mamba.picme.features.chat.js.parseQueryFilter
import com.mamba.picme.features.chat.js.toJsValue
import com.mamba.picme.features.chat.js.toMetaJsValue
```
> `syncHandler` 已 import(`:43`)。`JsValue` / `JsValue.Null` 已在用。

- [ ] **Step 3: 在 onRunScript 注册两个新 handler**

在 `onRunScript` 的 `rt.register(syncHandler("gallery.summary"){...})` 之后、`rt.eval(...)` 之前,新增:

```kotlin
// gallery.query：结构化过滤 → {ids,total}（只读，守隐私：只回 id+计数）
rt.register(syncHandler("gallery.query") { args ->
    val filter = parseQueryFilter(args)
    runBlocking {
        queryGalleryMediaUseCase(filter).toJsValue()
    }
})
// media.meta：单张白名单元数据（不回 uri/GPS/ocr/向量）
rt.register(syncHandler("media.meta") { args ->
    val id = (args as? JsValue.Num)?.value?.toLong()
        ?: (((args as? JsValue.Arr)?.items?.firstOrNull() as? JsValue.Num)?.value?.toLong())
    if (id == null) {
        JsValue.Null
    } else {
        runBlocking {
            mediaRepository?.getMediaById(id)?.toMetaJsValue() ?: JsValue.Null
        }
    }
})
```

> **媒体查单张的接入**:优先复用 ChatViewModel 已有的媒体访问入口。先查 `ChatViewModel` 是否持有 `MediaRepository`/`mediaDao`/`getMediaById` 类方法(执行时 `grep` 确认)。
> - 若已有 `mediaRepository.getMediaById(id)` 之类 → 直接用(把上面 `mediaRepository?` 改实)。
> - 若无 → 改用 `queryGalleryMediaUseCase` 同模块新增一个 `suspend fun meta(id): MediaEntity?`,或在 `onRunScript` 内直接 `runBlocking { /* database.mediaDao().getMediaById(id) */ }`。**推荐**:在 `QueryGalleryMediaUseCase` 加 `suspend fun meta(id: Long) = withContext(Dispatchers.IO) { db.mediaDao().getMediaById(id) }`,handler 调 `queryGalleryMediaUseCase.meta(id)`。这样不引入新依赖。

若采「UseCase 加 meta」方案,Task 5 Step 3 的 media.meta handler 改为:

```kotlin
rt.register(syncHandler("media.meta") { args ->
    val id = (args as? JsValue.Num)?.value?.toLong()
        ?: (((args as? JsValue.Arr)?.items?.firstOrNull() as? JsValue.Num)?.value?.toLong())
    if (id == null) {
        JsValue.Null
    } else {
        runBlocking { queryGalleryMediaUseCase.meta(id)?.toMetaJsValue() ?: JsValue.Null }
    }
})
```
并在 Task 2 的 `QueryGalleryMediaUseCase` 内补:

```kotlin
/** 单张媒体元数据(只读),供 JS `media.meta`。 */
suspend fun meta(id: Long): MediaEntity? = withContext(Dispatchers.IO) { db.mediaDao().getMediaById(id) }
```
(`MediaEntity` import 加到 UseCase 文件。)

**执行时统一采「UseCase 加 meta」方案**(最干净,无新依赖)。

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt \
        app/src/main/java/com/mamba/picme/domain/usecase/QueryGalleryMediaUseCase.kt
git commit -m "$(cat <<'EOF'
feat(js): onRunScript 注册 gallery.query / media.meta handler

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: 更新 run_gallery_script @Tool 描述

**Files:**
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/tool/ChatToolService.kt:167-173`

- [ ] **Step 1: 替换 @Tool value 与 @P 描述**

把 `run_gallery_script` 的 `value = [...]` 整段替换为(列出全部可用 handler,让 LLM 生成 JS 时知道能调什么):

```kotlin
@Tool(
    name = "run_gallery_script",
    value = ["在端侧沙箱执行一段 JavaScript,用于相册盘点/统计分析(只读,数据不出端)。可用 bridge.call: " +
        "bridge.call('gallery.summary') → 相册聚合统计对象(totalPhotos/totalVideos/totalMedia/hasFaceCount/personClusterCount/namedPersonCount/labeledCount/unlabeledCount/semanticEncodedCount/remainingPass1/remainingPass3/isScanning/currentPass/recommendation); " +
        "bridge.call('gallery.query', {label?,ocr?,location?,fromMs?,toMs?,hasFace?,limit?}) → 结构化过滤命中,返回 {ids:[...], total:N}(多维 AND,全可选;ids 已截断到 limit,total 为未截断真实数); " +
        "bridge.call('media.meta', id) → 单张轻量元数据 {id,type,captureMs,fileName,labels:[...],locationName,hasFace,faceId}(不含路径/GPS/OCR 原文/向量)。 " +
        "在 JS 内组合计算(如某标签占比 = query.total / summary.totalMedia),最后 return 一个结果对象——该对象会回传给你做自然语言总结。 " +
        "示例:var s=bridge.call('gallery.summary'); var c=bridge.call('gallery.query',{label:'猫'}); return {catPhotos:c.total, ratio: s.totalMedia>0 ? c.total/s.totalMedia : 0};"]
)
fun runGalleryScript(
    @P(name = "code", value = "JavaScript 源码;用 bridge.call('gallery.summary' | 'gallery.query', filter | 'media.meta', id) 取数据,return 结果对象") code: String
): String = dispatchCommand(AgentCommand.ExecuteScript(code = code))
```

> 注:原 `value` 用了半角逗号断句;新 `value` 字符串内避免裸双引号(`"`)冲突——字段名用单引号。保留原 `dispatchCommand` 主体不变。

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :runtime-core:compileKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: Commit**

```bash
git add runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/tool/ChatToolService.kt
git commit -m "$(cat <<'EOF'
feat(chat): run_gallery_script tool 描述补 gallery.query / media.meta

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: 端到端手验

**Files:** 无(验证步骤)

- [ ] **Step 1: 全量 JVM 单测**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS(含 Task 1/3 新增 13 个用例 + 既有用例)。

- [ ] **Step 2: 编译 APK**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: 真机端到端手验**

安装后在 Chat 页触发「盘点相册 / 含'猫'的照片有多少」类指令,确认:
- 远程 LLM 生成的 JS 调用了 `gallery.query` / `media.meta`;
- logcat(`adb logcat -s "PoLang:Js:*"`)看到 `bridge.call('gallery.query', ...)` 往返;
- 回复含真实计数(非 0 且与相册一致);
- 无 `SCRIPT_TIMEOUT` / `HANDLER_NOT_FOUND`。

> 若 LLM 未生成新 handler 调用:确认 Task 6 的 tool 描述已生效(描述是 LLM 唯一感知 handler 的渠道)。

- [ ] **Step 4: 文档同步**

`docs/superpowers/specs/2026-07-22-js-engine-jsbridge-design.md` §12 的 handler 清单更新为 `gallery.summary / gallery.query / media.meta`(原子提交,与 CLAUDE.md 三层文档一致性要求对齐)。

```bash
git add docs/superpowers/specs/2026-07-22-js-engine-jsbridge-design.md
git commit -m "$(cat <<'EOF'
docs(js): 设计文档同步 gallery.query / media.meta handler

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review

**1. Spec coverage** — P0 目标「扩只读数据面,三层闭环」:
- `gallery.query`:Task 1(model)+ Task 2(UseCase)+ Task 3(转换)+ Task 5(注册)+ Task 6(LLM 可知)。✅
- `media.meta`:Task 3(转换)+ Task 5(注册,含 UseCase.meta)+ Task 6。✅
- `gallery.summary` 保留不变。✅
- 隐私白名单:Task 3 `toMetaJsValue` + 测试断言不含 uri/GPS/ocrText。✅
- 注入链:Task 4 全链路。✅

**2. Placeholder scan** — Task 5 Step 3 的 `mediaRepository?` 写法是「探索后定」的占位,已在同 Step 明确「执行时统一采 UseCase 加 meta 方案」并给出最终代码,无悬空占位。其余步骤均含完整代码。

**3. Type consistency** —
- `QueryFilter` / `GalleryQueryResult`:Task 1 定义,Task 2/3/5 使用,字段名一致(label/ocr/location/fromMs/toMs/hasFace/limit;ids/total)。✅
- `parseQueryFilter(args: JsValue): QueryFilter`、`GalleryQueryResult.toJsValue(): JsValue.Obj`、`MediaEntity.toMetaJsValue(): JsValue.Obj`:Task 3 定义,Task 5 调用,签名一致。✅
- `QueryGalleryMediaUseCase.meta(id): MediaEntity?`:Task 5 补充定义,Task 2 文件加 import。✅(执行时在 Task 2 文件加 `MediaEntity` import + `meta` 方法,或合并到 Task 5 提交——计划已注明同提交。)

**4. 测试可达性** — Task 1/3 均纯 JVM(`MediaEntity` 为普通 data class,实例化不需 Room/Android);`./gradlew :app:testDebugUnitTest` 可跑。✅
