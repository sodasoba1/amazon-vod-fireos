package com.scriptgod.fireos.avod.model

/**
 * Device capability declarations sent to Amazon for manifest and licence requests.
 * No fixed video-quality tier is selected here.
 */
data class PlaybackQuality(
    val codecOverride: String,
    val hdrOverride: String
) {
    fun amazonCapabilityQueryString(): String =
        "&deviceVideoCodecOverride=$codecOverride&deviceHdrFormatsOverride=$hdrOverride"

    companion object {
        val AUTO = PlaybackQuality(codecOverride = "H264", hdrOverride = "None")
    }
}
