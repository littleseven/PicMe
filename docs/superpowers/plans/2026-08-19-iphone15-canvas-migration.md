# 画布迁移 iPhone 15（393×852）— 实施计划

**Goal:** 全部设备帧 400×890 → 393×852（预览与 iOS 主流机型一致），画布+spec+token 原子同轮。
**已签核:** 完整尺寸（含纵向 -38 底栈重锚）· 含 Camera 页 · 原子更新。
**代码零影响**（双端用真机尺寸+dp）。**IconSet 页不动**（库表非设备帧）。

## 换算规则（全局 SSOT）

- 宽 -7：右缘锚定 x −7（顶栏右组 242/272/302/332/362 → 235/265/295/325/355；右缘 384→377？否——**右缘余量保 16**：最右 icon 右边 = 393−16 = 377 → x=355 ✓ 上组成立）；400 宽子节点→393；居中 x 重算（浮动 tab (400−300)/2=45 → (393−300)/2=46.5）
- 高 -38：底部锚定 y −38（editor rail 724→686、home 866→828、panel_slot 560..700→522..662）；890 高帧→852；hug 帧只改宽
- 语义级重排（非平移）：editor 工具轨 7 项 x=16..352 间距 56 在 393 下溢出（352+44=396>393）→ 等距重排（16..334 间距 53，或边距 12），逐帧记录取值
- 每帧收口：capture_layout problemsOnly = 干净

## 任务

- **M1 Gallery**（9 帧；顶栏几何母本）：帧 resize → 400 宽子节点/右锚/居中修 → problemsOnly 干净 → 顶栏五钮 x 实测 = 235..355
- **M2 Settings**（8 帧，5 个 hug 只改宽；弹层 400 宽 scrim/把手居中重算）
- **M3 Chat**（3 帧；sidebar scrim 400×890→393×852、面板 x 重算）
- **M4 Editor**（6 帧；底栈 −38、工具轨等距重排、top_save x318→311、保存胶囊/旋钮组重算；记录新轨距）
- **M5 Camera**（7 帧；先探状态——并发会话重建中，若结构异常即停报告；统一面板宽=屏宽−56 重算、快门/底控 y 重锚、back/胶囊行 x 复核）
- **M6 spec+token 原子批**：topbar.yaml（设计稿几何注释全组 −7/宽 393）、editor.yaml §18（layout_slots −7/−38+新轨距）、camera.yaml（①-⑪ 相关坐标机械迁移）、chat/gallery yaml（400 提及处）、tokens（pager/overlayMaxWidth 400→393 等「画布宽」语义键）→ gen --check + push + sync --check
- **M7 refs 重导 + 终验**：全帧 PNG/structure 入库（相机渲染限制按 HEAD 保护剧本）、双门禁绿、commit

## 验收
1. 全部设备帧 393 宽（hug 帧高自适应、固定帧 852）
2. 每帧 capture_layout 无重叠/溢出/裁切
3. topbar 五钮节奏实测 = 新注释值；editor 底栈新 y 无出屏
4. spec/token/refs 与画布一致，sync --check 零漂移
