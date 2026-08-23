package com.paperjump.data

import android.content.Context
import com.paperjump.BuildConfig
import com.paperjump.ai.AiProtocol
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
     * The player's own Hugging Face key, for the AI level designer.
     *
     * Kept on the device and nowhere else. It is never bundled into the app: a key baked
     * into an APK can be read straight back out of it by anybody who has the file, so this
     * one is typed in once and stays here.
     */
    val aiToken: String = "",
    /** Which hosted vision model to ask. A setting so a model going away is not a rebuild. */
    val aiModel: String = AiProtocol.DEFAULT_MODEL,
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
