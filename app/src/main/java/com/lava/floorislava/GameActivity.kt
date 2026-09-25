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
import android.preference.PreferenceManager
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.*
import com.lava.floorislava.databinding.ActivityGameBinding
import kotlinx.coroutines.Job
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

    // Game constants
    private val gameDurationMs = 120_000L // 2 minutes
    private val safeZoneMinRadiusM = 15.0
    private val safeZoneMaxRadiusM = 45.0
    private val safeZoneRadiusM = 6.0 // physical size of the safe circle on the ground
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
            if (!gameActive) startRound()
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
            resetForNewRound()
        }

        setupLocationCallback()

        if (hasLocationPermission()) {
            enableLocation()
        }
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
            binding.statusText.text = "Tap START to place the safe zone"
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
                val mm = seconds / 60
                val ss = seconds % 60
                binding.timerText.text = String.format("%02d:%02d", mm, ss)

                binding.timerText.setBackgroundResource(
                    if (millisUntilFinished <= 15_000L) R.drawable.bg_timer_pill_danger
                    else R.drawable.bg_timer_pill
                )
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

        binding.distanceText.text = if (distance <= safeZoneRadiusM) {
            "You're in the safe zone!"
        } else {
            String.format("%.0f m to safe zone", remaining)
        }

        if (distance <= safeZoneRadiusM) {
            endRound(won = true)
        }
    }

    private fun endRound(won: Boolean) {
        gameActive = false
        countDownTimer?.cancel()
        binding.arButton.visibility = View.INVISIBLE

        binding.resultOverlay.visibility = View.VISIBLE
        if (won) {
            binding.resultPanel.setBackgroundResource(R.drawable.bg_win_panel)
            binding.resultIcon.text = "🟢"
            binding.resultTitle.text = getString(R.string.win_title)
            binding.resultTitle.setTextColor(ContextCompat.getColor(this, R.color.safe_green_glow))
            binding.resultSubtitle.text = "You made it before the lava rose."
        } else {
            binding.resultPanel.setBackgroundResource(R.drawable.bg_lose_panel)
            binding.resultIcon.text = "🌋"
            binding.resultTitle.text = getString(R.string.lose_title)
            binding.resultTitle.setTextColor(ContextCompat.getColor(this, R.color.lava_orange_bright))
            binding.resultSubtitle.text = "Time ran out before you reached safety."
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
        countDownTimer?.cancel()
        safeZoneSearchJob?.cancel()
        markerAnimator?.cancel()
        if (::fusedLocationClient.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }
}
