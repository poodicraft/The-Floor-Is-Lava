package com.lava.floorislava

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lava.floorislava.databinding.ActivityMainMenuBinding
import kotlinx.coroutines.launch

class MainMenuActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainMenuBinding
    private var playPulse: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainMenuBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.playButton.setOnClickListener { play() }

        binding.difficultyEasy.setOnClickListener { selectDifficulty(Difficulty.EASY) }
        binding.difficultyNormal.setOnClickListener { selectDifficulty(Difficulty.NORMAL) }
        binding.difficultyHard.setOnClickListener { selectDifficulty(Difficulty.HARD) }

        binding.multiplayerButton.setOnClickListener { openOnline(MultiplayerActivity::class.java) }
        binding.leaderboardsButton.setOnClickListener { openOnline(LeaderboardActivity::class.java) }
        binding.accountChip.setOnClickListener { showAccount() }

        binding.howToPlayButton.setOnClickListener { showHowToPlay() }
        binding.hapticsButton.setOnClickListener {
            val enabled = !GamePrefs.hapticsEnabled(this)
            GamePrefs.setHapticsEnabled(this, enabled)
            updateHapticsButton()
            if (enabled) Haptics.tick(this)
        }

        playIntroAnimation()
    }

    override fun onResume() {
        super.onResume()
        showDifficulty(GamePrefs.difficulty(this))
        updateStats()
        updateHapticsButton()
        updateAccountChip()
        startPlayPulse()
    }

    override fun onPause() {
        super.onPause()
        playPulse?.cancel()
    }

    private fun play() {
        val hasPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        // Location access can be revoked from system settings while the app
        // is in the background, so send the player back through the
        // permission screen instead of opening a map that can't find them.
        val target = if (hasPermission) GameActivity::class.java else SplashActivity::class.java
        startActivity(Intent(this, target))
    }

    /** Multiplayer and leaderboards need an account (and a Firebase-enabled build). */
    private fun openOnline(target: Class<*>) {
        when {
            !Cloud.isConfigured(this) -> MaterialAlertDialogBuilder(this)
                .setTitle(R.string.auth_not_configured_title)
                .setMessage(R.string.auth_not_configured_body)
                .setPositiveButton(R.string.how_to_play_ok, null)
                .show()
            !Cloud.isSignedIn(this) -> startActivity(Intent(this, AuthActivity::class.java))
            else -> startActivity(Intent(this, target))
        }
    }

    private fun updateAccountChip() {
        if (!Cloud.isSignedIn(this)) {
            binding.accountChip.text = if (Cloud.isConfigured(this)) "👤 SIGN IN" else "📴 OFFLINE"
            return
        }
        binding.accountChip.text = "👤 …"
        lifecycleScope.launch {
            val profile = runCatching { Cloud.myProfile() }.getOrNull()
            binding.accountChip.text = if (profile != null) {
                "👤 ${profile.username}  ·  ${profile.points} pts"
            } else {
                "👤 ${Cloud.auth.currentUser?.email ?: "Account"}"
            }
        }
    }

    private fun showAccount() {
        if (!Cloud.isConfigured(this)) {
            openOnline(AuthActivity::class.java)
            return
        }
        if (!Cloud.isSignedIn(this)) {
            startActivity(Intent(this, AuthActivity::class.java))
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Your account")
            .setMessage("Signed in as ${Cloud.auth.currentUser?.email ?: "unknown"}")
            .setPositiveButton("Close", null)
            .setNegativeButton("Sign out") { _, _ ->
                Cloud.signOut()
                startActivity(Intent(this, AuthActivity::class.java))
                finish()
            }
            .show()
    }

    private fun selectDifficulty(difficulty: Difficulty) {
        GamePrefs.setDifficulty(this, difficulty)
        showDifficulty(difficulty)
        updateStats()
        Haptics.tick(this)
    }

    private fun showDifficulty(selected: Difficulty) {
        val pills = mapOf(
            Difficulty.EASY to binding.difficultyEasy,
            Difficulty.NORMAL to binding.difficultyNormal,
            Difficulty.HARD to binding.difficultyHard
        )
        for ((difficulty, pill) in pills) {
            val isSelected = difficulty == selected
            pill.setBackgroundResource(if (isSelected) R.drawable.bg_mode_pill_selected else 0)
            pill.setTextColor(
                ContextCompat.getColor(this, if (isSelected) R.color.lava_black else R.color.text_secondary)
            )
        }
        binding.difficultySummary.text = selected.summary()
    }

    private fun updateStats() {
        val stats = GamePrefs.stats(this)
        val bestMs = GamePrefs.bestTimeMs(this, GamePrefs.difficulty(this))
        binding.statWins.text = stats.wins.toString()
        binding.statWinRate.text = if (stats.played == 0) "–" else "${stats.winRatePercent}%"
        binding.statBestStreak.text = stats.bestStreak.toString()
        binding.statBestTime.text = if (bestMs == 0L) "–" else formatDuration(bestMs)
    }

    private fun updateHapticsButton() {
        binding.hapticsButton.setText(
            if (GamePrefs.hapticsEnabled(this)) R.string.menu_haptics_on else R.string.menu_haptics_off
        )
    }

    private fun showHowToPlay() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.how_to_play_title)
            .setMessage(R.string.how_to_play_body)
            .setPositiveButton(R.string.how_to_play_ok, null)
            .show()
    }

    private fun playIntroAnimation() {
        val views: List<View> = listOf(binding.menuEmoji, binding.menuTitle, binding.playButton)
        views.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = 40f * resources.displayMetrics.density
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(120L * index)
                .setDuration(550L)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        }
    }

    private fun startPlayPulse() {
        playPulse?.cancel()
        playPulse = ObjectAnimator.ofPropertyValuesHolder(
            binding.playButton,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.06f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.06f)
        ).apply {
            duration = 900L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }
}
