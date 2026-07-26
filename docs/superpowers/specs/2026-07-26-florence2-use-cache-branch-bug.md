# Florence-2 ONNX decoder `use_cache_branch=true` 缓存分支 Bug 记录

- 日期：2026-07-26
- 状态：**已知问题（已绕过）**。当前生产路径用 no-cache 全量重算，可用但偏慢（O(n²)，~8–14s/张）。
- 目的：给后续攻坚 KV-cache 加速的人留好现场、复现路径、候选方向。

## 1. 现象

Florence-2 merged decoder（`decoder_model_merged_quantized.onnx` / `decoder_model_merged_q4.onnx`）
在 ORT 上以 `use_cache_branch=True`（带 KV cache）跑第 2+ 步时，**必崩**：

```
Non-zero status code returned while running MatMul node.
Name: /language_model/model/decoder/layers.0/encoder_attn/MatMul
Status Message: matmul_helper.h:144 Compute right operand cannot broadcast on dim 0
```

- 报错点：第 0 层 cross-attention（encoder_attn）的 MatMul，"右操作数 dim 0 无法广播"。
- **与量化无关**：INT8（`_quantized`）和 q4f16（`_q4`）merged decoder **都崩**，错误完全一致。
- 触发条件：仅 `use_cache_branch=True` 分支。`use_cache_branch=False`（no-cache 全量重算）正常。

## 2. 当前绕过方案（已在设备验证可用）

`Florence2Tagger.runDecoderLoop`：每步 `use_cache_branch=false`，把
`[DEC_START=2, *已生成]` 整个序列重新 embed 喂入，取 logits 最后一位 argmax。
encoder_hidden_states / attention_mask / 24 个**全零 dummy past_key_values** 每步复用同一份。

- 正确性：输出与 PyTorch `model.generate()` 一致（PC + 设备实测）。
- 代价：O(n²)——每步重算整个 decoder 序列。OD ~20 token、caption ~80 token，设备 ~8–14s/张。

## 3. Bug 定位（现场）

merged decoder 把 prefill（`use_cache_branch=false`）和 decode（`true`）合到一个 `If` 子图里。
**坏掉的是 `If(True)`（decode）子图**，证据来自输出签名的 dim 命名：

| 输出 | dim 0 | 含义 |
|---|---|---|
| `present.{L}.decoder.key` | `batch_size` | 正常 |
| `present.{L}.encoder.key` | `If_0_o3__d0`（**非 batch_size**） | 异常——If(True) 子图算出的 encoder KV 带一个被 q4/INT8 块量化扭曲的 dim 0 |

decoder 自注意力 KV（self-attn）dim 0 正常，**只有 cross-attn 的 encoder KV dim 0 异常**，
回喂下一步时与 decoder 侧 batch_size=1 对不上 → MatMul 广播失败。

独立 prefill decoder（`decoder_model_q4f16.onnx` / `decoder_model_quantized.onnx`）本身能跑、
输出干净的 `[batch, 12, seq, 64]` encoder KV，但把它喂给 merged decode 的 `True` 分支**一样崩**
（bug 在 merged 的 If(True) 子图内部，不在 KV 传递环节）。

## 4. 复现

- 穷举证明：`scripts/florence2_config_test.py`（5 种 INT8/q4 组合，全崩在同一行）。
- 正确管道对照：`scripts/florence2_q4f16_repro.py`（processor ground-truth 输入 + no-cache 管道 + 可选 PyTorch ref）。
- 模型目录：`/Users/guoshuai/code/florence-2-onnx`（INT8 + q4f16 全套）。
- 环境：Mac ORT 1.23.2（CPU）。设备端 ORT 同样崩（init 正常，decode 第 2 步崩）。

## 5. 候选攻坚方向（按性价比排序）

1. **重新导出 decoder 修 optimum 的量化 If 子图**（最可能根治）。
   optimum-exporter 对 `decoder_model_merged` 的 `MatMul` 量化在 If 子图里有已知问题；
   用更新版 optimum-intel / `optimum[exporters]` 重导，或导出时禁用某个量化 pattern。
2. **改用 `decoder_with_past_model.onnx`**（独立 decode 模型，无 If 分支）。
   - 现有 `decoder_with_past_model_quantized.onnx` 能跑，但 **seq_len 固定 16**（每步要 pad 到 16、present KV 每步 +16，浪费且要处理 padding mask）。
   - 若能重导成动态 seq_len 的 `decoder_with_past_model`，即可干净走 KV cache（prefill 用 `decoder_model`，decode 用它）。
3. **接受 no-cache**。231M 小模型 + 几十个 token，8–14s 对后台批量打标可接受；交互式入口才需优化。
4. （已排除）裸 `decoder_model_merged` 的 `False` 分支复用——它每步也重算，等价 no-cache，无收益。

## 6. 关键参数（攻 cache 分支时直接用，不用再爬）

- Task token IDs（processor 展开，已硬编码在 `Florence2Tagger`）：
  - `<OD>` = `[0,574,22486,5,8720,19,4120,766,11,5,2274,4,2]`
  - `<MORE_DETAILED_CAPTION>` = `[0,47066,21700,19,10,17818,99,16,2343,11,5,2274,4,2]`
- 结构：6 层 ×（decoder.key/value + encoder.key/value）= 24 KV；`[batch, 12 heads, seq, 64]`。
- `decoder_start_token_id=2`，`eos=2`，`forced_bos_token_id=0`（首位必出 BOS→`<s>`，decode 后 `removePrefix("<s>")` 去掉）。
- 预处理：resize 768（非 center crop）+ ImageNet normalize。

## 7. 相关

- 特性 spec：`docs/superpowers/specs/2026-07-25-english-tagging-dual-field-localization-design.md`
- 代码：`app/src/main/java/com/mamba/picme/domain/tag/florence2/Florence2Tagger.kt`（`runDecoderLoop`）
- memory：`florence2-tagger-status`
