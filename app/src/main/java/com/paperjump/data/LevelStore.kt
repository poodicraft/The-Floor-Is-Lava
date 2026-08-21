package com.paperjump.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.paperjump.processing.ProcessingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The level library on disk: one image file and one metadata file per saved level.
 *
 * Plain files rather than a database — there is no querying to do, the whole library is a
 * directory listing, and a level the user can find and copy off the device is a feature.
 */
class LevelStore(context: Context) {

    private val directory = File(context.filesDir, DIRECTORY_NAME)

    /** Newest first, which is the order the library shows them in. */
    suspend fun list(): List<SavedLevelMeta> = withContext(Dispatchers.IO) {
        val files = directory.listFiles { file -> file.name.endsWith(META_EXTENSION) }.orEmpty()
        files.mapNotNull { file ->
            runCatching { LevelMetaCodec.decode(file.readText()) }.getOrNull()
        }.sortedByDescending { it.createdAt }
    }

    suspend fun save(
        bitmap: Bitmap,
        name: String,
        source: LevelSource,
        config: ProcessingConfig,
        id: String = UUID.randomUUID().toString(),
    ): SavedLevelMeta = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val meta = SavedLevelMeta(
            id = id,
            name = name.trim().ifBlank { defaultName(source) },
            createdAt = System.currentTimeMillis(),
            source = source,
            config = config,
        )

        val stored = downscale(bitmap, MAX_STORED_DIMENSION)
        imageFile(id).outputStream().use { out ->
            // Drawings are flat colour and stay crisp (and small) as PNG; photographs are
            // full of noise, where PNG would be several megabytes for no benefit.
            if (source == LevelSource.PHOTO) {
                stored.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            } else {
                stored.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
        if (stored !== bitmap) stored.recycle()

        metaFile(id).writeText(LevelMetaCodec.encode(meta))
        meta
    }

    suspend fun load(id: String): Bitmap? = withContext(Dispatchers.IO) {
        val file = imageFile(id)
        if (!file.exists()) null else BitmapFactory.decodeFile(file.absolutePath)
    }

    /** A small decode for the library grid — never the full image. */
    suspend fun loadThumbnail(id: String, maxDimension: Int = THUMBNAIL_DIMENSION): Bitmap? =
        withContext(Dispatchers.IO) {
            val file = imageFile(id)
            if (!file.exists()) return@withContext null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val longestEdge = max(bounds.outWidth, bounds.outHeight)

            val options = BitmapFactory.Options().apply {
                inSampleSize = generateSequence(1) { it * 2 }
                    .first { longestEdge / it <= maxDimension }
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
        }

    /** Rewrites just the tuning of an already-saved level, leaving its image and name. */
    suspend fun updateConfig(id: String, config: ProcessingConfig): Unit = withContext(Dispatchers.IO) {
        val file = metaFile(id)
        val existing = runCatching { LevelMetaCodec.decode(file.readText()) }.getOrNull() ?: return@withContext
        if (existing.config == config) return@withContext
        file.writeText(LevelMetaCodec.encode(existing.copy(config = config)))
    }

    suspend fun rename(id: String, name: String): Unit = withContext(Dispatchers.IO) {
        val file = metaFile(id)
        val existing = runCatching { LevelMetaCodec.decode(file.readText()) }.getOrNull() ?: return@withContext
        file.writeText(LevelMetaCodec.encode(existing.copy(name = name.trim().ifBlank { existing.name })))
    }

    suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        metaFile(id).delete()
        imageFile(id).delete()
    }

    suspend fun deleteAll(): Unit = withContext(Dispatchers.IO) {
        directory.listFiles()?.forEach { it.delete() }
    }

    private fun metaFile(id: String) = File(directory, "$id$META_EXTENSION")

    private fun imageFile(id: String) = File(directory, "$id$IMAGE_EXTENSION")

    private fun defaultName(source: LevelSource) = when (source) {
        LevelSource.DRAWN -> "My drawing"
        LevelSource.PHOTO -> "Photographed level"
        LevelSource.SAMPLE -> "Sample level"
    }

    /** The detector analyses at 900px, so storing much more than that is dead weight. */
    private fun downscale(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val longestEdge = max(bitmap.width, bitmap.height)
        if (longestEdge <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / longestEdge
        return Bitmap.createScaledBitmap(
            bitmap,
            max(1, (bitmap.width * scale).roundToInt()),
            max(1, (bitmap.height * scale).roundToInt()),
            true,
        )
    }

    private companion object {
        const val DIRECTORY_NAME = "levels"
        const val META_EXTENSION = ".meta"
        const val IMAGE_EXTENSION = ".img"
        const val MAX_STORED_DIMENSION = 1400
        const val THUMBNAIL_DIMENSION = 320
        const val JPEG_QUALITY = 92
    }
}
