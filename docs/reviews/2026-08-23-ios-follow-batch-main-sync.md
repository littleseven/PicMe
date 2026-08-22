# 审查报告 — ios-follow-batch-20260822（main 7b4817764 → 76fc3cf07）

> 审查员：reviewer（fable 档，与实现代理交叉）；日期 2026-08-23。
> 审查对象：`git diff main...HEAD`（77adf909c 契约/spec + 76fc3cf07 实现），基准 specs（person.yaml / settings.yaml §3c §3d / chat.yaml §4 §4.1 / tag-control.yaml）+ tmp 契约。
> 每条 🔴/🟡 均经对抗验证（触发路径或双端对照证据）。
> **处置状态**：R1 已修复；Y2/Y4/Y5/Y6/Y7/Y8/Y9 已修复；Y1/Y3 已登记 tag-control.yaml platform_differences；Y10 登记技术债。

## 🔴 Must-fix

**R1. TAG Scan 动作卡进度轨道恒满格（进度语义丢失 + 误导）** — ✅ 已修复
- 位置：`TagScanComponents.swift` ScanActionCard。
- Android 对照（`TagGenerationControlScreen.kt:944-958`）：surfaceVariant 轨道 + 渐变 `fillMaxWidth(fraction)`，`fraction=(totalMedia-pendingCount)/totalMedia`。
- 失败场景：全新安装 500 张待处理 → 轨道显示 100% 渐变满格，用户以为索引已完成，实际 0%。
- 修复：surfaceVariant 底 + GeometryReader 按比例渐变填充，fraction 与 Android 同式；spec `tag-control.yaml` progress_track 措辞同原子修正。

## 🟡 Spec 偏差与债（按影响排序）

**Y1. Faces 阶段动作映射为全链而非 Pass1-only** — 📋 spec 显式登记（tag-control.yaml platform_differences）
- iOS `run(_:full:)`：faces→`startFull()/startIncremental()`（Pass1→2→3 全链）；Android stage FACE→`intentScanPass1(Full)` 仅 Pass1。
- 根因：iOS orchestrator 无 Pass1-only 入口（v1 存量限制带入 v2）。补 `startPass1(mode:)` 入口后收敛。

**Y2. ScanActionCard 文案丢参数 + 标题式样** — ✅ 已修复（caption 参数化：图库/待处理、已扫描/失败；标题「Scan status」+chip 同行；upToDate 补 totalMedia>0 空库条件）

**Y3. Regenerate 的 categories/timeRange 纯装饰** — 📋 spec 登记（iOS 执行链无 intentRegenerateCategories 参数；当前仅 overwrite 分流 Pass3 增量/全量；类别选择为 UI 态、按钮禁用逻辑与其对齐）

**Y4. chat 空状态「可滚动」实际失效** — ✅ 已修复（固定 frame 改 minHeight，ScrollView 恢复滚动余量）

**Y5. custom 形态 customModelExpanded 初始 false** — ✅ 已修复（init 按 provider==nil 置 true）

**Y6. 「Rescan all」额外破坏性确认（Android 无）** — ✅ 已修复（直发全量；Stages·full 与 Regenerate·overwrite 保留确认）

**Y7. 全量确认文案与 Android 不一致且三处复用** — ✅ 已修复（统一 Android 原文「该阶段将重做全部照片，可能需要较长时间。」）

**Y8. i18n 值错位（person 域）** — ✅ 已修复（Family zh-Hans 亲属→家庭；Add name zh 点击命名→添加名字/加入名字；「照片计数用短版」一项经复核为误读——顶栏/详情均用全称 photo(s) 键，仅角标用短版，符合 spec）

**Y9. TAG 页绕过 L() + 49 个死 scan_* 键未清** — ✅ 半修复（已切全局 L()，应用内语言切换生效；死键清理留后续批次，无害）

**Y10. 测试覆盖缩减** — 📋 技术债（TagScanUITest 删 Pass2/Pass3 执行路径冒烟无等价替代；ChatViewModel 阈值逻辑（==20/清零/403）无单测——建议下批补 ViewModel 层单测）

## ✅ 核对通过项（对抗验证后确认）

1. **guest 状态机全量**（vs chat.yaml §4.1）：恰 ==20 当次插播+弹层（>20 不再弹）；banner `isGuestMode && count>=20 && !dismissedThisSession`、会话内关、重启复现；403 识别 `isGuestMode && localizedCaseInsensitiveContains("quota_exceeded")` 与 Android :1388 同构；注册清零双入口（Settings verify 成功挂 `resetGuestMessageCount()`；chat sheet verify 写 token 后 `registrationSuccess()`，顺序正确）；`server_auth_token` 键同源；isGuestMode 省略 model 维度（iOS chat 恒远程）等价成立；发送前 guard 使 demo 命令/EDIT 跳转不计数。
2. **XCFramework/KMP 互操作**：worktree 重建含 apiKeyUrl（header 实证）；`RemoteProtocol.claude/.openai` 命名对照生成 header；`RemoteModelProvider` 7 参 init 匹配；`PROVIDERS as? [RemoteModelProvider]` 为既有成熟模式；custom `providerId:"custom"` uniqueKey upsert 对齐。
3. **provider-pages spec 值**：徽章 28/r8/字母规则、品牌 6 色、ConfiguredGreen #0B9E4A@12%、分隔线 inset 56、行高 64、canSubmit 三条件、控制台链接 apiKeyUrl 非空才显且用 shared SSOT、48/r12/disabled 35% 全对 settings.yaml §3c/§3d。两级 pop 可靠。
4. **person-reorder**：familyPredicates 18 项与 shared SSOT 逐项精确一致；CountBadge/RelationChip 四色对/AddRelationChip/未命名 primary 15sp/占位 face 图标/两行顶栏/五段式详情数值逐项吻合 person.yaml；保存语义不变；rescore 缺席为 spec 允许。Photos 计数近似性基本推翻（coverCandidates=簇内全集 GROUP BY，与列表 photoCount 同源）。
5. **chat-empty-v3 空态数值**：v3 全数值对定稿；chat_logo 资产存在；模型胶囊删除无双端断言残留；AddModelSheet 零 caller 且 Get API Key 已顺手改 shared apiKeyUrl。
6. **FlowLayout 向后兼容**：5 处既有调用走 init(spacing:) 行为等价；新 3 参 init 仅 chat 空态使用。
7. **download-integrity**：单流终检与并行路径/Android 同构；T1–T6 与 verifyExistingFile 语义逐条自洽（含 0 字节守卫、info=nil 跳过、空 sha 仅验 size）；日志 tag 合规。
8. **i18n 完整性**：新键三语全非空；20+ 值与 Android 逐字一致（threshold/banner/nudge/provider 协议句等）。

## 抽查记录（关键路径）

- shared header 三处比对确认 worktree 重建含 apiKeyUrl；**main checkout 的 debug framework 为旧版（无 apiKeyUrl）——主仓后续 iOS 构建需重跑 build-shared-kit**。
- UITest 新锚点（语义索引已覆盖/仅处理新增/场景）均在 xcstrings 落值可命中。
- StageActionSheet 状态机：点 new 即执行、full 先确认、Cancel 后不误执行；终态+失败时 cancel 已被守卫。
- Android `startScanWithGuard`（:179-188）实证仅后台条件 guard，佐证 Y6 修复方向。

## 结论

🔴 1/1 已清零；🟡 10 条中 7 修复、3 条登记（Y1/Y3 spec 显式差异 + Y10 技术债）。**整体判 PASS（有条件：真机终验待环境）**。
