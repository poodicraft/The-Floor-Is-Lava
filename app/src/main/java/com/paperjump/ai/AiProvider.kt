package com.paperjump.ai

/**
 * Which service the level designer is talking to, worked out from the key itself.
 *
 * There is nothing to choose in the app: keys announce who issued them — Google AI Studio's
 * start `AIza`, Hugging Face's start `hf_` — so pasting one is the whole of the setup. That
 * matters because the two services agree on nothing. Different URL, different header,
 * different request shape, different place to find the answer in the reply. All of that
 * lives behind this enum so [AiLevelClient] can stay one loop rather than two.
 *
 * Google is the default and the one the app ships pointed at: its free tier reads pictures
 * without a payment method on file, which is the whole reason this feature can exist at all.
 * Hugging Face's routing needs a card before it will serve anything, so it stays supported
 * for anyone who already has one, but it is no longer the way in.
 */
enum class AiProvider {

    GOOGLE {
        override val label = "Google AI Studio"
        override val keyPrefixes = GoogleProtocol.KEY_PREFIXES
        override val keyHome = "aistudio.google.com/apikey"
        override val defaultModel = GoogleProtocol.DEFAULT_MODEL
        override val modelCandidates = GoogleProtocol.MODEL_CANDIDATES
        override val modelsEndpoint = GoogleProtocol.MODELS_ENDPOINT

        override fun endpointFor(model: String) = GoogleProtocol.endpointFor(model)
        override fun authHeader(key: String) = GoogleProtocol.KEY_HEADER to key.trim()
        override fun parseModelIds(body: String) = GoogleProtocol.parseModelIds(body)
        override fun replyText(body: String) = GoogleProtocol.replyText(body)
        override fun failureMessage(status: Int, body: String) =
            GoogleProtocol.failureMessage(status, body)

        override fun worthTryingAnotherModel(status: Int, body: String) =
            GoogleProtocol.worthTryingAnotherModel(status, body)

        // Google takes the picture as bare base64 and the model in the URL, so the body
        // says nothing about which model is answering.
        override fun requestBody(model: String, imageBase64: String, hint: String) =
            GoogleProtocol.generateRequest(imageBase64, hint)
    },

    HUGGING_FACE {
        override val label = "Hugging Face"
        override val keyPrefixes = listOf("hf_")
        override val keyHome = "huggingface.co/settings/tokens"
        override val defaultModel = AiProtocol.DEFAULT_MODEL
        override val modelCandidates = AiProtocol.MODEL_CANDIDATES
        override val modelsEndpoint = AiProtocol.MODELS_ENDPOINT

        // One endpoint for every model here: the router works out where to send it.
        override fun endpointFor(model: String) = AiProtocol.ENDPOINT
        override fun authHeader(key: String) = "Authorization" to "Bearer ${key.trim()}"
        override fun parseModelIds(body: String) = AiProtocol.parseModelIds(body)
        override fun replyText(body: String) = AiProtocol.replyText(body)
        override fun failureMessage(status: Int, body: String) =
            AiProtocol.failureMessage(status, body)

        override fun worthTryingAnotherModel(status: Int, body: String) =
            AiProtocol.worthTryingAnotherModel(status, body)

        override fun requestBody(model: String, imageBase64: String, hint: String) =
            AiProtocol.chatRequest(model, "data:image/jpeg;base64,$imageBase64", hint)
    };

    /** How to name this service to a player. */
    abstract val label: String

    /** What its keys tend to begin with, which is how [forKey] tells them apart. */
    abstract val keyPrefixes: List<String>

    /** Where a free key comes from, for the app to point at. */
    abstract val keyHome: String

    abstract val defaultModel: String

    /** Names to fall back on when the live listing cannot be read. */
    abstract val modelCandidates: List<String>

    /** The service's own list of what this key may call. */
    abstract val modelsEndpoint: String

    abstract fun endpointFor(model: String): String

    /** The header that carries the key, which differs between the two. */
    abstract fun authHeader(key: String): Pair<String, String>

    abstract fun parseModelIds(body: String): List<String>

    /** @param imageBase64 a JPEG as bare base64 — each provider wraps it its own way */
    abstract fun requestBody(model: String, imageBase64: String, hint: String): String

    abstract fun replyText(body: String): String?

    abstract fun failureMessage(status: Int, body: String): String

    abstract fun worthTryingAnotherModel(status: Int, body: String): Boolean

    companion object {

        /**
         * Whoever issued this key.
         *
         * Anything unrecognised is treated as Google's, and deliberately so: Google has
         * already changed the shape of its keys once (`AIza…` became `AQ.…` for new ones),
         * and a feature that stops working because a prefix moved would be a silly way to
         * lose it. Hugging Face's `hf_` is the only shape actually being matched; the rest
         * of the world is assumed to be Google. Guessing wrong costs one clear error
         * message, which is cheaper than a provider picker nobody wanted.
         */
        fun forKey(key: String): AiProvider {
            val trimmed = key.trim()
            return entries.firstOrNull { provider ->
                provider != GOOGLE && provider.keyPrefixes.any { trimmed.startsWith(it) }
            } ?: GOOGLE
        }
    }
}
