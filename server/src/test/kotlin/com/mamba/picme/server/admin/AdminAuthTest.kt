package com.mamba.picme.server.admin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminAuthTest {

    @Test
    fun `valid cookie is accepted`() {
        val token = "s3cret-admin-token"
        val cookie = AdminAuth.expectedCookieValue(token)
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
