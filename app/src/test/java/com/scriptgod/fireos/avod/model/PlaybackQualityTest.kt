package com.scriptgod.fireos.avod.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackQualityTest {
    @Test fun automaticProfileHasSafeCodecAndHdrFallbacks() {
        assertEquals("H264", PlaybackQuality.AUTO.codecOverride)
        assertEquals("None", PlaybackQuality.AUTO.hdrOverride)
    }

    @Test fun capabilityDeclarationsCanBeCombinedWithoutAFixedTier() {
        val profile = PlaybackQuality("H264,H265", "DolbyVision,Hdr10")
        assertEquals("H264,H265", profile.codecOverride)
        assertEquals("DolbyVision,Hdr10", profile.hdrOverride)
    }
}
