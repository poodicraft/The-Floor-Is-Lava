package com.paperjump.data

import com.paperjump.game.GameMode
import com.paperjump.game.GameSetup
import com.paperjump.game.Twist

/**
 * Per-level, per-mode personal bests, and their on-disk encoding.
 *
 * Records are keyed by the *content* of a level rather than by a library entry, so a level
 * keeps its best time whether or not it was ever saved, and re-photographing the same
 * drawing to identical pixels lands on the same record.
 *
 * Pure Kotlin; [RecordStore] does the file handling.
 */
data class LevelRecord(
    val plays: Int = 0,
    val wins: Int = 0,
    val bestTimeSeconds: Float? = null,
    val bestCoins: Int = 0,
) {
    val hasBeenWon: Boolean get() = wins > 0

    /** Folds a finished run into the record, keeping only the improvements. */
    fun after(won: Boolean, timeSeconds: Float, coins: Int): LevelRecord = LevelRecord(
        plays = plays + 1,
        wins = wins + if (won) 1 else 0,
        // Only a completed run has a meaningful time: dying quickly is not a good score.
        bestTimeSeconds = if (won) {
            minOf(bestTimeSeconds ?: Float.MAX_VALUE, timeSeconds)
        } else {
            bestTimeSeconds
        },
        bestCoins = maxOf(bestCoins, coins),
    )

    /** True when this run beat the stored time, for the "new record" flourish. */
    fun isNewBestTime(timeSeconds: Float): Boolean =
        bestTimeSeconds == null || timeSeconds < bestTimeSeconds
}

/** Identifies one level played one particular way. */
data class RecordKey(val levelKey: String, val setup: GameSetup)

/**
 * Everything the records add up to, for the little scoreboard on the home screen.
 *
 * Levels are counted by their *content* key rather than per mode, so beating the same
 * drawing as a platformer and as a runner is one level beaten, not two.
 */
data class PlayerStats(
    val runs: Int = 0,
    val wins: Int = 0,
    val levelsBeaten: Int = 0,
    val bestTimeSeconds: Float? = null,
) {
    val hasPlayed: Boolean get() = runs > 0

    companion object {
        fun of(records: Map<RecordKey, LevelRecord>): PlayerStats = PlayerStats(
            runs = records.values.sumOf { it.plays },
            wins = records.values.sumOf { it.wins },
            levelsBeaten = records.entries
                .filter { it.value.hasBeenWon }
                .mapTo(mutableSetOf()) { it.key.levelKey }
                .size,
            bestTimeSeconds = records.values.mapNotNull { it.bestTimeSeconds }.minOrNull(),
        )
    }
}

/** Tab-separated lines; see [LevelMetaCodec] for why this is not JSON. */
object RecordCodec {

    fun encode(records: Map<RecordKey, LevelRecord>): String = buildString {
        records.forEach { (key, record) ->
            // A tab in a level key would corrupt the row; keys are hex, but be safe.
            if (key.levelKey.contains('\t')) return@forEach
            appendLine(
                listOf(
                    key.levelKey,
                    key.setup.mode.name,
                    key.setup.twist.name,
                    record.plays,
                    record.wins,
                    record.bestTimeSeconds?.toString() ?: NO_TIME,
                    record.bestCoins,
                ).joinToString("\t"),
            )
        }
    }

    fun decode(text: String): Map<RecordKey, LevelRecord> =
        text.lineSequence()
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 7) return@mapNotNull null
                val mode = GameMode.entries.firstOrNull { it.name == parts[1] } ?: return@mapNotNull null
                val twist = Twist.entries.firstOrNull { it.name == parts[2] } ?: return@mapNotNull null
                val plays = parts[3].toIntOrNull() ?: return@mapNotNull null
                val wins = parts[4].toIntOrNull() ?: return@mapNotNull null
                val best = if (parts[5] == NO_TIME) null else parts[5].toFloatOrNull()
                val coins = parts[6].toIntOrNull() ?: return@mapNotNull null

                RecordKey(parts[0], GameSetup(mode, twist)) to LevelRecord(plays, wins, best, coins)
            }
            .toMap()

    private const val NO_TIME = "-"
}
