-- 参考 DDL（运行时由 Exposed SchemaUtils.create 自动建表；此处供手动初始化/核对）
-- llm_call_log：管理后台唯一事实源，每次 /v1/chat/completions 一行
CREATE TABLE IF NOT EXISTS llm_call_log (
  id                INTEGER PRIMARY KEY AUTOINCREMENT,
  account_id        INTEGER NOT NULL,
  model             TEXT    NOT NULL,
  provider          TEXT    NOT NULL,             -- CLOUDFLARE | TOKENHUB
  prompt_tokens     INTEGER,                      -- 上游 usage；失败/拦截为 NULL
  completion_tokens INTEGER,
  total_tokens      INTEGER,
  cost_cny          REAL    NOT NULL DEFAULT 0,   -- 估算：单价 × tokens
  resp_bytes        INTEGER NOT NULL DEFAULT 0,   -- 上游响应字节（拦截 = 0）
  status            TEXT    NOT NULL,             -- ok | upstream_error | blocked_quota | blocked_rate
  latency_ms        INTEGER,
  created_at        INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_log_account ON llm_call_log(account_id, created_at);
CREATE INDEX IF NOT EXISTS idx_log_time    ON llm_call_log(created_at);
