# AVOD playback negotiation audit

Status: development branch only; not a Fire TV playback sign-off.

## 1. Prime Video reference trace

The checked-in Java decompilation contains the Prime device-property and quality-policy components, but does not contain the GetPlaybackResources / PlaybackUrlsCommonParams request builder itself. The older APK analysis notes identify a PlaybackUrlsCommonParams.smali file and the Prime quality configuration enum, but the corresponding implementation is not present in this Java source tree. Therefore exact request-parameter parity cannot honestly be claimed from the current decompilation.

Confirmed reference behaviour:
- Prime's VideoQualityManager.quality defaults to StreamingConnectionSetting.BEST.
- Prime's decoder provider accepts video decoders only when adaptive-playback and secure-playback are supported, and excludes decoders requiring tunneled playback unless the device is configured for tunneling.
- Prime checks HEVC Main/Main10 and Dolby Vision profiles/levels, not merely MIME presence.
- Prime derives UHD capability from a 3840x2160 display plus secure HEVC Main Level 5 support.
- Prime derives HEVC support from secure HEVC Main Level 4 support.
- Prime combines display HDR support with a compatible secure Dolby Vision decoder; HDR10 and HDR10+ are display properties.
- Prime's display HDCP property requires both secure output and protected-buffer support.
- Prime's Android TV DTID mapping uses androidtv-primevideo-dtid-config, an empty default DTID, and actual build/device properties. That config is specifically named for Android TV. AVOD retains the known generic Android token-registration DTID until a Fire TV-specific mapping is proven; it no longer sends an NVIDIA Shield model, fingerprint or User-Agent.

Sources in sodasoba1/Amafirebat-decomp:
- DecoderCapabilitiesProvider.java
- DisplayPropertiesProvider.java
- DeviceProperties synthetic lambdas 41 through 51
- DtidRequester.java
- DtidRequestUriFactory.java
- FirebatApplicationModule_Companion_ProvideDtidAcmEndpointFactory.java
- VideoQualityManager.java
- StreamingConnectionSetting.java

## 2. Device capability reporting

AVOD now reports secure/adaptive codec support, HEVC Main/Main10 levels, Dolby Vision profiles, AV1 Main10 levels, display mode dimensions, HDR10/HDR10+ and secure-output/protected-buffer flags. Widevine query failure is represented as UNKNOWN; it is no longer silently converted into L3.

The request advertises H264 plus HEVC only when the secure/adaptive HEVC Main Level 4 profile is present. HDR formats are based on display and decoder support. The server remains responsible for eligibility and quality selection.

## 3. Automatic quality selection

Removed the persisted SD/HD/UHD presets and the deviceVideoQualityOverride parameter. AVOD no longer chooses a fixed tier or forces SD solely because a Widevine query failed. It sends verified codec/HDR capabilities and allows Amazon to choose the returned ladder; Media3 then adapts among the representations in the MPD.

This is a deliberate server-default strategy, not proof that Prime sends an identical parameter set. The exact Prime request builder is absent from the current Java decompilation and still needs confirmation from a matching Fire TV/Prime APK or a controlled request comparison.

## 4. Manifest and licence requests

The manifest and licence requests now use the same codec/HDR capability declarations. The licence URL also includes retURL=True, matching the API-map reference. deviceVideoQualityOverride is absent from both requests.

The Widevine challenge wrapper and JSON licence decoding are unchanged. The next device test must confirm the server accepts the resulting licence URL and the session can decrypt media.

## 5. MPD and Media3

Removed the SegmentList-to-SegmentBase rewrite and the single-representation SIDX-size assumption. AVOD now preserves Amazon's segment addressing and only injects the descriptive-audio Role marker when needed. If XML enhancement fails, the original MPD is used.

Media3's DefaultTrackSelector still has no hard maximum video resolution. The existing transfer-listener forwarding fix remains, so the bandwidth meter can observe segment transfers. This does not prove the phone's low-resolution symptom is fixed: inspect the returned MPD ladder, selected format, measured bandwidth and licence result separately.

## 6. Ads, tracking, telemetry and validation

The app's Gradle dependencies contain no advertising, Firebase Analytics, Crashlytics or third-party analytics SDK. The manifest requests only Internet and network-state permissions. Local diagnostic logging is not uploaded by an analytics SDK.

UpdateStream and PES Start/Update/Stop are Amazon playback-session/progress reporting. They affect playback session state and watch-progress persistence, so they are retained rather than misclassified as disposable advertising telemetry. No client-side ad SDK was found in the app. This does not establish that Amazon will never return ad-supported content or server-side ad markers; that requires testing the actual returned playback resources.

Required validation before Fire TV sign-off:
1. Build and unit tests pass.
2. On the target Fire TV Stick, record the actual Build identity, DTID used, Widevine level, HDCP flags, display mode, secure decoder profiles and advertised codec/HDR values.
3. Compare the GetPlaybackResources response and returned MPD with Prime Video on the same device/title/account/territory.
4. Confirm the licence URL, Widevine challenge/response and successful decryption.
5. Confirm ExoPlayer sees the full eligible representation ladder, measures bandwidth, and switches representations during playback.
6. Verify watch progress, seeking, subtitles, audio tracks and restart/CDN failover.

No APK from this branch should be treated as Fire Stick-ready until those device tests pass.
