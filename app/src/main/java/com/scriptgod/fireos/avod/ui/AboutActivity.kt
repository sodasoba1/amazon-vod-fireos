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
        findViewById<TextView>(R.id.tv_version).text = "AVOD for Fire OS — Amazon Video on Demand, playback capabilities, and active device information."
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

    private fun setupQualitySection() {
        val profile = DeviceCapabilities.inspect(this)
        val hdrFormats = profile.amazonHdrFormats()
        val hdrLabel = if (hdrFormats.isEmpty()) "No HDR output" else hdrFormats.joinToString(" + ")

        val btnHd = findViewById<Button>(R.id.btn_quality_hd)
        val btnHdH265 = findViewById<Button>(R.id.btn_quality_hd_h265)
        val btnUhd = findViewById<Button>(R.id.btn_quality_uhd)
        // Quality tiers are now negotiated by Amazon. Remove the old fixed-tier controls.
        btnHd.visibility = android.view.View.GONE
        btnHdH265.visibility = android.view.View.GONE
        btnUhd.visibility = android.view.View.GONE

        findViewById<TextView>(R.id.tv_codec_badge).text =
            if (profile.supportsHevcMainL4) "Secure adaptive HEVC ready" else "H264 baseline"
        findViewById<TextView>(R.id.tv_hdr_badge).text = hdrLabel
        findViewById<TextView>(R.id.tv_quality_chip).text =
            if (profile.supportsUhd) "UHD-capable · automatic" else "Automatic stream selection"
        findViewById<TextView>(R.id.tv_h265_support).text =
            "Widevine: " + profile.widevineSecurityLevel +
                "  ·  HDCP: " + if (profile.supportsHdcp) "Yes" else "Not confirmed" +
                "  ·  Display: " + profile.maxVideoWidth + "x" + profile.maxVideoHeight +
                "  ·  HEVC Main L4: " + profile.supportsHevcMainL4 +
                "  ·  Main10: " + (profile.supportsHevcMain10L4 || profile.supportsHevcMain10L5) +
                "  ·  AV1 Main10: " + (profile.supportsAv1Main10L4 || profile.supportsAv1Main10L5)
        findViewById<TextView>(R.id.tv_quality_note).text =
            "Quality is not pinned to SD, HD or UHD. AVOD advertises the secure codecs and HDR formats " +
                "it can verify; Amazon selects the eligible stream ladder and Media3 adapts between " +
                "representations. Actual quality still depends on the title, account, DRM and network."
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
