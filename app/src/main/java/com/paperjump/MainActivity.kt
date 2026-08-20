package com.paperjump

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.paperjump.ui.theme.PaperJumpTheme

/**
 * Single-activity host. Everything else is Compose.
 *
 * The ViewModel is created here (rather than per-screen) so the captured sketch and the
 * detected level are shared by the capture, tuning and game destinations.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: SketchGameViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PaperJumpTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    PaperJumpApp(viewModel = viewModel)
                }
            }
        }
    }
}
