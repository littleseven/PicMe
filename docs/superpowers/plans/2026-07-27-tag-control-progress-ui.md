# TAG 生成控制页 · 分阶段进度展示重构 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 消除 `TagGenerationControlScreen` 三处 `X / Y` 分数式歧义，改用进度条 + 「已处理 · 待处理」展示，并修正 Pass1「已完成」语义口径。

**Architecture:** 抽出一个 UI 层纯函数 `tagPassProgress(total, remaining)` 派生真实「已处理 = 总数 − 待处理」（可 JVM 单测）；`PassControlCard` 增加 `LinearProgressIndicator`；B/C 区只改文案口径去斜杠；所有新增/改写文案走 `stringResource` 同步四语言。

**Tech Stack:** Kotlin · Jetpack Compose (Material3) · Android string resources (`%1$d` 占位符) · JUnit4。

**Spec:** `docs/superpowers/specs/2026-07-27-tag-control-progress-ui-design.md`

---

## File Structure

- **Create** `app/src/main/java/com/mamba/picme/features/gallery/components/TagPassProgress.kt` — 进度派生纯函数（`TagPassProgress` data class + `tagPassProgress()`），单一职责，可测。
- **Create** `app/src/test/java/com/mamba/picme/features/gallery/components/TagPassProgressTest.kt` — 上述纯函数的 JVM 单测。
- **Modify** `app/src/main/java/com/mamba/picme/features/gallery/components/TagGenerationControlScreen.kt` — A/B/C 三区。
- **Modify** `app/src/main/res/values/strings.xml`、`values-zh/strings.xml`、`values-zh-rCN/strings.xml`、`values-zh-rTW/strings.xml` — 新增 `tag_pass_*`。

---

## Task 1: 进度派生纯函数（TDD）

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/gallery/components/TagPassProgress.kt`
- Test: `app/src/test/java/com/mamba/picme/features/gallery/components/TagPassProgressTest.kt`

- [ ] **Step 1: 写失败测试**

Create `app/src/test/java/com/mamba/picme/features/gallery/components/TagPassProgressTest.kt`:

```kotlin
package com.mamba.picme.features.gallery.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagPassProgressTest {

    @Test
    fun `partial progress computes processed as total minus remaining`() {
        // 100 张，待处理 20 → 已处理 80（不是 withFace=50，修正语义口径）
        val p = tagPassProgress(total = 100, remaining = 20)
        assertEquals(80, p.processed)
        assertEquals(20, p.remaining)
        assertEquals(0.8f, p.fraction, 1e-5f)
        assertFalse(p.isComplete)
        assertFalse(p.isEmpty)
    }

    @Test
    fun `zero remaining with positive total is complete`() {
        val p = tagPassProgress(total = 100, remaining = 0)
        assertEquals(100, p.processed)
        assertEquals(1f, p.fraction, 1e-5f)
        assertTrue(p.isComplete)
        assertFalse(p.isEmpty)
    }

    @Test
    fun `zero total is empty and never complete`() {
        val p = tagPassProgress(total = 0, remaining = 0)
        assertEquals(0, p.processed)
        assertEquals(0f, p.fraction, 1e-5f)
        assertTrue(p.isEmpty)
        assertFalse(p.isComplete)
    }

    @Test
    fun `remaining larger than total is clamped to total`() {
        val p = tagPassProgress(total = 10, remaining = 99)
        assertEquals(0, p.processed)
        assertEquals(10, p.remaining)
        assertEquals(0f, p.fraction, 1e-5f)
        assertFalse(p.isComplete)
    }

    @Test
    fun `negative inputs are clamped to zero`() {
        val p = tagPassProgress(total = -5, remaining = -3)
        assertEquals(0, p.total)
        assertEquals(0, p.remaining)
        assertEquals(0, p.processed)
        assertTrue(p.isEmpty)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.gallery.components.TagPassProgressTest"`
Expected: 编译失败 / FAIL（`tagPassProgress` 未定义）。

- [ ] **Step 3: 写实现**

Create `app/src/main/java/com/mamba/picme/features/gallery/components/TagPassProgress.kt`:

```kotlin
package com.mamba.picme.features.gallery.components

/**
 * 单个 Pass 阶段的进度快照。
 *
 * 语义：[processed] = 本阶段「已处理」数（做过检测/生成），不是「有结果数」。
 * 取代旧的 `withFace / totalMedia` 分数式——后者把「有该结果的子集（如 withFace）」
 * 误当成「已完成」，导致进度误报。真实口径：processed = total − remaining。
 */
internal data class TagPassProgress(
    val total: Int,
    val remaining: Int,
    val processed: Int,
    /** 0f..1f；total = 0 时为 0f */
    val fraction: Float,
    val isComplete: Boolean,
    val isEmpty: Boolean
)

/**
 * 由「总数」与「待处理数」派生阶段进度。所有入参会被 clamp 到安全范围。
 */
internal fun tagPassProgress(total: Int, remaining: Int): TagPassProgress {
    val safeTotal = total.coerceAtLeast(0)
    val safeRemaining = remaining.coerceIn(0, safeTotal)
    val processed = (safeTotal - safeRemaining).coerceAtLeast(0)
    val fraction = if (safeTotal > 0) processed.toFloat() / safeTotal else 0f
    return TagPassProgress(
        total = safeTotal,
        remaining = safeRemaining,
        processed = processed,
        fraction = fraction.coerceIn(0f, 1f),
        isComplete = safeTotal > 0 && safeRemaining == 0,
        isEmpty = safeTotal == 0
    )
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.gallery.components.TagPassProgressTest"`
Expected: PASS（5 tests）。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/gallery/components/TagPassProgress.kt \
        app/src/test/java/com/mamba/picme/features/gallery/components/TagPassProgressTest.kt
git commit -m "feat(tag-control): 抽出 tagPassProgress 纯函数定义阶段进度口径

processed = total − remaining,修正旧 withFace/totalMedia 把『有结果子集』
误当『已完成』的语义错误;带 JVM 单测覆盖完成/进行中/空库/越界输入。

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 2: i18n strings（四语言同步）

**Files:**
- Modify: `app/src/main/res/values/strings.xml`（`</resources>` 在 941 行）
- Modify: `app/src/main/res/values-zh/strings.xml`（`</resources>` 在 660 行）
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`（`</resources>` 在 935 行）
- Modify: `app/src/main/res/values-zh-rTW/strings.xml`（`</resources>` 在 913 行）

- [ ] **Step 1: 在四个文件的 `</resources>` 之前各插入一组 key**

**`values/strings.xml`**（EN）— 在 `</resources>` 前插入：

```xml
    <!-- Tag generation pass progress (消除 X/Y 分数式歧义) -->
    <string name="tag_pass_no_media">No photos</string>
    <string name="tag_pass_title_face">Face detection &amp; semantic encoding</string>
    <string name="tag_pass_title_cluster">Person clustering</string>
    <string name="tag_pass_title_content">Image content understanding</string>
    <string name="tag_pass_desc_face">Detect faces and extract semantic features for unprocessed photos</string>
    <string name="tag_pass_desc_cluster">Group photos into people by facial features</string>
    <string name="tag_pass_desc_content">Generate scene / activity / object tags for unprocessed photos</string>
    <string name="tag_pass_progress_p1">Processed %1$d · Pending %2$d · %3$d with face</string>
    <string name="tag_pass_progress_p3">Processed %1$d · Pending %2$d</string>
    <string name="tag_pass_cluster_done">%1$d people · %2$d embeddings</string>
    <string name="tag_pass_cluster_pending">Not clustered · %1$d embeddings to group</string>
    <string name="tag_pass_step_face">Step 1: Face detection &amp; semantic encoding</string>
    <string name="tag_pass_step_cluster">Step 2: Person clustering</string>
    <string name="tag_pass_step_content">Step 3: Image content understanding</string>
    <string name="tag_pass_overview_face">Detect faces and extract semantic features for grouping &amp; search · Processed %1$d · Pending %2$d · %3$d with face · %4$d with semantic</string>
    <string name="tag_pass_overview_cluster">%1$d people identified</string>
    <string name="tag_pass_overview_content">Analyze content to generate scene/activity/object tags &amp; summary · Processed %1$d · Pending %2$d</string>
    <string name="tag_pass_col_stage">Stage</string>
    <string name="tag_pass_col_processed">Processed</string>
    <string name="tag_pass_col_pending">Pending</string>
    <string name="tag_pass_row_face">Face detection</string>
    <string name="tag_pass_row_content">Content tags</string>
```

**`values-zh/strings.xml` 与 `values-zh-rCN/strings.xml`**（简体，两文件内容相同）— 在 `</resources>` 前插入：

```xml
    <!-- Tag 生成阶段进度(消除 X/Y 分数式歧义) -->
    <string name="tag_pass_no_media">暂无照片</string>
    <string name="tag_pass_title_face">人脸检测与语义编码</string>
    <string name="tag_pass_title_cluster">人物聚类</string>
    <string name="tag_pass_title_content">图片内容理解</string>
    <string name="tag_pass_desc_face">为未处理照片识别面孔并提取语义特征</string>
    <string name="tag_pass_desc_cluster">按面部特征将照片分组到不同人物</string>
    <string name="tag_pass_desc_content">为未处理照片生成场景、活动、物体等描述标签</string>
    <string name="tag_pass_progress_p1">已处理 %1$d · 待处理 %2$d · 有人脸 %3$d</string>
    <string name="tag_pass_progress_p3">已处理 %1$d · 待处理 %2$d</string>
    <string name="tag_pass_cluster_done">已识别 %1$d 个人物 · %2$d 条特征</string>
    <string name="tag_pass_cluster_pending">尚未聚类 · %1$d 条特征待分组</string>
    <string name="tag_pass_step_face">第一步：人脸检测与语义编码</string>
    <string name="tag_pass_step_cluster">第二步：人物聚类</string>
    <string name="tag_pass_step_content">第三步：图片内容理解</string>
    <string name="tag_pass_overview_face">识别照片中的人脸并提取语义特征，用于人物归类与智能搜索 · 已处理 %1$d 张 · 待处理 %2$d 张 · %3$d 张有人脸 · %4$d 张有语义</string>
    <string name="tag_pass_overview_cluster">已识别 %1$d 个人物</string>
    <string name="tag_pass_overview_content">分析画面内容，生成场景、活动、物体等标签与摘要 · 已处理 %1$d 张 · 待处理 %2$d 张</string>
    <string name="tag_pass_col_stage">阶段</string>
    <string name="tag_pass_col_processed">已处理</string>
    <string name="tag_pass_col_pending">待处理</string>
    <string name="tag_pass_row_face">人脸检测</string>
    <string name="tag_pass_row_content">内容标签</string>
```

**`values-zh-rTW/strings.xml`**（繁体）— 在 `</resources>` 前插入：

```xml
    <!-- Tag 生成階段進度(消除 X/Y 分數式歧義) -->
    <string name="tag_pass_no_media">暫無照片</string>
    <string name="tag_pass_title_face">人臉偵測與語意編碼</string>
    <string name="tag_pass_title_cluster">人物聚類</string>
    <string name="tag_pass_title_content">圖片內容理解</string>
    <string name="tag_pass_desc_face">為未處理照片辨識面孔並擷取語意特徵</string>
    <string name="tag_pass_desc_cluster">依臉部特徵將照片分組到不同人物</string>
    <string name="tag_pass_desc_content">為未處理照片產生場景、活動、物體等描述標籤</string>
    <string name="tag_pass_progress_p1">已處理 %1$d · 待處理 %2$d · 有人臉 %3$d</string>
    <string name="tag_pass_progress_p3">已處理 %1$d · 待處理 %2$d</string>
    <string name="tag_pass_cluster_done">已識別 %1$d 個人物 · %2$d 條特徵</string>
    <string name="tag_pass_cluster_pending">尚未聚類 · %1$d 條特徵待分組</string>
    <string name="tag_pass_step_face">第一步：人臉偵測與語意編碼</string>
    <string name="tag_pass_step_cluster">第二步：人物聚類</string>
    <string name="tag_pass_step_content">第三步：圖片內容理解</string>
    <string name="tag_pass_overview_face">辨識照片中的人臉並擷取語意特徵，用於人物歸類與智慧搜尋 · 已處理 %1$d 張 · 待處理 %2$d 張 · %3$d 張有人臉 · %4$d 張有語意</string>
    <string name="tag_pass_overview_cluster">已識別 %1$d 個人物</string>
    <string name="tag_pass_overview_content">分析畫面內容，產生場景、活動、物體等標籤與摘要 · 已處理 %1$d 張 · 待處理 %2$d 張</string>
    <string name="tag_pass_col_stage">階段</string>
    <string name="tag_pass_col_processed">已處理</string>
    <string name="tag_pass_col_pending">待處理</string>
    <string name="tag_pass_row_face">人臉偵測</string>
    <string name="tag_pass_row_content">內容標籤</string>
```

- [ ] **Step 2: 校验四文件 key 数量一致**

Run: `for d in values values-zh values-zh-rCN values-zh-rTW; do printf "%s: " "$d"; grep -c 'name="tag_pass_' "app/src/main/res/$d/strings.xml"; done`
Expected: 四个文件均输出 `: 21`。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-zh/strings.xml \
        app/src/main/res/values-zh-rCN/strings.xml app/src/main/res/values-zh-rTW/strings.xml
git commit -m "i18n(tag-control): 新增 tag_pass_* 阶段进度文案四语言

覆盖 EN/简体(values-zh,values-zh-rCN)/繁体;含进度条文字、阶段标题、
概览说明、表格表头;带 %1\$d 占位符承接 processed/remaining/withFace。

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 3: A 区 — `PassControlCard` 引入进度条

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/gallery/components/TagGenerationControlScreen.kt`
  - import 区（加 `kotlin.math.roundToInt`）
  - `PassControlCard` 定义（约 657–736 行）
  - 三处调用（约 377–418 行）

- [ ] **Step 1: 加 import**

在 import 区（`import kotlinx.coroutines.launch` 附近）新增一行：

```kotlin
import kotlin.math.roundToInt
```

- [ ] **Step 2: 重写 `PassControlCard` 定义**

把现有 `PassControlCard`（约 657–736 行整段）替换为：

```kotlin
@Composable
private fun PassControlCard(
    title: String,
    description: String,
    progress: TagPassProgress?,
    progressText: String,
    onIncremental: () -> Unit,
    onFull: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                if (progress != null) {
                    Spacer(Modifier.height(6.dp))
                    if (progress.isEmpty) {
                        Text(
                            stringResource(R.string.tag_pass_no_media),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LinearProgressIndicator(
                                progress = { progress.fraction },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(6.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            if (progress.isComplete) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = Color(0xFF4CAF50)
                                )
                            } else {
                                Text(
                                    "${(progress.fraction * 100).roundToInt()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
                if (progressText.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = progressText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            Column(
                modifier = Modifier.padding(start = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .clickable { onIncremental() }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AddCircleOutline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.incremental),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
                Row(
                    modifier = Modifier
                        .clickable { onFull() }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Replay,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.full_regenerate),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}
```

> ⚠️ 按钮文字「增量」「全量」上方用了 `R.string.incremental` / `R.string.full_regenerate`。若这两个 key 不存在（既有硬编码），先 grep 确认；不存在则在 Task 2 的四文件里补这两个 key（EN: Incremental / Full；简: 增量 / 全量；繁: 增量 / 全量），或本步先保留硬编码 `"增量"`/`"全量"` 以匹配既有风格。**默认做法**：保留硬编码 `"增量"` / `"全量"`，与既有未迁移文案一致，避免范围蔓延。

- [ ] **Step 3: 替换三处 `PassControlCard(...)` 调用（约 377–418 行）**

把「分阶段独立控制」里的三处调用替换为：

```kotlin
                    val pass1Progress = tagPassProgress(totalMedia, remainingPass1)
                    val pass1Text = stringResource(
                        R.string.tag_pass_progress_p1,
                        pass1Progress.processed,
                        pass1Progress.remaining,
                        withFace
                    )
                    PassControlCard(
                        title = stringResource(R.string.tag_pass_title_face),
                        description = stringResource(R.string.tag_pass_desc_face),
                        progress = pass1Progress,
                        progressText = if (pass1Progress.isEmpty) "" else pass1Text,
                        onIncremental = {
                            refreshStats()
                            context.startForegroundService(TagGenerationService.intentScanPass1(context))
                        },
                        onFull = {
                            refreshStats()
                            context.startForegroundService(TagGenerationService.intentScanPass1Full(context))
                        }
                    )

                    Spacer(Modifier.height(8.dp))

                    val clusterText = if (personCount > 0) {
                        stringResource(R.string.tag_pass_cluster_done, personCount, embeddingCount)
                    } else {
                        stringResource(R.string.tag_pass_cluster_pending, embeddingCount)
                    }
                    PassControlCard(
                        title = stringResource(R.string.tag_pass_title_cluster),
                        description = stringResource(R.string.tag_pass_desc_cluster),
                        progress = null,
                        progressText = clusterText,
                        onIncremental = {
                            refreshStats()
                            context.startForegroundService(TagGenerationService.intentScanPass2(context))
                        },
                        onFull = {
                            refreshStats()
                            context.startForegroundService(TagGenerationService.intentScanPass2Full(context))
                        }
                    )

                    Spacer(Modifier.height(8.dp))

                    val pass3Progress = tagPassProgress(totalMedia, remainingPass3)
                    val pass3Text = stringResource(
                        R.string.tag_pass_progress_p3,
                        pass3Progress.processed,
                        pass3Progress.remaining
                    )
                    PassControlCard(
                        title = stringResource(R.string.tag_pass_title_content),
                        description = stringResource(R.string.tag_pass_desc_content),
                        progress = pass3Progress,
                        progressText = if (pass3Progress.isEmpty) "" else pass3Text,
                        onIncremental = {
                            refreshStats()
                            context.startForegroundService(TagGenerationService.intentScanPass3(context))
                        },
                        onFull = {
                            refreshStats()
                            context.startForegroundService(TagGenerationService.intentScanPass3Full(context))
                        }
                    )
```

- [ ] **Step 4: 编译验证**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。若 `LinearProgressIndicator(progress = {...})` 报签名不匹配，改回位置参数 `progress = progress.fraction`（旧 API，会有 deprecation 警告但可编译）。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/gallery/components/TagGenerationControlScreen.kt
git commit -m "feat(tag-control): 分阶段独立控制改用进度条+已处理/待处理

PassControlCard 增 LinearProgressIndicator(满条对勾/百分比/空库三态);
Pass1 用真实 processed=total−remaining,withFace 降为补充;Pass2 不画条
仅计数;去斜杠,文案接入 stringResource。

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 4: B 区 — 处理阶段概览文案去斜杠改口径

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/gallery/components/TagGenerationControlScreen.kt`（约 264–320 行三步说明）

- [ ] **Step 1: 替换「第一步」标题 + 副标题**

把第一步的 `Text("第一步：人脸检测与语义编码", ...)` 改为：

```kotlin
                            Text(stringResource(R.string.tag_pass_step_face), style = MaterialTheme.typography.bodyMedium)
```

其下副标题 `Text("识别照片中的人脸...· $withFace / $totalMedia 张已完成 · 有语义 $withSemantic 张", ...)` 改为：

```kotlin
                            Text(
                                stringResource(
                                    R.string.tag_pass_overview_face,
                                    totalMedia - remainingPass1,
                                    remainingPass1,
                                    withFace,
                                    withSemantic
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
```

- [ ] **Step 2: 替换「第二步」标题 + 副标题**

标题改为：

```kotlin
                            Text(stringResource(R.string.tag_pass_step_cluster), style = MaterialTheme.typography.bodyMedium)
```

副标题改为：

```kotlin
                            Text(
                                stringResource(R.string.tag_pass_overview_cluster, personCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
```

- [ ] **Step 3: 替换「第三步」标题 + 副标题**

标题改为：

```kotlin
                            Text(stringResource(R.string.tag_pass_step_content), style = MaterialTheme.typography.bodyMedium)
```

副标题改为：

```kotlin
                            Text(
                                stringResource(
                                    R.string.tag_pass_overview_content,
                                    totalMedia - remainingPass3,
                                    remainingPass3
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
```

- [ ] **Step 4: 编译验证**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/gallery/components/TagGenerationControlScreen.kt
git commit -m "refactor(tag-control): 处理阶段概览去斜杠改『已处理/待处理』口径

第一/三步副标题用 processed=total−remaining 替代 withFace/totalMedia;
文案接入 tag_pass_overview_*;第二步标题/副标题一并 i18n。

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 5: C 区 — 阶段进度表格去斜杠

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/gallery/components/TagGenerationControlScreen.kt`
  - 表头 `StatsPassTableHeader`（约 908–935 行）
  - 表格调用（约 853–856 行）

- [ ] **Step 1: 改表头文案**

`StatsPassTableHeader` 内三个 `Text` 的 `text` 改为 stringResource：

```kotlin
        Text(
            text = stringResource(R.string.tag_pass_col_stage),
            modifier = Modifier.weight(0.22f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Text(
            text = stringResource(R.string.tag_pass_col_processed),
            modifier = Modifier.weight(0.46f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            textAlign = TextAlign.End
        )
        Text(
            text = stringResource(R.string.tag_pass_col_pending),
            modifier = Modifier.weight(0.32f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            textAlign = TextAlign.End
        )
```

- [ ] **Step 2: 改两行表格调用**

把约 855–856 行：

```kotlin
            StatsPassTableRow("人脸检测", "$withFace / $totalMedia", remainingPass1.toString())
            StatsPassTableRow("内容标签", "$withLabels / $totalMedia", remainingPass3.toString())
```

替换为：

```kotlin
            StatsPassTableRow(
                pass = stringResource(R.string.tag_pass_row_face),
                done = (totalMedia - remainingPass1).toString(),
                remaining = remainingPass1.toString()
            )
            StatsPassTableRow(
                pass = stringResource(R.string.tag_pass_row_content),
                done = (totalMedia - remainingPass3).toString(),
                remaining = remainingPass3.toString()
            )
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/gallery/components/TagGenerationControlScreen.kt
git commit -m "refactor(tag-control): 阶段进度表格去斜杠,完成列改『已处理』纯数字

表头 Pass/完成/剩余 → 阶段/已处理/待处理;行值用 processed=total−remaining
替代 withFace/totalMedia;接入 stringResource。

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 6: 全量验证 + 验收

**Files:** 无（仅验证）

- [ ] **Step 1: 跑全部 JVM 单测**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL（含 Task 1 的 5 个新测试 + 既有测试无回归）。

- [ ] **Step 2: grep 验收 — 三处无 `X / Y` 斜杠分数式**

Run: `grep -nE '/ \$?totalMedia|/ \$?withLabels|/ \$?withFace' app/src/main/java/com/mamba/picme/features/gallery/components/TagGenerationControlScreen.kt`
Expected: 无输出（零命中）。

- [ ] **Step 3: grep 验收 — 不再硬编码进度口径文案（本改动范围内）**

Run: `grep -n '张已完成\|/ \$totalMedia' app/src/main/java/com/mamba/picme/features/gallery/components/TagGenerationControlScreen.kt`
Expected: 无输出。

- [ ] **Step 4: lint / 代码风格自检（可选）**

Run: `./gradlew :app:lint`
Expected: 无新增 ERROR（既有问题忽略）。

- [ ] **Step 5: 目视验收（设备，若有连接）**

```
adb install -r app/build/outputs/apk/debug/polang-debug.apk
adb shell am start -n com.mamba.picme/.MainActivity   # 导航至：设置→相册功能→TAG 生成控制
adb logcat -s "PoLang:*" "TagGenControl:*"
```

核对：
- Pass1「已处理」= 总数 − 待处理（不是「有人脸的张数」）。
- Pass2 无进度条，仅「已识别 N 人」或「尚未聚类」。
- 进度条三态：满条+对勾（已完成）/ 百分比（进行中）/「暂无照片」（空库）。
- 中/英/繁切换文案正确。

- [ ] **Step 6: 若 Step 1–4 有任何修复，统一补一个验证 commit；否则跳过**

```bash
git commit --allow-empty -m "test(tag-control): 进度展示重构验收通过(单测+grep+编译)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Self-Review 备注

- **Spec 覆盖**：A/B/C 三区 + i18n + 语义口径修正均有对应 Task；进度条三态在 Task 3 Step 2 实现；Pass2 不画条在 Task 3 Step 3（progress=null）。
- **类型一致**：`TagPassProgress.processed/remaining/total/fraction/isComplete/isEmpty` 在 Task 1 定义，Task 3 使用一致；`tagPassProgress(total, remaining)` 签名一致。
- **占位符**：`tag_pass_progress_p1` 三个 `%d` 对应 (processed, remaining, withFace)；`tag_pass_overview_face` 四个 `%d` 对应 (processed, remaining, withFace, withSemantic) — 调用处实参顺序一致。
