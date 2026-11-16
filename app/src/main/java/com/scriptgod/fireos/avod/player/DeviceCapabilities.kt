package com.scriptgod.fireos.avod.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaDrm
import android.os.Build
import android.util.Log
import android.view.Display
import java.util.UUID

/** Prime-style device capability inspection: secure/adaptive decoders, profiles, display and DRM. */
object DeviceCapabilities {
    private const val TAG = "DeviceCapabilities"
    private val WIDEVINE_UUID = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")

    data class Snapshot(
        val widevineSecurityLevel: String,
        val maxVideoWidth: Int,
        val maxVideoHeight: Int,
        val supportsHdcp: Boolean,
        val supportsHevcMainL4: Boolean,
        val supportsHevcMainL5: Boolean,
        val supportsHevcMain10L4: Boolean,
        val supportsHevcMain10L5: Boolean,
        val supportsDolbyVisionFhd30: Boolean,
        val supportsDolbyVisionUhd30: Boolean,
        val supportsAv1Main10L4: Boolean,
        val supportsAv1Main10L5: Boolean,
        val supportsHdr10: Boolean,
        val supportsHdr10Plus: Boolean,
        val supportsDolbyVision: Boolean
    ) {
        val supportsUhd: Boolean
            get() = DeviceCapabilities.supportsUhd(maxVideoWidth, maxVideoHeight, supportsHevcMainL5)
        val supportsHevc: Boolean get() = supportsHevcMainL4
        fun amazonHdrFormats(): List<String> = buildList {
            if (supportsDolbyVision) add("DolbyVision")
            if (supportsHdr10) add("Hdr10")
            if (Build.VERSION.SDK_INT >= 29 && supportsHdr10Plus) add("Hdr10Plus")
        }
    }

    private data class Profiles(
        var hevcMainL4: Boolean = false, var hevcMainL5: Boolean = false,
        var hevcMain10L4: Boolean = false, var hevcMain10L5: Boolean = false,
        var dvFhd: Boolean = false, var dvUhd: Boolean = false,
        var av1L4: Boolean = false, var av1L5: Boolean = false
    )

    @Suppress("DEPRECATION")
    fun widevineSecurityLevel(): String {
        val drm = try { MediaDrm(WIDEVINE_UUID) } catch (e: Exception) {
            Log.w(TAG, "Widevine security level unavailable: " + e.javaClass.simpleName)
            return "UNKNOWN"
        }
        return try {
            drm.getPropertyString("securityLevel").takeIf { it == "L1" || it == "L3" } ?: "UNKNOWN"
        } catch (e: Exception) {
            Log.w(TAG, "Widevine security-level query failed: " + e.javaClass.simpleName)
            "UNKNOWN"
        } finally { drm.release() }
    }

    @Suppress("DEPRECATION")
    fun inspect(context: Context): Snapshot {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        val mode = display?.mode
        var width = mode?.physicalWidth ?: 0
        var height = mode?.physicalHeight ?: 0
        for (property in listOf("vendor.display-size", "sys.display-size")) {
            val value = readSystemProperty(property) ?: continue
            val match = Regex("\\D*(\\d+)\\D+(\\d+)\\D*").matchEntire(value) ?: continue
            val w = match.groupValues[1].toIntOrNull() ?: continue
            val h = match.groupValues[2].toIntOrNull() ?: continue
            if (w > width && h > height) { width = w; height = h }
        }
        val flags = display?.flags ?: 0
        val hdcp = flags and Display.FLAG_SECURE != 0 &&
            flags and Display.FLAG_SUPPORTS_PROTECTED_BUFFERS != 0
        val hdrTypes = if (Build.VERSION.SDK_INT >= 34 && mode != null) mode.supportedHdrTypes
            else display?.hdrCapabilities?.supportedHdrTypes ?: intArrayOf()
        val hdr10 = Display.HdrCapabilities.HDR_TYPE_HDR10 in hdrTypes
        val hdr10Plus = Build.VERSION.SDK_INT >= 29 && Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS in hdrTypes
        val displayDv = Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in hdrTypes
        val decoders = inspectSecureAdaptiveDecoders()
        val uhd = supportsUhd(width, height, decoders.hevcMainL5)
        val dv = displayDv && if (uhd) decoders.dvUhd else decoders.dvFhd
        return Snapshot(
            widevineSecurityLevel(), width, height, hdcp,
            decoders.hevcMainL4, decoders.hevcMainL5, decoders.hevcMain10L4, decoders.hevcMain10L5,
            decoders.dvFhd, decoders.dvUhd, decoders.av1L4, decoders.av1L5,
            hdr10, hdr10Plus, dv
        ).also {
            Log.i(TAG, "Profile drm=" + it.widevineSecurityLevel + " hdcp=" + it.supportsHdcp +
                " display=" + width + "x" + height + " uhd=" + it.supportsUhd +
                " hevcMainL4=" + it.supportsHevcMainL4 + " hevcMainL5=" + it.supportsHevcMainL5 +
                " hevcMain10L4=" + it.supportsHevcMain10L4 + " hevcMain10L5=" + it.supportsHevcMain10L5 +
                " hdr=" + it.amazonHdrFormats())
        }
    }

    private fun inspectSecureAdaptiveDecoders(): Profiles {
        val result = Profiles()
        val codecs = try { MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos } catch (e: Exception) {
            Log.w(TAG, "Codec enumeration failed", e)
            return result
        }
        codecs.filterNot { it.isEncoder }.forEach { codec ->
            secureAdaptiveCapabilities(codec, "video/hevc")?.profileLevels?.forEach { p ->
                when (p.profile) {
                    1 -> { if (p.level >= 1024) result.hevcMainL4 = true; if (p.level >= 16384) result.hevcMainL5 = true }
                    2 -> { if (p.level >= 1024) result.hevcMain10L4 = true; if (p.level >= 16384) result.hevcMain10L5 = true }
                }
            }
            secureAdaptiveCapabilities(codec, "video/dolby-vision")?.profileLevels?.forEach { p ->
                if (p.profile == 32) { if (p.level >= 8) result.dvFhd = true; if (p.level >= 64) result.dvUhd = true }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                secureAdaptiveCapabilities(codec, "video/av01")?.profileLevels?.forEach { p ->
                    if (p.profile == 2) { if (p.level >= 256) result.av1L4 = true; if (p.level >= 4096) result.av1L5 = true }
                }
            }
        }
        return result
    }

    private fun secureAdaptiveCapabilities(codec: MediaCodecInfo, mime: String): MediaCodecInfo.CodecCapabilities? =
        try {
            val caps = codec.getCapabilitiesForType(mime)
            if (!caps.isFeatureSupported("adaptive-playback") ||
                !caps.isFeatureSupported("secure-playback") ||
                caps.isFeatureRequired("tunneled-playback")) null else caps
        } catch (_: IllegalArgumentException) { null }

    internal fun supportsUhd(width: Int, height: Int, secureHevcMainL5: Boolean): Boolean =
        width >= 3840 && height >= 2160 && secureHevcMainL5

    internal fun codecOverride(secureHevcMainL4: Boolean): String =
        if (secureHevcMainL4) "H264,H265" else "H264"

    internal fun hdrFormats(hdrTypes: IntArray, secureDolbyVisionDecoder: Boolean): List<String> = buildList {
        if (Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in hdrTypes && secureDolbyVisionDecoder) add("DolbyVision")
        if (Display.HdrCapabilities.HDR_TYPE_HDR10 in hdrTypes) add("Hdr10")
        if (Build.VERSION.SDK_INT >= 29 && Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS in hdrTypes) add("Hdr10Plus")
    }

    private fun readSystemProperty(name: String): String? = try {
        val type = Class.forName("android.os.SystemProperties")
        type.getMethod("get", String::class.java, String::class.java).invoke(null, name, "") as? String
    } catch (_: Exception) { null }
}
