package com.lava.floorislava

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lava.floorislava.capture.CameraScreen
import com.lava.floorislava.game.GameView
import com.lava.floorislava.ui.LevelTuneScreen

/** The three screens of the app. */
object Routes {
    const val CAPTURE = "capture"
    const val TUNE = "tune"
    const val GAME = "game"
}

/**
 * Capture → tune → play.
 *
 * The bitmap and the detected level live in [SketchGameViewModel] rather than in
 * navigation arguments: a `Bitmap` is far too big for a saved-state bundle, and keeping
 * it in the ViewModel means going back from the game to the tuning screen does not
 * re-run the detector.
 */
@Composable
fun SketchPlatformerApp(
    viewModel: SketchGameViewModel,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = Routes.CAPTURE) {
        composable(Routes.CAPTURE) {
            CameraScreen(
                onSketchSelected = { bitmap ->
                    viewModel.onSketchCaptured(bitmap)
                    navController.navigate(Routes.TUNE)
                },
            )
        }

        composable(Routes.TUNE) {
            LevelTuneScreen(
                sketch = viewModel.sketch,
                level = viewModel.level,
                config = viewModel.config,
                isProcessing = viewModel.isProcessing,
                errorMessage = viewModel.errorMessage,
                onConfigChange = viewModel::updateConfig,
                onRetake = {
                    viewModel.clearSketch()
                    navController.popBackStack(Routes.CAPTURE, inclusive = false)
                },
                onPlay = { navController.navigate(Routes.GAME) },
            )
        }

        composable(Routes.GAME) {
            val level = viewModel.level
            if (level == null) {
                // Only reachable if the process was killed and restored without a level.
                LaunchedEffect(Unit) {
                    navController.popBackStack(Routes.CAPTURE, inclusive = false)
                }
            } else {
                GameView(
                    level = level,
                    onBackToTuning = { navController.popBackStack() },
                    onNewSketch = {
                        viewModel.clearSketch()
                        navController.popBackStack(Routes.CAPTURE, inclusive = false)
                    },
                )
            }
        }
    }
}
