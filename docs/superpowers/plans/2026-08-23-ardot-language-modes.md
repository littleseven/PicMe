# ArDot 语言变量集实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ArDot 云端画布全部设计帧默认显示英文（对齐 `values/strings.xml`），中文经 UI Language 变量集 mode override 一键预览/导出，零物理重复帧。

**Architecture:** 新建独立变量集「UI Language」（English 默认 + 中文两 mode，STRING/TEXT_CONTENT），~190 个唯一字符串一变量多处绑；`ardot-preview-mode.sh` 扩展 `--lang`；对齐表由 structure.json × strings.xml 自动连接生成；绑定经 curl 直连 50501 的驱动脚本分批执行。

**Tech Stack:** ArDot MCP（batch_edit / apply_variables / fetch_variables / capture_screenshot / capture_layout / export_nodes）、python3（curl SSE 客户端）、bash。

**Spec:** `docs/superpowers/specs/2026-08-23-ardot-language-modes-design.md`（帧 id 清单、验收标准、风险表见 spec）

**执行总纪律（每个画布任务第一步都要做，后文简称「基线扫描」）：**
```bash
git log --oneline -3    # 确认没有并发会话新提交骑在本任务头上
```
+ MCP `fetch_editor_state`（确认 currentPage 正常、目标页帧仍在）+ 目标帧 `batch_read` children 计数与 plan 记录的基线数一致。任何突变：停手报告，勿动。

**探针失败（Task 0 NO-GO）的回落：** 放弃变量集，改「画布纯英文」方案（EN 就地改写所有中文帧，不建变量集）。该回落需要重写计划，本计划 Task 2-9 作废。

---

### Task 0: 探针——TEXT_CONTENT 绑定可行性（go/no-go 硬门）

**Files:**
- Modify: `docs/superpowers/specs/2026-08-23-ardot-language-modes-design.md`（§4.3 补 structure.json 实测形态、§8 补直改工效）
- Create: `docs/08-UI-SPECS/screens/lang/probe-record.md`（探针结论台账：变量集 id、两 mode id、可用绑定语法）

- [ ] **Step 0.1 基线扫描**（见执行总纪律；目标帧 `gallery/grid`=105:45，基线文本节点 5 个：相册/今天/(6)/昨天/(3)）

- [ ] **Step 0.2 建变量集**

MCP `apply_variables`（不传 fileUrl，单文件会话省略即可）：

```json
{
  "UI Language": {
    "modes": ["English", "中文"],
    "variables": {
      "gallery.title":     { "type": "STRING", "valuesByMode": {"English": "Gallery", "中文": "相册"} },
      "gallery.today":     { "type": "STRING", "valuesByMode": {"English": "Today", "中文": "今天"} },
      "gallery.yesterday": { "type": "STRING", "valuesByMode": {"English": "Yesterday", "中文": "昨天"} }
    }
  }
}
```

验证：MCP `fetch_variables` 返回中存在「UI Language」集，记录 **setId**、English **modeId**、中文 **modeId**、三个变量 **varId** → 写入 `probe-record.md` 表格。

- [ ] **Step 0.3 找 gallery/grid 文本节点 id**

MCP `batch_read`：`nodeIds:["105:45"]`，`readDepth:4`，`properties:["characters","type","name"]`。从响应中记录「相册/今天/昨天」三个 TEXT 节点 id。

- [ ] **Step 0.4 绑定语法探针（按序尝试，首个成功即停）**

MCP `batch_edit`，`operations` 单字符串，逐条试：

```
U("<nodeId相册>", {characters:"$<varId_gallery.title>"})
```
验证：`batch_read` 该节点 + `capture_screenshot`（单节点 2x）看是否仍渲染「相册/Gallery」。失败（报错或静默 no-op，注意 memory：**U 对已绑属性传纯值会静默 no-op 并回显旧值骗人**）则依次换：

```
U("<nodeId>", {text:"$<varId>"})
U("<nodeId>", {bindVariable:{property:"characters", variableId:"<varId>"}})
I 相关节点上实验性绑定字段
```
全败 → **NO-GO**，执行回落（见计划头）。成功语法记入 `probe-record.md`（后续所有 ops 生成用此语法）。

- [ ] **Step 0.5 mode 切换验证**

对帧根 105:45 写 override（`batch_edit`）：

```
U("105:45", {variableModes:[{variableSetId:"<langSetId>",modeId:"<zhModeId>"}]})
```
`capture_screenshot`(105:45, 2x) → 应显示「相册/今天/昨天」（中文）。再写 English modeId → 截图应显示 Gallery/Today/Yesterday。**两图留档** `/tmp/probe-zh.png`、`/tmp/probe-en.png` 并用 Read/视觉核验。

- [ ] **Step 0.6 双 override 并存**

同一帧再叠加 theme override：

```
U("105:45", {variableModes:[{variableSetId:"2:2",modeId:"79:1"},{variableSetId:"<langSetId>",modeId:"<zhModeId>"}]})
```
截图应同时呈浅色 + 中文。验证后还原：只留 lang override 或全清（`variableModes:null`）。若两 set 互斥覆盖（后者吃前者）→ 记录限制到 probe-record.md，工具脚本改为「写全量 override 数组」策略（本步语法已是全量写，通常天然并存）。

- [ ] **Step 0.7 structure.json 导出形态**

```bash
/usr/bin/python3 scripts/export-ardot-snapshot.py --out /tmp/probe-snap
grep -o '"characters":[^,]*' /tmp/probe-snap/structure.json | head -5
```
确认绑定后 `characters` 导出的是解析值还是引用/空。把结论 Edit 进 spec §4.3（替换「Step 0 确认后注明」句）。

- [ ] **Step 0.8 桌面端直改工效（用户协助项，可延后但不阻塞）**

请用户在 Ardot 桌面端对绑定节点直接输入一个字符再撤销；随后 `batch_read` 查绑定是否存活。结论写入 spec §8 风险表（若直改即解绑 → 注明「文案改动走变量面板」约定）。

- [ ] **Step 0.9 收尾**

`probe-record.md` 定稿（含回滚配方：`U(nodeId,{characters:"相册"})` 字面量覆写解绑）。提交：

```bash
git add docs/08-UI-SPECS/screens/lang/probe-record.md docs/superpowers/specs/2026-08-23-ardot-language-modes-design.md
git commit -m "feat(ardot): UI Language 变量集探针——TEXT_CONTENT 绑定可行性验证"
```

---

### Task 1: `ardot-preview-mode.sh` 增加 `--lang`

**Files:**
- Modify: `scripts/ardot-preview-mode.sh`

- [ ] **Step 1.1 改造脚本**

在现有 `SET_ID="2:2"; DARK_ID="2:0"; LIGHT_ID="79:1"` 行后加：

```bash
LANG_SET_ID="<probe-record.md 中的 setId>"
LANG_EN_ID="<English modeId>"
LANG_ZH_ID="<中文 modeId>"
```

用法行更新为 `scripts/ardot-preview-mode.sh <frameId> <light|dark|auto> [--lang en|zh|auto] [--shot out.png]`。参数解析段（现有 `--shot` 解析前）加：

```bash
LANG_MODE=""
args=()
while [ $# -gt 0 ]; do
  case "$1" in
    --lang) LANG_MODE="${2:?--lang 需 en|zh|auto}"; shift 2 ;;
    *) args+=("$1"); shift ;;
  esac
done
set -- "${args[@]}"
```
（原 `FRAME="${1:?…}"; MODE="${2:?…}"; shift 2` 与 `--shot` 解析保持不变，置于本段之后。）

`case "$MODE"` 构造 `MODE_JSON` 处，把单对象改为组装数组，`auto` 还原=写回默认（Dark/English）——null/[] 是静默 no-op（probe-record.md §5）：

```bash
entries=""
case "$MODE" in
  light) entries="{\"variableSetId\":\"$SET_ID\",\"modeId\":\"$LIGHT_ID\"}" ;;
  dark)  entries="{\"variableSetId\":\"$SET_ID\",\"modeId\":\"$DARK_ID\"}" ;;
  # null/[] 是静默 no-op（probe-record §5 实证），还原=显式写回默认 Dark
  auto)  entries="{\"variableSetId\":\"$SET_ID\",\"modeId\":\"$DARK_ID\"}" ;;
esac
case "${LANG_MODE:-}" in
  en)  entries="${entries:+$entries,}{\"variableSetId\":\"$LANG_SET_ID\",\"modeId\":\"$LANG_EN_ID\"}" ;;
  zh)  entries="${entries:+$entries,}{\"variableSetId\":\"$LANG_SET_ID\",\"modeId\":\"$LANG_ZH_ID\"}" ;;
  # --lang auto 还原=写回 English 默认 mode（同上，null 无效）
  auto) entries="${entries:+$entries,}{\"variableSetId\":\"$LANG_SET_ID\",\"modeId\":\"$LANG_EN_ID\"}" ;;
  "")  : ;;  # 未传 --lang：不触碰 lang override
esac
MODE_JSON="[$entries]"
```

- [ ] **Step 1.2 验证三态**

```bash
scripts/ardot-preview-mode.sh 105:45 dark --lang zh --shot /tmp/t1.png   # OK + 中文截图
scripts/ardot-preview-mode.sh 105:45 auto --lang auto                    # OK（还原=写回默认 Dark/English；null 是 no-op）
scripts/ardot-preview-mode.sh 105:45 light --lang zh --shot /tmp/t2.png  # OK + 浅色中文并存
```
Read 核验 /tmp/t1.png、/tmp/t2.png（t2 若非浅色 → 回看 Step 0.6 结论按全量数组策略修正）。验证后 `105:45` 还原 auto/auto，并 `--shot` 截图确认渲染回到 Dark+English——不能只看 OK 响应（no-op 也回 success）。

- [ ] **Step 1.3 提交**

```bash
git add scripts/ardot-preview-mode.sh
git commit -m "feat(ardot): preview 脚本支持 --lang en|zh 语言 mode override"
```

---

### Task 2: 对齐表生成器 + 绑定驱动脚本

**Files:**
- Create: `scripts/ardot-lang-align.py`（structure.json × strings.xlsx 对齐 + ops 生成）
- Create: `scripts/ardot-lang-driver.py`（curl 直连 50501：建变量回填 id / 分批 batch_edit）
- Create: `docs/08-UI-SPECS/screens/lang/ledger.json`（变量台账，本任务的 SSOT）
- Create: `docs/08-UI-SPECS/screens/lang/<page>.csv`（逐页对齐表，供人工审查）

- [ ] **Step 2.1 写 ledger 骨架**

`docs/08-UI-SPECS/screens/lang/ledger.json`：

```json
{
  "bindStyle": "characters:$",
  "endpoint": "http://127.0.0.1:50501/api/v1/mcp",
  "variables": {
    "gallery.title":     { "zh": "相册",   "en": "Gallery",   "status": "created", "ardotId": null },
    "gallery.today":     { "zh": "今天",   "en": "Today",     "status": "created", "ardotId": null },
    "gallery.yesterday": { "zh": "昨天",   "en": "Yesterday", "status": "created", "ardotId": null }
  }
}
```
（探针三变量先登记；`bindStyle` 按 probe-record.md 实测语法填，`characters:$` 表示 `characters:"$<id>"`。）

- [ ] **Step 2.2 写 `scripts/ardot-lang-align.py`**

```python
#!/usr/bin/python3
"""ArDot 语言对齐表生成器：structure.json 帧文本 × strings.xml (zh↔en 按 name 连接)。
用法:
  ardot-lang-align.py --page gallery            # 生成 docs/08-UI-SPECS/screens/lang/gallery.csv
  ardot-lang-align.py --page gallery --from-en  # 已是 EN 的帧（反向：EN 值→name→zh）
输出列: frame,node_id,name,zh,en,source(matched|manual),var,action(bind|skip)
ledger.json 已有的 var 直接复用（跨页共享文案不重复建变量）。"""
import json, re, csv, argparse, os
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
STRUCT = f'{ROOT}/docs/08-UI-SPECS/screens/refs/ardot/structure.json'
LEDGER = f'{ROOT}/docs/08-UI-SPECS/screens/lang/ledger.json'
OUTDIR = f'{ROOT}/docs/08-UI-SPECS/screens/lang'
RES = f'{ROOT}/androidApp/src/main/res'
CJK = re.compile(r'[一-鿿]')
PAGES = {
  'gallery':  ['gallery/empty','gallery/info','gallery/grid','gallery/search','gallery/search_no_result',
               'gallery/scanning','gallery/sort_menu','gallery/selection','gallery/settings'],
  'settings': ['settings/main_list','settings/local_models','settings/remote_models','settings/sandbox',
               'settings/developer','settings/dialog_language','settings/dialog_stage','settings/dialog_theme',
               'settings/add_remote_provider','settings/provider_config'],
  'chat':     ['chat/empty','chat/conversation','chat/sidebar','chat/guest-nudge-sheet'],
  'editor':   ['editor/current_crop','editor/current_adjust','editor/concept_a_hypic',
               'editor/concept_a_adjust','editor/concept_a_crop','editor/concept_a_beauty'],
  'people':   ['people/grid','people/detail'],
  'gallery_en': ['gallery/tag_control_v2_en','gallery/tag_stage_sheet_en'],
  'chat_en':  ['chat/empty-v2-guest'],
}

def strings(fname):
    out = {}
    for s in ET.parse(f'{RES}/{fname}').getroot().findall('string'):
        out[s.get('name')] = ''.join(s.itertext()).strip()
    return out

def walk_texts(node, frame, acc):
    if isinstance(node, dict):
        ch = node.get('characters')
        if node.get('type') == 'TEXT' and isinstance(ch, str) and ch.strip():
            acc.append({'frame': frame, 'id': node.get('id'), 'name': node.get('name',''), 'text': ch.strip()})
        for v in node.values(): walk_texts(v, frame, acc)
    elif isinstance(node, list):
        for v in node: walk_texts(v, frame, acc)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--page', required=True, choices=PAGES)
    ap.add_argument('--from-en', action='store_true')
    a = ap.parse_args()
    zh, en = strings('values-zh-rCN/strings.xml'), strings('values/strings.xml')
    src_val, dst_val = (en, zh) if a.from_en else (zh, en)
    by_value = {}
    for name, v in src_val.items():
        by_value.setdefault(v, []).append(name)   # 一值多名：取首名，CSV 标注多义
    struct = json.load(open(STRUCT))
    texts = []
    def find(n):
        if isinstance(n, dict):
            if n.get('name') in PAGES[a.page] and n.get('id'):
                texts_frame = []; walk_texts(n, n['name'], texts_frame); texts.extend(texts_frame)
            for v in n.values(): find(v)
        elif isinstance(n, list):
            for v in n: find(v)
    find(struct)
    ledger = json.load(open(LEDGER))
    zh_by_var = {v['zh']: k for k, v in ledger['variables'].items() if v.get('zh')}
    rows = []
    for t in texts:
        val, has_cjk = t['text'], bool(CJK.search(t['text']))
        names = by_value.get(val, [])
        if names and (has_cjk or a.from_en or val in src_val.values()):
            var = zh_by_var.get(val) if not a.from_en else None
            if not var:
                var = names[0].replace('_', '.')
            rows.append({**t, 'zh': val if not a.from_en else zh.get(names[0], ''),
                         'en': dst_val.get(names[0], '') if names else '',
                         'source': 'matched' if names else 'manual', 'var': var,
                         'action': 'bind', 'note': 'multi:' + ','.join(names) if len(names) > 1 else ''})
        elif has_cjk:
            rows.append({**t, 'zh': val, 'en': '', 'source': 'manual', 'var': '', 'action': 'bind',
                         'note': 'no-strings-hit' })
        else:
            rows.append({**t, 'zh': val, 'en': val, 'source': 'literal', 'var': '', 'action': 'skip', 'note': 'data'})
    os.makedirs(OUTDIR, exist_ok=True)
    with open(f'{OUTDIR}/{a.page}.csv', 'w', newline='') as f:
        w = csv.DictWriter(f, fieldnames=['frame','node_id','name','zh','en','source','var','action','note','text','id'])
        w.writeheader(); w.writerows(rows)
    binds = [r for r in rows if r['action'] == 'bind']
    print(f"{a.page}: texts={len(rows)} bind={len(binds)} skip={len(rows)-len(binds)} "
          f"manual={sum(1 for r in binds if r['source']=='manual')} → {OUTDIR}/{a.page}.csv")

if __name__ == '__main__':
    main()
```

- [ ] **Step 2.3 写 `scripts/ardot-lang-driver.py`**

```python
#!/usr/bin/python3
"""ArDot 语言绑定驱动：curl 直连 50501 MCP（SSE）。
用法:
  ardot-lang-driver.py sync-vars          # ledger 中 status!=created 的变量 → apply_variables
  ardot-lang-driver.py fetch-ids          # fetch_variables → 回填 ledger ardotId
  ardot-lang-driver.py run <page>.ops.json # 分批(≤25) batch_edit，逐 op 记录成败
日志: docs/08-UI-SPECS/screens/lang/driver.log（追加）"""
import json, sys, time, os, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LANG = f'{ROOT}/docs/08-UI-SPECS/screens/lang'
LEDGER = f'{LANG}/ledger.json'
ENDPOINT = 'http://127.0.0.1:50501/api/v1/mcp'

def rpc(method, params, retry=(30, 60, 90)):
    for attempt, wait in enumerate([0] + list(retry)):
        if wait: time.sleep(wait)
        body = {"jsonrpc": "2.0", "id": 1, "method": method, **({"params": params} if params else {})}
        req = urllib.request.Request(ENDPOINT, data=json.dumps(body).encode(),
            headers={'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream'})
        try:
            raw = urllib.request.urlopen(req, timeout=120).read().decode()
        except Exception as e:
            log(f"rpc {method} attempt{attempt} transport-fail {e}"); continue
        for line in raw.splitlines():
            if line.startswith('data:'):
                r = json.loads(line[5:])
                if r.get('isError'): raise RuntimeError(f"MCP isError: {json.dumps(r)[:400]}")
                return r
        log(f"rpc {method} attempt{attempt} no-data-line: {raw[:200]}")
    raise RuntimeError(f"rpc {method} exhausted retries")

def log(msg):
    with open(f'{LANG}/driver.log', 'a') as f: f.write(f"{time.strftime('%F %T')} {msg}\n")

def content(r):
    c = r.get('result', {}).get('content', [])
    return c[0].get('text', '') if c else json.dumps(r)[:400]

def sync_vars():
    ledger = json.load(open(LEDGER))
    pending = {k: v for k, v in ledger['variables'].items() if v['status'] == 'new'}
    if not pending: return print('no new vars')
    payload = {"UI Language": {"variables": {
        k: {"type": "STRING", "valuesByMode": {"English": v['en'], "中文": v['zh']}}
        for k, v in pending.items()}}}
    r = rpc('tools/call', {"name": "apply_variables", "arguments": {"variables": payload}})
    print(content(r)[:600])
    for k in pending: ledger['variables'][k]['status'] = 'created'
    json.dump(ledger, open(LEDGER, 'w'), ensure_ascii=False, indent=1)

def fetch_ids():
    ledger = json.load(open(LEDGER))
    r = rpc('tools/call', {"name": "fetch_variables", "arguments": {}})
    data = json.loads(content(r))
    sets = data['data']['variableSets'] if 'data' in data else data.get('variableSets', [])
    s = next(x for x in sets if x.get('name') == 'UI Language')
    ids = {v['name']: v['id'] for v in s.get('variables', [])}
    miss = []
    for k, v in ledger['variables'].items():
        if k in ids: v['ardotId'] = ids[k]
        elif v['status'] == 'created': miss.append(k)
    json.dump(ledger, open(LEDGER, 'w'), ensure_ascii=False, indent=1)
    print(f"backfilled={len(ids)} missing={miss}")

def run_ops(path):
    import re
    ops = json.load(open(f'{LANG}/{path}'))
    ledger = json.load(open(LEDGER))
    id_of = {k: v['ardotId'] for k, v in ledger['variables'].items()}
    def subst(op):
        return re.sub(r'\$ARDOT([\w.]+)', lambda m: '$' + id_of[m.group(1)], op)
    for i in range(0, len(ops), 25):
        chunk = [subst(o) for o in ops[i:i+25]]
        r = rpc('tools/call', {"name": "batch_edit", "arguments": {"operations": '\n'.join(chunk)}})
        log(f"batch {i//25}: {content(r)[:800]}")
        print(f"batch {i//25}: {len(chunk)} ops → {content(r)[:200]}")

if __name__ == '__main__':
    {'sync-vars': sync_vars, 'fetch-ids': fetch_ids}.get(sys.argv[1], lambda: run_ops(sys.argv[1]))()
```
（`run` 的 ops 文件格式：字符串数组，绑定 op 形如 `U("<nodeId>", {characters:"$ARDOT<varName>"})`，`$ARDOT<varName>` 占位在执行时替换为变量 id——占位替换取 op 中 `$ARDOT` 后随的 varName。）

- [ ] **Step 2.4 冒烟**

```bash
/usr/bin/python3 scripts/ardot-lang-align.py --page gallery    # 期望 ~texts=89 bind≈45-60 manual若干 → lang/gallery.csv
/usr/bin/python3 scripts/ardot-lang-driver.py fetch-ids        # 期望 backfilled=3 missing=[]
```
人工打开 `lang/gallery.csv` 抽查 5 行：`相册→Gallery` 命中 `name="gallery"`、多义行有 `multi:` 标注。

- [ ] **Step 2.5 提交**

```bash
git add scripts/ardot-lang-align.py scripts/ardot-lang-driver.py docs/08-UI-SPECS/screens/lang/
git commit -m "feat(ardot): 语言对齐表生成器+绑定驱动脚本（lang/ 台账体系）"
```

---

### Task 3: Gallery 页落地（pilot）

**Files:**
- Modify: `docs/08-UI-SPECS/screens/lang/ledger.json`、`lang/gallery.csv`
- Create: `lang/gallery.ops.json`

基线：9 帧共 89 文本节点（empty 3 / info 22 / grid 5 / search 4 / search_no_result 4 / scanning 5 / sort_menu 11 / selection 3 / settings 32）。

- [ ] **Step 3.1 基线扫描**（gallery 9 帧 children 数与上表文本数吻合、git log 无新提交）

- [ ] **Step 3.2 生成对齐表并人工审查**

```bash
/usr/bin/python3 scripts/ardot-lang-align.py --page gallery
```
逐行审 `lang/gallery.csv`：① `source=manual` 行（strings.xml 未命中：如「生活不是我们活过的日子…」引言、「按内容、地点、文字搜索…」若 key 名不匹配）人工补 EN 列（设计虚构按语义翻译）；② `multi:` 多义行选正确 name（如 zh「保存」对应 `save` 而非 `pexels_api_key_save`，两者 EN 若不同以真机为准）；③ `source=literal skip` 行确认确属语言无关数据。改完 CSV 后，把 manual 行的 var/en 回写 ledger（`status:"new"`）。

- [ ] **Step 3.3 建变量 + 回填 id**

```bash
/usr/bin/python3 scripts/ardot-lang-driver.py sync-vars    # 期望输出 success
/usr/bin/python3 scripts/ardot-lang-driver.py fetch-ids    # 期望 missing=[]
```

- [ ] **Step 3.4 生成并执行绑定 ops**

用 python 从 CSV 生成 `lang/gallery.ops.json`（bind 行 → `U("<node_id>", {characters:"$ARDOT<var>"})`，按 ledger.bindStyle 语法模板）：

```bash
/usr/bin/python3 - <<'EOF'
import csv, json
rows = [r for r in csv.DictReader(open('docs/08-UI-SPECS/screens/lang/gallery.csv')) if r['action']=='bind' and r['var']]
ops = [f'U("{r["node_id"]}", {{characters:"$ARDOT{r[\"var\"]}"}})' for r in rows]
json.dump(ops, open('docs/08-UI-SPECS/screens/lang/gallery.ops.json','w'), ensure_ascii=False, indent=0)
print(len(ops), 'ops')
EOF
/usr/bin/python3 scripts/ardot-lang-driver.py run gallery.ops.json
```
期望：batch 全部 `"success":true`；任何 op 失败 → 看 driver.log 逐条修（常见：node_id 失效→重跑 align 生成）。

- [ ] **Step 3.5 EN 渲染 + 布局验证**

帧根无需 override（English 是默认 mode）——但**新建变量集后画布默认 mode 是否 English 需确认**：`capture_screenshot` 105:45 应已是 Gallery/Today/Yesterday；若仍中文 → 对页面根 103:1 写 English override（`U("103:1",{variableModes:[{variableSetId:"<setId>",modeId:"<enId>"}]})`）。
逐帧验证 9 帧：

```bash
for f in 103:3 105:1 105:45 107:112 107:156 107:189 107:264 107:353 118:1; do
  echo "== $f"; # MCP capture_layout problemsOnly + capture_screenshot 逐帧
done
```
EN 更长引发的折行/裁字（OUTSIDE_PARENT / 高度溢出）→ `U` 调宽文本盒或容器宽（沿 auto-layout 派生坐标纪律：改 padding/gap，不硬写 x）。修完重拍。**修复记录追加到 gallery.csv 的 note 列。**

- [ ] **Step 3.6 zh 抽查**

```bash
scripts/ardot-preview-mode.sh 105:45 dark --lang zh --shot /tmp/g-grid-zh.png   # 中文回显
scripts/ardot-preview-mode.sh 105:45 auto --lang auto
```
Read 核验中文正确。

- [ ] **Step 3.7 提交**（canvas 本体不入库，快照统一在 Task 9；此处提交台账）

```bash
git add docs/08-UI-SPECS/screens/lang/
git commit -m "feat(ardot): Gallery 9 帧语言绑定——EN 正稿+zh 变量（对齐表/ops/台账）"
```

---

### Task 4: Settings 页落地

**Files:** `lang/settings.csv`、`lang/settings.ops.json`、`ledger.json`

基线：10 帧共 107 文本节点（main_list 19 / local_models 15 / remote_models 12 / sandbox 12 / developer 15 / dialog_language 7 / dialog_stage 9 / dialog_theme 6 / add_remote_provider 3 / provider_config 9）。

- [ ] **Step 4.1 基线扫描**（Settings 页 108:1，10 帧在位）

- [ ] **Step 4.2 对齐表 + 审查**

```bash
/usr/bin/python3 scripts/ardot-lang-align.py --page settings
```
重点人工项：「邮箱注册、额度与 API Key 绑定。」带格式参数的 strings.xml 模板（`%1$s` 占位）——CSV 匹配失败属正常，人工按 EN 模板填；「DeepSeek 官方 · 已配置」供应商行=模板文案 manual；dialog_language 帧内「English/中文/繁體中文」选项字面量=语言名，**EN mode 下保持原样**（选项列出的是语言自身名字），这三行改 `action=skip`、note=language-self-name。manual 行回写 ledger。

- [ ] **Step 4.3 建变量 + 绑定 + 验证**（与 Task 3 Step 3.3-3.6 完全同构）

```bash
/usr/bin/python3 scripts/ardot-lang-driver.py sync-vars && /usr/bin/python3 scripts/ardot-lang-driver.py fetch-ids
# 生成 settings.ops.json（同 Task 3 Step 3.4 heredoc，页名换 settings）
/usr/bin/python3 scripts/ardot-lang-driver.py run settings.ops.json
# 逐帧 capture_layout problemsOnly + 截图：108:94 108:363 111:1 111:50 111:129 111:276 111:294 111:446 166:3 167:2
scripts/ardot-preview-mode.sh 108:94 dark --lang zh --shot /tmp/s-main-zh.png && scripts/ardot-preview-mode.sh 108:94 auto --lang auto
```

- [ ] **Step 4.4 提交**

```bash
git add docs/08-UI-SPECS/screens/lang/ && git commit -m "feat(ardot): Settings 10 帧语言绑定——EN 正稿+zh 变量"
```

---

### Task 5: Chat 页落地 + empty-v2-guest 补绑

**Files:** `lang/chat.csv`、`lang/chat.ops.json`、`lang/chat_en.csv`、`lang/chat_en.ops.json`、`ledger.json`

基线：4 中文帧 43 文本节点（empty 13 / conversation 13 / sidebar 12 / guest-nudge-sheet 5）+ EN 帧 empty-v2-guest 14。

- [ ] **Step 5.1 基线扫描**（Chat 页 111:319）

- [ ] **Step 5.2 中文 4 帧对齐 + 绑定**（同 Task 3 流程，页名 chat）

```bash
/usr/bin/python3 scripts/ardot-lang-align.py --page chat
```
人工重点：会话消息正文（「根据刚才的数据，大宝的照片…」「8818 tok · 53 · 18」等）=设计虚构 → manual 语义翻译；「找到了 1598 张…」=模板串 manual。执行 sync-vars/fetch-ids/ops/run + 逐帧验证（111:321 111:383 111:492 171:171）+ zh 抽查 111:383。

- [ ] **Step 5.3 empty-v2-guest 反向补绑**

```bash
/usr/bin/python3 scripts/ardot-lang-align.py --page chat_en --from-en
```
EN 值→name→zh 反查（「Hi, I'm Xiaolang」等 strings.xml 命中即得 zh；未命中 manual 译中文，其 zh 译法可参考 `chat/empty-v2-guest-zh` 帧现有文案=现成对照表）。审查后回写 ledger（en 值为源）→ sync-vars → fetch-ids → 生成 `chat_en.ops.json`（bind 模板同构）→ run → 截图验证 171:187 EN 不变 + `--lang zh` 抽查回中文。

- [ ] **Step 5.4 提交**

```bash
git add docs/08-UI-SPECS/screens/lang/ && git commit -m "feat(ardot): Chat 4+1 帧语言绑定——含 empty-v2 反向补绑"
```

---

### Task 6: Editor 页落地

**Files:** `lang/editor.csv`、`lang/editor.ops.json`、`ledger.json`

基线：6 帧共 73 文本节点（current_crop 3 / current_adjust 15 / concept_a_hypic 16 / concept_a_adjust 17 / concept_a_crop 15 / concept_a_beauty 7）。

- [ ] **Step 6.1 基线扫描**（Editor 页 118:104；注意 editor.yaml §18 重设计是另一工作流，若发现 concept 帧结构与基线不符→停手报告）

- [ ] **Step 6.2 对齐 + 绑定 + 验证**（同 Task 3 流程，页名 editor；帧 118:105 118:165 118:243 118:372 118:480 118:583；人工重点：滤镜名「徕卡经典/徕卡鲜艳」查 strings.xml filter_* 键，未命中 manual）

- [ ] **Step 6.3 提交**

```bash
git add docs/08-UI-SPECS/screens/lang/ && git commit -m "feat(ardot): Editor 6 帧语言绑定——EN 正稿+zh 变量"
```

---

### Task 7: people/tag_control EN 帧补绑 + 命名收敛 + 删 _zh 帧

**Files:** `lang/people.csv`、`lang/people.ops.json`、`lang/gallery_en.csv`、`lang/gallery_en.ops.json`、`ledger.json`

基线：people 2 帧 41 文本节点（grid 20 / detail 21）+ gallery_en 2 帧 60（tag_control_v2_en 51 / tag_stage_sheet_en 9）。

- [ ] **Step 7.1 基线扫描**

- [ ] **Step 7.2 people + gallery_en 反向补绑**（同 Task 5 Step 5.3 流程，`--from-en`；帧 171:3 171:87 171:273 172:113；验证 EN 不变 + 每帧 zh 抽查）

- [ ] **Step 7.3 命名收敛（batch_edit）**

```
U("171:273", {name:"tag_control_v2"})
U("172:113", {name:"tag_stage_sheet"})
U("182:63", {name:"viewer-bottombar-preview"})
```
（`182:63` viewer-bottombar-en-preview 若已改名/移位以基线扫描为准。）

- [ ] **Step 7.4 删 `chat/empty-v2-guest-zh`**

前置：Step 5.3 已把其文案收进变量集（zh 值即来源于它）。执行 `batch_edit`：`D("172:117")`。验证 `fetch_editor_state` Chat 页顶层无该帧。

- [ ] **Step 7.5 仓库引用同步**

```bash
grep -rn 'tag_control_v2_en\|tag_stage_sheet_en\|empty-v2-guest-zh\|viewer-bottombar-en-preview' docs/ scripts/ --include='*.md' --include='*.yaml' --include='*.py' | grep -v 'lang/\|plans/2026-08-23'
```
命中处逐一更新为新名（manifest 在 Task 9 export 后自动重生成）。提交：

```bash
git add -A docs/ && git commit -m "feat(ardot): people/tag_control 补绑+命名收敛(EN=无后缀 canonical)+删 _zh 物理帧"
```

---

### Task 8: 全量核验

- [ ] **Step 8.1 默认 EN 全帧截图核验**

逐页对全部 39 帧截图（页根 id 截图亦可：6:2 103:1 111:319 108:1 118:104 171:1），Read/视觉核验无中文残留（language-self-name 三行除外——见 Task 4 记录）。发现中文残留 → 该帧重跑 align 比对补绑。

- [ ] **Step 8.2 布局零回归**

39 帧 `capture_layout problemsOnly` 全绿（对照：修复记录均在 CSV note 列，无新增 OUTSIDE_PARENT/裁字）。

- [ ] **Step 8.3 zh 模式全页抽查**

页根 override 继承未在探针验证（仅帧根实证）——首次对 103:1 写 override 后先单帧截图确认继承生效，再进入循环。

```bash
for p in 103:1 108:1 111:319 118:104 171:1; do scripts/ardot-preview-mode.sh $p dark --lang zh --shot /tmp/zh-$p.png; done
for p in 103:1 108:1 111:319 118:104 171:1; do scripts/ardot-preview-mode.sh $p auto --lang auto; done
```
核验 5 张全中文。

- [ ] **Step 8.4 token 门禁**

```bash
/usr/bin/python3 scripts/sync-ardot-variables.py --check
```
期望：零漂移 exit 0（UI Language 集不在 PoLang Tokens 过滤范围内，若脚本报未知集合错误→按 SET_NAME 过滤逻辑修脚本，属脚本 bug）。

---

### Task 9: 快照入库 + zh 预览 pass + 收尾

**Files:** `docs/08-UI-SPECS/screens/refs/ardot/*`（快照再生成）、spec 状态更新

- [ ] **Step 9.1 export 快照**

```bash
/usr/bin/python3 scripts/export-ardot-snapshot.py
find docs/08-UI-SPECS/screens/refs/ardot -name '*.png' -size -5k    # 必须为空
```
空白帧恢复套路（memory 实证）：MCP `capture_screenshot`(2x) 单帧重生成替换；settings/sandbox 双通道空白 → 桌面端滚动画布后重跑。

- [ ] **Step 9.2 zh 抽查帧入 refs**

```bash
scripts/ardot-preview-mode.sh 105:45 dark --lang zh --shot docs/08-UI-SPECS/screens/refs/ardot/gallery-grid-zh.png
scripts/ardot-preview-mode.sh 105:45 auto --lang auto
```
（另取 108:94→settings-main_list-zh、111:383→chat-conversation-zh、118:243→editor-concept_a_hypic-zh、171:87→people-detail-zh，同法。）

- [ ] **Step 9.3 验收清单过一遍**（spec §6 五条逐条核）

- [ ] **Step 9.4 提交 + 收尾**

```bash
git add docs/08-UI-SPECS/ && git commit -m "feat(ardot): 全画布语言变量集落地——EN 默认正稿+zh mode 快照（40 帧含 5 张 zh 抽查）"
```
spec 头部状态改「已实施（2026-08-23）」；`git log` 确认未骑并发 HEAD。向用户汇报：验收五条结果 + 探针结论（含桌面端直改工效结论）+ zh 预览用法一句（`scripts/ardot-preview-mode.sh <frame|page> dark --lang zh`）。

---

## Self-Review 记录

- Spec 覆盖：§2 范围→Task 3-7；§3 机制→Task 0/2；§4 工具→Task 1/9；§5 顺序→Task 编号一一对应；§6 验收→Task 8/9；§7 清理→Task 7；§8 风险→Task 0 探针项+Task 9 空白帧套路。无缺口。
- 占位符扫描：`<probe-record.md 中的 setId>` 等 <> 项均为 Task 0 实测数据回填点（数据流，非计划缺口）；无 TBD/TODO。
- 类型一致性：ledger 字段（zh/en/status/ardotId）、ops 模板 `U("<id>",{characters:"$ARDOT<var>"})`、脚本入参（--page/--from-en/sync-vars/fetch-ids/run）跨任务一致。
- 已知简化：无。`$ARDOT<varName>` 占位替换已按正则实现（`re.sub(r'\$ARDOT([\w.]+)', …)`），与 ops 生成端 Task 3 Step 3.4 的模板一致。
