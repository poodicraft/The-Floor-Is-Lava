package com.paperjump.ai

/**
 * The wire format for Google AI Studio's Gemini API.
 *
 * Here because Hugging Face turned out not to work without a payment method on file:
 * every model, however current, came back "not supported by any provider you have
 * enabled". Google AI Studio's free tier reads images, needs no card, and is a different
 * shape of request — so it gets its own file rather than a pile of `if`s in the other one.
 *
 * Pure Kotlin, like its Hugging Face counterpart, so the request built and the replies
 * accepted are both unit tested without a key or a network.
 */
object GoogleProtocol {

    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"

    /** The key goes in a header rather than the query string, so it stays out of any log. */
    const val KEY_HEADER = "x-goog-api-key"

    /** Keys from AI Studio all start with this, which is how the app knows the provider. */
    const val KEY_PREFIX = "AIza"

    /** Fast, cheap, reads images, and the free tier's workhorse. */
    const val DEFAULT_MODEL = "gemini-2.5-flash"

    /**
     * The fallback list.
     *
     * A last resort behind [MODELS_ENDPOINT], for the same reason as on the other provider:
     * a model name written today is a guess about next month. These are the long-lived
     * names rather than the newest ones.
     */
    val MODEL_CANDIDATES: List<String> = listOf(
        DEFAULT_MODEL,
        "gemini-flash-latest",
        "gemini-2.0-flash",
        "gemini-2.5-flash-lite",
        "gemini-2.5-pro",
    )

    /** Google's own list of what this key may call. */
    const val MODELS_ENDPOINT = "$BASE/models"

    fun endpointFor(model: String): String =
        "$BASE/models/${model.trim().removePrefix("models/")}:generateContent"

    /**
     * Model names that can actually answer this kind of request.
     *
     * Filtered on `generateContent`: the listing also carries embedding and token-counting
     * models, which would each cost a wasted round trip to discover the hard way.
     */
    fun parseModelIds(body: String): List<String> =
        Json.parse(body)["models"].asList()
            .filter { model ->
                model["supportedGenerationMethods"].asList().any { it.asText() == "generateContent" }
            }
            .mapNotNull { it["name"].asText()?.trim()?.removePrefix("models/") }
            .filter { it.isNotBlank() && !it.contains("embedding") }
            .distinct()

    /**
     * The request: the instructions, the player's note, and the picture inline.
     *
     * [imageBase64] is the raw base64 of a JPEG — no `data:` prefix, unlike the other
     * provider, which is exactly the sort of difference that earns this its own file.
     */
    fun generateRequest(imageBase64: String, hint: String): String {
        val instructions = buildString {
            append(LevelPlan.PROMPT)
            if (hint.isNotBlank()) {
                append("\n\nThe person who drew it says: \"")
                append(hint.trim().take(300))
                append("\". Use that to decide what the shapes mean.")
            }
        }
        return """
            {"contents":[{"role":"user","parts":[
               {"text":"${Json.escape(instructions)}"},
               {"inline_data":{"mime_type":"image/jpeg","data":"${Json.escape(imageBase64)}"}}
             ]}],
             "generationConfig":{"temperature":0.3,"maxOutputTokens":1400}}
        """.trimIndent().replace("\n", "")
    }

    /** The answer text, out of the first candidate's parts. */
    fun replyText(body: String): String? {
        val parts = Json.parse(body)["candidates"].asList().firstOrNull()["content"]["parts"]
            .asList()
            .mapNotNull { it["text"].asText() }
        return if (parts.isEmpty()) null else parts.joinToString("\n")
    }

    /** Whether a different model is worth a try, or the problem is the player's to fix. */
    fun worthTryingAnotherModel(status: Int, body: String): Boolean {
        if (status == 404) return true
        if (status != 400) return false
        val detail = errorDetail(body)?.lowercase().orEmpty()
        return "model" in detail || "not found" in detail || "not supported" in detail
    }

    fun failureMessage(status: Int, body: String): String {
        val detail = errorDetail(body)
        val explanation = when (status) {
            400 -> "Google would not take that request. If the key was only just made, give " +
                "it a minute and try again."
            401, 403 -> "Google rejected the key. Make a new one at aistudio.google.com/apikey " +
                "and paste it into Settings — it should start with AIza."
            404 -> "That model is not available to this key. The app will try others."
            429 -> "That is the free tier's limit for now. It resets by the minute and by the " +
                "day, so waiting a moment usually clears it."
            in 500..599 -> "Google had a problem at their end ($status). Try again."
            else -> "Google refused the request ($status)."
        }
        return if (detail.isNullOrBlank()) explanation else "$explanation\n\n$detail"
    }

    /** Gemini nests its message one deeper than most: `{"error":{"message":"…"}}`. */
    internal fun errorDetail(body: String): String? {
        val root = Json.parse(body) ?: return body.trim().take(200).ifBlank { null }
        return (root["error"]["message"].asText() ?: root["message"].asText())
            ?.trim()
            ?.take(200)
    }
}
