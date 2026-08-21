package com.paperjump

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.paperjump.capture.CameraScreen
import com.paperjump.draw.DrawingScreen
import com.paperjump.game.GameView
import com.paperjump.ui.HomeScreen
import com.paperjump.ui.HowToPlayScreen
import com.paperjump.ui.LevelLibraryScreen
import com.paperjump.ui.LevelTuneScreen
import com.paperjump.ui.ModeSelectScreen
import com.paperjump.ui.SettingsScreen

/** Every destination in the app. */
object Routes {
    const val HOME = "home"
    const val DRAW = "draw"
    const val CAPTURE = "capture"
    const val TUNE = "tune"
    const val MODE = "mode"
    const val GAME = "game"
    const val LIBRARY = "library"
    const val HOW_TO_PLAY = "how_to_play"
    const val SETTINGS = "settings"
}

/**
 * home → (draw | photograph | library) → tune → mode → play.
 *
 * The bitmap and the detected level live in [SketchGameViewModel] rather than in navigation
 * arguments: a `Bitmap` is far too big for a saved-state bundle, and keeping it there means
 * stepping back from the game to the mode picker does not re-run the detector.
 */
@Composable
fun PaperJumpApp(
    viewModel: SketchGameViewModel,
    versionName: String,
    navController: NavHostController = rememberNavController(),
) {
    val settings = viewModel.settingsRepository.settings

    NavHost(navController = navController, startDestination = Routes.HOME) {

        composable(Routes.HOME) {
            HomeScreen(
                savedLevelCount = viewModel.savedLevels.size,
                versionName = versionName,
                onDraw = { navController.navigate(Routes.DRAW) },
                onPhotograph = { navController.navigate(Routes.CAPTURE) },
                onLibrary = { navController.navigate(Routes.LIBRARY) },
                onHowToPlay = { navController.navigate(Routes.HOW_TO_PLAY) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.DRAW) {
            DrawingScreen(
                controller = viewModel.drawingController,
                onPlay = { document, aspect ->
                    viewModel.onDrawingFinished(document, aspect)
                    navController.navigate(Routes.TUNE)
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.CAPTURE) {
            CameraScreen(
                onSketchSelected = { bitmap, source ->
                    viewModel.onSketchCaptured(bitmap, source)
                    navController.navigate(Routes.TUNE)
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.LIBRARY) {
            LevelLibraryScreen(
                levels = viewModel.savedLevels,
                loadThumbnail = viewModel::loadThumbnail,
                onOpen = { meta ->
                    viewModel.openSavedLevel(meta) { navController.navigate(Routes.MODE) }
                },
                onRename = viewModel::renameSavedLevel,
                onDelete = viewModel::deleteSavedLevel,
                onDraw = {
                    navController.popBackStack()
                    navController.navigate(Routes.DRAW)
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.HOW_TO_PLAY) {
            HowToPlayScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                settings = settings,
                savedLevelCount = viewModel.savedLevels.size,
                versionName = versionName,
                onSettingsChange = viewModel.settingsRepository::update,
                onClearLevels = viewModel::clearLibrary,
                onClearRecords = viewModel::clearRecords,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.TUNE) {
            LevelTuneScreen(
                sketch = viewModel.sketch,
                level = viewModel.level,
                config = viewModel.config,
                isProcessing = viewModel.isProcessing,
                errorMessage = viewModel.errorMessage,
                isSaved = viewModel.currentSavedId != null,
                onConfigChange = viewModel::updateConfig,
                onSave = viewModel::saveCurrentLevel,
                onRetake = {
                    viewModel.clearSketch()
                    navController.popBackStack()
                },
                onContinue = { navController.navigate(Routes.MODE) },
            )
        }

        composable(Routes.MODE) {
            val level = viewModel.level
            if (level == null) {
                ReturnHome(navController)
            } else {
                ModeSelectScreen(
                    level = level,
                    recordFor = viewModel::recordFor,
                    onPlay = { mode ->
                        viewModel.selectMode(mode)
                        navController.navigate(Routes.GAME)
                    },
                    onBack = { navController.popBackStack() },
                )
            }
        }

        composable(Routes.GAME) {
            val level = viewModel.level
            if (level == null) {
                // Only reachable if the process was killed and restored without a level.
                ReturnHome(navController)
            } else {
                GameView(
                    level = level,
                    mode = viewModel.mode,
                    settings = settings,
                    record = viewModel.recordFor(viewModel.mode),
                    wasPersonalBest = viewModel.lastRunWasBest,
                    onRunFinished = viewModel::onRunFinished,
                    onChangeMode = { navController.popBackStack() },
                    onTune = {
                        navController.popBackStack(Routes.TUNE, inclusive = false)
                    },
                    onQuit = {
                        navController.popBackStack(Routes.HOME, inclusive = false)
                    },
                )
            }
        }
    }
}

@Composable
private fun ReturnHome(navController: NavHostController) {
    LaunchedEffect(Unit) {
        navController.popBackStack(Routes.HOME, inclusive = false)
    }
}
