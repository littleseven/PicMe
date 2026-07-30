# NIMA 美学评分选封面（Plan B）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 引入 NIMA (Neural Image Assessment) 离线美学模型，为每个人脸聚类挑"美学分最高"的代表图作封面，替换当前 `coverMediaId = mediaIds.firstOrNull()` 的随机取值。

**Architecture:** NIMA 转 **ONNX**（int8/float，MobileNetV2 backbone，~4MB int8），用项目既有的 **ONNX Runtime**（与 MobileCLIP/OPUS-MT/ModNet 同栈，参考 `ModNetOnnxBackend`）封装 `NimaOnnxScorer`。模型走「模型中心」**推荐层** WiFi 静默预下载（`ModelConfig.RECOMMENDED_MODEL_IDS`）。`media_assets` 加 `aestheticScore` 列（Room v18→v19）。后台 `AestheticScoreWorker` 给媒体打分；封面选择 = 该人物成员中 `aestheticScore` 最高者，写回 `persons.coverMediaId`。

**Tech Stack:** Kotlin + ONNX Runtime（`ai.onnxruntime.*`）+ Room + WorkManager/协程；纯 JVM 单测（封面选择 SQL 逻辑/打分映射）+ 设备验证。

**Spec:** `docs/superpowers/specs/2026-07-30-face-cluster-experience-design.md`（§3.3；A→C→B 之 B；决策 A1 后台打分 + B1 模型中心下载）

**⚠️ 前置依赖（外部，阻塞执行）:**
- **NIMA ONNX 模型文件**需先产出：`titu1994/neural-image-assessment`（或 `idealo/image-quality-assessment`）的 MobileNetV2 权重 → 导出 ONNX（输入 1×3×224×224，输出 1×10 分布或 scalar）。本计划 Task 1 给出导出步骤与注册，但**实际执行卡在拿到可用 `.onnx`**（验证输入/输出名与归一化）。
- 红线合规：NIMA 100% 端侧，不上传图片（符合 ADR-008）。

**验证命令（本环境真门槛=编译+JVM单测）:**
- 编译：`./gradlew :app:assembleDebug`
- 单测：`./gradlew :app:testDebugUnitTest`
- 设备（非自动化门槛）：`adb logcat -s "PoLang:Aesthetic"`

**约定:**
- 禁止 `com.mamba.picme.*` 全限定名（用 import）；禁止 wildcard import；lambda 参数显式命名；4 空格缩进。
- 每任务末尾提交一次（Conventional Commits），结尾加 `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`。
- I18N：新文案同步 4 套 `values*/`。Log tag：`PoLang:Aesthetic`。

---

## File Structure

| 文件 | 职责 | 动作 |
|---|---|---|
| `data/download/LlmModelDownloadManager.kt`（`ModelConfig`） | 注册 `nima-aesthetic-onnx` + 加入 `RECOMMENDED_MODEL_IDS` | 改 |
| `data/local/entity/MediaEntity.kt` | 新增 `aestheticScore: Float?` | 改 |
| `data/local/MediaDao.kt` | `updateAestheticScore` + `getMediaWithoutAestheticScore` | 改 |
| `data/local/AppDatabase.kt` | `version=19` + `MIGRATION_18_19` | 改 |
| `domain/aesthetic/NimaOnnxScorer.kt` | ONNX Runtime 推理封装（参考 `ModNetOnnxBackend`） | 新 |
| `domain/aesthetic/AestheticScoreWorker.kt` | 后台打分 + 封面刷新 | 新 |
| `data/local/dao/PersonDao.kt` | `refreshCoverByAesthetic(personId)` / `refreshAllCovers()` | 改 |
| `domain/aesthetic/CoverSelector.kt`（纯逻辑，可测） | 候选 → 封面选择规则 | 新 |

---

### Task 1: NIMA ONNX 模型获取 + 注册到模型中心

> 本任务含**外部手动步骤**（产出 ONNX）+ 代码注册。模型未就绪时，后续任务可先合入"列+选择逻辑+回退"，封面回退旧逻辑。

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/data/download/LlmModelDownloadManager.kt`（模型注册表 + `ModelConfig.RECOMMENDED_MODEL_IDS`）

- [ ] **Step 1: 产出 NIMA ONNX（外部）**

导出脚本（PyTorch，一次性）：
```bash
# 1. 克隆 NIMA 实现，加载 MobileNetV2 预训练权重
git clone https://github.com/titu1994/neural-image-assessment && cd neural-image-assessment
# 2. 导出 ONNX（输入 1x3x224x224，归一化 ImageNet mean/std；输出 1x10 EMD 分布）
python export_onnx.py --backbone mobilenetv2 --weights weights/mobilenetv2.pth --out nima_mobilenetv2.onnx
# 3. （可选）int8 量化到 ~4MB
python -m onnxruntime.quantization.quantize --input nima_mobilenetv2.onnx --output nima_mobilenetv2_int8.onnx --quant_format QDQ
```
> 验证 I/O：`python -c "import onnx; m=onnx.load('nima_mobilenetv2_int8.onnx'); print([(i.name,[d.dim_value for d in i.type.tensor_type.shape.dim]) for i in m.graph.input], [(o.name,...) for o in m.graph.output])"`，记录输入名（如 `input.1`）与输出名（如 `1333` / `output`）。这些值填入 Task 3 的 `NimaOnnxScorer`。

- [ ] **Step 2: 托管模型 + 在模型注册表登记**

在 `LlmModelDownloadManager.kt` 的模型注册表（`ModelConfig` 列表处）新增一项（参照 `modnet-onnx` 写法）：

```kotlin
    ModelConfig(
        id = "nima-aesthetic-onnx",
        name = "NIMA 美学评分",
        description = "离线图像美学评价，用于人脸聚类挑选代表封面",
        size = 4L * 1024 * 1024,
        sources = mapOf("modelscope" to "<repo-path>/nima_mobilenetv2_int8.onnx"),
        files = listOf("nima_mobilenetv2_int8.onnx"),
        tags = listOf("Gallery")
    ),
```

并在 `ModelConfig.RECOMMENDED_MODEL_IDS` 加入：

```kotlin
val RECOMMENDED_MODEL_IDS: Set<String> = CHAT_MODEL_IDS + setOf(
    "modnet-onnx",
    "u2netp-onnx",
    "mediapipe-face-landmarker",
    "nima-aesthetic-onnx"        // 美学封面（Plan B）
)
```

> `sources` 的实际上传地址由发布流程决定（与其它模型同托管）。`RecommendedModelAutoDownloader` 会自动把 RECOMMENDED 中未下载项在 WiFi 下静默拉取（既有逻辑，无需改）。

- [ ] **Step 3: 编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/mamba/picme/data/download/LlmModelDownloadManager.kt
git commit -m "feat(aesthetic): 注册 NIMA 美学模型到模型中心推荐层

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: media_assets +aestheticScore（Room v18→v19）

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/data/model/MediaEntity.kt`、`data/local/MediaDao.kt`、`data/local/AppDatabase.kt`

- [ ] **Step 1: MediaEntity 加列**

在 `MediaEntity`（`lastTagScanPasses` 之后）新增：

```kotlin
    /** NIMA 美学评分（1.0~10.0，null=未评分）。供人脸聚类选最佳封面。 */
    @ColumnInfo(name = "aestheticScore")
    val aestheticScore: Float? = null
```

- [ ] **Step 2: MediaDao 读写方法**

在 `MediaDao` 新增：

```kotlin
    @Query("UPDATE media_assets SET aestheticScore = :score WHERE id = :mediaId")
    suspend fun updateAestheticScore(mediaId: Long, score: Float)

    /** 取未评分且已索引的照片（限定数量，供后台分批打分）。 */
    @Query("SELECT * FROM media_assets WHERE aestheticScore IS NULL AND type = 'PHOTO' LIMIT :limit")
    suspend fun getMediaWithoutAestheticScore(limit: Int): List<com.mamba.picme.data.model.MediaEntity>
```

> `type = 'PHOTO'`：枚举在 DB 的存储形式以现有查询为准（参照既有 `type` 过滤查询；若枚举存为整数则改为相应字面量）。

- [ ] **Step 3: 迁移 v18→v19**

`AppDatabase.kt`：`@Database(..., version = 19, ...)`；在 `MIGRATION_17_18` 之后新增：

```kotlin
        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `media_assets` ADD COLUMN `aestheticScore` REAL")
            }
        }
```

并在 `addMigrations(...)` 列表末尾追加 `MIGRATION_18_19`（参照第 90-91 行既有写法）。

- [ ] **Step 4: 编译 + 既有单测**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL；既有单测 PASS（含 Room schema 一致性校验，若有）。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/data/model/MediaEntity.kt \
        app/src/main/java/com/mamba/picme/data/local/MediaDao.kt \
        app/src/main/java/com/mamba/picme/data/local/AppDatabase.kt
git commit -m "feat(aesthetic): media_assets +aestheticScore 列（Room v18→v19）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: NimaOnnxScorer（ONNX Runtime 封装）

> 参照 `domain/matting/ModNetOnnxBackend.kt` 的 OrtEnvironment/OrtSession/initialize/infer/release 范式。输入预处理：缩放到 224×224、ImageNet 归一化；输出 1×10 分布 → 加权均值得 1~10 分。

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/aesthetic/NimaOnnxScorer.kt`

- [ ] **Step 1: 实现 NimaOnnxScorer**

```kotlin
package com.mamba.picme.domain.aesthetic

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.mamba.picme.data.download.ModelPathConfig
import java.io.File

/**
 * NIMA 美学评分（ONNX Runtime，参考 ModNetOnnxBackend）。
 *
 * 模型走模型中心 `nima-aesthetic-onnx`；未下载时 [initialize] 返回 false，
 * 调用方回退（封面用旧 firstOrNull 逻辑）。
 */
class NimaOnnxScorer(private val context: Context) {
    companion object {
        private const val TAG = "PoLang:Aesthetic"
        private const val MODEL_ID = "nima-aesthetic-onnx"
        private const val FILE_NAME = "nima_mobilenetv2_int8.onnx"
        private const val INPUT_SIZE = 224
        // ImageNet 归一化
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
        // NIMA 10 档权重（1~10 分）
        private val WEIGHTS = FloatArray(10) { i -> (i + 1).toFloat() }
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    // Task 1 Step 1 记录的真实 I/O 名填入：
    private val inputName = "input.1"
    private val outputName = "1333"

    suspend fun initialize(): Boolean {
        val modelDir = ModelPathConfig.getModelDir(context, MODEL_ID)
        val modelFile = File(modelDir, FILE_NAME)
        if (!modelFile.exists()) {
            Log.w(TAG, "NIMA model not present: ${modelFile.absolutePath}")
            return false
        }
        return try {
            val options = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            }
            session = env.createSession(modelFile.absolutePath, options)
            Log.i(TAG, "NIMA session initialized")
            true
        } catch (e: Exception) {
            Log.e(TAG, "NIMA initialize failed", e)
            false
        }
    }

    /** 返回美学分 1.0~10.0；失败返回 null。 */
    fun score(bitmap: Bitmap): Float? {
        val sess = session ?: run {
            Log.w(TAG, "NIMA session not initialized")
            return null
        }
        return try {
            val input = preprocess(bitmap)
            val shape = longArrayOf(1, 3, INPUT_SIZE, INPUT_SIZE)
            val tensor = OnnxTensor.createTensor(env, input, shape)
            val output = sess.run(mapOf(inputName to tensor)).use { results ->
                @Suppress("UNCHECKED_CAST")
                val raw = results[outputName]?.value as? Array<FloatArray>
                raw
            }
            tensor.close()
            output?.firstOrNull()?.let { dist -> weightedScore(dist) }
        } catch (e: Exception) {
            Log.e(TAG, "NIMA inference failed", e)
            null
        }
    }

    private fun preprocess(bitmap: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val out = FloatArray(3 * INPUT_SIZE * INPUT_SIZE)
        val px = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(px, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (i in px.indices) {
            val r = ((px[i] shr 16) and 0xFF) / 255f
            val g = ((px[i] shr 8) and 0xFF) / 255f
            val b = (px[i] and 0xFF) / 255f
            out[i] = (r - MEAN[0]) / STD[0]
            out[INPUT_SIZE * INPUT_SIZE + i] = (g - MEAN[1]) / STD[1]
            out[2 * INPUT_SIZE * INPUT_SIZE + i] = (b - MEAN[2]) / STD[2]
        }
        if (scaled !== bitmap) scaled.recycle()
        return out
    }

    private fun weightedScore(dist: FloatArray): Float {
        val sum = dist.sum()
        if (sum <= 0f) return 5f
        var acc = 0f
        for (i in dist.indices) acc += (dist[i] / sum) * WEIGHTS[i]
        return acc
    }

    fun release() {
        session?.close()
        session = null
        Log.i(TAG, "NIMA session released")
    }
}
```

> `inputName/outputName`、输出是否需 softmax 依 Task 1 实测模型调整。`OnnxTensor.createTensor(env, FloatArray, shape)` 为 ONNX Runtime 标准用法（与 ModNet 同库）。

- [ ] **Step 2: 编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/aesthetic/NimaOnnxScorer.kt
git commit -m "feat(aesthetic): NimaOnnxScorer ONNX Runtime 封装

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: 封面选择纯逻辑（CoverSelector，TDD）

> 把"候选(美学分,mediaId)→封面"的规则抽成纯函数先行测试，避免 DAO 耦合。

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/aesthetic/CoverSelector.kt`
- Test: `app/src/test/java/com/mamba/picme/domain/aesthetic/CoverSelectorTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mamba.picme.domain.aesthetic

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverSelectorTest {
    @Test
    fun picksHighestScoreMember() {
        val candidates = listOf(
            CoverCandidate(mediaId = 1, score = 6.0f),
            CoverCandidate(mediaId = 2, score = 8.5f),
            CoverCandidate(mediaId = 3, score = 7.0f)
        )
        assertEquals(2L, CoverSelector.bestCoverMediaId(candidates))
    }

    @Test
    fun returnsNullWhenAllUnscored() {
        val candidates = listOf(
            CoverCandidate(mediaId = 1, score = null),
            CoverCandidate(mediaId = 2, score = null)
        )
        assertEquals(null, CoverSelector.bestCoverMediaId(candidates))
    }

    @Test
    fun ignoresNullScores() {
        val candidates = listOf(
            CoverCandidate(mediaId = 1, score = null),
            CoverCandidate(mediaId = 2, score = 5.0f)
        )
        assertEquals(2L, CoverSelector.bestCoverMediaId(candidates))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.aesthetic.CoverSelectorTest"`
Expected: FAIL — `CoverSelector`/`CoverCandidate` 未定义。

- [ ] **Step 3: 实现 CoverSelector**

```kotlin
package com.mamba.picme.domain.aesthetic

/** 一个候选封面：媒体 id + 美学分（未评分为 null）。 */
data class CoverCandidate(val mediaId: Long, val score: Float?)

/** 纯逻辑：从候选中选美学分最高的 mediaId；全部未评分返回 null（调用方回退旧逻辑）。 */
object CoverSelector {
    fun bestCoverMediaId(candidates: List<CoverCandidate>): Long? =
        candidates.filter { it.score != null }
            .maxByOrNull { it.score!! }
            ?.mediaId
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.aesthetic.CoverSelectorTest"`
Expected: PASS（3 用例）。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/aesthetic/CoverSelector.kt \
        app/src/test/java/com/mamba/picme/domain/aesthetic/CoverSelectorTest.kt
git commit -m "feat(aesthetic): CoverSelector 封面选择纯逻辑（最高美学分）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: PersonDao 封面刷新 + AestheticScoreWorker（后台打分+刷新封面）

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/data/local/dao/PersonDao.kt`
- Create: `app/src/main/java/com/mamba/picme/domain/aesthetic/AestheticScoreWorker.kt`
- Modify: `di/AppContainer.kt`（暴露 worker 入口，可选）

- [ ] **Step 1: PersonDao 取某人物候选 + 批量刷新封面**

在 `PersonDao` 新增（候选 = 该人物成员 mediaId + aestheticScore，经 face_embeddings JOIN media_assets）：

```kotlin
    data class PersonCoverCandidate(val mediaId: Long, val score: Float?)

    @Query(
        "SELECT m.id AS mediaId, m.aestheticScore AS score " +
            "FROM face_embeddings e INNER JOIN media_assets m ON m.id = e.mediaId " +
            "WHERE e.personId = :personId"
    )
    suspend fun getCoverCandidates(personId: Long): List<PersonCoverCandidate>

    @Query("UPDATE persons SET coverMediaId = :coverMediaId, updatedAt = :now WHERE personId = :personId")
    suspend fun updateCoverMedia(personId: Long, coverMediaId: Long?, now: Long = System.currentTimeMillis())

    /** 全部人物 personId（用于批量刷新封面）。 */
    @Query("SELECT personId FROM persons")
    suspend fun getAllPersonIds(): List<Long>
```

> `updateCoverMedia` 已存在则复用，勿重复定义（见既有 PersonDao:48）。若已存在，本步仅加 `getCoverCandidates`/`getAllPersonIds`。

- [ ] **Step 2: AestheticScoreWorker**

```kotlin
package com.mamba.picme.domain.aesthetic

import android.content.Context
import android.util.Log
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.domain.image.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 后台美学打分 + 封面刷新（A1：独立后台，不阻塞 Pass1）。
 * - 给未评分照片打分、回写 media_assets.aestheticScore；
 * - 重算每个人物封面 = CoverSelector.bestCoverMediaId(成员候选)，回退不动。
 * 模型未就绪时跳过打分，仅尝试用已有分数刷新封面。
 */
class AestheticScoreWorker(private val context: Context) {
    companion object {
        private const val TAG = "PoLang:Aesthetic"
        private const val BATCH = 50
    }

    suspend fun runOnce(): Int = withContext(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(context)
        val mediaDao = db.mediaDao()
        val personDao = db.personDao()
        val scorer = NimaOnnxScorer(context)
        val modelReady = scorer.initialize()
        if (!modelReady) Log.w(TAG, "NIMA not ready; only refresh covers with existing scores")

        var scored = 0
        try {
            if (modelReady) {
                val pending = mediaDao.getMediaWithoutAestheticScore(BATCH)
                for (entity in pending) {
                    val bmp = ImageLoader.loadBitmap(context, entity.uri, 384) // 复用既有加载器；尺寸按实现
                    if (bmp == null) continue
                    try {
                        val s = scorer.score(bmp)
                        if (s != null) {
                            mediaDao.updateAestheticScore(entity.id, s)
                            scored++
                        }
                    } finally {
                        bmp.recycle()
                    }
                }
            }
            // 刷新全部人物封面
            for (pid in personDao.getAllPersonIds()) {
                val candidates = personDao.getCoverCandidates(pid)
                    .map { CoverCandidate(it.mediaId, it.score) }
                val best = CoverSelector.bestCoverMediaId(candidates)
                if (best != null) personDao.updateCoverMedia(pid, best)
            }
            Log.i(TAG, "Aesthetic pass done: scored=$scored, persons refreshed")
        } catch (e: Exception) {
            Log.e(TAG, "Aesthetic pass failed", e)
        } finally {
            scorer.release()
        }
        scored
    }
}
```

> `ImageLoader.loadBitmap` 以仓库既有 bitmap 加载工具为准（Pass1 用 `pipeline.loadBitmap`；此处用等价工具，避免重复解码逻辑——以编译期既有符号为准，必要时改用 `BitmapFactory` 直解）。打分用 384px 足够 NIMA（再内部缩放到 224）。

- [ ] **Step 3: 触发入口（AppContainer + 闲置/聚类后）**

在 `di/AppContainer.kt` 暴露：
```kotlin
    val aestheticScoreWorker: com.mamba.picme.domain.aesthetic.AestheticScoreWorker by lazy {
        com.mamba.picme.domain.aesthetic.AestheticScoreWorker(context)
    }
```
触发时机（任选其一，最小改动）：
- 在 `TagScanOrchestrator` 末批精修后（Plan A Task 5 Step 5 附近）追加 `app.container.aestheticScoreWorker.runOnce()`；或
- 应用进入空闲/夜间时由既有调度触发。

> 若 Plan A 未合并，触发点可临时挂在设置页「相册打标」的扫描完成回调；最终以聚类完成后触发为准。

- [ ] **Step 4: 编译 + 单测**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL；单测 PASS（含 Task 4 CoverSelector）。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/data/local/dao/PersonDao.kt \
        app/src/main/java/com/mamba/picme/domain/aesthetic/AestheticScoreWorker.kt \
        app/src/main/java/com/mamba/picme/di/AppContainer.kt
git commit -m "feat(aesthetic): 后台美学打分 AestheticScoreWorker + 封面按美学分刷新

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: 文档 + i18n + 设备验证

**Files:**
- Modify: `docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md`（美学封面段）、`PRODUCT.md`、`CLAUDE.md`（模型清单如需）
- Modify: `res/values*/strings.xml`（模型中心展示名 `model_nima_aesthetic_name` 等，4 locale）

- [ ] **Step 1: 文档同步**

`docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md` 增「美学封面」：NIMA ONNX（推荐层下载）→ `aestheticScore` → `CoverSelector` → `persons.coverMediaId`。`PRODUCT.md` 人物章节注美学封面。引用 spec §3.3。

- [ ] **Step 2: i18n（模型中心文案，4 locale）**

如模型中心列表用 `ModelConfig.name/description`（已是中文字面量），则按既有做法补 EN/繁；若有 `R.string.model_*` 则同步 4 套。

- [ ] **Step 3: 设备验证（用户实地）**

`adb logcat -s "PoLang:Aesthetic"`：
- NIMA 下载完成后：`NIMA session initialized` → `Aesthetic pass done: scored=N`。
- 人物页封面切换为高分图（与 `coverMediaId` 一致）。
- 模型未下载时：`NIMA not ready`，封面回退旧逻辑，人物页正常。

- [ ] **Step 4: Commit**

```bash
git add docs/ PRODUCT.md app/src/main/res/values*/strings.xml
git commit -m "docs(aesthetic): NIMA 美学封面架构/产品文档同步 + i18n

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## 自审（Self-Review）

- **Spec 覆盖**：§3.3 美学打分（A1 后台）→ Task 5；模型交付（B1 模型中心）→ Task 1；封面选择 → Task 4/5；数据列 → Task 2；ONNX 推理 → Task 3。无遗漏。
- **占位符**：`sources` URL、`ImageLoader` 符号、`type='PHOTO'` 字面量以既有实现为准（已注明，非 TBD）。其余含完整代码/命令。
- **类型一致**：`CoverCandidate(mediaId, score)` + `CoverSelector.bestCoverMediaId(List<CoverCandidate>): Long?` 在 Task 4/5 一致；`NimaOnnxScorer.initialize(): Boolean` / `score(Bitmap): Float?` / `release()` Task 3/5 一致；`PersonDao.getCoverCandidates(personId): List<PersonCoverCandidate>` + `updateCoverMedia(personId, coverMediaId, now)` Task 5 一致；`MediaDao.updateAestheticScore/getMediaWithoutAestheticScore` Task 2/5 一致；`aestheticScore: Float?` MediaEntity(Task 2) ↔ MediaDao(Task 2) ↔ 封面选择(Task 5)。
- **前置风险**：Task 1 的 ONNX 产出是外部阻塞；未就绪时 Task 2/4 可独立合入（列+选择纯逻辑），Task 3/5 依赖模型 I/O 实测值（inputName/outputName）。回退路径明确（bestCoverMediaId 全 null → 不动 cover）。
- **隐私合规**：NIMA 100% 端侧 ONNX，无图片上传（ADR-008）。
