-- 参考 DDL（运行时由 Exposed createMissingTablesAndColumns 自动补列）
ALTER TABLE llm_channel ADD COLUMN default_model TEXT NOT NULL DEFAULT '';
