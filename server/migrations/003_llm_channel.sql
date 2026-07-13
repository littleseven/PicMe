-- 参考 DDL（运行时由 Exposed SchemaUtils.create 自动建表；此处供手动初始化/核对）
CREATE TABLE IF NOT EXISTS llm_channel (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  name            TEXT    NOT NULL,                 -- ≤32 字符；同时写入 llm_call_log.provider
  kind            TEXT    NOT NULL,                 -- 'gateway' | 'direct'
  base_url        TEXT    NOT NULL,
  auth_style      TEXT    NOT NULL DEFAULT 'bearer',-- 'bearer' | 'cf_aig'
  api_token       TEXT    NOT NULL DEFAULT '',
  model_map_json  TEXT    NOT NULL DEFAULT '{}',
  enabled         INTEGER NOT NULL DEFAULT 1,
  is_active       INTEGER NOT NULL DEFAULT 0,
  created_at      INTEGER NOT NULL,
  updated_at      INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_llm_channel_name ON llm_channel(name);
