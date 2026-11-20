package com.avod.fireos.avod.ui

import android.content.Intent
import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.avod.fireos.avod.R
import com.avod.fireos.avod.auth.CblLoginFlow
import com.avod.fireos.avod.auth.LoginFlow
import com.avod.fireos.avod.auth.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Login screen — view/controller only.
 * Protocol logic lives in [LoginFlow]; token persistence lives in [TokenStore].
 */
class LoginActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LoginActivity"

        /** Delegate to TokenStore so callers that still reference LoginActivity.findTokenFile keep working. */
        fun findTokenFile(context: android.content.Context) = TokenStore.findTokenFile(context)
    }

    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var etMfa: EditText
    private lateinit var mfaContainer: LinearLayout
    private lateinit var cbShowPassword: CheckBox
    private lateinit var btnLogin: Button
    private lateinit var btnSkip: Button
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar

    private val scopeJob = Job()
    private val scope = CoroutineScope(Dispatchers.Main + scopeJob)

    private val tokenStore by lazy { TokenStore(this) }
    private val loginFlow  by lazy { LoginFlow() }
    private val cblFlow    by lazy { CblLoginFlow() }
    private var cblJob: Job? = null

    private var needsMfa = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (tokenStore.isValid()) {
            Log.w(TAG, "Valid token found, skipping login")
            launchMain()
            return
        }

        setContentView(R.layout.activity_login)

        etEmail       = findViewById(R.id.et_email)
        etPassword    = findViewById(R.id.et_password)
        etMfa         = findViewById(R.id.et_mfa)
        etPassword.isSaveEnabled = false
        etMfa.isSaveEnabled = false
        mfaContainer  = findViewById(R.id.mfa_container)
        cbShowPassword = findViewById(R.id.cb_show_password)
        btnLogin      = findViewById(R.id.btn_login)
        btnSkip       = findViewById(R.id.btn_skip)
        tvStatus      = findViewById(R.id.tv_status)
        progressBar   = findViewById(R.id.progress_bar)

        cbShowPassword.setOnCheckedChangeListener { _, isChecked ->
            val cursorPos = etPassword.selectionEnd
            etPassword.transformationMethod = if (isChecked)
                HideReturnsTransformationMethod.getInstance()
            else
                PasswordTransformationMethod.getInstance()
            etPassword.setSelection(cursorPos.coerceAtMost(etPassword.text.length))
        }

        btnLogin.setOnClickListener { onLoginClicked() }
        findViewById<Button>(R.id.btn_code_login).setOnClickListener { startCblLogin() }
        btnSkip.setOnClickListener {
            if (tokenStore.isValid()) launchMain()
            else showStatus("No valid token found")
        }
        btnSkip.visibility = if (tokenStore.isValid()) View.VISIBLE else View.GONE
    }

    private fun onLoginClicked() {
        val email = etEmail.text.toString().trim()
        val password = etPassword.text.toString()

        if (needsMfa) {
            val mfaCode = etMfa.text.toString().trim()
            if (mfaCode.isEmpty()) { showStatus("Please enter MFA code"); return }
            doMfa(mfaCode)
        } else {
            if (email.isEmpty() || password.isEmpty()) {
                showStatus("Please enter email and password"); return
            }
            doLogin(email, password)
        }
    }

    private fun doLogin(email: String, password: String) {
        showLoading(true); showStatus("")
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { loginFlow.performFullLogin(email, password) }
                handleResult(result)
            } catch (e: Exception) {
                Log.w(TAG, "Login error (${e.javaClass.simpleName})")
                showStatus("Login error: ${e.message}")
            } finally {
                etPassword.setText("")
                showLoading(false)
            }
        }
    }

    private fun doMfa(mfaCode: String) {
        showLoading(true); showStatus("")
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { loginFlow.submitMfaCode(mfaCode) }
                handleResult(result)
            } catch (e: Exception) {
                Log.w(TAG, "MFA error (${e.javaClass.simpleName})")
                showStatus("MFA error: ${e.message}")
            } finally {
                etMfa.setText("")
                showLoading(false)
            }
        }
    }

    private fun handleResult(result: LoginFlow.Result) {
        when {
            result.needsMfa -> {
                needsMfa = true
                mfaContainer.visibility = View.VISIBLE
                btnLogin.text = "Verify"
                showStatus(result.mfaMessage ?: "Enter verification code")
                tvStatus.setTextColor(0xFF00A8E0.toInt())
                etMfa.requestFocus()
            }
            result.authCode != null -> {
                showStatus("Registering device...")
                tvStatus.setTextColor(0xFF00A8E0.toInt())
                tvStatus.visibility = View.VISIBLE
                registerDevice(result.authCode)
            }
            else -> showStatus(result.error ?: "Login failed")
        }
    }

    /**
     * Code-Based Linking: ask Amazon for a code, show the user amazon.com/code + the code, then
     * poll until they approve on their phone. No password or email MFA — sidesteps the throttle.
     */
    private fun startCblLogin() {
        cblJob?.cancel()
        progressBar.visibility = View.VISIBLE
        showStatus("Getting a sign-in code...")
        tvStatus.setTextColor(0xFF00A8E0.toInt())
        cblJob = scope.launch {
            try {
                val pair = withContext(Dispatchers.IO) { cblFlow.createCodePair() }
                progressBar.visibility = View.GONE
                showStatus("On your phone or computer, go to:\n${pair.verificationUri}\n\nand enter code:  ${pair.userCode}\n\nWaiting for approval...")
                tvStatus.setTextColor(0xFF00A8E0.toInt())

                val deadline = System.currentTimeMillis() + pair.expiresInSeconds * 1000L
                while (System.currentTimeMillis() < deadline) {
                    delay(pair.intervalSeconds.coerceAtLeast(2) * 1000L)
                    when (val r = withContext(Dispatchers.IO) { cblFlow.pollOnce(pair.deviceCode) }) {
                        CblLoginFlow.PollResult.Pending -> { /* keep waiting */ }
                        is CblLoginFlow.PollResult.Success -> {
                            withContext(Dispatchers.IO) { tokenStore.save(r.tokenData) }
                            Log.w(TAG, "CBL login successful, token saved")
                            showStatus("Login successful!")
                            tvStatus.setTextColor(0xFF00CC00.toInt())
                            launchMain()
                            return@launch
                        }
                        is CblLoginFlow.PollResult.Error -> {
                            showStatus("Code sign-in failed: ${r.message}")
                            tvStatus.setTextColor(0xFFFF4444.toInt())
                            return@launch
                        }
                    }
                }
                showStatus("Code expired — tap 'Sign in with a code' to try again.")
                tvStatus.setTextColor(0xFFFF4444.toInt())
            } catch (e: Exception) {
                Log.w(TAG, "CBL error (${e.javaClass.simpleName})")
                progressBar.visibility = View.GONE
                showStatus("Code sign-in error: ${e.message}")
                tvStatus.setTextColor(0xFFFF4444.toInt())
            }
        }
    }

    private fun registerDevice(authCode: String) {
        showLoading(true)
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val tokenData = loginFlow.performDeviceRegistration(authCode)
                    tokenStore.save(tokenData)
                }
                Log.w(TAG, "Device registered and token saved")
                showStatus("Login successful!")
                tvStatus.setTextColor(0xFF00CC00.toInt())
                tvStatus.visibility = View.VISIBLE
                etPassword.setText("")
                launchMain()
            } catch (e: Exception) {
                Log.w(TAG, "Registration failed (${e.javaClass.simpleName})")
                showStatus("Registration failed: ${e.message}")
            } finally { showLoading(false) }
        }
    }

    // --- UI helpers ---

    private fun showLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        btnLogin.isEnabled = !loading
    }

    private fun showStatus(message: String) {
        if (message.isEmpty()) {
            tvStatus.visibility = View.GONE
        } else {
            tvStatus.text = message
            tvStatus.setTextColor(0xFFFF4444.toInt())
            tvStatus.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scopeJob.cancel()
    }

    private fun launchMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
