package com.scriptgod.fireos.avod.player

import android.view.Display
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceCapabilitiesTest {
    @Test
    fun hdrOverrideContainsOnlySupportedAmazonFormatsInReferenceOrder() {
        val types = intArrayOf(
            Display.HdrCapabilities.HDR_TYPE_HDR10,
            Display.HdrCapabilities.HDR_TYPE_HLG,
            Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION
        )
        assertEquals(listOf("DolbyVision", "Hdr10"), DeviceCapabilities.supportedHdrFormats(types))
    }

    @Test
    fun hlgAloneDoesNotClaimAnAmazonHdrFormat() {
        val types = intArrayOf(Display.HdrCapabilities.HDR_TYPE_HLG)
        assertEquals(emptyList<String>(), DeviceCapabilities.supportedHdrFormats(types))
    }
}
