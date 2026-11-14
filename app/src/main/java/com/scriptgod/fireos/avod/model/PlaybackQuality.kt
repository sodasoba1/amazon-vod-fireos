package com.scriptgod.fireos.avod.model

/**
 * Quality parameters sent identically to GetPlaybackResources manifest and licence requests.
 * Mirrors the current Kodi Amazon VOD implementation (network.py:185-239).
 *
 * HD and UHD are Amazon server-side quality tiers, not fixed pixel dimensions. The returned
 * MPD is authoritative: title rights, territory, subscription, DRM and CDN policy can all
 * reduce the available representations.
 */
data class PlaybackQuality(
    val videoQuality: String,   // "SD", "HD", or "UHD"
    val codecOverride: String,  // "H264" or "H264,H265"
    val hdrOverride: String     // "None", "DolbyVision", "Hdr10", or both
) {
    companion object {
        /** SD H264 SDR fallback for Widevine L3 devices. */
        val SD = PlaybackQuality("SD", "H264", "None")

        /** HD H264 SDR request. Previous manifest tests observed 720p, not a guaranteed cap. */
        val HD = PlaybackQuality("HD", "H264", "None")

        /** HD tier with both H264 and H265 declared as supported codecs. */
        val HD_H265 = PlaybackQuality("HD", "H264,H265", "None")

        /** UHD request with no HDR formats (SDR if Amazon offers that combination). */
        val UHD_SDR = PlaybackQuality("UHD", "H264,H265", "None")

        /** UHD request with the HDR formats actually reported by the connected display. */
        val UHD_HDR = PlaybackQuality("UHD", "H264,H265", "DolbyVision,Hdr10")

        const val PREF_KEY = "video_quality"

        fun fromPrefValue(value: String?) = when (value) {
            "HD_H265" -> HD_H265
            "UHD_HDR" -> UHD_HDR
            else -> HD
        }

        /** Dynamic UHD HDR overrides and the UHD SDR fallback retain the user's UHD choice. */
        fun toPrefValue(q: PlaybackQuality) = when {
            q.videoQuality == "UHD" -> "UHD_HDR"
            q == HD_H265 -> "HD_H265"
            else -> "HD"
        }
    }
}
