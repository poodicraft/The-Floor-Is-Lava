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

    /** A vision model small enough to be served on the free tier. Overridable in Settings. */
    const val DEFAULT_MODEL = "Qwen/Qwen2.5-VL-7B-Instruct"

    /**
     * Models to try, in order, when the chosen one is not being served.
     *
     * Hugging Face does not run these itself — it routes to partner providers, and which
     * provider carries which model changes without notice and differs per account. Pinning
     * one model is therefore a promise the app cannot keep: the first real call failed with
     * "not supported by any provider you have enabled" on a model that was serving fine
     * when it was chosen. Walking a list costs one wasted round trip in the bad case and
     * makes the feature survive the landscape moving underneath it.
     */
    val MODEL_CANDIDATES: List<String> = listOf(
        DEFAULT_MODEL,
        "Qwen/Qwen2.5-VL-72B-Instruct",
        "meta-llama/Llama-3.2-11B-Vision-Instruct",
        "google/gemma-3-27b-it",
        "mistralai/Mistral-Small-3.1-24B-Instruct-2503",
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
