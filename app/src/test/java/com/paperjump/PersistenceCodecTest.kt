package com.paperjump

import com.paperjump.data.LevelMetaCodec
import com.paperjump.data.LevelRecord
import com.paperjump.data.LevelSource
import com.paperjump.data.PlayerStats
import com.paperjump.data.RecordCodec
import com.paperjump.data.RecordKey
import com.paperjump.data.SavedLevelMeta
import com.paperjump.game.GameMode
import com.paperjump.game.GameSetup
import com.paperjump.game.Twist
import com.paperjump.processing.ProcessingConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The on-disk formats.
 *
 * These are hand-rolled, so they get the treatment hand-rolled formats need: round trips,
 * awkward input, and proof that one bad file cannot take the whole library down with it.
 */
class PersistenceCodecTest {

    private val meta = SavedLevelMeta(
        id = "abc-123",
        name = "Cave of doom",
        createdAt = 1_700_000_000_000L,
        source = LevelSource.DRAWN,
        config = ProcessingConfig(
            gridCols = 120,
            inkSensitivity = -0.25f,
            colorSensitivity = 0.5f,
            minBlobCells = 4,
        ),
    )

    @Test
    fun `level metadata survives a round trip`() {
        assertEquals(meta, LevelMetaCodec.decode(LevelMetaCodec.encode(meta)))
    }

    @Test
    fun `a name with an equals sign is not truncated`() {
        // The format splits on the first '=', so everything after it is the value.
        val awkward = meta.copy(name = "a = b = c")
        assertEquals("a = b = c", LevelMetaCodec.decode(LevelMetaCodec.encode(awkward))?.name)
    }

    @Test
    fun `newlines in a name cannot corrupt the file`() {
        val awkward = meta.copy(name = "line one\nid=stolen")
        val decoded = LevelMetaCodec.decode(LevelMetaCodec.encode(awkward))

        assertNotNull(decoded)
        assertEquals("the id must not be hijacked by the name", "abc-123", decoded!!.id)
        assertTrue("the newline should be flattened", !decoded.name.contains('\n'))
    }

    @Test
    fun `a blank name falls back to something showable`() {
        val decoded = LevelMetaCodec.decode(LevelMetaCodec.encode(meta.copy(name = "   ")))
        assertTrue(decoded!!.name.isNotBlank())
    }

    @Test
    fun `unreadable metadata decodes to null rather than throwing`() {
        assertNull(LevelMetaCodec.decode(""))
        assertNull(LevelMetaCodec.decode("nonsense"))
        assertNull(LevelMetaCodec.decode("id=only-an-id"))
        assertNull(LevelMetaCodec.decode("id=x\ncreatedAt=not-a-number\nsource=DRAWN"))
        assertNull(LevelMetaCodec.decode("id=x\ncreatedAt=1\nsource=FROM_THE_FUTURE"))
    }

    @Test
    fun `missing tuning fields fall back to the defaults`() {
        val decoded = LevelMetaCodec.decode("id=x\ncreatedAt=1\nsource=PHOTO\nname=Old file")
        assertEquals(ProcessingConfig(), decoded?.config)
    }

    @Test
    fun `records survive a round trip`() {
        val records = mapOf(
            RecordKey("aaaa", GameSetup(GameMode.PLATFORMER, Twist.NONE)) to LevelRecord(plays = 7, wins = 3, bestTimeSeconds = 12.5f, bestCoins = 4),
            RecordKey("aaaa", GameSetup(GameMode.FLYER, Twist.RISING_LAVA)) to LevelRecord(plays = 2, wins = 0, bestTimeSeconds = null, bestCoins = 1),
        )
        assertEquals(records, RecordCodec.decode(RecordCodec.encode(records)))
    }

    @Test
    fun `one corrupt row does not lose the rest of the file`() {
        val good = mapOf(RecordKey("bbbb", GameSetup(GameMode.MAZE, Twist.COIN_HUNT)) to LevelRecord(plays = 1, wins = 1, bestTimeSeconds = 9f))
        val text = "this row is broken\n" + RecordCodec.encode(good) + "\nalso\tbroken\n"

        assertEquals(good, RecordCodec.decode(text))
    }

    @Test
    fun `the same level keeps a separate best for each game and twist`() {
        val plain = RecordKey("cccc", GameSetup(GameMode.PLATFORMER, Twist.NONE))
        val timed = RecordKey("cccc", GameSetup(GameMode.PLATFORMER, Twist.TIME_ATTACK))
        val maze = RecordKey("cccc", GameSetup(GameMode.MAZE, Twist.NONE))
        val records = mapOf(
            plain to LevelRecord(plays = 1, wins = 1, bestTimeSeconds = 5f),
            timed to LevelRecord(plays = 4, wins = 2, bestTimeSeconds = 8f),
            maze to LevelRecord(plays = 2, wins = 1, bestTimeSeconds = 20f),
        )
        val decoded = RecordCodec.decode(RecordCodec.encode(records))

        assertEquals(3, decoded.size)
        assertEquals(5f, decoded[plain]!!.bestTimeSeconds!!, 0.001f)
        assertEquals(8f, decoded[timed]!!.bestTimeSeconds!!, 0.001f)
        assertEquals(20f, decoded[maze]!!.bestTimeSeconds!!, 0.001f)
    }

    @Test
    fun `only a completed run sets a best time`() {
        val fresh = LevelRecord()

        val died = fresh.after(won = false, timeSeconds = 2f, coins = 3)
        assertNull("dying quickly is not a good score", died.bestTimeSeconds)
        assertEquals(1, died.plays)
        assertEquals(0, died.wins)
        assertEquals("coins still count", 3, died.bestCoins)

        val won = died.after(won = true, timeSeconds = 30f, coins = 1)
        assertEquals(30f, won.bestTimeSeconds!!, 0.001f)
        assertEquals(1, won.wins)
        assertEquals("the better coin count is kept", 3, won.bestCoins)
    }

    @Test
    fun `a slower win does not overwrite the best time`() {
        val record = LevelRecord()
            .after(won = true, timeSeconds = 10f, coins = 0)
            .after(won = true, timeSeconds = 25f, coins = 0)

        assertEquals(10f, record.bestTimeSeconds!!, 0.001f)
        assertEquals(2, record.wins)
    }

    @Test
    fun `a new best is recognised before it is stored`() {
        val record = LevelRecord().after(won = true, timeSeconds = 10f, coins = 0)

        assertTrue(record.isNewBestTime(9.9f))
        assertTrue(!record.isNewBestTime(10.1f))
        assertTrue("anything beats never having finished", LevelRecord().isNewBestTime(999f))
    }

    @Test
    fun `stats add up across modes without counting a level twice`() {
        val records = mapOf(
            RecordKey("aaa", GameSetup(GameMode.PLATFORMER)) to LevelRecord(plays = 5, wins = 2, bestTimeSeconds = 12.5f),
            RecordKey("aaa", GameSetup(GameMode.RUNNER)) to LevelRecord(plays = 3, wins = 1, bestTimeSeconds = 9.0f),
            RecordKey("bbb", GameSetup(GameMode.MAZE)) to LevelRecord(plays = 4, wins = 0),
        )

        val stats = PlayerStats.of(records)

        assertEquals(12, stats.runs)
        assertEquals(3, stats.wins)
        assertEquals("one drawing beaten two ways is still one level", 1, stats.levelsBeaten)
        assertEquals(9.0f, stats.bestTimeSeconds!!, 0.001f)
    }

    @Test
    fun `an empty scoreboard has nothing to show`() {
        val stats = PlayerStats.of(emptyMap())
        assertEquals(false, stats.hasPlayed)
        assertEquals(null, stats.bestTimeSeconds)
    }
}
