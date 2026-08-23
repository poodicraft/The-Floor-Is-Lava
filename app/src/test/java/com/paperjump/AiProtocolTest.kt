package com.paperjump

import com.paperjump.ai.AiProtocol
import com.paperjump.ai.Json
import com.paperjump.ai.JsonValue
import com.paperjump.ai.LevelPlan
import com.paperjump.ai.asList
import com.paperjump.ai.asText
import com.paperjump.ai.get
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Everything the AI level designer does with text.
 *
 * A model's reply is the least trustworthy input in the app — it is generated, not
 * specified — so the parsing is where this feature either holds up or falls over, and it is
 * all pure Kotlin precisely so it can be tested without a key, a network or an emulator.
 */
class AiProtocolTest {

    // ---- the JSON reader --------------------------------------------------------------

    @Test
    fun `reads the shapes a reply is made of`() {
        val value = Json.parse("""{"a":1,"b":[true,null,"x"],"c":{"d":-2.5e2}}""")

        assertEquals(1.0, (value["a"] as JsonValue.Num).value, 0.001)
        assertEquals(3, value["b"].asList().size)
        assertEquals("x", value["b"].asList()[2].asText())
        assertEquals(-250.0, (value["c"]["d"] as JsonValue.Num).value, 0.001)
    }

    @Test
    fun `broken json is null rather than an exception`() {
        assertNull(Json.parse("{\"a\":"))
        assertNull(Json.parse("not json at all"))
        assertNull(Json.parse(""))
    }

    @Test
    fun `finds the object inside a chatty reply`() {
        val reply = """
            Sure! Here is your level:

            ```json
            {"title":"Castle", "platforms":[{"x1":0,"y1":0.9,"x2":1,"y2":0.9}]}
            ```

            Hope that helps!
        """.trimIndent()

        val value = Json.parseFirstObject(reply)
        assertEquals("Castle", value["title"].asText())
    }

    @Test
    fun `a brace inside a string does not end the object`() {
        val value = Json.parseFirstObject("""{"title":"a } shape","platforms":[]}""")
        assertEquals("a } shape", value["title"].asText())
    }

    @Test
    fun `escapes what it puts in a request`() {
        val escaped = Json.escape("say \"hi\"\nand \\ that")
        val roundTripped = Json.parse("\"$escaped\"").asText()
        assertEquals("say \"hi\"\nand \\ that", roundTripped)
    }

    // ---- the plan ---------------------------------------------------------------------

    private val goodReply = """
        {"title":"Dragon's keep",
         "platforms":[{"x1":0.0,"y1":0.9,"x2":1.0,"y2":0.9,"thickness":0.014},
                      {"x1":0.3,"y1":0.6,"x2":0.5,"y2":0.6}],
         "lava":[{"x1":0.4,"y1":0.95,"x2":0.6,"y2":0.95}],
         "coins":[{"x":0.4,"y":0.5}],
         "enemies":[{"x":0.7,"y":0.85}],
         "start":{"x":0.05,"y":0.85},
         "goal":{"x":0.95,"y":0.55}}
    """.trimIndent()

    @Test
    fun `reads a well formed plan`() {
        val plan = LevelPlan.parse(goodReply)
        assertNotNull(plan)
        requireNotNull(plan)

        assertEquals("Dragon's keep", plan.title)
        assertEquals(2, plan.platforms.size)
        assertEquals(1, plan.lava.size)
        assertEquals(1, plan.coins.size)
        assertEquals(1, plan.enemies.size)
        assertEquals(0.05f, plan.start!!.x, 0.001f)
        assertEquals(0.55f, plan.goal!!.y, 0.001f)
        assertTrue(plan.isPlayable)
    }

    @Test
    fun `a plan with nothing to stand on is not a plan`() {
        assertNull(LevelPlan.parse("""{"title":"empty","platforms":[]}"""))
        assertNull(LevelPlan.parse("I'm sorry, I can't help with that."))
    }

    @Test
    fun `coordinates off the page are pulled back onto it`() {
        val plan = LevelPlan.parse(
            """{"platforms":[{"x1":-3,"y1":0.5,"x2":9.5,"y2":1.7,"thickness":80}]}""",
        )
        requireNotNull(plan)
        val line = plan.platforms.single()

        assertEquals(0f, line.x1, 0.001f)
        assertEquals(1f, line.x2, 0.001f)
        assertEquals(1f, line.y2, 0.001f)
        assertTrue("an 80-wide platform would be the whole page", line.thickness <= 0.06f)
    }

    @Test
    fun `numbers sent as strings are still numbers`() {
        val plan = LevelPlan.parse(
            """{"platforms":[{"x1":"0.1","y1":"0.9","x2":"0.9","y2":"0.9"}]}""",
        )
        assertEquals(1, plan?.platforms?.size)
    }

    @Test
    fun `a half written entry is dropped, not the whole level`() {
        val plan = LevelPlan.parse(
            """{"platforms":[{"x1":0.1,"y1":0.9,"x2":0.9,"y2":0.9},{"x1":0.2,"y1":0.4}]}""",
        )
        assertEquals("the entry with no end point should be dropped", 1, plan?.platforms?.size)
    }

    @Test
    fun `a runaway reply cannot build a huge level`() {
        val many = (1..500).joinToString(",") { """{"x1":0,"y1":0.5,"x2":1,"y2":0.5}""" }
        val plan = LevelPlan.parse("""{"platforms":[$many]}""")
        assertTrue("expected a cap", (plan?.platforms?.size ?: 0) in 1..100)
    }

    @Test
    fun `a plan wrapped in another object is still found`() {
        val plan = LevelPlan.parse(
            """{"level":{"title":"Nested","platforms":[{"x1":0,"y1":0.9,"x2":1,"y2":0.9}]}}""",
        )
        assertEquals("Nested", plan?.title)
    }

    // ---- the request and the reply envelope -------------------------------------------

    @Test
    fun `the request is valid json with the picture and the note in it`() {
        val body = AiProtocol.chatRequest(
            model = "some/model",
            imageDataUrl = "data:image/jpeg;base64,AAAA",
            hint = "a \"dragon\"",
        )
        val parsed = Json.parse(body)
        assertNotNull("the request body must be valid JSON", parsed)

        assertEquals("some/model", parsed["model"].asText())
        val content = parsed["messages"].asList().single()["content"].asList()
        assertTrue(content[0]["text"].asText()!!.contains("platformer"))
        assertTrue("the note has to reach the model", content[0]["text"].asText()!!.contains("dragon"))
        assertEquals("data:image/jpeg;base64,AAAA", content[1]["image_url"]["url"].asText())
    }

    @Test
    fun `an empty note does not add an empty sentence`() {
        val body = AiProtocol.chatRequest("m", "data:,", hint = "   ")
        val text = Json.parse(body)["messages"].asList().single()["content"].asList()[0]["text"].asText()
        assertTrue(text!!.isNotBlank())
        assertTrue("no dangling quote when there is nothing to say", !text.contains("says: \"\""))
    }

    @Test
    fun `pulls the answer out of a chat completion`() {
        val body = """{"choices":[{"message":{"role":"assistant","content":"the answer"}}]}"""
        assertEquals("the answer", AiProtocol.replyText(body))
    }

    @Test
    fun `also copes with content sent as parts`() {
        val body = """
            {"choices":[{"message":{"content":[{"type":"text","text":"one"},
                                               {"type":"text","text":"two"}]}}]}
        """.trimIndent()
        assertEquals("one\ntwo", AiProtocol.replyText(body))
    }

    @Test
    fun `a reply with no answer in it is null`() {
        assertNull(AiProtocol.replyText("""{"choices":[]}"""))
        assertNull(AiProtocol.replyText("gateway timeout"))
    }

    @Test
    fun `failures say what to do about them`() {
        val badKey = AiProtocol.failureMessage(401, """{"error":"Invalid credentials"}""")
        assertTrue(badKey.contains("Settings"))
        assertTrue("the provider's own words are worth keeping", badKey.contains("Invalid credentials"))

        assertTrue(AiProtocol.failureMessage(402, "").contains("credits"))
        assertTrue(AiProtocol.failureMessage(503, "").contains("warming up"))
        assertTrue(AiProtocol.failureMessage(404, "").contains(AiProtocol.DEFAULT_MODEL))
        assertTrue(AiProtocol.failureMessage(418, "").contains("418"))
    }

    @Test
    fun `an error body that is not json is still shown`() {
        val message = AiProtocol.failureMessage(500, "upstream exploded")
        assertTrue(message.contains("upstream exploded"))
    }
}
