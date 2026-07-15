# Changelog

## [1.0.11] - 2026-07-15

### ✨ Features
- feat: APK 分发迁移至腾讯云 COS
- feat(chat): 相册搜索 + 横滑卡片 carousel + 「查看全部」跳转相册
- feat(agent): AgentAction.MediaResults + RefineMediaSearch 支持搜索结果细化
- chore(app): 品牌统一改名为 破浪相册 / PoLang

### 🐛 Bug Fixes
- fix(chat): 修复「介绍一下你自己」误触发「暂不支持此操作」
- fix(chat): 搜索结果预览全功能化 + 修复顶/底栏叠加
- fix(gallery): 搜索结果预览隐藏 SearchTopBar（修复双顶栏）
- fix(server/admin): APK 上传增加 200MB 限制 + 进度条

## [1.0.10] - 2026-07-09

### ✨ Features
- feat(editor): 编辑页滤镜面板实现，支持风格特效实时预览
- feat(tag): MobileCLIP 中英双语分类 + Qwen token 限制优化
- feat(tag): MobileClipTagClassifier 零样本场景/物体/标签分类
- feat(tag): 结合 MobileCLIP 分类与 Qwen 活动+摘要生成
- feat(tag): 添加活动+摘要专用提示词变体
- feat(tag): ControlledVocab 按字段暴露候选列表

### 🐛 Bug Fixes
- fix(editor): 修复编辑页瘦脸/大眼不生效与方向反问题，并优化预览图尺寸
- fix(beauty): 恢复照片编辑路径多 Pass 美颜管线

### ⚡ Performance
- perf(agent): 相机页默认卸载 LLM，异步加载 UI 优化启动速度

### 🔧 Build & Refactor
- build: 提取 :mnn-core 模块，移除 beauty-engine -> runtime-core 依赖
- build(agent-core): 传递依赖转换为 implementation 隔离
- build: 统一各 native 模块 ndkVersion 为 28.2.13676358
- build: 对齐 agent-core 与 runtime-core 依赖版本
- build(beauty-engine): 移除未使用的 libncnn.so 减小包体积
- build(app): 限定 onnxruntime pickFirst 到 arm64-v8a 并文档化双来源约束
- build: 修复 KSP 增量缓存损坏问题

### 📚 Documentation
- docs(editor): 更新 AGENTS.md，标记滤镜功能已实现
- docs: 第二轮精简 — 合并技术规范同类项并修复死链
- docs: 精简文档体系 — 合并同类项并删除过时/临时文档
- docs: 将 mermaid 图表转换为 ASCII 线框图
- docs: 添加模块架构图并修正 AgentOrchestrator 位置
- docs: 添加架构审查修复实施计划
- docs(sentencepiece): 添加模块 AGENTS.md
- docs: 修复 AGENTS.md 模块职责不匹配问题
- docs: 结构性治理 — agent-core AGENTS.md、规范标题、路径修复

### 🧪 Tests
- test(tag): 添加 MobileClipTagClassifier top-k 单元测试

---

### ✨ Features
- feat(accessibility): move AccessibilityService to debug-only builds

## [1.0.6] - 2026-07-05

### ✨ Features
- feat(tag): person naming persistence, full-rescan name preservation and TAG control UI redesign
- feat: 激进移除 NCNN 推理后端并整理模型列表
- feat(faces): 更新人脸检测与嵌入模型为Glint360K R100
- feat(tag): 方案B 密度自适应 k-NN 人脸聚类
- feat(tag): 接入 Glint360K R100 MNN 替换失效 ArcFace R100 并增强聚类诊断
- feat(tag): integrate ArcFace R100 MNN embedder and fix landmark alignment
- feat(tagging): MobileFaceNet 5-point alignment + DBSCAN face clustering tuning
- feat(editor): 添加AI一键优化功能并更新隐私策略
- feat(agent): implement post-action screen state observation for ReAct UI tools
- feat(feishu): support implicit search queries in preview-Nth commands
- feat(accessibility): expose gallery media items with contentDescription and selection state
- feat(accessibility): make PicMeAccessibilityService available in release builds
- feat(agent): add search_photos tool for direct gallery search via Feishu
- feat(settings): add accessibility service guide entry in system settings
- feat(agent): integrate AccessibilityService for Compose UI remote control
- feat(agent): add top-level retry and friendly error for remote 502
- feat(agent): update ReAct system prompt for post-action screen state
- feat(agent): append post-action screen state to UI tools in PicMeToolService
- feat(agent): add UiObservationFormatter for post-action screen state
- feat(agent): support compact semantic summary in ViewHierarchyExtractor
- feat(test): accessibility-ui-driver skill + public helpers + input-by-bounds fix
- feat(test): minimal UI driver integration verification script
- feat(test): PC-side UiDriverClient with JSON-RPC over adb forward
- feat(test): debug accessibility service manifest and config
- feat(test): PicMeAccessibilityService lifecycle wrapper
- feat(test): JSON-RPC socket server for UI automation
- feat(test): Accessibility action performer for click/swipe/input/back
- feat(test): AccessibilityNodeInfo to UiNode serializer
- feat(test): Accessibility UI automation data models
- feat(editor): Phase 1 图片编辑器重构
- feat(search): 优化媒体搜索性能并改进FTS5全文搜索
- feat(search): 优化中英文跨语言搜索支持
- feat(gallery): 添加相册暖启动占位页面
- feat(gallery): 添加搜索加载状态和优化搜索结果预览
- feat(gallery): show batch delete/share actions for search result selection
- feat(search): add Chinese translations for 430 ML Kit image labels
- feat(search): integrate ExplicitFirstSearchPipeline into MediaSearchEngine
- feat(search): add ExplicitFirstSearchPipeline
- feat(search): add candidate-set queries to MediaDao
- feat(search): extend QueryParser with fine-grained time parsing
- feat(search): add QuerySegmenter
- feat(search): add segmented query data models
- feat(tag): integrate ML Kit Image Labeler as independent TagScanPass
- feat(tag): 使用 MobileCLIP-S2 ONNX 替换 MNN CLIP 后端
- feat(chat): 添加语音输入功能支持
- feat(navigation): 调整应用导航结构和界面布局
- feat(model): 更新模型配置支持新版本kimi和deepseek模型
- feat(search): 优化移动端语义搜索功能
- feat(gallery): 添加TAG控制和搜索测试功能
- feat(gallery): 优化AI标签扫描策略并改进UI组件
- feat: add SentencePiece test UI and navigation
- feat: integrate OPUS-MT translator with SentencePiece into search flow
- feat: add SentencePiece NDK module with JNI bridge for Android
- feat(search): 添加语义搜索引擎支持跨模态检索
- feat(facedetect): 实现多脸检测功能并优化合影识别逻辑
- feat(search): 实现 MobileCLIP 语义搜索与三层混合检索
- feat(database): 升级数据库并集成MobileCLIP语义编码功能
- feat(logger): 添加VERBOSE日志级别支持并扩展日志模块配置
- feat(vision): 添加图片识别功能的模型加载检查
- feat(tag): 重构相册 TAG 生成流程
- feat(settings): 添加TAG生成GPU加速开关功能
- feat(download): 更新模型文件配置并优化混淆规则
- feat(model): 添加模型必需标签显示功能
- feat(gallery): 添加人物分组重命名功能
- feat(chat): 调整快速操作按钮顺序并优化人脸识别参数
- feat(tag): 添加分阶段扫描控制功能和数据库统计接口
- feat(indexing): 优化人脸聚类和标签生成调度功能
- feat(tag): 集成人脸特征提取和标签生成控制界面
- feat(chat): 添加图片对话记忆功能支持图片上下文引用
- feat(chat): 实现内置相册选择器和模型切换功能
- feat(indexing): 添加身份证智能识别功能
- feat(tag): 添加相册自动标签生成技术方案与Agent集成
- feat(vision): 添加图像理解功能并优化多模态推理
- feat(gallery): wire ImageTagIndexingWorker into AppContainer and GalleryScreen
- feat(agent): expose getLocalLlmEngine() on AgentOrchestrator
- feat(indexing): add ImageTagIndexingWorker for AI vision tagging
- feat(llm): add imageInference method to LocalLlmEngine
- feat(llm): add generateWithImage API to MnnLlmClient
- feat(llm): add nativeGenerateWithImage JNI method for multimodal inference
- feat(llm): add vision files to Qwen3.5-2B-MNN model config
- feat: replace ThumbnailPrefetcher with dual-level ThumbnailCache
- feat(gallery): 添加相机功能入口

### 🐛 Bug Fixes
- fix(gallery): 修复预览页二次打开显示旧图及滑动时相邻视频后台播放
- fix(beauty): 修复 AI 一键优化暗部偏黄绿问题
- fix(feishu): support gallery search + preview Nth in direct search path
- fix(accessibility): bridge PicMeAccessibilityService to AccessibilityServiceHolder
- fix(feishu): make search_photos tool return human-readable result directly
- fix(gallery): update delegate to show search results from Agent search command
- fix(app): register GalleryCapability globally so Feishu search can find it
- fix(feishu): strengthen search prompt and add direct gallery-search fast path
- fix(gallery): catch search exceptions and return actionable error message
- fix(agent): add Compose search semantics and editable-node fallback for accessibility
- fix(agent): align ReAct system prompt and tool descriptions with actual tool schema
- fix(agent): correct ChatMemory trimming and remove unsafe top-level retry
- fix(agent): run all PicMeToolService UI operations on main thread
- fix(test): long_click supports bounds locator
- fix(test): use argparse in ui_driver.py CLI and add usage docs
- fix(test): click by contentDescription and walk up to clickable ancestor
- fix(gallery): 相册 TopBar 适配刘海/相机孔安全距离
- fix(gallery): refresh search results after media deletion
- fix(gallery): disable Coil crossfade on thumbnails to avoid recycled bitmap crash
- fix(gallery): enable long-press and batch selection in search results
- fix(search): filter bad Chinese-to-English translations for semantic recall
- fix(search): parse Chinese month names like 五月
- fix(tag-scan): 统一任务统计口径、修复扫描中统计不刷新、补回MobileCLIP编码、放宽人脸检测阈值
- fix(mobileclip): align tokenizer with HF CLIPTokenizer and harden search pipeline
- fix(tag): 优化中文查询翻译质量校验
- fix(search): 修复语义搜索中的NaN相似度计算问题
- fix(mnn): 修复CLIP文本编码器的张量维度和数据类型问题
- fix(i18n): OPUS-MT tokenizer vocab mapping + decoder optimization
- fix(build): 解决 onnxruntime-android 与 sherpa-onnx 的库冲突
- fix(chat): 修复聊天界面遮罩层点击关闭功能
- fix(gallery): 修复搜索结果媒体点击选择问题
- fix(tag): 修复人脸聚类中embedding分配问题
- fix(image): 修复 Android Bitmap 颜色通道提取逻辑
- fix(image): 修复 Android Bitmap 颜色通道提取逻辑
- fix(vision): 修复 Android Bitmap 颜色通道映射错误
- fix(face-clustering): 修复人脸聚类中的多项问题并改进算法
- fix(llm): use correct Qwen3.5 normalization from llm_config.json
- fix(llm): add Qwen-VL standard normalization to image preprocessing
- fix(indexing): auto-load LLM model in ImageTagIndexingWorker before tagging
- fix(build): add jnigraphics link and fix JVM method name clash
- fix(chat): inference mode routing based on AiAgentInferencePreference

### ♻️ Refactors
- refactor(skills): rename test skills to drop picme- prefix and unify naming
- refactor(skills): merge ui-automation-expert into accessibility-ui-driver and unify test skill naming
- refactor(skills): integrate adb-bot with test skills, remove deprecated agent-test-framework
- refactor(search): 移除FTS5搜索实现并优化搜索性能
- refactor(runtime): 统一本地模型加载管理机制
- refactor(camera): 重构语音控制模块集成 WakeWord KWS 引擎
- refactor(tag-generation): 重构标签生成控制界面
- refactor(settings): 重构设置页为多级分类结构
- refactor(models): 重构模型管理中心按服务功能分类
- refactor(navigation): 重构首页导航与聊天页界面
- refactor(tag): 重构标签生成控制界面组件结构
- refactor(tag): 重构自动标签生成管道，优化执行顺序和性能
- refactor(chat): 重构聊天界面输入区域为文本语音双模式
- refactor(ocr): 重构 ML Kit OCR 处理器实现
- refactor(ai-agent): 统一远程推理逻辑并优化设置界面结构
- refactor(settings): 调整设置页布局，突出 TAG 生成入口
- ``` refactor(tag): 优化图片标签生成调度器和AI扫描逻辑
- refactor(tag): 重构标签生成服务为前台服务架构
- refactor(PicMeApplication): 移除媒体列表中的源信息日志输出
- refactor(faces): 统一聚类配置并优化人脸聚类算法
- refactor(faces): 统一聚类配置并优化人脸聚类算法
- refactor(gallery): 重构媒体查看器界面并添加重新标记功能
- refactor(agent-core): remove unused SPI/ServiceLoader infrastructure

### 🧪 Tests
- test(agent): add PicMeToolService observation behavior tests
- test(test): add UiNode serialization and RPC parse tests

### 📚 Documentation
- docs(product): 更新产品架构文档与AI对话页定位调整
- docs(skills): prefer accessibility UI dump over screenshots in test skills
- docs: Phase 1 implementation plan for AccessibilityService UI automation
- docs: AccessibilityService 结构化 UI 自动化测试技术方案
- docs: update DEVELOPMENT, AI_TOOLS, CLAUDE.md; add release notes v1.0.4; bump version
- docs: refresh README/docs for gallery search; add GALLERY_SEARCH SSOT, delete obsolete docs
- docs(app): update AGENTS.md with explicit-first search architecture
- docs(app): update AGENTS.md for ML Kit tag pass integration
- docs(runtime-core): 添加 MNN-LLM 全局模型加载与多实例安全调研报告
- docs(skills): 更新技能文档中的脚本路径和包名引用
- docs(qa): 添加 PoLang 核心功能测试指南
- docs(qa): 添加 PoLang 核心功能测试指引文档
- docs: thumbnail LRU cache implementation plan
- docs: thumbnail LRU cache design spec
- docs: 更新项目文档以反映从 PoLang 到 langchain4android 的架构变更

### 🔧 Others
- perf(ui-driver): add gallery_search.py one-shot script and extend wait_for
- style(agent-core): compact HTTP request/response body logging to single line
- perf(agent): compact JSON output of ViewHierarchyExtractor to save tokens
- chore(agent): verify full build and tests for Feishu ReAct UI observation
- optimize(dev-loop): accessibility UI dump, --fast mode, package fixes
- perf(chat): 提高图像描述的 token 限制
- chore(release): bump version to 1.0.5 (10005) and update release notes
- chore(deps): 更新 sherpa-onnx 和相关依赖库版本
- chore(version): 更新应用版本号
- perf(tag): 优化 OPUS-MT 翻译引擎性能
- style(chat): 调整聊天界面UI组件样式
- chore(build): 修复 KSP 增量缓存损坏问题
- chore(android): 更新应用权限配置和版本信息
- chore(version): 更新应用版本号
- i18n(localization): 完善应用国际化支持
- perf(tag): 优化TAG生成性能，启用OpenCL GPU加速并添加性能分析文档
- style(chat): 调整聊天界面UI样式和布局
All notable changes to this project will be documented in this file.

