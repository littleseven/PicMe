# ASR 语言模型（LM）说明文档

> **文档编号**: TECH-SPEC-ASR-LM-001
> **关联模块**: `features/camera/voice/`, `k2fsa/sherpa/mnn/`
> **最后更新**: 2026-06-07

---

## 1. 什么是 LM 文件

**LM = Language Model（语言模型）**

在语音识别（ASR）流水线中，LM 负责根据**语言规律**修正声学模型的输出，提升识别准确率。

### 1.1 ASR 流水线

```
音频输入
    │
    ▼
┌─────────────────┐
│  声学模型 (AM)   │  ← encoder.mnn + decoder.mnn + joiner.mnn
│  Acoustic Model │     将音频波形转为音素概率分布
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  语言模型 (LM)   │  ← with-state-epoch-*.mnn（可选）
│  Language Model │     根据语法/语义规律选择最可能的文本
└────────┬────────┘
         │
         ▼
      文本输出
```

### 1.2 LM 的作用举例

| 场景 | 无 LM（纯声学模型） | 有 LM（声学+语言模型） |
|------|---------------------|------------------------|
| 用户说"我想拍照" | "我象拍照"（同音错误） | "我想拍照"（语义正确） |
| 用户说"切换滤镜" | "切花滤镜"（音近错误） | "切换滤镜"（语义正确） |
| 用户说"去相册" | "去相册"（正确，但无上下文） | "去相册"（结合上下文更确定） |

LM 的核心价值：**将声学相似的候选词，按语言规律排序，选择最合理的文本**。

---

## 2. Sherpa-MNN 中的 LM 文件

### 2.1 文件命名

Sherpa-MNN 的 LM 文件通常命名为：

```
with-state-epoch-{N}-avg-{M}.int8.mnn
```

例如：
```
with-state-epoch-99-avg-1.int8.mnn
```

### 2.2 当前部署状态

PicMe 当前部署的 Sherpa-MNN 模型包**不包含 LM 文件**。模型目录中只有：

```
llm_models/sherpa-mnn-zipformer-zh-en/
├── encoder-epoch-99-avg-1.int8.mnn   ← 声学模型：编码器
├── decoder-epoch-99-avg-1.int8.mnn   ← 声学模型：解码器
├── joiner-epoch-99-avg-1.int8.mnn    ← 声学模型：联合器
└── tokens.txt                        ← 词表映射
```

**没有**：`with-state-epoch-99-avg-1.int8.mnn`

### 2.3 配置加载逻辑

`AsrConfigManager.getLmConfigFromDirectory()` 的加载顺序：

1. 读取 `config.json` 中的 `lm` 字段（如果存在）
2. 如果不存在，调用 `getDefaultLmConfig()`
3. `getDefaultLmConfig()` 检查模型目录名是否包含 `zh`/`bilingual`/`chinese`
4. 如果是中文模型，尝试查找 `with-state-epoch-99-avg-1.int8.mnn`
5. **文件不存在 → 静默使用空 LM 配置**

```kotlin
// 当前行为：空 LM 配置
OnlineLMConfig()  // model="", scale=0.0f
```

---

## 3. 有 LM vs 无 LM 的差异

| 维度 | 无 LM | 有 LM |
|------|-------|-------|
| **识别准确率** | 中等，同音字易错 | 较高，语义修正能力强 |
| **内存占用** | 仅 AM（~100-200MB） | AM + LM（~200-400MB） |
| **推理延迟** | 较快 | 略慢（需 LM 解码） |
| **模型包大小** | 较小 | 较大（多一个 LM 文件） |
| **离线能力** | 完全离线 | 完全离线 |

---

## 4. 是否需要 LM

### 4.1 当前决策：不部署 LM

原因：
1. **模型包体积**：LM 文件约 100-200MB，增加下载负担
2. **当前准确率**：纯 AM 对相机场景短指令（"拍照"、"切换滤镜"）已足够
3. **内存预算**：相机页本身占用大，ASR 应尽量轻量

### 4.2 未来可优化

如果用户反馈语音识别错误率高，可考虑：
1. 下载带 LM 的完整模型包
2. 在 `config.json` 中配置 `lm` 字段
3. `AsrConfigManager` 会自动加载

```json
{
  "modelType": "zipformer",
  "transducer": {
    "encoder": "encoder-epoch-99-avg-1.int8.mnn",
    "decoder": "decoder-epoch-99-avg-1.int8.mnn",
    "joiner": "joiner-epoch-99-avg-1.int8.mnn"
  },
  "tokens": "tokens.txt",
  "lm": {
    "model": "with-state-epoch-99-avg-1.int8.mnn",
    "scale": 0.5
  }
}
```

---

## 5. 相关文件

| 文件 | 说明 |
|------|------|
| `app/src/main/java/com/k2fsa/sherpa/mnn/AsrModelConfig.kt` | LM 配置加载逻辑 |
| `app/src/main/java/com/picme/features/camera/voice/SherpaMnnAsrEngine.kt` | ASR 引擎实现 |

---

## 6. 参考

- Sherpa-ONNX 文档：https://k2-fsa.github.io/sherpa/onnx/
- MNN 模型格式：https://www.yuque.com/mnn/cn/model_convert
