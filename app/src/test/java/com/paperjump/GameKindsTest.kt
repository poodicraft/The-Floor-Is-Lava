package com.paperjump

import com.paperjump.game.DeathCause
import com.paperjump.game.GameEngine
import com.paperjump.game.GameMode
import com.paperjump.game.GameSetup
import com.paperjump.game.GameStatus
import com.paperjump.processing.Coin
import com.paperjump.processing.Enemy
import com.paperjump.processing.LevelData
import com.paperjump.processing.LevelRect
import com.paperjump.processing.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Each game type is a different game, so what is tested here is that they really do differ:
 * the maze has no gravity, the runner cannot stop, the flyer dies on the ink a platformer
 * would happily stand on.
 */
class GameKindsTest {

    private val cols = 24
    private val rows = 12
    private val floorRow = rows - 1

    /** A room with a floor, and whatever else a test asks for. */
    private fun room(
        floor: Boolean = true,
        extraSolid: Set<Pair<Int, Int>> = emptySet(),
        coins: List<Coin> = emptyList(),
        goal: LevelRect? = null,
        spawn: Vec2 = Vec2(2.5f, floorRow - 0.5f),
        enemies: List<Enemy> = emptyList(),
    ): LevelData {
        val solid = BooleanArray(cols * rows)
        if (floor) for (col in 0 until cols) solid[floorRow * cols + col] = true
        extraSolid.forEach { (col, row) -> solid[row * cols + col] = true }

        return LevelData(
            cols = cols,
            rows = rows,
            solid = solid,
            hazard = BooleanArray(cols * rows),
            platforms = emptyList(),
            hazards = emptyList(),
            coins = coins,
            spawn = spawn,
            goal = goal,
            enemies = enemies,
        )
    }

    private fun GameEngine.run(seconds: Float, frameRate: Float = 60f) {
        repeat((seconds * frameRate).toInt()) { update(1f / frameRate) }
    }

    private fun engine(mode: GameMode, level: LevelData = room()) =
        GameEngine(level, GameSetup(mode))

    // ---- maze: top-down, no gravity ---------------------------------------------------

    @Test
    fun `the maze has no gravity`() {
        val engine = engine(GameMode.MAZE, room(floor = false, spawn = Vec2(12.5f, 5.5f)))
        val startY = engine.playerY

        engine.run(3f)

        assertEquals("the player fell in a top-down game", startY, engine.playerY, 0.01f)
        assertEquals(GameStatus.PLAYING, engine.status)
    }

    @Test
    fun `the maze steers in all four directions`() {
        val engine = engine(GameMode.MAZE, room(floor = false, spawn = Vec2(12.5f, 6.5f)))
        val start = engine.playerY

        engine.moveUp = true
        engine.run(0.6f)
        assertTrue("holding up should move the player up", engine.playerY < start - 1f)

        engine.moveUp = false
        engine.moveDown = true
        engine.run(1.2f)
        assertTrue("holding down should move the player back down", engine.playerY > start)
    }

    @Test
    fun `maze walls block movement`() {
        // A vertical wall to the right of the spawn.
        val wall = (0 until rows).map { 8 to it }.toSet()
        val engine = engine(GameMode.MAZE, room(floor = false, extraSolid = wall, spawn = Vec2(2.5f, 5.5f)))

        engine.moveRight = true
        engine.run(4f)

        assertTrue("the player went through the wall", engine.playerX + engine.playerWidth <= 8.05f)
        assertEquals("bumping a wall is not fatal in a maze", GameStatus.PLAYING, engine.status)
    }

    @Test
    fun `a diagonal is no faster than a straight line`() {
        val straight = engine(GameMode.MAZE, room(floor = false, spawn = Vec2(2.5f, 5.5f)))
        straight.moveRight = true
        straight.run(1f)
        val straightDistance = straight.playerX - 2.5f + straight.playerWidth / 2f

        val diagonal = engine(GameMode.MAZE, room(floor = false, spawn = Vec2(2.5f, 5.5f)))
        diagonal.moveRight = true
        diagonal.moveUp = true
        diagonal.run(1f)
        val dx = diagonal.playerX - 2.5f + diagonal.playerWidth / 2f
        val dy = abs(diagonal.playerY - straight.playerY)
        val diagonalDistance = kotlin.math.hypot(dx, dy)

        assertEquals(
            "a diagonal should cover the same ground as a straight line",
            straightDistance,
            diagonalDistance,
            straightDistance * 0.12f,
        )
    }

    // ---- runner: no brakes ------------------------------------------------------------

    @Test
    fun `the runner runs without being told to`() {
        val engine = engine(GameMode.RUNNER)
        val start = engine.playerX

        engine.run(1f)

        assertTrue("the runner should have set off by itself", engine.playerX > start + 3f)
    }

    @Test
    fun `the runner cannot be steered backwards`() {
        val engine = engine(GameMode.RUNNER)
        val start = engine.playerX

        engine.moveLeft = true
        engine.run(1f)

        assertTrue("holding left should not stop the runner", engine.playerX > start + 3f)
    }

    @Test
    fun `running into a wall ends the run`() {
        val wall = (floorRow - 4 until floorRow).map { 10 to it }.toSet()
        val engine = engine(GameMode.RUNNER, room(extraSolid = wall))

        engine.run(4f)

        assertEquals(GameStatus.DEAD, engine.status)
        assertEquals(DeathCause.CRASHED, engine.deathCause)
    }

    @Test
    fun `the runner can still jump a gap`() {
        val engine = engine(GameMode.RUNNER)
        engine.run(0.3f)
        engine.pressJump()
        val restingY = engine.playerY
        engine.run(0.25f)

        assertTrue("the runner should have left the ground", engine.playerY < restingY - 0.5f)
    }

    // ---- flyer: the ink is deadly -----------------------------------------------------

    @Test
    fun `the flyer starts clear of the ink it was spawned on`() {
        val engine = engine(GameMode.FLYER)
        assertEquals("spawning on a platform must not be instant death", GameStatus.PLAYING, engine.status)
        assertTrue("the flyer should start above the floor", engine.playerY + engine.playerHeight < floorRow.toFloat())
    }

    @Test
    fun `the flyer dies on touching the ink`() {
        val engine = engine(GameMode.FLYER)

        // No flapping: gravity takes it into the floor a platformer would land on.
        engine.run(4f)

        assertEquals(GameStatus.DEAD, engine.status)
        assertEquals(DeathCause.CRASHED, engine.deathCause)
    }

    @Test
    fun `flapping holds an altitude`() {
        val engine = engine(GameMode.FLYER)
        val start = engine.playerY

        // Played the way the game is meant to be played: a flap whenever it drops below
        // where it started, and a glide the rest of the time.
        // Two seconds: long enough to prove it is holding station, short enough that the
        // forward drift does not carry it into the right-hand edge of this small room.
        repeat(120) {
            if (engine.playerY > start) engine.pressJump()
            engine.update(1f / 60f)
        }

        assertEquals("flapping should have kept it alive", GameStatus.PLAYING, engine.status)
        assertTrue(
            "it sank to ${engine.playerY} from $start despite flapping",
            engine.playerY < start + 1.5f,
        )
        assertTrue("it should not have climbed away either", engine.playerY > start - 4f)
    }

    @Test
    fun `climbing off the top of the page ends the flight`() {
        val engine = engine(GameMode.FLYER, room(floor = false))

        // Flapping flat out, which would otherwise coast above the whole drawing.
        repeat(600) {
            engine.pressJump()
            engine.update(1f / 60f)
        }

        assertEquals(GameStatus.DEAD, engine.status)
        assertEquals(DeathCause.CRASHED, engine.deathCause)
    }

    @Test
    fun `the flyer drifts forward on its own`() {
        val engine = engine(GameMode.FLYER)
        val start = engine.playerX

        repeat(10) {
            engine.pressJump()
            engine.run(0.2f)
        }

        assertTrue("the flyer should carry forward by itself", engine.playerX > start + 2f)
    }

    // ---- what the games share ---------------------------------------------------------

    @Test
    fun `every game can be won by reaching the flag`() {
        val goal = LevelRect(14f, floorRow - 4f, 3f, 4f)
        GameMode.entries.forEach { mode ->
            // A page-wide floor is scenery to three of these games and a wall to the flyer.
            val level = room(
                floor = mode != GameMode.FLYER,
                goal = goal,
                spawn = Vec2(2.5f, floorRow - 0.5f),
            )
            val engine = GameEngine(level, GameSetup(mode))

            // Drive each game towards the flag the way its own controls allow.
            repeat(400) {
                when (mode) {
                    GameMode.PLATFORMER -> engine.moveRight = true
                    GameMode.MAZE -> engine.moveRight = true
                    GameMode.RUNNER -> Unit
                    // Fly the course rather than spamming the button: flap when below the
                    // flag, glide when above it.
                    GameMode.FLYER ->
                        if (engine.playerCenterY > goal.centerY) engine.pressJump()
                }
                engine.update(1f / 60f)
            }

            assertEquals("$mode could not reach the flag", GameStatus.WON, engine.status)
        }
    }

    // ---- creatures --------------------------------------------------------------------

    @Test
    fun `walking into a creature ends the run`() {
        val creature = Enemy(0, Vec2(6.5f, floorRow - 0.5f), radius = 0.5f)
        val engine = engine(GameMode.PLATFORMER, room(enemies = listOf(creature)))

        engine.moveRight = true
        engine.run(3f)

        assertEquals(GameStatus.DEAD, engine.status)
        assertEquals(DeathCause.ENEMY, engine.deathCause)
    }

    @Test
    fun `landing on a creature squashes it and bounces the player`() {
        val creature = Enemy(0, Vec2(3.2f, floorRow - 0.5f), radius = 0.5f)
        val engine = engine(GameMode.PLATFORMER, room(enemies = listOf(creature)))

        // Jump, drift onto it, and come down on its head.
        engine.pressJump()
        engine.run(0.1f)
        engine.releaseJump()
        engine.moveRight = true
        engine.run(1.2f)

        assertTrue("the creature should have been squashed", engine.enemyDefeated[0])
        assertEquals("and squashing it is not fatal", GameStatus.PLAYING, engine.status)
    }

    @Test
    fun `a creature turns round at the end of its ledge`() {
        // A three-cell island in mid-air, with the creature on it.
        val ledge = setOf(8 to 6, 9 to 6, 10 to 6)
        val creature = Enemy(0, Vec2(9.5f, 5.5f), radius = 0.5f)
        val level = room(extraSolid = ledge, spawn = Vec2(2.5f, floorRow - 0.5f), enemies = listOf(creature))
        val engine = GameEngine(level, GameSetup(GameMode.PLATFORMER))

        engine.run(6f)

        assertTrue("it walked off the ledge", engine.enemyX[0] > 8f)
        assertTrue("it walked off the ledge", engine.enemyX[0] < 11f)
    }

    @Test
    fun `a level with no creatures behaves exactly as before`() {
        val engine = engine(GameMode.PLATFORMER)
        engine.moveRight = true
        engine.run(2f)
        assertEquals(GameStatus.PLAYING, engine.status)
    }

    @Test
    fun `restarting brings a squashed creature back`() {
        val creature = Enemy(0, Vec2(6.5f, floorRow - 0.5f), radius = 0.5f)
        val engine = engine(GameMode.PLATFORMER, room(enemies = listOf(creature)))
        val start = engine.enemyX[0]

        engine.moveRight = true
        engine.run(3f)
        assertEquals(GameStatus.DEAD, engine.status)

        engine.restart()
        assertTrue("a restarted creature is alive again", !engine.enemyDefeated[0])
        assertEquals("and back where it was drawn", start, engine.enemyX[0], 0.01f)
    }
}
