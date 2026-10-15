package io.github.phongmanh.restaurant.core.application

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** RFC 8785 for the values the audit log stores. */
class CanonicalJsonTest {
    private fun canonical(json: String) = CanonicalJson.encode(Json.parseToJsonElement(json))

    @Test
    fun `whitespace and key order don't matter`() {
        assertEquals(
            """{"a":[1,true,null,"x"],"b":{"c":2,"d":"e"}}""",
            canonical(""" { "b" : { "d" : "e", "c" : 2 }, "a" : [ 1, true, null, "x" ] } """),
        )
    }

    @Test
    fun `keys sort by UTF-16 code units`() {
        // RFC 8785 §3.2.3's example: "\r", "1", "\u0080", "ö", "€", "😀", "דּ".
        val input = """{"€":1,"\r":2,"דּ":3,"😀":4,"1":5,"ö":6,"\u0080":7}"""
        assertEquals("""{"\r":2,"1":5,"""" + "\u0080" + """":7,"ö":6,"€":1,"😀":4,"דּ":3}""", canonical(input))
    }

    @Test
    fun `strings use the shortest escapes and keep other characters as they are`() {
        assertEquals(
            "\"\\\"\\\\\\b\\f\\n\\r\\t\\u0001\\u001f é/\"",
            CanonicalJson.encode(JsonPrimitive("\"\\\b\u000C\n\r\t\u0001\u001F é/")),
        )
    }

    @Test
    fun `an integer reads the same however jsonb wrote it`() {
        assertEquals("12", canonical("12"))
        assertEquals("12", canonical("12.0"))
        assertEquals("-9007199254740991", canonical("-9007199254740991"))
    }

    @Test
    fun `fractions and integers beyond 2^53 are refused`() {
        assertFailsWith<IllegalArgumentException> { canonical("1.5") }
        assertFailsWith<IllegalArgumentException> { canonical("9007199254740992") }
        assertFalse(CanonicalJson.isSupported(Json.parseToJsonElement("""{"a":[0.1]}""")))
        assertTrue(CanonicalJson.isSupported(Json.parseToJsonElement("""{"a":[1]}""")))
    }
}
