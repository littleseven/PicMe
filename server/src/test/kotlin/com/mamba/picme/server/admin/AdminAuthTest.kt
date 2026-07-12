package com.mamba.picme.server.admin

import com.mamba.picme.server.auth.AccountService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminAuthTest {

    @Test
    fun `valid cookie equals sha256 of token and validates`() {
        val token = "s3cret-admin-token"
        val cookie = AdminAuth.expectedCookieValue(token)
        assertEquals(AccountService.sha256(token), cookie)
        assertTrue(AdminAuth.isValid(cookie, token))
    }

    @Test
    fun `wrong cookie rejected`() {
        assertFalse(AdminAuth.isValid("nope", "token"))
    }

    @Test
    fun `blank admin token disables admin entirely`() {
        assertFalse(AdminAuth.isValid(AdminAuth.expectedCookieValue(""), ""))
        assertFalse(AdminAuth.isValid("anything", ""))
    }

    @Test
    fun `null or blank cookie rejected`() {
        assertFalse(AdminAuth.isValid(null, "token"))
        assertFalse(AdminAuth.isValid("   ", "token"))
    }
}
