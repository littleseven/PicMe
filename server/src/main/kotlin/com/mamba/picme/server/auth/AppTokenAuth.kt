package com.mamba.picme.server.auth

/**
 * 客户端请求携带的认证 header 名。值为邮箱注册下发的动态 token（picme_at_*），
 * 由 AccountService.validateToken 按 account.token_hash 校验，无静态 env。
 */
const val APP_TOKEN_HEADER = "X-App-Token"
