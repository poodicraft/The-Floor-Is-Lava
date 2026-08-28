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
