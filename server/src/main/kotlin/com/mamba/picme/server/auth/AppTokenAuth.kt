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
 * 客户端平台标识 header。值为 "android" / "ios" 等，用于 llm_call_log.platform 列与管理后台展示。
 */
const val PLATFORM_HEADER = "X-Platform"
