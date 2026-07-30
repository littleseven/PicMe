package com.mamba.picme.server.auth

/**
 * 客户端账号认证 header。值为邮箱注册下发的动态 token（pl-* 前缀），
 * 由 AccountService.validateToken 按 account.token_hash 校验，无静态 env。
 */
const val APP_TOKEN_HEADER = "X-App-Token"

/**
 * 未注册访客的设备标识 header。仅 /chat/completions 路径在缺少有效 token 时接受，
 * 命中 AnonymousDevices 设备级试用额度（GUEST_LLM_QUOTA，默认 100）。
 */
const val DEVICE_ID_HEADER = "X-Device-Id"

/**
 * 远程诊断 worker（云主机）鉴权 header。值为静态共享密钥（env DIAG_WORKER_TOKEN）。
 * worker 出口 IP 池化轮换，故不按 IP 白名单，仅校验此 token。
 */
const val DIAG_WORKER_TOKEN_HEADER = "X-Diag-Worker-Token"
