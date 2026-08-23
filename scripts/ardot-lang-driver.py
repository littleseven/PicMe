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
        response = next((r for r in parsed if 'result' in r or 'error' in r), parsed[0] if parsed else None)
        if response is None:
            log(f"rpc {method} attempt{attempt} no-data-line: {raw[:200]}"); continue
        if response.get('error'):
            raise RuntimeError(f"MCP error: {json.dumps(response['error'])[:400]}")
        if response.get('result', {}).get('isError'):
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
    def subst(op):
        return re.sub(r'\$ARDOT([\w.]+)', lambda m: '$' + id_of[m.group(1)], op)
    for i in range(0, len(ops), 25):
        chunk = [subst(o) for o in ops[i:i+25]]
        r = rpc('tools/call', {"name": "batch_edit", "arguments": {"operations": '\n'.join(chunk)}})
        log(f"batch {i//25}: {content(r)[:800]}")
        print(f"batch {i//25}: {len(chunk)} ops → {content(r)[:200]}")

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
