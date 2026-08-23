package com.paperjump

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.paperjump.data.ThemeChoice
import com.paperjump.ui.theme.PaperJumpTheme

/**
 * Single-activity host. Everything else is Compose.
 *
 * The ViewModel is created here rather than per-screen so the sketch, the level, the
 * sketchpad and the library are shared by every destination.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: SketchGameViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings = viewModel.settingsRepository.settings
            val dark = when (settings.theme) {
                ThemeChoice.SYSTEM -> isSystemInDarkTheme()
                ThemeChoice.LIGHT -> false
                ThemeChoice.DARK -> true
            }

            PaperJumpTheme(darkTheme = dark) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    PaperJumpApp(
                        viewModel = viewModel,
                        // Straight from version.properties, so this is exactly what Android
                        // shows on the app's details page.
                        versionName = BuildConfig.VERSION_NAME,
                    )
                }
            }
        }
    }
}
