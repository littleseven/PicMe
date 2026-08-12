# 双端体验一致性差异清单（Android ↔ iOS）

> **目的**：逐屏 code 级结构对齐审计，产出按「用户体感影响」排序的差异清单，驱动对齐工作（**相机页高 ROI 项 #1/#3 已于 2026-08-10 对齐合并 main**；下一优先见 §0 策略）。
> **基准**：Android `main` 为 ground truth；契约 SSOT = `specs/screens/*.yaml`（camera/gallery-grid/chat/settings/model-download-center）。
> **方法**：5 个并行只读 subagent 逐屏比对 Android Compose 实现 vs iOS SwiftUI 实现；高严重度项附双侧 `文件:行` 证据。一致性框架见 [`IOS_ANDROID_UI_PARITY.md`](../03-TECHNICAL-SPECS/IOS_ANDROID_UI_PARITY.md) §0。
> **日期**：2026-08-10 · 真机 baseline 已采（iPhone 15，`scripts/auto_test_output/ios_20260810_005256/`）。
> **图例**：🔴 需一致层（信息层级/布局/功能默认/文案状态/触发时机/无障碍） · 🟢 允许平台原生差异 · 体感：高=每次用都察觉/核心流；中=细心用户察觉/边缘；低=几乎不察觉。

> **🔄 2026-08-12 更新（post-snapshot 解决批次）**：本审计定格 08-10。之后以下项已合并 main（对应行内联标 ✅）：
> - 主页跟手 Pager + 4 页常驻 + 悬浮 Tab 双渲染 bug → ✅（`e8582301`）— 关闭主-1/主-2/主-5
> - 相册 NL 搜索 + TAG 扫描 → ✅（`bb1839de` / `b78d7081`）— 关闭相-1/相-2
> - 相册大图页图像理解(VLM) / 提取文字(OCR) → ✅（`51f85cde`）— 关闭相-7
> - 图片编辑器 lite（CROP/ADJUST/FILTER/MARKUP）→ ✅（`dc021070`）— 相-8「编辑」部分
> - 聊天多会话侧栏 + 「新建会话」丢历史 bug → ✅（`54799952`）— 关闭聊-3/聊-6；流式文本经 `onText` 逐字吐已 live（聊-1 部分）
>
> **仍未解决**：相-3/4 分组模式 · 相-5 长按编辑触感 · 相-6 视频播放 · 相-8 证件照 · 聊-1 节奏器精细化 · 聊-4/5 富消息类型 · §3 录像/十字星接脸/MAKEUP/风格滤镜 · §4 账号/AI记忆/ASR。**🔴 人脸聚类质量阻塞**（MNN3.5 Apple bug）见 [`IOS_TASK_STATUS.md`](../../01-PRODUCT/IOS_TASK_STATUS.md) §6.1。

---

## §0 跨屏总排序（按体感影响，含可执行性）

| # | 差异 | 屏 | 体感 | 代价 | 备注 |
|---|---|---|---|---|---|
| **1** | **快门三件套错位**（尺寸 62≠76pt / 闪屏白≠黑 / 0.9·250ms≠0.6·80ms / 无 haptic·音效） | 相机 | 高 | **极低** | `DesignTokens.swift` 已定义正确值却是**死代码**——启用 token 即修一半。**最高 ROI** |
| **2** | **跟手 Pager + 4 页常驻**（iOS 松手跳变 + 页面重建 vs Android 跟手物理吸附全常驻） | 主页 | 高 | 中 | 签名交互，每次横滑+进出相机/聊天都察觉 |
| **3** | **相机右列 4/6 按钮 no-op**（比例/网格/场景/ProMode 点了没反应） | 相机 | 高 | 中 | 系统性踩空；先做"面板+最小功能"消除死按钮 |
| **4** | **相册智能功能整块未接通**（搜索/扫描/VLM/OCR/编辑/证件照/4 分组模式 全灰置或「敬请期待」） | 相册 | 高 | **高** | 智能相册核心差异化；属 Phase 6 大功能建，非纯对齐 |
| **5** | **大图查看器三缺**（长按进编辑+触感 / 视频播放 / 拖拽多选） | 相册 | 高 | 中 | 高频操作缺失 |
| **6** | **相机模式选择器纯装饰**（VIDEO/DOCUMENT 选中只改样式，快门恒走静态拍照；录像全缺） | 相机 | 高 | 中高 | 欺骗性 UI；VIDEO 接录像是较大工程 |
| **7** | **设置账号子系统全缺**（登录/quota/登出/删除账号/清除访客） | 设置 | 高 | 中 | 影响配额与账户流 |
| **8** | **人脸十字星触发语义错位**（iOS 联动点击对焦 vs Android 联动人脸检测追踪） | 相机 | 中 | 中 | 名不副实；需接人脸检测点驱动 |
| **9** | **MAKEUP tab 空壳 + 5 风格滤镜占位** | 相机 | 中 | 中 | 唇彩/腮红/TOON 等 Phase 6 |
| **10** | **AI 记忆页非功能骨架**（列表永不填充、CRUD 空闭包） | 设置 | 中 | 中 | 接 SharedKit MemoryManager/GRDB |
| 11 | 相册 PhotoInfo 字段断层（A ~12 类 vs I 5 个基础字段） | 相册 | 中 | 中 | 智能相册信息密度 |
| 12 | **聊天流式节奏器缺失**（一次性甩全文 vs Android 50ms/字逐字吐） | 聊天 | 高 | 中 | 聊天最大体感差；复刻 StreamingPacingController |
| 13 | **聊天两个 bug**：`default:break` 丢弃 text_reply/success/error + 「新建会话」=「清空」**销毁历史** | 聊天 | 高 | **极低** | 补 handleUiAction 分支 + 改 newSession 不销毁 |
| 14 | 聊天多会话侧边栏 + 9/11 消息类型 + Markdown/表格/代码渲染 | 聊天 | 高 | 高 | 大功能建，需先重构 ChatMessage 模型 |
| 15 | i18n key 覆盖 ~~239~~ 417/981（大量回退英文，双端字面不一致） | 全局 | 中 | 中（机械） | 持续补 |
| 16 | 相册页悬浮 Tab 双重渲染（iOS 内部缺陷，材质/阴影加倍） | 主页 | 低 | 极低 | 删冗余 overlay |
| 17 | 美颜角标绿≠accent / 面板高 38%≠35% / 滤镜面板 53%≠50% | 相机 | 低 | 极低 | token 已有正确值 |
| 18 | 相机左列 返回/Reset no-op | 相机 | 中（返回断链） | 低 | 接线 dismiss/重置 |

> **策略**：~~相机是下一步重点~~ ✅ **相机高 ROI 项 #1（快门 token+黑闪+反馈）/ #3（右列 4 面板）已于 2026-08-10 对齐合并 main**（`f050d6ea`/`262bf406`/`0267b62f`/`19ae5942`/`04b912fa`/`e965445e`）。剩 #8（十字星接人脸）→ #9（makeup/滤镜）→ #6（录像，大工程留后）属 **G5 功能深化**（见 `IOS_TASK_STATUS.md` §6.6）。**聊天的两个 bug（#13）代价极低、有数据丢失风险，应速修**。相册/设置的「整块功能缺失」(#4/#5/#7) 与聊天大功能(#14) 属 Phase 6 功能建，单列计划，不混入纯对齐批次。

---

## §1 主页（4 页 Pager + 悬浮 Tab）

骨架（页顺序/初始页=相册/4 Tab 语义/设置入口）**已对齐**。差异集中在横滑体感与状态保留。

> ✅ **2026-08-12 解决**：主-1 跟手 Pager + 主-2 4 页常驻 + 主-5 悬浮 Tab 双渲染 bug 全部修复（`e8582301`，`TabView(.page)` 替换 ZStack 条件渲染）。剩主-3 局部手势禁用 / 主-4 人物页多显 Tab / 主-6 打标 Tab 占位 / 主-7 返回语义。

| # | 层 | 差异 | 证据 | 体感 |
|---|---|---|---|---|
| 主-1 | 🔴功能/触发 | **横滑**：Android `HorizontalPager` 跟手+物理吸附；iOS `DragGesture.onEnded` 松手跳变，拖拽期无反馈 | A:`MainPagerHost.kt:85-89` / I:`MainTabView.swift:78-88` | 高 |
| 主-2 | 🔴功能 | **页面常驻**：A `beyondViewportPageCount=3` 全常驻；I `if currentPage==X` 条件渲染，相机/聊天/人物离开即销毁（相机 session/聊天草稿重建） | A:`MainPagerHost.kt:87` / I:`MainTabView.swift:22-51` | 高 |
| 主-3 | 🔴触发 | 局部禁用外层横滑：A 相册详情/聊天全屏预览上报禁用；I 全局手势无禁用（阈值有缓解，内层横滑偶发误触切页） | A:`GalleryScreen.kt:250` / I:`MainTabView.swift:78-88` | 中 |
| 主-4 | 信息层级 | 悬浮 Tab 显隐：A 仅相册页；I 相册页**和人物页**都显 | A:`GalleryScreen.kt:840` / I:`MainTabView.swift:67-75` | 中 |
| 主-5 | 布局 | **相册页 Tab 双重渲染**（iOS bug）：两处 overlay 各画一个 FloatingBottomTab（材质/阴影加倍） | I:`MainTabView.swift:24-31` + `:67-75` | 低（极低代价修） |
| 主-6 | 功能 | 打标 Tab：A 进真实打标管理页；I 弹占位「Coming Soon」 | A:`GalleryScreen.kt:860` / I:`FloatingBottomTab.swift:13` | 中 |
| 主-7 | 功能 | 返回语义：A 系统回返回任何状态回相册；I 无系统 Back，相机页返回仅靠手势（发现性弱） | A:`MainPagerHost.kt:75` / I:`MainTabView.swift` | 中 |

**体感总评**：核心结构对齐，**横滑是最大短板**（跟手+常驻 vs 松手跳变+重建），叠加 Tab 双渲染/人物页多显等细节。

---

## §2 相册（网格 + 大图查看器）

网格骨架（3 列等比方块/人脸感知对齐/长按进选择/缩略图勾选/双指缩放/单击切栏/冷启动格言 366 条同源）**已对齐**。差异主要是「智能功能未接通」+「高频交互缺失」。

> ✅ **2026-08-12 解决**：相-1 NL 搜索（`bb1839de`，`MediaSearchEngine` 全链路 live）/ 相-2 TAG 扫描（`b78d7081`，3-Pass 全通）/ 相-7 大图页 VLM 图像理解 + OCR 提取文字（`51f85cde`）/ 相-8 大图底栏「编辑」入口（`dc021070` 编辑器 lite）。**仍缺**：相-3/4 分组模式 · 相-5 长按编辑触感 · 相-6 视频播放 · 相-8 证件照 · 相-9 PhotoInfo 字段断层。

| # | 子区 | 差异 | 证据 | 体感 |
|---|---|---|---|---|
| 相-1 | 🔴功能 | **搜索**：A 全文检索 live；I 点弹「敬请期待」 | A:`GalleryScreen.kt:566` / I:`GalleryGridView.swift:116` | 高 |
| 相-2 | 🔴功能 | **TAG 扫描**：A toggle live+进度条；I 弹「敬请期待」 | A:`GalleryTopBar.kt:102` / I:`GalleryGridView.swift:112` | 高 |
| 相-3 | 🔴功能 | **4 分组模式**（FACE/PERSON/LANDSCAPE/LOCATION）：A 全启用；I 灰置禁用 | A:`GalleryTopBar.kt:153` / I:`GalleryGridView.swift:148` | 高 |
| 相-4 | 🔴功能 | **拖拽批量选择**：A 有；I 仅逐格点击 | A:`MediaGrid.kt:131` / I:`GalleryGridView.swift:243` | 高 |
| 相-5 | 🔴功能 | **大图长按→编辑器+触感**：A 有；I 仅 onTap | A:`MediaPager.kt:239` / I:`MediaPagerView.swift:273` | 高 |
| 相-6 | 🔴功能 | **视频播放**：A ExoPlayer 内联；I 仅静态图 | A:`MediaPager.kt:1561` / I:`MediaPagerView.swift:292` | 高 |
| 相-7 | 🔴功能 | 大图更多菜单：**图像理解(VLM)/OCR** A live；I 灰置 | A:`MediaPager.kt:926,944` / I:`MediaPagerView.swift:109` | 高 |
| 相-8 | 🔴功能 | 大图底栏：**编辑/证件照** A live；I 灰置 | A:`MediaPager.kt:1033` / I:`MediaPagerView.swift:158` | 高 |
| 相-9 | 信息层级 | **PhotoInfo 字段断层**：A ~12 类（人脸/标签/OCR/美学/重新打标/位置跳地图/预览）；I 仅 5 基础字段 | A:`MediaPager.kt:1104` / I:`MediaPagerView.swift:371` | 中 |
| 相-10 | 功能 | 删除确认：A 仅系统确认；I app 层 confirmationDialog + 系统 = 双重 | A:`GalleryScreen.kt:622` / I:`GalleryGridView.swift:338` | 中 |
| 相-11 | 布局 | 列数：A `Adaptive(110.dp)`（宽屏多列）；I 固定 3 列 | A:`MediaGrid.kt:158` / I:`GalleryGridView.swift:35` | 中 |
| 相-12 | 触发 | 分组头粘性：I `pinnedViews` 吸顶；A 不吸顶 | A:`MediaGrid.kt:166` / I:`GalleryGridView.swift:224` | 中 |
| 相-13 | 功能 | 缩略图预加载：A ±3 页 LRU+磁盘；I 无 | A:`MediaGrid.kt:98` / I:`ThumbnailView.swift:30` | 中 |
| 相-14 | 文案 | 空相册：A 格言占位；I 纯文本 "No media found" | A:`GalleryScreen.kt:766` / I:`GalleryGridView.swift:185` | 中 |

**体感总评**：iOS 相册是 Android 的「骨架级子集」。3 类系统性缺口：①智能功能未接通（搜索/扫描/VLM/OCR/编辑/证件照/分组）②高频交互缺失（拖拽选/长按编辑/视频）③信息密度断层（PhotoInfo）。前 2 类决定「基础可用性」，第 3 类是「智能差异化」。

---

## §3 相机（重点屏，下一步对齐对象）

骨架（顶/底/快门/美颜面板布局）**高度对齐**，但功能可用率约 40%。**~~最关键发现：`DesignTokens.swift` 已定义正确 token 却全是死代码~~** ✅ **死代码已启用**（2026-08-10 相机对齐 B1 合并 main，`f050d6ea`：快门 76/58pt、闪屏 0.6/80ms 等 token 已生效）。§3.1 快门/§3.2 右列面板的 gap 已关；§3.3-3.5 录像/十字星/makeup 仍属 G5。

### 3.1 快门（最该先治，代价极低）

| # | 差异 | 证据 | 体感 |
|---|---|---|---|
| 机-1 | 外径 **62pt≠76**（token `ShutterTokens.diameter=76` 声明未用） | I:`ShutterButton.swift:17` / 死token:`DesignTokens.swift:251` | 高 |
| 机-2 | 内径 52pt≠58（token 未用） | I:`ShutterButton.swift:22` | 高 |
| 机-3 | 闪屏**白色≠黑色**（Android 黑闪，相机惯例） | A:`CameraScreen.kt:1517` / I:`CameraPreviewView.swift:104` | 高 |
| 机-4 | 闪屏 **0.9/250ms≠0.6/80ms**（token `flashAlpha/flashFadeMs` 未用） | I:`CameraPreviewView.swift:105,108` | 中 |
| 机-5 | haptic：A `LONG_PRESS` 有；I 无 | A:`CameraScreen.kt:1650` | 高 |
| 机-6 | 拍照音：A `CLICK` 有；I 无 | A:`CameraScreen.kt:1654` | 中 |
| 机-7 | 按压缩放：I 有 scaleEffect；A 无（**反向 gap，建议 A 补**） | I:`ShutterButton.swift:23` | 中 |

### 3.2 右列按钮（4/6 no-op，系统性踩空）

| # | 按钮 | 差异 | 证据 |
|---|---|---|---|
| 机-8 | 比例 | A 开 RatioSelector 真 panel（4:3/16:9/FULL 切 ScaleType）；I `{ }` no-op | I:`CameraPreviewView.swift:322` |
| 机-9 | 网格 | A 开 GridSelector + 实绘虚线叠加；I no-op 无叠加 | I:`CameraPreviewView.swift:323` |
| 机-10 | 场景 | A NONE/NIGHT/MOON（NIGHT→EV+1, MOON→EV-2+3.2x）；I no-op | I:`CameraPreviewView.swift:324` |
| 机-11 | ProMode | A 半屏 EV/WB/对比度/饱和度/色温；I no-op 无类型 | I:`CameraPreviewView.swift:328` |

### 3.3 模式选择器 / 录像

| # | 差异 | 证据 | 体感 |
|---|---|---|---|
| 机-12 | 模式选择器纯装饰：VIDEO/DOCUMENT 选中只改样式，快门恒走静态拍照 | I:`CameraPreviewView.swift:409` | 高 |
| 机-13 | 录像全缺：无 AVCaptureMovieFileOutput、无录像态（token 声明未用）、无计时 | I: 全缺 | 高 |

### 3.4 人脸十字星（语义错位）

| # | 差异 | 证据 | 体感 |
|---|---|---|---|
| 机-14 | **触发源不同**：A 联动人脸检测跟脸移动；I 联动点击对焦跟手指点 | A:`CameraScreen.kt:1418` / I:`CameraGesturesView.swift:69` | 高 |
| 机-15 | 显隐时序：A 多态（220/160/320/420ms）；I 单一 1.5s hold（因不联脸） | I:`CameraGesturesView.swift:70` | 中 |
| 机-16 | 细节：L 角线宽 2pt≠3、十字 alpha 0.6≠0.8 线宽 1.5≠2 | I:`CameraGesturesView.swift:87,123` | 低 |

### 3.5 美颜 / 滤镜 / 其他

| # | 差异 | 证据 | 体感 |
|---|---|---|---|
| 机-17 | **MAKEUP tab 空壳**：唇彩/腮红全缺（占位 "Phase 6"） | I:`BeautyPanelView.swift:153` | 中 |
| 机-18 | **5 风格滤镜占位**（TOON/SKETCH/POSTERIZE/EMBOSS/CROSSHATCH lock） | I:`FilterSelectorView.swift:42` | 中 |
| 机-19 | 美颜角标**绿≠accent**；面板高 38%≠35%；滤镜面板 53%≠50% | I:`CameraPreviewView.swift:319`,`BeautyPanelView.swift:45` | 低 |
| 机-20 | 左列**返回/Reset no-op**（返回键点了没反应，导航断链） | I:`CameraPreviewView.swift:301,307` | 中 |
| 机-21 | 变焦条 4 档硬编码常显（A 按设备能力条件显隐 0.6x/3.2x） | I:`CameraPreviewView.swift:347` | 中 |
| 机-22 | 语音 FAB / AI Chat FAB：A 有（默认隐藏/常显）；I 全缺 | A:`CameraPreviewContent.kt:561` | 中 |

**已对齐项（亮点）**：美颜 FACE 4 项（范围/默认全 0）、色调 9 款 ColorMatrix（矩阵逐字节同源）、变焦条样式、缩略图、翻转、美颜/滤镜面板骨架、十字星视觉参数。

**相机节最该先治（输入下一步计划）**：①快门 token 启用+闪屏黑+反馈（极低代价）→ ②右列 4 面板最小功能 → ③十字星接人脸 → ④MAKEUP/风格滤镜 → ⑤录像（大工程）。

---

## §4 设置（主页 + 二级页）

主页框架（10 项网格 + 主题/语言快选 + Hero 卡布局 + 数据隐私 7 段 + 模型中心 16 模型）**高度一致**。差异在二级页实质功能完成度。

| # | 子区 | 差异 | 证据 | 体感 |
|---|---|---|---|---|
| 设-1 | 账号 | **账号/认证全缺**（登录/quota/登出/删除/清除访客） | I:`SettingsScreen.swift:239` 占位 | 高 |
| 设-2 | AI记忆 | **非功能骨架**（列表永不填充、CRUD 空闭包） | I:`SettingsSubPages.swift:246` | 高 |
| 设-3 | 语音控制 | 完全缺失（模式+ASR/KWS 模型） | I:`SettingsSubPages.swift:49` "Not Available" | 高 |
| 设-4 | 开发者 | 缺 Shader 调试/LLM 日志/测试工具/日志管理 | I:`SettingsSubPages.swift:294` | 高 |
| 设-5 | 相机美颜设置 | **信息层级根本不同**：A=模型/Stage 配置向；I=美颜诊断/调参向（需产品决策定 SSOT） | A:`SettingsScreen.kt:546` / I:`SettingsSubPages.swift:340` | 高 |
| 设-6 | Hero 卡 | 无登录态反映（永远 "Account" 静态） | I:`SettingsScreen.swift:39` | 中 |
| 设-7 | 远程模型 | 缺编辑能力（只能删重建） | I:`SettingsSubPages.swift:123` | 中 |
| 设-8 | 通信通道 | 无连接状态显示；飞书/TG section 条件显示 vs A 双常驻 | I:`SettingsSubPages.swift:170` | 中 |
| 设-9 | 模型中心 | 缺 RecommendedHeaderCard（WiFi 预下载开关）；其余严格 1:1 | I:`ModelDownloadCenterView.swift:90` | 中 |
| 设-10 | 主页占位 | Gallery 功能 / Backup 两项 "Coming Soon" | I:`SettingsScreen.swift:210,216` | 中 |
| 设-11 | 死代码 | ModelCenterView/AboutView 已实现但不可达（NavigationLink 永不触发） | I:`SettingsScreen.swift:174` | 低 |
| 设-12 | i18n | Android `SettingsRemoteModels.kt` 3 处硬编码中文（违反 i18N 硬规则） | A:`SettingsRemoteModels.kt:43,50,73` | 中 |

**体感总评**：主页框架是双端对齐标杆；二级页两梯队——「完全空白」（账号/AI记忆）与「部分实现关键缺失」（语音/开发者/相机美颜设置分叉）。**模型中心（除 Recommended 卡）是 1:1 对齐的参照基准**。

---

## §5 聊天

骨架阶段，约 Android 基准端 ~20% 功能面。输入卡 24dp 圆角、thinking 三点（6dp/400ms/160ms stagger）、BlinkCursor（">" 500ms）、空态布局、示例 chip 点击**已对齐**。

> ✅ **2026-08-12 解决**：聊-3「新建会话」丢历史 bug（`newSession()` 改为建新 thread + `switchSession`，不再调 `clearHistory`）/ 聊-6 多会话侧栏（`54799952`，`ChatThreadSidebarView`）。聊-2 流式文本经 `onText` 逐字吐已 live（`handleUiAction` 的 `default:break` 仅分发 UI 动作 kind，不丢流式文本）；`success`/`error` 工具确认反馈仍待补（轻微）。**仍缺**：聊-1 节奏器精细化 · 聊-4/5 富消息类型（图片/图表/表格/代码，需重构 `ChatMessage`）· 聊-7/8 媒体反馈与附件。

| # | 子区 | 差异 | 证据 | 体感 |
|---|---|---|---|---|
| 聊-1 | 🔴功能 | **流式节奏器缺失**：A `StreamingPacingController`（50ms/字+标点+100+换行+200+CJK 分块）；I `onText` 直写 snapshot，**一次性甩全文** | A:`StreamingPacingController.kt` / I:`ChatViewModel.swift:52` | 高 |
| 聊-2 | 🔴功能 | **`default:break` 丢弃** text_reply/success/error（DTO 4 kind 仅 media_results 消费，工具执行离散动作永不可见） | I:`ChatViewModel.swift:137` | 高（bug，极低代价） |
| 聊-3 | 🔴功能 | **「新建会话」=「清空」**：I 调 `clearHistory()` 销毁当前会话；A `newSession()` 保留历史 | A:`ChatScreen.kt:839` / I:`ChatView.swift:93` | 高（数据丢失，极低代价） |
| 聊-4 | 🔴信息层级 | **9/11 消息类型缺**（图片/图表/表格/代码/优化候选/编辑结果）；I `ChatMessage` 仅 role，须先重构模型 | A:`ChatScreen.kt:2378` / I:`ChatMessage.swift:8` | 高 |
| 聊-5 | 🔴文案 | Agent 文本无 **Markdown/表格/代码块**渲染（纯 Text） | A:`ChatScreen.kt:1042` / I:`ChatView.swift:249` | 高 |
| 聊-6 | 🔴功能 | **多会话侧边栏全缺**（280dp 抽屉/列表/切换/重命名/删除/自动标题）；Menu 弹「coming soon」 | A:`ChatThreadSidebar.kt` / I:`ChatView.swift:75` | 高 |
| 聊-7 | 🔴功能 | 媒体反馈 👍👎🔄 缺；图片全屏预览/相册结果预览缺；写操作确认弹窗缺 | A:`MediaResultsCarousel.kt:153` / I:`ChatView.swift:355` | 高 |
| 聊-8 | 🔴功能 | 模型胶囊 / 相册选图 / 图片意图芯片 / 待发缩略图 缺 | A:`ChatScreen.kt:1928,1966` / I:`ChatView.swift:164` | 高 |
| 聊-9 | 功能 | 图表/表格全屏预览、JS 沙盒（找相似/画图）、排除约束、访客注册缺 | A:`ChatScreen.kt:657,682` | 中 |
| 聊-10 | 文案 | 媒体标题/日期/查看全部缺；示例文案硬编码英文（违反 i18n）；流式光标位置错位（下方 vs 内联右侧） | I:`ChatView.swift:417,259` | 中 |

**体感总评**：~20% 功能面。**最危险 = 聊-2/聊-3 两个 bug**（结果不可见 + 误操作丢历史，代价极低应速修）；**最大体感差 = 聊-1 流式节奏器**（一次性甩文失去逐字体验）。聊-4~8 为大功能建，需先重构 `ChatMessage` 模型承接 11 种类型。

---

## §6 方法论与下一步

- **契约 SSOT**：`specs/screens/*.yaml`（camera/gallery-grid/chat/settings/model-download-center）。对齐 = iOS 实现 → yaml 契约（yaml 镜像 Android）。
- **真机验证**：`./scripts/ios-auto-dev-loop.sh --quick --screenshot <name>`（baseline 已采，iPhone 15）。相机视觉类改动用 before/after 截图 + syslog 崩溃检查。
- **下一步**：~~相机页对齐（T7b）~~ ✅ **已完成并合并 main（2026-08-10）**——快门 token 启用+黑闪+反馈（`f050d6ea`）+ 右列 4 面板（比例/网格/场景/ProMode + 面板互斥状态机，`262bf406`/`0267b62f`/`19ae5942`/`04b912fa`/`e965445e`）。§3 剩余 #8 十字星接人脸 / #9 makeup·风格滤镜 / #6 录像属 **G5 功能深化**（见 [`IOS_TASK_STATUS.md`](../../01-PRODUCT/IOS_TASK_STATUS.md) §6.6 / [`plans/2026-08-10-ios-implementation-tasks.md`](../superpowers/plans/2026-08-10-ios-implementation-tasks.md) T9）。
- **不混入本批**：相册/设置的「整块功能缺失」（搜索/编辑/账号/录像等 Phase 6 大功能）单列计划，不在纯对齐批次内。

---

## §7 不算差异（🟢 平台原生 / 已对齐）

字体（Roboto vs SF）、图标族（Material vs SF Symbols）、材质（涟漪 vs 高亮、毛玻璃）、动效曲线、系统返回/权限流形态——均允许平台原生差异。各屏已对齐的骨架项见各节「已对齐」说明。
