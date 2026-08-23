package com.paperjump.ai

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import kotlin.math.max
import kotlin.math.roundToInt

/** What one attempt at turning a drawing into a level came back with. */
sealed interface AiOutcome {
    /** @param model the one that actually answered, which may not be the one asked for */
    data class Success(val plan: LevelPlan, val model: String) : AiOutcome
    data class Failure(val message: String) : AiOutcome
}

/**
 * Sends a drawing to a hosted vision model and gets a level plan back.
 *
 * The only part of the app that touches the network, and the only part that needs a key.
 * It fails in words rather than exceptions: every way this can go wrong — no key, no
 * signal, a model that is asleep, a reply that is not JSON — ends as a [AiOutcome.Failure]
 * with something the player can act on, because the caller always has a working fallback.
 *
 * Which service it talks to comes from the key ([AiProvider.forKey]); everything that
 * differs between them lives in [AiProvider], so what is left here is one loop.
 */
object AiLevelClient {

    /** Big enough for a model to read the drawing, small enough to send over mobile data. */
    private const val MAX_IMAGE_EDGE = 768
    private const val JPEG_QUALITY = 85

    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 90_000

    /** Looking up the model list must not hold the drawing up for long. */
    private const val LIST_TIMEOUT_MS = 15_000

    /** At most this many round trips before giving up; each one costs the player a wait. */
    private const val MAX_MODELS_TRIED = 6

    /**
     * @param proxyUrl a server that holds the key, or blank to call the service directly
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
                "No key yet. Settings → AI level designer is where it goes.",
            )
        }

        // Through a proxy there is no key here to read the service off, and the proxy that
        // ships with the app is Google's — see server/README.md.
        val provider = if (viaProxy) AiProvider.GOOGLE else AiProvider.forKey(token)
        val imageBase64 = base64Jpeg(drawing)

        // Try the chosen model first, then whatever the service says it is serving right
        // now, then the built-in list. Model names go stale faster than releases do, so no
        // single name — and no list written months ago — is something to rely on.
        val candidates = buildList {
            model.trim().takeIf { it.isNotEmpty() }?.let { add(it) }
            // Through a proxy the model has to be one the proxy allows, so the live list
            // is skipped: it would only produce names the proxy is going to refuse.
            if (!viaProxy) discoverModels(provider, token).forEach { if (it !in this) add(it) }
            provider.modelCandidates.forEach { if (it !in this) add(it) }
        }.take(MAX_MODELS_TRIED)

        var lastFailure = "${provider.label} did not answer."
        var everyModelRefused = true
        for (candidate in candidates) {
            val body = provider.requestBody(candidate, imageBase64, hint)
            val endpoint =
                if (viaProxy) proxyEndpoint(proxyUrl, candidate) else provider.endpointFor(candidate)

            val response = runCatching {
                // Through a proxy the app sends no key at all — that is the whole point.
                post(endpoint, body, provider, if (viaProxy) "" else token, appSecret)
            }.getOrElse { error ->
                // A network failure will not be cured by asking for a different model.
                return@withContext AiOutcome.Failure(
                    when (error) {
                        is UnknownHostException ->
                            "Could not reach the level designer. Check the phone's connection."
                        else -> "Could not reach the level designer (${error.javaClass.simpleName})."
                    },
                )
            }

            if (response.status !in 200..299) {
                lastFailure = provider.failureMessage(response.status, response.body)
                if (provider.worthTryingAnotherModel(response.status, response.body)) continue
                return@withContext AiOutcome.Failure(lastFailure)
            }
            // It answered; whatever went wrong after this is not about availability.
            everyModelRefused = false

            val reply = provider.replyText(response.body)
            if (reply == null) {
                lastFailure = "${provider.label} sent a reply I could not read."
                continue
            }

            val plan = LevelPlan.parse(reply)
            if (plan == null) {
                lastFailure = "The model did not describe a level it could build. Try again, " +
                    "or add a note saying what you drew."
                continue
            }

            return@withContext AiOutcome.Success(plan, candidate)
        }

        AiOutcome.Failure(
            if (everyModelRefused) {
                "None of the vision models the app knows about would take the job. That is " +
                    "${provider.label}'s side rather than yours. Try again in a minute, or " +
                    "put a model that works into Settings.\n\n" + lastFailure
            } else {
                lastFailure
            },
        )
    }

    /** The proxy takes the model in the query string, since Google's body has no room for it. */
    private fun proxyEndpoint(proxyUrl: String, model: String): String {
        val base = proxyUrl.trim()
        val separator = if ("?" in base) "&" else "?"
        return base + separator + "model=" + URLEncoder.encode(model, "UTF-8")
    }

    /**
     * Asks the service which models this key may currently call.
     *
     * Best effort by design: a failure here is not worth reporting, because the built-in
     * list is still there and the caller is about to try it.
     */
    private fun discoverModels(provider: AiProvider, token: String): List<String> = runCatching {
        val response = get(provider.modelsEndpoint, provider, token)
        if (response.status in 200..299) provider.parseModelIds(response.body) else emptyList()
    }.getOrDefault(emptyList())

    private class Response(val status: Int, val body: String)

    private fun get(endpoint: String, provider: AiProvider, token: String): Response {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = LIST_TIMEOUT_MS
            if (token.isNotBlank()) {
                val (name, value) = provider.authHeader(token)
                setRequestProperty(name, value)
            }
            setRequestProperty("Accept", "application/json")
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            return Response(status, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private fun post(
        endpoint: String,
        body: String,
        provider: AiProvider,
        token: String,
        appSecret: String,
    ): Response {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            if (token.isNotBlank()) {
                val (name, value) = provider.authHeader(token)
                setRequestProperty(name, value)
            }
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

    /** The drawing, shrunk and encoded; each provider wraps this in its own way. */
    private fun base64Jpeg(drawing: Bitmap): String {
        val scaled = downscale(drawing)
        val bytes = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
        if (scaled !== drawing) scaled.recycle()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
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
