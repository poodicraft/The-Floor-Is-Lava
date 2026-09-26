package com.lava.floorislava

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/** Where each player's safe zone is, and how far away it is from them. */
data class ZonePlan(
    val sameSpot: Boolean,
    val distanceM: Double,
    val hostZone: GeoPoint,
    val guestZone: GeoPoint,
    /** False when the players chose to race without the OpenStreetMap check. */
    val mapChecked: Boolean
)

sealed class PlanOutcome {
    data class Planned(val plan: ZonePlan) : PlanOutcome()

    /** No Overpass server answered, so nothing could be checked. */
    object MapUnavailable : PlanOutcome()

    /** The map answered, but everything in range is buildings, water or roads. */
    object NoOpenSpace : PlanOutcome()
}

/**
 * Picks safe zones. The area is fetched once from OpenStreetMap
 * ([OverpassChecker.scan]) and hundreds of candidate spots are then tested
 * on the phone, preferring spots where the whole safe circle is clear.
 *
 * Multiplayer is fair by construction:
 * - Players standing together share ONE zone on the perpendicular bisector
 *   between them, so it is exactly the same distance from both.
 * - Players in different places each get their OWN zone, both exactly the
 *   same distance from their own starting spot.
 */
object ZonePlanner {

    /** Closer than this and the two players count as being in the same place. */
    const val SAME_PLACE_THRESHOLD_M = 60.0

    private const val SINGLE_CANDIDATES = 400
    private const val SHARED_CANDIDATES = 300
    private const val SEPARATE_DISTANCES = 40
    private const val BEARINGS_PER_DISTANCE = 36

    fun isSamePlace(host: GeoPoint, guest: GeoPoint): Boolean =
        GeoUtils.distanceMeters(host, guest) <= SAME_PLACE_THRESHOLD_M

    /**
     * The first candidate whose whole safe circle is clear of everything, else
     * the first whose circle is clear of every road (brushing a building wall
     * is acceptable, standing on a road never is). With no [scan] (unchecked
     * play) the first candidate.
     */
    fun pickClear(scan: AreaScan?, zoneRadiusM: Double, candidates: List<GeoPoint>): GeoPoint? {
        if (scan == null) return candidates.firstOrNull()
        return candidates.firstOrNull { scan.isZoneClear(it, zoneRadiusM) }
            ?: candidates.firstOrNull { scan.isZoneOffRoads(it, zoneRadiusM) }
    }

    /** Single player: a zone between the difficulty's min and max distance from [player]. */
    suspend fun planSingle(player: GeoPoint, difficulty: Difficulty, checkMap: Boolean): PlanOutcome {
        val scan = if (checkMap) {
            OverpassChecker.scan(player, difficulty.maxDistanceM + difficulty.zoneRadiusM)
                ?: return PlanOutcome.MapUnavailable
        } else {
            null
        }
        val zone = withContext(Dispatchers.Default) {
            val candidates = List(SINGLE_CANDIDATES) {
                GeoUtils.randomPointNear(player, difficulty.minDistanceM, difficulty.maxDistanceM)
            }
            pickClear(scan, difficulty.zoneRadiusM, candidates)
        } ?: return PlanOutcome.NoOpenSpace
        val distance = GeoUtils.distanceMeters(player, zone)
        return PlanOutcome.Planned(ZonePlan(false, distance, zone, zone, checkMap))
    }

    /** Multiplayer: fair zones for both players. */
    suspend fun plan(host: GeoPoint, guest: GeoPoint, difficulty: Difficulty, checkMap: Boolean): PlanOutcome =
        if (isSamePlace(host, guest)) planShared(host, guest, difficulty, checkMap)
        else planSeparate(host, guest, difficulty, checkMap)

    private suspend fun planShared(
        host: GeoPoint, guest: GeoPoint, difficulty: Difficulty, checkMap: Boolean
    ): PlanOutcome {
        val separation = GeoUtils.distanceMeters(host, guest)
        val half = separation / 2
        val midpoint = GeoUtils.midpoint(host, guest)
        // The zone must be further than half the gap, or no point on the
        // bisector is that far from both players.
        val minD = max(difficulty.minDistanceM, half + 1.0)
        val maxD = max(difficulty.maxDistanceM, minD + 1.0)

        val scan = if (checkMap) {
            OverpassChecker.scan(midpoint, maxD + difficulty.zoneRadiusM) ?: return PlanOutcome.MapUnavailable
        } else {
            null
        }
        // Direction host -> guest; the fair points lie at right angles to it.
        val axis = if (separation < 0.5) null else ArMath.bearingBetween(host, guest)

        val choice = withContext(Dispatchers.Default) {
            val candidates = List(SHARED_CANDIDATES) {
                val distance = Random.nextDouble(minD, maxD)
                val alongBisector = sqrt(distance * distance - half * half)
                val bearing = if (axis == null) {
                    Random.nextDouble(0.0, 360.0)
                } else {
                    axis + if (Random.nextBoolean()) 90.0 else -90.0
                }
                GeoUtils.destination(midpoint, alongBisector, bearing)
            }
            pickClear(scan, difficulty.zoneRadiusM, candidates)
        } ?: return PlanOutcome.NoOpenSpace

        // Both players are the same distance from any point on the bisector.
        val distance = GeoUtils.distanceMeters(host, choice)
        return PlanOutcome.Planned(ZonePlan(true, distance, choice, choice, checkMap))
    }

    private suspend fun planSeparate(
        host: GeoPoint, guest: GeoPoint, difficulty: Difficulty, checkMap: Boolean
    ): PlanOutcome {
        val radius = difficulty.maxDistanceM + difficulty.zoneRadiusM
        val hostScan = if (checkMap) OverpassChecker.scan(host, radius) ?: return PlanOutcome.MapUnavailable else null
        val guestScan = if (checkMap) OverpassChecker.scan(guest, radius) ?: return PlanOutcome.MapUnavailable else null

        val plan = withContext(Dispatchers.Default) {
            var found: ZonePlan? = null
            for (attempt in 0 until SEPARATE_DISTANCES) {
                val distance = Random.nextDouble(difficulty.minDistanceM, difficulty.maxDistanceM)
                val hostZone = pickClear(hostScan, difficulty.zoneRadiusM, ring(host, distance)) ?: continue
                val guestZone = pickClear(guestScan, difficulty.zoneRadiusM, ring(guest, distance)) ?: continue
                found = ZonePlan(false, distance, hostZone, guestZone, checkMap)
                break
            }
            found
        } ?: return PlanOutcome.NoOpenSpace
        return PlanOutcome.Planned(plan)
    }

    /** Points exactly [distance] from [origin], spread all the way round, starting at a random bearing. */
    private fun ring(origin: GeoPoint, distance: Double): List<GeoPoint> {
        val start = Random.nextDouble(0.0, 360.0)
        val step = 360.0 / BEARINGS_PER_DISTANCE
        return List(BEARINGS_PER_DISTANCE) { i -> GeoUtils.destination(origin, distance, start + i * step) }
    }
}
