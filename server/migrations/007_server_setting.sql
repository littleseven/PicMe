-- 参考 DDL（运行时由 Exposed SchemaUtils.create 自动建表；此处供手动初始化/核对）
CREATE TABLE IF NOT EXISTS server_setting (
  key        VARCHAR(48) PRIMARY KEY,
  value      INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
