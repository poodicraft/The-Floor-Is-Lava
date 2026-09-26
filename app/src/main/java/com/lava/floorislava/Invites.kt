package com.lava.floorislava

import android.content.Intent
import android.net.Uri

/**
 * Invite links for multiplayer matches.
 *
 * The shared link points at a small web page (docs/join/ in this repository,
 * served by GitHub Pages) because chat apps only make https addresses
 * tappable. Its "Join the race" button opens the app through
 * floorislava://join/CODE. The app also accepts the https link itself where
 * Android hands it over (older Android versions offer the app as a choice).
 */
object Invites {

    private const val PAGE_URL = "https://poodicraft.github.io/The-Floor-Is-Lava/join/"

    /** A code from a tapped invite, waiting until the player reaches the menu signed in. */
    @Volatile
    var pendingCode: String? = null
        private set

    fun linkFor(code: String): String = "$PAGE_URL?c=$code"

    /** Remembers the match code if [intent] was opened from an invite link. */
    fun capture(intent: Intent?) {
        val uri = intent?.data ?: return
        codeFrom(uri)?.let { pendingCode = it }
    }

    /** Hands over the pending code once, or null. */
    fun take(): String? = pendingCode.also { pendingCode = null }

    /** floorislava://join/ABCDE or https://…/join/?c=ABCDE → "ABCDE". */
    private fun codeFrom(uri: Uri): String? {
        val raw = uri.getQueryParameter("c") ?: uri.lastPathSegment ?: return null
        return Matches.normalizeCode(raw).takeIf { it.length == 5 }
    }
}
