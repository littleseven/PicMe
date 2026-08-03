# Camera 模块技术实现规范 (Camera Technical Implementation)

> **边界声明（Boundary Statement）**
> - 本文档仅承载本模块的实现细节（架构、代码约束、检查清单）。
> - 产品目标与验收口径以 `PRODUCT.md` 为准；交互流程与体验规则以 `docs/01-PRODUCT/FEATURES.md` 为准。
> - 顶层治理规则（角色协作、全局红线、文档流程）以根目录 `AGENTS.md` 为准。
> - 禁止将模块级实现细节回填到顶层 `AGENTS.md`；跨模块或专项技术内容应下沉到对应模块文档或 `docs/*_TECH_SPEC.md`。

**模块定位**：确保 PoLang 的相机模块实现零延迟拍摄、智能场景识别和实时 HDR 处理。

**主要维护者**：项目开发者

**阅读对象**：CO、PM、RD、CR、QA、AI Agent

## 1. 核心产品逻辑 (Core Product Logic)

- **[PERF] 零延迟拍摄**：快门延迟 < 50ms，使用 `CAPTURE_MODE_MINIMIZE_LATENCY` 模式
- **[FEEDBACK] 三位一体反馈**：触感 + 音效 + 黑场必须同步触发（50ms 内）
- **[OFFLINE] OCR 本地识别**：使用 ML Kit 离线引擎，严禁云端处理
- **[PRIVACY] 权限最小化**：仅在需要时申请相机和存储权限，提供降级方案
- **[REALTIME] 美颜实时预览**：所有美颜效果处理延迟 < 100ms，支持实时预览
- **[ENGINE] 单引擎架构**：自研 `beauty-engine`（BIG_BEAUTY）为唯一引擎；GPUPixel 已于 2026-05 完全移除
- **[NATURAL] 自然美学原则**：所有美颜效果必须保持自然，避免过度失真
- **[ACCURACY] 十字星精确跟踪**：人脸跟踪十字星偏差 < 5px，支持旋转/缩放/镜像场景
- **[MLKIT] 人脸能力预留**：表情/状态属性（微笑、睁眼、头部角度）仍保留为产品能力，但需通过独立 ML Kit 分析流补齐；MediaPipe Face Landmarker 作为当前预览主分析链路，Selfie Segmentation 作为异步增强流引入，严禁阻塞预览渲染线程

## 2. 技术实现规范 (Technical Implementation)

### 2.1 CameraX 配置要求

- **ImageCapture**：必须使用 `CAPTURE_MODE_MINIMIZE_LATENCY` 模式，JPEG 质量设为 85（平衡画质与体积）
- **Preview**：锁定目标帧率 30fps，避免帧率波动导致卡顿
- **闪光灯**：默认使用自动模式（FLASH_MODE_AUTO）

### 2.2 场景识别实现逻辑

- **夜景模式触发条件**：当环境光照度 (Lux) < 10.0 时建议自动触发
  - 曝光补偿：+1.0 to +2.0
  - ISO 限制：自动提升至 1600+
- **月亮模式检测**：需同时满足三个条件：
  - 变焦倍率 > 3.0x
  - 检测到高对比度圆形物体
  - 场景为逆光环境
  - 锁定最大焦距，避免过度放大导致模糊

### 2.3 OCR 引擎集成

**ML Kit 配置要求**：
- 使用离线 Document Scanner API，禁用相册选择功能（仅允许实时拍摄）
- 设置设备源为 CAMERA，确保调用后置摄像头

**拍摄后自动 OCR 流程**：
1. **拍摄完成回调** → `onCaptureSuccess(photoUri: Uri)`
2. **立即触发后台 OCR 任务** → 调用 `viewModel.startOcrTask(photoUri)`，不阻塞 UI
3. **同步显示拍照反馈** → 播放黑场、音效、触感三位一体反馈
4. **OCR 结果展示** → 通过 TextOverlay 组件，使用 AnimatedVisibility 实现平滑出现/消失动画

### 2.4 性能优化关键点

**启动速度优化 (< 500ms)**：
- **时间分配要求**：
  - **0-50ms**：Application 初始化，仅注册必要组件（ActivityLifecycleCallbacks）
  - **50-150ms**：权限检查与请求（相机、存储）
  - **150-300ms**：CameraX 初始化并绑定生命周期
  - **300-500ms**：首次预览帧渲染完成
- **禁止事项**：
  - ❌ 预加载相机模块
  - ❌ 初始化 AI 模型（应懒加载）
  - ❌ 执行数据库迁移或大量数据加载

> **美颜算法实现细节与 GPU 加速策略已迁移至 `beauty-engine/AGENTS.md`。**
> Camera 模块仅负责调用 `beauty-engine` 提供的 `BeautyPreviewEngine` 接口。

**拍摄反馈同步机制**：
- **三位一体反馈必须同时触发**：
  1. **触感反馈**：调用 `HapticFeedback.performHapticFeedback()`，使用 LONG_PRESS 类型
  2. **音效播放**：启动 MediaPlayer 播放快门音（需低延迟播放器）
  3. **黑场动画**：通过 LaunchedEffect 实现 50ms 透明度渐变（1.0 → 0.0）
- **关键点**：三个反馈必须在同一帧内触发，任一反馈缺失都会导致用户感知“卡顿”

### 2.5 人脸跟踪十字星坐标转换

**问题定义**：ML Kit 检测到的人脸坐标需要转换为屏幕坐标，用于绘制十字星指示器。

**数据流与处理时机**：
```
Camera Sensor (原始图像)
    ↓
ImageProxy (YUV_420_888, rotationDegrees)
    ↓
ML Kit InputImage.fromMediaImage(mediaImage, rotationDegrees)
    ├── ML Kit 内部自动处理旋转补偿
    └── 输出：Face.boundingBox（已旋转的坐标）
    ↓
transformFaceCoordinate() / transformFaceCoordinateSimple()
    ├── Step 1: 按 rotationDegrees 交换宽高并归一化
    ├── Step 2: 前置镜像补偿（x = 1 - x）
    ├── Step 3: 旋转补偿
    └── Step 4: 映射为 Preview 像素坐标
```

**关键说明**：
- ✅ **ML Kit 自动处理旋转**：`InputImage.fromMediaImage()` 根据 `rotationDegrees` 调整检测坐标系
- ✅ **检测结果已是旋转后坐标**：`face.boundingBox` 返回相对于**旋转后**图像的坐标
- ✅ **归一化无需额外旋转**：直接使用 `imageProxy.width/height`（传感器物理尺寸）相除即可
- ✅ **Step 2 的目的**：将**已旋转的图像坐标系**映射到**屏幕显示坐标系**

**坐标系统差异**：
1. **图像坐标系**：ML Kit 检测坐标，原点在左上角，基于旋转后的图像
2. **屏幕坐标系**：PreviewView 渲染坐标，考虑 FIT_CENTER 缩放和 letterbox 效应
3. **变换因素**：旋转（坐标系映射）、镜像（前置摄像头）、宽高比适配

**四步转换算法**：

```kotlin
val (rotatedWidth, rotatedHeight) = when (rotationDegrees) {
    90, 270 -> Pair(imageProxyHeight, imageProxyWidth)
    else -> Pair(imageProxyWidth, imageProxyHeight)
}

// Step1: 归一化
val normX = faceX / rotatedWidth
val normY = faceY / rotatedHeight

// Step2: 前置镜像
val mirroredX = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
    1f - normX
} else {
    normX
}

// Step3: 旋转补偿
val (adjustedX, adjustedY) = when (rotationDegrees) {
    180 -> Pair(1f - mirroredX, 1f - normY)
    else -> Pair(mirroredX, normY)
}

// Step4: 像素映射
val screenX = adjustedX * previewWidth
val screenY = adjustedY * previewHeight
```

> 约束：`transformFaceCoordinateSimple()` 与 `transformFaceCoordinate()` 必须保持同构，避免分析链路和绘制链路出现偏移差异。

**关键实现要点**：
- ✅ **统一转换函数**：分析链路使用 `transformFaceCoordinateSimple()`，屏幕绘制链路使用 `transformFaceCoordinate()`
- ✅ **前置摄像头镜像**：X 坐标翻转 `1 - normX`
- ✅ **旋转宽高先交换**：`rotationDegrees` 为 90/270 时先交换宽高再归一化
- ✅ **完整日志记录**：固定输出 `Step1~Step4` 便于调试

**验证场景**：
- ✅ 竖屏自拍（前置 0°）：十字星镜像对齐
- ✅ 横屏拍摄（后置 90°）：十字星精确跟踪
- ✅ 视频通话（前置 270°）：十字星无偏移
- ✅ 2x 变焦：十字星仍精确对准人脸

**十字星显示状态机（2026-04）**：
- **S1 隐藏**：未锁定人脸时保持隐藏。
- **S2 锁定提示**：锁定人脸且设备运动时快速淡入（用于提示追焦）。
- **S3 稳定消退**：锁定人脸且画面稳定持续短暂阈值后淡出，降低对构图遮挡。
- **实现要求**：
  - 只允许一个统一状态机驱动透明度，禁止多处并行触发动画。
  - `锁定 -> 稳定`必须有短暂延时，避免稳定阈值抖动导致闪烁。
  - `锁定丢失`应立即回到隐藏态。

## 3. Agent 执行规约 (Execution Rules)

### 3.0 团队协作运行模式（单实例多角色）
- **角色流转**：同一会话内按 `CO -> PM -> RD -> CR -> QA` 串行执行。
- **触发口令**：`自动执行`（默认 AUTO_MAX 自动推进）、`保守执行`（关键节点确认）。
- **自动修复预算**：单任务最多自动修复 2 次，超限必须上报并提供备选方案。
- **红线暂停**：仅在隐私风险、不可逆操作或缺失外部输入时请求确认。

- **拍摄操作**：必须在后台线程保存照片，避免阻塞 UI
- **OCR 管理**：OCR 引擎在空闲 30s 后必须释放，避免内存泄漏
- **夜景模式**：必须正确应用曝光补偿（+1.0 to +2.0）
- **月亮模式**：必须锁定最大焦距，避免过度放大导致模糊
- **权限处理**：权限被拒绝时必须提供降级方案和引导设置
- **I18N**：所有用户可见字符串必须提取到 strings.xml
- **美颜效果**：
  - **实时预览**：所有美颜效果必须支持实时预览，延迟 < 100ms
  - **GPU 加速**：优先使用 GPU 实现，避免 CPU 计算瓶颈
  - **自然美学**：所有效果必须保持自然，避免过度失真
  - **安全约束**：瘦脸、身材调整等必须限制在安全范围内
  - **记忆功能**：记住用户上次使用的参数组合
- **十字星跟踪**：
  - **必须使用统一转换函数**：严禁在业务代码散落不同坐标算法
  - **必须处理旋转和镜像**：前置摄像头、横屏场景必须验证
  - **必须添加调试日志**：记录 `Step1~Step4` 便于排查问题
- **UI 主题色适配**（新增 2026-03-31）：
  - **严禁硬编码颜色**：所有文字、图标、背景必须使用 `MaterialTheme.colorScheme`
  - **文字颜色规范**：
    - 主要文字：`MaterialTheme.colorScheme.onSurface`
    - 次要文字：`MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)`
    - 强调文字：`MaterialTheme.colorScheme.primary`
  - **图标颜色规范**：
    - 常规图标：`MaterialTheme.colorScheme.onSurface`
    - 激活图标：`MaterialTheme.colorScheme.primary`
    - 次要图标：`MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)`
  - **背景颜色规范**：
    - 主背景：`MaterialTheme.colorScheme.surface`
    - 次级背景：`MaterialTheme.colorScheme.surfaceVariant`
    - 半透明背景：`MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)`
  - **禁止使用**：`Color.White`、`Color.Black`（除了在 Color Scheme 定义中）
  - **必须验证**：在浅色和深色模式下都要测试文字可读性
- **沉浸式模式**（新增 2026-03-31）：
  - **系统栏隐藏**：相机页面必须隐藏状态栏和导航栏，提供全屏拍摄体验
  - **实现方式**：
    - 使用 `WindowInsetsControllerCompat` 控制系统栏
    - 调用 `hide(WindowInsetsCompat.Type.systemBars())` 隐藏系统栏
    - 设置 `systemBarsBehavior = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` 允许滑动边缘临时显示
  - **生命周期管理**：
    - 在 `CameraScreen` 的 `DisposableEffect` 中配置
    - 页面退出时必须恢复系统栏显示（`show(WindowInsetsCompat.Type.systemBars())`）
  - **日志记录**：
    - 进入沉浸式：`PoLangLogger.d("PoLang:Camera", "Immersive mode enabled")`
    - 退出沉浸式：`PoLangLogger.d("PoLang:Camera", "Immersive mode disabled")`
  - **注意事项**：
    - 不需要手动调整UI元素的padding（EdgeToEdge已在MainActivity配置）
    - 确保获取到Activity窗口引用（需要类型转换 `context as? Activity`）

## 4. 常见陷阱检查清单 (Checklist)

### 4.1 功能与性能检查
- [ ] 是否在拍摄时阻塞了 UI 线程？（保存操作必须在后台）
- [ ] OCR 引擎是否在空闲 30s 后释放？（避免内存泄漏）
- [ ] 夜景模式的曝光补偿是否正确应用？（+1.0 to +2.0）
- [ ] 月亮模式是否锁定了最大焦距？（避免过度放大导致模糊）
- [ ] 拍照音效是否使用了低延迟播放器？（MediaPlayer 延迟过高）
- [ ] 权限被拒绝时是否有降级方案？（如无相机权限时引导设置）
- [ ] 所有用户可见字符串是否已提取到 strings.xml？（支持 I18N）
- [ ] 拍摄反馈是否三位一体同步触发？（触感 + 音效 + 黑场）
- [ ] 美颜效果是否使用 GPU 加速？（CPU 计算会导致卡顿）
- [ ] 瘦脸/大眼是否限制在安全范围内？（避免失真）
- [ ] 唇色是否保留唇部纹理？（避免塑料感）
- [ ] 身材调整是否保持身体比例？（避免变形）
- [ ] 十字星坐标转换是否统一走 `transformFaceCoordinateSimple()` / `transformFaceCoordinate()`？（严禁多套算法并存）
- [ ] 前置摄像头是否正确镜像？（十字星应跟随镜中人脸）
- [ ] 横屏拍摄是否处理了 90°旋转？（坐标应正确映射）
- [ ] 是否添加了调试日志？（便于排查错位问题）

### 4.2 UI 主题色适配检查
- [ ] 美颜面板文字颜色是否使用 `MaterialTheme.colorScheme.onSurface`？（不要硬编码 `Color.White`）
- [ ] 滤镜选择器文字颜色是否使用主题色？（避免浅色模式下不可见）
- [ ] 比例选择器文字颜色是否使用主题色？（选中/未选中状态都要检查）
- [ ] 所有图标颜色是否使用 `onSurface` 或 `primary`？（避免硬编码白色）
- [ ] 滑块组件颜色是否使用主题色？（thumbColor、trackColor 都要检查）
- [ ] 背景颜色是否使用 `surfaceVariant`？（不要使用半透明白色）
- [ ] 边框颜色是否使用主题色？（避免硬编码白色边框）
- [ ] 是否在浅色和深色模式下都验证了文字可读性？（两种模式都要测试）

### 4.3 沉浸式模式检查
- [ ] 相机页面是否隐藏了状态栏和导航栏？（提供全屏拍摄体验）
- [ ] 是否使用 `WindowInsetsControllerCompat` 控制系统栏？（不要使用已废弃的API）
- [ ] 是否在 `DisposableEffect` 中管理沉浸式生命周期？（确保正确清理）
- [ ] 页面退出时是否恢复了系统栏显示？（避免影响其他页面）
- [ ] 是否正确获取了Activity窗口引用？（需要类型转换 `context as? Activity`）
- [ ] 是否记录了沉浸式模式的启用和禁用日志？（便于调试）
- [ ] UI元素是否正常显示？（不被系统栏遮挡，EdgeToEdge已在MainActivity配置）

## 2.7 Agent 集成规范（2026-05 新增，2026-08 远程 tool_calls 对齐）

**CameraAgentIntegration**
- 通过 `CameraCapability` 绑定到 `CapabilityRegistry`
- 在 `CameraScreen` 中通过 ` remember { CameraAgentIntegration(...) }` 创建
- 生命周期绑定：`DisposableEffect` 中注册 delegate，退出时解绑
- 支持命令：
  - `capture` — 触发拍照
  - `switch_camera` — 切换前后置
  - `set_beauty_param` — 设置美颜参数（支持多参数同时调节）
  - `set_filter` — 切换滤镜
  - `set_style_effect` — 切换风格特效

**AI 指令链路（远程 tool_calls）**
- 端侧文本 LLM 已移除，相机 AI 指令统一走远程 tool_calls：
  `CameraScreen` → `AiAgentUseCase` → `AgentOrchestrator.processCameraInput()` → 远程 LLM + `CameraToolService`（@Tool 工具集，scene=CAMERA）→ `ToolCallCommandParser` 解析 → `CapabilityRegistry` 执行
- 协议与 chat 场景一致（标准 OpenAI tool_calls，ADR-005），不新造协议
- AI 输入 UI 保留（`AiChatScreen` 浮动面板），但不再有端侧文本模型加载/卸载逻辑

**AiAgentPanel → AiChatScreen 迁移**
- 旧版：`Dialog` 设计的 `AiAgentPanel`（已废弃）
- 新版：`ModalBottomSheet` 设计的 `AiChatScreen`，支持折叠/展开
- 通过 `CameraAgentIntegration` 统一消息状态管理

### 2.8 语音控制集成（2026-05 新增，2026-06 耳机模式适配，2026-06 KWS 迁移）

**VoiceCommandCoordinator**
- 在 `CameraScreen` 中通过 `remember { VoiceCommandCoordinator(...) }` 创建
- 支持双模式切换：`PUSH_TO_TALK` / `WAKE_WORD`
- 声控开关状态与对话历史同步（`UserPreferencesRepository`）

**KwakeWordKwsEngine（Sherpa-ONNX KWS）**
- 相机预览可见时运行，页面退出自动停止
- 使用 Sherpa-ONNX `KeywordSpotter` 实现 always-on 低功耗唤醒词检测（~14MB INT8）
- 检测到唤醒词后触发 ASR → 远程 tool_calls 解析 → 命令执行
- KWS 模型不可用时回退到原 `WakeWordEngine`（VAD + ASR 文本匹配）

**耳机模式适配（2026-06 新增）**
- 自动检测音频输入设备优先级：**蓝牙耳机 SCO > 有线耳机 > 内置麦克风**
- `AudioRecorder` 根据设备类型动态选择音源和效果器：
  - 蓝牙耳机：启动 `startBluetoothSco()`，使用 `MIC` 音源，关闭 AEC/NS（蓝牙协议自带 DSP 处理），增益 0.7
  - 有线耳机：使用 `MIC` 音源，关闭 AEC（耳机通常自带），启用 NS，增益 0.9
  - 内置麦克风：使用 `VOICE_COMMUNICATION` 音源，启用 AEC + NS，增益 1.0
- 输入增益在 `read()` / `readShortArray()` / `readSegment()` 中实时应用，防止蓝牙耳机麦克风过驱动导致削波
- 所有语音入口（`PushToTalkEngine`、`KwakeWordKwsEngine`、`SherpaOnnxAsrEngine` 流式识别）统一通过 `context` 传入 `AudioRecorder`，确保耳机模式全局生效
- 蓝牙 SCO 生命周期：录音启动时 `startBluetoothSco()` + `MODE_IN_COMMUNICATION`，停止时恢复 `MODE_NORMAL`

**语音命令反馈**
- 识别成功：AgentMessage.AgentText 显示识别结果
- 命令执行：实时更新 Camera 状态
- 识别失败：AgentMessage.AgentText 显示错误提示

## 5. 与产品文档对照 (Product Alignment)

**必须满足的产品指标**：
- ✅ 快门延迟 < 50ms → 使用 `CAPTURE_MODE_MINIMIZE_LATENCY`
- ✅ 三位一体反馈 → 触感 + 音效 + 黑场同步触发
- ✅ OCR 无感集成 → 后台异步处理，不阻塞拍摄流
- ✅ 冷启动 < 500ms → 分阶段初始化，懒加载 AI 模型
- ✅ 美颜实时预览 → GPU 加速，延迟 < 100ms
- ✅ 自然美学 → 所有效果限制在安全范围内
- ✅ 十字星精确跟踪 → 统一四步坐标转换，偏差 < 5px

**技术决策记录**：
- 选择 CameraX 而非 Camera2：简化生命周期管理，降低代码复杂度
- 使用 ML Kit Document Scanner：离线可用，精度足够，无需云端
- 黑场时长定为 50ms：模拟单反机械快门感受，经用户测试最佳
- 美颜使用 GPU 加速：CPU 计算无法满足实时性要求
- 单引擎架构（BIG_BEAUTY）：自研 OpenGL ES + EGL 多 Pass 渲染管线
- 十字星坐标转换统一为四步法：避免分析链路与绘制链路偏移
- 坐标调试日志固定 Step1~Step4：便于快速定位旋转/镜像问题
- `BeautyParamsConverter`：统一 `BeautySettings` → `BeautyParams` 转换逻辑，包括 `FilterType` → `colorMatrix` 映射

### 2.6 ML Kit 人脸增强能力实现规范

#### 2.6.1 表情与状态属性（能力预留）
相关字段与产品能力仍保留，但当前预览主分析链路已切换为 MediaPipe；如需启用，需补充独立 ML Kit 分析流，并确保与预览渲染线程隔离：

| 属性 | 字段 | 应用场景 | 实现位置 |
|:---|:---|:---|:---|
| 微笑概率 | `face.smilingProbability` | 微笑快门（可选开关） | `CameraFrameAnalyzer` / `CameraScreen` |
| 左眼睁开 | `face.leftEyeOpenProbability` | 闭眼提醒、连拍选优 | `CameraFrameAnalyzer` |
| 右眼睁开 | `face.rightEyeOpenProbability` | 同上 | `CameraFrameAnalyzer` |
| 头部欧拉角 | `face.headEulerAngleX/Y/Z` | 侧脸时自动降低瘦脸/大眼强度 | `CameraScreen` 参数联动 |
| 追踪 ID | `face.trackingId` | 多人场景稳定跟踪 | 未来扩展 |

**约束**：
- 这些属性读取必须在 `ImageAnalysis` 异步回调中完成，严禁放入主线程阻塞逻辑。
- 侧脸降强度逻辑应通过状态流（`StateFlow` / `remember`）联动到美颜参数下发，避免直接修改用户保存的偏好值。

#### 2.6.2 MediaPipe Face Landmarker 468 点（已落地）
- **引入方式**：集成 MediaPipe `face_landmarker` 任务（`face_landmarker.task` 模型），通过 `ImageAnalysis` 异步分析流驱动。
- **数据流**：`ImageAnalysis` → `MediaPipeFaceDetector` → 468 点坐标 → 468→106 点语义映射 → `FaceWarpParams` → `BeautyPreviewEngine`；若 MediaPipe 连续漏检或初始化失败，可自动回退到本地 `InsightFace2D106Detector`（ML Kit 人脸框 + InsightFace `2d106det.onnx`）直接输出 106 点。
- **映射规范**：
  - 106 点轮廓为开放曲线（33 点）：从右鬓角(0) → 下巴(16) → 左鬓角(32)。
  - 非轮廓区域（73 点）：眉毛、鼻梁、鼻尖、眼睛、鼻孔、嘴巴、瞳孔，对齐字节火山引擎 106 点标准。
  - 映射策略：优先使用对等语义点，缺失点使用插值。详见 `MediaPipeFaceDetector.kt`。
- **性能红线**：
  - MediaPipe Face Landmarker 推理耗时约 20-40ms/帧（中端机，GPU delegate），**绝对禁止**放入预览渲染管线同步执行。
  - InsightFace `2d106det` 仅允许作为异步备选检测链路触发，禁止每帧常驻推理；当前实现要求至少连续 3 次主链路漏检且满足冷却时间后才允许触发。
  - 必须采用"异步分析 + 参数插值"策略：分析流每 200-300ms 更新一次关键点，预览流根据最近一次结果进行平滑插值。
- **应用场景**：精细美型参数映射（瘦脸、大眼）、妆容 UV 贴合（唇色、腮红）。
- **调试浮层约束**：开启 `showFaceDebugOverlay` 后，浮层必须同时展示当前帧 `detectionSource` 与设置页选择的 `requestedDetectionEngineMode`，避免误判 `Auto -> InsightFace` 的备选命中来源。

#### 2.6.3 Selfie Segmentation（Phase 2-3）
- **引入方式**：新增 ML Kit `segmentation-selfie` 依赖。
- **数据流**：`ImageAnalysis` → `SelfieSegmenter` → 前景 Mask Bitmap → GPU 纹理上传 → Shader 蒙版应用。
- **性能注意**：
  - Mask 生成在 CPU，但应用必须在 GPU（通过 `uniform sampler2D` 传入 Shader）。
  - 低端机可降级为关闭分割相关效果。
- **隐私约束**：完全端侧运行，符合 `[PRIVACY]` 红线。
