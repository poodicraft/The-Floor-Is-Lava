package com.paperjump.ai

/**
 * The wire format for Hugging Face's OpenAI-compatible router, kept free of Android so the
 * request we build and the replies we accept are both unit tested.
 *
 * The app talks to `router.huggingface.co` rather than to one model's own endpoint: the
 * router speaks the chat-completions shape every hosted vision model understands, so
 * changing the model is a setting rather than a rewrite.
 */
object AiProtocol {

    const val ENDPOINT = "https://router.huggingface.co/v1/chat/completions"

    /**
     * A vision model small enough to be served on the free tier. Overridable in Settings.
     *
     * Explicitly a *VL* model and one of the smaller ones, which is what makes it the
     * likeliest of the current crop to be carried by a provider on a free account.
     */
    const val DEFAULT_MODEL = "Qwen/Qwen3-VL-8B-Instruct"

    /**
     * Hugging Face's own list of vision models that a provider is actually serving.
     *
     * Asked at run time rather than guessed at build time. A hard-coded model name is a
     * bet on a landscape that moves weekly — the first one this app shipped with had
     * stopped being served by anybody by the time the app reached a phone. This is the
     * list the website itself shows, so it cannot go stale.
     */
    const val MODELS_ENDPOINT =
        "https://huggingface.co/api/models" +
            "?pipeline_tag=image-text-to-text&inference_provider=all&sort=trendingScore&limit=25"

    /** Model ids out of that listing; anything unexpected simply yields nothing. */
    fun parseModelIds(body: String): List<String> =
        Json.parse(body).asList()
            .mapNotNull { it["id"].asText()?.trim() }
            .filter { it.isNotBlank() && "/" in it }
            .distinct()

    /**
     * The fallback list, used when the live one cannot be fetched.
     *
     * Real ids, read off the live listing rather than remembered: every name written here
     * from memory so far has been wrong or stale by the time it reached a phone. Which
     * provider carries which model still changes weekly, so this is a last resort behind
     * [MODELS_ENDPOINT] rather than something to rely on.
     */
    val MODEL_CANDIDATES: List<String> = listOf(
        DEFAULT_MODEL,
        "Qwen/Qwen3.5-9B",
        "Qwen/Qwen3.8-27B",
        "google/gemma-4-26B-A4B-it",
        "google/gemma-4-31B-it",
        "Qwen/Qwen3.6-27B",
        "moonshotai/Kimi-K3",
    )

    /**
     * True when a failure means "this model, here, right now" rather than something the
     * player has to fix — the cases where trying a different model is worth a round trip.
     */
    fun worthTryingAnotherModel(status: Int, body: String): Boolean {
        if (status == 404) return true
        if (status != 400) return false
        val detail = errorDetail(body)?.lowercase().orEmpty()
        return "not supported" in detail || "provider" in detail || "model" in detail
    }

    /**
     * Builds the request body: the instructions, the player's own note, and the picture.
     *
     * [imageDataUrl] is a `data:image/...;base64,...` URL — the router takes the image
     * inline, which saves the app from needing anywhere to host one.
     */
    fun chatRequest(model: String, imageDataUrl: String, hint: String): String {
        val instructions = buildString {
            append(LevelPlan.PROMPT)
            if (hint.isNotBlank()) {
                append("\n\nThe person who drew it says: \"")
                append(hint.trim().take(300))
                append("\". Use that to decide what the shapes mean.")
            }
        }
        return """
            {"model":"${Json.escape(model)}",
             "max_tokens":1400,
             "temperature":0.3,
             "messages":[{"role":"user","content":[
               {"type":"text","text":"${Json.escape(instructions)}"},
               {"type":"image_url","image_url":{"url":"${Json.escape(imageDataUrl)}"}}
             ]}]}
        """.trimIndent().replace("\n", "")
    }

    /**
     * Digs the assistant's text out of a chat-completions reply.
     *
     * `content` is usually a string, but some providers return it as the same list of typed
     * parts the request uses, so both are accepted.
     */
    fun replyText(body: String): String? {
        val root = Json.parse(body) ?: return null
        val content = root["choices"].asList().firstOrNull()["message"]["content"]
        content.asText()?.let { return it }
        val parts = content.asList().mapNotNull { it["text"].asText() }
        return if (parts.isEmpty()) null else parts.joinToString("\n")
    }

    /** The message a failed call should show the player, given its status and body. */
    fun failureMessage(status: Int, body: String): String {
        val detail = errorDetail(body)
        val explanation = when (status) {
            401, 403 -> "Hugging Face rejected the key. Check it in Settings, and make sure " +
                "the token is allowed to call Inference Providers — a fine-grained token " +
                "needs that box ticked, a plain read token already has it."
            402 -> "This month's free Hugging Face credits are used up. It resets monthly, " +
                "or you can add credits to the account."
            400, 404 -> "No provider on your Hugging Face account is serving a vision model " +
                "the app can use. Open huggingface.co/settings/inference-providers and turn " +
                "the providers on — that is usually all it takes. A specific model can also " +
                "be set in Settings."
            408, 504 -> "Hugging Face took too long to answer. Try again."
            429 -> "Too many requests in a row. Wait a minute and try again."
            503 -> "The model is warming up on Hugging Face. Give it a minute and try again."
            in 500..599 -> "Hugging Face had a problem at their end ($status). Try again."
            else -> "Hugging Face refused the request ($status)."
        }
        return if (detail.isNullOrBlank()) explanation else "$explanation\n\n$detail"
    }

    /** The provider's own words, when it bothered to send any. */
    internal fun errorDetail(body: String): String? {
        val root = Json.parse(body) ?: return body.trim().take(200).ifBlank { null }
        val error = root["error"]
        return (error.asText() ?: error["message"].asText() ?: root["message"].asText())
            ?.trim()
            ?.take(200)
    }
}
