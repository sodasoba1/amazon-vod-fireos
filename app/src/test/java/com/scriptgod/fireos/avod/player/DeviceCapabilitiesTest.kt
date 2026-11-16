package com.scriptgod.fireos.avod.player

import android.view.Display
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCapabilitiesTest {
    @Test fun uhdRequires4kDisplayAndSecureHevcMainLevelFive() {
        assertTrue(DeviceCapabilities.supportsUhd(3840, 2160, true))
        assertFalse(DeviceCapabilities.supportsUhd(1920, 1080, true))
        assertFalse(DeviceCapabilities.supportsUhd(3840, 2160, false))
    }

    @Test fun codecOverrideOnlyAdvertisesSecureAdaptiveHevc() {
        assertEquals("H264,H265", DeviceCapabilities.codecOverride(true))
        assertEquals("H264", DeviceCapabilities.codecOverride(false))
    }

    @Test fun hdrOverrideRequiresDolbyVisionDecoderAndDisplaySupport() {
        val types = intArrayOf(Display.HdrCapabilities.HDR_TYPE_HDR10, Display.HdrCapabilities.HDR_TYPE_HLG,
            Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION)
        assertEquals(listOf("DolbyVision", "Hdr10"), DeviceCapabilities.hdrFormats(types, true))
        assertEquals(listOf("Hdr10"), DeviceCapabilities.hdrFormats(types, false))
    }

    @Test fun hlgAloneDoesNotClaimAnAmazonHdrFormat() {
        assertEquals(emptyList<String>(), DeviceCapabilities.hdrFormats(
            intArrayOf(Display.HdrCapabilities.HDR_TYPE_HLG), true
        ))
    }
}
