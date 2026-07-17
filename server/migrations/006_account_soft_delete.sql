-- 参考 DDL（运行时由 Exposed createMissingTablesAndColumn 自动补列）
ALTER TABLE account ADD COLUMN deleted_at INTEGER;
