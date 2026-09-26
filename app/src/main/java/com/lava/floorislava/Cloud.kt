package com.lava.floorislava

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint

/** A player's public profile, as shown on the leaderboards. */
data class PlayerProfile(
    val uid: String,
    val username: String,
    val points: Long,
    val wins: Long,
    val games: Long,
    val multiplayerWins: Long,
    val areaKey: String,
    val areaName: String
) {
    companion object {
        fun from(doc: DocumentSnapshot): PlayerProfile? {
            val username = doc.getString("username") ?: return null
            return PlayerProfile(
                uid = doc.id,
                username = username,
                points = doc.getLong("points") ?: 0L,
                wins = doc.getLong("wins") ?: 0L,
                games = doc.getLong("games") ?: 0L,
                multiplayerWins = doc.getLong("mpWins") ?: 0L,
                areaKey = doc.getString("areaKey") ?: "",
                areaName = doc.getString("areaName") ?: ""
            )
        }

        /** Most points first; wins, then name, break ties. */
        val RANKING: Comparator<PlayerProfile> =
            compareByDescending<PlayerProfile> { it.points }
                .thenByDescending { it.wins }
                .thenBy { it.username.lowercase() }
    }
}

class UsernameTakenException : Exception("That username is already taken")
class PlayerNotFoundException : Exception("No player with that username")

/**
 * Everything that talks to Firebase: accounts, the player profile and score,
 * friends and leaderboards. Multiplayer matches live in [Matches].
 *
 * Firestore layout:
 *   users/{uid}                 profile + score (public to signed-in players)
 *   users/{uid}/friends/{uid}   one doc per friend
 *   usernames/{lowercase name}  { uid } — makes usernames unique
 *   matches/{code}              multiplayer matches
 */
object Cloud {

    private val USERNAME_PATTERN = Regex("^[A-Za-z0-9_]{3,16}$")

    /** False when the APK was built without app/google-services.json. */
    fun isConfigured(context: Context): Boolean = FirebaseApp.getApps(context).isNotEmpty()

    fun isSignedIn(context: Context): Boolean = isConfigured(context) && auth.currentUser != null

    val auth: FirebaseAuth get() = FirebaseAuth.getInstance()
    val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()

    /** Signed-in player's id, or null (also null in builds without Firebase, instead of crashing). */
    val uid: String? get() = runCatching { auth.currentUser?.uid }.getOrNull()

    private fun users() = db.collection("users")
    private fun userDoc(uid: String) = users().document(uid)

    fun isValidUsername(name: String) = USERNAME_PATTERN.matches(name)

    // --- Accounts ---

    /**
     * Creates the Firebase account, then claims the username and writes the
     * profile in one transaction. If the name turns out to be taken, the
     * just-created account is deleted again so the email can be reused.
     */
    suspend fun signUp(username: String, email: String, password: String) {
        val result = auth.createUserWithEmailAndPassword(email, password).await()
        val user = result.user ?: throw IllegalStateException("Sign up failed — please try again")
        try {
            createProfile(user.uid, username)
        } catch (e: Exception) {
            runCatching { user.delete().await() }
            auth.signOut()
            throw e
        }
    }

    suspend fun signIn(email: String, password: String) {
        auth.signInWithEmailAndPassword(email, password).await()
    }

    /**
     * The OAuth web client id that Google sign-in needs. It is generated from
     * google-services.json only once the Google provider is switched on in
     * Firebase, so it is looked up by name instead of R.string to keep the
     * app building without it. Null means Google sign-in isn't set up yet.
     */
    fun googleWebClientId(context: Context): String? {
        val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        return if (id != 0) context.getString(id) else null
    }

    /** Signs in to Firebase with a Google ID token from Credential Manager. */
    suspend fun signInWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential).await()
    }

    /** True when the signed-in player has no username yet (first Google sign-in). */
    suspend fun needsUsername(): Boolean {
        val me = uid ?: return false
        return userDoc(me).get().await().getString("username") == null
    }

    /** Gives the signed-in player [username] and a fresh profile. */
    suspend fun claimUsername(username: String) {
        val me = uid ?: throw IllegalStateException("Not signed in")
        createProfile(me, username)
    }

    /**
     * Claims usernames/{name} and writes users/{uid} in one transaction, so
     * two players can never end up with the same name.
     */
    private suspend fun createProfile(uid: String, username: String) {
        val nameRef = db.collection("usernames").document(username.lowercase())
        try {
            db.runTransaction { tx ->
                if (tx.get(nameRef).exists()) throw UsernameTakenException()
                tx.set(nameRef, mapOf("uid" to uid))
                tx.set(
                    userDoc(uid),
                    mapOf(
                        "username" to username,
                        "usernameLower" to username.lowercase(),
                        "points" to 0L,
                        "wins" to 0L,
                        "games" to 0L,
                        "mpWins" to 0L,
                        "areaKey" to "",
                        "areaName" to "",
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
                null
            }.await()
        } catch (e: Exception) {
            val taken = e is UsernameTakenException || e.cause is UsernameTakenException
            throw if (taken) UsernameTakenException() else e
        }
    }

    fun signOut() = auth.signOut()

    suspend fun myProfile(): PlayerProfile? {
        val me = uid ?: return null
        return PlayerProfile.from(userDoc(me).get().await())
    }

    // --- Score ---

    /** Adds a finished round to the signed-in player's lifetime score. Never throws. */
    fun recordRoundInBackground(difficulty: Difficulty, won: Boolean, elapsedMs: Long) {
        val me = uid ?: return
        LavaApp.appScope.launch {
            runCatching {
                val ref = userDoc(me)
                db.runTransaction { tx ->
                    val snapshot = tx.get(ref)
                    val updates = hashMapOf<String, Any>(
                        "games" to FieldValue.increment(1L),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    if (won) {
                        updates["wins"] = FieldValue.increment(1L)
                        updates["points"] = FieldValue.increment(difficulty.pointsForEscape(elapsedMs).toLong())
                        val bestKey = "best_${difficulty.name}"
                        val best = snapshot.getLong(bestKey) ?: 0L
                        if (best == 0L || elapsedMs < best) updates[bestKey] = elapsedMs
                    }
                    tx.set(ref, updates, SetOptions.merge())
                    null
                }.await()
            }
        }
    }

    /** Bonus for beating a friend in a multiplayer race. Never throws. */
    fun recordMultiplayerWinInBackground() {
        val me = uid ?: return
        LavaApp.appScope.launch {
            runCatching {
                userDoc(me).set(
                    mapOf(
                        "mpWins" to FieldValue.increment(1L),
                        "points" to FieldValue.increment(Difficulty.MULTIPLAYER_WIN_BONUS.toLong())
                    ),
                    SetOptions.merge()
                ).await()
            }
        }
    }

    // --- Area ---

    private var lastAreaKey: String? = null

    /** Works out the player's city from [point] and saves it on their profile. Never throws. */
    fun updateAreaInBackground(context: Context, point: GeoPoint) {
        val me = uid ?: return
        val appContext = context.applicationContext
        LavaApp.appScope.launch {
            runCatching {
                val area = withContext(Dispatchers.IO) {
                    Area.resolve(appContext, point.latitude, point.longitude)
                }
                if (area.key == lastAreaKey) return@runCatching
                userDoc(me).set(
                    mapOf("areaKey" to area.key, "areaName" to area.name),
                    SetOptions.merge()
                ).await()
                lastAreaKey = area.key
            }
        }
    }

    // --- Friends ---

    /** Adds the player called [username] as a friend (both ways). Returns their profile. */
    suspend fun addFriendByUsername(username: String): PlayerProfile {
        val me = uid ?: throw IllegalStateException("Not signed in")
        val nameDoc = db.collection("usernames").document(username.trim().lowercase()).get().await()
        val friendUid = nameDoc.getString("uid") ?: throw PlayerNotFoundException()
        if (friendUid == me) throw IllegalArgumentException("That's you!")
        addFriend(friendUid)
        return PlayerProfile.from(userDoc(friendUid).get().await()) ?: throw PlayerNotFoundException()
    }

    /** Makes [friendUid] and the signed-in player friends of each other. */
    suspend fun addFriend(friendUid: String) {
        val me = uid ?: return
        if (friendUid == me) return
        val since = mapOf("since" to FieldValue.serverTimestamp())
        db.batch()
            .set(userDoc(me).collection("friends").document(friendUid), since)
            .set(userDoc(friendUid).collection("friends").document(me), since)
            .commit()
            .await()
    }

    // --- Leaderboards ---

    suspend fun globalLeaderboard(): List<PlayerProfile> =
        users()
            .orderBy("points", Query.Direction.DESCENDING)
            .limit(50)
            .get().await()
            .documents.mapNotNull(PlayerProfile::from)
            .sortedWith(PlayerProfile.RANKING)

    /** You and everyone you've added or raced against. */
    suspend fun friendsLeaderboard(): List<PlayerProfile> {
        val me = uid ?: return emptyList()
        val friendIds = userDoc(me).collection("friends").get().await().documents.map { it.id }
        val ids = (friendIds + me).distinct()
        // Firestore "in" queries take at most 10 ids at a time.
        return ids.chunked(10).flatMap { chunk ->
            users().whereIn(FieldPath.documentId(), chunk).get().await()
                .documents.mapNotNull(PlayerProfile::from)
        }.sortedWith(PlayerProfile.RANKING)
    }

    /**
     * Players whose home area matches [areaKey]. Sorted on the phone so the
     * query needs no composite index in Firestore.
     */
    suspend fun areaLeaderboard(areaKey: String): List<PlayerProfile> {
        if (areaKey.isBlank()) return emptyList()
        return users()
            .whereEqualTo("areaKey", areaKey)
            .limit(500)
            .get().await()
            .documents.mapNotNull(PlayerProfile::from)
            .sortedWith(PlayerProfile.RANKING)
            .take(50)
    }
}
