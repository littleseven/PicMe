# 本地开发环境上下文（Local Environment Context）

> **版本**: 1.0  
> **状态**: 生效中  
> **最后更新**: 2026-07-06  
> **维护者**: 项目开发者  
> **上级文档**: 根目录 `AGENTS.md`

---

## 1. 文档目的

本文档记录本机（`guoshuai` 的 macOS 开发机）上**与 polang / PoLang 相关的本地工具、源码与模型路径**。每次启动新会话时，AI Agent 应优先读取本文档，避免重复询问或猜测以下路径：

- MNN 源码目录
- ModelScope / Hugging Face 工具与缓存目录
- 已下载的端侧模型目录
- 项目内嵌的 MNN 预编译库目录

> **注意**：本文档描述的是**当前本机实际环境**，不是项目代码仓库中的文件。路径变更后需同步更新本文档。

---

## 2. 关键本地路径速查

### 2.1 MNN 相关

| 路径 | 类型 | 说明 |
|------|------|------|
| `~/code/MNN` | 源码目录 | 本地 MNN 完整源码，可用于编译 Android `.so` 或排查 MNN 内部实现 |
| `<project>/engines/beauty-engine/libs/mnn` | 项目内嵌头文件 | 项目构建时引用的 MNN headers，当前仅含 `include/` |
| `<project>/engines/agent-native/libs/mnn` | 项目内嵌头文件 | `:engines:agent-native` 模块引用的 MNN headers（Phase 4 Task 12 自 runtime-core 迁入），当前仅含 `include/` |
| `<project>/engines/beauty-engine/src/main/cpp/` | 项目源码 | MNN JNI Bridge、MNN Face Detector / Embedder 实现 |

> 项目内 `engines/beauty-engine/libs/mnn` 与 `engines/agent-native/libs/mnn` 目前**只包含头文件**，没有预编译库。如需更新 MNN 库文件，可从 `~/code/MNN` 编译后拷贝，或从 MNN Release 包解压后放入对应目录。

### 2.2 模型下载与管理工具

| 工具 | 可执行文件路径 | 缓存目录 | 说明 |
|------|---------------|----------|------|
| `modelscope` | `~/miniconda3/bin/modelscope` | `~/.cache/modelscope` | ModelScope 官方 CLI，已安装 |
| `huggingface-cli` | `~/miniconda3/bin/huggingface-cli` | `~/.cache/huggingface` | Hugging Face 官方 CLI，已安装 |
| `hf` | `~/miniconda3/bin/hf` | `~/.cache/huggingface` | Hugging Face CLI 别名，已安装 |

### 2.3 已下载模型目录（`~/code`）

以下模型/推理资源已存在于 `~/code` 下，可直接引用，无需重复下载：

| 目录/文件 | 用途 | 备注 |
|----------|------|------|
| `~/code/antelopev2` | 人脸检测 / 关键点 / Embedding | InsightFace 经典模型 |
| `~/code/arcface_r100` | 人脸识别 Embedding | ArcFace R100 |
| `~/code/budaoshou_ArcFace-R100-MNN` | ArcFace R100 MNN 转换版 | 已转换为 MNN 格式 |
| `~/code/mobileclip_s0_onnx` | MobileCLIP S0 ONNX | 语义召回/CLIP 编码 |
| `~/code/mobileclip_s2_onnx` | MobileCLIP S2 ONNX | 语义召回/CLIP 编码 |
| `~/code/MobileCLIP2-S2` | MobileCLIP2 S2 | CLIP 模型资源 |
| `~/code/opus-mt-zh-en` | 翻译模型（中→英） | OPUS-MT |
| `~/code/opus-mt-zh-en-int8` | 翻译模型 INT8 量化版 | OPUS-MT 量化 |
| `~/code/sherpa-onnx-1.13.3-static-link-onnxruntime.aar` | 语音唤醒/ASR | Sherpa ONNX 静态库 AAR（项目内嵌为 `shared/libs/sherpa-onnx-1.13.3.aar`（Phase 4 Task 11 起），以项目内实际文件为准） |
| `~/code/zvec` | 向量/Embedding 相关 | 本地向量工具或模型 |

> 以上列表基于 `~/code` 当前内容整理。新增模型后应补充到本表。

---

## 3. 常用命令速查

```bash
# 进入本地 MNN 源码
cd ~/code/MNN

# 查看 ModelScope 下载的模型
ls ~/.cache/modelscope/hub

# 查看 Hugging Face 下载的模型
ls ~/.cache/huggingface/hub

# 使用 huggingface-cli 下载模型（示例）
huggingface-cli download <repo_id> --local-dir ~/code/<target_dir>

# 使用 modelscope 下载模型（示例）
modelscope download --model <model_id> --local_dir ~/code/<target_dir>
```

---

## 4. 对 AI Agent 的启动提示

每次新会话开始时，Agent 应默认已知：

1. **MNN 源码不在项目仓库内**，而是在 `~/code/MNN`。
2. **项目内的 MNN 库不完整**：`engines/beauty-engine/libs/mnn` 和 `engines/agent-native/libs/mnn` 仅含头文件，编译 MNN 相关 Native 代码时需确认库文件是否存在。
3. **模型优先从 `~/code` 查找**，避免不必要重复下载。
4. **ModelScope / Hugging Face 已安装在 miniconda3 环境中**，CLI 可直接调用。

---

## 5. 更新记录

| 版本 | 日期 | 变更 | 作者 |
|------|------|------|------|
| 1.0 | 2026-07-06 | 初版，记录 MNN 源码、HF/MS 工具、已下载模型路径 | CO |
