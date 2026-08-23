# Review: ios-follow optimize-gacha（AI 优化抽卡 iOS 追齐）

> 2026-08-16 · 分支 `feat/ios-optimize-gacha` · 模式 B（功能追齐）
> 实现：引擎=fable reasoner / shared=主 agent(K3) / Chat·Editor UI=GLM coder / 审查=fable reviewer（交叉，铁律 4）
> 输入：docs/08-UI-SPECS/screens/editor.yaml §17 + chat.yaml §17（Stage 2 反向提取）+ AI_OPTIMIZATION.md §11.5 数值契约

## 裁决

**PASS（有条件）**——审查发现的 2 🔴 + 6 🟡 中：2 🔴 与 3 🟡 已修复并验证；2 🟡 以 spec 注记登记（行为差异不阻塞）；1 🟡 被驳回（与 Android 实际行为不符）；2 🔵 已清理；其余 🔵 登记技术债。编译绿、shared jvmTest 136/136 + iosX64Test 绿、真机安装启动绿。

## 审查发现与处置

| 级别 | 发现 | 处置 |
|------|------|------|
| 🔴-1 | `IosChatPromptTest` 陈旧（断言 8 工具 + 禁用表含 ai_optimize），`:shared:iosX64Test` 红 | ✅ 已修（计数 9 + 移出禁用表 + 增补 ai_optimize 断言）；iosX64Test 实跑绿 |
| 🔴-2 | chat 发送链路未把暂存图标识注入 LLM 输入 → ai_optimize 主场景不可达 | ✅ 已修：`ChatViewModel.llmInput(text:stagedImageUri:)` 按 Android ChatViewModel.kt:1191 同款格式注入（标识字符串非像素，不触 [PRIVACY]） |
| 🟡-1 | 反馈落库 image_key 哈希临时路径（含 UUID）→ 按图聚合数据废 | ✅ 已修：`optimizeWithGacha(imageKey:)` 稳定键参数；chat=解析成功 uri（PHAsset id/原始路径）、editor=baseRecipe.sourceUri；auto/user/dismiss 三源全切换 |
| 🟡-2 | editor dismiss 落库 selectedIndex=previewedIndex 与 Android（-1）相反 | ✅ 已修：dismiss 恒 -1（对齐 Android/Chat 侧口径） |
| 🟡-3 | chat drawIndex 首抽 0 vs Android 1 | ✅ 已修：首抽 1（reroll 仍 +1） |
| 🟡-4 | spec 4 处与实现矛盾（capabilities 行/ChatEditStateHolder/on_error/Done 键） | ✅ spec 补注 4 处（chat.yaml §16 矩阵行改 supported + §17 confirm_ios_gap/fallback 注；editor.yaml §17.2 on_error_ios_note/compare_mode_locks Done 注） |
| 🟡-5 | iOS uri 不可解析→纯文本 vs Android（审查称退 GENERAL 继续优化） | 📝 spec fallback_chain 注记（审查对 Android 行为的描述未经证实——Android capability 对不可解析 uri 抛 Error observation；两端在此角落均不走卡条，差异仅错误形态） |
| 🟡-6 | 「无可用源图」iOS 纯文本 vs Android 单发图 | ❌ 驳回：Android `Fallback(imageUri=null)` 同样走纯文本 `insertAgentMessage`（ChatViewModel.kt:1471-1474）；iOS 行为一致。spec 已注记 |
| 🔵 | MNN.framework symlink 未被 ignore（worktree 构建产物） | ✅ iosApp/.gitignore 规则去尾斜杠（目录+symlink 双形态） |
| 🔵 | `OptimizeScene.label` 死代码 | ✅ 已删 |
| 🔵 | restoreGachaSelections 对过期组也恢复选中态 | 📝 技术债（Android 仅对 pending 组恢复；仅影响过期卡条视觉） |
| 🔵 | Documents/chat_edit_cache 无清理机制（源图导出数 MB/张） | 📝 技术债（建议 LRU/容量上限，独立 follow） |
| 🔵 | editor apply 历史入栈延迟到去抖渲染后（~200ms 内 undo 不可用） | 📝 技术债（Android 应用即入栈） |

## 编译与验收记录

- 编译：BUILD SUCCEEDED（generic/platform=iOS；3 轮修复：①K/N 对 lambda 参数位 Boolean 装箱→桥接口改双 String 回调 ②引擎顶层 `Scene` 撞 `SwiftUI.Scene`→改名 `OptimizeScene`（12 文件）③CIImage 非可选绑定）
- `:shared:jvmTest`：136/136 ✅（含 ChatToolManifestConsistencyTest 9 工具 + DescriptorTest）
- `:shared:iosX64Test`（IosChatPromptTest）：✅
- 引擎单测：42 用例/2283 断言 ✅（移植期 macOS harness 实跑；真机 PoLangTests 见下）
- 真机 PoLangTests（PoLangUnitTests scheme，`-allowProvisioningUpdates`）：**296/299 通过，42/42 Gacha 用例全绿**；3 败均为设备态相关既有测试（`testDownloadedModelShowsCheckAndDelete` 需设备有已下载模型；`testFrontCameraLeftSideBecomesRightSide`/`testFrontCameraMirrorsXCoordinate` 相机镜像——本 diff 零相机改动，非本分支引入）
- ios-auto-dev-loop：xcodegen/pod/jvmTest/编译/安装/启动 ✅；截图体检跳过（pymmobiledevice3 缺失，iOS 26 已知限制）；跨端 SSIM 未跑（Android 设备离线）

## 双端语义偏差登记（有意为之，均已注释/台账）

1. beauty 维度 iOS 不渲染（B1 缺口）→ 人像候选视觉差异仅剩调色；指纹/去重仍含 beauty 维度
2. NIMA EP：Android NNAPI / iOS CPU 默认（评分语义一致）
3. buildExplanation：Android 硬编码中文（i18n 违例）/ iOS 返 key 走 xcstrings 三语（[I18N] 红线）；Android 侧技术债登记不回头改
4. 反馈库：Room 独立库 / iOS TagDatabase sqlite3 扩表
5. 候选缩略图：Android 内存 Bitmap / iOS 落盘 Documents/chat_edit_cache（chat 共享路径）
6. 场景分析：iOS 未接人脸检测 → SELFIE/PORTRAIT/GROUP 启发不可达（阈值已移植，接 RetinaFace 后即通）
7. pending 过期：iOS 进程回收更激进 → expired 更高频（语义同源）
8. ChatEditStateHolder 多轮编辑态 iOS 缺失（确认后仅落图，多轮基准编辑不可达）

## 覆盖面（相对 Android）

- 引擎语义 1:1（审查逐值核对：方向池/模板/抖动边界/指纹量化 kotlinRoundInt/护栏 5pp·15%·step4/守卫 0.05·MIN_VALID_CARDS=2/NIMA NHWC·(x-127.5)/127.5·Σpᵢ·(i+1)/presets 字节级/滤镜别名表逐项）
- chat 入口：ai_optimize 工具（第 9）+ 触发链 + 卡条 + 状态机 + 降级链 + 过期语义 + 反馈三源
- editor 入口：gachaRun 状态机 + 对比条 + 对比模式锁 + unavailable 兜底
- i18n：29 key ×三语（14 UI 照抄 Android + 8 场景解释句 iOS 本地化升级 + 7 方向名）
