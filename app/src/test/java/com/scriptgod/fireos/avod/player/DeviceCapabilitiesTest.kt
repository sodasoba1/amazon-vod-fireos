package com.scriptgod.fireos.avod.player

import android.view.Display
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun displayDiagnosticsReportHdr10PlusWithoutAddingItToAmazonRequest() {
        val types = intArrayOf(Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS)
        assertEquals(listOf("Hdr10Plus"), DeviceCapabilities.reportedHdrFormats(types))
        assertEquals(emptyList<String>(), DeviceCapabilities.supportedHdrFormats(types))
    }

    @Test
    fun uhdRequiresSecureAdaptiveLevelFiveDecoderAnd4kDimensions() {
        assertTrue(DeviceCapabilities.isUhdCapable(true, true, true, true))
        assertFalse(DeviceCapabilities.isUhdCapable(false, true, true, true))
        assertFalse(DeviceCapabilities.isUhdCapable(true, false, true, true))
        assertFalse(DeviceCapabilities.isUhdCapable(true, true, false, true))
        assertFalse(DeviceCapabilities.isUhdCapable(true, true, true, false))
    }
}
