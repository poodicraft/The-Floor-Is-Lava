package com.poodicraft.bookquest

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import com.poodicraft.bookquest.data.CloudSync
import com.poodicraft.bookquest.data.LibraryRepository
import com.poodicraft.bookquest.data.Prefs
import com.poodicraft.bookquest.data.Subject
import com.poodicraft.bookquest.ui.BookQuestRoot
import com.poodicraft.bookquest.ui.theme.BookQuestTheme
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = Prefs(this)
        val repository = LibraryRepository.get(this)

        if (!prefs.starterBooksAdded) {
            prefs.starterBooksAdded = true
            repository.seedStarterBooks(
                listOf(
                    Triple("starter/welcome_he.txt", "ברוכים הבאים למסע הספרים", Subject.LANGUAGE),
                    Triple("starter/welcome_en.txt", "Welcome to BookQuest", Subject.LANGUAGE),
                    Triple("starter/welcome_ar.txt", "أهلا بك في رحلة الكتب", Subject.LANGUAGE)
                )
            )
        }

        setContent {
            var themeMode by remember { mutableStateOf(prefs.themeMode) }
            var language by remember { mutableStateOf(prefs.language) }

            BookQuestTheme(themeMode = themeMode) {
                BookQuestRoot(
                    themeMode = themeMode,
                    onThemeModeChange = { mode ->
                        prefs.themeMode = mode
                        themeMode = mode
                    },
                    language = language,
                    onLanguageChange = { tag ->
                        prefs.language = tag
                        language = tag
                        repository.noteLanguage(tag)
                        AppCompatDelegate.setApplicationLocales(
                            LocaleListCompat.forLanguageTags(tag)
                        )
                    }
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Quietly push progress to the account, if one is signed in.
        val cloud = CloudSync.get(this)
        if (cloud.isConfigured) {
            lifecycleScope.launch { cloud.pushQuietly() }
        }
    }
}
