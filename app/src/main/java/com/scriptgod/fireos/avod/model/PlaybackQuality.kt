package com.scriptgod.fireos.avod.model

/**
 * Device capability declarations sent to Amazon for both manifest and licence requests.
 * Video quality is deliberately not pinned: Amazon returns the eligible ladder and Media3
 * adapts among its representations.
 */
data class PlaybackQuality(
    val codecOverride: String,
    val hdrOverride: String
) {
    companion object {
        val AUTO = PlaybackQuality(codecOverride = "H264", hdrOverride = "None")
    }
}
