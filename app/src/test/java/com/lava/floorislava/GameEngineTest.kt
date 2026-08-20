package com.lava.floorislava

import com.lava.floorislava.game.GameEngine
import com.lava.floorislava.game.GameStatus
import com.lava.floorislava.processing.Coin
import com.lava.floorislava.processing.LevelData
import com.lava.floorislava.processing.LevelRect
import com.lava.floorislava.processing.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Physics tests. The engine is framework-free, so these run on the JVM in milliseconds. */
class GameEngineTest {

    private val cols = 24
    private val rows = 12
    private val floorRow = rows - 1

    /** A flat room: floor along the bottom, everything else open. */
    private fun level(
        hazardCells: Set<Pair<Int, Int>> = emptySet(),
        coins: List<Coin> = emptyList(),
        goal: LevelRect? = null,
        spawn: Vec2 = Vec2(2.5f, floorRow - 0.5f),
        extraSolid: Set<Pair<Int, Int>> = emptySet(),
    ): LevelData {
        val solid = BooleanArray(cols * rows)
        for (col in 0 until cols) solid[floorRow * cols + col] = true
        extraSolid.forEach { (col, row) -> solid[row * cols + col] = true }

        val hazard = BooleanArray(cols * rows)
        hazardCells.forEach { (col, row) -> hazard[row * cols + col] = true }

        return LevelData(
            cols = cols,
            rows = rows,
            solid = solid,
            hazard = hazard,
            platforms = listOf(LevelRect(0f, floorRow.toFloat(), cols.toFloat(), 1f)),
            hazards = hazardCells.map { LevelRect(it.first.toFloat(), it.second.toFloat(), 1f, 1f) },
            coins = coins,
            spawn = spawn,
            goal = goal,
        )
    }

    /** Runs the engine at a steady 60 fps for [seconds]. */
    private fun GameEngine.run(seconds: Float, frameRate: Float = 60f) {
        val frames = (seconds * frameRate).toInt()
        repeat(frames) { update(1f / frameRate) }
    }

    @Test
    fun `gravity settles the player on the floor`() {
        val engine = GameEngine(level(spawn = Vec2(2.5f, 2f)))
        engine.run(2f)

        assertEquals(floorRow.toFloat(), engine.playerY + engine.playerHeight, 0.02f)
        assertTrue("player should be grounded", engine.isOnGround)
        assertEquals(GameStatus.PLAYING, engine.status)
    }

    @Test
    fun `jump rises then lands back on the floor`() {
        val engine = GameEngine(level())
        engine.run(0.5f)
        val restingY = engine.playerY

        engine.pressJump()
        engine.run(0.2f)
        assertTrue("player should be in the air", engine.playerY < restingY - 1f)

        engine.releaseJump()
        engine.run(2f)
        assertEquals(restingY, engine.playerY, 0.02f)
        assertTrue(engine.isOnGround)
    }

    @Test
    fun `releasing jump early gives a lower jump`() {
        val full = GameEngine(level())
        full.run(0.3f)
        full.pressJump()
        var fullPeak = Float.MAX_VALUE
        repeat(60) {
            full.update(1f / 60f)
            fullPeak = minOf(fullPeak, full.playerY)
        }

        val short = GameEngine(level())
        short.run(0.3f)
        short.pressJump()
        short.update(1f / 60f)
        short.releaseJump()
        var shortPeak = Float.MAX_VALUE
        repeat(60) {
            short.update(1f / 60f)
            shortPeak = minOf(shortPeak, short.playerY)
        }

        assertTrue("a tapped jump should be lower than a held one", shortPeak > fullPeak + 0.5f)
    }

    @Test
    fun `walking right moves the player and stops at the wall`() {
        val engine = GameEngine(level())
        engine.run(0.2f)
        val startX = engine.playerX

        engine.moveRight = true
        engine.run(0.5f)
        assertTrue("player should have moved right", engine.playerX > startX + 2f)

        // Keep running: the level edge is a wall, so the player must not leave the paper.
        engine.run(6f)
        assertTrue("player left the level", engine.playerX + engine.playerWidth <= cols.toFloat())
    }

    @Test
    fun `the left edge of the paper is solid`() {
        val engine = GameEngine(level(spawn = Vec2(1.5f, floorRow - 0.5f)))
        engine.moveLeft = true
        engine.run(3f)
        assertTrue("player left the level on the left", engine.playerX >= 0f)
    }

    @Test
    fun `touching lava is fatal`() {
        val engine = GameEngine(level(hazardCells = setOf(6 to floorRow - 1)))
        engine.moveRight = true
        engine.run(2f)
        assertEquals(GameStatus.DEAD, engine.status)
    }

    @Test
    fun `falling off the page is fatal`() {
        // A level with no floor under the spawn: nothing to land on.
        val empty = LevelData(
            cols = cols,
            rows = rows,
            solid = BooleanArray(cols * rows),
            hazard = BooleanArray(cols * rows),
            platforms = emptyList(),
            hazards = emptyList(),
            coins = emptyList(),
            spawn = Vec2(2.5f, 1.5f),
            goal = null,
        )
        val engine = GameEngine(empty)
        engine.run(3f)
        assertEquals(GameStatus.DEAD, engine.status)
    }

    @Test
    fun `coins are collected once`() {
        val coin = Coin(index = 0, center = Vec2(6f, floorRow - 0.5f), radius = 0.4f)
        val engine = GameEngine(level(coins = listOf(coin)))
        assertEquals(0, engine.coinsCollected)

        engine.moveRight = true
        engine.run(2f)

        assertEquals(1, engine.coinsCollected)
        assertTrue(engine.collected[0])
        assertTrue("pickup time should be recorded", engine.collectedAt[0] >= 0f)

        engine.run(1f)
        assertEquals("a coin must not be counted twice", 1, engine.coinsCollected)
    }

    @Test
    fun `reaching the goal wins`() {
        val goal = LevelRect(18f, floorRow - 3f, 2f, 3f)
        val engine = GameEngine(level(goal = goal))
        engine.moveRight = true
        engine.run(4f)
        assertEquals(GameStatus.WON, engine.status)
    }

    @Test
    fun `restart returns the player to the spawn and counts the attempt`() {
        val engine = GameEngine(level(hazardCells = setOf(6 to floorRow - 1)))
        engine.moveRight = true
        engine.run(2f)
        assertEquals(GameStatus.DEAD, engine.status)

        engine.moveRight = false
        engine.restart()

        assertEquals(GameStatus.PLAYING, engine.status)
        assertEquals(2, engine.attempts)
        assertEquals(2.5f, engine.playerCenterX, 0.001f)
        assertEquals(0f, engine.elapsedSeconds, 0.001f)
    }

    @Test
    fun `a tall wall blocks horizontal movement`() {
        val wall = (floorRow - 4 until floorRow).map { 8 to it }.toSet()
        val engine = GameEngine(level(extraSolid = wall))
        engine.moveRight = true
        engine.run(4f)
        assertTrue("player tunnelled through the wall", engine.playerX + engine.playerWidth <= 8.01f)
    }

    /** The same room drawn at `factor` times the grid resolution. */
    private fun scaledRoom(factor: Int): LevelData {
        val scaledCols = cols * factor
        val scaledRows = rows * factor
        val solid = BooleanArray(scaledCols * scaledRows)
        for (row in scaledRows - factor until scaledRows) {
            for (col in 0 until scaledCols) solid[row * scaledCols + col] = true
        }
        return LevelData(
            cols = scaledCols,
            rows = scaledRows,
            solid = solid,
            hazard = BooleanArray(scaledCols * scaledRows),
            platforms = emptyList(),
            hazards = emptyList(),
            coins = emptyList(),
            spawn = Vec2(2.5f * factor, scaledRows - factor - 0.5f),
            goal = null,
        )
    }

    @Test
    fun `a level plays the same however finely it was sampled`() {
        // The detail slider must not double as a difficulty slider: the same drawing at
        // 24 and 96 columns has to produce the same trajectory across the page.
        val coarse = GameEngine(scaledRoom(1))
        val fine = GameEngine(scaledRoom(4))
        val engines = listOf(coarse, fine)

        engines.forEach { engine ->
            engine.moveRight = true
            engine.run(0.3f)
            engine.pressJump()
            repeat(45) { engine.update(1f / 60f) }
        }

        assertEquals(
            coarse.playerX / coarse.level.width,
            fine.playerX / fine.level.width,
            0.01f,
        )
        assertEquals(
            coarse.playerY / coarse.level.height,
            fine.playerY / fine.level.height,
            0.01f,
        )
    }

    @Test
    fun `physics are frame-rate independent`() {
        val at30 = GameEngine(level())
        val at144 = GameEngine(level())
        at30.run(0.4f, frameRate = 30f)
        at144.run(0.4f, frameRate = 144f)
        at30.pressJump()
        at144.pressJump()

        var peak30 = Float.MAX_VALUE
        repeat(30) { at30.update(1f / 30f); peak30 = minOf(peak30, at30.playerY) }
        var peak144 = Float.MAX_VALUE
        repeat(144) { at144.update(1f / 144f); peak144 = minOf(peak144, at144.playerY) }

        assertTrue(
            "jump height differed by ${abs(peak30 - peak144)} between 30 and 144 fps",
            abs(peak30 - peak144) < 0.15f,
        )
    }
}
