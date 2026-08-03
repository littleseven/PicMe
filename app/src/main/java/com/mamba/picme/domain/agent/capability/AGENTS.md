# IM 远程控制 Capability 模块技术实现规范 (Remote Control)

> **边界声明（Boundary Statement）**
> - 本文档仅承载本模块的实现细节（架构、代码约束、检查清单）。
> - 产品目标与验收口径以 `PRODUCT.md` 为准；交互流程与体验规则以 `docs/01-PRODUCT/FEATURES.md#5-im-远程控制融合入口` 为准。
> - 顶层治理规则（角色协作、全局红线、文档流程）以根目录 `AGENTS.md` 为准。
> - 跨模块技术架构以 `docs/03-TECHNICAL-SPECS/IM_REMOTE_CONTROL_TECH_SPEC.md` 为准。
> - 禁止将模块级实现细节回填到顶层 `AGENTS.md`；跨模块或专项技术内容应下沉到对应模块文档或 `docs/*_TECH_SPEC.md`。

**模块定位**：通过飞书等 IM 平台 + LLM 实现 App 远程控制的核心 Capability 与基础设施组件。

**核心组件**：
| 组件 | 位置 | 角色 |
|------|------|------|
| `RemoteControlCapability` | `domain/agent/capability/RemoteControlCapability.kt` | 设备绑定/状态管理/审计（应用级单例） |
| `FeishuChannelHandler` | `domain/agent/remote/FeishuChannelHandler.kt` | 飞书 WebSocket 连接/消息收发/图片上传（OAPI SDK） |
| `RemoteCommandDispatcher` | `domain/agent/remote/RemoteCommandDispatcher.kt` | 命令解析/LLM 意图理解/Capability 分派 |

**主要维护者**：项目开发者

**阅读对象**：RD、AI Agent

---

## 1. 核心产品逻辑 (Core Product Logic)

- **[IM_FLOW] IM 消息驱动**：所有远程操作由 IM 消息触发，通过飞书 WebSocket 实时推送至设备
- **[LOCAL] 命令执行在设备端**：飞书仅做消息中转，不执行任何业务逻辑
- **[PRIVACY] 人脸数据不离开设备**：涉及人像的检测/编辑强制设备端执行，不上传
- **[FEEDBACK] 操作结果回传**：所有命令执行后必须向 IM 回传结果（成功/失败/进度）
- **[CONFIRM] 敏感操作确认**：删除/批量操作/人像编辑/解绑需用户确认
- **[BIND] 扫码绑定**：首次绑定必须设备端扫码确认，防止未授权访问

## 2. 技术实现规范 (Technical Implementation)

### 2.1 RemoteControlCapability — 应用级单例管理

**设计原则**：
- 不通过 `AgentCommand` 密封类分发命令（不污染 agent-core）
- 通过公开 API 由 `RemoteCommandDispatcher` 直接调用
- `execute()` 始终返回 `METHOD_NOT_FOUND`（管理命令不走 AgentCommand 路由）
- 在 `Application.onCreate()` 中初始化，永不注销

**生命周期**：
```
Application.onCreate() → getInstance() 创建 → 注册到 CapabilityRegistry
    ├── 扫码绑定成功 → updateBinding(token, url, userId, deviceName)
    ├── 用户解绑 → clearBinding()
    ├── 设置自动确认 → setAutoConfirm(true/false)
    └── Application.onTerminate() → onDestroy() 清理
```

**状态管理**（线程安全）：
```kotlin
@Volatile private var _deviceBound: Boolean
@Volatile private var _deviceToken: String
@Volatile private var _boundUserId: String
@Volatile private var _autoConfirm: Boolean
```

### 2.2 Capability 全局注册（2026-07-29 单轨收敛）

`CapabilityRegistry` 是**唯一注册表**——`ComposeCapabilityHost` / `LocalCapabilityHost` / `GlobalCapabilityHost` 已全部退役删除（此前双轨制导致 Activity recreate 竞态后 chat Capability 整批不可见，见 2026-07-29 盘点故障）。

- 应用级 Capability：全部在 `PoLangApplication.initializeCapabilities()` 启动期注册（含 SettingsCapability 补注册——此前从未注册，是死能力）
- `NavigationCapability` / `SystemCapability`：依赖 NavController/Context，由 `MainActivity` 创建后即调用 `AgentOrchestrator.registerCapability()` 注册
- 页面级 `CameraCapability`：随 CameraScreen `DisposableEffect` register/unregister
- 飞书直接搜索快速通道在后台线程通过 `RemoteControlToolService` 调用 `CapabilityRegistry.dispatch()`，同样命中上述注册表

**实现位置**：`MainActivity.kt` 在创建 `navigationCapability`/`systemCapability` 后同步调用：
```kotlin
val orchestrator = AgentOrchestrator.getInstance(applicationContext)
orchestrator.registerCapability(navigationCapability)
orchestrator.registerCapability(systemCapability)
```

### 2.3 FeishuChannelHandler — 飞书通道管理

**关键行为**：
- 使用飞书 OAPI SDK 的 `ws.Client` 与飞书平台建立 WebSocket 长连接
- 连接由设备端主动发起（出站连接），不受 NAT/防火墙限制
- 飞书 SDK 内置重连机制（指数退避）
- 接收 `P2MessageReceiveV1` 事件后分发到 `RemoteCommandDispatcher`
- 通过飞书 OAPI HTTP 客户端回复消息/上传图片
- 连接状态通过回调暴露

**初始化流程**：
```
Application.onCreate() → FeishuChannelHandler.init(appId, appSecret)
    ├── apiClient = OAPI Client.Builder(appId, appSecret).build()
    ├── wsClient = WS Client.Builder(appId, appSecret)
    │       .eventHandler(eventHandler)
    │       .build()
    └── wsClient.start()  // 建立长连接
```

**与 ApkClaw 的差异**：
- `FeishuChannelHandler` 只负责飞书通道（消息收发），不直接处理业务
- 业务命令全部委托给 `RemoteCommandDispatcher` → Capability 系统
- 浏览/搜索等自有 Capability 操作不依赖 AccessibilityService；但“预览第 N 张”等需要识别 Compose 网格具体项的场景，依赖无障碍服务读取语义节点

### 2.4 RemoteCommandDispatcher — 命令分派

**解析策略**：

| 命令类型 | 解析方式 | 说明 |
|----------|----------|------|
| **明确动作** | 直接映射 → Capability | `action: "browse_recent"` → GalleryCapability |
| **自然语言** | LLM 解析意图 | `"帮我优化这张照片"` → `edit_image / ai_optimize` |
| **组合命令** | LLM 分解为子任务 | `"裁剪成1:1再调亮"` → `[crop, brightness]` |
| **相册搜索快速通道** | 正则提取，不经过 LLM | `"搜索去年夏天小孩的照片"`、`"打开相册搜索7月的美女预览第四张"` |

**执行流程**：
```
收到命令 → 解析意图 → 分发到 CapabilityRegistry.dispatch()
    → 收集结果 → FeishuChannelHandler.sendImage/sendMessage 回复
```

**相册搜索快速通道（2026-07）**：
- 为避免 LLM 不遵循 prompt 导致搜索失败，`RemoteCommandDispatcher` 对明确包含“搜索/查找/找...的照片”的指令走本地直接执行。
- 支持复合命令：`打开相册，搜索7月的美女，预览第四张` → 提取 query=`7月的美女`、index=`4`。
- 执行步骤：`navigate_to(gallery)` → `search_photos(query)` → （可选）`click_gallery_item(index)`。
- `click_gallery_item` 依赖无障碍服务识别相册网格中 `contentDescription` 以 `照片/Photo/视频/Video/文档/Document` 开头的语义节点。
- 若当前已在相册页，`navigate_to(gallery)` 会忽略（不报错），因此复合命令在任意场景均可触发。

### 2.5 与 agent-core 的边界

- **禁止**：在 `AgentCommands.kt` 中添加远程控制专用的 `Remote*` 类型
- **禁止**：在 agent-core 中引入任何飞书 SDK 相关依赖
- **允许**：在 `app` 模块的 domain 层引入飞书 OAPI SDK
- **原则**：agent-core 保持零业务依赖，远程控制相关类型和逻辑全部在 `app` 模块

### 2.6 确认流程矩阵

| 操作类型 | 确认要求 | 实现 |
|----------|----------|------|
| 浏览/搜索 | 不要求 | 直接执行 |
| 图片编辑（非人像） | 不要求 | 直接执行 |
| 图片编辑（人像） | 确认 | 飞书交互卡片确认 |
| 批量操作 | 确认 | 飞书交互卡片确认 |
| 删除操作 | 确认 | 飞书交互卡片确认 |
| 设备解绑 | 双重确认 | App 内确认 + 飞书确认 |
| 系统设置修改 | 确认 | 飞书交互卡片确认 |

## 3. Agent 执行规约 (Execution Rules)

- **RemoteControlCapability 修改**：只能修改 `app` 模块代码，严禁修改 `agent-core`/`AgentCommands.kt`
- **单例访问**：通过 `RemoteControlCapability.getInstance()` 获取实例，禁止直接构造
- **线程安全**：所有状态字段使用 `@Volatile` 修饰，读写操作不涉及复合操作则无需同步
- **状态回传**：任何绑定状态变更后必须记录日志（`PoLang:RemoteControl` TAG）
- **飞书 SDK 重连**：飞书 SDK 内置重连机制，设备端无需额外实现
- **结果回传**：命令执行结果（含错误）必须通过飞书回复，不能静默失败
- **LLM 调用**：IM 远程的 LLM 调用共享 `UnifiedRemoteClient`，但使用独立的 System Prompt
- **隐私红线**：涉及人脸的照片处理必须在设备端完成，不得经飞书平台中转
- **UI 依赖**：RemoteControlCapability 不依赖任何页面存在，在任何页面/后台均可访问

## 4. 常见陷阱检查清单 (Checklist)

- [ ] 飞书 SDK 的 `ws.Client` 是否初始化成功？（检查 App ID/Secret 配置）
- [ ] FeishuChannelHandler 是否通过 `init()` 启动？（确保在 Application.onCreate 中调用）
- [ ] 飞书 SDK 的 packaging exclude 是否配置？（META-INF 冲突）
- [ ] 状态字段是否有 `@Volatile` 修饰？（确保可见性）
- [ ] 敏感操作是否加了确认流程？（参照确认矩阵）
- [ ] 人脸相关操作是否强制设备端执行？（隐私红线）
- [ ] 命令执行结果是否总是回传？（即使失败）
- [ ] `execute()` 方法是否始终返回 `METHOD_NOT_FOUND`？（不走 AgentCommand 路由）
- [ ] 是否使用了 `PoLang:RemoteControl` TAG 记录关键日志？

## 5. 与产品文档对照 (Product Alignment)

**必须满足的产品指标**：
- ✅ IM 消息驱动 → 飞书 WebSocket 实时推送，无需云端中转
- ✅ 命令执行在设备端 → 飞书仅做消息通道
- ✅ 人脸数据不离开设备 → 人像编辑强制走本地推理
- ✅ 操作结果回传 → 所有命令执行后通过飞书 OAPI 回复
- ✅ 敏感操作确认 → 飞书交互卡片 + App 内双重确认
- ✅ 扫码绑定 → 设备端扫码绑定，防止未授权访问
- ✅ 多设备支持 → 通过 device_token 区分，@指定设备执行

**技术决策记录**：
- **RemoteControlCapability 不通过 AgentCommand 分发**：保持 agent-core 零业务依赖
- **设备直连飞书 WebSocket**：参考 ApkClaw 方案，无需任何云端基础设施
- **飞书 OAPI SDK 直接上传图片**：图片直接从设备到飞书，不走中间存储
- **独立 System Prompt**：IM 远程场景的 LLM 上下文与 App 内不同，需独立维护
- **飞书 SDK 内置重连**：无需手动实现心跳/重连逻辑

## 6. 相关任务索引 [agent-task]

| ID | 任务 | 阶段 | 状态 |
|----|------|------|------|
| `im-remote-001` | 引入飞书 OAPI SDK 依赖，配置 packaging exclude | Phase 1 | 待实现 |
| `im-remote-002` | 实现 FeishuChannelHandler（WS 连接 + OAPI 客户端） | Phase 1 | 待实现 |
| `im-remote-003` | 实现飞书消息接收与文本/图片回复 | Phase 1 | 待实现 |
| `im-remote-004` | 实现 RemoteCommandDispatcher（命令接收/LLM解析/分派） | Phase 1 | 待实现 |
| `im-remote-005` | 实现设备绑定流程（配对码 + 状态管理） | Phase 1 | 待实现 |
| `im-remote-006` | 实现远程相册浏览/搜索命令 | Phase 2 | 待实现 |
| `im-remote-007` | 实现远程图片编辑命令（单张） | Phase 2 | 待实现 |
| `im-remote-008` | 实现结果图片直接上传飞书并回复 | Phase 2 | 待实现 |
| `im-remote-009` | 实现远程相册管理命令（创建/移动/删除） | Phase 3 | 待实现 |
| `im-remote-010` | 实现交互式卡片模板与确认流程 | Phase 3 | 待实现 |
| `im-remote-011` | 实现多设备管理与@指定 | Phase 3 | 待实现 |
| `im-remote-012` | 实现批量编辑命令 | Phase 3 | 待实现 |
| `im-remote-013` | 实现操作审计日志 | Phase 3 | 待实现 |
| `im-remote-014` | 端到端测试：飞书消息 → 设备执行 → 结果回传 | Phase 4 | 待实现 |
| `im-remote-015` | 性能基准测试 | Phase 4 | 待实现 |
| `im-remote-016` | 异常场景测试 | Phase 4 | 待实现 |

---

> **维护者**：RD Agent
> **最后更新**：2026-06-17
> **方案变更**：~~SCF Relay Server~~ → 设备端直连飞书 WebSocket
> **状态**：设计阶段 · 待实现
