package com.scriptgod.fireos.avod.player

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaDrm
import android.os.Build
import android.util.Log
import android.view.Display
import java.util.UUID

/** Runtime hardware capabilities, kept separate from Amazon's protocol device type. */
object DeviceCapabilities {
    private const val TAG = "DeviceCapabilities"
    private val WIDEVINE_UUID = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)
    private const val HEVC_MAIN = 1
    private const val HEVC_MAIN10 = 2
    private const val HEVC_LEVEL_4 = 1024
    private const val HEVC_LEVEL_5 = 16384

    data class SecureHevcCapabilities(
        val decoderPresent: Boolean,
        val secureDecoderPresent: Boolean,
        val adaptiveSecureDecoderPresent: Boolean,
        val mainLevel4: Boolean,
        val main10Level4: Boolean,
        val mainLevel5: Boolean,
        val main10Level5: Boolean,
        val secureDolbyVisionFhd: Boolean,
        val secureDolbyVisionUhd: Boolean,
        val secureAv1Main10L4: Boolean,
        val secureAv1Main10L5: Boolean,
        val uhd30Supported: Boolean,
        val maxWidth: Int,
        val maxHeight: Int,
        val secureDecoderNames: List<String>
    ) {
        val supportsHd: Boolean get() = secureDecoderPresent && (mainLevel4 || main10Level4)
        val supportsUhd: Boolean get() = isUhdCapable(
            secureDecoderPresent, adaptiveSecureDecoderPresent, mainLevel5 || main10Level5, uhd30Supported
        )

        fun summary(): String =
            "present=$decoderPresent secure=$secureDecoderPresent adaptive=$adaptiveSecureDecoderPresent " +
            "mainL4=$mainLevel4 main10L4=$main10Level4 mainL5=$mainLevel5 main10L5=$main10Level5 " +
            "DV-FHD=$secureDolbyVisionFhd DV-UHD=$secureDolbyVisionUhd " +
            "AV1-Main10-L4=$secureAv1Main10L4 AV1-Main10-L5=$secureAv1Main10L5 " +
            "3840x2160@30=$uhd30Supported " +
            "max=${maxWidth}x$maxHeight decoders=${secureDecoderNames.joinToString().ifBlank { "none" }}"
    }

    data class DisplayReport(val width: Int, val height: Int, val hdrFormats: List<String>, val protectedBuffers: Boolean) {
        fun summary(): String =
            "mode=${width}x$height HDR=${hdrFormats.joinToString().ifBlank { "none" }} protectedBuffers=$protectedBuffers"
    }

    @Suppress("DEPRECATION")
    fun widevineSecurityLevel(): String {
        val drm = try { MediaDrm(WIDEVINE_UUID) } catch (e: Exception) {
            Log.w(TAG, "Widevine unavailable: ${e.javaClass.simpleName}")
            return "L3"
        }
        return try {
            drm.getPropertyString("securityLevel")
        } catch (e: Exception) {
            Log.w(TAG, "Unable to query Widevine security level: ${e.javaClass.simpleName}")
            "L3"
        } finally { drm.release() }
    }

    /** Enumerate secure, non-tunneled HEVC decoders and verify profile, level and UHD dimensions. */
    fun secureHevcCapabilities(): SecureHevcCapabilities {
        var present = false
        var secure = false
        var adaptive = false
        var mainL4 = false
        var main10L4 = false
        var mainL5 = false
        var main10L5 = false
        var dvFhd = false
        var dvUhd = false
        var av1L4 = false
        var av1L5 = false
        var uhd30 = false
        var maxWidth = 0
        var maxHeight = 0
        val names = mutableListOf<String>()
        return try {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.forEach { info ->
                if (info.isEncoder || info.supportedTypes.none { it.equals("video/hevc", true) }) return@forEach
                present = true
                val caps = runCatching { info.getCapabilitiesForType("video/hevc") }.getOrNull() ?: return@forEach
                val isSecure = caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback)
                val isAdaptive = caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback)
                val requiresTunnel = caps.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback)
                if (!isSecure || requiresTunnel) return@forEach
                secure = true
                adaptive = adaptive || isAdaptive
                names += info.name
                caps.videoCapabilities?.let { video ->
                    maxWidth = maxOf(maxWidth, video.supportedWidths.upper)
                    maxHeight = maxOf(maxHeight, video.supportedHeights.upper)
                    uhd30 = uhd30 || runCatching { video.areSizeAndRateSupported(3840, 2160, 30.0) }.getOrDefault(false)
                }
                caps.profileLevels.forEach { level ->
                    if (level.profile == HEVC_MAIN && level.level >= HEVC_LEVEL_4) mainL4 = true
                    if (level.profile == HEVC_MAIN10 && level.level >= HEVC_LEVEL_4) main10L4 = true
                    if (level.profile == HEVC_MAIN && level.level >= HEVC_LEVEL_5) mainL5 = true
                    if (level.profile == HEVC_MAIN10 && level.level >= HEVC_LEVEL_5) main10L5 = true
                }
                val dvCaps = runCatching { info.getCapabilitiesForType("video/dolby-vision") }.getOrNull()
                if (dvCaps != null && dvCaps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback) &&
                    !dvCaps.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback)) {
                    dvCaps.profileLevels.forEach { level ->
                        if (level.profile == 32 && level.level >= 8) dvFhd = true
                        if (level.profile == 32 && level.level >= 64) dvUhd = true
                    }
                }
                if (Build.VERSION.SDK_INT >= 29) {
                    val av1Caps = runCatching { info.getCapabilitiesForType("video/av01") }.getOrNull()
                    if (av1Caps != null && av1Caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback) &&
                        !av1Caps.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback)) {
                        av1Caps.profileLevels.forEach { level ->
                            if (level.profile == 2 && level.level >= 256) av1L4 = true
                            if (level.profile == 2 && level.level >= 4096) av1L5 = true
                        }
                    }
                }
            }
            SecureHevcCapabilities(present, secure, adaptive, mainL4, main10L4, mainL5, main10L5, dvFhd, dvUhd, av1L4, av1L5, uhd30, maxWidth, maxHeight, names)
        } catch (e: Exception) {
            Log.w(TAG, "HEVC capability enumeration failed: ${e.javaClass.simpleName}")
            SecureHevcCapabilities(present, secure, adaptive, mainL4, main10L4, mainL5, main10L5, dvFhd, dvUhd, av1L4, av1L5, false, maxWidth, maxHeight, names)
        }
    }

    internal fun isUhdCapable(secure: Boolean, adaptive: Boolean, level5: Boolean, supports3840x2160: Boolean): Boolean =
        secure && adaptive && level5 && supports3840x2160

    /** Amazon request values currently verified by this app: HDR10 and Dolby Vision. */
    fun supportedHdrFormats(hdrTypes: IntArray): List<String> = buildList {
        if (Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in hdrTypes) add("DolbyVision")
        if (Display.HdrCapabilities.HDR_TYPE_HDR10 in hdrTypes) add("Hdr10")
    }

    /** Full display-reported HDR list for diagnostics; HDR10+ is not yet sent in playback requests. */
    internal fun reportedHdrFormats(hdrTypes: IntArray): List<String> = buildList {
        if (Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in hdrTypes) add("DolbyVision")
        if (Display.HdrCapabilities.HDR_TYPE_HDR10 in hdrTypes) add("Hdr10")
        if (Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS in hdrTypes) add("Hdr10Plus")
    }

    @Suppress("DEPRECATION")
    fun displayReport(display: Display): DisplayReport {
        val mode = display.mode
        val hdr = display.hdrCapabilities?.supportedHdrTypes ?: intArrayOf()
        val protected = (display.flags and Display.FLAG_SUPPORTS_PROTECTED_BUFFERS) != 0
        return DisplayReport(mode?.physicalWidth ?: 0, mode?.physicalHeight ?: 0, reportedHdrFormats(hdr), protected)
    }

    fun hardwareIdentitySummary(): String =
        "manufacturer=${Build.MANUFACTURER} brand=${Build.BRAND} model=${Build.MODEL} " +
        "device=${Build.DEVICE} product=${Build.PRODUCT} hardware=${Build.HARDWARE} " +
        "android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}"

    /** Compatibility helper now requires a secure HEVC decoder. */
    fun deviceSupportsH265(): Boolean = secureHevcCapabilities().supportsHd
}
