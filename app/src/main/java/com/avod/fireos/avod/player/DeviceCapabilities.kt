package com.avod.fireos.avod.player

import android.media.MediaCodecInfo
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

    /**
     * All HEVC decoder profile/levels on this device.
     *
     * Mirrors the stock Prime Video app's DecoderCapabilitiesProvider: it iterates every
     * non-encoder `video/hevc` decoder's `profileLevels` to decide UHD support.
     */
    private fun hevcProfileLevels(): List<MediaCodecInfo.CodecProfileLevel> =
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals("video/hevc", ignoreCase = true) } }
            .mapNotNull { runCatching { it.getCapabilitiesForType("video/hevc") }.getOrNull() }
            .flatMap { it.profileLevels?.asList() ?: emptyList() }

    /**
     * HEVC **Main** profile at **Level 5** or higher (3840x2160).
     *
     * This is the stock app's actual UHD codec gate (SUPPORTS_UHD = display >= 4K AND
     * supportsHevcMainL5). The stock app matches `profile == HEVCProfileMain` with
     * `level >= HEVCMainTierLevel5` (0x4000 = 16384). A plain "has any HEVC decoder"
     * check is too weak — a 1080p-only HEVC decoder (Level 4) passes it but cannot do 4K.
     */
    fun supportsHevcMainL5(): Boolean = hevcProfileLevels().any {
        it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain &&
            it.level >= MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel5
    }

    /** HEVC **Main10** profile at **Level 5**+ — required for 4K HDR10 (10-bit). */
    fun supportsHevcMain10L5(): Boolean = hevcProfileLevels().any {
        it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 &&
            it.level >= MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel5
    }

    /**
     * Current HDCP level reported by the Widevine CDM, e.g. "HDCP-2.2", "HDCP-1.4",
     * "HDCP-NONE", "Disconnected", or "UNKNOWN" when unavailable.
     *
     * Mirrors HdcpChecker's fallback path in the stock app, which reads the Widevine
     * `hdcpLevel` property when the FireOS service value is absent. Amazon releases 4K
     * only over an HDCP 2.2 link, so this feeds the UHD decision below.
     */
    fun hdcpLevel(): String {
        val drm = try {
            MediaDrm(WIDEVINE_UUID)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to init Widevine for HDCP query (${e.javaClass.simpleName})")
            return "UNKNOWN"
        }
        return try {
            drm.getPropertyString("hdcpLevel")
        } catch (e: Exception) {
            Log.w(TAG, "Unable to query HDCP level (${e.javaClass.simpleName})")
            "UNKNOWN"
        } finally {
            @Suppress("DEPRECATION")
            drm.release()
        }
    }

    /**
     * True when the device reports HDCP **2.2** or higher — Amazon's gate for 4K output.
     * Returns false for a definite lower level; treats "UNKNOWN"/unparseable as not-blocking
     * (the caller logs rather than hard-blocking, since some devices report NONE until a
     * secure session is active).
     */
    fun supportsHdcp22(): Boolean {
        val m = Regex("""(\d+)\.(\d+)""").find(hdcpLevel()) ?: return false
        val major = m.groupValues[1].toInt()
        val minor = m.groupValues[2].toInt()
        return major > 2 || (major == 2 && minor >= 2)
    }
}
