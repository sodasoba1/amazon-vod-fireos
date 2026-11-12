package com.scriptgod.fireos.avod.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmazonAuthServiceSecurityTest {
    @Test
    fun bearerTokenIsAllowedOnlyOnKnownAmazonPlaybackApiHosts() {
        assertTrue(AmazonAuthService.isTrustedApiHost("atv-ps-eu.amazon.co.uk"))
        assertTrue(AmazonAuthService.isTrustedApiHost("atv-ps-eu.amazon.de"))
        assertTrue(AmazonAuthService.isTrustedApiHost("atv-ps.amazon.com"))
        assertTrue(AmazonAuthService.isTrustedApiHost("atv-ps-eu.primevideo.com"))

        assertFalse(AmazonAuthService.isTrustedApiHost("cdn.example.net"))
        assertFalse(AmazonAuthService.isTrustedApiHost("aiv-cdn.net"))
        assertFalse(AmazonAuthService.isTrustedApiHost("cdn.amazon.com"))
        assertFalse(AmazonAuthService.isTrustedApiHost("atv-ps-eu.amazon.co.uk.attacker.invalid"))
    }
}
