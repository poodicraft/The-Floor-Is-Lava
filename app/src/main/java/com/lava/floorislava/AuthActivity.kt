package com.lava.floorislava

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.lifecycleScope
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.lava.floorislava.databinding.ActivityAuthBinding
import kotlinx.coroutines.launch

/**
 * Sign up / log in with Google or email. Players need an account before
 * reaching the main menu; a first-time Google player also picks a username.
 * If this build has no Firebase config, explains that and offers solo play.
 */
class AuthActivity : AppCompatActivity() {

    private enum class Mode { SIGN_UP, LOG_IN, CHOOSE_NAME }

    private lateinit var binding: ActivityAuthBinding
    private var mode = Mode.SIGN_UP

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!Cloud.isConfigured(this)) {
            binding.authForm.visibility = View.GONE
            binding.notConfiguredGroup.visibility = View.VISIBLE
            binding.playOfflineButton.setOnClickListener { openMenu() }
            return
        }

        binding.googleButton.setOnClickListener { signInWithGoogle() }
        binding.signUpTab.setOnClickListener { setMode(Mode.SIGN_UP) }
        binding.logInTab.setOnClickListener { setMode(Mode.LOG_IN) }
        binding.submitButton.setOnClickListener { submit() }
        setMode(Mode.SIGN_UP)

        if (Cloud.isSignedIn(this)) {
            // Already signed in: straight to the menu, unless this account
            // still has no username (e.g. the app closed before picking one).
            setBusy(true)
            lifecycleScope.launch { finishSignIn() }
        }
    }

    private fun setMode(newMode: Mode) {
        mode = newMode
        val choosing = newMode == Mode.CHOOSE_NAME
        binding.googleButton.visibility = if (choosing) View.GONE else View.VISIBLE
        binding.orDivider.visibility = if (choosing) View.GONE else View.VISIBLE
        binding.authTabs.visibility = if (choosing) View.GONE else View.VISIBLE
        binding.chooseNameText.visibility = if (choosing) View.VISIBLE else View.GONE
        binding.emailInput.visibility = if (choosing) View.GONE else View.VISIBLE
        binding.passwordInput.visibility = if (choosing) View.GONE else View.VISIBLE
        binding.usernameInput.visibility = if (newMode == Mode.LOG_IN) View.GONE else View.VISIBLE
        binding.submitButton.text = getString(submitLabel())
        binding.authError.visibility = View.GONE

        if (!choosing) {
            val selected = if (newMode == Mode.SIGN_UP) binding.signUpTab else binding.logInTab
            val other = if (newMode == Mode.SIGN_UP) binding.logInTab else binding.signUpTab
            selected.setBackgroundResource(R.drawable.bg_mode_pill_selected)
            selected.setTextColor(ContextCompat.getColor(this, R.color.lava_black))
            other.background = null
            other.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    private fun submitLabel(): Int = when (mode) {
        Mode.SIGN_UP -> R.string.auth_sign_up_button
        Mode.LOG_IN -> R.string.auth_log_in_button
        Mode.CHOOSE_NAME -> R.string.auth_continue_button
    }

    private fun submit() {
        val username = binding.usernameInput.text.toString().trim()
        val email = binding.emailInput.text.toString().trim()
        val password = binding.passwordInput.text.toString()

        val problem = when {
            mode != Mode.LOG_IN && !Cloud.isValidUsername(username) ->
                "Usernames are 3–16 letters, numbers or _"
            mode != Mode.CHOOSE_NAME && !Patterns.EMAIL_ADDRESS.matcher(email).matches() ->
                "Enter a valid email address"
            mode != Mode.CHOOSE_NAME && password.length < 6 -> "Passwords need at least 6 characters"
            else -> null
        }
        if (problem != null) {
            showError(problem)
            return
        }

        setBusy(true)
        lifecycleScope.launch {
            try {
                when (mode) {
                    Mode.SIGN_UP -> Cloud.signUp(username, email, password)
                    Mode.LOG_IN -> Cloud.signIn(email, password)
                    Mode.CHOOSE_NAME -> Cloud.claimUsername(username)
                }
                finishSignIn()
            } catch (e: Exception) {
                setBusy(false)
                showError(friendlyMessage(e))
            }
        }
    }

    private fun signInWithGoogle() {
        val webClientId = Cloud.googleWebClientId(this)
        if (webClientId == null) {
            showError(getString(R.string.auth_google_not_set_up))
            return
        }
        setBusy(true)
        lifecycleScope.launch {
            try {
                val idToken = GoogleSignIn.requestIdToken(this@AuthActivity, webClientId)
                Cloud.signInWithGoogle(idToken)
                finishSignIn()
            } catch (_: GetCredentialCancellationException) {
                setBusy(false) // closed the account chooser
            } catch (_: NoCredentialException) {
                setBusy(false)
                showError("There's no Google account on this phone. Add one in Settings → Accounts, or use email below.")
            } catch (e: GetCredentialException) {
                setBusy(false)
                showError(googleErrorMessage(e))
            } catch (e: Exception) {
                setBusy(false)
                showError(friendlyMessage(e))
            }
        }
    }

    /** After any sign-in: pick a username if the account has none, else open the menu. */
    private suspend fun finishSignIn() {
        val needsName = try {
            Cloud.needsUsername()
        } catch (_: Exception) {
            false // offline: the menu still works, and we'll ask again next launch
        }
        if (needsName) {
            setBusy(false)
            setMode(Mode.CHOOSE_NAME)
        } else {
            openMenu()
        }
    }

    /** Google's "developer console" error means the Firebase side isn't finished (SHA-1 / provider). */
    private fun googleErrorMessage(e: GetCredentialException): String {
        val message = e.message.orEmpty()
        return if (message.contains("28444") || message.contains("Developer console", ignoreCase = true)) {
            getString(R.string.auth_google_not_set_up)
        } else {
            "Google sign-in didn't work: ${message.ifBlank { e.type }}"
        }
    }

    private fun friendlyMessage(e: Exception): String = when (e) {
        is UsernameTakenException -> "That username is taken — try another one"
        is FirebaseAuthUserCollisionException ->
            "An account with this email already exists. Log in with the method you used before."
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
        binding.submitButton.text = if (busy) "" else getString(submitLabel())
        binding.authProgress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.googleButton.isEnabled = !busy
        binding.googleButton.alpha = if (busy) 0.6f else 1f
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
