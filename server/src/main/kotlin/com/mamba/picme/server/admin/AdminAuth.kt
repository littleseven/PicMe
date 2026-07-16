package com.mamba.picme.server.admin

import com.mamba.picme.server.auth.AccountService

/**
 * 管理后台 cookie 认证：cookie 值 = sha256(ADMIN_TOKEN)，HttpOnly。
 * ADMIN_TOKEN 为空 → 后台禁用（isValid 一律 false）。
 */
object AdminAuth {
    const val COOKIE_NAME = "pl_admin"

    fun expectedCookieValue(adminToken: String): String =
        if (adminToken.isBlank()) "" else AccountService.sha256(adminToken)

    fun isValid(cookieValue: String?, adminToken: String): Boolean {
        if (adminToken.isBlank() || cookieValue.isNullOrBlank()) return false
        return cookieValue == expectedCookieValue(adminToken)
    }
}
