-- 诊断工单链路废弃（能力并入 claude-tunnel 实时通道），删除 diag_job 表。
-- 参考 DDL：运行时 schema 由 Exposed SchemaUtils 管理（DiagJobs 表定义已移除），
-- 本文件用于在已部署库上手动/运维执行清理。
DROP TABLE IF EXISTS diag_job;
