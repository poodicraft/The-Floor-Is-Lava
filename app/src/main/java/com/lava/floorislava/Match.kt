package com.lava.floorislava

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import org.osmdroid.util.GeoPoint
import kotlin.random.Random

/** Which side of a match a player is on. Field names in Firestore are prefixed with it. */
enum class Role(val prefix: String) {
    HOST("host"),
    GUEST("guest");

    val other: Role get() = if (this == HOST) GUEST else HOST
}

object MatchStatus {
    const val WAITING = "waiting"     // created, no friend yet
    const val LOBBY = "lobby"         // both players in, sharing locations
    const val PLANNING = "planning"   // host is finding safe zones
    const val RUNNING = "running"     // zones set, race on
    const val CANCELLED = "cancelled"
}

object Outcome {
    const val ESCAPED = "escaped"
    const val TIMEOUT = "timeout"
    const val QUIT = "quit"
}

enum class RaceResult { PENDING, WON, LOST, DRAW, NOBODY }

/** One multiplayer race, stored at matches/{code}. */
data class Match(
    val code: String,
    val hostUid: String,
    val hostName: String,
    val guestUid: String?,
    val guestName: String?,
    val difficulty: Difficulty,
    val status: String,
    val sameSpot: Boolean,
    val targetDistanceM: Double,
    val mapChecked: Boolean,
    val error: String?,
    private val doc: DocumentSnapshot
) {
    fun roleOf(uid: String?): Role? = when (uid) {
        null -> null
        hostUid -> Role.HOST
        guestUid -> Role.GUEST
        else -> null
    }

    fun nameOf(role: Role): String = (if (role == Role.HOST) hostName else guestName) ?: "Friend"

    fun location(role: Role): GeoPoint? = point("${role.prefix}Lat", "${role.prefix}Lng")

    fun zone(role: Role): GeoPoint? = point("${role.prefix}ZoneLat", "${role.prefix}ZoneLng")

    fun outcome(role: Role): String? = doc.getString("${role.prefix}Outcome")

    fun timeMs(role: Role): Long? = doc.getLong("${role.prefix}TimeMs")

    fun remainingM(role: Role): Double? = doc.getDouble("${role.prefix}RemainingM")

    val hasGuest: Boolean get() = guestUid != null

    private fun point(latKey: String, lngKey: String): GeoPoint? {
        val lat = doc.getDouble(latKey) ?: return null
        val lng = doc.getDouble(lngKey) ?: return null
        return GeoPoint(lat, lng)
    }

    /**
     * The race result from [me]'s point of view, as far as it's known.
     * [myOutcome]/[myTime] let the caller pass its own result before the
     * write has come back from Firestore.
     */
    fun resultFor(me: Role, myOutcome: String? = outcome(me), myTime: Long? = timeMs(me)): RaceResult {
        val mine = myOutcome
        val theirs = outcome(me.other)
        return when {
            mine == Outcome.ESCAPED && theirs == Outcome.ESCAPED -> {
                val myTimeMs = myTime ?: Long.MAX_VALUE
                val theirTime = timeMs(me.other) ?: Long.MAX_VALUE
                when {
                    myTimeMs < theirTime -> RaceResult.WON
                    myTimeMs > theirTime -> RaceResult.LOST
                    else -> RaceResult.DRAW
                }
            }
            mine == Outcome.ESCAPED && theirs != null -> RaceResult.WON
            mine == Outcome.ESCAPED -> RaceResult.PENDING
            mine != null && theirs == Outcome.ESCAPED -> RaceResult.LOST
            mine != null && theirs != null -> RaceResult.NOBODY
            mine != null -> RaceResult.LOST
            else -> RaceResult.PENDING
        }
    }

    companion object {
        fun from(doc: DocumentSnapshot): Match? {
            if (!doc.exists()) return null
            return Match(
                code = doc.id,
                hostUid = doc.getString("hostUid") ?: return null,
                hostName = doc.getString("hostName") ?: "Host",
                guestUid = doc.getString("guestUid"),
                guestName = doc.getString("guestName"),
                difficulty = Difficulty.fromName(doc.getString("difficulty")),
                status = doc.getString("status") ?: MatchStatus.WAITING,
                sameSpot = doc.getBoolean("sameSpot") ?: false,
                targetDistanceM = doc.getDouble("targetDistanceM") ?: 0.0,
                mapChecked = doc.getBoolean("mapChecked") ?: true,
                error = doc.getString("error"),
                doc = doc
            )
        }
    }
}

class MatchNotFoundException : Exception("No match with that code")
class MatchFullException : Exception("That match already has two players")
class OwnMatchException : Exception("That's your own match code — send it to your friend")

/** Creating, joining and syncing multiplayer matches in Firestore. */
object Matches {

    // No 0/O or 1/I so codes are easy to read out loud.
    private const val CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val CODE_LENGTH = 5

    private fun doc(code: String) = Cloud.db.collection("matches").document(code)

    fun normalizeCode(raw: String): String = raw.trim().uppercase().filter { it in CODE_CHARS }

    /** Creates a match and returns its join code. */
    suspend fun create(difficulty: Difficulty, hostName: String): String {
        val me = Cloud.uid ?: throw IllegalStateException("Not signed in")
        repeat(8) {
            val code = (1..CODE_LENGTH).map { CODE_CHARS[Random.nextInt(CODE_CHARS.length)] }.joinToString("")
            val ref = doc(code)
            val created = Cloud.db.runTransaction { tx ->
                if (tx.get(ref).exists()) {
                    false
                } else {
                    tx.set(
                        ref,
                        mapOf(
                            "hostUid" to me,
                            "hostName" to hostName,
                            "guestUid" to null,
                            "guestName" to null,
                            "difficulty" to difficulty.name,
                            "status" to MatchStatus.WAITING,
                            "createdAt" to FieldValue.serverTimestamp()
                        )
                    )
                    true
                }
            }.await()
            if (created) return code
        }
        throw IllegalStateException("Couldn't create a match — please try again")
    }

    /** Joins the match [code] as the guest. */
    suspend fun join(code: String, guestName: String) {
        val me = Cloud.uid ?: throw IllegalStateException("Not signed in")
        val ref = doc(code)
        Cloud.db.runTransaction { tx ->
            val snapshot = tx.get(ref)
            // A match the host already left reads as "not found", not as "full".
            if (!snapshot.exists() || snapshot.getString("status") == MatchStatus.CANCELLED) {
                throw MatchNotFoundException()
            }
            val hostUid = snapshot.getString("hostUid")
            val guestUid = snapshot.getString("guestUid")
            val status = snapshot.getString("status")
            when {
                hostUid == me -> throw OwnMatchException()
                guestUid == me -> Unit // rejoining after leaving the screen
                guestUid != null || status != MatchStatus.WAITING -> throw MatchFullException()
                else -> tx.update(
                    ref,
                    mapOf("guestUid" to me, "guestName" to guestName, "status" to MatchStatus.LOBBY)
                )
            }
            null
        }.await()
    }

    fun listen(code: String, onChange: (Match?) -> Unit): ListenerRegistration =
        doc(code).addSnapshotListener { snapshot, _ ->
            onChange(snapshot?.let { Match.from(it) })
        }

    fun publishLocation(code: String, role: Role, point: GeoPoint) {
        doc(code).set(
            mapOf("${role.prefix}Lat" to point.latitude, "${role.prefix}Lng" to point.longitude),
            SetOptions.merge()
        )
    }

    fun setStatus(code: String, status: String, error: String? = null) {
        doc(code).update(mapOf("status" to status, "error" to error))
    }

    suspend fun publishPlan(code: String, plan: ZonePlan) {
        doc(code).update(
            mapOf(
                "sameSpot" to plan.sameSpot,
                "targetDistanceM" to plan.distanceM,
                "mapChecked" to plan.mapChecked,
                "hostZoneLat" to plan.hostZone.latitude,
                "hostZoneLng" to plan.hostZone.longitude,
                "guestZoneLat" to plan.guestZone.latitude,
                "guestZoneLng" to plan.guestZone.longitude,
                "status" to MatchStatus.RUNNING,
                "error" to null,
                "startedAt" to FieldValue.serverTimestamp()
            )
        ).await()
    }

    fun reportProgress(code: String, role: Role, remainingM: Double) {
        doc(code).update("${role.prefix}RemainingM", remainingM)
    }

    fun reportOutcome(code: String, role: Role, outcome: String, timeMs: Long) {
        doc(code).update(
            mapOf("${role.prefix}Outcome" to outcome, "${role.prefix}TimeMs" to timeMs)
        )
    }

    /** Host leaving cancels the match; a guest leaving frees the seat for someone else. */
    fun leave(code: String, role: Role) {
        if (role == Role.HOST) {
            setStatus(code, MatchStatus.CANCELLED)
        } else {
            doc(code).update(
                mapOf(
                    "guestUid" to null,
                    "guestName" to null,
                    "guestLat" to null,
                    "guestLng" to null,
                    "status" to MatchStatus.WAITING
                )
            )
        }
    }
}
