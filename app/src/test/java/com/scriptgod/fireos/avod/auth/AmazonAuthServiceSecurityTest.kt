package com.scriptgod.fireos.avod.auth

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmazonAuthServiceSecurityTest {
    @Test
    fun bearerTokenIsRestrictedToHttpsPlaybackApiHostsOnStandardPort() {
        assertTrue(AmazonAuthService.shouldAttachAuthorization("https://atv-ps-eu.amazon.co.uk/".toHttpUrl()))
        assertTrue(AmazonAuthService.shouldAttachAuthorization("https://atv-ps-eu.amazon.de/".toHttpUrl()))
        assertTrue(AmazonAuthService.shouldAttachAuthorization("https://atv-ps.amazon.com/".toHttpUrl()))
        assertTrue(AmazonAuthService.shouldAttachAuthorization("https://atv-ps-eu.primevideo.com/".toHttpUrl()))

        assertFalse(AmazonAuthService.shouldAttachAuthorization("http://atv-ps-eu.amazon.co.uk/".toHttpUrl()))
        assertFalse(AmazonAuthService.shouldAttachAuthorization("https://atv-ps-eu.amazon.co.uk:8443/".toHttpUrl()))
        assertFalse(AmazonAuthService.shouldAttachAuthorization("https://cdn.example.net/".toHttpUrl()))
        assertFalse(AmazonAuthService.shouldAttachAuthorization("https://aiv-cdn.net/".toHttpUrl()))
        assertFalse(AmazonAuthService.shouldAttachAuthorization("https://cdn.amazon.com/".toHttpUrl()))
        assertFalse(AmazonAuthService.shouldAttachAuthorization("https://atv-ps-eu.amazon.co.uk.attacker.invalid/".toHttpUrl()))
    }
}
