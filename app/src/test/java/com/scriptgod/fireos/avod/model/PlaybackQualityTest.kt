package com.scriptgod.fireos.avod.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackQualityTest {
    @Test
    fun dynamicUhdHdrOverrideRetainsUhdPreference() {
        assertEquals("UHD_HDR", PlaybackQuality.toPrefValue(PlaybackQuality.UHD_HDR.copy(hdrOverride = "Hdr10")))
    }

    @Test
    fun uhdSdrFallbackRetainsUhdPreference() {
        assertEquals("UHD_HDR", PlaybackQuality.toPrefValue(PlaybackQuality.UHD_SDR))
    }

    @Test
    fun hdPresetsKeepTheirOwnPreferenceValues() {
        assertEquals("HD", PlaybackQuality.toPrefValue(PlaybackQuality.HD))
        assertEquals("HD_H265", PlaybackQuality.toPrefValue(PlaybackQuality.HD_H265))
    }
}
