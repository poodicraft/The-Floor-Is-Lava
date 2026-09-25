package com.lava.floorislava

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.lava.floorislava.databinding.ActivityArBinding
import org.osmdroid.util.GeoPoint
import kotlin.math.atan

class ArActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityArBinding
    private lateinit var sensorManager: SensorManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var rotationSensor: Sensor? = null

    private var safeZonePoint: GeoPoint? = null
    private var currentLocation: GeoPoint? = null
    private var deviceAzimuthDeg: Float = 0f
    private var devicePitchDeg: Float = 0f

    // Camera field of view in degrees. Starts with a reasonable fallback
    // (typical phone rear camera) and gets replaced with the real device's
    // actual FOV — calculated from its sensor size and focal length via
    // Camera2 characteristics — as soon as the camera binds. Using the
    // device's real FOV instead of a guess is what keeps the marker's
    // on-screen position accurate rather than "roughly in the area."
    private var horizontalFovDeg = 67.0
    private var verticalFovDeg = 50.0

    private val pulseHandler = Handler(Looper.getMainLooper())
    private val pulseRunnable = object : Runnable {
        override fun run() {
            binding.arOverlay.tickPulse()
            pulseHandler.postDelayed(this, 50L)
        }
    }

    private lateinit var locationCallback: LocationCallback

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                binding.arPermissionGroup.visibility = android.view.View.GONE
                startCamera()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityArBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val lat = intent.getDoubleExtra(EXTRA_SAFE_LAT, Double.NaN)
        val lng = intent.getDoubleExtra(EXTRA_SAFE_LNG, Double.NaN)
        if (!lat.isNaN() && !lng.isNaN()) {
            safeZonePoint = GeoPoint(lat, lng)
        }

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        binding.arCloseButton.setOnClickListener { finish() }

        binding.arGrantCameraButton.setOnClickListener {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        setupLocationCallback()

        if (hasCameraPermission()) {
            startCamera()
        } else {
            binding.arPermissionGroup.visibility = android.view.View.VISIBLE
        }

        if (hasLocationPermission()) {
            startLocationUpdates()
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview)
                readRealCameraFov(camera.cameraInfo)
            } catch (_: Exception) {
                // Camera bind failed (e.g. no camera hardware) — the AR view
                // just won't show a live feed, but won't crash the app.
                // FOV stays at the fallback default in this case.
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Reads the actual rear camera's sensor size and focal length via Camera2
     * interop, and computes its true horizontal/vertical field of view —
     * replacing the fallback guess. If any of this data isn't available on a
     * particular device, we silently keep the fallback rather than crash.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun readRealCameraFov(cameraInfo: androidx.camera.core.CameraInfo) {
        try {
            val camera2Info = Camera2CameraInfo.from(cameraInfo)
            val sensorSize = camera2Info.getCameraCharacteristic(
                CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE
            ) ?: return
            val focalLengths = camera2Info.getCameraCharacteristic(
                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
            ) ?: return
            val focalLength = focalLengths.firstOrNull() ?: return
            if (focalLength <= 0f) return

            val hFovRad = 2 * atan((sensorSize.width / (focalLength * 2)).toDouble())
            val vFovRad = 2 * atan((sensorSize.height / (focalLength * 2)).toDouble())

            val hFovDeg = Math.toDegrees(hFovRad)
            val vFovDeg = Math.toDegrees(vFovRad)

            // Sanity-check the computed values before trusting them — some
            // devices report odd characteristics data. Typical phone rear
            // cameras fall well within 30°-100°; outside that range, keep
            // the fallback default instead of risking a wildly wrong FOV.
            if (hFovDeg in 30.0..100.0) horizontalFovDeg = hFovDeg
            if (vFovDeg in 20.0..90.0) verticalFovDeg = vFovDeg
        } catch (_: Exception) {
            // Camera2 characteristics not available on this device for some
            // reason — keep using the fallback FOV values.
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 700L)
            .setMinUpdateIntervalMillis(400L)
            .build()
        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) currentLocation = GeoPoint(loc.latitude, loc.longitude)
        }
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                currentLocation = GeoPoint(loc.latitude, loc.longitude)
                updateArProjection()
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

        val rawRotationMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rawRotationMatrix, event.values)

        // The raw rotation matrix assumes the device's natural "flat on a
        // table, screen up" orientation as its reference frame. When you
        // actually hold a phone upright to look at the camera view (like in
        // this AR screen), the sensor's own X/Y axes end up tilted relative
        // to what a person means by "up/down" and "left/right" on screen —
        // which is exactly why tilting up/down was showing up as left/right
        // before. remapCoordinateSystem re-expresses the matrix in terms of
        // the device's AXIS_X/AXIS_Z (its natural "held upright, camera
        // facing away from you" pose) instead of the flat-on-a-table default.
        val remappedRotationMatrix = FloatArray(9)
        SensorManager.remapCoordinateSystem(
            rawRotationMatrix,
            SensorManager.AXIS_X,
            SensorManager.AXIS_Z,
            remappedRotationMatrix
        )

        val orientation = FloatArray(3)
        SensorManager.getOrientation(remappedRotationMatrix, orientation)

        var azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
        if (azimuth < 0) azimuth += 360f
        deviceAzimuthDeg = azimuth
        devicePitchDeg = Math.toDegrees(orientation[1].toDouble()).toFloat()

        updateArProjection()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // no-op
    }

    private fun updateArProjection() {
        val player = currentLocation ?: return
        val target = safeZonePoint ?: return

        val distance = GeoUtils.distanceMeters(player, target)
        binding.arDistanceText.text = String.format("%.0f m to safe zone", distance)

        val bearing = ArMath.bearingBetween(player, target)
        val offset = ArMath.angularOffset(deviceAzimuthDeg, bearing)

        val isBehind = kotlin.math.abs(offset) > horizontalFovDeg / 2.0 + 15.0
        val screenX = ArMath.horizontalScreenPosition(offset, horizontalFovDeg)
        val screenY = ArMath.verticalScreenPosition(devicePitchDeg, verticalFovDeg, distance)
        val markerScale = ArMath.markerScaleForDistance(distance)

        binding.arOverlay.updateTarget(screenX, screenY, isBehind, markerScale)

        val isAboveScreen = screenY < -1.1f
        val isBelowScreen = screenY > 1.1f && !isBehind

        binding.arDirectionHint.visibility =
            if (isBehind || isAboveScreen || isBelowScreen) android.view.View.VISIBLE else android.view.View.GONE

        binding.arDirectionHint.text = when {
            isBehind && offset > 0 -> "Safe zone is to your right — turn around"
            isBehind -> "Safe zone is to your left — turn around"
            isAboveScreen -> "Tilt your phone up"
            isBelowScreen -> "Tilt your phone down"
            else -> ""
        }
    }

    override fun onResume() {
        super.onResume()
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        pulseHandler.post(pulseRunnable)
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        pulseHandler.removeCallbacks(pulseRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::fusedLocationClient.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }

    companion object {
        const val EXTRA_SAFE_LAT = "extra_safe_lat"
        const val EXTRA_SAFE_LNG = "extra_safe_lng"
    }
}
