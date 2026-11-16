package com.scriptgod.fireos.avod.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlaybackQualityTest {
    @Test fun automaticProfileHasSafeCodecAndHdrFallbacks() {
        assertEquals("H264", PlaybackQuality.AUTO.codecOverride)
        assertEquals("None", PlaybackQuality.AUTO.hdrOverride)
    }

    @Test fun capabilityQueryDoesNotPinAQualityTier() {
        val profile = PlaybackQuality("H264,H265", "DolbyVision,Hdr10")
        val query = profile.amazonCapabilityQueryString()
        assertEquals("&deviceVideoCodecOverride=H264,H265&deviceHdrFormatsOverride=DolbyVision,Hdr10", query)
        assertFalse(query.contains("deviceVideoQualityOverride"))
    }
}
