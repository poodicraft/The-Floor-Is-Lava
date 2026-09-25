package com.lava.floorislava

import android.animation.ValueAnimator
import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Looper
import android.os.SystemClock
import android.preference.PreferenceManager
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.firestore.ListenerRegistration
import com.lava.floorislava.databinding.ActivityGameBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import kotlin.math.cos
import kotlin.math.sin

class GameActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityGameBinding
    private lateinit var map: MapView
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null

    // Round settings come from the difficulty picked in the main menu
    private lateinit var difficulty: Difficulty
    private val gameDurationMs get() = difficulty.durationMs
    private val safeZoneMinRadiusM get() = difficulty.minDistanceM
    private val safeZoneMaxRadiusM get() = difficulty.maxDistanceM
    private val safeZoneRadiusM get() = difficulty.zoneRadiusM // physical size of the safe circle on the ground
    private val maxSafeZoneAttempts = 15 // verified point-in-polygon checks before giving up

    // Game state
    private var currentLocation: GeoPoint? = null
    private var safeZoneCenter: GeoPoint? = null
    private var safeZoneCircle: Polygon? = null
    private var safeZoneMarker: Marker? = null
    private var playerMarker: Marker? = null
    private var countDownTimer: CountDownTimer? = null
    private var gameActive = false
    private var hasCenteredCamera = false
    private var safeZoneSearchJob: Job? = null
    private var currentHeading = 0f
    private var markerAnimator: ValueAnimator? = null
    private var displayedMarkerPosition: GeoPoint? = null
    private var roundStartedAt = 0L
    private var closestDistanceM = Double.MAX_VALUE
    private var lastTickSecond = -1

    // Multiplayer race state (null matchCode = single player)
    private var matchCode: String? = null
    private val isMatch get() = matchCode != null
    private var myRole: Role? = null
    private var matchRegistration: ListenerRegistration? = null
    private var latestMatch: Match? = null
    private var raceStarting = false
    private var roundFinished = false
    private var outcomeReported = false
    private var myOutcome: String? = null
    private var myTimeMs: Long? = null
    private var raceBonusRecorded = false
    private var lastProgressReportAt = 0L

    private lateinit var locationCallback: LocationCallback

    override fun onCreate(savedInstanceState: Bundle?) {
        // osmdroid requires this config call before any map view is used
        Configuration.getInstance().load(
            applicationContext,
            PreferenceManager.getDefaultSharedPreferences(applicationContext)
        )
        Configuration.getInstance().userAgentValue = packageName

        super.onCreate(savedInstanceState)
        binding = ActivityGameBinding.inflate(layoutInflater)
        setContentView(binding.root)

        difficulty = GamePrefs.difficulty(this)
        binding.difficultyChip.text = difficulty.label
        binding.timerText.text = formatDuration(gameDurationMs)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        map = binding.map
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.setTilesScaledToDpi(true)
        map.controller.setZoom(19.0)
        map.overlays.clear()

        binding.startButton.setOnClickListener {
            if (!gameActive && !isMatch) startRound()
        }

        binding.recenterButton.setOnClickListener {
            currentLocation?.let {
                map.controller.animateTo(it)
            }
        }

        binding.arButton.setOnClickListener {
            openArView()
        }

        binding.playAgainButton.setOnClickListener {
            if (isMatch) {
                startActivity(Intent(this, MultiplayerActivity::class.java))
                finish()
            } else {
                resetForNewRound()
            }
        }

        binding.resultMenuButton.setOnClickListener { finish() }
        binding.menuButton.setOnClickListener { leaveToMenu() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leaveToMenu()
        })

        setupLocationCallback()

        if (hasLocationPermission()) {
            enableLocation()
        }

        intent.getStringExtra(EXTRA_MATCH_CODE)?.let { setupMatchMode(it) }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun enableLocation() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 700L)
            .setMinUpdateIntervalMillis(400L)
            .setMinUpdateDistanceMeters(0.5f)
            .build()

        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())

        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) {
                onLocationUpdate(GeoPoint(loc.latitude, loc.longitude))
            }
        }
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                onLocationUpdate(GeoPoint(loc.latitude, loc.longitude))
            }
        }
    }

    private fun onLocationUpdate(point: GeoPoint) {
        currentLocation = point
        updatePlayerMarker(point)

        if (!hasCenteredCamera) {
            hasCenteredCamera = true
            map.controller.setCenter(point)
            map.controller.setZoom(19.0)
            if (!isMatch) binding.statusText.text = "Tap START to place the safe zone"
            Cloud.updateAreaInBackground(this, point)
        }

        if (gameActive) {
            checkWinCondition(point)
        }
    }

    private fun updatePlayerMarker(point: GeoPoint) {
        if (playerMarker == null) {
            playerMarker = Marker(map).apply {
                position = point
                title = "You"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = ContextCompat.getDrawable(this@GameActivity, R.drawable.ic_player_direction)
                rotation = currentHeading
            }
            map.overlays.add(playerMarker)
            displayedMarkerPosition = point
            map.invalidate()
            return
        }

        val fromPoint = displayedMarkerPosition ?: point
        val toPoint = point

        markerAnimator?.cancel()
        markerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450L // slightly shorter than the ~700ms GPS update interval, so motion stays continuous
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                val lat = fromPoint.latitude + (toPoint.latitude - fromPoint.latitude) * fraction
                val lng = fromPoint.longitude + (toPoint.longitude - fromPoint.longitude) * fraction
                val interpolated = GeoPoint(lat, lng)
                displayedMarkerPosition = interpolated
                playerMarker?.position = interpolated
                map.invalidate()
            }
            start()
        }
    }

    // --- Compass heading ---

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

        val rotationMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

        val orientation = FloatArray(3)
        SensorManager.getOrientation(rotationMatrix, orientation)

        val azimuthRad = orientation[0]
        var headingDeg = Math.toDegrees(azimuthRad.toDouble()).toFloat()
        if (headingDeg < 0) headingDeg += 360f

        currentHeading = headingDeg
        playerMarker?.rotation = headingDeg
        map.invalidate()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // no-op
    }

    // --- Round start / verified safe zone search ---

    private fun startRound() {
        val playerPos = currentLocation
        if (playerPos == null) {
            binding.statusText.text = "Still finding your GPS location…"
            return
        }

        gameActive = true
        setSearchingUi(true)
        binding.statusText.text = "Finding a reachable safe zone…"

        safeZoneSearchJob?.cancel()
        safeZoneSearchJob = lifecycleScope.launch {
            var chosenPoint: GeoPoint? = null

            for (attempt in 1..maxSafeZoneAttempts) {
                if (!gameActive) return@launch // round was reset/cancelled mid-search

                val candidate = GeoUtils.randomPointNear(
                    playerPos, safeZoneMinRadiusM, safeZoneMaxRadiusM
                )
                // isPointClear only returns true when it was ACTUALLY VERIFIED
                // clear against real building/water shapes. A failed/timed-out
                // check counts as "no" here too — we never use an unverified point.
                val verifiedClear = OverpassChecker.isPointClear(candidate)
                if (verifiedClear) {
                    chosenPoint = candidate
                    break
                }
            }

            if (!gameActive) return@launch

            val safePoint = chosenPoint
            if (safePoint == null) {
                // Every attempt was either blocked or couldn't be verified.
                // Don't guess — tell the player and let them retry rather
                // than risk dropping the zone somewhere unchecked.
                gameActive = false
                setSearchingUi(false)
                binding.statusText.text =
                    "Couldn't find a verified clear spot nearby — check your connection and try again"
                return@launch
            }

            beginCountdown(playerPos, safePoint)
        }
    }

    /** Back button / ✕ button: ask before abandoning a live round. */
    private fun leaveToMenu() {
        val raceInProgress = isMatch && !roundFinished
        if (!gameActive && !raceInProgress) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.quit_title)
            .setMessage(if (isMatch) R.string.quit_message_race else R.string.quit_message)
            .setPositiveButton(R.string.quit_confirm) { _, _ ->
                reportOutcome(Outcome.QUIT, elapsedSinceStart())
                finish()
            }
            .setNegativeButton(R.string.quit_cancel, null)
            .show()
    }

    private fun openArView() {
        val safe = safeZoneCenter ?: return
        val intent = Intent(this, ArActivity::class.java).apply {
            putExtra(ArActivity.EXTRA_SAFE_LAT, safe.latitude)
            putExtra(ArActivity.EXTRA_SAFE_LNG, safe.longitude)
        }
        startActivity(intent)
    }

    private fun setSearchingUi(searching: Boolean) {
        binding.startButton.isEnabled = !searching
        binding.startButtonText.visibility = if (searching) View.INVISIBLE else View.VISIBLE
        binding.startButtonSpinner.visibility = if (searching) View.VISIBLE else View.GONE
    }

    private fun beginCountdown(playerPos: GeoPoint, safePoint: GeoPoint) {
        setSearchingUi(false)
        binding.startButtonText.text = "RUN!"
        binding.timerText.visibility = View.VISIBLE
        binding.distanceText.visibility = View.VISIBLE
        binding.arButton.visibility = View.VISIBLE
        binding.statusText.text = "Get to the safe zone!"

        safeZoneCenter = safePoint
        drawSafeZone(safePoint, safeZoneRadiusM)
        roundStartedAt = SystemClock.elapsedRealtime()
        closestDistanceM = Double.MAX_VALUE
        lastTickSecond = -1
        Haptics.roundStart(this)

        // Zoom to fit both player and safe zone
        val box = org.osmdroid.util.BoundingBox(
            maxOf(playerPos.latitude, safePoint.latitude) + 0.0006,
            maxOf(playerPos.longitude, safePoint.longitude) + 0.0006,
            minOf(playerPos.latitude, safePoint.latitude) - 0.0006,
            minOf(playerPos.longitude, safePoint.longitude) - 0.0006
        )
        map.zoomToBoundingBox(box, true, 100)

        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(gameDurationMs, 250L) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = Math.ceil(millisUntilFinished / 1000.0).toInt()
                binding.timerText.text = formatDuration(seconds * 1000L)

                binding.timerText.setBackgroundResource(
                    if (millisUntilFinished <= 15_000L) R.drawable.bg_timer_pill_danger
                    else R.drawable.bg_timer_pill
                )

                // The lava glow creeps in from the screen edges as time runs out
                val elapsedFraction = 1f - millisUntilFinished.toFloat() / gameDurationMs
                binding.lavaVignette.alpha = (elapsedFraction * elapsedFraction * 0.85f).coerceIn(0f, 0.85f)

                // Final 10 seconds: pulse the timer and buzz once per second
                if (millisUntilFinished <= 10_000L && seconds != lastTickSecond) {
                    lastTickSecond = seconds
                    pulseTimer()
                    Haptics.tick(this@GameActivity)
                }

                if (isMatch) checkOpponentAhead()
            }

            override fun onFinish() {
                if (gameActive) endRound(won = false)
            }
        }.start()
    }

    private fun drawSafeZone(center: GeoPoint, radiusMeters: Double) {
        safeZoneCircle?.let { map.overlays.remove(it) }
        safeZoneMarker?.let { map.overlays.remove(it) }

        // osmdroid has no native circle overlay, so we draw one as a 64-point polygon
        val points = ArrayList<GeoPoint>()
        val earthRadius = 6371000.0
        val latRad = Math.toRadians(center.latitude)
        for (i in 0..64) {
            val angle = 2 * Math.PI * i / 64
            val dLat = (radiusMeters * cos(angle)) / earthRadius
            val dLng = (radiusMeters * sin(angle)) / (earthRadius * cos(latRad))
            points.add(
                GeoPoint(
                    center.latitude + Math.toDegrees(dLat),
                    center.longitude + Math.toDegrees(dLng)
                )
            )
        }

        safeZoneCircle = Polygon(map).apply {
            setPoints(points)
            fillColor = Color.parseColor("#5539E67A")
            strokeColor = Color.parseColor("#FF39E67A")
            strokeWidth = 5f
        }
        map.overlays.add(safeZoneCircle)

        safeZoneMarker = Marker(map).apply {
            position = center
            title = "SAFE ZONE"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(this@GameActivity, R.drawable.ic_safe_flag)
        }
        map.overlays.add(safeZoneMarker)
        map.invalidate()
    }

    private fun checkWinCondition(playerLoc: GeoPoint) {
        val safe = safeZoneCenter ?: return
        val distance = GeoUtils.distanceMeters(playerLoc, safe)
        val remaining = (distance - safeZoneRadiusM).coerceAtLeast(0.0)
        closestDistanceM = minOf(closestDistanceM, remaining)
        if (isMatch) reportProgressThrottled(remaining)

        binding.distanceText.text = if (distance <= safeZoneRadiusM) {
            "You're in the safe zone!"
        } else {
            String.format("%.0f m to safe zone", remaining)
        }

        if (distance <= safeZoneRadiusM) {
            endRound(won = true)
        }
    }

    private fun pulseTimer() {
        binding.timerText.animate().cancel()
        binding.timerText.scaleX = 1.18f
        binding.timerText.scaleY = 1.18f
        binding.timerText.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(350L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun endRound(won: Boolean) {
        gameActive = false
        roundFinished = true
        countDownTimer?.cancel()
        binding.arButton.visibility = View.INVISIBLE

        val elapsedMs = elapsedSinceStart()
        val newBest = GamePrefs.recordResult(this, difficulty, won, elapsedMs)
        val stats = GamePrefs.stats(this)
        Cloud.recordRoundInBackground(difficulty, won, elapsedMs)
        reportOutcome(if (won) Outcome.ESCAPED else Outcome.TIMEOUT, elapsedMs)

        if (won) {
            Haptics.win(this)
            binding.lavaVignette.animate().alpha(0f).setDuration(400L).start()
            binding.resultPanel.setBackgroundResource(R.drawable.bg_win_panel)
            binding.resultIcon.text = "🟢"
            binding.resultTitle.text = getString(R.string.win_title)
            binding.resultTitle.setTextColor(ContextCompat.getColor(this, R.color.safe_green_glow))
            binding.resultSubtitle.text = "You made it before the lava rose."
            binding.resultStats.text = buildString {
                append("⏱ Escaped in ${formatDuration(elapsedMs)}")
                if (newBest) append("  ·  NEW BEST!")
                append("\n🔥 Win streak: ${stats.streak}")
            }
        } else {
            Haptics.lose(this)
            binding.lavaVignette.animate().alpha(1f).setDuration(400L).start()
            binding.resultPanel.setBackgroundResource(R.drawable.bg_lose_panel)
            binding.resultIcon.text = "🌋"
            binding.resultTitle.text = getString(R.string.lose_title)
            binding.resultTitle.setTextColor(ContextCompat.getColor(this, R.color.lava_orange_bright))
            binding.resultSubtitle.text = "Time ran out before you reached safety."
            binding.resultStats.text =
                if (closestDistanceM == Double.MAX_VALUE) "Streak lost — try again!"
                else String.format("So close! You got within %.0f m", closestDistanceM)
        }

        // Fade the overlay in and pop the panel up
        binding.resultOverlay.alpha = 0f
        binding.resultOverlay.visibility = View.VISIBLE
        binding.resultOverlay.animate().alpha(1f).setDuration(300L).start()
        binding.resultPanel.scaleX = 0.8f
        binding.resultPanel.scaleY = 0.8f
        binding.resultPanel.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(450L)
            .setInterpolator(OvershootInterpolator(1.6f))
            .start()

        val match = latestMatch
        val role = myRole
        if (match != null && role != null) showRaceResult(match, role)
    }

    private fun elapsedSinceStart(): Long =
        if (roundStartedAt == 0L) 0L
        else (SystemClock.elapsedRealtime() - roundStartedAt).coerceAtMost(gameDurationMs)

    // --- Multiplayer race ---

    private fun setupMatchMode(code: String) {
        matchCode = code
        binding.startButton.isEnabled = false
        binding.startButtonText.text = "…"
        binding.statusText.text = "Loading the race…"
        binding.opponentText.visibility = View.VISIBLE
        binding.opponentText.text = "⚔️ Connecting to your friend…"
        binding.playAgainButton.text = getString(R.string.new_match)
        matchRegistration = Matches.listen(code) { onMatchUpdate(it) }
    }

    private fun onMatchUpdate(match: Match?) {
        if (match == null) return
        latestMatch = match
        val role = myRole ?: match.roleOf(Cloud.uid)?.also { myRole = it } ?: return

        if (difficulty != match.difficulty && !gameActive) {
            difficulty = match.difficulty
            binding.timerText.text = formatDuration(gameDurationMs)
        }
        binding.difficultyChip.text = "⚔️ ${difficulty.label}"

        val zone = match.zone(role)
        if (match.status == MatchStatus.RUNNING && zone != null && !raceStarting) {
            raceStarting = true
            startRaceCountdown(match, role, zone)
        }

        renderOpponent(match, role)
        if (roundFinished) showRaceResult(match, role) else checkOpponentAhead()
    }

    /** 3-2-1-GO, then the normal countdown, towards this player's zone from the match. */
    private fun startRaceCountdown(match: Match, role: Role, zone: GeoPoint) {
        val opponent = match.nameOf(role.other)
        val distance = match.targetDistanceM.toInt()
        binding.statusText.text = if (match.sameSpot) {
            "Same safe zone as $opponent — $distance m from each of you"
        } else {
            "Your own safe zone, $distance m away — same distance as $opponent's"
        }
        lifecycleScope.launch {
            for (n in 3 downTo 1) {
                binding.startButtonText.text = n.toString()
                pulseView(binding.startButton)
                Haptics.tick(this@GameActivity)
                delay(1000L)
            }
            gameActive = true
            beginCountdown(currentLocation ?: zone, zone)
            binding.statusText.text = "GO! Beat $opponent to the safe zone!"
        }
    }

    private fun pulseView(view: View) {
        view.animate().cancel()
        view.scaleX = 1.15f
        view.scaleY = 1.15f
        view.animate().scaleX(1f).scaleY(1f).setDuration(400L)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    private fun renderOpponent(match: Match, role: Role) {
        val other = role.other
        val name = match.nameOf(other)
        binding.opponentText.text = when (match.outcome(other)) {
            Outcome.ESCAPED -> "🏁 $name escaped in ${formatDuration(match.timeMs(other) ?: 0L)}"
            Outcome.TIMEOUT -> "🌋 The lava got $name"
            Outcome.QUIT -> "🏳️ $name quit the race"
            else -> match.remainingM(other)?.let { "🧑 $name: ${it.toInt()} m to go" }
                ?: "🧑 $name is getting ready…"
        }
    }

    /** If the other player already escaped faster than we possibly can now, the race is lost. */
    private fun checkOpponentAhead() {
        if (!gameActive) return
        val match = latestMatch ?: return
        val role = myRole ?: return
        if (match.outcome(role.other) != Outcome.ESCAPED) return
        val theirTime = match.timeMs(role.other) ?: return
        if (elapsedSinceStart() > theirTime) endRound(won = false)
    }

    private fun reportProgressThrottled(remainingM: Double) {
        val code = matchCode ?: return
        val role = myRole ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastProgressReportAt < 2500L) return
        lastProgressReportAt = now
        Matches.reportProgress(code, role, remainingM)
    }

    private fun reportOutcome(outcome: String, timeMs: Long) {
        val code = matchCode ?: return
        val role = myRole ?: return
        if (outcomeReported) return
        outcomeReported = true
        myOutcome = outcome
        myTimeMs = timeMs
        Matches.reportOutcome(code, role, outcome, timeMs)
    }

    /** Rewrites the result card with the race result; called again as the other player finishes. */
    private fun showRaceResult(match: Match, role: Role) {
        val opponent = match.nameOf(role.other)
        val theirTime = match.timeMs(role.other)
        val mine = myOutcome ?: match.outcome(role)
        val myTime = myTimeMs ?: match.timeMs(role)
        val result = match.resultFor(role, mine, myTime)

        val won = result == RaceResult.WON || result == RaceResult.PENDING && mine == Outcome.ESCAPED
        binding.resultPanel.setBackgroundResource(if (won || result == RaceResult.DRAW) R.drawable.bg_win_panel else R.drawable.bg_lose_panel)
        binding.resultTitle.setTextColor(
            ContextCompat.getColor(this, if (won || result == RaceResult.DRAW) R.color.safe_green_glow else R.color.lava_orange_bright)
        )

        when (result) {
            RaceResult.PENDING -> {
                binding.resultIcon.text = "⏳"
                binding.resultTitle.text = "YOU ESCAPED!"
                binding.resultSubtitle.text = "Waiting for $opponent to finish…"
            }
            RaceResult.WON -> {
                binding.resultIcon.text = "🏆"
                binding.resultTitle.text = "YOU BEAT ${opponent.uppercase()}!"
                binding.resultSubtitle.text = if (match.outcome(role.other) == Outcome.ESCAPED && theirTime != null && myTime != null) {
                    "You: ${formatDuration(myTime)}  ·  $opponent: ${formatDuration(theirTime)}
+${Difficulty.MULTIPLAYER_WIN_BONUS} race bonus points"
                } else {
                    "$opponent didn't make it.
+${Difficulty.MULTIPLAYER_WIN_BONUS} race bonus points"
                }
                if (!raceBonusRecorded) {
                    raceBonusRecorded = true
                    Cloud.recordMultiplayerWinInBackground()
                    Haptics.win(this)
                }
            }
            RaceResult.LOST -> {
                binding.resultIcon.text = "🌋"
                binding.resultTitle.text = "${opponent.uppercase()} WINS"
                binding.resultSubtitle.text = if (match.outcome(role.other) == Outcome.ESCAPED && theirTime != null) {
                    "$opponent reached safety in ${formatDuration(theirTime)}."
                } else {
                    "The lava got you."
                }
            }
            RaceResult.DRAW -> {
                binding.resultIcon.text = "🤝"
                binding.resultTitle.text = "DEAD HEAT!"
                binding.resultSubtitle.text = "You both escaped in ${formatDuration(myTime ?: 0L)}."
            }
            RaceResult.NOBODY -> {
                binding.resultIcon.text = "🌋"
                binding.resultTitle.text = "THE LAVA WINS"
                binding.resultSubtitle.text = "Neither of you made it this time."
            }
        }
    }

    private fun resetForNewRound() {
        safeZoneSearchJob?.cancel()
        gameActive = false

        binding.resultOverlay.visibility = View.GONE
        setSearchingUi(false)
        binding.startButtonText.text = getString(R.string.start_button)
        binding.timerText.visibility = View.INVISIBLE
        binding.distanceText.visibility = View.INVISIBLE
        binding.arButton.visibility = View.INVISIBLE
        binding.timerText.setBackgroundResource(R.drawable.bg_timer_pill)
        binding.timerText.text = formatDuration(gameDurationMs)
        binding.lavaVignette.animate().cancel()
        binding.lavaVignette.alpha = 0f
        binding.statusText.text = "Tap START to place a new safe zone"

        safeZoneCircle?.let { map.overlays.remove(it) }
        safeZoneMarker?.let { map.overlays.remove(it) }
        safeZoneCircle = null
        safeZoneMarker = null
        safeZoneCenter = null
        map.invalidate()

        currentLocation?.let {
            map.controller.animateTo(it)
            map.controller.setZoom(19.0)
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Leaving mid-race (e.g. swiping the app away) counts as quitting.
        if (isMatch && raceStarting && !roundFinished) reportOutcome(Outcome.QUIT, elapsedSinceStart())
        matchRegistration?.remove()
        countDownTimer?.cancel()
        safeZoneSearchJob?.cancel()
        markerAnimator?.cancel()
        if (::fusedLocationClient.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }

    companion object {
        const val EXTRA_MATCH_CODE = "extra_match_code"
    }
}
