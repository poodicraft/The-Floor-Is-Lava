package com.paperjump

import com.paperjump.game.DeathCause
import com.paperjump.game.GameEngine
import com.paperjump.game.GameMode
import com.paperjump.game.GameSetup
import com.paperjump.game.Twist
import com.paperjump.game.GameStatus
import com.paperjump.game.Tuning
import com.paperjump.processing.Coin
import com.paperjump.processing.LevelData
import com.paperjump.processing.LevelRect
import com.paperjump.processing.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules each [GameMode] layers on top of the physics.
 *
 * The physics themselves are covered by `GameEngineTest`; what matters here is that a mode
 * changes *only* the win and lose conditions, and that neither the countdown nor the flood
 * speed shifts when the same drawing is sampled at a different resolution.
 */
class GameModeTest {

    /** A flat room at `factor` times the reference resolution, floor along the bottom. */
    private fun room(
        factor: Int = 1,
        coins: List<Coin> = emptyList(),
        goal: LevelRect? = null,
        hazardCells: Set<Pair<Int, Int>> = emptySet(),
        spawn: Vec2? = null,
    ): LevelData {
        val cols = 24 * factor
        val rows = 12 * factor
        val solid = BooleanArray(cols * rows)
        for (row in rows - factor until rows) {
            for (col in 0 until cols) solid[row * cols + col] = true
        }
        val hazard = BooleanArray(cols * rows)
        hazardCells.forEach { (col, row) -> hazard[row * cols + col] = true }

        return LevelData(
            cols = cols,
            rows = rows,
            solid = solid,
            hazard = hazard,
            platforms = listOf(LevelRect(0f, (rows - factor).toFloat(), cols.toFloat(), factor.toFloat())),
            hazards = hazardCells.map { LevelRect(it.first.toFloat(), it.second.toFloat(), 1f, 1f) },
            coins = coins,
            spawn = spawn ?: Vec2(2.5f * factor, rows - factor - 0.5f),
            goal = goal,
        )
    }

    private fun GameEngine.run(seconds: Float, frameRate: Float = 60f) {
        repeat((seconds * frameRate).toInt()) { update(1f / frameRate) }
    }

    private val floorRow = 11

    // ---- classic ------------------------------------------------------------------

    @Test
    fun `a plain run has no clock and no flood`() {
        val engine = GameEngine(room(), GameSetup(GameMode.PLATFORMER, Twist.NONE))
        assertNull("an untwisted run must not be timed", engine.timeRemaining)
        assertNull("an untwisted run must not flood", engine.lavaSurfaceY)
        assertFalse(engine.isGoalLocked)

        engine.run(30f)
        assertEquals(GameStatus.PLAYING, engine.status)
    }

    // ---- coin hunt ----------------------------------------------------------------

    @Test
    fun `coin hunt keeps the goal shut until every coin is collected`() {
        val coin = Coin(index = 0, center = Vec2(18f, floorRow - 0.5f), radius = 0.4f)
        val goal = LevelRect(9f, floorRow - 3f, 2f, 3f)
        val engine = GameEngine(room(coins = listOf(coin), goal = goal), GameSetup(twist = Twist.COIN_HUNT))

        assertTrue("the goal starts locked", engine.isGoalLocked)

        // Walk straight over the flag with the coin still on the page.
        engine.moveRight = true
        engine.run(1f)
        assertEquals("the flag must not open early", GameStatus.PLAYING, engine.status)
        assertTrue(engine.isGoalLocked)

        // Carry on to the coin.
        engine.run(1.5f)
        assertEquals(1, engine.coinsCollected)
        assertFalse("collecting the last coin unlocks the flag", engine.isGoalLocked)

        // Back to the flag.
        engine.moveRight = false
        engine.moveLeft = true
        engine.run(2.5f)
        assertEquals(GameStatus.WON, engine.status)
    }

    @Test
    fun `coin hunt on a level with no coins opens immediately`() {
        val goal = LevelRect(9f, floorRow - 3f, 2f, 3f)
        val engine = GameEngine(room(goal = goal), GameSetup(twist = Twist.COIN_HUNT))
        assertFalse(engine.isGoalLocked)

        engine.moveRight = true
        engine.run(2f)
        assertEquals(GameStatus.WON, engine.status)
    }

    // ---- time attack --------------------------------------------------------------

    @Test
    fun `time attack counts down and kills at zero`() {
        val engine = GameEngine(room(), GameSetup(twist = Twist.TIME_ATTACK))
        val limit = engine.timeRemaining
        assertNotNull("time attack must be timed", limit)

        engine.run(1f)
        assertEquals(limit!! - 1f, engine.timeRemaining!!, 0.05f)
        assertEquals(GameStatus.PLAYING, engine.status)

        engine.run(limit + 1f)
        assertEquals(GameStatus.DEAD, engine.status)
        assertEquals(DeathCause.TIME_UP, engine.deathCause)
        assertEquals("the clock must not go negative", 0f, engine.timeRemaining!!, 0.001f)
    }

    @Test
    fun `reaching the goal in time still wins`() {
        val goal = LevelRect(9f, floorRow - 3f, 2f, 3f)
        val engine = GameEngine(room(goal = goal), GameSetup(twist = Twist.TIME_ATTACK))
        engine.moveRight = true
        engine.run(2f)
        assertEquals(GameStatus.WON, engine.status)
        assertEquals(DeathCause.NONE, engine.deathCause)
    }

    @Test
    fun `the countdown does not change with the detail slider`() {
        // Same drawing, sampled at 24 and at 96 columns.
        fun limitFor(factor: Int): Float {
            val goal = LevelRect(20f * factor, 6f * factor, 2f * factor, 3f * factor)
            val level = room(
                factor = factor,
                goal = goal,
                // Scaled exactly, so the two levels really are the same drawing.
                spawn = Vec2(2.5f * factor, 10.5f * factor),
            )
            return Twist.TIME_ATTACK.rulesFor(level, Tuning.forLevel(level)).timeLimitSeconds!!
        }

        val coarse = limitFor(1)
        // Equality would be trivially true if both were sitting on a clamp.
        assertTrue("the limit should be tracking the level size, not clamped", coarse in 12.1f..179.9f)
        assertEquals(coarse, limitFor(4), 0.05f)
    }

    // ---- rising lava --------------------------------------------------------------

    @Test
    fun `rising lava waits, then floods the page`() {
        val engine = GameEngine(room(), GameSetup(twist = Twist.RISING_LAVA))
        val start = engine.lavaSurfaceY
        assertNotNull(start)
        assertTrue("the flood starts off the bottom of the page", start!! > engine.level.height)

        // Grace period: the lava has not moved yet.
        engine.run(2f)
        assertEquals(start, engine.lavaSurfaceY!!, 0.01f)

        engine.run(10f)
        assertTrue("the flood should be climbing", engine.lavaSurfaceY!! < start)
        assertEquals("but it should not have caught up yet", GameStatus.PLAYING, engine.status)

        engine.run(80f)
        assertEquals(GameStatus.DEAD, engine.status)
        assertEquals(DeathCause.FLOODED, engine.deathCause)
    }

    @Test
    fun `the flood crosses the page at the same rate however finely it was sampled`() {
        fun secondsToCross(factor: Int): Float {
            val level = room(factor = factor)
            val speed = Twist.RISING_LAVA.rulesFor(level, Tuning.forLevel(level)).risingLavaSpeed!!
            return level.height / speed
        }
        assertEquals(secondsToCross(1), secondsToCross(4), 0.01f)
    }

    // ---- death causes and restart ---------------------------------------------------

    @Test
    fun `death causes are reported`() {
        val burnt = GameEngine(room(hazardCells = setOf(6 to floorRow - 1)))
        burnt.moveRight = true
        burnt.run(2f)
        assertEquals(DeathCause.LAVA, burnt.deathCause)

        val bottomless = LevelData(
            cols = 24,
            rows = 12,
            solid = BooleanArray(24 * 12),
            hazard = BooleanArray(24 * 12),
            platforms = emptyList(),
            hazards = emptyList(),
            coins = emptyList(),
            spawn = Vec2(2.5f, 1.5f),
            goal = null,
        )
        val fallen = GameEngine(bottomless)
        fallen.run(3f)
        assertEquals(DeathCause.FELL, fallen.deathCause)
    }

    @Test
    fun `restart rewinds the clock and the flood`() {
        val timed = GameEngine(room(), GameSetup(twist = Twist.TIME_ATTACK))
        val limit = timed.timeRemaining!!
        timed.run(5f)
        assertTrue(timed.timeRemaining!! < limit)
        timed.restart()
        assertEquals(limit, timed.timeRemaining!!, 0.001f)
        assertEquals(DeathCause.NONE, timed.deathCause)

        val flooded = GameEngine(room(), GameSetup(twist = Twist.RISING_LAVA))
        val start = flooded.lavaSurfaceY!!
        flooded.run(20f)
        assertTrue(flooded.lavaSurfaceY!! < start)
        flooded.restart()
        assertEquals(start, flooded.lavaSurfaceY!!, 0.001f)
        assertEquals(GameStatus.PLAYING, flooded.status)
    }
}
