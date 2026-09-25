package com.lava.floorislava

import org.osmdroid.util.GeoPoint
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/** Where each player's safe zone is, and how far away it is from them. */
data class ZonePlan(
    val sameSpot: Boolean,
    val distanceM: Double,
    val hostZone: GeoPoint,
    val guestZone: GeoPoint
)

/**
 * Picks fair safe zones for a multiplayer race. Every zone is checked
 * against real OpenStreetMap buildings/water/roads by [OverpassChecker],
 * exactly like single player.
 *
 * - Players standing together share ONE safe zone, placed on the
 *   perpendicular bisector between them so it is exactly the same walking
 *   distance from both.
 * - Players in different places each get their OWN safe zone, both exactly
 *   the same distance from their own starting spot.
 */
object ZonePlanner {

    /** Closer than this and the two players count as being in the same place. */
    const val SAME_PLACE_THRESHOLD_M = 60.0

    private const val SHARED_ATTEMPTS = 24
    private const val DISTANCE_ATTEMPTS = 4
    private const val BEARINGS_PER_DISTANCE = 10

    fun isSamePlace(host: GeoPoint, guest: GeoPoint): Boolean =
        GeoUtils.distanceMeters(host, guest) <= SAME_PLACE_THRESHOLD_M

    /** Returns null when no verified-clear zone could be found (e.g. no internet). */
    suspend fun plan(host: GeoPoint, guest: GeoPoint, difficulty: Difficulty): ZonePlan? =
        if (isSamePlace(host, guest)) planShared(host, guest, difficulty)
        else planSeparate(host, guest, difficulty)

    private suspend fun planShared(host: GeoPoint, guest: GeoPoint, difficulty: Difficulty): ZonePlan? {
        val separation = GeoUtils.distanceMeters(host, guest)
        val half = separation / 2
        val midpoint = GeoUtils.midpoint(host, guest)
        // Direction host -> guest; the fair points lie at right angles to it.
        val axis = if (separation < 0.5) null else ArMath.bearingBetween(host, guest)

        repeat(SHARED_ATTEMPTS) {
            // The zone must be further than half the gap, or no point on the
            // bisector is that far from both players.
            val minD = max(difficulty.minDistanceM, half + 1.0)
            val maxD = max(difficulty.maxDistanceM, minD + 1.0)
            val distance = Random.nextDouble(minD, maxD)
            val alongBisector = sqrt(distance * distance - half * half)
            val bearing = if (axis == null) {
                Random.nextDouble(0.0, 360.0)
            } else {
                axis + if (Random.nextBoolean()) 90.0 else -90.0
            }
            val zone = GeoUtils.destination(midpoint, alongBisector, bearing)
            if (OverpassChecker.isPointClear(zone)) {
                return ZonePlan(sameSpot = true, distanceM = distance, hostZone = zone, guestZone = zone)
            }
        }
        return null
    }

    private suspend fun planSeparate(host: GeoPoint, guest: GeoPoint, difficulty: Difficulty): ZonePlan? {
        repeat(DISTANCE_ATTEMPTS) {
            val distance = Random.nextDouble(difficulty.minDistanceM, difficulty.maxDistanceM)
            val hostZone = clearPointAt(host, distance) ?: return@repeat
            val guestZone = clearPointAt(guest, distance) ?: return@repeat
            return ZonePlan(sameSpot = false, distanceM = distance, hostZone = hostZone, guestZone = guestZone)
        }
        return null
    }

    /** A verified-clear point exactly [distance] metres from [origin], in any direction. */
    private suspend fun clearPointAt(origin: GeoPoint, distance: Double): GeoPoint? {
        repeat(BEARINGS_PER_DISTANCE) {
            val candidate = GeoUtils.destination(origin, distance, Random.nextDouble(0.0, 360.0))
            if (OverpassChecker.isPointClear(candidate)) return candidate
        }
        return null
    }
}
