package com.lava.floorislava

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.firestore.ListenerRegistration
import com.lava.floorislava.databinding.ActivityMultiplayerBinding
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import java.util.Locale

/**
 * Multiplayer lobby: host a race or join one with a 5-letter code, share
 * locations, and (host) start the race once both players have GPS.
 */
class MultiplayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMultiplayerBinding
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private var myLocation: GeoPoint? = null
    private var lastPublished: GeoPoint? = null
    private var lastPublishedAt = 0L
    private var areaUpdated = false

    private var matchCode: String? = null
    private var myRole: Role? = null
    private var registration: ListenerRegistration? = null
    private var latest: Match? = null
    private var friendAdded = false
    private var raceLaunched = false
    private var myName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Cloud.isSignedIn(this)) {
            Toast.makeText(this, "Sign in to play multiplayer", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (!hasLocationPermission()) {
            startActivity(Intent(this, SplashActivity::class.java))
            finish()
            return
        }

        binding = ActivityMultiplayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val difficulty = GamePrefs.difficulty(this)
        binding.createDifficultyText.text =
            "${difficulty.label} · ${difficulty.summary()}\n(pick the difficulty on the main menu)"

        binding.mpClose.setOnClickListener { leaveAndFinish() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leaveAndFinish()
        })
        binding.createMatchButton.setOnClickListener { createMatch(difficulty) }
        binding.joinMatchButton.setOnClickListener { joinMatch() }
        binding.shareCodeButton.setOnClickListener { shareCode() }
        binding.startMatchButton.setOnClickListener { startRace() }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                onLocation(GeoPoint(loc.latitude, loc.longitude))
            }
        }
        startLocationUpdates()
    }

    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L).build()
        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null && myLocation == null) onLocation(GeoPoint(loc.latitude, loc.longitude))
        }
    }

    private fun onLocation(point: GeoPoint) {
        myLocation = point
        if (!areaUpdated) {
            areaUpdated = true
            Cloud.updateAreaInBackground(this, point)
        }
        publishLocationIfNeeded()
    }

    /** Sends our position to the match when it changes meaningfully, or every 15 s. */
    private fun publishLocationIfNeeded(force: Boolean = false) {
        val code = matchCode ?: return
        val role = myRole ?: return
        val point = myLocation ?: return
        val now = SystemClock.elapsedRealtime()
        val previous = lastPublished
        val moved = previous == null || GeoUtils.distanceMeters(previous, point) > 3.0
        if (force || moved || now - lastPublishedAt > 15_000L) {
            Matches.publishLocation(code, role, point)
            lastPublished = point
            lastPublishedAt = now
        }
    }

    private suspend fun myName(): String =
        myName ?: (Cloud.myProfile()?.username ?: "Player").also { myName = it }

    // --- Create / join ---

    private fun createMatch(difficulty: Difficulty) {
        setChooseBusy(true)
        lifecycleScope.launch {
            try {
                val code = Matches.create(difficulty, myName())
                enterLobby(code, Role.HOST)
            } catch (e: Exception) {
                showError(e.message ?: "Couldn't create a match")
            } finally {
                setChooseBusy(false)
            }
        }
    }

    private fun joinMatch() {
        val code = Matches.normalizeCode(binding.joinCodeInput.text.toString())
        if (code.length != 5) {
            showError("Match codes are 5 letters/numbers")
            return
        }
        setChooseBusy(true)
        lifecycleScope.launch {
            try {
                Matches.join(code, myName())
                enterLobby(code, Role.GUEST)
            } catch (e: Exception) {
                val cause = e.cause
                val known = when {
                    e is MatchNotFoundException || cause is MatchNotFoundException -> "No match with code $code"
                    e is MatchFullException || cause is MatchFullException -> "That match already has two players"
                    e is OwnMatchException || cause is OwnMatchException -> "That's your own match — send the code to your friend"
                    else -> e.message ?: "Couldn't join the match"
                }
                showError(known)
            } finally {
                setChooseBusy(false)
            }
        }
    }

    private fun setChooseBusy(busy: Boolean) {
        binding.createMatchButton.isEnabled = !busy
        binding.joinMatchButton.isEnabled = !busy
    }

    private fun enterLobby(code: String, role: Role) {
        matchCode = code
        myRole = role
        binding.mpError.visibility = View.GONE
        binding.chooseGroup.visibility = View.GONE
        binding.lobbyGroup.visibility = View.VISIBLE
        binding.lobbyCode.text = code
        binding.shareCodeButton.visibility = if (role == Role.HOST) View.VISIBLE else View.GONE
        registration?.remove()
        registration = Matches.listen(code) { onMatch(it) }
        publishLocationIfNeeded(force = true)
    }

    private fun resetToChoose() {
        registration?.remove()
        registration = null
        matchCode = null
        myRole = null
        latest = null
        friendAdded = false
        lastPublished = null
        binding.lobbyGroup.visibility = View.GONE
        binding.chooseGroup.visibility = View.VISIBLE
    }

    // --- Lobby ---

    private fun onMatch(match: Match?) {
        val role = myRole ?: return
        if (match == null) {
            showError("This match no longer exists")
            resetToChoose()
            return
        }
        latest = match

        if (match.status == MatchStatus.CANCELLED) {
            showError("The host cancelled the match")
            resetToChoose()
            return
        }
        if (role == Role.GUEST && match.guestUid != Cloud.uid) {
            showError("You're no longer in this match")
            resetToChoose()
            return
        }
        if (match.status == MatchStatus.RUNNING && !raceLaunched) {
            raceLaunched = true
            startActivity(
                Intent(this, GameActivity::class.java).putExtra(GameActivity.EXTRA_MATCH_CODE, match.code)
            )
            finish()
            return
        }
        if (match.hasGuest && !friendAdded) {
            // Racing someone makes you friends, so they show up on the friends leaderboard.
            friendAdded = true
            val other = if (role == Role.HOST) match.guestUid else match.hostUid
            if (other != null) {
                LavaApp.appScope.launch { runCatching { Cloud.addFriend(other) } }
            }
        }
        render(match, role)
    }

    private fun render(match: Match, role: Role) {
        val difficulty = match.difficulty
        binding.lobbyDifficulty.text =
            "🔥 ${difficulty.label} · ${formatDuration(difficulty.durationMs)} on the clock · both of you"

        binding.hostRow.text = playerLine("👑", match.hostName, role == Role.HOST, match.location(Role.HOST) != null)
        binding.guestRow.text = if (match.hasGuest) {
            playerLine("🧑", match.guestName ?: "Friend", role == Role.GUEST, match.location(Role.GUEST) != null)
        } else {
            "⏳ Waiting for your friend to join…"
        }

        val hostPos = match.location(Role.HOST)
        val guestPos = match.location(Role.GUEST)
        binding.placementText.text = if (hostPos != null && guestPos != null) {
            val apart = GeoUtils.distanceMeters(hostPos, guestPos)
            if (ZonePlanner.isSamePlace(hostPos, guestPos)) {
                "🤝 You're together (${apart.toInt()} m apart) — you'll race to the SAME safe zone, " +
                    "exactly the same distance from both of you."
            } else {
                "🌍 You're ${formatDistance(apart)} apart — you'll each get your own safe zone, " +
                    "exactly the same distance from where you each stand."
            }
        } else {
            ""
        }

        val planning = match.status == MatchStatus.PLANNING
        val ready = match.hasGuest && hostPos != null && guestPos != null && match.status == MatchStatus.LOBBY

        if (role == Role.HOST) {
            binding.startMatchButton.visibility = View.VISIBLE
            binding.startMatchButton.isEnabled = ready
            binding.startMatchButton.alpha = if (ready || planning) 1f else 0.45f
            binding.startMatchButton.text = if (planning) "" else "START RACE"
            binding.lobbyProgress.visibility = if (planning) View.VISIBLE else View.GONE
            binding.lobbyHint.text = when {
                planning -> "Finding verified-safe zones for both of you…"
                !match.hasGuest -> "Send the code to your friend so they can join"
                !ready -> "Waiting for both GPS locations…"
                else -> "Everyone's ready — start when you are!"
            }
        } else {
            binding.startMatchButton.visibility = View.GONE
            binding.lobbyProgress.visibility = View.GONE
            binding.lobbyHint.text = if (planning) {
                "The host is finding safe zones…"
            } else {
                "Waiting for ${match.hostName} to start the race…"
            }
        }

        val error = match.error
        if (!error.isNullOrBlank()) showError(error) else binding.mpError.visibility = View.GONE
    }

    private fun playerLine(icon: String, name: String, isMe: Boolean, located: Boolean): String {
        val you = if (isMe) " (you)" else ""
        val gps = if (located) "📍 ready" else "🛰 finding GPS…"
        return "$icon  $name$you   ·   $gps"
    }

    private fun formatDistance(meters: Double): String =
        if (meters < 1000) "${meters.toInt()} m" else String.format(Locale.US, "%.1f km", meters / 1000)

    private fun startRace(checkMap: Boolean = true) {
        val match = latest ?: return
        val code = matchCode ?: return
        val hostPos = match.location(Role.HOST) ?: return
        val guestPos = match.location(Role.GUEST) ?: return
        Matches.setStatus(code, MatchStatus.PLANNING)
        lifecycleScope.launch {
            try {
                when (val outcome = ZonePlanner.plan(hostPos, guestPos, match.difficulty, checkMap)) {
                    is PlanOutcome.Planned -> Matches.publishPlan(code, outcome.plan)
                    PlanOutcome.MapUnavailable -> {
                        Matches.setStatus(code, MatchStatus.LOBBY, "Couldn't reach the map servers to check the area.")
                        showMapUnavailableDialog()
                    }
                    PlanOutcome.NoOpenSpace -> Matches.setStatus(
                        code, MatchStatus.LOBBY,
                        "No open ground found near one of you — move somewhere more open (a park or square) and try again."
                    )
                }
            } catch (e: Exception) {
                Matches.setStatus(code, MatchStatus.LOBBY, "Couldn't start the race: ${e.message}")
            }
        }
    }

    private fun showMapUnavailableDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.map_unavailable_title)
            .setMessage(R.string.map_unavailable_body)
            .setPositiveButton(R.string.map_unavailable_retry) { _, _ -> startRace() }
            .setNegativeButton(R.string.map_unavailable_unchecked) { _, _ -> startRace(checkMap = false) }
            .setNeutralButton(R.string.quit_cancel_short, null)
            .show()
    }

    private fun shareCode() {
        val code = matchCode ?: return
        val text = "Race me in Floor Is Lava! 🌋 Open Multiplayer → Join a friend, and enter code: $code"
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, "Send match code"))
    }

    private fun showError(message: String) {
        binding.mpError.text = message
        binding.mpError.visibility = View.VISIBLE
    }

    private fun leaveAndFinish() {
        val code = matchCode
        val role = myRole
        if (code != null && role != null && !raceLaunched) Matches.leave(code, role)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        registration?.remove()
        if (::fusedLocationClient.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }
}
