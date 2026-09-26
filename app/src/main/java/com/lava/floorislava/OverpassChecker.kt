package com.lava.floorislava

import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The blocking OpenStreetMap features around one spot: buildings, water,
 * construction sites, parking lots and private areas as shapes you can't
 * stand inside, and roads, railways, fences and walls as lines you must keep
 * clear of. Each line has its own keep-away distance ([Line.buffer]): roads
 * are mapped as a centre line, so a wide road needs a bigger one.
 *
 * Fetched ONCE per round by [OverpassChecker.scan]; any number of candidate
 * safe-zone points can then be tested on the phone, instantly and offline.
 */
class AreaScan internal constructor(
    private val areas: List<List<GeoPoint>>,
    private val lines: List<Line>
) {
    class Line(val points: List<GeoPoint>, val buffer: Double)

    val featureCount: Int get() = areas.size + lines.size

    /** True when [point] is outside every blocking shape and far enough from every blocking line. */
    fun isClear(point: GeoPoint): Boolean = isZoneClear(point, 0.0)

    /**
     * True when the whole safe circle ([radiusM] around [center]) stays off
     * every road/railway/fence buffer and doesn't overlap any blocked area.
     */
    fun isZoneClear(center: GeoPoint, radiusM: Double): Boolean =
        isOffAllLines(center, radiusM) && isOutsideAllAreas(center, radiusM)

    /**
     * Like [isZoneClear], but the circle may brush against a building or
     * other area as long as its centre is outside it. Roads still have to be
     * completely clear.
     */
    fun isZoneOffRoads(center: GeoPoint, radiusM: Double): Boolean =
        isOffAllLines(center, radiusM) && isOutsideAllAreas(center, 0.0)

    private fun isOffAllLines(center: GeoPoint, radiusM: Double): Boolean {
        for (line in lines) {
            if (line.points.size >= 2 && distanceToPolyline(center, line.points) <= line.buffer + radiusM) return false
        }
        return true
    }

    private fun isOutsideAllAreas(center: GeoPoint, radiusM: Double): Boolean {
        for (area in areas) {
            if (area.size < 3) continue
            if (isPointInsidePolygon(center, area)) return false
            if (radiusM > 0 && distanceToPolyline(center, area) < radiusM) return false
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
 * also slow and flaky (3–50 s answers, some time out), so:
 * - two servers are asked at once and the first complete answer wins; if
 *   neither has answered after [HEDGE_AFTER_MS], the other two are asked too;
 * - the game screen [prefetch]es the area as soon as GPS is known, and a scan
 *   is reused while the player stays inside it, so START is usually instant.
 *
 * A partial answer (the server ran out of time) is treated as a failure:
 * a missing building must never read as "clear".
 */
object OverpassChecker {

    /** Extra distance kept from the edge of any road, railway, fence or wall. */
    private const val SAFETY_MARGIN_METERS = 4.0

    /** The largest keep-away distance any line gets; the query fetches this much further out. */
    private const val MAX_LINE_BUFFER_METERS = 25.0

    private val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter"
    )
    private const val SERVERS_PER_WAVE = 2
    private const val HEDGE_AFTER_MS = 12_000L

    /**
     * Prefetches cover this radius: enough for Hard (70 m + a 5 m zone) with
     * ~35 m to spare, so the scan stays usable if the player walks a bit.
     */
    const val PREFETCH_RADIUS_METERS = 110.0
    private const val CACHE_MAX_AGE_MS = 30 * 60 * 1000L
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val SERVER_TIMEOUT_S = 25
    private const val USER_AGENT = "FloorIsLava/1.2 (Android game; github.com/poodicraft/The-Floor-Is-Lava)"

    @Volatile
    private var preferredEndpoint = 0

    /** Requests run here so a slow loser can finish (or time out) without holding up the winner. */
    private val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Coverage(val center: GeoPoint, val radius: Double) {
        fun covers(point: GeoPoint, radiusNeeded: Double): Boolean =
            GeoUtils.distanceMeters(center, point) + radiusNeeded <= radius
    }

    private class CachedScan(val coverage: Coverage, val scan: AreaScan, val fetchedAt: Long)
    private class PendingScan(val coverage: Coverage, val result: Deferred<AreaScan?>)

    // A few recent scans (e.g. host and guest areas in a multiplayer race).
    private const val MAX_CACHED = 4
    private val lock = Any()
    private val cachedScans = ArrayList<CachedScan>()
    private val pendingScans = ArrayList<PendingScan>()

    private fun now() = SystemClock.elapsedRealtime()

    private fun cachedCovering(center: GeoPoint, radius: Double): AreaScan? = synchronized(lock) {
        val fresh = now() - CACHE_MAX_AGE_MS
        cachedScans.removeAll { it.fetchedAt < fresh }
        cachedScans.firstOrNull { it.coverage.covers(center, radius) }?.scan
    }

    private fun pendingCovering(center: GeoPoint, radius: Double): PendingScan? = synchronized(lock) {
        pendingScans.firstOrNull { it.coverage.covers(center, radius) }
    }

    /**
     * Starts fetching the area around [center] in the background, unless a
     * fresh or in-flight scan already covers it. Safe to call on every GPS fix.
     */
    fun prefetch(center: GeoPoint) {
        val needed = PREFETCH_RADIUS_METERS - 30.0
        if (cachedCovering(center, needed) != null || pendingCovering(center, needed) != null) return
        startScan(center, PREFETCH_RADIUS_METERS)
    }

    private fun startScan(center: GeoPoint, radius: Double): PendingScan {
        val result = CompletableDeferred<AreaScan?>()
        val scan = PendingScan(Coverage(center, radius), result)
        synchronized(lock) { pendingScans.add(scan) }
        requestScope.launch {
            val elements = firstAnswer(buildQuery(center, radius))
            val area = elements?.let { parse(it) }
            synchronized(lock) {
                pendingScans.remove(scan)
                if (area != null) {
                    cachedScans.add(0, CachedScan(scan.coverage, area, now()))
                    while (cachedScans.size > MAX_CACHED) cachedScans.removeAt(cachedScans.size - 1)
                }
            }
            result.complete(area)
        }
        return scan
    }

    /**
     * Fetches everything that could block a safe zone within [radiusMeters]
     * of [center] — from a recent or in-flight scan when one covers it.
     * Returns null only when no server could be reached or none gave a
     * complete answer.
     */
    suspend fun scan(center: GeoPoint, radiusMeters: Double): AreaScan? {
        cachedCovering(center, radiusMeters)?.let { return it }
        // A prefetch that's already on its way is usually the quickest answer.
        pendingCovering(center, radiusMeters)?.result?.await()?.let { return it }
        return startScan(center, maxOf(radiusMeters, PREFETCH_RADIUS_METERS)).result.await()
    }

    /**
     * Asks the servers in waves of [SERVERS_PER_WAVE]. The next wave starts
     * when the current one has failed, or after [HEDGE_AFTER_MS] without an
     * answer. The first complete answer wins; null when every server failed.
     */
    private suspend fun firstAnswer(query: String): JSONArray? {
        val order = ENDPOINTS.indices.map { (preferredEndpoint + it) % ENDPOINTS.size }
        val waves = order.chunked(SERVERS_PER_WAVE)
        val winner = CompletableDeferred<JSONArray?>()
        val outstanding = AtomicInteger(0)
        val allLaunched = AtomicBoolean(false)

        for ((waveIndex, wave) in waves.withIndex()) {
            val isLast = waveIndex == waves.lastIndex
            outstanding.addAndGet(wave.size)
            if (isLast) allLaunched.set(true)
            for (index in wave) {
                requestScope.launch {
                    val elements = fetch(ENDPOINTS[index], query)
                    if (elements != null && winner.complete(elements)) preferredEndpoint = index
                    if (outstanding.decrementAndGet() == 0 && allLaunched.get()) winner.complete(null)
                }
            }
            if (isLast) break
            withTimeoutOrNull(HEDGE_AFTER_MS) {
                while (!winner.isCompleted && outstanding.get() > 0) delay(200L)
            }
            if (winner.isCompleted) break
        }
        return winner.await()
    }

    private fun buildQuery(center: GeoPoint, radiusMeters: Double): String {
        // Locale.US keeps the decimal point a "." on phones set to e.g. German.
        val r = String.format(Locale.US, "%.0f", radiusMeters + MAX_LINE_BUFFER_METERS)
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
              way["highway"~"^(motorway|trunk|primary|secondary|tertiary)(_link)?$"]$around;
              way["highway"~"^(unclassified|residential|living_street|service|road|busway|cycleway)$"]$around;
              way["amenity"="parking"]$around;
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
        val lines = ArrayList<AreaScan.Line>()

        for (i in 0 until elements.length()) {
            val element = elements.optJSONObject(i) ?: continue
            val tags = element.optJSONObject("tags") ?: JSONObject()
            val lineBuffer = lineBuffer(tags)
            // Footpaths, pedestrian streets and the like are fine to stand on.
            if (lineBuffer == null && tags.has("highway")) continue

            val direct = element.optJSONArray("geometry")
            if (direct != null) {
                val shape = toPoints(direct)
                when {
                    lineBuffer != null -> lines.add(AreaScan.Line(shape, lineBuffer))
                    // An access-restricted way that isn't closed is a private road or path.
                    tags.has("access") && !isClosed(shape) -> lines.add(AreaScan.Line(shape, DEFAULT_LINE_BUFFER))
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
                if (isClosed(shape)) areas.add(shape) else lines.add(AreaScan.Line(shape, DEFAULT_LINE_BUFFER))
            }
        }
        return AreaScan(areas, lines)
    }

    private const val DEFAULT_LINE_BUFFER = 4.0 + SAFETY_MARGIN_METERS
    private val WALKABLE_HIGHWAYS = setOf(
        "footway", "path", "pedestrian", "steps", "bridleway", "corridor", "track", "platform"
    )
    private const val LANE_WIDTH_METERS = 3.5

    /**
     * How far a safe zone must stay from this line's centre: half the road's
     * width plus [SAFETY_MARGIN_METERS]. Uses the mapped `width` or `lanes`
     * when present, else a typical width for the road type. Null when the
     * feature isn't a line to keep away from (e.g. a building).
     */
    internal fun lineBuffer(tags: JSONObject): Double? {
        val highway = tags.optString("highway", "")
        if (highway in WALKABLE_HIGHWAYS) return null
        if (highway.isNotEmpty()) {
            val typicalHalfWidth = when (highway.removeSuffix("_link")) {
                "motorway", "trunk" -> 12.0
                "primary" -> 9.0
                "secondary" -> 7.5
                "tertiary" -> 6.0
                "unclassified", "residential", "living_street", "road", "busway" -> 4.5
                "service" -> 3.5
                "cycleway" -> 2.0
                else -> 4.0
            }
            val mappedHalfWidth = mappedWidth(tags)?.div(2)
                ?: tags.optString("lanes", "").toDoubleOrNull()?.let { it * LANE_WIDTH_METERS / 2 }
                ?: 0.0
            val halfWidth = maxOf(typicalHalfWidth, mappedHalfWidth)
            return minOf(halfWidth + SAFETY_MARGIN_METERS, MAX_LINE_BUFFER_METERS)
        }
        if (tags.has("railway")) return 3.0 + SAFETY_MARGIN_METERS
        if (tags.has("barrier")) return 1.0 + SAFETY_MARGIN_METERS
        return null
    }

    /** The `width` tag in metres ("7", "7.5", "7 m"), or null. */
    private fun mappedWidth(tags: JSONObject): Double? =
        tags.optString("width", "").trim().removeSuffix("m").trim().replace(',', '.').toDoubleOrNull()

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
