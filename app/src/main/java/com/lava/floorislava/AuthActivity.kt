package com.lava.floorislava

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.lava.floorislava.databinding.ActivityAuthBinding
import kotlinx.coroutines.launch

/**
 * Sign up / log in. Players need an account before reaching the main menu.
 * If this build has no Firebase config, explains that and offers solo play.
 */
class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding
    private var signUpMode = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Cloud.isConfigured(this)) {
            showNotConfigured()
            return
        }
        if (Cloud.isSignedIn(this)) {
            openMenu()
            return
        }

        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.signUpTab.setOnClickListener { setMode(signUp = true) }
        binding.logInTab.setOnClickListener { setMode(signUp = false) }
        binding.submitButton.setOnClickListener { submit() }
        setMode(signUp = true)
    }

    private fun showNotConfigured() {
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.authForm.visibility = View.GONE
        binding.notConfiguredGroup.visibility = View.VISIBLE
        binding.playOfflineButton.setOnClickListener { openMenu() }
    }

    private fun setMode(signUp: Boolean) {
        signUpMode = signUp
        binding.usernameInput.visibility = if (signUp) View.VISIBLE else View.GONE
        binding.submitButton.setText(if (signUp) R.string.auth_sign_up_button else R.string.auth_log_in_button)
        binding.authError.visibility = View.GONE

        val selected = if (signUp) binding.signUpTab else binding.logInTab
        val other = if (signUp) binding.logInTab else binding.signUpTab
        selected.setBackgroundResource(R.drawable.bg_mode_pill_selected)
        selected.setTextColor(ContextCompat.getColor(this, R.color.lava_black))
        other.background = null
        other.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
    }

    private fun submit() {
        val username = binding.usernameInput.text.toString().trim()
        val email = binding.emailInput.text.toString().trim()
        val password = binding.passwordInput.text.toString()

        val problem = when {
            signUpMode && !Cloud.isValidUsername(username) ->
                "Usernames are 3–16 letters, numbers or _"
            !Patterns.EMAIL_ADDRESS.matcher(email).matches() -> "Enter a valid email address"
            password.length < 6 -> "Passwords need at least 6 characters"
            else -> null
        }
        if (problem != null) {
            showError(problem)
            return
        }

        setBusy(true)
        lifecycleScope.launch {
            try {
                if (signUpMode) Cloud.signUp(username, email, password) else Cloud.signIn(email, password)
                openMenu()
            } catch (e: Exception) {
                setBusy(false)
                showError(friendlyMessage(e))
            }
        }
    }

    private fun friendlyMessage(e: Exception): String = when (e) {
        is UsernameTakenException -> "That username is taken — try another one"
        is FirebaseAuthUserCollisionException -> "An account with this email already exists. Log in instead?"
        is FirebaseAuthWeakPasswordException -> "That password is too weak — use at least 6 characters"
        is FirebaseAuthInvalidUserException -> "No account found with this email"
        is FirebaseAuthInvalidCredentialsException -> "Wrong email or password"
        is FirebaseNetworkException -> "No internet connection — check it and try again"
        else -> e.message ?: "Something went wrong — please try again"
    }

    private fun showError(message: String) {
        binding.authError.text = message
        binding.authError.visibility = View.VISIBLE
    }

    private fun setBusy(busy: Boolean) {
        binding.submitButton.isEnabled = !busy
        binding.submitButton.text = if (busy) "" else getString(
            if (signUpMode) R.string.auth_sign_up_button else R.string.auth_log_in_button
        )
        binding.authProgress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.signUpTab.isEnabled = !busy
        binding.logInTab.isEnabled = !busy
    }

    private fun openMenu() {
        // CLEAR_TOP reuses an existing menu rather than stacking a second copy.
        startActivity(
            Intent(this, MainMenuActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }
}
