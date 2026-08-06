# 相册图片去重 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复并接通既有的「重复照片管理」半成品：端侧分层检测（精确 MD5 + 近似 pHash），用户确认后每组保留一张。

**Architecture:** 纯 Kotlin 哈希核心 `PerceptualHash`（可 JVM 单测）+ Android I/O 薄壳 `DuplicateImageDetector`（ContentResolver 读取 + Bitmap 解码 + 分组编排）+ 现有 `FindDuplicateMediaUseCase` / `MediaViewModel` / `DuplicateManager` UI 复用。新增一条导航路由 + 一处设置入口。无数据库迁移、无新 worker、无新模型。

**Tech Stack:** Kotlin、androidx.compose、Coroutines、Room（只读）、ContentResolver / BitmapFactory / MessageDigest（端侧）。

**Spec:** `docs/superpowers/specs/2026-08-06-gallery-image-dedup-design.md`

---

## File Structure

| 文件 | 责任 | 动作 |
|------|------|------|
| `app/src/main/java/com/mamba/picme/core/common/PerceptualHash.kt` | 纯 Kotlin：MD5 流式 / 64-bit pHash(DCT) / 汉明距离 / 并查集聚类。零 Android 依赖。 | 新建 |
| `app/src/test/java/com/mamba/picme/core/common/PerceptualHashTest.kt` | 纯 JVM 单测覆盖上述数学。 | 新建 |
| `app/src/main/java/com/mamba/picme/core/common/DuplicateImageDetector.kt` | Android I/O 薄壳 + 两层分组编排 + 择优排序。 | 重写（object 保留） |
| `app/src/main/java/com/mamba/picme/domain/usecase/FindDuplicateMediaUseCase.kt` | 取照片 → 查 size/mime → 组装 `DedupItem` → 调检测器。 | 重写 |
| `app/src/main/java/com/mamba/picme/di/AppContainer.kt` | 给 UseCase 注入 `context`。 | 改 1 行 |
| `app/src/test/java/com/mamba/picme/core/common/DuplicateImageDetectorTest.kt` | 旧 File-based API 测试（API 已删）。 | 删除 |
| `app/src/main/java/com/mamba/picme/navigation/Screen.kt` | 新增路由常量。 | 加 1 行 |
| `app/src/main/java/com/mamba/picme/MainActivity.kt` | 注册 `composable` + 接 Settings 回调。 | 改 2 处 |
| `app/src/main/java/com/mamba/picme/features/settings/SettingsScreen.kt` | GALLERY 卡加「重复照片管理」入口。 | 改 3 处 |
| `app/src/main/res/values-zh-rCN/strings.xml`、`values-zh-rTW/strings.xml` | 校验/补齐去重文案。 | 校验 |
| `app/src/main/AGENTS.md` 或 `features/gallery/AGENTS.md` | 记录去重管理页接通。 | 更新 |

**复用不动**：`DuplicateManager.kt`（`DuplicateManagerRoute` / `DuplicateManagerScreen` / 卡片 / 预览框）、`MediaViewModel`（`startDuplicateScan` / `deleteDuplicateGroup` / `deleteAllDuplicatesExceptOne` / `isScanningDuplicates`）、`deleteMediaByIds` 删除链路、`domain/model/DuplicateGroup.kt`、`values/strings.xml` 全部去重文案。

---

## Task 1: 纯 Kotlin 哈希核心 `PerceptualHash`（TDD）

**Files:**
- Create: `app/src/main/java/com/mamba/picme/core/common/PerceptualHash.kt`
- Create: `app/src/test/java/com/mamba/picme/core/common/PerceptualHashTest.kt`

- [ ] **Step 1: 写失败测试**

Create `app/src/test/java/com/mamba/picme/core/common/PerceptualHashTest.kt`:

```kotlin
package com.mamba.picme.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayInputStream

class PerceptualHashTest {

    // ── MD5（流式）──

    @Test
    fun `md5Hex same bytes gives same 32-hex hash`() {
        val h1 = PerceptualHash.md5Hex(ByteArrayInputStream("test content".toByteArray()))
        val h2 = PerceptualHash.md5Hex(ByteArrayInputStream("test content".toByteArray()))
        assertNotNull(h1)
        assertEquals(32, h1.length)
        assertEquals(h1, h2)
    }

    @Test
    fun `md5Hex different bytes gives different hash`() {
        val h1 = PerceptualHash.md5Hex(ByteArrayInputStream("content A".toByteArray()))
        val h2 = PerceptualHash.md5Hex(ByteArrayInputStream("content B".toByteArray()))
        assertNotEquals(h1, h2)
    }

    // ── 汉明距离 ──

    @Test
    fun `hammingDistance same hash is 0`() {
        val h = 0x123456789ABCDEF0L
        assertEquals(0, PerceptualHash.hammingDistance(h, h))
    }

    @Test
    fun `hammingDistance one bit is 1`() {
        assertEquals(1, PerceptualHash.hammingDistance(0L, 0x0000000000000001L))
    }

    @Test
    fun `hammingDistance all 64 bits is 64`() {
        assertEquals(64, PerceptualHash.hammingDistance(0L, -1L))
    }

    @Test
    fun `hammingDistance is symmetric`() {
        val a = 0x123456789ABCDEF0L
        val b = 0x0FEDCBA987654321L
        assertEquals(
            PerceptualHash.hammingDistance(a, b),
            PerceptualHash.hammingDistance(b, a)
        )
    }

    // ── pHash：确定性 + 稳定性 + 敏感性 ──

    private fun gradient(size: Int, horizontal: Boolean): DoubleArray {
        val g = DoubleArray(size * size)
        for (r in 0 until size) for (c in 0 until size) {
            g[r * size + c] = if (horizontal) c.toDouble() else r.toDouble()
        }
        return g
    }

    @Test
    fun `phash is deterministic for identical input`() {
        val g = gradient(32, horizontal = true)
        assertEquals(PerceptualHash.phash(g), PerceptualHash.phash(g.toList().toDoubleArray()))
    }

    @Test
    fun `phash stable under tiny 1-pixel perturbation`() {
        val g = gradient(32, horizontal = true)
        val g2 = g.copyOf().also { it[0] = it[0] + 1.0 } // 1 个像素 +1
        val d = PerceptualHash.hammingDistance(PerceptualHash.phash(g), PerceptualHash.phash(g2))
        assertTrue("expected small hamming distance, got $d", d <= 8)
    }

    @Test
    fun `phash distinguishes clearly different patterns`() {
        val horiz = PerceptualHash.phash(gradient(32, horizontal = true))
        val vert = PerceptualHash.phash(gradient(32, horizontal = false))
        val d = PerceptualHash.hammingDistance(horiz, vert)
        assertTrue("expected large hamming distance, got $d", d > 10)
    }

    // ── 并查集聚类 ──

    @Test
    fun `clusterByHamming merges within threshold, splits beyond it`() {
        // 0 与 0x1F 距离 5；0 与 0x3F 距离 6
        val hashes = listOf(0L, 0x000000000000001FL, 0x000000000000003FL)
        val t5 = PerceptualHash.clusterByHamming(hashes, threshold = 5)
        assertEquals(1, t5.size)            // 仅 [0,1] 成组；0x3F 单独被过滤
        assertTrue(t5[0].containsAll(listOf(0, 1)))

        val t6 = PerceptualHash.clusterByHamming(hashes, threshold = 6)
        assertEquals(1, t6.size)            // [0,1,2] 全合并
        assertEquals(3, t6[0].size)
    }

    @Test
    fun `clusterByHamming empty or single yields no groups`() {
        assertTrue(PerceptualHash.clusterByHamming(emptyList()).isEmpty())
        assertTrue(PerceptualHash.clusterByHamming(listOf(1L)).isEmpty())
    }
}
```

- [ ] **Step 2: 运行测试，确认失败（类不存在，编译失败）**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.core.common.PerceptualHashTest"`
Expected: 编译失败 / 测试未运行（`PerceptualHash` 未定义）。

- [ ] **Step 3: 写最小实现**

Create `app/src/main/java/com/mamba/picme/core/common/PerceptualHash.kt`:

```kotlin
package com.mamba.picme.core.common

import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * 纯 Kotlin 去重核心：MD5（流式）/ 64-bit pHash（DCT）/ 汉明距离 / 并查集聚类。
 * 零 Android 依赖，可纯 JVM 单测。端侧执行，不上传任何数据。
 */
object PerceptualHash {

    /** pHash 灰度矩阵边长（32×32 → DCT → 取左上 8×8）。 */
    const val PHASH_SIZE = 32

    /** 近似判定阈值（汉明距离）。保守值，少误报。 */
    const val SIMILAR_HAMMING_THRESHOLD = 5

    /**
     * 流式 MD5。**不关闭** [input]（由调用方 `use` 管理）。返回 32 位小写十六进制。
     */
    fun md5Hex(input: InputStream): String {
        val md = MessageDigest.getInstance("MD5")
        val dis = DigestInputStream(input, md)
        val buf = ByteArray(8 * 1024)
        while (dis.read(buf) > 0) {
            // 排空：每读一块同步更新 digest
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 64-bit pHash。[gray] 长度须为 [size]×[size]。
     * 流程：灰度矩阵 → 2D DCT-II → 左上 8×8 系数 → 以中位数阈值化。
     */
    fun phash(gray: DoubleArray, size: Int = PHASH_SIZE): Long {
        require(gray.size == size * size) { "gray size ${gray.size} != ${size * size}" }
        val dct = dct2(gray, size)
        val block = Array(8) { r -> DoubleArray(8) { c -> dct[r][c] } }
        val coeffs = ArrayList<Double>(64)
        for (r in 0 until 8) for (c in 0 until 8) coeffs.add(block[r][c])
        coeffs.sort()
        val median = coeffs[32] // 64 个系数的中位数
        var hash = 0L
        var bit = 0
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                if (block[r][c] > median) hash = hash or (1L shl bit)
                bit++
            }
        }
        return hash
    }

    /** 64-bit 汉明距离（popcount of XOR）。 */
    fun hammingDistance(a: Long, b: Long): Int {
        var xor = a xor b
        var d = 0
        while (xor != 0L) {
            d++
            xor = xor and (xor - 1)
        }
        return d
    }

    /**
     * 并查集按汉明距离聚类。返回每组成员在 [hashes] 中的下标（仅保留 size≥2 的组）。
     */
    fun clusterByHamming(
        hashes: List<Long>,
        threshold: Int = SIMILAR_HAMMING_THRESHOLD
    ): List<List<Int>> {
        val n = hashes.size
        if (n < 2) return emptyList()
        val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var cur = x
            while (parent[cur] != cur) {
                parent[cur] = parent[parent[cur]]
                cur = parent[cur]
            }
            return cur
        }
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (hammingDistance(hashes[i], hashes[j]) <= threshold) {
                    parent[find(i)] = find(j)
                }
            }
        }
        return parent.indices
            .groupBy { find(it) }
            .values
            .filter { it.size >= 2 }
    }

    private fun dct2(input: DoubleArray, size: Int): Array<DoubleArray> {
        val rowDct = Array(size) { r ->
            dct1(DoubleArray(size) { c -> input[r * size + c] })
        }
        val out = Array(size) { DoubleArray(size) }
        for (c in 0 until size) {
            val col = DoubleArray(size) { r -> rowDct[r][c] }
            val colDct = dct1(col)
            for (r in 0 until size) out[r][c] = colDct[r]
        }
        return out
    }

    private fun dct1(x: DoubleArray): DoubleArray {
        val n = x.size
        val out = DoubleArray(n)
        val factor = PI / (2.0 * n)
        for (k in 0 until n) {
            var sum = 0.0
            for (m in 0 until n) {
                sum += x[m] * cos(factor * (2 * m + 1) * k)
            }
            val c0 = if (k == 0) sqrt(1.0 / n) else sqrt(2.0 / n)
            out[k] = c0 * sum
        }
        return out
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.core.common.PerceptualHashTest"`
Expected: 11 tests PASS。

- [ ] **Step 5: 删除旧 File-based 检测器测试**

Delete the entire file `app/src/test/java/com/mamba/picme/core/common/DuplicateImageDetectorTest.kt`（其测试的 `calculateMD5(File)` / `calculatePerceptualHash(File)` / 嵌套 `DuplicateGroup` API 将在 Task 2 移除；纯数学测试已由 `PerceptualHashTest` 接管）。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/mamba/picme/core/common/PerceptualHash.kt \
        app/src/test/java/com/mamba/picme/core/common/PerceptualHashTest.kt \
        app/src/test/java/com/mamba/picme/core/common/DuplicateImageDetectorTest.kt
git commit -m "feat(gallery): 纯 Kotlin 去重核心 PerceptualHash（MD5/pHash/汉明/聚类）+ 单测"
```

---

## Task 2: 重写 `DuplicateImageDetector`（Android 薄壳 + 编排）+ UseCase

**Files:**
- Modify (rewrite whole file): `app/src/main/java/com/mamba/picme/core/common/DuplicateImageDetector.kt`
- Modify (rewrite whole file): `app/src/main/java/com/mamba/picme/domain/usecase/FindDuplicateMediaUseCase.kt`
- Modify: `app/src/main/java/com/mamba/picme/di/AppContainer.kt:601`

- [ ] **Step 1: 重写检测器（Android I/O + 分组编排）**

Replace the entire contents of `app/src/main/java/com/mamba/picme/core/common/DuplicateImageDetector.kt` with:

```kotlin
package com.mamba.picme.core.common

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.mamba.picme.domain.model.DuplicateGroup

/**
 * 图片去重检测器（端侧）：精确 MD5 + 近似 pHash 两层。
 *
 * 纯算法见 [PerceptualHash]（可 JVM 单测）；本对象只负责 Android I/O
 * （ContentResolver 读取、Bitmap 解码）与分组编排。所有媒体字节 100% 本地处理。
 *
 * 两组内成员按「像素最多 → 评分最高 → 最新」择优排序，最优者在前，
 * 作为默认保留项（UI 取 index 0 保留，删其余）。
 */
object DuplicateImageDetector {

    private const val TAG = "PoLang:Gallery"

    /** 一张待检图片的最小信号。像素宽高由 pHash 解码顺带获得，精确组无需。 */
    data class DedupItem(
        val uri: String,
        val sizeBytes: Long,
        val mime: String,
        val captureDate: Long,
        val aestheticScore: Float? = null,
    )

    /** 解码后附带原始像素面积，用于近似组择优。 */
    private data class Decoded(val item: DedupItem, val pixelArea: Int)

    /**
     * 两层检测：
     * 1. 精确：(size, mime) 分桶 → 桶内 MD5 流式 → MD5 相同成组。
     * 2. 近似：全部图 pHash → 汉明 ≤ [PerceptualHash.SIMILAR_HAMMING_THRESHOLD] 并查集聚类
     *    → 含 ≥2 个不同 MD5 的聚类作为相似组（全部字节相同的聚类已是精确组，跳过）。
     */
    fun findDuplicates(context: Context, items: List<DedupItem>): List<DuplicateGroup> {
        if (items.size < 2) return emptyList()
        val cr = context.contentResolver

        // 1. 一次性算好 MD5（流式，缓存，避免两遍 I/O）
        val md5ByUri = LinkedHashMap<String, String?>()
        for (item in items) md5ByUri[item.uri] = md5FromUri(cr, item.uri)

        // 2. 精确组
        val exact = findExact(items, md5ByUri)
        val exactUris: Set<String> = exact.flatMap { group -> group.fileUris }.toSet()

        // 3. 近似组（排除与精确组完全重合的聚类）
        val near = findNear(cr, items, md5ByUri)
            .filter { group -> group.fileUris.any { uri -> uri !in exactUris } }

        return exact + near
    }

    private fun findExact(
        items: List<DedupItem>,
        md5ByUri: Map<String, String?>
    ): List<DuplicateGroup> {
        val results = mutableListOf<DuplicateGroup>()
        items
            .groupBy { "${it.sizeBytes}|${it.mime}" }
            .filter { it.value.size >= 2 }
            .forEach { (_, bucket) ->
                bucket
                    .mapNotNull { item -> md5ByUri[item.uri]?.let { md5 -> md5 to item } }
                    .groupBy({ it.first }, { it.second })
                    .filter { it.value.size >= 2 }
                    .forEach { (md5, group) ->
                        results += DuplicateGroup(
                            id = "exact:$md5",
                            fileUris = rankExact(group).map { it.uri },
                            isExactDuplicate = true
                        )
                    }
            }
        return results
    }

    private fun findNear(
        cr: ContentResolver,
        items: List<DedupItem>,
        md5ByUri: Map<String, String?>
    ): List<DuplicateGroup> {
        val decoded = mutableListOf<Decoded>()
        val hashes = mutableListOf<Long>()
        for (item in items) {
            val (hash, area) = phashFromUri(cr, item.uri) ?: continue
            hashes += hash
            decoded += Decoded(item, area)
        }
        val results = mutableListOf<DuplicateGroup>()
        for (cluster in PerceptualHash.clusterByHamming(hashes)) {
            val members = cluster.map { idx -> decoded[idx] }
            val distinctMd5 = members.mapNotNull { it.item.uri.let { u -> md5ByUri[u] } }.toSet()
            if (distinctMd5.size < 2) continue // 全部字节相同 → 已是精确组
            results += DuplicateGroup(
                id = "near:${members.first().item.uri}",
                fileUris = rankNear(members).map { it.item.uri },
                isExactDuplicate = false
            )
        }
        return results
    }

    private fun rankExact(items: List<DedupItem>): List<DedupItem> =
        items.sortedWith(
            compareByDescending<DedupItem> { it.aestheticScore ?: -1f }
                .thenByDescending { it.captureDate }
        )

    private fun rankNear(decoded: List<Decoded>): List<Decoded> =
        decoded.sortedWith(
            compareByDescending<Decoded> { it.pixelArea }
                .thenByDescending { it.item.aestheticScore ?: -1f }
                .thenByDescending { it.item.captureDate }
        )

    /** 流式 MD5；失败/不可读返回 null。 */
    private fun md5FromUri(cr: ContentResolver, uri: String): String? = try {
        cr.openInputStream(Uri.parse(uri))?.use { PerceptualHash.md5Hex(it) }
    } catch (e: Exception) {
        com.mamba.picme.core.common.Logger.w(TAG, "md5 failed for $uri", e)
        null
    }

    /** 返回 (pHash, 原始 width*height)；解码失败返回 null。降采样到 32×32 后转灰度。 */
    private fun phashFromUri(cr: ContentResolver, uri: String): Pair<Long, Int>? {
        val parsed = Uri.parse(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            cr.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (e: Exception) {
            Logger.w(TAG, "phash bounds failed for $uri", e)
            return null
        }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        val target = PerceptualHash.PHASH_SIZE
        val sample = maxOf(1, maxOf(w, h) / target)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp: Bitmap = try {
            cr.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        } catch (e: Exception) {
            Logger.w(TAG, "phash decode failed for $uri", e)
            return null
        }
        val scaled = if (bmp.width != target || bmp.height != target) {
            Bitmap.createScaledBitmap(bmp, target, target, false).also { if (it !== bmp) bmp.recycle() }
        } else {
            bmp
        }
        return try {
            val px = IntArray(target * target)
            scaled.getPixels(px, 0, target, 0, 0, target, target)
            val gray = DoubleArray(target * target) { i ->
                val p = px[i]
                0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
            }
            PerceptualHash.phash(gray, target) to (w * h)
        } catch (e: Exception) {
            Logger.w(TAG, "phash compute failed for $uri", e)
            null
        } finally {
            scaled.recycle()
        }
    }
}
```

> 注：`Logger.w(tag, message, throwable)` 三参重载已存在（`app/.../core/common/Logger.kt:195`）。`findDuplicates` 内含阻塞 I/O，由 UseCase 包在 `withContext(Dispatchers.IO)` 中调用。

- [ ] **Step 2: 重写 UseCase（注入 Context，组装 DedupItem）**

Replace the entire contents of `app/src/main/java/com/mamba/picme/domain/usecase/FindDuplicateMediaUseCase.kt` with:

```kotlin
package com.mamba.picme.domain.usecase

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.core.common.DuplicateImageDetector
import com.mamba.picme.domain.model.DuplicateGroup
import com.mamba.picme.domain.repository.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext

/**
 * 查找重复图片：取全部照片 → 查 size/mime → 组装 DedupItem → 调端侧检测器。
 * 媒体读取 100% 本地（ContentResolver），不上传。
 */
class FindDuplicateMediaUseCase(
    private val repository: MediaRepository,
    private val context: Context
) {
    suspend operator fun invoke(): List<DuplicateGroup> = withContext(Dispatchers.IO) {
        val allAssets = repository.allMedia.firstOrNull() ?: return@withContext emptyList()
        val cr = context.contentResolver
        val items = allAssets
            .filter { it.type == MediaType.PHOTO }
            .mapNotNull { asset -> asset.toDedupItem(cr) }
        DuplicateImageDetector.findDuplicates(context, items)
    }

    private fun MediaAsset.toDedupItem(cr: ContentResolver): DuplicateImageDetector.DedupItem? {
        val size = fileSizeBytes(cr, uri) ?: return null
        val mime = cr.getType(Uri.parse(uri)) ?: "image/*"
        return DuplicateImageDetector.DedupItem(
            uri = uri,
            sizeBytes = size,
            mime = mime,
            captureDate = captureDate,
            aestheticScore = aestheticScore
        )
    }

    private fun fileSizeBytes(cr: ContentResolver, uri: String): Long? = try {
        cr.openFileDescriptor(Uri.parse(uri), "r")?.use { it.statSize }
    } catch (e: Exception) {
        null
    }
}
```

- [ ] **Step 3: 改 DI 注入 context**

In `app/src/main/java/com/mamba/picme/di/AppContainer.kt`, change line 601 from:

```kotlin
            findDuplicateMediaUseCase = FindDuplicateMediaUseCase(repository),
```

to:

```kotlin
            findDuplicateMediaUseCase = FindDuplicateMediaUseCase(repository, context),
```

（`context: Context` 为 AppContainer 的 `private val context`，见 `AppContainer.kt:213`；同文件 615/629 行已有 `appContext = context` 先例。）

- [ ] **Step 4: 编译 + 回归单测**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests "com.mamba.picme.core.common.PerceptualHashTest"`
Expected: 编译通过；PerceptualHashTest 11 项全 PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/mamba/picme/core/common/DuplicateImageDetector.kt \
        app/src/main/java/com/mamba/picme/domain/usecase/FindDuplicateMediaUseCase.kt \
        app/src/main/java/com/mamba/picme/di/AppContainer.kt
git commit -m "fix(gallery): 重写去重检测器(精确MD5+近似pHash)与UseCase(ContentResolver流式)"
```

---

## Task 3: 接通导航 + 设置入口

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/navigation/Screen.kt`
- Modify: `app/src/main/java/com/mamba/picme/MainActivity.kt`
- Modify: `app/src/main/java/com/mamba/picme/features/settings/SettingsScreen.kt`

- [ ] **Step 1: 加路由常量**

In `app/src/main/java/com/mamba/picme/navigation/Screen.kt`，在 `data object TagViewer : Screen("tag_viewer")`（第 38 行）之后新增一行：

```kotlin
    data object DuplicateManager : Screen("duplicate_manager")
```

- [ ] **Step 2: 注册 composable**

In `app/src/main/java/com/mamba/picme/MainActivity.kt`，找到 `composable(Screen.TagViewer.route) { ... }` 区块（约 316 行起），在其后新增：

```kotlin
                            composable(Screen.DuplicateManager.route) {
                                DuplicateManagerRoute(
                                    viewModel = mediaViewModel,
                                    onNavigateBack = { navController.popBackStack() }
                                )
                            }
```

并在文件顶部 import 区加（若尚无）：

```kotlin
import com.mamba.picme.features.gallery.components.DuplicateManagerRoute
```

（`mediaViewModel` 在 `MainActivity` 第 115 行通过 `viewModel(factory = app.container.createMediaViewModelFactory())` 取得，作用域覆盖整个 NavHost；`navController.popBackStack()` 模式同 `TagViewer`。）

- [ ] **Step 3: SettingsScreen 增加入口参数与回调透传**

3a. 在 `SettingsScreen.kt` 主 Composable 参数表（约 113–125 行，`onNavigateToPeople` 旁）新增：

```kotlin
    onNavigateToDuplicateManager: () -> Unit = {},
```

3b. 在内部 Content Composable 参数表（约 341–352 行，`onNavigateToTagViewer` 旁）新增同名参数：

```kotlin
    onNavigateToDuplicateManager: () -> Unit = {},
```

3c. 在主 Composable 调用内部 Content 的实参区（约 276–285 行，`onNavigateToPeople = onNavigateToPeople` 旁）新增：

```kotlin
            onNavigateToDuplicateManager = onNavigateToDuplicateManager,
```

- [ ] **Step 4: GALLERY 卡加入口行**

在 `SettingsScreen.kt` 的 GALLERY `SettingsSection { ... }`（约 481–510 行）内，紧接 `tag_viewer` 那个 `SettingsClickableRow`（其 `onClick = onNavigateToTagViewer`，约 495 行）之后插入：

```kotlin
                    SettingsClickableRow(
                        title = stringResource(R.string.manage_duplicates),
                        subtitle = stringResource(R.string.duplicate_manager_desc),
                        leadingIcon = Icons.Rounded.PhotoLibrary,
                        onClick = onNavigateToDuplicateManager
                    )
```

并在文件顶部 import 区加：

```kotlin
import androidx.compose.material.icons.rounded.PhotoLibrary
```

> 若 `Icons.Rounded.PhotoLibrary` 不可用，回退用已 import 的 `Icons.Rounded.Search`（与 tag_viewer 同图标可接受），但优先 `PhotoLibrary`。

- [ ] **Step 5: MainActivity 接 Settings 回调**

在 `MainActivity.kt` 调用 `SettingsScreen(...)` 的实参区（与 `onNavigateToTagViewer = { ... }`、`onNavigateToPeople = { ... }` 同处；可用 `grep -n "onNavigateToTagViewer =" app/src/main/java/com/mamba/picme/MainActivity.kt` 定位），新增：

```kotlin
                onNavigateToDuplicateManager = { navController.navigate(Screen.DuplicateManager.route) }
```

- [ ] **Step 6: 编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: 编译通过。

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/com/mamba/picme/navigation/Screen.kt \
        app/src/main/java/com/mamba/picme/MainActivity.kt \
        app/src/main/java/com/mamba/picme/features/settings/SettingsScreen.kt
git commit -m "feat(gallery): 接通重复照片管理入口(设置→相册功能)与导航路由"
```

---

## Task 4: i18n 校验 + 文档同步 + 全量构建

**Files:**
- Verify: `app/src/main/res/values/strings.xml`、`values-zh-rCN/strings.xml`、`values-zh-rTW/strings.xml`
- Modify: `app/src/main/AGENTS.md`（或 `features/gallery/AGENTS.md` 若存在）

- [ ] **Step 1: 校验三语去重文案齐全**

Run:
```bash
for d in values values-zh-rCN values-zh-rTW; do
  echo "== $d =="
  grep -E 'manage_duplicates|duplicate_manager_desc|exact_duplicate|similar_image|no_duplicates_found|duplicate_groups_found|keep_first_delete_others|will_keep_first_file|confirm_delete' app/src/main/res/$d/strings.xml | wc -l
done
```
Expected: 三个目录均返回 `9`（9 个 key 全在）。任一目录 <9，则比照 `values/strings.xml`（326 行起）把缺失 key 补到对应 `values-zh-rCN/strings.xml` / `values-zh-rTW/strings.xml`，中文翻译：

- `manage_duplicates`：管理重复照片 / 管理重複照片
- `duplicate_manager_desc`：查找并删除重复或相似的照片以释放空间。 / 查找並刪除重複或相似的照片以釋放空間。
- `exact_duplicate`：完全相同 / 完全相同
- `similar_image`：高度相似 / 高度相似
- `no_duplicates_found`：未发现重复照片 / 未發現重複照片
- `duplicate_groups_found`：发现 %1$d 组重复 / 發現 %1$d 組重複
- `keep_first_delete_others`：保留第一张，删除其余 / 保留第一張，刪除其餘
- `will_keep_first_file`：将保留第一张，删除其余： / 將保留第一張，刪除其餘：
- `confirm_delete`：确认删除 / 確認刪除

（其余已存在 key 如 `count_files`/`preview_all`/`cancel` 同法核对，缺则补。）

- [ ] **Step 2: 更新 AGENTS.md**

在 `app/src/main/AGENTS.md`（或 `features/gallery/AGENTS.md`）相册功能小节追加一行，说明「重复照片管理」入口已接通：设置 → 相册功能 → 管理重复照片（`Screen.DuplicateManager`），端侧精确(MD5)+近似(pHash)去重，确认后保留一张。

- [ ] **Step 3: 全量编译 + 单测**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: APK 构建成功；单测全 PASS（至少 PerceptualHashTest 11 项）。

- [ ] **Step 4: 真机闭环验证（手测）**

1. `adb install -r app/build/outputs/apk/debug/polang-debug.apk`（或 `./scripts/auto-dev-loop.sh`）。
2. 用 Debug 页 Pexels/批量生成造 ≥2 组重复：一组字节完全相同（同图存两次），一组高度相似（同图不同尺寸/重压缩）。
3. 设置 → 相册功能 → 管理重复照片 → 进入即扫描。
4. 确认：【精确重复】组与【高度相似】组分别正确识别；点「保留第一张，删除其余」→ 授权 → 相册确认每组只剩保留项。
5. `adb logcat -s "PoLang:Gallery"` 观察无异常。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/res/values-zh-rCN/strings.xml app/src/main/res/values-zh-rTW/strings.xml \
        app/src/main/AGENTS.md
git commit -m "docs(gallery): 去重功能 i18n 校验 + AGENTS.md 同步"
```
（仅当 Step 1 有补文案时才 add strings；无改动则只提交 AGENTS.md。）

---

## 红线核对（实施全程遵守）

| 红线 | 落实 |
|------|------|
| [PRIVACY] | MD5/pHash 全部 `ContentResolver.openInputStream` + 本地 `BitmapFactory` 解码；零网络、零上传 |
| [PERF] | 精确组无解码；近似组 `inSampleSize` 降采样到 32×32；扫描在 `Dispatchers.IO`；<1000 张可接受 |
| [I18N] | Task 4 校验/补齐三语 |
| 代码硬规则 | 无全限定名（除同一文件内 `com.mamba.picme.core.common.Logger.w` 自引用——可改为加 import 后用 `Logger.w`）、无通配 import、lambda 显式命名、tag `PoLang:Gallery`、4 空格 |

> **实施修正**：Task 2 检测器里对 Logger 用了全限定名 `com.mamba.picme.core.common.Logger.w(...)`，违反「无全限定名」规则。落地时改为在文件顶部 `import com.mamba.picme.core.common.Logger`（同包其实无需 import——`DuplicateImageDetector` 本就在 `core.common` 包，直接写 `Logger.w(...)` 即可）。计划代码保留全限定名仅为示意，实施务必去掉。
