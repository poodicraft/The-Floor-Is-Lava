package com.paperjump

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.paperjump.capture.CameraScreen
import com.paperjump.draw.DrawingScreen
import com.paperjump.game.GameView
import com.paperjump.ui.FreeDrawScreen
import com.paperjump.ui.HomeScreen
import com.paperjump.ui.HowToPlayScreen
import com.paperjump.ui.LevelLibraryScreen
import com.paperjump.ui.LevelTuneScreen
import com.paperjump.ui.ModeSelectScreen
import com.paperjump.ui.SettingsScreen
import com.paperjump.ui.WhatsNewScreen

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
    const val WHATS_NEW = "whats_new"
    const val FREE_DRAW = "free_draw"
}

/**
 * home → (draw | photograph | library) → **choose a game** → play.
 *
 * Choosing the game comes straight after making a level, because that is the interesting
 * decision; the tuning screen is a detour off it for when the detector misread something,
 * rather than a step everybody has to walk through to reach their level.
 *
 * The bitmap and the detected level live in [SketchGameViewModel] rather than in navigation
 * arguments: a `Bitmap` is far too big for a saved-state bundle, and keeping it there means
 * stepping back from the game to the game picker does not re-run the detector.
 */
@Composable
fun PaperJumpApp(
    viewModel: SketchGameViewModel,
    versionName: String,
    navController: NavHostController = rememberNavController(),
) {
    val settings = viewModel.settingsRepository.settings

    // Show the release notes once after an update, then never again unless asked. Recorded
    // before navigating, so a player who backs straight out is not shown them next launch.
    LaunchedEffect(versionName) {
        if (settings.lastSeenVersion.isNotEmpty() && settings.lastSeenVersion != versionName) {
            navController.navigate(Routes.WHATS_NEW)
        }
        if (settings.lastSeenVersion != versionName) {
            viewModel.settingsRepository.update { it.copy(lastSeenVersion = versionName) }
        }
    }

    // Screens slide in the direction you are travelling, so the app has a sense of depth
    // instead of cutting between unrelated pages.
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        enterTransition = {
            slideInHorizontally(animationSpec = tween(SCREEN_MOTION_MS)) { it / 6 } +
                fadeIn(animationSpec = tween(SCREEN_MOTION_MS))
        },
        exitTransition = {
            slideOutHorizontally(animationSpec = tween(SCREEN_MOTION_MS)) { -it / 8 } +
                fadeOut(animationSpec = tween(SCREEN_MOTION_MS))
        },
        popEnterTransition = {
            slideInHorizontally(animationSpec = tween(SCREEN_MOTION_MS)) { -it / 8 } +
                fadeIn(animationSpec = tween(SCREEN_MOTION_MS))
        },
        popExitTransition = {
            slideOutHorizontally(animationSpec = tween(SCREEN_MOTION_MS)) { it / 6 } +
                fadeOut(animationSpec = tween(SCREEN_MOTION_MS))
        },
    ) {

        composable(Routes.HOME) {
            HomeScreen(
                savedLevelCount = viewModel.savedLevels.size,
                versionName = versionName,
                stats = viewModel.stats,
                onDraw = { navController.navigate(Routes.DRAW) },
                onPhotograph = { navController.navigate(Routes.CAPTURE) },
                onLibrary = { navController.navigate(Routes.LIBRARY) },
                onHowToPlay = { navController.navigate(Routes.HOW_TO_PLAY) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
                onWhatsNew = { navController.navigate(Routes.WHATS_NEW) },
                onFreeDraw = { navController.navigate(Routes.FREE_DRAW) },
            )
        }

        composable(Routes.FREE_DRAW) {
            // The build runs while this screen is still up, so a failure can be explained
            // here rather than on a game picker that would have no idea what went wrong.
            val ready = viewModel.aiLevelReady
            LaunchedEffect(ready) {
                if (viewModel.consumeAiLevelReady()) navController.navigate(Routes.MODE)
            }

            FreeDrawScreen(
                controller = viewModel.freeDrawController,
                hint = viewModel.freeDrawHint,
                state = viewModel.aiState,
                hasKey = settings.hasAiKey,
                onHintChange = viewModel::updateFreeDrawHint,
                onBuild = { document, aspect -> viewModel.buildLevelWithAi(document, aspect) },
                onPlayAnyway = { navController.navigate(Routes.MODE) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onBack = {
                    viewModel.clearAiState()
                    navController.popBackStack()
                },
            )
        }

        composable(Routes.WHATS_NEW) {
            WhatsNewScreen(
                versionName = versionName,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.DRAW) {
            DrawingScreen(
                controller = viewModel.drawingController,
                onPlay = { document, aspect ->
                    viewModel.onDrawingFinished(document, aspect)
                    navController.navigate(Routes.MODE)
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.CAPTURE) {
            CameraScreen(
                onSketchSelected = { bitmap, source ->
                    viewModel.onSketchCaptured(bitmap, source)
                    navController.navigate(Routes.MODE)
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
                onRetake = { navController.popBackStack() },
                onContinue = { navController.popBackStack() },
            )
        }

        composable(Routes.MODE) {
            ModeSelectScreen(
                // Detection may still be running: this screen is now the first thing shown
                // after a drawing is finished, so it owns the waiting state.
                level = viewModel.level,
                isProcessing = viewModel.isProcessing,
                errorMessage = viewModel.errorMessage,
                twist = viewModel.setup.twist,
                recordFor = viewModel::recordFor,
                onTwistChange = { twist ->
                    viewModel.selectSetup(viewModel.setup.copy(twist = twist))
                },
                onPlay = { setup ->
                    viewModel.selectSetup(setup)
                    navController.navigate(Routes.GAME)
                },
                onTune = { navController.navigate(Routes.TUNE) },
                onBack = {
                    viewModel.clearSketch()
                    navController.popBackStack()
                },
            )
        }

        composable(Routes.GAME) {
            val level = viewModel.level
            if (level == null) {
                // Only reachable if the process was killed and restored without a level.
                ReturnHome(navController)
            } else {
                GameView(
                    level = level,
                    setup = viewModel.setup,
                    settings = settings,
                    record = viewModel.recordFor(viewModel.setup),
                    wasPersonalBest = viewModel.lastRunWasBest,
                    onRunFinished = viewModel::onRunFinished,
                    onChangeMode = { navController.popBackStack() },
                    onTune = {
                        navController.popBackStack(Routes.MODE, inclusive = false)
                        navController.navigate(Routes.TUNE)
                    },
                    onQuit = {
                        navController.popBackStack(Routes.HOME, inclusive = false)
                    },
                )
            }
        }
    }
}

/** Long enough to read as movement, short enough never to be in the way. */
private const val SCREEN_MOTION_MS = 260

@Composable
private fun ReturnHome(navController: NavHostController) {
    LaunchedEffect(Unit) {
        navController.popBackStack(Routes.HOME, inclusive = false)
    }
}
