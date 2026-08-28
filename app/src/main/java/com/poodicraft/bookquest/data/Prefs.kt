package com.poodicraft.bookquest.data

import android.content.Context

/** Small wrapper around SharedPreferences for user choices. */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("bookquest_prefs", Context.MODE_PRIVATE)

    var language: String
        get() = sp.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
        set(value) = sp.edit().putString(KEY_LANGUAGE, value).apply()

    /** "system", "light" or "dark". */
    var themeMode: String
        get() = sp.getString(KEY_THEME, "system") ?: "system"
        set(value) = sp.edit().putString(KEY_THEME, value).apply()

    var readerFontSize: Float
        get() = sp.getFloat(KEY_FONT, 18f)
        set(value) = sp.edit().putFloat(KEY_FONT, value).apply()

    /** "light", "sepia" or "dark". */
    var readerTheme: String
        get() = sp.getString(KEY_READER_THEME, "light") ?: "light"
        set(value) = sp.edit().putString(KEY_READER_THEME, value).apply()

    var starterBooksAdded: Boolean
        get() = sp.getBoolean(KEY_STARTER, false)
        set(value) = sp.edit().putBoolean(KEY_STARTER, value).apply()

    companion object {
        const val DEFAULT_LANGUAGE = "he"
        val LANGUAGES = listOf("he", "en", "ar")

        private const val KEY_LANGUAGE = "language"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_FONT = "reader_font"
        private const val KEY_READER_THEME = "reader_theme"
        private const val KEY_STARTER = "starter_books_added"
    }
}
