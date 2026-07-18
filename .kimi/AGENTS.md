# PoLang — kimi-cli 项目配置

> **定位**：本文件为 kimi-cli 专用项目级配置，与项目根目录 `AGENTS.md` 共同构成规范体系。  
> **优先级**：根目录 `AGENTS.md` > 本文件。模块级 `AGENTS.md` 在其管辖范围内优先。

## 项目速览

- **名称**: PoLang
- **类型**: Android 相机应用（Kotlin + Jetpack Compose）
- **包名**: com.mamba.picme
- **架构**: Clean Architecture + MVVM
- **关键约束**: 100% 本地 AI 处理、交互反馈 < 100ms、三语言 I18N（EN/CN/TW）

## 启动上下文（Startup Context）

每次新会话启动时，**必须先读取** `../docs/05-DEVELOPMENT/LOCAL_ENVIRONMENT.md`，了解本机环境上下文：

- MNN 源码目录：`~/code/MNN`
- Hugging Face / ModelScope CLI 与缓存路径
- `~/code` 下已下载的端侧模型清单
- 项目内嵌 MNN 头文件目录（当前无预编译库）

## kimi-cli 工作规范

### 🚀 Token 优化与交互效率
- **引用替代全文**：严禁在对话中粘贴超过 50 行的代码片段。必须使用 `[file](file:///path)` 引用，仅展示关键行。
- **记忆优先检索**：处理渲染、坐标或架构问题时，必须先调用 `search_memory` 检索 `expert_experience`，避免重复阅读技术文档。
- **增量式修改**：使用 `StrReplaceFile` 时，`original_text` 必须足够唯一且简短，严禁替换整个文件。

### 文件操作偏好
- **并行读取**：对无依赖关系的多个文件，必须一次性并行调用 `ReadFile`。
- **精准定位**：未知路径时，优先使用 `Grep` 配合正则表达式定位，减少 `Glob` 的无效遍历。

### 构建与验证
- 代码修改后必须执行 `./gradlew assembleDebug` 验证编译
- 构建失败时基于日志自主修复，单任务最多自愈 2 次
- 使用 `adb logcat -s "PoLang:*"` 查看运行时日志

### 多语言同步（I18N）
- 新增或修改用户可见字符串时，必须同步更新以下三个文件：
  - `app/src/main/res/values/strings.xml`（英文/默认）
  - `app/src/main/res/values-zh-rCN/strings.xml`（简体中文）
  - `app/src/main/res/values-zh-rTW/strings.xml`（繁体中文）

### 日志规范
- 统一标签格式：`PoLang:[ModuleName]`
- 示例：`private const val TAG = "PoLang:Camera"`

## 项目文档索引

| 文档 | 路径 | 内容 |
|------|------|------|
| 顶层治理 | `../AGENTS.md` | 角色协作、全局红线、文档治理 |
| 产品需求 | `../PRODUCT.md` | 目标与约束 |
| 交互规范 | `../docs/01-PRODUCT/FEATURES.md` | 交互与体验规则 |
| 技术规范 | `../AGENTS.md` | 代码风格与审查清单 |
| 模块规范 | `../app/src/main/java/com/mamba/picme/*/AGENTS.md` | 各模块实现细则 |
| 本地环境 | `../docs/05-DEVELOPMENT/LOCAL_ENVIRONMENT.md` | 本机 MNN、HF/MS、模型目录等环境上下文 |

## 快捷命令

```bash
./gradlew :app:assembleDebug    # 构建调试 APK
./gradlew test                   # 运行单元测试
adb logcat -s "PoLang:*"          # 查看 PoLang 日志
```

> 完整开发指南（环境配置、IDE 快捷键、性能分析、发布流程）：`DEVELOPMENT.md`

## 关联 AI 工具

- **Lingma (IDE 内辅助)**: `.lingma/skills/`
- **OpenClaw (工作区上下文)**: `.openclaw/workspace/`
- **Skills 同步**: `.openclaw/skills/` → 符号链接 → `.lingma/skills/`
