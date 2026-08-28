package com.poodicraft.bookquest.reader

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Reads plain text books. School files arrive in all sorts of encodings, so we
 * try UTF-8 first and fall back to the legacy Hebrew and Arabic code pages.
 */
object TextLoader {

    private const val MAX_BYTES = 8 * 1024 * 1024

    fun read(file: File): String {
        val raw = readCapped(file)
        if (raw.isEmpty()) return ""

        if (raw.size >= 3 && raw[0] == 0xEF.toByte() && raw[1] == 0xBB.toByte() && raw[2] == 0xBF.toByte()) {
            return decodeOrNull(raw.copyOfRange(3, raw.size), Charsets.UTF_8) ?: ""
        }
        if (raw.size >= 2 && raw[0] == 0xFF.toByte() && raw[1] == 0xFE.toByte()) {
            return String(raw, Charsets.UTF_16LE)
        }
        if (raw.size >= 2 && raw[0] == 0xFE.toByte() && raw[1] == 0xFF.toByte()) {
            return String(raw, Charsets.UTF_16BE)
        }

        decodeOrNull(raw, Charsets.UTF_8)?.let { return it }

        val legacy = if (looksArabic(raw)) charsetOrNull("windows-1256") else charsetOrNull("windows-1255")
        legacy?.let { charset -> decodeOrNull(raw, charset)?.let { return it } }
        return String(raw, Charsets.ISO_8859_1)
    }

    private fun readCapped(file: File): ByteArray {
        return try {
            if (file.length() <= MAX_BYTES) {
                file.readBytes()
            } else {
                val buffer = ByteArray(MAX_BYTES)
                file.inputStream().use { it.read(buffer) }
                buffer
            }
        } catch (e: Exception) {
            ByteArray(0)
        }
    }

    /** Counts bytes that are common Arabic letters in windows-1256 versus Hebrew ones in windows-1255. */
    private fun looksArabic(bytes: ByteArray): Boolean {
        var arabic = 0
        var hebrew = 0
        val sample = minOf(bytes.size, 20000)
        for (i in 0 until sample) {
            val value = bytes[i].toInt() and 0xFF
            if (value in 0xC7..0xDE) arabic++
            if (value in 0xE0..0xFA) hebrew++
        }
        return arabic > hebrew
    }

    private fun charsetOrNull(name: String): Charset? = try {
        Charset.forName(name)
    } catch (e: Exception) {
        null
    }

    private fun decodeOrNull(bytes: ByteArray, charset: Charset): String? = try {
        val decoder = charset.newDecoder()
        decoder.onMalformedInput(CodingErrorAction.REPORT)
        decoder.onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: Exception) {
        null
    }
}
