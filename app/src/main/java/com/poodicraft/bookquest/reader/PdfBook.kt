package com.poodicraft.bookquest.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File

/** Thin, thread safe wrapper around the platform PDF renderer. */
class PdfBook private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer
) : Closeable {

    private val lock = Any()
    private var closed = false

    val pageCount: Int get() = renderer.pageCount

    fun renderPage(index: Int, targetWidth: Int): Bitmap? {
        synchronized(lock) {
            if (closed || index < 0 || index >= renderer.pageCount) return null
            var page: PdfRenderer.Page? = null
            try {
                val opened = renderer.openPage(index)
                page = opened
                val width = targetWidth.coerceIn(320, 2200)
                val height = (width.toFloat() * opened.height / opened.width)
                    .toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                opened.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bitmap
            } catch (e: Exception) {
                return null
            } finally {
                try {
                    page?.close()
                } catch (e: Exception) {
                    // Nothing useful to do if the page will not close.
                }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            try {
                renderer.close()
            } catch (e: Exception) {
                // Ignore.
            }
            try {
                descriptor.close()
            } catch (e: Exception) {
                // Ignore.
            }
        }
    }

    companion object {
        fun open(file: File): PdfBook? = try {
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            PdfBook(descriptor, PdfRenderer(descriptor))
        } catch (e: Exception) {
            null
        }
    }
}
