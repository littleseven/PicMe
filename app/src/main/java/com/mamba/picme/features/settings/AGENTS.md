# Settings 模块技术实现规范 (Settings Technical Implementation)

> **边界声明（Boundary Statement）**
> - 本文档仅承载本模块的实现细节（架构、代码约束、检查清单）。
> - 产品目标与验收口径以 `PRODUCT.md` 为准；交互流程与体验规则以 `docs/01-PRODUCT/FEATURES.md` 为准。
> - 顶层治理规则（角色协作、全局红线、文档流程）以根目录 `AGENTS.md` 为准。
> - 禁止将模块级实现细节回填到顶层 `AGENTS.md`；跨模块或专项技术内容应下沉到对应模块文档或 `docs/*_TECH_SPEC.md`。

**模块定位**：确保 PoLang 的设置系统使用 DataStore 安全、高效地存储用户配置。

**主要维护者**：[RD] 全栈工程师

**阅读对象**：RD、AI Agent

## 1. 核心产品逻辑 (Core Product Logic)

- **[LOCAL] 本地优先存储**：设置数据本地化存储，隐私敏感 Agent 数据优先本地处理
- **[I18N] 多语言支持**：英文、简体中文、繁体中文三语齐全
- **[PRIVACY] 权限透明**：明确告知用途，提供降级方案
- **[TYPE_SAFE] 类型安全**：使用 DataStore 和 Sealed Class 确保编译期检查

## 2. 技术实现规范 (Technical Implementation)

### 2.1 DataStore 定义规范
- **Preference Key 命名**：使用 `intPreferencesKey`、`booleanPreferencesKey` 等类型安全 API
- **Flow 暴露数据**：通过 `userPreferences.data.map{}`将偏好设置转换为 Flow 流
- **Repository 封装**：所有读写操作必须经过 Repository 层，ViewModel 不直接调用 DataStore

### 2.2 设置项数据模型
**使用 Sealed Class 区分三种设置类型**：
- **SwitchSetting**：开关型设置（如水印开关），包含标题、描述、默认值
- **SliderSetting**：滑块型设置（如美颜程度），包含最小值、最大值、默认值、步长
- **SelectorSetting**：选择器型设置（如滤镜风格），包含选项列表、默认索引

### 2.3 设置 UI 组件实现

#### 2.3.1 通用设置项布局
**使用 when 表达式根据类型渲染不同组件**：
- SwitchSetting → 调用 SwitchSettingItem，显示标题和开关
- SliderSetting → 调用 SliderSettingItem，显示滑块和数值
- SelectorSetting → 调用 SelectorSettingItem，显示下拉选择器

#### 2.3.2 实时预览支持
**设置变更立即生效机制**：
1. ViewModel 使用 `combine`操作符合并多个 Flow（美颜程度、水印开关、滤镜强度）
2. 通过 `stateIn`将合并后的 Flow 转换为 StateFlow，自动通知 UI 更新
3. 用户修改设置时调用 Repository 的 suspend 函数，无需手动刷新 UI

### 2.4 权限管理实现

#### 2.4.1 Android 13+ 权限适配
**动态申请策略**：
- **Android 13+ (API 33+)**：使用 `READ_MEDIA_IMAGES`替代废弃的`READ_EXTERNAL_STORAGE`
- **Android 12 及以下**：继续使用 `READ_EXTERNAL_STORAGE`和`WRITE_EXTERNAL_STORAGE`
- **相机权限**：所有版本统一使用 `CAMERA` 权限

#### 2.4.2 权限降级策略
**根据权限拒绝情况提供不同降级方案**：
- **相机权限被拒**：显示说明对话框，引导用户前往系统设置开启
- **存储权限被拒**：进入受限模式，允许拍照但不允许保存（或保存到应用私有目录）
- **部分权限被拒**：仅启用已授权功能，未授权功能隐藏或禁用

### 2.5 I18N 多语言支持

#### 2.5.1 字符串资源组织规范
**文件结构**：
- `res/values/strings.xml` - 英文（默认）
- `res/values-zh-rCN/strings.xml` - 简体中文
- `res/values-zh-rTW/strings.xml` - 繁体中文

**命名规则**：采用 `[feature]_[description]`格式，如`settings_title`、`ocr_copy_success`

**更新流程**：新增功能时必须同步更新三个语言文件，确保文案对齐

#### 2.5.2 动态语言切换（可选功能）
**实现方式**：通过 `Context.createConfigurationContext()` 创建带特定 Locale 的 Context，支持应用内独立于系统设置的语言切换

## 3. Agent 执行规约 (Execution Rules)

- **DataStore 操作**：必须使用协程（edit 是 suspend 函数）
- **Flow 生命周期**：正确处理 Flow 的生命周期，使用 `stateIn` 管理
- **权限请求时机**：首次使用时再请求，避免启动时全部请求
- **默认值设置**：所有设置项必须有默认值，避免首次读取为 null
- **多语言同步**：新增功能时必须同步更新三个语言文件
- **深色模式**：必须使用 MaterialTheme.colorScheme 支持深色模式
- **实时生效**：设置变更通过 Flow 自动通知订阅者，无需手动刷新
- **选择器设置项**：人脸检测引擎等枚举配置应以 `SelectorSetting` / 选项 Chip 暴露，默认值必须可回退到 `AUTO`，并通过 Repository 持久化到 DataStore。

## 4. 常见陷阱检查清单 (Checklist)

- [ ] DataStore 操作是否使用了协程？（edit 是 suspend 函数）
- [ ] 是否正确处理了 Flow 的生命周期？（使用 `stateIn`）
- [ ] 权限请求是否在合适的时机？（首次使用时再请求）
- [ ] 设置项是否有默认值？（避免首次读取为 null）
- [ ] 多语言文案是否同步更新？（新增功能时检查三个语言文件）
- [ ] 是否支持深色模式？（使用 MaterialTheme.colorScheme）
- [ ] 设置变更是否实时生效？（通过 Flow 自动通知订阅者）

### 2.6 模型管理（2026-05 新增，2026-06 按服务功能重分类）

**统一模型中心**
- **入口**：`ModelCenterScreen`，从设置页「AI 助手」卡片「模型中心」进入
- **顶部分类（按 PoLang 服务功能划分）**：
  - **必须**：核心运行必须模型，提供一键下载缺失模型队列
  - **聊天**：本地 LLM + ASR/KWS（文字、语音、多模态对话）
  - **相册打标**：MobileCLIP、OPUS-MT 等语义标签/搜索模型
  - **美颜相机**：人脸检测、关键点、Embedding 等美颜/人脸模型
- **功能**：
  - 统一管理所有本地模型
  - 按服务功能分类 Tab 浏览
  - **必须分类一键下载**：`downloadAllRequiredModels()` 将未下载的必须模型依次加入 `LlmModelDownloadManager` 队列
  - 下载新模型（从 ModelScope 远程仓库）
  - 删除本地模型释放空间
  - 查看模型属性（JSON 格式，支持复制）
- **存储路径**：应用私有目录 `files/llm_models/{modelId}/`
- **下载管理**：`LlmModelDownloadManager` 支持断点续传、暂停和进度回调
- **模型配置**：`res/raw/llm_models.json` 定义所有可用模型的元数据；每个模型通过 `tags` 中的服务功能标签（`must-have` / `chat` / `voice` / `photo-tagging` / `beauty-camera`）决定所属分类
- **必须模型清单（`ModelConfig.REQUIRED_MODEL_IDS`）**：
  - `qwen3_5_2b`（本地 LLM，文字/多模态对话）
  - `sherpa-onnx-zipformer-zh-en`（ASR，语音输入）
  - `sherpa-onnx-kws-zipformer-wenetspeech`（KWS，唤醒词）
  - `face-det-retina500m-mnn`（默认人脸检测，Det10G 已降级为可选）
  - `face-landmark-2d106-mnn`（人脸关键点）
  - `face-embedding-glint360k-r100-mnn`（人脸聚类/识别，Glint360K R100）
  - `mobileclip-onnx`（语义搜索/相册打标）
  - `opus-mt-zh-en`（中文查询翻译）

**Agent 模式设置**
- **本地模式**：仅使用本地模型（默认 Qwen3.5-2B）
- **远程模式**：仅使用云端 LLM API（OpenAI 兼容协议）
- **关闭模式**：禁用 Agent
- **隐私级别**：`STRICT` / `PERMISSIVE`；运行时输入分级为 `PUBLIC` / `SENSITIVE` / `RESTRICTED`

### 2.7 相册功能入口（2026-06 新增）

**入口位置**：设置页「相册功能」卡片（`SettingsScreen` 中非 Debug 区域）

**当前功能**：
- **管理重复照片**：点击后导航到独立的 `DuplicateManagerRoute`
  - 使用共享的 `MediaViewModel`（与 `GalleryScreen` 同一实例）
  - 进入时自动调用 `startDuplicateScan()` 扫描重复/相似照片
  - 顶部栏提供「返回」和「删除全部重复」操作
  - 通过系统返回键或顶部返回按钮退出，返回到 Settings 页
- **TAG 生成控制**：点击后导航到 `TagGenerationControlScreen`
  - 支持按类别 / 时间范围重新生成 TAG
  - 提供 3-Pass 打标进度控制

**实现约定**：
- 相册功能卡片对所有构建类型可见（非 Debug 限定）
- Debug 构建额外显示「相册调试功能」区域（图片下载页、搜索测试、OpenCL 后端切换）
- 图标统一使用 `SettingsClickableRow` 的 `leadingIcon` + 右侧箭头，保持可点击心智

### 2.8 设置页二级分类（2026-06 拆分）

**目标**：解决设置页内容过多、单屏无法看完的问题，主菜单一屏可见，功能分组进入二级页。

**分类枚举**：`SettingsCategory`
- `MAIN` — 设置主菜单，6 个分类卡片 + 重复照片快速入口
- `PERSONALIZATION` — 主题、语言
- `AI_AGENT` — 模型中心、Agent 模式、飞书通信通道、语音交互
- `GALLERY` — 重复照片管理、TAG 生成控制、Debug 相册调试工具
- `CAMERA_BEAUTY` — ROI / Landmark 阶段配置、高级人脸检测选项
- `SYSTEM` — 悬浮窗 AI 聊天气泡、电池优化与 MIUI 权限
- `DEVELOPER` — Debug 总开关、相机/人脸/日志浮层、Shader 调试、日志模块

**导航实现**
- 主菜单路由：`Screen.Settings.route = "settings"`
- 二级页路由：`Screen.SettingsCategory.route = "settings/{category}"`
- `MainActivity.kt` 解析 `category` 参数并映射到 `SettingsCategory`
- 主菜单点击卡片后 `onNavigateToCategory` 调用 `navController.navigate(Screen.SettingsCategory.createRoute(category.name.lowercase()))`
- 所有二级页共用同一个 `SettingsScreen` Composable，通过 `category` 条件渲染对应区块

**UI 约定**
- 主菜单使用 2 列卡片网格（3 行，一屏可显示完）
- 每个分类卡片包含：图标、标题、两行描述
- 重复照片管理在主菜单底部以单行可点击入口形式保留，方便从相册迁移后的老用户习惯

### 2.9 Agent 集成（2026-05 新增）

**SettingsAgentIntegration**
- 通过 `SettingsCapability` 绑定到 `CapabilityRegistry`
- 支持命令：
  - `navigate_to` — 跳转设置子页面
  - `toggle_setting` — 切换开关设置
  - `download_model` — 下载 LLM 模型
- 使用 `AiChatScreen` 提供统一聊天界面

## 6. 与产品文档对照 (Product Alignment)

**必须满足的产品指标**：
- ✅ 零云端存储 → 设置数据本地存储，不依赖云端同步
- ✅ 多语言支持 → 英文、简体中文、繁体中文三语齐全
- ✅ 权限透明 → 明确告知用途，提供降级方案
- ✅ 模型管理 → LLM/ASR 模型下载、切换、删除
- ✅ Agent 集成 → SettingsCapability 支持语音/文字命令

**技术决策记录**：
- 选择 DataStore 而非 SharedPreferences：类型安全、支持 Flow、无主线程阻塞
- 使用 Sealed Class 表示设置项：编译器检查 exhaustive when，避免遗漏
- Combine 多个 Flow：减少订阅次数，提升性能
- 模型存储在应用私有目录：避免 Scoped Storage 限制，支持大文件
