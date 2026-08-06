# NIMA 美学打分接入人物封面选择 — 设计

> **状态**：设计稿，待写实现计划
> **日期**：2026-08-02
> **目标**：把已托管验证的 NIMA MobileNet 美学模型接入现成的封面选择管线，给 `media_assets.aestheticScore` 填值，让 `CoverSelector` 的"美学+人脸质量双分加权"真正生效（今天 aestheticScore 全 null，封面只能按人脸质量单分挑）。

## 1. 背景：系统已就绪，只差美学那条腿

封面选择管线早已搭好并在等 NIMA：

| 组件 | 状态 |
|---|---|
| `domain/aesthetic/CoverSelector`（美学 1..10 归一 + eDifFIQA 人脸质量 加权，W_FACE=0.6 / W_AESTHETIC=0.4） | ✅ 实现 + JVM 单测 |
| `media_assets.aestheticScore: Float?` 列 + Room v18→v19 迁移 | ✅ |
| `MediaDao.updateAestheticScore` / `getMediaWithoutAestheticScore` | ✅ |
| `EdiffiqaScorer`（ONNX 人脸画质）+ `FaceAligner`（5 点对齐 112） | ✅ |
| `AestheticScoreWorker`（后台打分 + `refreshCovers()`） | ⚠️ **只跑 eDifFIQA，从不写 aestheticScore** → 封面降级为单分 |
| **`NimaScorer` + NIMA 模型注册** | ❌ **本 spec 补齐** |

红线符合：NIMA 100% 端侧推理（ONNX Runtime），不上传图片（ADR-008）。

## 2. 模型决策（已验证、已托管）

- **选型**：HF `cromsc/nima-mobilenet-aesthetic.onnx`。经本机从 idealo 官方 `weights_mobilenet_aesthetic_0.07.hdf5`（Keras 2.1.6 weights-only）重建 + tf2onnx 转换做 **parity 验证**：两者 90 张真实照分数统计**逐位相同**（min 3.60 / max 5.40 / std 0.36 / mean 4.42 / range 1.80），face.jpg 均 5.392 → 证实 HF 该模型就是 idealo canonical 权重，非劣质翻版。
- **架构**：MobileNet(V1) `include_top=False, pooling='avg'` → Dropout(0.75) → Dense(10, softmax)。
- **I/O（实测锁定）**：输入 `input_1` `[1,224,224,3]` NHWC float，预处理 `(x/127.5 - 1.0)`；输出 `dense_1` `[1,10]` softmax，`score = Σ out[i]·(i+1)`（i=0..9）∈ [1,10]。
- **体积**：12.3 MB（与 eDifFIQA 7MB 同量级，远小于 tagger）。
- **托管**：已传 ModelScope `budaoshou/nima-aesthetic-onnx`，文件 `nima_mobilenet_aesthetic.onnx`。
- **关于"分数区间窄"**：[3.6,5.4] 主要是测试集同质化（80/90 明星写真同档次）+ NIMA 固有保守地板（极端退化也只压到 ~3.8，AVA 训练通病）。对**簇内相对排名**（封面挑选的本质）排序正确即可，配合 eDifFIQA 主权重作次级 tiebreaker。真实相册方差更大、signal 更明显。int8 量化/换 backbone 作为后续可选优化（见 §9）。

## 3. 设计

### 3.1 `domain/aesthetic/NimaScorer.kt`（新，镜像 `EdiffiqaScorer`）
- ONNX Runtime（`OrtEnvironment`/`OrtSession`，CPU），模型目录 `ModelPathConfig.getModelDir(context, MODEL_ID_NIMA)`，文件名 `nima_mobilenet_aesthetic.onnx`，未就绪 `initialize()` 返回 false（调用方跳过）。
- `suspend fun initialize(): Boolean`：同 eDifFIQA 范式（BASIC_OPT）。
- `fun score(bitmap: Bitmap): Float?`：整图 resize 到 224×224 → NHWC 预处理 `(px-127.5)/127.5`（**逐像素交错 RGB**：`out[h*W*3+w*3+c]`，非 eDifFIQA 的 NCHW 三 plane 分离）→ `OrtSession.run` → 取输出首行 10 个 float → `Σ v[i]·(i+1)`，失败返回 null。
- `fun release()`：关 session。
- **与 eDifFIQA 的差异**：输入是整图（不需人脸对齐）、NHWC 布局、输出 softmax 求 EMD-style 期望分；eDifFIQA 是对齐人脸、NCHW、单标量质量分。

### 3.2 `AestheticScoreWorker` 改造（一图两分，单次解码）
- pending 查询改为"缺任一分"：`(aestheticScore IS NULL OR faceQualityScore IS NULL) AND type='PHOTO'`（新增 DAO `getMediaWithoutEitherScore(limit)`，或复用现有两个查询并合并去重；具体见计划）。
- 每张已解码 `bmp`：
  - **NIMA**（aestheticScore 为 null 时必跑，整图 resize 224）→ `updateAestheticScore`。
  - **eDifFIQA**（faceQualityScore 为 null 时，复用 `scheduler.detectFacesForScoring` 拿 5 点 → `FaceAligner.align` → 取最高人脸分；无脸则跳过）→ `updateFaceQualityScore`。
- 两个 scorer 各自 `initialize()`：任一未就绪则只跑另一个（现有降级天然扩展）。
- 打分后照旧 `refreshCovers()`（`CoverSelector` 消费双分，缺美学时单分降级逻辑已存在）。
- 触发点不变（人物页顶栏手动 `runOnce(300)`；未来可接聚类后自动触发，非本 spec 范围）。

### 3.3 模型注册（照抄 eDifFIQA 模式）
1. `ModelPathConfig`：`const val MODEL_ID_NIMA = "nima-aesthetic-onnx"`。
2. `LlmModelDownloadManager.modelFilesForId`：加 `modelId == "nima-aesthetic-onnx" -> NIMA_MODEL_FILES`（**必须在 `else -> LLM_MODEL_FILES` 之前**，否则误落到 LLM 文件集）；定义 `NIMA_MODEL_FILES = listOf("nima_mobilenet_aesthetic.onnx")`。
3. `RECOMMENDED_MODEL_IDS`：加 `"nima-aesthetic-onnx"`（走 `RecommendedModelAutoDownloader` WiFi 静默预下载，与 eDifFIQA/tagger 同层）。
4. `res/raw/llm_models.json`：新增条目（json 声明 `files` 白名单优先于 `modelFilesForId`）：
   ```json
   {
     "id": "nima-aesthetic-onnx",
     "name": "NIMA 美学评分",
     "description": "NIMA MobileNet (idealo) 图像美学评分 ONNX (~12MB)，为人物聚类挑最佳封面（构图/美学）",
     "size": 12867270,
     "sources": { "ModelScope": "budaoshou/nima-aesthetic-onnx" },
     "files": [ "nima_mobilenet_aesthetic.onnx" ],
     "tags": [ "recommended", "photo-tagging", "aesthetic", "onnx" ]
   }
   ```

### 3.4 `CoverSelector` — 不动
早已消费 `aestheticScore`，分数一写封面自动按双分重算。

## 4. 数据流

```
AestheticScoreWorker.runOnce()
  ├─ getMediaWithoutEitherScore()  → 每张解码一次
  │   ├─ NimaScorer(整图 224, NHWC [-1,1]) → media_assets.aestheticScore
  │   └─ EdiffiqaScorer(人脸对齐 112, NCHW) → media_assets.faceQualityScore
  └─ refreshCovers()
        └─ CoverSelector.bestCoverMediaId(W_FACE·q + W_AESTH·aNorm) → persons.coverMediaId
```

## 5. 错误处理 / 降级
- NIMA 模型未下载/推理失败 → 该图 aestheticScore 留 null，`CoverSelector` 单分降级（人脸质量）。
- eDifFIQA 未就绪 → 仅写美学分；两者都未就绪 → 仅用已有分数刷新封面。
- 解码/对齐失败 → 该图跳过，不影响批次（现有 try/finally 已 recycle bmp）。
- 任一模型缺失时 worker 仍能跑另一个，不相互阻塞。

## 6. 测试
- **JVM 单测**：`NimaScorer` 预处理（NHWC [-1,1] 平面展开）+ 期望分公式 `Σ v[i]·(i+1)`（纯数组变换，喂固定 float[] 断言输出）；`NimaScorer` 未初始化/失败返回 null。
- **既有**：`CoverSelector` 双分加权已有单测，回归不动。
- **设备验证**：装好后人物页手动触发 `runOnce`，看封面是否更优；可选 `/ui-driver` accessibility 驱动。**建议**在真实相册（含糊片/好片大方差）上观察分数铺开，确认 signal 比同质化测试集更明显。

## 7. 文档 / i18n / 红线
- 文档：模型中心文案（json description）、`docs/03-TECHNICAL-SPECS/TAG_GENERATION.md`（若提及美学阶段）按需同步；本设计已记录 NIMA=idealo 的 parity 结论与 I/O。
- i18n：模型名/描述若有 UI 露出（模型中心页），同步 4 套 `values/`、`values-zh/`、`values-zh-rCN/`、`values-zh-rTW/`。
- 红线：NIMA 100% 端侧，不上传图片（ADR-008）。

## 8. 不做（out of scope）
- int8 量化到 ~4MB（spec 旧目标；当前 12MB 可接受，量化作为可选后续）。
- 换 NIMA-InceptionV3/MUSIQ 等更宽 range 模型（先看 MobileNet 真实相册效果再定）。
- NIMA 用于搜索/精选等其他场景（仅先存分数）。
- 自动触发（聚类后）——保持手动触发入口。

## 9. 转换可复现性（附录）
转换脚本留 `scripts/nima/convert_idealo_to_onnx.py` + `verify_hf_onnx*.py`，记录"HF==idealo"结论与 `[-1,1]/NHWC/224` 口径来源。本机 conda env 为跑转换临时改了 `scipy 1.18→1.11.4`、`onnx→1.16.2`（原 scipy 在 numpy 1.26 下已坏，属修复）。

## 10. 与三 Pass 索引流水线关系（2026-08-06 补充， supersede §8「自动触发」条）

**定位**：美学评分不是第 4 个 Pass，而是**索引流水线之外的附属打分器（Scoring Sidecar）**。两类任务的边界：

| 维度 | 三 Pass 索引流水线（TagScanOrchestrator） | 美学评分（AestheticScoreWorker） |
|------|------------------------------------------|--------------------------------|
| 产出 | faceRoi / embedding / person / labels —— 搜索与人物的**索引数据** | aestheticScore / faceQualityScore —— 信息展示与封面选优的**增强数据** |
| 执行模型 | 会话制：任务表 + 断点续扫 + 暂停/恢复/取消 | 非会话制：循环跑批（`runUntilDone`），幂等可重入，无任务表 |
| 单张成本 | Pass 3 VLM 秒级，需要 checkpoint | ~200ms（NNAPI），全库排空约半小时，无需 checkpoint |
| 依赖关系 | Pass1 → Pass2/Pass3 链式依赖 | 仅一处弱依赖：人脸画质待打分集合 gate 在 Pass 1 的 `hasFace` 上 |

**关系规则（实现已落地）**：

1. **互斥执行**：eDifFIQA 复用 pipeline 的 RetinaFace 检测（`TagGenerationScheduler.detectFacesForScoring`，无同步保护），与 Pass 1 并发会踩同一 MNN 解释器。规则：扫描会话活跃时，手动增量触发推迟（会话完成后由 post-scan 钩子兜底）、全量重打拒绝；post-scan 自动补分发生在会话 COMPLETED 之后，天然互斥。
2. **触发**：① 扫描会话完成后自动 `runUntilDone()` 排空积压（取代旧 `runOnce()` 每会话仅 50 张、大图库永远补不齐的缺陷）；② 打标控制页「美学与人脸画质评分」卡片手动增量/全量（`ACTION_SCORE_AESTHETIC[_FULL]`，前台 Service 承载，离开页面不中断）。
3. **待打分口径**：`aestheticScore IS NULL OR (faceQualityScore IS NULL AND hasFace = 1)`。无脸照片永远写不进 faceQualityScore，若计入待打分集合会按时间序永久堵住队首（2026-08-06 实测：队首 317 张无脸旧照挡住后面 8000 张待评分照片）。
4. **进度展示**：顶部进度卡是「当前活跃任务」统一槽位——打分活跃（`AestheticScoreWorker.progress != null`）时优先显示打分进度，否则显示扫描会话进度；分阶段区的美学卡片显示累计进度（已评分/照片总数，1s 轮询 DB 统计）。
