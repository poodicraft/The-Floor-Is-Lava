package com.lava.floorislava

import android.content.Context
import android.content.SharedPreferences

/**
 * How hard a round is: how long you get, how far away the safe zone can
 * spawn, and how big the safe circle is on the ground.
 */
enum class Difficulty(
    val label: String,
    val durationMs: Long,
    val minDistanceM: Double,
    val maxDistanceM: Double,
    val zoneRadiusM: Double
) {
    EASY("EASY", 180_000L, 15.0, 30.0, 8.0),
    NORMAL("NORMAL", 120_000L, 15.0, 45.0, 6.0),
    HARD("HARD", 75_000L, 30.0, 70.0, 5.0);

    /** Leaderboard points for escaping: a base per difficulty plus 1 per second left on the clock. */
    fun pointsForEscape(elapsedMs: Long): Int {
        val base = when (this) {
            EASY -> 50
            NORMAL -> 100
            HARD -> 200
        }
        val secondsLeft = ((durationMs - elapsedMs).coerceAtLeast(0L) / 1000L).toInt()
        return base + secondsLeft
    }

    /** e.g. "2:00 · safe zone 15–45 m away" */
    fun summary(): String =
        "${formatDuration(durationMs)} · safe zone ${minDistanceM.toInt()}–${maxDistanceM.toInt()} m away"

    companion object {
        /** Extra leaderboard points for beating a friend in a multiplayer race. */
        const val MULTIPLAYER_WIN_BONUS = 100

        fun fromName(name: String?): Difficulty = values().firstOrNull { it.name == name } ?: NORMAL
    }
}

/** Formats milliseconds as m:ss. */
fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).toInt()
    return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60)
}

/** Settings and lifetime stats, kept in SharedPreferences. */
object GamePrefs {

    private const val FILE = "floor_is_lava"
    private const val KEY_DIFFICULTY = "difficulty"
    private const val KEY_HAPTICS = "haptics"
    private const val KEY_PLAYED = "played"
    private const val KEY_WINS = "wins"
    private const val KEY_STREAK = "streak"
    private const val KEY_BEST_STREAK = "best_streak"
    private const val KEY_BEST_TIME_PREFIX = "best_time_"

    data class Stats(val played: Int, val wins: Int, val streak: Int, val bestStreak: Int) {
        val winRatePercent: Int get() = if (played == 0) 0 else (wins * 100) / played
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun difficulty(context: Context): Difficulty {
        return Difficulty.fromName(prefs(context).getString(KEY_DIFFICULTY, Difficulty.NORMAL.name))
    }

    fun setDifficulty(context: Context, difficulty: Difficulty) {
        prefs(context).edit().putString(KEY_DIFFICULTY, difficulty.name).apply()
    }

    fun hapticsEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_HAPTICS, true)

    fun setHapticsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_HAPTICS, enabled).apply()
    }

    fun stats(context: Context): Stats {
        val p = prefs(context)
        return Stats(
            played = p.getInt(KEY_PLAYED, 0),
            wins = p.getInt(KEY_WINS, 0),
            streak = p.getInt(KEY_STREAK, 0),
            bestStreak = p.getInt(KEY_BEST_STREAK, 0)
        )
    }

    /** Fastest escape for [difficulty] in ms, or 0 if never won on it. */
    fun bestTimeMs(context: Context, difficulty: Difficulty): Long =
        prefs(context).getLong(KEY_BEST_TIME_PREFIX + difficulty.name, 0L)

    /**
     * Records a finished round. Returns true if it was a win that beat the
     * previous fastest escape on this difficulty.
     */
    fun recordResult(context: Context, difficulty: Difficulty, won: Boolean, elapsedMs: Long): Boolean {
        val p = prefs(context)
        val old = stats(context)
        val streak = if (won) old.streak + 1 else 0
        val editor = p.edit()
            .putInt(KEY_PLAYED, old.played + 1)
            .putInt(KEY_WINS, old.wins + if (won) 1 else 0)
            .putInt(KEY_STREAK, streak)
            .putInt(KEY_BEST_STREAK, maxOf(old.bestStreak, streak))

        var newBest = false
        if (won) {
            val previousBest = bestTimeMs(context, difficulty)
            if (previousBest == 0L || elapsedMs < previousBest) {
                editor.putLong(KEY_BEST_TIME_PREFIX + difficulty.name, elapsedMs)
                newBest = true
            }
        }
        editor.apply()
        return newBest
    }
}
