package com.scriptgod.fireos.avod.player

import android.media.MediaCodecList
import android.media.MediaDrm
import android.util.Log
import java.util.UUID

/**
 * Device hardware capability queries used to select the right playback quality tier.
 *
 * Why this matters: Amazon's license server enforces
 *   HD quality + Widevine L3 + no HDCP → license DENIED
 *   SD quality + Widevine L3 + no HDCP → license GRANTED
 * Querying before player creation lets the caller select the right tier up-front
 * rather than hitting a license error mid-playback.
 */
object DeviceCapabilities {

    private const val TAG = "DeviceCapabilities"
    private val WIDEVINE_UUID = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)

    /**
     * Queries the Widevine CDM for its security level.
     * Returns "L1" on real hardware with a TEE; "L3" on emulators / un-provisioned devices.
     */
    @Suppress("DEPRECATION")
    fun widevineSecurityLevel(): String {
        val drm = try {
            MediaDrm(WIDEVINE_UUID)
        } catch (e: android.media.UnsupportedSchemeException) {
            Log.w(TAG, "Widevine DRM not supported on this device — assuming L3")
            return "L3"
        } catch (e: Exception) {
            Log.e(TAG, "Unable to initialise Widevine DRM — assuming L3 (${e.javaClass.simpleName})")
            return "L3"
        }

        return try {
            drm.getPropertyString("securityLevel")
        } catch (e: Exception) {
            Log.e(TAG, "Unable to query Widevine security level — assuming L3 (${e.javaClass.simpleName})")
            "L3"
        } finally {
            // MediaDrm.close() was added in API 28; release() is required on API 18–27.
            drm.release()
        }
    }

    /** Returns only the HDR formats supported by the display and accepted by Amazon's request API. */
    fun supportedHdrFormats(hdrTypes: IntArray): List<String> = buildList {
        if (android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in hdrTypes) add("DolbyVision")
        if (android.view.Display.HdrCapabilities.HDR_TYPE_HDR10 in hdrTypes) add("Hdr10")
    }

    /** Returns true if this device has any H265/HEVC video decoder. */
    fun deviceSupportsH265(): Boolean =
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals("video/hevc", ignoreCase = true) }
        }
}
