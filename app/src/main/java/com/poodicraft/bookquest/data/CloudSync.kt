package com.poodicraft.bookquest.data

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** Who, if anyone, is signed in. */
sealed class AccountState {
    /** No google-services.json was bundled, so the feature is switched off. */
    data object NotConfigured : AccountState()
    data object SignedOut : AccountState()
    data class SignedIn(val name: String, val email: String) : AccountState()
}

/** What the last backup attempt did. */
sealed class SyncState {
    data object Idle : SyncState()
    data object Working : SyncState()
    data class Done(val at: Long) : SyncState()
    data class Failed(val reason: String) : SyncState()
}

/**
 * Google sign in through Credential Manager, plus a merge based backup of the
 * reading progress into Cloud Firestore.
 *
 * The whole class is inert unless the app was built with a google-services.json:
 * every entry point reports [AccountState.NotConfigured] instead of crashing, so
 * an unconfigured build still runs perfectly well offline.
 */
class CloudSync private constructor(
    private val appContext: Context,
    private val repository: LibraryRepository
) {

    private val _account = MutableStateFlow<AccountState>(AccountState.SignedOut)
    val account: StateFlow<AccountState> = _account.asStateFlow()

    private val _sync = MutableStateFlow<SyncState>(SyncState.Idle)
    val sync: StateFlow<SyncState> = _sync.asStateFlow()

    /**
     * The OAuth web client id. The google-services plugin generates this string
     * resource from google-services.json, so looking it up by name means the app
     * still compiles when the file is absent.
     */
    private val webClientId: String? by lazy {
        val id = appContext.resources.getIdentifier(
            "default_web_client_id",
            "string",
            appContext.packageName
        )
        if (id != 0) appContext.getString(id) else null
    }

    private val auth: FirebaseAuth? by lazy {
        try {
            FirebaseAuth.getInstance()
        } catch (e: Throwable) {
            null
        }
    }

    private val firestore: FirebaseFirestore? by lazy {
        try {
            FirebaseFirestore.getInstance()
        } catch (e: Throwable) {
            null
        }
    }

    val isConfigured: Boolean get() = webClientId != null && auth != null

    init {
        refresh()
    }

    /** Re-reads the current Firebase user into [account]. */
    fun refresh() {
        if (!isConfigured) {
            _account.value = AccountState.NotConfigured
            return
        }
        val user = auth?.currentUser
        _account.value = if (user == null) {
            AccountState.SignedOut
        } else {
            AccountState.SignedIn(
                name = user.displayName ?: user.email.orEmpty(),
                email = user.email.orEmpty()
            )
        }
    }

    /**
     * Shows the Google account chooser and signs the chosen account in to Firebase,
     * then immediately runs a first sync so existing progress is pulled down.
     */
    suspend fun signIn(activity: Activity): Result<Unit> {
        val clientId = webClientId ?: return Result.failure(CloudNotConfigured())
        val firebaseAuth = auth ?: return Result.failure(CloudNotConfigured())

        // The first pass only offers accounts that already use this app, which is
        // the quiet one-tap path. If there are none, ask again with every account.
        val token = requestIdToken(activity, clientId, filterAuthorized = true)
            ?: requestIdToken(activity, clientId, filterAuthorized = false)
            ?: return Result.failure(lastCredentialError ?: SignInCancelled())

        return try {
            val credential = GoogleAuthProvider.getCredential(token, null)
            firebaseAuth.signInWithCredential(credential).await()
            refresh()
            syncNow()
        } catch (e: Exception) {
            _sync.value = SyncState.Failed(e.message.orEmpty())
            Result.failure(e)
        }
    }

    private var lastCredentialError: Exception? = null

    private suspend fun requestIdToken(
        activity: Activity,
        clientId: String,
        filterAuthorized: Boolean
    ): String? {
        return try {
            val option = GetGoogleIdOption.Builder()
                .setServerClientId(clientId)
                .setFilterByAuthorizedAccounts(filterAuthorized)
                .setAutoSelectEnabled(false)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build()
            val response = CredentialManager.create(activity).getCredential(activity, request)
            val credential = response.credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                GoogleIdTokenCredential.createFrom(credential.data).idToken
            } else {
                null
            }
        } catch (e: Exception) {
            lastCredentialError = e
            null
        }
    }

    fun signOut() {
        try {
            auth?.signOut()
        } catch (e: Exception) {
            // Nothing else to do; the state refresh below still runs.
        }
        _sync.value = SyncState.Idle
        refresh()
    }

    /**
     * Pulls the stored snapshot, merges it into local state, and writes the merged
     * result straight back. Running it twice in a row is harmless.
     */
    suspend fun syncNow(): Result<Unit> {
        if (!isConfigured) return Result.failure(CloudNotConfigured())
        val uid = auth?.currentUser?.uid ?: return Result.failure(NotSignedIn())
        val db = firestore ?: return Result.failure(CloudNotConfigured())

        _sync.value = SyncState.Working
        return try {
            withContext(Dispatchers.IO) {
                val document = db.collection("users").document(uid)
                val stored = document.get().await()
                val payload = stored.getString("payload")
                if (!payload.isNullOrBlank()) {
                    repository.mergeSnapshot(payload)
                }
                val merged = repository.exportSnapshot()
                document.set(
                    mapOf(
                        "payload" to merged,
                        "updatedAt" to System.currentTimeMillis()
                    )
                ).await()
            }
            _sync.value = SyncState.Done(System.currentTimeMillis())
            Result.success(Unit)
        } catch (e: Exception) {
            _sync.value = SyncState.Failed(e.message ?: e.javaClass.simpleName)
            Result.failure(e)
        }
    }

    /** Best effort background push, used when the app goes to the background. */
    suspend fun pushQuietly() {
        if (!isConfigured || auth?.currentUser == null) return
        syncNow()
    }

    class CloudNotConfigured : Exception("Google sign in is not configured in this build")
    class NotSignedIn : Exception("No account is signed in")
    class SignInCancelled : Exception("Sign in was cancelled")

    companion object {
        @Volatile
        private var instance: CloudSync? = null

        fun get(context: Context): CloudSync {
            return instance ?: synchronized(this) {
                instance ?: CloudSync(
                    context.applicationContext,
                    LibraryRepository.get(context)
                ).also { instance = it }
            }
        }
    }
}
