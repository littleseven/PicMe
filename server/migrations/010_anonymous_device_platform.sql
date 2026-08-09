-- 参考 DDL（运行时由 Exposed createMissingTablesAndColumns 自动补列）
ALTER TABLE anonymous_device ADD COLUMN platform VARCHAR(16);
