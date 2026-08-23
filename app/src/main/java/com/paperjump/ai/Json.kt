package com.paperjump.ai

/**
 * A very small JSON reader.
 *
 * Hand-rolled for the same reason the save formats are: `org.json` is a stub in JVM unit
 * tests, so anything built on it cannot be tested without an emulator — and parsing a
 * language model's reply is exactly the code that has to be tested, because the reply is
 * the least trustworthy input in the app.
 */
sealed interface JsonValue {
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue
    data class Arr(val items: List<JsonValue>) : JsonValue
    data class Str(val value: String) : JsonValue
    data class Num(val value: Double) : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data object Null : JsonValue
}

/** Field lookup that copes with anything not being an object. */
operator fun JsonValue?.get(key: String): JsonValue? = (this as? JsonValue.Obj)?.fields?.get(key)

fun JsonValue?.asList(): List<JsonValue> = (this as? JsonValue.Arr)?.items.orEmpty()

fun JsonValue?.asText(): String? = (this as? JsonValue.Str)?.value

fun JsonValue?.asFloatOrNull(): Float? = when (this) {
    is JsonValue.Num -> value.toFloat()
    // Models sometimes quote their numbers. Refusing those would be pedantry.
    is JsonValue.Str -> value.trim().toFloatOrNull()
    else -> null
}

object Json {

    /** Parses a whole document, or returns `null` if it is not valid JSON. */
    fun parse(text: String): JsonValue? = runCatching { Reader(text).readDocument() }.getOrNull()

    /**
     * Finds and parses the first complete JSON object inside [text].
     *
     * A chat model asked for JSON will happily wrap it in "Sure! Here you go:" and a fenced
     * code block. Rather than fight that with prompt wording, take the first balanced object
     * and ignore everything around it.
     */
    fun parseFirstObject(text: String): JsonValue? {
        val start = text.indexOf('{')
        if (start < 0) return null

        var depth = 0
        var index = start
        var inString = false
        var escaped = false
        while (index < text.length) {
            val c = text[index]
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return parse(text.substring(start, index + 1))
                }
            }
            index++
        }
        return null
    }

    /** Escapes a string for embedding in a JSON document. */
    fun escape(raw: String): String = buildString {
        raw.forEach { c ->
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
    }

    private class Reader(private val text: String) {
        private var index = 0

        fun readDocument(): JsonValue {
            val value = readValue()
            skipWhitespace()
            return value
        }

        fun readValue(): JsonValue {
            skipWhitespace()
            return when (val c = peek()) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> JsonValue.Str(readString())
                't' -> readLiteral("true", JsonValue.Bool(true))
                'f' -> readLiteral("false", JsonValue.Bool(false))
                'n' -> readLiteral("null", JsonValue.Null)
                else -> if (c == '-' || c.isDigit()) readNumber() else fail("unexpected '$c'")
            }
        }

        private fun readObject(): JsonValue {
            expect('{')
            val fields = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') { index++; return JsonValue.Obj(fields) }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                fields[key] = readValue()
                skipWhitespace()
                when (val c = next()) {
                    ',' -> Unit
                    '}' -> return JsonValue.Obj(fields)
                    else -> fail("expected , or } but found '$c'")
                }
            }
        }

        private fun readArray(): JsonValue {
            expect('[')
            val items = mutableListOf<JsonValue>()
            skipWhitespace()
            if (peek() == ']') { index++; return JsonValue.Arr(items) }
            while (true) {
                items += readValue()
                skipWhitespace()
                when (val c = next()) {
                    ',' -> Unit
                    ']' -> return JsonValue.Arr(items)
                    else -> fail("expected , or ] but found '$c'")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                when (val c = next()) {
                    '"' -> return out.toString()
                    '\\' -> when (val escape = next()) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        '/' -> out.append('/')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            val hex = text.substring(index, index + 4)
                            index += 4
                            out.append(hex.toInt(16).toChar())
                        }
                        else -> fail("bad escape '\\$escape'")
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun readNumber(): JsonValue {
            val start = index
            if (peek() == '-') index++
            while (index < text.length && (text[index].isDigit() || text[index] in ".eE+-")) index++
            val slice = text.substring(start, index)
            return JsonValue.Num(slice.toDoubleOrNull() ?: fail("bad number '$slice'"))
        }

        private fun readLiteral(word: String, value: JsonValue): JsonValue {
            if (!text.startsWith(word, index)) fail("expected $word")
            index += word.length
            return value
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        private fun peek(): Char = if (index < text.length) text[index] else fail("unexpected end")

        private fun next(): Char = peek().also { index++ }

        private fun expect(c: Char) {
            if (next() != c) fail("expected '$c'")
        }

        private fun fail(message: String): Nothing = throw IllegalArgumentException(message)
    }
}
