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
    private const val HEVC_LEVEL_5 = 16384

    data class SecureHevcCapabilities(
        val decoderPresent: Boolean,
        val secureDecoderPresent: Boolean,
        val adaptiveSecureDecoderPresent: Boolean,
        val mainLevel5: Boolean,
        val main10Level5: Boolean,
        val uhd30Supported: Boolean,
        val maxWidth: Int,
        val maxHeight: Int,
        val secureDecoderNames: List<String>
    ) {
        val supportsHd: Boolean get() = secureDecoderPresent
        val supportsUhd: Boolean get() = isUhdCapable(
            secureDecoderPresent, adaptiveSecureDecoderPresent, mainLevel5 || main10Level5, uhd30Supported
        )

        fun summary(): String =
            "present=$decoderPresent secure=$secureDecoderPresent adaptive=$adaptiveSecureDecoderPresent " +
            "mainL5=$mainLevel5 main10L5=$main10Level5 3840x2160@30=$uhd30Supported " +
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
        var mainL5 = false
        var main10L5 = false
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
                    if (level.profile == HEVC_MAIN && level.level >= HEVC_LEVEL_5) mainL5 = true
                    if (level.profile == HEVC_MAIN10 && level.level >= HEVC_LEVEL_5) main10L5 = true
                }
            }
            SecureHevcCapabilities(present, secure, adaptive, mainL5, main10L5, uhd30, maxWidth, maxHeight, names)
        } catch (e: Exception) {
            Log.w(TAG, "HEVC capability enumeration failed: ${e.javaClass.simpleName}")
            SecureHevcCapabilities(present, secure, adaptive, mainL5, main10L5, false, maxWidth, maxHeight, names)
        }
    }

    internal fun isUhdCapable(secure: Boolean, adaptive: Boolean, level5: Boolean, supports3840x2160: Boolean): Boolean =
        secure && adaptive && level5 && supports3840x2160

    /** Only report HDR strings that the current playback request builder supports. */
    fun supportedHdrFormats(hdrTypes: IntArray): List<String> = buildList {
        if (Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in hdrTypes) add("DolbyVision")
        if (Display.HdrCapabilities.HDR_TYPE_HDR10 in hdrTypes) add("Hdr10")
    }

    @Suppress("DEPRECATION")
    fun displayReport(display: Display): DisplayReport {
        val mode = display.mode
        val hdr = display.hdrCapabilities?.supportedHdrTypes ?: intArrayOf()
        val protected = (display.flags and Display.FLAG_SUPPORTS_PROTECTED_BUFFERS) != 0
        return DisplayReport(mode?.physicalWidth ?: 0, mode?.physicalHeight ?: 0, supportedHdrFormats(hdr), protected)
    }

    fun hardwareIdentitySummary(): String =
        "manufacturer=${Build.MANUFACTURER} brand=${Build.BRAND} model=${Build.MODEL} " +
        "device=${Build.DEVICE} product=${Build.PRODUCT} hardware=${Build.HARDWARE} " +
        "android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}"

    /** Compatibility helper now requires a secure HEVC decoder. */
    fun deviceSupportsH265(): Boolean = secureHevcCapabilities().supportsHd
}
