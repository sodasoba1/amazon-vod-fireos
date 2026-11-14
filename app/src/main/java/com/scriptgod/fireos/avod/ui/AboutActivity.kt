package com.scriptgod.fireos.avod.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.scriptgod.fireos.avod.R
import com.scriptgod.fireos.avod.data.ProgressRepository
import com.scriptgod.fireos.avod.model.PlaybackQuality
import com.scriptgod.fireos.avod.player.DeviceCapabilities
import com.scriptgod.fireos.avod.model.TokenData
import java.io.File

class AboutActivity : AppCompatActivity() {

    companion object {
        // Must match PlayerActivity.PREF_AUDIO_PASSTHROUGH
        private const val PREF_AUDIO_PASSTHROUGH = "audio_passthrough"
    }

    private lateinit var btnBack: Button
    private var appliedInitialButtonFocus: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_about)

        btnBack = findViewById(R.id.btn_about_back)
        btnBack.setOnClickListener { UiTransitions.close(this) }

        // Version
        val versionName = packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        findViewById<TextView>(R.id.tv_version).text = "Inspect ScriptGod's AmazonVOD for FireOS, playback capability, and the active device token."
        findViewById<TextView>(R.id.tv_version_value).text = versionName
        findViewById<TextView>(R.id.tv_package).text = packageName
        findViewById<TextView>(R.id.tv_version_chip).text = "Version $versionName"

        // Account info from token file
        val tokenFile = LoginActivity.findTokenFile(this)
        if (tokenFile != null) {
            try {
                val token = Gson().fromJson(tokenFile.readText(), TokenData::class.java)
                val deviceId = token.deviceId
                val masked = if (deviceId.length > 12) deviceId.take(8) + "…" + deviceId.takeLast(4)
                             else deviceId
                findViewById<TextView>(R.id.tv_device_id).text = masked
            } catch (e: Exception) {
                findViewById<TextView>(R.id.tv_device_id).text = "error reading token"
            }
            findViewById<TextView>(R.id.tv_token_location).text = "app-private storage"
            findViewById<TextView>(R.id.tv_token_chip).text = "Token active"
            findViewById<TextView>(R.id.tv_token_status).text = "A device token is present and the app can launch directly into the catalog."
        } else {
            findViewById<TextView>(R.id.tv_device_id).text = "no token"
            findViewById<TextView>(R.id.tv_token_location).text = "—"
            findViewById<TextView>(R.id.tv_token_chip).text = "Token missing"
            findViewById<TextView>(R.id.tv_token_status).text = "No token file was found. The app will require login before browsing content."
        }

        // Video quality setting
        setupQualitySection()

        // Audio passthrough setting
        setupAudioPassthroughSection()

        // Sign out
        findViewById<Button>(R.id.btn_sign_out).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Sign Out")
                .setMessage("Sign out and return to the login screen?")
                .setPositiveButton("Sign Out") { _, _ -> performLogout() }
                .setNegativeButton("Cancel", null)
                .show()
        }

        UiMotion.revealFresh(
            findViewById(R.id.about_header),
            findViewById(R.id.about_chip_row),
            findViewById(R.id.about_panel_columns)
        )
    }

    @Suppress("DEPRECATION")
    private fun setupQualitySection() {
        // H265 decoder + display HDR capability indicators
        val supportsH265 = DeviceCapabilities.deviceSupportsH265()
        val hdrTypes = windowManager.defaultDisplay.hdrCapabilities?.supportedHdrTypes ?: intArrayOf()
        val hdrFormats = DeviceCapabilities.supportedHdrFormats(hdrTypes)
        val hdrLabel = when {
            hdrFormats.contains("DolbyVision") && hdrFormats.contains("Hdr10") -> "Yes (DV + HDR10)"
            hdrFormats.contains("DolbyVision") -> "Yes (Dolby Vision)"
            hdrFormats.contains("Hdr10") -> "Yes (HDR10)"
            else -> "No HDR10/Dolby Vision"
        }
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val btnHd     = findViewById<Button>(R.id.btn_quality_hd)
        val btnHdH265 = findViewById<Button>(R.id.btn_quality_hd_h265)
        val btnUhd    = findViewById<Button>(R.id.btn_quality_uhd)
        val tvNote    = findViewById<TextView>(R.id.tv_quality_note)
        val tvCodecBadge = findViewById<TextView>(R.id.tv_codec_badge)
        val tvHdrBadge = findViewById<TextView>(R.id.tv_hdr_badge)
        val tvQualityChip = findViewById<TextView>(R.id.tv_quality_chip)

        val supportsDisplayHdr = hdrFormats.isNotEmpty()
        tvCodecBadge.text = if (supportsH265) "HEVC ready" else "HEVC unavailable"
        tvHdrBadge.text = if (supportsDisplayHdr) "HDR: ${hdrFormats.joinToString(" + ")}" else "No HDR10/DV output"
        tvQualityChip.text = when {
            supportsH265 && supportsDisplayHdr -> "4K HDR capable"
            supportsH265 -> "4K SDR capable"
            else -> "HD only"
        }
        findViewById<TextView>(R.id.tv_h265_support).text =
            "Device H265/HEVC: ${if (supportsH265) "Yes" else "No"}  ·  Display HDR: $hdrLabel"

        btnHdH265.isEnabled = supportsH265
        btnUhd.isEnabled = supportsH265
        btnHdH265.alpha = if (supportsH265) 1f else 0.45f
        btnUhd.alpha = if (supportsH265) 1f else 0.45f

        tvNote.text = when {
            !supportsH265 ->
                "This device has no HEVC decoder, so the UHD request is unavailable. HD H264 remains available."
            !supportsDisplayHdr ->
                "The 4K preset still requests UHD, but sends no HDR formats because the display reports neither HDR10 nor Dolby Vision. Amazon may return a lower tier if UHD SDR is unavailable."
            else ->
                "The 4K preset requests UHD and only the HDR formats reported by the connected display. Amazon may still return a lower tier depending on title rights, DRM and availability."
        }

        fun updateButtons(selected: PlaybackQuality) {
            btnHd.isSelected = selected == PlaybackQuality.HD
            btnHdH265.isSelected = selected == PlaybackQuality.HD_H265
            btnUhd.isSelected = selected == PlaybackQuality.UHD_HDR
        }

        // Apply initial highlight
        val initialQuality = PlaybackQuality.fromPrefValue(prefs.getString(PlaybackQuality.PREF_KEY, null))
        updateButtons(initialQuality)

        fun save(q: PlaybackQuality) {
            prefs.edit().putString(PlaybackQuality.PREF_KEY, PlaybackQuality.toPrefValue(q)).apply()
            updateButtons(q)
            Toast.makeText(this, "Quality set to ${btnLabel(q)} — takes effect on next playback", Toast.LENGTH_SHORT).show()
        }

        btnHd.setOnClickListener     { save(PlaybackQuality.HD) }
        btnHdH265.setOnClickListener { save(PlaybackQuality.HD_H265) }
        btnUhd.setOnClickListener    { save(PlaybackQuality.UHD_HDR) }

    }

    private fun setupAudioPassthroughSection() {
        val caps = androidx.media3.exoplayer.audio.AudioCapabilities.getCapabilities(this)
        val supportsAc3  = caps.supportsEncoding(androidx.media3.common.C.ENCODING_AC3)
        val supportsEac3 = caps.supportsEncoding(androidx.media3.common.C.ENCODING_E_AC3)
        val supportsAny  = supportsAc3 || supportsEac3

        val prefs     = getSharedPreferences("settings", MODE_PRIVATE)
        val btnOff    = findViewById<Button>(R.id.btn_passthrough_off)
        val btnOn     = findViewById<Button>(R.id.btn_passthrough_on)
        val tvBadge   = findViewById<TextView>(R.id.tv_passthrough_badge)
        val tvSupport = findViewById<TextView>(R.id.tv_passthrough_support)
        val tvNote    = findViewById<TextView>(R.id.tv_passthrough_note)

        tvBadge.text = when {
            supportsAc3 && supportsEac3 -> "AC3 + EAC3 capable"
            supportsAc3  -> "AC3 capable"
            supportsEac3 -> "EAC3 capable"
            else         -> "Passthrough unavailable"
        }
        val fmts = listOfNotNull(
            if (supportsAc3)  "Dolby Digital (AC3)"   else null,
            if (supportsEac3) "Dolby Digital+ (EAC3)" else null
        )
        tvSupport.text = "HDMI audio passthrough: " +
            if (fmts.isNotEmpty()) fmts.joinToString(", ") else "not supported on this output"

        btnOn.isEnabled = supportsAny
        btnOn.alpha = if (supportsAny) 1f else 0.45f

        fun updateButtons(on: Boolean) {
            btnOff.isSelected = !on
            btnOn.isSelected  = on
        }

        val initial = prefs.getBoolean(PREF_AUDIO_PASSTHROUGH, false) && supportsAny
        updateButtons(initial)

        tvNote.text = if (!supportsAny)
            "This output does not report Dolby passthrough support. Keep Off to use PCM stereo."
        else
            "When On, encoded Dolby audio is sent directly to your AV receiver. " +
            "Volume control moves to the receiver. Takes effect on next playback session."

        fun save(on: Boolean) {
            val valueToSave = on && supportsAny
            prefs.edit().putBoolean(PREF_AUDIO_PASSTHROUGH, valueToSave).apply()
            updateButtons(valueToSave)
            Toast.makeText(
                this,
                if (on) "Passthrough on — takes effect on next playback"
                else    "Passthrough off — takes effect on next playback",
                Toast.LENGTH_SHORT
            ).show()
        }

        btnOff.setOnClickListener { save(false) }
        btnOn.setOnClickListener  { save(true)  }
    }

    private fun btnLabel(q: PlaybackQuality) = when {
        q.videoQuality == "UHD" -> "4K"
        q == PlaybackQuality.HD_H265 -> "H265"
        else -> "HD H264"
    }

    private fun performLogout() {
        ProgressRepository.init(applicationContext)
        // Delete the app-private token.
        File(filesDir, ".device-token").delete()
        // Clear local progress cache
        ProgressRepository.clear()

        Toast.makeText(this, "Signed out", Toast.LENGTH_SHORT).show()

        // Return to login, clear back stack
        val intent = Intent(this, LoginActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finish()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !appliedInitialButtonFocus) {
            appliedInitialButtonFocus = true
            btnBack.post { btnBack.requestFocus() }
        }
    }
}
