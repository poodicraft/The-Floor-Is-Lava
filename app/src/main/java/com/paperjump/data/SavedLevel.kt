package com.paperjump.data

import com.paperjump.processing.ProcessingConfig

/**
 * Metadata for a level in the library, and its on-disk encoding.
 *
 * A saved level is stored as the **source image** plus the tuning used to read it, not as a
 * serialised `LevelData`. The image is the thing the player actually made; the level is
 * derived from it in milliseconds and always by the current detector, so improving the
 * detector improves every level already in the library instead of leaving them frozen.
 *
 * Pure Kotlin so the format is unit tested on the JVM — file handling lives in [LevelStore].
 */

/** Where a level came from, shown as a badge in the library. */
enum class LevelSource(val label: String) {
    DRAWN("Drawn"),
    PHOTO("Photo"),
    SAMPLE("Sample"),
}

data class SavedLevelMeta(
    val id: String,
    val name: String,
    val createdAt: Long,
    val source: LevelSource,
    val config: ProcessingConfig,
)

/**
 * A deliberately boring `key=value` line format.
 *
 * Hand-rolled rather than JSON so it stays free of Android's `org.json` (which is stubbed
 * out in unit tests) without pulling in a serialisation dependency for six fields.
 */
object LevelMetaCodec {

    fun encode(meta: SavedLevelMeta): String = buildString {
        appendLine("id=${meta.id}")
        // Newlines would break the line format; a name is one line by definition.
        appendLine("name=${meta.name.replace('\n', ' ').replace('\r', ' ').trim()}")
        appendLine("createdAt=${meta.createdAt}")
        appendLine("source=${meta.source.name}")
        appendLine("gridCols=${meta.config.gridCols}")
        appendLine("inkSensitivity=${meta.config.inkSensitivity}")
        appendLine("colorSensitivity=${meta.config.colorSensitivity}")
        appendLine("minBlobCells=${meta.config.minBlobCells}")
    }

    /** Returns `null` for anything unreadable, so one corrupt file cannot hide the library. */
    fun decode(text: String): SavedLevelMeta? {
        val fields = text.lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
            }
            .toMap()

        val id = fields["id"]?.takeIf { it.isNotBlank() } ?: return null
        val createdAt = fields["createdAt"]?.toLongOrNull() ?: return null
        val source = fields["source"]?.let { name ->
            LevelSource.entries.firstOrNull { it.name == name }
        } ?: return null

        val defaults = ProcessingConfig()
        return SavedLevelMeta(
            id = id,
            name = fields["name"].orEmpty().ifBlank { "Untitled level" },
            createdAt = createdAt,
            source = source,
            config = ProcessingConfig(
                gridCols = fields["gridCols"]?.toIntOrNull() ?: defaults.gridCols,
                inkSensitivity = fields["inkSensitivity"]?.toFloatOrNull() ?: defaults.inkSensitivity,
                colorSensitivity = fields["colorSensitivity"]?.toFloatOrNull() ?: defaults.colorSensitivity,
                minBlobCells = fields["minBlobCells"]?.toIntOrNull() ?: defaults.minBlobCells,
            ),
        )
    }
}
