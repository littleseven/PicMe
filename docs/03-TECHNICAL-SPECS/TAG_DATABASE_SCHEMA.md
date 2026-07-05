# TAG 检测结果数据库存储文档

> 本文档记录 PicMe 相册 TAG 生成流程在数据库中的表结构、字段含义、数据流转关系。
> 最后更新：2026-06-30

---

## 1. 数据库概览

- **数据库名**：`picme_database`
- **版本**：6
- **涉及表**：8 张（核心 TAG 相关 5 张 + 辅助 3 张）

---

## 2. 核心表结构

### 2.1 media_assets（媒体资产主表）

存储每张照片/视频的元数据及 TAG 生成结果。

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | Long (PK) | 自增主键，媒体唯一标识 |
| `uri` | String | Content URI，指向系统媒体库 |
| `type` | MediaType | 类型：IMAGE / VIDEO |
| `captureDate` | Long | 拍摄时间戳（毫秒） |
| `fileName` | String | 文件名 |
| `duration` | Long? | 视频时长（图片为 null） |
| `hasFace` | Boolean | 是否检测到人脸（Pass 1 产出） |
| `faceId` | String? | 人物簇 ID（Pass 2 聚类后填充） |
| `source` | String? | 来源标识 |
| `labels` | String? | **TAG 结果 JSON**（Pass 3 产出，见 §3.1） |
| `mlKitLabels` | String? | **ML Kit 英文标签 JSON 数组**（Pass 5 产出，见 §3.5） |
| `ocrText` | String? | OCR 提取的文字 |
| `latitude` | Double? | GPS 纬度 |
| `longitude` | Double? | GPS 经度 |
| `locationName` | String? | 逆地理编码地名 |
| `indexedAt` | Long? | 索引完成时间戳（null=未索引） |
| `faceRoiResult` | String? | **人脸 ROI 检测 JSON**（Pass 1 产出，见 §3.2） |
| `semanticEmbedding` | String? | **MobileCLIP 语义 embedding**（512维 FloatArray 的 Base64，Pass 1 内联产出） |
| `lastTagScanAt` | Long? | 最近一次 TAG 扫描成功时间戳 |
| `lastTagScanPasses` | String? | 已完成的 Pass 阶段 JSON，如 `{"1":ts,"2":ts,"3":ts}` |

**关键索引**：无显式索引（通过 `lastTagScanAt` 和 `faceRoiResult` 等字段做条件查询）

---

### 2.2 tags（标签词表）

规范化标签的去重存储表。

| 字段 | 类型 | 说明 |
|------|------|------|
| `tagId` | Long (PK) | 自增主键 |
| `name` | String | 标签名称（唯一索引） |
| `category` | String | 类别：scene / animal / object / food / person / other |

**索引**：`name` 唯一索引

---

### 2.3 media_tag_cross_ref（媒体-标签关联表）

多对多关联表，记录每张媒体被打上的标签及置信度。

| 字段 | 类型 | 说明 |
|------|------|------|
| `mediaId` | Long (PK, FK) | 关联 media_assets.id |
| `tagId` | Long (PK, FK) | 关联 tags.tagId |
| `confidence` | Float? | ML Kit 置信度（TAG 流程中未使用） |

**外键**：
- `mediaId` → `media_assets.id` (CASCADE)
- `tagId` → `tags.tagId` (CASCADE)

**索引**：`tagId`

---

### 2.4 persons（人物聚类表）

人脸聚类后生成的人物去重表。

| 字段 | 类型 | 说明 |
|------|------|------|
| `personId` | Long (PK) | 自增主键，人物唯一标识 |
| `name` | String? | 人物名称（用户可编辑） |
| `coverMediaId` | Long? | 封面媒体 ID |
| `faceCount` | Int | 该人物关联的人脸数量 |
| `createdAt` | Long | 创建时间戳 |
| `updatedAt` | Long | 更新时间戳 |

---

### 2.5 face_embeddings（人脸 Embedding 表）

存储 Glint360K R100 提取的 512 维特征向量，用于聚类匹配。

| 字段 | 类型 | 说明 |
|------|------|------|
| `embeddingId` | Long (PK) | 自增主键 |
| `mediaId` | Long | 关联 media_assets.id |
| `personId` | Long? | 关联 persons.personId（聚类后填充） |
| `embedding` | ByteArray | 512 维 FloatArray 的序列化字节 |
| `createdAt` | Long | 创建时间戳 |

**外键**：`personId` → `persons.personId` (SET_NULL)
**索引**：`personId`

---

### 2.6 tag_scan_tasks（扫描任务队列表）

5-Pass 混合管道的原子任务持久化，支持暂停/恢复/失败重试。

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | Long (PK) | 自增主键 |
| `sessionId` | String | 扫描会话 ID |
| `mediaId` | Long | 关联媒体 ID |
| `pass` | TagScanPass | 阶段：FACE_DETECTION / DBSCAN / QWEN_TAGGING / MOBILE_CLIP_ENCODING / ML_KIT_TAGGING |
| `tagCategories` | String? | 目标 TAG 类别 JSON 数组，null=全部 |
| `status` | TagScanTaskStatus | 状态：PENDING / RUNNING / PAUSED / COMPLETED / FAILED / CANCELLED |
| `priority` | Int | 优先级（数值越小越优先） |
| `attemptCount` | Int | 已尝试次数 |
| `createdAt` | Long | 创建时间戳 |
| `scheduledAt` | Long? | 计划执行时间（失败重试退避） |
| `startedAt` | Long? | 开始执行时间 |
| `completedAt` | Long? | 完成时间 |
| `errorMessage` | String? | 失败原因 |

**索引**：
- `(status, priority, scheduledAt)`
- `(mediaId, pass, status)`
- `(sessionId, status)`

---

### 2.7 location_hierarchy（地理层级表）

行政区划 + POI 去重存储。

| 字段 | 类型 | 说明 |
|------|------|------|
| `locationId` | Long (PK) | 自增主键 |
| `country` | String? | 国家 |
| `province` | String? | 省份 |
| `city` | String? | 城市 |
| `district` | String? | 区县 |
| `poi` | String? | POI 名称 |
| `latitude` | Double? | 纬度（4位小数精度去重） |
| `longitude` | Double? | 经度（4位小数精度去重） |

**索引**：`city`, `province`

---

### 2.8 media_locations（媒体-位置关联表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `mediaId` | Long (PK, FK) | 关联 media_assets.id |
| `locationId` | Long (PK, FK) | 关联 location_hierarchy.locationId |
| `accuracy` | Float? | GPS 精度 |

---

## 3. JSON 数据格式

### 3.1 `labels` 字段格式（Pass 3 产出）

Qwen 图像理解生成的最终 TAG 结果，存储为 JSON 对象：

```json
{
  "face": {
    "count": 2,
    "selfie": false,
    "groupPhoto": true,
    "personIds": [1, 3]
  },
  "scene": "户外公园",
  "activity": "野餐",
  "objects": ["毯子", "食物", "篮子"],
  "tags": ["春天", "家庭", "休闲"],
  "qwenSummary": "一家人在公园野餐的温馨场景"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `face.count` | Int | 人脸数量 |
| `face.selfie` | Boolean | 是否自拍 |
| `face.groupPhoto` | Boolean | 是否合影（≥3人） |
| `face.personIds` | Long[] | 关联的人物 ID 列表 |
| `scene` | String | 场景描述 |
| `activity` | String | 活动描述 |
| `objects` | String[] | 检测到的物体 |
| `tags` | String[] | 标签列表 |
| `qwenSummary` | String | 摘要描述 |

---

### 3.2 `faceRoiResult` 字段格式（Pass 1 产出）

人脸检测结果的轻量持久化，用于 Pass 1→Pass 3 断点续扫：

```json
{
  "hasFace": true,
  "faceCount": 2,
  "isSelfie": false,
  "isGroupPhoto": true
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `hasFace` | Boolean | 是否有人脸 |
| `faceCount` | Int | 人脸数量 |
| `isSelfie` | Boolean | 是否自拍（faceCount==1） |
| `isGroupPhoto` | Boolean | 是否合影（faceCount≥2） |

> **注意**：2026-06-27 优化后，106 点 landmarks 不再存储（RetinaFace bbox 已足够）。

---

### 3.3 `semanticEmbedding` 字段格式

MobileCLIP-S0 生成的 512 维语义 embedding，存储为 **FloatArray 的 Base64 字符串**。

- 模型：MobileCLIP-S0 (vision_model ~45MB)
- 维度：512
- 归一化：L2 归一化
- 用途：自然语言图片搜索（余弦相似度匹配）

---

### 3.5 `mlKitLabels` 字段格式（Pass 5 产出）

ML Kit Image Labeler 输出的英文标签，按置信度过滤后存储为 JSON 数组：

```json
["Outdoor", "Food", "Plant"]
```

| 字段 | 类型 | 说明 |
|------|------|------|
| 数组元素 | String | 英文标签文本，不经过翻译 |
| 置信度 | — | 已在提取时按 `confidenceThreshold` 过滤，不额外存储 |

### 3.6 `lastTagScanPasses` 字段格式

记录各 Pass 的完成时间戳，用于增量扫描去重：

```json
{"1": 1750992000000, "2": 1750992100000, "3": 1750992200000}
```

| Key | 含义 |
|-----|------|
| `"1"` | Pass 1（人脸检测 + MobileCLIP 内联合并）完成时间 |
| `"2"` | Pass 2（DBSCAN 聚类）完成时间 |
| `"3"` | Pass 3（Qwen 标签）完成时间 |
| `"4"` | Pass 4（MobileCLIP 单独重编码，保留兼容）完成时间 |
| `"5"` | Pass 5（ML Kit 英文标签）完成时间 |

---

## 4. 5-Pass 数据流转

```
┌─────────────────────────────────────────────────────────────────┐
│  Pass 1: 人脸检测 + MobileCLIP 语义编码（同一张 Bitmap）          │
│  ├─ faceDetector.detectFacesOnly(bitmap) → ROI 列表            │
│  ├─ faceClusterEngine.extractFeature(bitmap, roi) → embedding   │
│  ├─ mobileClipEngine.encode(bitmap) → 512维语义向量              │
│  │                                                              │
│  └─ 写入 DB:                                                    │
│     • media_assets.hasFace = true/false                        │
│     • media_assets.faceRoiResult = JSON（人脸上下文）             │
│     • media_assets.semanticEmbedding = Base64(512维向量)          │
│     • face_embeddings.embedding = ByteArray(512维)             │
│     • media_assets.lastTagScanPasses += {"1": ts}               │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│  Pass 2: DBSCAN 全局聚类（批量，mediaId = -1 标记）              │
│  ├─ 读取所有未聚类的 face_embeddings                            │
│  ├─ 余弦距离矩阵 + DBSCAN 聚类                                  │
│  │                                                              │
│  └─ 写入 DB:                                                    │
│     • persons 表新增/更新人物簇                                  │
│     • face_embeddings.personId = 聚类分配的人物 ID              │
│     • media_assets.faceId = personId（可选，用于快速查询）       │
│     • media_assets.lastTagScanPasses += {"2": ts}               │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│  Pass 3: Qwen 图像理解标签生成                                   │
│  ├─ 从 faceRoiResult 恢复人脸上下文（无需重新检测）               │
│  ├─ llmEngine.imageInference(bitmap, prompt) → JSON 标签         │
│  │                                                              │
│  └─ 写入 DB:                                                    │
│     • media_assets.labels = 最终 TAG JSON                        │
│     • tags 表新增规范化标签（去重）                               │
│     • media_tag_cross_ref 新增关联记录                           │
│     • media_assets.lastTagScanPasses += {"3": ts}               │
│     • media_assets.lastTagScanAt = 当前时间戳                   │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│  Pass 5: ML Kit Image Labeler 英文标签提取                       │
│  ├─ mlKitTagExtractor.extract(uri) → 英文标签列表                │
│  │                                                              │
│  └─ 写入 DB:                                                    │
│     • media_assets.mlKitLabels = JSON 英文标签数组                │
│     • media_assets.lastTagScanPasses += {"5": ts}               │
└─────────────────────────────────────────────────────────────────┘

> Pass 4（MOBILE_CLIP_ENCODING）在常规扫描中已内联合并到 Pass 1，枚举值保留用于
> 历史任务兼容及单独重编码场景，此处不再单独绘制数据流。
```

---

## 5. 关键 DAO 操作

### 5.1 MediaDao（媒体表操作）

```kotlin
// Pass 1 相关
updateFaceRoiResult(mediaId, json, hasFace)     // 写入人脸检测结果
updateSemanticEmbedding(mediaId, embedding)     // 写入语义 embedding
updateHasFace(mediaId, hasFace)                 // 更新人脸标记

// Pass 3 相关
updateLabels(mediaId, labels)                   // 写入 AI 标签
getUnlabeledMedia()                             // 获取未标签媒体

// 查询
getMediaWithoutFaceRoi()                        // 未 Pass 1 的媒体
getMediaWithFaceRoiWithoutLabels()              // 已 Pass 1 但未 Pass 3
getMediaNeedingSemanticEncoding()               // 有 labels 但无 semantic（旧数据兼容）
getMediaWithSemanticEmbedding()                 // 已有语义 embedding

// 重置
resetAllFaceData()                              // 清空人脸数据（全量重跑）
resetAllLabels()                                // 清空标签（Pass 3 重跑）
resetAllSemanticEmbeddings()                    // 清空语义 embedding
updateLastTagScan(mediaId, timestamp, passesJson) // 更新扫描记录
```

### 5.2 PersonDao（人物聚类操作）

```kotlin
insertPerson(person)                            // 新增人物簇
updatePersonName(personId, name)                // 编辑人物名
getAllPersons()                                 // 获取所有人物
getMediaByPerson(personId)                      // 获取某人物的所有照片
assignEmbeddingByMediaId(mediaId, personId)     // 聚类分配
resetAllEmbeddingAssignments()                  // 重置聚类（保留 embedding）
clearAllEmbeddings() / clearAllPersons()        // 清空（全量重聚）
```

### 5.3 TagDao（标签操作）

```kotlin
insertTag(tag)                                  // 新增标签词
insertMediaTag(crossRef)                        // 关联媒体-标签
getTagsForMedia(mediaId)                        // 获取某媒体的所有标签
searchByTagName(query)                          // 按标签搜索媒体
clearTagsForMedia(mediaId)                      // 清空某媒体的标签关联
```

---

## 6. 数据库版本迁移历史

| 版本 | 变更内容 |
|------|----------|
| 2 → 3 | 新增 `tag_scan_tasks` 表；`media_assets` 新增 `lastTagScanAt` / `lastTagScanPasses` |
| 3 → 4 | `media_assets` 新增 `semanticEmbedding`（MobileCLIP 语义向量） |
| 4 → 5 | 空迁移（修复设备上版本已升到 5 的问题） |
| 5 → 6 | `media_assets` 新增 `mlKitLabels`（ML Kit 英文标签 JSON）；`TagScanPass` 新增 `ML_KIT_TAGGING` |

---

## 7. 统计查询示例

```sql
-- 各 Pass 完成进度
SELECT
    COUNT(*) as total,
    SUM(CASE WHEN faceRoiResult IS NOT NULL THEN 1 ELSE 0 END) as pass1_done,
    SUM(CASE WHEN faceId IS NOT NULL THEN 1 ELSE 0 END) as pass2_done,
    SUM(CASE WHEN labels IS NOT NULL THEN 1 ELSE 0 END) as pass3_done,
    SUM(CASE WHEN semanticEmbedding IS NOT NULL THEN 1 ELSE 0 END) as semantic_done,
    SUM(CASE WHEN mlKitLabels IS NOT NULL THEN 1 ELSE 0 END) as pass5_done
FROM media_assets;

-- 人物簇统计
SELECT p.name, p.faceCount, COUNT(DISTINCT e.mediaId) as mediaCount
FROM persons p
LEFT JOIN face_embeddings e ON p.personId = e.personId
GROUP BY p.personId
ORDER BY p.faceCount DESC;

-- 标签分布
SELECT t.name, t.category, COUNT(*) as mediaCount
FROM tags t
JOIN media_tag_cross_ref m ON t.tagId = m.tagId
GROUP BY t.tagId
ORDER BY mediaCount DESC;
```

---

## 8. 注意事项

1. **`labels` 与 `media_tag_cross_ref` 的关系**：
   - `labels` 是 Qwen 生成的原始 JSON（含 face/scene/activity/objects/tags/summary）
   - `media_tag_cross_ref` 是规范化后的标签词表关联（用于搜索）
   - 两者不同步：`labels` 是完整结果，`media_tag_cross_ref` 是搜索索引

2. **`faceId` 字段**：
   - 旧版直接存储 personId 字符串
   - 新版通过 `face_embeddings.personId` 关联（更灵活，支持多脸/媒体）
   - `faceId` 保留用于快速查询兼容

3. **`semanticEmbedding` 存储**：
   - 512 维 FloatArray → `ByteBuffer` → Base64 字符串
   - 读取时反向解码：Base64 → ByteArray → FloatBuffer → FloatArray

4. **增量扫描去重**：
   - 通过 `lastTagScanAt` + `lastTagScanPasses` 判断是否需要重新扫描
   - 用户可设置"避重时间窗口"（如 7 天内不再扫描）
