package com.paperjump.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.paperjump.game.GameRenderer
import com.paperjump.processing.LevelData

/**
 * The detected level, drawn small with the game's own renderer.
 *
 * Shared by the tuning screen and the game picker so that what you are shown before playing
 * is drawn by exactly the code that draws it while you play — a preview that could drift
 * from the real thing would be worse than no preview.
 */
@Composable
fun LevelPreview(
    level: LevelData,
    modifier: Modifier = Modifier,
    showPaper: Boolean = true,
) {
    Canvas(modifier) {
        val camera = GameRenderer.fitCamera(level, size.width, size.height)
        val styleScale = GameRenderer.styleScale(level)
        with(GameRenderer) {
            if (showPaper) drawPaper(camera, level.cols, level.rows, styleScale)
            withWorld(camera) {
                level.goal?.let { drawGoal(it, 0f, styleScale) }
                drawPlatforms(level.platforms, level.platformStrokes, styleScale)
                drawHazards(level.hazards, 0f, styleScale, level.hazardStrokes)
                drawCoins(level, null, null, 0f, 0f, styleScale)
                drawEnemies(level, null, 0f, styleScale)
                drawSpawnMarker(level, 0f, styleScale)
            }
        }
    }
}
