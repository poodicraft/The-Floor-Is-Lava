package com.lava.floorislava.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.camera.core.ImageProxy
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Bitmap plumbing shared by the camera and the gallery picker.
 *
 * Everything here downsamples *while decoding* — a 12 MP phone photo is ~48 MB as an
 * ARGB bitmap and there is no reason to ever hold one: the detector works on a 900 px
 * copy anyway.
 */
object ImageUtils {

    /** Longest edge we keep in memory for the source sketch (also used for the preview). */
    const val SOURCE_MAX_DIMENSION = 1600

    /** Decodes a gallery [Uri], honouring the EXIF orientation phones write. */
    suspend fun loadFromUri(
        context: Context,
        uri: Uri,
        maxDimension: Int = SOURCE_MAX_DIMENSION,
    ): Bitmap? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDimension)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri)?.use { stream: InputStream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: return@withContext null

        val degrees = resolver.openInputStream(uri)?.use { exifRotation(it) } ?: 0
        val rotated = rotate(decoded, degrees.toFloat())
        scaleToFit(rotated, maxDimension)
    }

    /**
     * Converts a CameraX capture result into an upright bitmap.
     *
     * `ImageCapture` hands back JPEG bytes in a single plane plus the rotation the sensor
     * was held at; both have to be applied by hand.
     */
    fun fromImageProxy(image: ImageProxy, maxDimension: Int = SOURCE_MAX_DIMENSION): Bitmap? {
        val buffer = image.planes.firstOrNull()?.buffer ?: return null
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDimension)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val rotated = rotate(decoded, image.imageInfo.rotationDegrees.toFloat())
        return scaleToFit(rotated, maxDimension)
    }

    private fun exifRotation(stream: InputStream): Int =
        when (
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

    private fun rotate(bitmap: Bitmap, degrees: Float): Bitmap {
        if (degrees % 360f == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun scaleToFit(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val longestEdge = max(bitmap.width, bitmap.height)
        if (longestEdge <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / longestEdge
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            max(1, (bitmap.width * scale).roundToInt()),
            max(1, (bitmap.height * scale).roundToInt()),
            true,
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    /** Largest power-of-two subsample that still leaves the image above [maxDimension]. */
    private fun sampleSizeFor(width: Int, height: Int, maxDimension: Int): Int {
        var sampleSize = 1
        var longestEdge = max(width, height)
        while (longestEdge / 2 >= maxDimension) {
            longestEdge /= 2
            sampleSize *= 2
        }
        return sampleSize
    }
}
