package com.paperjump

import com.paperjump.ai.AiProvider
import com.paperjump.ai.GoogleProtocol
import com.paperjump.ai.Json
import com.paperjump.ai.asList
import com.paperjump.ai.asText
import com.paperjump.ai.get
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Google AI Studio side of the level designer, and the routing that picks it.
 *
 * Worth its own file because the two services agree on almost nothing: the model is in the
 * URL rather than the body, the picture is bare base64 rather than a `data:` URL, the key
 * rides in its own header, and the error is one object deeper. Every one of those is a way
 * to send a request that comes back 400, and none of them can be caught by reading the code.
 */
class GoogleProtocolTest {

    // ---- the request -------------------------------------------------------------------

    @Test
    fun `the request is valid json with the picture and the note in it`() {
        val body = GoogleProtocol.generateRequest(imageBase64 = "AAAA", hint = "a \"dragon\"")
        val parsed = Json.parse(body)
        assertNotNull("the request body must be valid JSON", parsed)

        val parts = parsed["contents"].asList().single()["parts"].asList()
        val text = parts[0]["text"].asText()
        assertTrue(text!!.contains("platformer"))
        assertTrue("the note has to reach the model", text.contains("dragon"))

        val image = parts[1]["inline_data"]
        assertEquals("image/jpeg", image["mime_type"].asText())
        assertEquals("AAAA", image["data"].asText())
    }

    @Test
    fun `the picture goes in bare, without a data url around it`() {
        val body = GoogleProtocol.generateRequest("AAAA", "")
        assertFalse(
            "a data: prefix here is a 400 from Google",
            body.contains("data:image"),
        )
    }

    @Test
    fun `an empty note does not add an empty sentence`() {
        val body = GoogleProtocol.generateRequest("AAAA", hint = "   ")
        val text = Json.parse(body)["contents"].asList().single()["parts"].asList()[0]["text"].asText()
        assertTrue(text!!.isNotBlank())
        assertTrue("no dangling quote when there is nothing to say", !text.contains("says: \"\""))
    }

    @Test
    fun `the model goes in the url`() {
        assertTrue(
            GoogleProtocol.endpointFor("gemini-2.5-flash")
                .endsWith("/models/gemini-2.5-flash:generateContent"),
        )
        // The listing calls them "models/gemini-2.5-flash"; pasting that back must not
        // produce ".../models/models/gemini-2.5-flash".
        assertEquals(
            GoogleProtocol.endpointFor("gemini-2.5-flash"),
            GoogleProtocol.endpointFor("  models/gemini-2.5-flash  "),
        )
    }

    // ---- the reply ---------------------------------------------------------------------

    @Test
    fun `pulls the answer out of a generateContent reply`() {
        val body = """
            {"candidates":[{"content":{"role":"model","parts":[{"text":"the answer"}]},
             "finishReason":"STOP"}]}
        """.trimIndent()
        assertEquals("the answer", GoogleProtocol.replyText(body))
    }

    @Test
    fun `an answer split over several parts is joined`() {
        val body = """{"candidates":[{"content":{"parts":[{"text":"one"},{"text":"two"}]}}]}"""
        assertEquals("one\ntwo", GoogleProtocol.replyText(body))
    }

    @Test
    fun `a reply with no answer in it is null`() {
        assertNull(GoogleProtocol.replyText("""{"candidates":[]}"""))
        assertNull(GoogleProtocol.replyText("""{"promptFeedback":{"blockReason":"SAFETY"}}"""))
        assertNull(GoogleProtocol.replyText("gateway timeout"))
    }

    // ---- failures ----------------------------------------------------------------------

    @Test
    fun `failures say what to do about them`() {
        val badKey = GoogleProtocol.failureMessage(
            403,
            """{"error":{"code":403,"message":"API key not valid","status":"PERMISSION_DENIED"}}""",
        )
        assertTrue("it has to say where a new key comes from", badKey.contains("aistudio.google.com"))
        assertTrue("Google's own words are worth keeping", badKey.contains("API key not valid"))

        assertTrue(GoogleProtocol.failureMessage(429, "").contains("limit"))
        assertTrue(GoogleProtocol.failureMessage(503, "").contains("503"))
        assertTrue(GoogleProtocol.failureMessage(418, "").contains("418"))
    }

    @Test
    fun `an error body that is not json is still shown`() {
        assertTrue(GoogleProtocol.failureMessage(500, "upstream exploded").contains("upstream exploded"))
    }

    @Test
    fun `a model that is not there is worth trying another`() {
        val body = """
            {"error":{"code":404,"message":"models/gemini-9-ultra is not found for API version
             v1beta, or is not supported for generateContent."}}
        """.trimIndent()
        assertTrue(GoogleProtocol.worthTryingAnotherModel(404, body))
        assertTrue(
            GoogleProtocol.worthTryingAnotherModel(
                400,
                """{"error":{"message":"model is not supported"}}""",
            ),
        )
    }

    @Test
    fun `a problem the player has to fix is not worth another model`() {
        assertFalse("a bad key stays bad", GoogleProtocol.worthTryingAnotherModel(403, ""))
        assertFalse("so does a spent quota", GoogleProtocol.worthTryingAnotherModel(429, ""))
        assertFalse(
            "a 400 about something else is not a model problem",
            GoogleProtocol.worthTryingAnotherModel(400, """{"error":{"message":"image too large"}}"""),
        )
    }

    // ---- the model listing --------------------------------------------------------------

    @Test
    fun `reads the model ids out of a listing and skips what cannot answer`() {
        val body = """
            {"models":[
              {"name":"models/gemini-2.5-flash","supportedGenerationMethods":["generateContent"]},
              {"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]},
              {"name":"models/gemini-2.5-pro","supportedGenerationMethods":["countTokens","generateContent"]},
              {"name":"models/gemini-2.5-flash","supportedGenerationMethods":["generateContent"]},
              {"supportedGenerationMethods":["generateContent"]}
            ]}
        """.trimIndent()

        assertEquals(
            listOf("gemini-2.5-flash", "gemini-2.5-pro"),
            GoogleProtocol.parseModelIds(body),
        )
    }

    @Test
    fun `a listing that is not a listing yields nothing rather than throwing`() {
        assertTrue(GoogleProtocol.parseModelIds("").isEmpty())
        assertTrue(GoogleProtocol.parseModelIds("<html>404</html>").isEmpty())
        assertTrue(GoogleProtocol.parseModelIds("""{"error":{"message":"nope"}}""").isEmpty())
    }

    @Test
    fun `the model list starts with the default and has no duplicates`() {
        assertEquals(GoogleProtocol.DEFAULT_MODEL, GoogleProtocol.MODEL_CANDIDATES.first())
        assertEquals(
            GoogleProtocol.MODEL_CANDIDATES.size,
            GoogleProtocol.MODEL_CANDIDATES.toSet().size,
        )
        assertTrue("a fallback is only useful if there is one", GoogleProtocol.MODEL_CANDIDATES.size > 1)
    }

    // ---- picking a service off the key ---------------------------------------------------

    @Test
    fun `the key says who to talk to`() {
        assertEquals(AiProvider.GOOGLE, AiProvider.forKey("AIzaSyExampleExampleExample"))
        assertEquals(AiProvider.HUGGING_FACE, AiProvider.forKey("hf_exampleexampleexample"))
        assertEquals("whitespace is a paste, not a service", AiProvider.GOOGLE, AiProvider.forKey("  AIzaX "))
        assertEquals("anything unrecognised gets the free one", AiProvider.GOOGLE, AiProvider.forKey("what"))
    }

    @Test
    fun `google's newer key shape is still google`() {
        // Real shape, from a key AI Studio issued in 2026: the old AIza… prefix is not the
        // only one any more, and a key that works must not be turned away by a guess about
        // how it starts.
        assertEquals(AiProvider.GOOGLE, AiProvider.forKey("AQ.Ab8ExampleExampleExample"))
        assertEquals(AiProvider.GOOGLE, AiProvider.forKey("some-shape-nobody-has-seen-yet"))
        assertTrue(
            "the shapes we do know are worth telling people about",
            GoogleProtocol.KEY_PREFIXES.containsAll(listOf("AIza", "AQ.")),
        )
    }

    @Test
    fun `each service asks in its own shape`() {
        val google = AiProvider.GOOGLE.requestBody("gemini-2.5-flash", "AAAA", "")
        assertNotNull(Json.parse(google))
        assertNull("Google's body names no model", Json.parse(google)["model"].asText())

        val hugging = AiProvider.HUGGING_FACE.requestBody("some/model", "AAAA", "")
        assertEquals("some/model", Json.parse(hugging)["model"].asText())
        assertTrue(
            "Hugging Face wants the data url that Google refuses",
            hugging.contains("data:image/jpeg;base64,AAAA"),
        )
    }

    @Test
    fun `the key rides in the header each service expects`() {
        assertEquals("x-goog-api-key" to "AIzaX", AiProvider.GOOGLE.authHeader(" AIzaX "))
        assertEquals("Authorization" to "Bearer hf_x", AiProvider.HUGGING_FACE.authHeader("hf_x "))
    }

    @Test
    fun `a model name left over from the other service is recognised`() {
        assertEquals(AiProvider.HUGGING_FACE, AiProvider.forModel("Qwen/Qwen3-VL-8B-Instruct"))
        assertEquals(AiProvider.GOOGLE, AiProvider.forModel("gemini-2.5-flash"))
    }
}
