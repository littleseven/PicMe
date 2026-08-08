---
name: mnn-llm-android
description: |
  MNN-LLM Android 端侧大模型推理专家。涵盖模型下载、加载、JNI 桥接、推理调用、ChatMessages 兼容性、Qwen 模型特殊处理等。Use when working with MNN-LLM local inference, Qwen models, empty response debugging, or model loading failures on Android.
version: 1.0.0
created: 2026-05-26
updated: 2026-08-03
maintainer: [RD] 全栈工程师
tags:
  - mnn
  - llm
  - android
  - jni
  - qwen
  - local-inference
  - model-loading
---


# MNN-LLM Android 推理专家

> **定位**：预防 AI 在 MNN-LLM 端侧推理中重复犯已验证过的错误。
> **触发时机**：本地 LLM 推理返回空、模型加载失败、JNI 桥接问题、Qwen 模型适配时。

---

## 触发条件

- 用户提到 "MNN-LLM"、"本地 LLM"、"端侧推理"、"Qwen"、"模型加载"
- 出现 `Empty LLM response` 错误
- 出现 `Model not found` 或 `createLLM failed`
- 需要实现或调试 JNI 桥接代码
- 需要下载或管理 MNN 格式模型文件

---

## 核心原则

1. **两阶段调用是铁律**：`response(..., 0)` prefill + 循环 `generate(1)` decode
2. **不要手动调用 `generate_init()`**：`response()` 内部自动调用
3. **不要传 `nullptr` 给 `end_with`**：官方默认 `"\n"`，但两阶段模式下传 `nullptr` 给 `response(..., 0)` 是 OK 的
4. **小模型 `maxNewTokens` 必须限制**：0.6B 模型保持 128-256，过大导致空响应
5. **ChatMessages API 兼容性差**：优先使用纯文本 `response(prompt, ...)`

---

## 官方调用模式（来自 MNN 源码）

### 模式一：两阶段流式生成（官方推荐）

```cpp
// llm_session.cpp 官方实现
std::ostringstream oss;
llm->response(history_, &output_ostream, "<eop>", 0);  // prefill only

while (!stop_requested && !generate_text_end && current_size < max_new_tokens) {
    llm->generate(1);  // decode one token
    current_size++;
    // check stop condition
}
```

### 模式二：单阶段直接生成（简化版）

```cpp
// llm.cpp 源码：response() 内部自动调用 generate_init()
void Llm::response(const std::string& user_content, std::ostream* os, 
                   const char* end_with, int max_new_tokens) {
    if (!end_with) { end_with = "\n"; }  // 默认换行符！
    auto prompt = user_content;
    if (mConfig->use_template()) {
        prompt = apply_chat_template(user_content);  // 自动加 chat template
    }
    std::vector<int> input_ids = tokenizer_encode(prompt);
    response(input_ids, os, end_with, max_new_tokens);
}
```

**关键发现**：
- `response()` 内部自动调用 `generate_init(os, end_with)`
- `end_with` 默认 `"\n"`，传 `nullptr` 会被替换为 `"\n"`
- `use_template=true` 时自动应用 chat template，外部不要手动加 `<|im_start|>`

---

## 模型加载流程

### 1. 模型文件结构

```
filesDir/llm_models/qwen3-0-6b/
├── config.json          # 模型配置（必须）
├── llm.mnn             # 模型结构（必须）
├── llm.mnn.weight      # 模型权重（必须）
├── tokenizer.json      # 分词器（必须）
└── tokenizer_config.json
```

### 2. Java 层加载检查清单

```kotlin
// MnnLlmClient.load() 中的验证逻辑
val configFile = File(configPath)
val modelFile = File(modelDir, "llm.mnn")
val weightFile = File(modelDir, "llm.mnn.weight")

// 1. 检查 config.json 存在且非空
if (!configFile.exists() || configFile.length() == 0L) return false

// 2. 检查模型文件存在
if (!modelFile.exists() && !weightFile.exists()) return false

// 3. 检查不是 Git LFS 指针文件
if (modelFile.length() < 1000) {
    val firstLine = modelFile.bufferedReader().readLine()
    if (firstLine.contains("git-lfs")) return false
}
```

### 3. Native 层加载

```cpp
MNN::Transformer::Llm *llm = MNN::Transformer::Llm::createLLM(configPath);
if (llm == nullptr) return 0;  // 创建失败

bool loaded = llm->load();
if (!loaded) {
    MNN::Transformer::Llm::destroy(llm);
    return 0;  // 加载失败
}
```

---

## 常见陷阱

| 陷阱 | 症状 | 修复 |
|------|------|------|
| **手动调用 `generate_init()`** | 状态混乱，输出异常或空 | 删除手动调用，`response()` 内部已自动调用 |
| **`end_with = nullptr`** | 可能触发异常或空响应 | 传 `"\n"` 或 `"<eop>"`，不要传 `nullptr` |
| **ChatMessages API 返回空** | `Empty LLM response` | 改用纯文本 `response(prompt, ...)` |
| **maxNewTokens 过大** | 小模型返回空 | 0.6B 模型用 128，2B 模型用 256 |
| **手动加 chat template** | 双重标记，输出异常 | 让 `use_template=true` 自动处理，外部用纯文本 |
| **模型目录名不匹配** | `Model not found` | 注册表 `cacheDirName` 必须与下载目录名一致 |
| **Git LFS 指针文件** | 模型加载失败 | 检查文件头是否包含 `git-lfs`，删除并重新下载 |
| **未加线程锁** | 并发调用崩溃 | JNI 桥接中 `response()`/`generate()` 必须加 `std::mutex` |

---

## 诊断流程

### Step 1: 确认模型文件完整性

```bash
adb shell run-as com.mamba.picme ls -la files/llm_models/qwen3-0-6b/
# 检查文件大小，config.json 应 > 1KB，llm.mnn 应 > 100MB
```

### Step 2: 检查 config.json 关键配置

```bash
adb shell run-as com.mamba.picme cat files/llm_models/qwen3-0-6b/config.json
```

关键字段：
- `max_new_tokens`: 默认生成上限
- `use_template`: 是否自动应用 chat template
- `jinja.context.enable_thinking`: Qwen3 思考模式开关
- `backend_type`: cpu / opencl / vulkan

### Step 3: 检查 Native 层日志

```bash
adb logcat -s PoLang:LlmJNI:D *:S
```

关注：
- `After prefill, oss size=?, status=?`
- `After generate(N), gen_seq_len=?, stopped=?`

### Step 4: 验证推理调用链

```
Java: LocalLlmEngine.generate()
  → MnnLlmClient.generate()
    → JNI: nativeGenerate()
      → C++: llm->response(prompt, &oss, end_with, max_new_tokens)
        → llm.cpp: generate_init() + generate()
```

---

## Qwen 模型特殊处理

### Thinking 模式（Qwen3）

```json
// config.json 中
"jinja": {
    "context": {
        "enable_thinking": true
    }
}
```

- 模型会输出 `<think>...</think>` 包裹的思考过程
- 后处理需过滤 think 标签：

```cpp
// llm_session.cpp 官方做法
std::string deleteThinkPart(std::string content) {
    size_t start = content.find("<think>");
    if (start == std::string::npos) return content;
    size_t end = content.find("</think>", start);
    if (end == std::string::npos) return content;
    content.erase(start, end - start + std::string("</think>").length());
    return content;
}
```

### Prompt 格式

```kotlin
// 纯文本格式（推荐）
val prompt = buildString {
    appendLine("system:")
    appendLine(systemPrompt)
    appendLine()
    appendLine("user:")
    appendLine(userInput)
    appendLine()
    append("assistant:")
}
```

**不要**手动添加 `<|im_start|>`、`<|im_end|>` 等标记，`use_template=true` 时 MNN-LLM 内部会自动应用。

---

## 审查清单

- [ ] 模型文件完整且不是 Git LFS 指针
- [ ] `config.json` 存在且有效
- [ ] 模型目录名与注册表 `cacheDirName` 一致
- [ ] JNI 桥接中 `response()`/`generate()` 有 `std::mutex` 保护
- [ ] 未手动调用 `generate_init()`
- [ ] `end_with` 参数不为 `nullptr`
- [ ] `maxNewTokens` 适合模型大小（0.6B→128, 2B→256）
- [ ] 优先使用纯文本 `response(prompt, ...)` 而非 `ChatMessages`
- [ ] 外部 prompt 未手动添加 chat template 标记
- [ ] Qwen3 模型输出已过滤 `<think>` 标签

---

## 参考文档

- [MNN GitHub](https://github.com/alibaba/MNN)
- [MNN-LLM 官方 Android Demo](https://github.com/alibaba/MNN/tree/master/apps/Android/MnnLlmChat)
- `engines/beauty-engine/src/main/cpp/llm_jni_bridge.cpp` — 项目 JNI 桥接实现
- `engines/beauty-engine/libs/mnn/include/MNN/llm/llm.hpp` — MNN-LLM 头文件

---

## 相关文件

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.0.0 | 2026-05-26 | 初始版本，整合 MNN-LLM 推理经验 |

---

## Skill 编写规范（维护者必读）

### 长度控制
- **SKILL.md 正文 < 500 行**。超过则拆分代码示例到 `reference.md`。
- 使用渐进式披露：核心流程在 SKILL.md，详细代码在 reference.md。

### 代码示例
- 单个代码块不超过 30 行。
- 超过 30 行的代码应移至 `reference.md`，SKILL.md 中只保留说明和链接。

### 引用规范
- 引用其他 Skill 使用相对路径：`/xxx`
- 引用项目文档使用相对路径：`docs/XXX.md`

### 版本管理
- 每次更新必须修改 `updated` 字段和「版本历史」表格。
- 重大结构调整应升级 minor 版本（1.0 → 1.1）。
- 内容重写或架构变更应升级 major 版本（1.x → 2.0）。
