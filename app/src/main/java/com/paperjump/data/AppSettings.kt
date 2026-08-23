package com.paperjump.data

import android.content.Context
import com.paperjump.BuildConfig
import com.paperjump.ai.AiProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Which colour scheme to use, independent of what the system is doing. */
enum class ThemeChoice(val label: String) {
    SYSTEM("System"),
    LIGHT("Paper"),
    DARK("Night"),
}

/**
 * Everything on the settings screen.
 *
 * Each field has to actually change something — a settings screen full of switches that do
 * nothing is worse than no settings screen.
 */
data class AppSettings(
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    /** Buzz on jump and on death. */
    val haptics: Boolean = true,
    /** Multiplier on the on-screen control size, for small hands and large phones alike. */
    val controlScale: Float = 1f,
    /** Move/jump buttons swap sides for left-handed players. */
    val jumpOnRight: Boolean = true,
    /** Show the run timer in the HUD (the countdown in Time attack always shows). */
    val showTimer: Boolean = true,
    /** The last release whose "what's new" the player has seen, so it is shown once. */
    val lastSeenVersion: String = "",
    /**
     * The key for the AI level designer — Google AI Studio's, or Hugging Face's.
     *
     * Which service it is comes from the key itself, so there is nothing else to pick. A
     * key typed in here stays on the device; a key that arrived with the build came from a
     * repository secret and was never committed.
     */
    val aiToken: String = "",
    /**
     * Which vision model to ask, or blank for whichever the service is serving.
     *
     * Blank by default and best left that way: the app asks the service what it can call
     * before falling back on names of its own. This is here for the day one particular
     * model is wanted, so that is a line of text rather than a new build.
     */
    val aiModel: String = "",
    /**
     * A proxy that holds the key on the player's behalf, if the build was given one.
     *
     * When this is set the app sends no key at all: the proxy adds it. That is the only
     * arrangement where the app works with nothing to type *and* the key cannot be read
     * out of the APK — see server/README.md.
     */
    val aiProxyUrl: String = "",
) {
    /** True when a request can be made at all: either a proxy answers, or we hold a key. */
    val canUseAi: Boolean get() = aiProxyUrl.isNotBlank() || aiToken.isNotBlank()

    val hasAiKey: Boolean get() = canUseAi

    /** Whoever the key belongs to. A proxy holds the key for us, and ours is Google's. */
    val aiProvider: AiProvider
        get() = if (aiProxyUrl.isNotBlank()) AiProvider.GOOGLE else AiProvider.forKey(aiToken)

    /**
     * The model to actually ask for.
     *
     * A name left over from the other service is dropped rather than sent: it would cost a
     * round trip to be told the obvious. That happens on any phone that used the Hugging
     * Face build and has since been given a Google key.
     */
    val aiModelInUse: String
        get() = aiModel.trim()
            .takeIf { it.isNotEmpty() && AiProvider.forModel(it) == aiProvider }
            .orEmpty()

    companion object {
        const val MIN_CONTROL_SCALE = 0.75f
        const val MAX_CONTROL_SCALE = 1.35f
    }
}

/**
 * Settings storage.
 *
 * Backed by `SharedPreferences` — a handful of primitives, written with `apply()`, is
 * exactly the case it is for; a database or DataStore would be ceremony for six values.
 * The current value is Compose state, so screens observe it by reading it.
 */
class SettingsRepository(context: Context) {

    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var settings: AppSettings by mutableStateOf(load())
        private set

    fun update(transform: (AppSettings) -> AppSettings) {
        val updated = transform(settings)
        if (updated == settings) return
        settings = updated
        persist(updated)
    }

    private fun load(): AppSettings {
        val defaults = AppSettings()
        val themeName = preferences.getString(KEY_THEME, null)
        return AppSettings(
            theme = ThemeChoice.entries.firstOrNull { it.name == themeName } ?: defaults.theme,
            haptics = preferences.getBoolean(KEY_HAPTICS, defaults.haptics),
            controlScale = preferences.getFloat(KEY_CONTROL_SCALE, defaults.controlScale)
                .coerceIn(AppSettings.MIN_CONTROL_SCALE, AppSettings.MAX_CONTROL_SCALE),
            jumpOnRight = preferences.getBoolean(KEY_JUMP_ON_RIGHT, defaults.jumpOnRight),
            showTimer = preferences.getBoolean(KEY_SHOW_TIMER, defaults.showTimer),
            lastSeenVersion = preferences.getString(KEY_LAST_SEEN_VERSION, null)
                ?: defaults.lastSeenVersion,
            // The build's own values are the starting point; anything typed in overrides
            // them, and clearing a field falls back to the build's value again.
            aiToken = preferences.getString(KEY_AI_TOKEN, null)?.takeIf { it.isNotBlank() }
                ?: BuildConfig.AI_TOKEN,
            aiProxyUrl = preferences.getString(KEY_AI_PROXY, null)?.takeIf { it.isNotBlank() }
                ?: BuildConfig.AI_PROXY_URL,
            aiModel = preferences.getString(KEY_AI_MODEL, null)?.takeIf { it.isNotBlank() }
                ?: defaults.aiModel,
        )
    }

    private fun persist(settings: AppSettings) {
        preferences.edit()
            .putString(KEY_THEME, settings.theme.name)
            .putBoolean(KEY_HAPTICS, settings.haptics)
            .putFloat(KEY_CONTROL_SCALE, settings.controlScale)
            .putBoolean(KEY_JUMP_ON_RIGHT, settings.jumpOnRight)
            .putBoolean(KEY_SHOW_TIMER, settings.showTimer)
            .putString(KEY_LAST_SEEN_VERSION, settings.lastSeenVersion)
            .putString(KEY_AI_TOKEN, settings.aiToken)
            .putString(KEY_AI_MODEL, settings.aiModel)
            .putString(KEY_AI_PROXY, settings.aiProxyUrl)
            .apply()
    }

    private companion object {
        const val FILE_NAME = "paper_jump_settings"
        const val KEY_THEME = "theme"
        const val KEY_HAPTICS = "haptics"
        const val KEY_CONTROL_SCALE = "control_scale"
        const val KEY_JUMP_ON_RIGHT = "jump_on_right"
        const val KEY_SHOW_TIMER = "show_timer"
        const val KEY_LAST_SEEN_VERSION = "last_seen_version"
        const val KEY_AI_TOKEN = "ai_token"
        const val KEY_AI_MODEL = "ai_model"
        const val KEY_AI_PROXY = "ai_proxy_url"
    }
}
