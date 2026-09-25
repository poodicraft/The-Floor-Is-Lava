package com.lava.floorislava

import org.osmdroid.util.GeoPoint
import kotlin.math.*

/**
 * Math helpers for projecting a real-world GPS point onto the phone's live
 * camera view, based on the device's current orientation (from the rotation
 * vector sensor) and the bearing/distance to the target.
 */
object ArMath {

    /**
     * Compass bearing in degrees (0-360, 0 = north) from [from] to [to].
     */
    fun bearingBetween(from: GeoPoint, to: GeoPoint): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLng = Math.toRadians(to.longitude - from.longitude)

        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        val bearingRad = atan2(y, x)
        var bearingDeg = Math.toDegrees(bearingRad)
        if (bearingDeg < 0) bearingDeg += 360.0
        return bearingDeg
    }

    /**
     * Given the device's current azimuth (compass heading, degrees) and the
     * bearing to the target, returns the signed angular offset in degrees
     * (-180..180) between where the phone is pointing and where the target
     * actually is. 0 = dead ahead, negative = target is to the left,
     * positive = target is to the right.
     */
    fun angularOffset(deviceAzimuthDeg: Float, targetBearingDeg: Double): Double {
        var diff = targetBearingDeg - deviceAzimuthDeg
        while (diff > 180) diff -= 360
        while (diff < -180) diff += 360
        return diff
    }

    /**
     * Projects the target onto normalized screen-space X (-1..1, where 0 is
     * screen center) given the angular offset and the camera's horizontal
     * field of view in degrees. Values outside -1..1 mean the target is
     * currently off-screen (behind, or too far to either side).
     */
    fun horizontalScreenPosition(angularOffsetDeg: Double, horizontalFovDeg: Double): Float {
        return (angularOffsetDeg / (horizontalFovDeg / 2.0)).toFloat()
    }

    /**
     * Projects the target's vertical screen position based on device pitch
     * (tilt up/down) vs. the REAL elevation angle of a ground-level target
     * at the given distance — not a fixed guess. A ground-level object's
     * downward angle depends on how far away it is: right next to you, the
     * ground is almost straight down (steep angle); far away, the ground
     * blends into the horizon (shallow angle). Using atan(phoneHeight /
     * distance) captures that correctly, which is what keeps the marker
     * anchored to the actual ground instead of floating above it.
     *
     * Returns normalized screen-space Y (-1..1, 0 = center, positive = down).
     */
    private const val PITCH_SIGN = 1.0 // flip to -1.0 if up/down ever comes out inverted on a device
    private const val ASSUMED_PHONE_HEIGHT_METERS = 1.4 // roughly chest/eye height when holding a phone up

    fun verticalScreenPosition(
        devicePitchDeg: Float,
        verticalFovDeg: Double,
        distanceMeters: Double
    ): Float {
        // Real angle below the horizon at which a point on the ground at
        // `distanceMeters` away appears, given the phone is held roughly
        // ASSUMED_PHONE_HEIGHT_METERS above the ground. Clamped to a small
        // minimum distance so this doesn't blow up (divide-by-near-zero)
        // when you're standing right on top of the safe zone.
        val safeDistance = distanceMeters.coerceAtLeast(1.0)
        val targetElevationDeg = -Math.toDegrees(
            atan(ASSUMED_PHONE_HEIGHT_METERS / safeDistance)
        )

        val signedPitch = devicePitchDeg * PITCH_SIGN
        val offset = targetElevationDeg - signedPitch
        return (offset / (verticalFovDeg / 2.0)).toFloat()
    }

    /**
     * Returns a scale factor for the marker based on distance, so it looks
     * bigger up close and smaller far away — like a real object would.
     * Uses inverse-distance falloff (roughly how perspective actually
     * works), clamped to a sensible min/max so the marker never disappears
     * to nothing or grows absurdly large right next to you.
     */
    fun markerScaleForDistance(distanceMeters: Double): Float {
        val referenceDistance = 15.0 // distance at which scale = 1.0 (baseline size)
        val safeDistance = distanceMeters.coerceAtLeast(2.0)
        val rawScale = (referenceDistance / safeDistance).toFloat()
        return rawScale.coerceIn(0.4f, 2.2f)
    }
}
