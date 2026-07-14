-- 参考 DDL（运行时由 Exposed createMissingTablesAndColumns 自动补列）
ALTER TABLE account ADD COLUMN token_plain VARCHAR(128) NOT NULL DEFAULT '';
