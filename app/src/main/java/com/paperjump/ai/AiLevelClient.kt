package com.paperjump.ai

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import kotlin.math.max
import kotlin.math.roundToInt

/** What one attempt at turning a drawing into a level came back with. */
sealed interface AiOutcome {
    data class Success(val plan: LevelPlan) : AiOutcome
    data class Failure(val message: String) : AiOutcome
}

/**
 * Sends a drawing to a hosted vision model and gets a level plan back.
 *
 * The only part of the app that touches the network, and the only part that needs a key.
 * It fails in words rather than exceptions: every way this can go wrong — no key, no
 * signal, a model that is asleep, a reply that is not JSON — ends as a [AiOutcome.Failure]
 * with something the player can act on, because the caller always has a working fallback.
 */
object AiLevelClient {

    /** Big enough for a model to read the drawing, small enough to send over mobile data. */
    private const val MAX_IMAGE_EDGE = 768
    private const val JPEG_QUALITY = 85

    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 90_000

    /**
     * @param proxyUrl a server that holds the key, or blank to call Hugging Face directly
     * @param token the player's own key; ignored, and not sent, when [proxyUrl] is set
     */
    suspend fun planLevel(
        drawing: Bitmap,
        hint: String,
        token: String,
        model: String,
        proxyUrl: String = "",
        appSecret: String = "",
    ): AiOutcome = withContext(Dispatchers.IO) {
        val viaProxy = proxyUrl.isNotBlank()
        if (!viaProxy && token.isBlank()) {
            return@withContext AiOutcome.Failure(
                "No Hugging Face key yet. Settings → AI level designer is where it goes.",
            )
        }

        val body = AiProtocol.chatRequest(
            model = model.ifBlank { AiProtocol.DEFAULT_MODEL },
            imageDataUrl = dataUrl(drawing),
            hint = hint,
        )

        val endpoint = if (viaProxy) proxyUrl.trim() else AiProtocol.ENDPOINT
        val response = runCatching {
            // Through a proxy the app sends no key at all — that is the whole point of it.
            post(endpoint, body, if (viaProxy) "" else token, appSecret)
        }.getOrElse { error ->
            return@withContext AiOutcome.Failure(
                when (error) {
                    is UnknownHostException ->
                        "Could not reach the level designer. Check the phone's connection."
                    else -> "Could not reach the level designer (${error.javaClass.simpleName})."
                },
            )
        }

        if (response.status !in 200..299) {
            return@withContext AiOutcome.Failure(
                AiProtocol.failureMessage(response.status, response.body),
            )
        }

        val reply = AiProtocol.replyText(response.body)
            ?: return@withContext AiOutcome.Failure("Hugging Face sent a reply I could not read.")

        val plan = LevelPlan.parse(reply)
            ?: return@withContext AiOutcome.Failure(
                "The model did not describe a level it could build. Try again, or add a " +
                    "note saying what you drew.",
            )

        AiOutcome.Success(plan)
    }

    private class Response(val status: Int, val body: String)

    private fun post(endpoint: String, body: String, token: String, appSecret: String): Response {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer ${token.trim()}")
            if (appSecret.isNotBlank()) setRequestProperty("x-paperengine", appSecret.trim())
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return Response(status, text)
        } finally {
            connection.disconnect()
        }
    }

    /** The drawing, shrunk and inlined as a data URL. */
    private fun dataUrl(drawing: Bitmap): String {
        val scaled = downscale(drawing)
        val bytes = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
        if (scaled !== drawing) scaled.recycle()
        return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun downscale(bitmap: Bitmap): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= MAX_IMAGE_EDGE) return bitmap
        val scale = MAX_IMAGE_EDGE.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            max(1, (bitmap.width * scale).roundToInt()),
            max(1, (bitmap.height * scale).roundToInt()),
            true,
        )
    }
}
