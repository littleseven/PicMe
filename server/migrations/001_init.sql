-- 参考建表脚本（运行时由 Exposed SchemaUtils.create 自动建表；此处供手动初始化/核对）
CREATE TABLE IF NOT EXISTS rule (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  scene TEXT NOT NULL,
  locale TEXT NOT NULL,
  condition_json TEXT,
  params_json TEXT NOT NULL,
  version INTEGER NOT NULL,
  enabled INTEGER NOT NULL DEFAULT 1
);
CREATE INDEX IF NOT EXISTS idx_rule_scene ON rule(scene, locale, enabled);
-- seed 幂等关键：INSERT OR IGNORE 需唯一约束才会忽略重复
CREATE UNIQUE INDEX IF NOT EXISTS idx_rule_seed ON rule(scene, locale, version);

CREATE TABLE IF NOT EXISTS asset (
  key TEXT PRIMARY KEY,
  kind TEXT NOT NULL,
  version INTEGER NOT NULL,
  size INTEGER,
  md5 TEXT,
  cos_bucket TEXT NOT NULL,
  cos_key TEXT NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS telemetry_event (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  type TEXT NOT NULL,
  payload_json TEXT,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_telemetry_time ON telemetry_event(created_at);

CREATE TABLE IF NOT EXISTS llm_daily_counter (
  day TEXT PRIMARY KEY,
  tokens INTEGER NOT NULL DEFAULT 0,
  cost_cny REAL NOT NULL DEFAULT 0,
  blocked INTEGER NOT NULL DEFAULT 0
);
