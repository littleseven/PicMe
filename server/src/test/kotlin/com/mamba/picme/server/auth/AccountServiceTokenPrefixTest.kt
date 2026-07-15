package com.mamba.picme.server.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountServiceTokenPrefixTest {

    @Test
    fun `new tokens use pl- prefix`() {
        val token = AccountService.generateToken()
        assertTrue("token should start with pl-", token.startsWith("pl-"))
    }

    @Test
    fun `legacy picme_at tokens are rejected`() {
        assertFalse(AccountService.isTokenFormat("picme_at_" + "a".repeat(64)))
    }

    @Test
    fun `pl- tokens of sufficient length are accepted`() {
        assertTrue(AccountService.isTokenFormat("pl-" + "a".repeat(64)))
    }
}
