package com.poodicraft.bookquest

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.poodicraft.bookquest.data.Prefs

class BookQuestApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Hebrew is the default language of the app, whatever the device is set to.
        val prefs = Prefs(this)
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(prefs.language)
        )
    }
}
