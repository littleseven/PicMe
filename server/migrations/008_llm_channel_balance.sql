-- 参考 DDL（运行时由 Exposed SchemaUtils.createMissingTablesAndColumns 自动补列）
ALTER TABLE llm_channel ADD COLUMN balance_url        VARCHAR(512) DEFAULT '';
ALTER TABLE llm_channel ADD COLUMN balance_json       TEXT         DEFAULT '';
ALTER TABLE llm_channel ADD COLUMN balance_checked_at INTEGER;
