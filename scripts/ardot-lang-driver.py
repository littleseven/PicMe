#!/usr/bin/python3
"""ArDot 语言绑定驱动：curl 直连 50501 MCP（SSE）。
用法:
  ardot-lang-driver.py sync-vars          # ledger 中 status==new 的变量 → apply_variables
  ardot-lang-driver.py fetch-ids          # fetch_variables → 回填 ledger ardotId
  ardot-lang-driver.py run <page>.ops.json # 分批(≤25) batch_edit，逐批记 driver.log
日志: docs/08-UI-SPECS/screens/lang/driver.log（追加）"""
import json, sys, time, os, re, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LANG = f'{ROOT}/docs/08-UI-SPECS/screens/lang'
LEDGER = f'{LANG}/ledger.json'
ENDPOINT = 'http://127.0.0.1:50501/api/v1/mcp'

def log(msg):
    with open(f'{LANG}/driver.log', 'a') as f: f.write(f"{time.strftime('%F %T')} {msg}\n")

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
        # 计划代码修正：SSE 会先推 endpoint/event 通知行——取首个带 JSON-RPC result/error 的 data 行，
        # 且错误判定同时看顶层 error 与 result.isError（计划只查 data 顶层 isError，两层都会漏）。
        parsed = []
        for line in raw.splitlines():
            if line.startswith('data:'):
                try: parsed.append(json.loads(line[5:]))
                except ValueError: pass
        # 修I3：删 parsed[0] 回退——纯通知行无 result/error 键，被当返回值会绕过两道错误检查（sync_vars 误标 created）；视同 no-data-line 走重试
        response = next((r for r in parsed if 'result' in r or 'error' in r), None)
        if response is None:
            log(f"rpc {method} attempt{attempt} no-response-line: {raw[:200]}"); continue
        if response.get('error'):
            log(f"rpc {method} MCP error: {json.dumps(response['error'])[:400]}")  # 修I1：最致命失败类别须落 log
            raise RuntimeError(f"MCP error: {json.dumps(response['error'])[:400]}")
        if response.get('result', {}).get('isError'):
            log(f"rpc {method} isError: {json.dumps(response)[:400]}")  # 修I1
            raise RuntimeError(f"MCP isError: {json.dumps(response)[:400]}")
        return response
    raise RuntimeError(f"rpc {method} exhausted retries")

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
    ops = json.load(open(f'{LANG}/{path}'))
    ledger = json.load(open(LEDGER))
    id_of = {k: v['ardotId'] for k, v in ledger['variables'].items()}
    # 修M4：发批前预检所有 $ARDOT 引用可解析——缺 ardotId 列名 fail-fast，避免批次中途 '$'+None TypeError。
    # 占位捕获 lstrip('.')：约定写法 $ARDOTgallery.title，但 $ARDOT.gallery.title（分隔点）为高频人因笔误，
    # 兼容之，否则报错名单出现 '.gallery.title' 与 ledger 键对不上、误导排查。
    refs = sorted({m.group(1).lstrip('.') for op in ops for m in re.finditer(r'\$ARDOT([\w.]+)', op)})
    missing = [v for v in refs if not id_of.get(v)]
    if missing:
        sys.exit(f"run_ops abort: $ARDOT refs missing ardotId (run fetch-ids / check ledger): {missing}")
    def subst(op):
        return re.sub(r'\$ARDOT([\w.]+)', lambda m: '$' + id_of[m.group(1).lstrip('.')], op)
    for i in range(0, len(ops), 25):
        chunk = [subst(o) for o in ops[i:i+25]]
        r = rpc('tools/call', {"name": "batch_edit", "arguments": {"operations": '\n'.join(chunk)}})
        body = content(r)
        # 修I2：batch_edit 逐 op 返回结果/新节点 id，25-op 批次远超 800 字符——
        # 可解析则 log 只记失败项明细+总数；提不出列表则整段落 log(≥4000)。stdout 只留批次摘要。
        try:
            data = json.loads(body)
        except ValueError:
            data = None
        items = None
        if isinstance(data, list):
            items = data
        elif isinstance(data, dict):
            items = next((v for v in (data.get('results'), data.get('operations'), data.get('data'))
                          if isinstance(v, list)), None)
        if items is not None:
            fails = [x for x in items if isinstance(x, dict) and
                     (x.get('error') or x.get('success') is False or x.get('status') == 'failed')]
            log(f"batch {i//25}: total={len(items)} failed={len(fails)}"
                + ('; fails=' + json.dumps(fails, ensure_ascii=False)[:2000] if fails else ''))
            print(f"batch {i//25}: {len(chunk)} ops sent, {len(items) - len(fails)} ok / {len(fails)} failed")
        else:
            log(f"batch {i//25} (raw): {body[:4000]}")
            print(f"batch {i//25}: {len(chunk)} ops sent → raw response {len(body)}ch in driver.log")

if __name__ == '__main__':
    # 修A：显式分派——原 dict.get 兜底把 sys.argv[1] 整体传 run_ops，`run x.ops.json` 收到的是 'run' 而非文件名
    usage = 'usage: ardot-lang-driver.py sync-vars | fetch-ids | run <page>.ops.json'
    if len(sys.argv) >= 2 and sys.argv[1] == 'run':
        if len(sys.argv) < 3:
            print(usage, file=sys.stderr); sys.exit(2)
        run_ops(sys.argv[2])
    elif len(sys.argv) >= 2 and sys.argv[1] == 'sync-vars':
        sync_vars()
    elif len(sys.argv) >= 2 and sys.argv[1] == 'fetch-ids':
        fetch_ids()
    else:
        print(usage, file=sys.stderr); sys.exit(2)
