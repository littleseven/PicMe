package com.mamba.picme.server.diag

/** 诊断任务状态机。 */
enum class DiagStatus {
    QUEUED,            // 已上报，待 worker 诊断
    DIAGNOSED,         // 已出根因，待用户确认
    FIX_REQUESTED,     // 用户已确认 + 选 mode，待 worker 修复
    FIXED,             // 修复完成且自检通过
    FIXED_UNVERIFIED,  // 修复完成但未跑/未通过测试
    DIAGNOSE_FAILED,   // 诊断失败
    FIX_FAILED,        // 修复失败
    TIMED_OUT,         // 超时
    ARCHIVED,          // 管理后台「废弃」：worker 不再领取，记录保留可激活
}
