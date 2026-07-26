# Florence-2 ONNX decoder `use_cache_branch=true` 缓存分支 Bug 记录

- 日期：2026-07-26
- 状态：**已修复 ✅**（当天攻坚完成）。根因 = optimum 导出 bug（非量化、非 ORT），图手术修复，
  PC 三链路逐 token 验证 + 设备端实测通过。生产路径已切换 KV cache（旧模型文件自动回退 no-cache）。
- 历史：当日上午记录为"已知问题（已绕过）"，用 no-cache 全量重算（O(n²)，~8–14s/张）。

## 1. 现象（历史）

Florence-2 merged decoder（`decoder_model_merged_quantized.onnx` / `decoder_model_merged_q4.onnx`）
在 ORT 上以 `use_cache_branch=True`（带 KV cache）跑第 2+ 步时必崩：

```
Non-zero status code returned while running MatMul node.
Name: /language_model/model/decoder/layers.0/encoder_attn/MatMul
Status Message: matmul_helper.h:144 Compute right operand cannot broadcast on dim 0
```

当时观察：仅 `use_cache_branch=True` 分支崩；INT8 和 q4 都崩，误以为"与量化无关"只是猜测。

## 2. 根因（2026-07-26 下午定位）

**optimum 导出 bug，与量化、与 ORT 版本均无关。**

1. **决定性反证**：未量化 fp32 `decoder_model_merged.onnx` 的 cache 分支**同样崩在同一节点**
   （`scripts/florence2_fp32_merged_cache_test.py`）。且 **decode 第 1 步能跑、第 2 步才崩**——
   说明 bug 在 True 分支自己的输出里。
2. **子图证据**：merged decoder 的 `If(then_branch)`（decode 缓存步）中，
   `present.{L}.encoder.{key,value}`（6 层 × 2 = 12 个输出）被导成
   **shape=(0,12,1,64) 的空 Constant 节点**（fp32/INT8/q4 三个 merged 模型 12/12 全中）。
   - decode 第 1 步：cross-attn 消费的是 prefill（else 分支）产出的真 encoder KV → 正常；
   - 第 1 步输出的 present.encoder = 空张量 → 回喂第 2 步 → MatMul 右操作数 dim 0 = 0 → 崩。
3. **语义**：cross-attn 的 K/V 来自 encoder_hidden_states，decode 全程不变，
   present.encoder 本应是 past.encoder 的直通（then 分支内 cross-attn 计算本身
   正确使用了 `past_key_values.{L}.encoder.*` 输入，只是输出被导错了）。

## 3. 修复：ONNX 图手术（`scripts/florence2_fix_merged_decoder.py`）

把 then_branch 里 12 个空 Constant 替换为
`Identity(past_key_values.{L}.encoder.{key,value})` 直通，
并把子图输出 value_info 形状从 else 分支同名输出拷贝同步。

产出（模型目录 `/Users/guoshuai/code/florence-2-onnx`，**本地与 ModelScope 均已同名替换为修复版，
无 `_fixed` 后缀**）：
- `decoder_model_merged.onnx`（fp32）
- `decoder_model_merged_quantized.onnx`（INT8，**生产分发版**，与 ModelScope 同名同内容）
- `decoder_model_merged_q4.onnx`（q4f16 备用）

**同名替换的兼容设计**：fixed 版（98,178,346 B）与旧版（98,177,854 B）差 492B，
`Florence2Tagger` 按文件大小区分模式（fixed→CACHE，旧版→no-cache 兜底）；
下载管理器按远端 size 校验，旧副本会因大小不符自动重下为 fixed 版。

## 4. 验证

### 4.1 PC（`scripts/florence2_cache_verify.py`，以已对齐 PyTorch 的 no-cache 为 ground truth）

| 链路 | task | no-cache | cache | 加速 | 一致性 |
|---|---|---|---|---|---|
| fp32 | OD 24 toks | 1.27s | 0.55s | 2.3x | ✅ 逐 token 一致 |
| q4 | OD 24 toks | 1.14s | 0.34s | 3.3x | ✅ 逐 token 一致 |
| q4 | CAPTION 97 toks | 6.14s | 1.02s | 6.0x | ✅ 逐 token 一致 |
| INT8 | CAPTION 96 toks | 4.54s | 1.06s | 4.3x | ⚠️ 微小数值漂移 |

- INT8 的漂移是量化数值噪声（计算顺序不同导致舍入路径不同；如 loc_304 vs loc_305），
  输出仍连贯正确；fp32/q4 逐 token 一致证明手术语义精确。序列越长加速越明显（O(n²)→O(n)）。
- INT8 fixed 在 `ORT_ENABLE_ALL` 下 PC 验证正常（设备端优化等级无需降级）。

### 4.2 设备端（小米 24129PN74C，debug 包实测）

- 日志 `Florence2Tagger initialized (4 INT8 sessions, decoder=CACHE)` ✅
- `test_florence2` 广播单张实测：**2.3–3.6s/张**（OD+caption；修复前 no-cache 8–14s/张），
  scene/activity/objects/summary/zh 全部正确 ✅
- 批量扫描稳定推进，零新增失败 ✅

### 4.3 修复过程中踩的坑（记录防复发）

**ORT Java 1.24 API 陷阱**：`OrtSession.Result.get(String)` 返回的是
`Optional<OnnxValue>`（不是 `OnnxTensor`！），`get(Int)` 才返回 `OnnxValue`。
Kotlin 里 `result["name"] as OnnxTensor` 编译期不报错（平台类型），
运行期炸 `ArrayStoreException: java.util.Optional cannot be stored in an array of type OnnxTensor[]`。
正确姿势：位置索引 `result[i] as OnnxTensor`（参照 OpusMtTranslator），
或 `result.get("name").get() as OnnxTensor`。
项目内 ORT 版本 1.24.3，PC 测试环境 1.23.2（Python API 无此坑）。

## 5. 生产接入（已落地）

- **模型分发**：fixed INT8 decoder 已作为 `decoder_model_merged_quantized.onnx` **同名覆盖上传**
  到 ModelScope `budaoshou/Florence-2-base-ONNX`（远端 size 已确认 = 98,178,346 B）。
  catalog（`llm_models.json` / `ModelPathConfig.FLORENCE2_MODEL_FILES`）零改动。
- `Florence2Tagger.kt`：`DecoderMode { CACHE, NO_CACHE }` 枚举，按 decoder 文件大小自动选择
  （`DECODER_FIXED_SIZE = 98178346L` → CACHE；否则按旧版处理 → no-cache 兜底）。
  KV cache 循环：prefill=False 分支 + dummy past；decode=True 分支每步只请求
  logits + 12 份 decoder present，encoder past 复用 prefill 张量；
  张量生命周期参照 OpusMtTranslator。
- **自动升级路径**：下载管理器按远端 size 校验本地文件，旧副本大小不符会自动重下为 fixed 版。

## 6. 关键参数（沿用，攻 cache 分支时直接用）

- Task token IDs（processor 展开，已硬编码在 `Florence2Tagger`）：
  - `<OD>` = `[0,574,22486,5,8720,19,4120,766,11,5,2274,4,2]`
  - `<MORE_DETAILED_CAPTION>` = `[0,47066,21700,19,10,17818,99,16,2343,11,5,2274,4,2]`
- 结构：6 层 ×（decoder.key/value + encoder.key/value）= 24 KV；`[batch, 12 heads, seq, 64]`。
  present 输出顺序：每层 decoder.key, decoder.value, encoder.key, encoder.value（位置索引 b=1+L*4）。
- `decoder_start_token_id=2`，`eos=2`，`forced_bos_token_id=0`（首位必出 BOS→`<s>`，decode 后 `removePrefix("<s>")` 去掉）。
- 预处理：resize 768（非 center crop）+ ImageNet normalize。

## 7. 相关脚本

| 脚本 | 用途 |
|---|---|
| `scripts/florence2_fix_merged_decoder.py` | **图手术修复**（Constant → Identity 直通） |
| `scripts/florence2_fp32_merged_cache_test.py` | 决定性反证：fp32 也崩 → 非量化问题 |
| `scripts/florence2_cache_verify.py` | 修复后三链路正确性 + 加速比验证 |
| `scripts/florence2_config_test.py` | （历史）5 种 INT8/q4 组合穷举，全崩同一行 |
| `scripts/florence2_q4f16_repro.py` | （历史）no-cache 正确管道对照 |

## 8. 相关

- 特性 spec：`docs/superpowers/specs/2026-07-25-english-tagging-dual-field-localization-design.md`
- 代码：`app/src/main/java/com/mamba/picme/domain/tag/florence2/Florence2Tagger.kt`（`runDecoderWithCache` / `runDecoderNoCache`）
- memory：`florence2-tagger-status`
- 另注：设备扫描日志中另有 `[Pass 3] Failed to load bitmap for mediaId=-1000...`（负数 ID）失败，
  与本 bug 无关（存量无效媒体记录），未在本次处理。
