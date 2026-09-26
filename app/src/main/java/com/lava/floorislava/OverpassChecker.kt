package com.lava.floorislava

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * The blocking OpenStreetMap features around one spot: buildings, water,
 * construction sites and private areas as shapes you can't stand inside, and
 * roads, railways, fences and walls as lines you must keep clear of.
 *
 * Fetched ONCE per round by [OverpassChecker.scan]; any number of candidate
 * safe-zone points can then be tested on the phone, instantly and offline.
 */
class AreaScan internal constructor(
    private val areas: List<List<GeoPoint>>,
    private val lines: List<List<GeoPoint>>
) {
    val featureCount: Int get() = areas.size + lines.size

    /** True when [point] is outside every blocking shape and far enough from every blocking line. */
    fun isClear(point: GeoPoint): Boolean {
        for (area in areas) {
            if (area.size >= 3 && isPointInsidePolygon(point, area)) return false
        }
        for (line in lines) {
            if (line.size >= 2 && distanceToPolyline(point, line) <= OverpassChecker.LINE_BUFFER_METERS) return false
        }
        return true
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

    /** Shortest distance in meters from [point] to any segment of [polyline]. */
    private fun distanceToPolyline(point: GeoPoint, polyline: List<GeoPoint>): Double {
        var minDistance = Double.MAX_VALUE
        for (i in 0 until polyline.size - 1) {
            val d = distanceToSegment(point, polyline[i], polyline[i + 1])
            if (d < minDistance) minDistance = d
        }
        return minDistance
    }

    /** Distance from a point to a segment, using an equirectangular projection (fine at this scale). */
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

        val ddx = px - (ax + t * dx)
        val ddy = py - (ay + t * dy)
        return kotlin.math.sqrt(ddx * ddx + ddy * ddy)
    }
}

/**
 * Fetches blocking map features from OpenStreetMap's free Overpass API (no key
 * required), so a safe zone never lands inside a building, in water, on a
 * road or somewhere private.
 *
 * One request covers the whole area a round can use. The public Overpass
 * servers reject Android's default "Dalvik/..." User-Agent outright (406 from
 * overpass-api.de, 429 from the others), so requests name the app. They are
 * also busy (10–15 s answers, some time out), so two servers are asked at
 * once and the first complete answer wins; if both fail, the other two are
 * tried. The server that answered is asked first next time.
 *
 * A partial answer (the server ran out of time) is treated as a failure:
 * a missing building must never read as "clear".
 */
object OverpassChecker {

    /** How close a candidate may get to a road, railway, fence or wall. */
    const val LINE_BUFFER_METERS = 8.0

    private val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter"
    )
    private const val SERVERS_PER_WAVE = 2
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val SERVER_TIMEOUT_S = 25
    private const val USER_AGENT = "FloorIsLava/1.2 (Android game; github.com/poodicraft/The-Floor-Is-Lava)"

    @Volatile
    private var preferredEndpoint = 0

    /** Requests run here so a slow loser can finish (or time out) without holding up the winner. */
    private val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Fetches everything that could block a safe zone within [radiusMeters]
     * of [center]. Returns null only when no server could be reached or none
     * gave a complete answer.
     */
    suspend fun scan(center: GeoPoint, radiusMeters: Double): AreaScan? {
        val query = buildQuery(center, radiusMeters)
        val order = ENDPOINTS.indices.map { (preferredEndpoint + it) % ENDPOINTS.size }
        for (wave in order.chunked(SERVERS_PER_WAVE)) {
            val elements = firstAnswer(wave, query) ?: continue
            return parse(elements)
        }
        return null
    }

    /** Asks every server in [indices] at once; the first complete answer wins, null if all fail. */
    private suspend fun firstAnswer(indices: List<Int>, query: String): JSONArray? {
        val winner = CompletableDeferred<JSONArray?>()
        val pending = AtomicInteger(indices.size)
        for (index in indices) {
            requestScope.launch {
                val elements = fetch(ENDPOINTS[index], query)
                if (elements != null && winner.complete(elements)) preferredEndpoint = index
                if (pending.decrementAndGet() == 0) winner.complete(null)
            }
        }
        return winner.await()
    }

    private fun buildQuery(center: GeoPoint, radiusMeters: Double): String {
        // Locale.US keeps the decimal point a "." on phones set to e.g. German.
        val r = String.format(Locale.US, "%.0f", radiusMeters + LINE_BUFFER_METERS)
        val at = String.format(Locale.US, "%.6f,%.6f", center.latitude, center.longitude)
        val around = "(around:$r,$at)"
        return """
            [out:json][timeout:$SERVER_TIMEOUT_S];
            (
              way["building"]$around;
              relation["building"]$around;
              way["natural"="water"]$around;
              relation["natural"="water"]$around;
              way["waterway"="riverbank"]$around;
              way["landuse"="construction"]$around;
              way["leisure"="swimming_pool"]$around;
              way["barrier"~"^(fence|wall)$"]$around;
              way["access"~"^(private|no)$"]$around;
              way["highway"~"^(motorway|trunk|primary|secondary)(_link)?$"]$around;
              way["railway"~"^(rail|light_rail|subway|tram)$"]$around;
            );
            out geom qt;
        """.trimIndent()
    }

    /** One POST to one server. Null on any failure, including a partial (timed-out) answer. */
    private fun fetch(endpoint: String, query: String): JSONArray? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                // Without this the servers answer 406/429 to Android's default "Dalvik/..." agent.
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val body = "data=" + URLEncoder.encode(query, "UTF-8")
            connection.outputStream.use { it.write(body.toByteArray()) }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            // Overpass reports running out of time or memory in "remark" while
            // still answering 200 with whatever it found so far.
            val remark = json.optString("remark", "")
            if (remark.contains("error", ignoreCase = true)) return null
            json.optJSONArray("elements")
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    internal fun parse(elements: JSONArray): AreaScan {
        val areas = ArrayList<List<GeoPoint>>()
        val lines = ArrayList<List<GeoPoint>>()

        for (i in 0 until elements.length()) {
            val element = elements.optJSONObject(i) ?: continue
            val tags = element.optJSONObject("tags") ?: JSONObject()
            val isLineFeature = tags.has("highway") || tags.has("railway") || tags.has("barrier")

            val direct = element.optJSONArray("geometry")
            if (direct != null) {
                val shape = toPoints(direct)
                when {
                    isLineFeature -> lines.add(shape)
                    // An access-restricted way that isn't closed is a private road or path.
                    tags.has("access") && !isClosed(shape) -> lines.add(shape)
                    else -> areas.add(shape)
                }
                continue
            }

            // Relations (multipolygons): closed outer rings are areas; ring
            // pieces that aren't closed on their own still count as edges.
            val members = element.optJSONArray("members") ?: continue
            for (m in 0 until members.length()) {
                val member = members.optJSONObject(m) ?: continue
                if (member.optString("role") == "inner") continue
                val geometry = member.optJSONArray("geometry") ?: continue
                val shape = toPoints(geometry)
                if (isClosed(shape)) areas.add(shape) else lines.add(shape)
            }
        }
        return AreaScan(areas, lines)
    }

    private fun toPoints(geometry: JSONArray): List<GeoPoint> {
        val points = ArrayList<GeoPoint>(geometry.length())
        for (j in 0 until geometry.length()) {
            val node = geometry.optJSONObject(j) ?: continue
            if (!node.has("lat") || !node.has("lon")) continue
            points.add(GeoPoint(node.getDouble("lat"), node.getDouble("lon")))
        }
        return points
    }

    private fun isClosed(shape: List<GeoPoint>): Boolean =
        shape.size >= 4 &&
            shape.first().latitude == shape.last().latitude &&
            shape.first().longitude == shape.last().longitude
}
