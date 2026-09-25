package com.lava.floorislava

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Uses OpenStreetMap's free Overpass API (no key required) to check candidate
 * points against real building/water polygon SHAPES — not just "is anything
 * nearby" — so a safe zone can never land inside a building's outline, even
 * for large buildings where the interior is far from any edge.
 *
 * Safety-critical design choice: if the network call fails or times out, we
 * FAIL CLOSED — treat the point as blocked, not clear. A round that takes an
 * extra second to find a verified-clear point is fine; a safe zone that
 * silently lands in someone's living room because a request timed out is not.
 * Each check retries up to 3 times before the caller should just try a
 * different candidate point instead.
 */
object OverpassChecker {

    private const val ENDPOINT = "https://overpass-api.de/api/interpreter"
    private const val SEARCH_RADIUS_METERS = 40 // how far out to fetch shapes to test against
    private const val ROAD_BUFFER_METERS = 8.0 // minimum distance to keep from roads/rails/fences
    private const val TIMEOUT_MS = 7000L
    private const val MAX_RETRIES = 3

    /**
     * Returns true ONLY if [point] was successfully verified as not inside any
     * building or water polygon. Returns false if it's confirmed blocked, OR
     * if verification failed after retries — callers should treat "false" as
     * "don't use this point" either way, and try a different candidate.
     */
    suspend fun isPointClear(point: GeoPoint): Boolean {
        repeat(MAX_RETRIES) {
            val result = tryCheckPointClear(point)
            if (result != null) return result
        }
        return false // fail CLOSED — couldn't verify, so don't risk it
    }

    private suspend fun tryCheckPointClear(point: GeoPoint): Boolean? {
        return try {
            withTimeout(TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    queryBlockingShapes(point)
                }
            }
        } catch (_: TimeoutCancellationException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Fetches building, water, road, railway, and restricted-land features
     * within [SEARCH_RADIUS_METERS] of [point]. Area features (buildings,
     * water, fenced/private land) are tested with real point-in-polygon
     * geometry. Line features (roads, railways) use a proximity buffer
     * instead, since standing 2 meters from a live traffic lane is just as
     * unreachable/unsafe as standing on it, even though it's not literally
     * "inside" a polygon.
     */
    private fun queryBlockingShapes(point: GeoPoint): Boolean {
        val query = """
            [out:json][timeout:6];
            (
              way["building"](around:$SEARCH_RADIUS_METERS,${point.latitude},${point.longitude});
              relation["building"](around:$SEARCH_RADIUS_METERS,${point.latitude},${point.longitude});
              way["natural"="water"](around:$SEARCH_RADIUS_METERS,${point.latitude},${point.longitude});
              way["waterway"="riverbank"](around:$SEARCH_RADIUS_METERS,${point.latitude},${point.longitude});
              way["landuse"="construction"](around:$SEARCH_RADIUS_METERS,${point.latitude},${point.longitude});
              way["leisure"="swimming_pool"](around:$SEARCH_RADIUS_METERS,${point.latitude},${point.longitude});
              way["barrier"="fence"](around:$ROAD_BUFFER_METERS,${point.latitude},${point.longitude});
              way["barrier"="wall"](around:$ROAD_BUFFER_METERS,${point.latitude},${point.longitude});
              way["access"~"private|no"](around:$ROAD_BUFFER_METERS,${point.latitude},${point.longitude});
              way["highway"~"motorway|trunk|primary|secondary"](around:$ROAD_BUFFER_METERS,${point.latitude},${point.longitude});
              way["railway"~"rail|light_rail|subway|tram"](around:$ROAD_BUFFER_METERS,${point.latitude},${point.longitude});
            );
            out geom;
        """.trimIndent()

        val elements = executeQuery(query) ?: return false // fail CLOSED on request failure

        for (i in 0 until elements.length()) {
            val element = elements.getJSONObject(i)
            val polygon = extractPolygon(element) ?: continue
            if (polygon.size < 2) continue

            // Roads/railways/fences come back as lines, not closed shapes —
            // for these, "inside" doesn't apply, so use distance-to-line
            // instead of point-in-polygon.
            val tags = element.optJSONObject("tags")
            val isLineFeature = tags != null && (
                tags.has("highway") || tags.has("railway") || tags.has("barrier") ||
                (tags.has("access") && !tags.has("building"))
            )

            if (isLineFeature) {
                if (distanceToPolyline(point, polygon) <= ROAD_BUFFER_METERS) {
                    return false // blocked — too close to a road/rail/fence line
                }
            } else if (polygon.size >= 3 && isPointInsidePolygon(point, polygon)) {
                return false // blocked — point is inside this area shape
            }
        }
        return true // clear — not inside/near any blocking feature
    }

    /** Shortest distance in meters from [point] to any segment of [polyline]. */
    private fun distanceToPolyline(point: GeoPoint, polyline: List<GeoPoint>): Double {
        var minDistance = Double.MAX_VALUE
        for (i in 0 until polyline.size - 1) {
            val d = distanceToSegment(point, polyline[i], polyline[i + 1])
            if (d < minDistance) minDistance = d
        }
        return minDistance
    }

    /** Approximate distance in meters from a point to a line segment, using an equirectangular projection (fine at this small scale). */
    private fun distanceToSegment(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        val metersPerDegLat = 111_320.0
        val metersPerDegLng = 111_320.0 * kotlin.math.cos(Math.toRadians(p.latitude))

        val px = p.longitude * metersPerDegLng
        val py = p.latitude * metersPerDegLat
        val ax = a.longitude * metersPerDegLng
        val ay = a.latitude * metersPerDegLat
        val bx = b.longitude * metersPerDegLng
        val by = b.latitude * metersPerDegLat

        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy

        val t = if (lengthSquared == 0.0) 0.0 else
            (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0.0, 1.0)

        val closestX = ax + t * dx
        val closestY = ay + t * dy

        val ddx = px - closestX
        val ddy = py - closestY
        return kotlin.math.sqrt(ddx * ddx + ddy * ddy)
    }

    /** Extracts a way's outer ring as a list of GeoPoints from an Overpass "geometry" element. */
    private fun extractPolygon(element: JSONObject): List<GeoPoint>? {
        // Ways have "geometry" directly. Relations (multipolygons) have "members",
        // each with its own "geometry" — use the first outer member as an approximation.
        val directGeometry = element.optJSONArray("geometry")
        val geometryArray = directGeometry ?: run {
            val members = element.optJSONArray("members") ?: return null
            var found: org.json.JSONArray? = null
            for (m in 0 until members.length()) {
                val member = members.getJSONObject(m)
                if (member.optString("role") == "outer" || found == null) {
                    member.optJSONArray("geometry")?.let { found = it }
                }
            }
            found
        } ?: return null

        val polygon = ArrayList<GeoPoint>()
        for (j in 0 until geometryArray.length()) {
            val node = geometryArray.optJSONObject(j) ?: continue
            if (!node.has("lat") || !node.has("lon")) continue
            polygon.add(GeoPoint(node.getDouble("lat"), node.getDouble("lon")))
        }
        return polygon
    }

    private fun executeQuery(query: String): org.json.JSONArray? {
        return try {
            val url = URL(ENDPOINT)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 6000
            connection.readTimeout = 6000
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

            val body = "data=" + URLEncoder.encode(query, "UTF-8")
            connection.outputStream.use { it.write(body.toByteArray()) }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                return null
            }

            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            val json = JSONObject(responseText)
            json.optJSONArray("elements")
        } catch (_: Exception) {
            null
        }
    }

    /** Ray-casting point-in-polygon test. */
    private fun isPointInsidePolygon(point: GeoPoint, polygon: List<GeoPoint>): Boolean {
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val xi = polygon[i].longitude
            val yi = polygon[i].latitude
            val xj = polygon[j].longitude
            val yj = polygon[j].latitude

            val intersects = ((yi > point.latitude) != (yj > point.latitude)) &&
                (point.longitude < (xj - xi) * (point.latitude - yi) / (yj - yi) + xi)
            if (intersects) inside = !inside
            j = i
        }
        return inside
    }
}
