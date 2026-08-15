# Figma UI Spec + 完整性闸门 — 相机页先导 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: 用 superpowers:subagent-driven-development(推荐) 或 superpowers:executing-plans 逐任务实现。步骤用 checkbox(`- [ ]`) 跟踪。
>
> **执行位置**:本计划在隔离 worktree `.worktrees/figma-camera-spec`(分支 `feat/figma-ui-spec-camera-pilot`,from main)内执行。所有相对路径相对 worktree 根。
> **设计依据**:`docs/superpowers/specs/2026-08-14-figma-ui-spec-completeness-design.md`(已提交 `91fe12e4`)。

**Goal:** 建立「Figma 作 UI&样式 SSOT + 结构化完整性闸门」的对齐管线,并在相机页上跑通全闭环(11 状态帧 + iOS 实现经闸门核对),证明机制有效(闭环中填补的缺口数 > 0)。

**Architecture:** Figma 设计文件承载结构(帧=状态)与样式(Figma Variables);`design-tokens.json` 由 Figma 导出再生;精简 YAML 只留行为/状态机;完整性闸门逐状态核对「Figma 帧 node 树 ↔ iOS a11y 树」。真机截图作地面真值护栏。

**Tech Stack:** Figma MCP(`create_new_file`/`use_figma`/`get_design_context`/`get_variable_defs`/`get_metadata`)、Python 3(闸门匹配器 + token 转换,pytest)、SwiftUI + XCUITest(iOS a11y 树 dump)、adb + uiautomator(Android ground truth 采集)。

**关键决策(定掉设计 §11 待决项):**
1. Figma 文件:**新建**(`create_new_file`,先 `whoami` 取 planKey)。
2. token 导出:**Figma API via `use_figma` dump Variables → Python 转换器 → design-tokens.json**(无 Tokens Studio 外部依赖)。
3. 闸门:`use_figma` dump 帧 node 树 + XCUITest dump iOS a11y 树 + **Python 匹配器**(pytest TDD)。
4. 分支:已建 `feat/figma-ui-spec-camera-pilot`(本 worktree)。
5. 铺开:先导闭环成立后,另出计划铺其余屏。

---

## 文件结构(创建/修改映射)

| 文件 | 责任 | 动作 |
|---|---|---|
| `scripts/figma/seed-variables.js` | `use_figma` 脚本:读 design-tokens.json,建 Variables 集合(Light/Dark mode) | 新建 |
| `scripts/figma/dump-variables.js` | `use_figma` 脚本:遍历 Variables → 结构化 JSON 返回 | 新建 |
| `scripts/figma/dump-frame-nodes.js` | `use_figma` 脚本:遍历指定帧 → 扁平 node 列表 JSON 返回 | 新建 |
| `scripts/figma/transform_tokens.py` | Figma Variables dump → design-tokens.json 格式(纯函数,TDD) | 新建 |
| `scripts/figma/test_transform_tokens.py` | transform_tokens 的 pytest | 新建 |
| `scripts/capture-android-camera-states.sh` | adb 驱动 + 截图 + uiautomator dump 11 状态 | 新建 |
| `scripts/completeness/match.py` | Figma node 列表 ↔ iOS a11y 列表 匹配,产出缺口(纯函数,TDD) | 新建 |
| `scripts/completeness/test_match.py` | match.py 的 pytest(含 fixture) | 新建 |
| `scripts/completeness/ios_a11y_dump.swift` | XCUITest:dump 指定状态 a11y 树 → JSON(写入 App Documents) | 新建 |
| `scripts/completeness-check.sh` | 编排器:dump 双侧 → match.py → 报告/退出码 | 新建 |
| `specs/screens/refs/android/camera-<state>.{png,xml}` | Android ground truth(11 状态) | 采集产出 |
| `specs/screens/camera.yaml` | 瘦身:删结构/样式视觉描述,留行为/状态机 | 修改 |
| `iosApp/PoLang/Features/Camera/Beauty/BeautyPanelView.swift` 等 | 按 Figma 帧 + 闸门对齐面板 | 修改 |
| `shared/src/commonMain/resources/design-tokens.json` | 改为 Figma 导出再生(经 transform_tokens.py) | 修改(再生) |

**职责边界**:`scripts/figma/*` = Figma 读写脚本(JS,经 use_figma 执行);`scripts/completeness/*` = 闸门逻辑(Python 核心 + Swift dump);`scripts/*.sh` = 编排;`specs/screens/refs/*` = ground truth 产物(gitignore 还是入库?——**入库**,是规格的一部分,见下)。

> **产物入库策略**:`refs/android/*.png` 与 dump JSON 入库(它们是「地面真值」规格的一部分,且可复现)。dump JSON 体积小;截图每张 ~100KB × 11 可接受。

---

## Phase A — Figma 基础 + token 管线

### Task A1: 创建 Figma 设计文件

**Files:** 无(产出 fileKey,记入 `scripts/figma/.figma-meta.json`)

- [ ] **Step 1: 取 planKey**

调用 `mcp__figma__whoami`,从返回的 plans 取一个 `type` 可写(organization/team)的 `key`。若多个,选用户主 team。

- [ ] **Step 2: 创建文件**

调用 `mcp__figma__create_new_file`:`fileName="PoLang UI Spec"`, `editorType="design"`, `planKey=<step1>`。记下返回的 `fileKey` + `url`。

- [ ] **Step 3: 持久化 fileKey**

```bash
mkdir -p scripts/figma
cat > scripts/figma/.figma-meta.json <<'EOF'
{ "fileKey": "<填入>", "fileName": "PoLang UI Spec", "url": "<填入>" }
EOF
```

- [ ] **Step 4: 验证可访问**

调用 `mcp__figma__get_metadata`(`fileKey`,不传 nodeId)→ 应返回 page 列表(默认 Page 1)。Commit。

### Task A2: token 播种 — design-tokens.json → Figma Variables

**Files:** `scripts/figma/seed-variables.js`(新建)

- [ ] **Step 1: 写 seed 脚本**

`seed-variables.js` 是传给 `mcp__figma__use_figma` 的 `code`。逻辑:建集合 "PoLang Tokens";为 colorScheme 建 Light/Dark 两 mode;按 design-tokens.json 的 spacing/radius/icon/color/statusColor/alpha/elevation + 各组件 section 建 Variables(color 类型用 FLOAT RGBA via `figma.variables.createVariable` + `setVariableValue` + color alias;number 类型直接数值)。

```javascript
// scripts/figma/seed-variables.js — 在 use_figma sandbox 内执行
// 读 design-tokens.json 内容(作为字符串注入;由调用方拼装,见 Step 2)
const TOKENS = __TOKENS_JSON__; // 占位,调用方替换
const collection = await figma.variables.createVariableCollection('PoLang Tokens');
const modeId = collection.modes[0].modeId;
collection.renameMode(modeId, 'Light');
const darkModeId = collection.addMode('Dark');

async function numVar(name, value, group) {
  const v = figma.variables.createVariable(name, collection.id, 'FLOAT');
  v.setValueForMode(modeId, value); v.setValueForMode(darkModeId, value);
  return v;
}
// spacing / radius / icon / alpha / elevation:数值
for (const [k,v] of Object.entries(TOKENS.spacing)) await numVar('spacing/'+k, v);
// ...(radius/icon/alpha/elevation 同理,展开各 section 的 number 字段)
// color/statusColor:COLOR 变量(hex → RGBA 0..1)
function hexToRGBA(h){h=h.replace('#','');const a=parseInt(h.slice(0,2),16)/255,r=parseInt(h.slice(2,4),16)/255,g=parseInt(h.slice(4,6),16)/255,b=parseInt(h.slice(6,8),16)/255;return{r,g,b,a};}
async function colorVar(name, hex){const v=figma.variables.createVariable(name,collection.id,'COLOR');const c=hexToRGBA(hex);const p={r:c.r,g:c.g,b:c.b,a:c.a};v.setValueForMode(modeId,p);v.setValueForMode(darkModeId,p);return v;}
for (const [k,h] of Object.entries(TOKENS.color)) await colorVar('color/'+k, h);
for (const [k,h] of Object.entries(TOKENS.statusColor)) await colorVar('statusColor/'+k, h);
// colorScheme:light→Light mode,dark→Dark mode(同名变量两 mode 不同值)
for (const role of Object.keys(TOKENS.colorScheme.light)){
  const v=figma.variables.createVariable('scheme/'+role,collection.id,'COLOR');
  v.setValueForMode(modeId, hexToRGBA(TOKENS.colorScheme.light[role]));
  v.setValueForMode(darkModeId, hexToRGBA(TOKENS.colorScheme.dark[role]));
}
return { ok:true, collectionId: collection.id, modeCount: collection.modes.length };
```

> 完整脚本须覆盖所有 number section(spacing/radius/icon/alpha/elevation/topBar/shutter/beautyPanel/grid/searchField/pager/appSlider/bottomTab/bottomSheet/chip/badge/camera/chatBubble/chatCarousel/chatContext/settings/editor/modelCenter/person)与 color/statusColor/scheme。实现时逐 section 展开(模式固定,无悬念)。

- [ ] **Step 2: 执行播种**

用 Read 读 `shared/src/commonMain/resources/design-tokens.json` 内容,替换脚本里的 `__TOKENS_JSON__`,把完整 JS 作为 `code` 调 `mcp__figma__use_figma`(`fileKey`, `code`, description="seed variables from design-tokens.json")。期望返回 `{ok:true, modeCount:2}`。

- [ ] **Step 3: 验证 Variables 建好**

调 `mcp__figma__get_variable_defs`(`fileKey`, 任一节点)或 `mcp__figma__use_figma` 遍历 `figma.variables.getVariablesCollectionAsync` 计数,确认 spacing/radius/scheme 等存在。Commit seed 脚本。

### Task A3: token 导出转换器(TDD)— Figma dump → design-tokens.json

**Files:** `scripts/figma/transform_tokens.py`(新建), `scripts/figma/test_transform_tokens.py`(新建)

- [ ] **Step 1: 写失败测试**

```python
# scripts/figma/test_transform_tokens.py
import json, pytest
from transform_tokens import figma_dump_to_tokens

FIXTURE_DUMP = {
  "collection": "PoLang Tokens",
  "modes": ["Light", "Dark"],
  "variables": [
    {"name": "spacing/xs", "type": "FLOAT", "values": {"Light": 4, "Dark": 4}},
    {"name": "color/focusRing", "type": "COLOR",
     "values": {"Light": {"r":0,"g":0.898,"b":1,"a":1}, "Dark": {"r":0,"g":0.898,"b":1,"a":1}}},
    {"name": "scheme/primary", "type": "COLOR",
     "values": {"Light": {"r":0.404,"g":0.314,"b":0.643,"a":1},
                "Dark":  {"r":0.816,"g":0.737,"b":1,"a":1}}},
  ]
}

def test_spacing_number_roundtrip():
    out = figma_dump_to_tokens(FIXTURE_DUMP)
    assert out["spacing"]["xs"] == 4

def test_fixed_color_to_hex():
    out = figma_dump_to_tokens(FIXTURE_DUMP)
    # focusRing=#FF00E5FF → AARRGGBB
    assert out["color"]["focusRing"] == "#FF00E5FF"

def test_scheme_splits_light_dark():
    out = figma_dump_to_tokens(FIXTURE_DUMP)
    assert out["colorScheme"]["light"]["primary"] == "#FF6750A4"
    assert out["colorScheme"]["dark"]["primary"] == "#FFD0BCFF"
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd scripts/figma && python -m pytest test_transform_tokens.py -v`
Expected: FAIL(`ModuleNotFoundError: No module named 'transform_tokens'`)

- [ ] **Step 3: 写实现**

```python
# scripts/figma/transform_tokens.py
"""Figma Variables dump → design-tokens.json。纯函数,无 IO。"""
def _rgba_to_argb_hex(c):
    a = round(c["a"] * 255); r = round(c["r"] * 255); g = round(c["g"] * 255); b = round(c["b"] * 255)
    return f"#{a:02X}{r:02X}{g:02X}{b:02X}"

def _set_leaf(root, dotted_path, value):
    parts = dotted_path.split("/"); d = root
    for p in parts[:-1]: d = d.setdefault(p, {})
    d[parts[-1]] = value

def figma_dump_to_tokens(dump):
    out = {"colorScheme": {"light": {}, "dark": {}}}
    for v in dump["variables"]:
        name, typ, vals = v["name"], v["type"], v["values"]
        if name.startswith("scheme/"):
            role = name[len("scheme/"):]
            out["colorScheme"]["light"][role] = _rgba_to_argb_hex(vals["Light"])
            out["colorScheme"]["dark"][role] = _rgba_to_argb_hex(vals["Dark"])
        elif typ == "COLOR":
            _set_leaf(out, name, _rgba_to_argb_hex(vals["Light"]))   # 固定色 Light=Dark
        else:  # FLOAT
            _set_leaf(out, name, vals["Light"])
    return out
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd scripts/figma && python -m pytest test_transform_tokens.py -v`
Expected: 3 PASS

- [ ] **Step 5: 接入导出脚本 + Commit**

`scripts/figma/dump-variables.js`(`use_figma` code):遍历 `figma.variables.getLocalVariableCollectionsAsync()` → 取 "PoLang Tokens" → 输出 `{collection, modes, variables:[{name,type,values:{Light,Dark}}]}`。编排:调 use_figma dump → 写临时 JSON → `python transform_tokens.py` 写回 `shared/src/commonMain/resources/design-tokens.json`。比对 git diff 应为空(播种↔导出可逆)。Commit。

> 注:`DesignTokens.swift` 本计划**保持手镜像**(从再生 JSON 同步),完整 Swift codegen 列为后续改进,不扩本计划范围。

---

## Phase B — Android ground truth 采集(相机 11 状态)

### Task B1: 采集脚本

**Files:** `scripts/capture-android-camera-states.sh`(新建)

- [ ] **Step 1: 写采集脚本**

脚本接受状态列表,对每个状态:提示用户在设备上进入该状态(或自动 adb input 点击入口),`adb exec-out screencap -p > refs/android/camera-<state>.png`,`adb shell uiautomator dump /sdcard/d.xml && adb pull` → `refs/android/camera-<state>.xml`。

```bash
#!/usr/bin/env bash
# scripts/capture-android-camera-states.sh
# 用法: ./scripts/capture-android-camera-states.sh <state...>
# 示例: ./scripts/capture-android-camera-states.sh idle panel_beauty_face panel_filter
set -euo pipefail
OUT="specs/screens/refs/android"; mkdir -p "$OUT"
STATES=("$@")
[ ${#STATES[@]} -eq 0 ] && STATES=(idle panel_beauty_face panel_beauty_makeup panel_filter panel_ratio panel_scene panel_grid panel_pro capturing focusing permission_denied)

capture_one() {
  local st="$1"
  echo "==> 采集 camera/$st : 请在 Android 设备上进入该状态,回车继续..."
  read -r
  adb exec-out screencap -p > "$OUT/camera-$st.png"
  adb shell uiautomator dump /sdcard/camera-$st.xml >/dev/null
  adb pull /sdcard/camera-$st.xml "$OUT/camera-$st.xml" >/dev/null
  echo "   ✓ $OUT/camera-$st.{png,xml}"
}
for s in "${STATES[@]}"; do capture_one "$s"; done
echo "done."
```

- [ ] **Step 2: chmod + 验证连接**

Run: `chmod +x scripts/capture-android-camera-states.sh && adb devices`
Expected: 列出已连接 Android 设备(用户已确认连接)。

- [ ] **Step 3: 采集 idle + panel_beauty_face(先验可行性)**

Run: `./scripts/capture-android-camera-states.sh idle panel_beauty_face`
Expected: 生成 `refs/android/camera-idle.{png,xml}` 与 `camera-panel_beauty_face.{png,xml}`,uiautomator dump 含可读节点。

> ⚠️ `capturing`(瞬时闪屏)无法稳定截图:降级用录屏 `adb screenrecord` 抽首帧,或用 `focusing`(对焦环)替代验证瞬时态采集法。在采集时记录"未截图锚定"标注。

- [ ] **Step 4: 采集全部 11 状态 + Commit**

依次采集(瞬时态用 Step3 降级法)。确认 11 套 `{png,xml}` 齐全后:`git add specs/screens/refs/android/camera-*` + commit。

---

## Phase C — 完整性闸门工具(TDD 核心)

### Task C1: 匹配器(TDD)— Figma node 列表 ↔ iOS a11y 列表

**Files:** `scripts/completeness/match.py`(新建), `scripts/completeness/test_match.py`(新建)

- [ ] **Step 1: 写失败测试**

```python
# scripts/completeness/test_match.py
import pytest
from match import compare, NormNode

def figma(name, role="generic"): return NormNode(label=name, role=role, x=0,y=0,w=10,h=10)
def ios(label, role="generic"):   return NormNode(label=label, role=role, x=0,y=0,w=10,h=10)

def test_missing_ios_element_is_gap():
    figma_nodes = [figma("磨皮", "slider"), figma("美白", "slider")]
    ios_nodes   = [ios("磨皮", "slider")]
    rep = compare(figma_nodes, ios_nodes)
    assert rep.missing == [NormNode("美白","slider",0,0,10,10)]
    assert rep.extra == []
    assert rep.ok is False

def test_extra_ios_element_is_warning():
    rep = compare([figma("磨皮","slider")], [ios("磨皮","slider"), ios("鬼影","button")])
    assert rep.missing == []
    assert rep.extra == [NormNode("鬼影","button",0,0,10,10)]
    assert rep.ok is True  # 多出不 fail,只警告

def test_label_and_role_match():
    rep = compare([figma("磨皮","slider")], [ios("磨皮","slider")])
    assert rep.ok is True and not rep.missing
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd scripts/completeness && python -m pytest test_match.py -v`
Expected: FAIL(无 match 模块)

- [ ] **Step 3: 写实现**

```python
# scripts/completeness/match.py
"""完整性匹配:仅判'在不在'(label+role),尺寸偏差归还原度另处理。"""
from dataclasses import dataclass, field
from typing import List

@dataclass(frozen=True)
class NormNode:
    label: str
    role: str
    x: float; y: float; w: float; h: float

@dataclass
class Report:
    missing: List[NormNode] = field(default_factory=list)  # Figma 有 iOS 无 → fail
    extra:   List[NormNode] = field(default_factory=list)  # iOS 多 → 警告
    @property
    def ok(self): return len(self.missing) == 0

def _key(n): return (n.label.strip().lower(), n.role.lower())

def compare(figma_nodes: List[NormNode], ios_nodes: List[NormNode]) -> Report:
    f = {_key(n): n for n in figma_nodes if n.label.strip()}
    i = {_key(n): n for n in ios_nodes if n.label.strip()}
    missing = [n for k,n in f.items() if k not in i]
    extra   = [n for k,n in i.items() if k not in f]
    return Report(missing=missing, extra=extra)
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd scripts/completeness && python -m pytest test_match.py -v`
Expected: 3 PASS。Commit。

### Task C2: iOS a11y 树 dump(XCUITest)

**Files:** `iosApp/PoLangUITests/CompletenessDumpUITests.swift`(新建)

- [ ] **Step 1: 写 dump UI test**

把当前界面 a11y 树展平为 `{label, role, frame{...}}` 写入 App Documents(供 `devicectl copy from` 取出)。

```swift
// iosApp/PoLangUITests/CompletenessDumpUITests.swift
import XCTest

final class CompletenessDumpUITests: XCTestCase {
    /// 把 app.descendants a11y 树 dump 成 JSON 到 App Documents/<name>.json。
    /// 由 completeness-check.sh 经 devicectl 取回。
    func testDumpAccessibilityTree() throws {
        let app = XCUIApplication(); app.launch()
        // 调用方通过环境变量指定目标状态与输出名:
        let state = ProcessInfo.processInfo.environment["DUMP_STATE"] ?? "idle"
        let nodes = app.descendants(matching: .any).allElementsBoundByIndex.compactMap { el -> [String: Any]? in
            let lbl = el.label
            guard !lbl.isEmpty else { return nil }
            let f = el.frame
            return ["label": lbl, "role": roleString(el), "x": f.minX, "y": f.minY, "w": f.width, "h": f.height]
        }
        let payload: [String: Any] = ["state": state, "nodes": nodes]
        let json = try JSONSerialization.data(withJSONObject: payload, options: [.prettyPrinted])
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        try json.write(to: docs.appendingPathComponent("ios-a11y-\(state).json"))
    }
    private func roleString(_ el: XCUIElement) -> String {
        if el.elementType == .button { return "button" }
        if el.elementType == .slider { return "slider" }
        if el.elementType == .staticText { return "text" }
        return "generic"
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `cd .worktrees/figma-camera-spec && xcodebuild -workspace iosApp/PoLang.xcworkspace -scheme PoLang -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 15' build-for-testing 2>&1 | tail -5`
Expected: BUILD SUCCEEDED(若缺 UITests target,需在 project.yml 注册后 `xcodegen generate`——见项目 memory 的 xcodegen 流程)。

> ⚠️ 真机 dump:用户已连真机,用 `generic/platform=iOS` destination 跑该测试,产物经 `xcrun devicectl device copy from` 取出(memory: iOS26 screenshotr 不可用,copy from Documents 可用)。

### Task C3: Figma 帧 node dump 脚本

**Files:** `scripts/figma/dump-frame-nodes.js`(新建)

- [ ] **Step 1: 写 dump 脚本(`use_figma` code)**

输入帧名(状态 id),遍历该帧子树,只取「有意义」叶子节点(有 name/text 的 TEXT、INSTANCE、有 name 的可交互层),跳过装饰分组,输出 `[{label, role, x, y, w, h}]`(坐标已是 dp/pt 量纲,Figma 默认 pt)。

```javascript
// scripts/figma/dump-frame-nodes.js — use_figma code; __FRAME_NAME__ 由调用方替换
const TARGET = __FRAME_NAME__;
const page = figma.currentPage;
const frame = page.findOne(n => n.type === 'FRAME' && n.name === TARGET);
if (!frame) { return { error: 'frame not found: ' + TARGET }; }
const out = [];
function walk(n){
  if (n.type === 'TEXT') { const b=n.absoluteBoundingBox; out.push({label:n.characters||n.name, role:'text', x:b.x,y:b.y,w:b.width,h:b.height}); return; }
  if (n.name && /button|slider|chip|tab|shutter|toggle/i.test(n.name)) {
    const b=n.absoluteBoundingBox; out.push({label:n.name, role:inferRole(n.name), x:b.x,y:b.y,w:b.width,h:b.height});
  }
  if ('children' in n) for (const c of n.children) walk(c);
}
function inferRole(nm){ if(/slider/i.test(nm))return 'slider'; if(/button|shutter/i.test(nm))return 'button'; if(/chip|tab/i.test(nm))return 'chip'; return 'generic'; }
walk(frame);
return { state: TARGET, nodes: out };
```

### Task C4: 闸门编排器

**Files:** `scripts/completeness-check.sh`(新建)

- [ ] **Step 1: 写编排脚本**

```bash
#!/usr/bin/env bash
# scripts/completeness-check.sh <screen> [--state <id>]
# 核对 iOS a11y 树 vs Figma 帧 node 树,产出缺口,有 missing 即非零退出。
set -euo pipefail
SCREEN="${1:?usage: completeness-check.sh <screen> [--state <id>]}"; shift
STATE=""; [[ "${1:-}" == "--state" ]] && STATE="$2"
FIGMA_META="scripts/figma/.figma-meta.json"
FILEKEY=$(python3 -c "import json;print(json.load(open('$FIGMA_META'))['fileKey'])")
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
STATES=(); [ -n "$STATE" ] && STATES=("$STATE") || STATES=(idle panel_beauty_face panel_beauty_makeup panel_filter panel_ratio panel_scene panel_grid panel_pro capturing focusing permission_denied)
ANY_FAIL=0
for s in "${STATES[@]}"; do
  echo "── $SCREEN/$s"
  # 1. Figma 帧 node 树
  python3 scripts/figma/run_usefigma_dump.py frame "$FILEKEY" "$SCREEN/$s" > "$WORK/figma-$s.json" || { echo "  ⚠️ Figma 帧缺失 $SCREEN/$s(跳过,记为未建帧)"; continue; }
  # 2. iOS a11y 树(已由 XCUITest dump 到 refs/ios/ios-a11y-<s>.json)
  IOS="specs/screens/refs/ios/ios-a11y-$s.json"
  [ -f "$IOS" ] || { echo "  ⚠️ iOS dump 缺失 $IOS"; ANY_FAIL=1; continue; }
  # 3. 匹配
  python3 scripts/completeness/match.py "$WORK/figma-$s.json" "$IOS" "$s"
done
exit $ANY_FAIL
```

(`scripts/figma/run_usefigma_dump.py` 是一个薄封装:读 dump-frame-nodes.js 模板,替换 `__FRAME_NAME__`,打印说明让操作者用 use_figma 执行并粘贴回 JSON;或若环境支持,直接调 MCP。**MCP 调用是交互的**,所以编排器里 Figma dump 步骤实际由人工/Claude 触发 use_figma,产物落 `refs/figma/<screen>-<state>.json`,编排器读取它而非实时调。**修正**:编排器改为读 `specs/screens/refs/figma/<screen>-<state>.json`。)

- [ ] **Step 2: 修正编排器读取落盘的 Figma dump**

把上面 `python3 run_usefigma_dump.py ...` 行改为:
```bash
FIGMA_DUMP="specs/screens/refs/figma/$SCREEN-$s.json"
[ -f "$FIGMA_DUMP" ] || { echo "  ⚠️ Figma dump 缺失 $FIGMA_DUMP(先跑 dump-frame-nodes)"; ANY_FAIL=1; continue; }
cp "$FIGMA_DUMP" "$WORK/figma-$s.json"
```
`match.py` 改为 CLI:读两 JSON → 规范化 → compare → 打印 missing/extra + exit code。

- [ ] **Step 3: 给 match.py 加 CLI 入口 + Commit**

```python
# match.py 追加
import json, sys
def _load(p): d=json.load(open(p)); return [NormNode(n["label"],n.get("role","generic"),n["x"],n["y"],n["w"],n["h"]) for n in d.get("nodes",[])]
if __name__ == "__main__":
    figma_path, ios_path, state = sys.argv[1], sys.argv[2], sys.argv[3]
    rep = compare(_load(figma_path), _load(ios_path))
    print(f"[{state}] {'OK' if rep.ok else 'GAP'}  missing={len(rep.missing)} extra={len(rep.extra)}")
    for m in rep.missing: print(f"  🔴 缺: {m.label} ({m.role})")
    for e in rep.extra:   print(f"  🟡 多: {e.label} ({e.role})")
    sys.exit(0 if rep.ok else 1)
```
Commit 全部闸门脚本。

---

## Phase D — 建 Figma 帧(11 状态,锚真机截图)

### Task D1: 建相机页 Page + 11 帧

**Files:** Figma 文件(产物:帧 nodeId 记入 `specs/screens/refs/figma/.frame-index.json`)

- [ ] **Step 1: 建 Camera Page**

`mcp__figma__use_figma`:建 page "Camera"。

- [ ] **Step 2: 逐状态建帧(优先级 1 的 7 面板先)**

对每状态 `s`(顺序:panel_beauty_face → panel_beauty_makeup → panel_filter → panel_ratio → panel_scene → panel_grid → panel_pro → idle → capturing → focusing → permission_denied):

a) `use_figma` 建空 frame `camera/<s>`,尺寸 375×812,名=`camera/<s>`。
b) 把 `refs/android/camera-<s>.png` 作为锚图放入帧右侧(锁定,仅参照)。
c) 照锚图重建布局:**颜色/间距/圆角全绑 Variables**(`scheme/*`、`color/*`、`spacing/*`、`beautyPanel/*`、`bottomSheet/*`、`camera/*`),源码(`CameraScreen.kt` 对应段)只查具体数值,不猜布局。
d) 装饰叠层(十字星/网格线/对焦框)按状态画在帧内。
e) `mcp__figma__get_screenshot`(帧)→ 与 `refs/android/camera-<s>.png` 目检比对,偏差大则修。

- [ ] **Step 3: dump 每帧 node 树入库**

对每帧执行 `dump-frame-nodes.js` → 写 `specs/screens/refs/figma/camera-<s>.json`。

- [ ] **Step 4: 记帧索引 + Commit**

```bash
cat > specs/screens/refs/figma/.frame-index.json <<'EOF'
{ "screen":"camera", "frames": {"panel_beauty_face":"<nodeId>", "...":"..."} }
EOF
```
Commit `refs/figma/*`。

> **优先级**:Step 2 先把 7 个 `panel_*` 帧建到「与 Android 截图目检一致」(用户核心诉求:面板完全对齐);`idle/capturing/focusing/permission_denied` 次之。

---

## Phase E — 精简 camera.yaml

### Task E1: 结构/样式移出,留行为/状态机

**Files:** `specs/screens/camera.yaml`(修改)

- [ ] **Step 1: 标注移出项**

通读 camera.yaml,把以下类内容标记「→ Figma」:各 section 的 `size/position/cornerRadius/color/active_state视觉/inactive_state视觉/padding`。保留:`panel_state_machine`、`back_stack`、`states`(枚举)、`allowed_differences`、各 `click/action/show_when/visible_when/enabled_when`、`*_defaults`。

- [ ] **Step 2: 瘦身(分批,先 beauty_panel 段)**

对 `beauty_panel`(行 ~511–747):删除纯几何/颜色描述行,保留 tab 切换行为、滑杆绑定 action、`show_when`。在段首加注释:`# 结构/样式 → Figma 帧 camera/panel_beauty_face;本段只留行为`。

- [ ] **Step 3: 抽查行为未丢**

grep 瘦身后 `click:|action:|show_when:|visible_when:` 数量 ≥ 瘦身前(只增不减——只删视觉,不删行为)。Commit。

> 其余 section(filter/ratio/scene/grid/pro)随 Phase F 对齐时同步瘦身,不在本 task 强求全做。

---

## Phase F — iOS 面板对齐(闸门驱动,beauty 先行)

### Task F1: beauty 面板对齐(模板)

**Files:** `iosApp/PoLang/Features/Camera/Beauty/BeautyPanelView.swift`(修改),可能含 `BeautyControls` 等

- [ ] **Step 1: 读 Figma 帧 + 当前 iOS 实现**

a) `mcp__figma__get_design_context`(`fileKey`, `camera/panel_beauty_face` 的 nodeId)→ 拿精确值(面板高比、间距、滑杆 thumb、tab 样式、配色 token)。
b) Read `BeautyPanelView.swift`,对照列出偏差(如 `机-19:heightRatio 0.38≠0.35`、角标绿≠accent)。

- [ ] **Step 2: dump iOS a11y 树(panel_beauty_face 态)**

驱动 iOS 真机进入美颜面板态 → 跑 `CompletenessDumpUITests`(`DUMP_STATE=panel_beauty_face`)→ `devicectl copy from` 取 `ios-a11y-panel_beauty_face.json` 到 `specs/screens/refs/ios/`。

- [ ] **Step 3: 跑闸门,看缺口**

Run: `./scripts/completeness-check.sh camera --state panel_beauty_face`
Expected: 列出 🔴 缺失元素(结构性缺口)+ 🟡 多余。**记录缺口数**(成功标准需 > 0)。

- [ ] **Step 4: 按 Figma 帧修 SwiftUI**

逐缺口修:让 iOS 用 `BeautyPanelTokens.heightRatio`(0.35)而非硬编码;角标用 `scheme.primary`;补齐 Figma 有 iOS 无的元素;尺寸/配色全引 `DesignTokens.swift` 常量。

- [ ] **Step 5: 重跑闸门到绿 + Commit**

重跑 Step 3 → 直到 `OK missing=0`。`git add` 改动 + commit `feat(ios): 美颜面板按 Figma 帧 + 闸门对齐`。

### Task F2: 其余 6 面板 + 4 状态(循 F1 模式)

**Files:** `FilterSelectorView.swift` / `CameraPreviewView.swift`(ratio/scene/grid/pro 面板入口)等

- [ ] **Step 1–5**:对 `panel_beauty_makeup / panel_filter / panel_ratio / panel_scene / panel_grid / panel_pro / idle / capturing / focusing / permission_denied` 重复 F1 的 Step1–5(读帧→dump iOS→闸门→修→绿)。

> 每个状态独立 commit。`capturing/focusing` 瞬时态:iOS 用调试入口强制进入该态再 dump。

---

## Phase G — 验收

### Task G1: 全状态闸门 + 真机并排

- [ ] **Step 1: 全 11 状态闸门**

Run: `./scripts/completeness-check.sh camera`
Expected: 全部 `OK`(missing=0)。任何未建帧/未对齐状态记为已知缺口。

- [ ] **Step 2: 真机 iOS vs Android 截图并排**

对 7 面板态:iOS 真机截图 + Android `refs/android/camera-panel_*.png`,目检结构一致(用户「完全对齐」验收)。

- [ ] **Step 3: 缺口数验证(成功标准)**

统计闭环中填补的完整性缺口数(闸门从红到绿过程中修复的 🔴 数)。**必须 > 0**,否则机制空跑,回查采集/匹配是否有效。

- [ ] **Step 4: 收尾 Commit + 更新设计文档执行进展**

设计文档 §1 顶部加执行结果摘要;commit。本 worktree 可后续走 PR/合 main 流程(见 superpowers:finishing-a-development-branch)。

---

## 自检(写计划后对照 spec)

**1. Spec 覆盖**:
- Figma 文件结构(spec §4)→ Task A1/A2/D1 ✓
- token 播种 + 导出(spec §4.2)→ Task A2/A3 ✓
- 截图锚护栏(spec §4.3)→ Task D1 Step2b/e ✓
- 完整性闸门(spec §5)→ Task C1–C4/F1 Step3 ✓
- 精简 YAML(spec §6)→ Task E1 ✓
- 11 状态帧矩阵(spec §7.2)→ Task D1 ✓
- 工作流 8 步(spec §8)→ Phase A–G 映射 ✓
- 成功标准(spec §12)→ Task G1 Step3 + 面板 ✓
- 待决项(spec §11)→ 「关键决策」段全定 ✓

**2. 占位符扫描**:无 TBD/TODO;`__TOKENS_JSON__`/`__FRAME_NAME__` 是显式注入占位(调用方替换),非计划占位。

**3. 类型一致**:`NormNode(label,role,x,y,w,h)`、`Report(missing,extra,ok)`、`compare()` 在 test/match/编排器中签名一致 ✓。`figma_dump_to_tokens(dump)` 签名一致 ✓。

---

## 执行交接

计划已存 `.worktrees/figma-camera-spec/docs/superpowers/plans/2026-08-14-figma-ui-spec-camera-pilot.md`。两种执行方式:

1. **Subagent 驱动(推荐)** — 每任务派新 subagent,任务间审查,迭代快。
2. **内联执行** — 本会话内用 executing-plans 批量执行 + 检查点。

选哪种?
