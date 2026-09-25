package com.lava.floorislava

import org.osmdroid.util.GeoPoint
import kotlin.math.*
import kotlin.random.Random

object GeoUtils {

    private const val EARTH_RADIUS_METERS = 6371000.0

    /**
     * Generates a random GeoPoint within [minRadiusMeters, maxRadiusMeters] of [center].
     * Uses a min radius so the safe zone never spawns literally under your feet.
     */
    fun randomPointNear(
        center: GeoPoint,
        minRadiusMeters: Double,
        maxRadiusMeters: Double
    ): GeoPoint {
        val randomAngle = Random.nextDouble(0.0, 2 * PI)
        val randomRadius = Random.nextDouble(minRadiusMeters, maxRadiusMeters)

        val latRad = Math.toRadians(center.latitude)
        val lngRad = Math.toRadians(center.longitude)
        val angularDistance = randomRadius / EARTH_RADIUS_METERS

        val newLatRad = asin(
            sin(latRad) * cos(angularDistance) +
                cos(latRad) * sin(angularDistance) * cos(randomAngle)
        )
        val newLngRad = lngRad + atan2(
            sin(randomAngle) * sin(angularDistance) * cos(latRad),
            cos(angularDistance) - sin(latRad) * sin(newLatRad)
        )

        return GeoPoint(Math.toDegrees(newLatRad), Math.toDegrees(newLngRad))
    }

    /** The point [distanceMeters] away from [origin] along compass [bearingDeg] (0 = north). */
    fun destination(origin: GeoPoint, distanceMeters: Double, bearingDeg: Double): GeoPoint {
        val latRad = Math.toRadians(origin.latitude)
        val lngRad = Math.toRadians(origin.longitude)
        val bearingRad = Math.toRadians(bearingDeg)
        val angularDistance = distanceMeters / EARTH_RADIUS_METERS

        val newLatRad = asin(
            sin(latRad) * cos(angularDistance) +
                cos(latRad) * sin(angularDistance) * cos(bearingRad)
        )
        val newLngRad = lngRad + atan2(
            sin(bearingRad) * sin(angularDistance) * cos(latRad),
            cos(angularDistance) - sin(latRad) * sin(newLatRad)
        )
        return GeoPoint(Math.toDegrees(newLatRad), Math.toDegrees(newLngRad))
    }

    /** Geographic midpoint of [a] and [b] (exact enough at walking distances). */
    fun midpoint(a: GeoPoint, b: GeoPoint): GeoPoint {
        val distance = distanceMeters(a, b)
        if (distance < 0.01) return GeoPoint(a.latitude, a.longitude)
        return destination(a, distance / 2, ArMath.bearingBetween(a, b))
    }

    /** Haversine distance in meters between two GeoPoints. */
    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLng = Math.toRadians(b.longitude - a.longitude)

        val h = sin(dLat / 2).pow(2.0) + cos(lat1) * cos(lat2) * sin(dLng / 2).pow(2.0)
        val c = 2 * atan2(sqrt(h), sqrt(1 - h))
        return EARTH_RADIUS_METERS * c
    }
}
