package com.lava.floorislava

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/** The Google account chooser, through Android's Credential Manager. */
object GoogleSignIn {

    /**
     * Shows the account chooser and returns the chosen account's ID token.
     * The first pass offers only accounts that already used this app (a quiet
     * one-tap); if there are none, it asks again with every account on the phone.
     *
     * Throws GetCredentialCancellationException if the player closes the
     * chooser, and NoCredentialException if the phone has no Google account.
     */
    suspend fun requestIdToken(activity: Activity, webClientId: String): String =
        try {
            request(activity, webClientId, onlyAccountsUsedBefore = true)
        } catch (_: NoCredentialException) {
            request(activity, webClientId, onlyAccountsUsedBefore = false)
        }

    private suspend fun request(activity: Activity, webClientId: String, onlyAccountsUsedBefore: Boolean): String {
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(webClientId)
            .setFilterByAuthorizedAccounts(onlyAccountsUsedBefore)
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = CredentialManager.create(activity).getCredential(activity, request).credential
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            return GoogleIdTokenCredential.createFrom(credential.data).idToken
        }
        throw IllegalStateException("Google didn't return an account")
    }

    /** Forgets the chosen account on sign out, so the chooser shows again next time. */
    suspend fun clear(context: Context) {
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
    }
}
