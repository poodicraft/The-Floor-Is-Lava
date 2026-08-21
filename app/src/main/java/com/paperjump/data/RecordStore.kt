package com.paperjump.data

import android.content.Context
import com.paperjump.game.GameMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Personal bests, kept in one small tab-separated file. See [RecordCodec] for the format. */
class RecordStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)

    @Volatile
    private var cache: Map<RecordKey, LevelRecord>? = null

    suspend fun all(): Map<RecordKey, LevelRecord> {
        cache?.let { return it }
        return withContext(Dispatchers.IO) {
            val loaded = runCatching {
                if (file.exists()) RecordCodec.decode(file.readText()) else emptyMap()
            }.getOrDefault(emptyMap())
            cache = loaded
            loaded
        }
    }

    suspend fun recordFor(levelKey: String, mode: GameMode): LevelRecord =
        all()[RecordKey(levelKey, mode)] ?: LevelRecord()

    /** Folds a finished run in and persists. Returns the record as it now stands. */
    suspend fun record(
        levelKey: String,
        mode: GameMode,
        won: Boolean,
        timeSeconds: Float,
        coins: Int,
    ): LevelRecord {
        val key = RecordKey(levelKey, mode)
        val updated = (all()[key] ?: LevelRecord()).after(won, timeSeconds, coins)
        val merged = all() + (key to updated)
        cache = merged
        withContext(Dispatchers.IO) {
            runCatching { file.writeText(RecordCodec.encode(merged)) }
        }
        return updated
    }

    suspend fun clear() {
        cache = emptyMap()
        withContext(Dispatchers.IO) { file.delete() }
    }

    private companion object {
        const val FILE_NAME = "records.tsv"
    }
}
