package io.github.triet4p.aegisf.provider

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Argument assembly decides dispatch eligibility.
 *
 * Every case here is about what the runtime is allowed to execute: a complete, schema-valid
 * object for a declared tool is [ToolCallDisposition.READY]; a partial string, a missing
 * required member, an extra member the schema forbids, an out-of-bounds value, an unknown tool
 * or a turn-level truncation never is. The provider schema lowering is pinned too, because the
 * live Gemini endpoint rejects the neutral schema's `$ref`/`additionalProperties` fields.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolArgumentValidationTest {

    @Test
    fun `a complete declared argument object is ready with parsed values`() {
        val calls = assemble("""{"value":"ALPHA-17"}""")
        assertEquals(1, calls.size)
        assertTrue(calls.single().dispatchable)
        assertEquals(ToolCallDisposition.READY, calls.single().disposition)
        assertEquals("ALPHA-17", calls.single().arguments?.get("value"))
    }

    @Test
    fun `a partial JSON object never becomes dispatchable`() {
        for (partial in listOf("""{"value":"ALPHA""", """{"value":""", "{", "not json", "[1,2]")) {
            val call = assemble(partial).single()
            assertFalse("partial arguments '$partial' must not dispatch", call.dispatchable)
            assertEquals(ToolCallDisposition.MALFORMED_ARGUMENTS, call.disposition)
            assertNull(call.arguments)
        }
    }

    @Test
    fun `a missing required member is a schema violation`() {
        val call = assemble("{}").single()
        assertEquals(ToolCallDisposition.SCHEMA_INVALID, call.disposition)
        assertTrue(call.detail.orEmpty().contains("required"))
    }

    @Test
    fun `an undeclared member is refused when the schema forbids extras`() {
        val call = assemble("""{"value":"ALPHA-17","extra":true}""").single()
        assertEquals(ToolCallDisposition.SCHEMA_INVALID, call.disposition)
        assertTrue(call.detail.orEmpty().contains("not allowed"))
    }

    @Test
    fun `a wrong primitive type is a schema violation`() {
        val call = ToolCallAssembly.assemble(
            listOf(RawToolCall("count-1", "probe_count", """{"count":"three"}""")),
            listOf(ProviderFixtures.secondTool),
        ).single()
        assertEquals(ToolCallDisposition.SCHEMA_INVALID, call.disposition)
    }

    @Test
    fun `declared bounds are enforced before dispatch`() {
        val below = ToolCallAssembly.assemble(
            listOf(RawToolCall("count-1", "probe_count", """{"count":0}""")),
            listOf(ProviderFixtures.secondTool),
        ).single()
        val above = ToolCallAssembly.assemble(
            listOf(RawToolCall("count-2", "probe_count", """{"count":501}""")),
            listOf(ProviderFixtures.secondTool),
        ).single()
        assertEquals(ToolCallDisposition.SCHEMA_INVALID, below.disposition)
        assertEquals(ToolCallDisposition.SCHEMA_INVALID, above.disposition)
    }

    @Test
    fun `a local reference is resolved and its nested constraints are enforced`() {
        val valid = ToolCallAssembly.assemble(
            listOf(RawToolCall("page-1", "probe_page", """{"bounds":{"limit":10,"offset":0}}""")),
            listOf(ProviderFixtures.referencedTool),
        ).single()
        val invalid = ToolCallAssembly.assemble(
            listOf(RawToolCall("page-2", "probe_page", """{"bounds":{"limit":501,"offset":0}}""")),
            listOf(ProviderFixtures.referencedTool),
        ).single()
        assertTrue(valid.dispatchable)
        assertEquals(ToolCallDisposition.SCHEMA_INVALID, invalid.disposition)
    }

    @Test
    fun `an unknown tool name never becomes dispatchable`() {
        val call = ToolCallAssembly.assemble(
            listOf(RawToolCall("unknown-1", "execute_sql", """{"sql":"select 1"}""")),
            listOf(ProviderFixtures.echoTool),
        ).single()
        assertEquals(ToolCallDisposition.UNKNOWN_TOOL, call.disposition)
        assertFalse(call.dispatchable)
    }

    @Test
    fun `nothing from a truncated turn is dispatchable even with valid arguments`() {
        val call = ToolCallAssembly.assemble(
            listOf(RawToolCall("call-1", "probe_echo", """{"value":"ALPHA-17"}""")),
            listOf(ProviderFixtures.echoTool),
            forcedDisposition = ToolCallDisposition.TRUNCATED_TURN,
        ).single()
        assertEquals(ToolCallDisposition.TRUNCATED_TURN, call.disposition)
        assertFalse(call.dispatchable)
    }

    @Test
    fun `a provider-truncated call is not dispatchable while its sibling is`() {
        val calls = ToolCallAssembly.assemble(
            listOf(
                RawToolCall("call-1", "probe_echo", """{"value":"ALPHA-17"}"""),
                RawToolCall(
                    "call-2",
                    "probe_echo",
                    """{"value":"BETA-18"}""",
                    forcedDisposition = ToolCallDisposition.TRUNCATED_TURN,
                ),
            ),
            listOf(ProviderFixtures.echoTool),
        )
        assertTrue(calls[0].dispatchable)
        assertFalse(calls[1].dispatchable)
    }

    @Test
    fun `the gemini schema lowerings remove unsupported keywords and upper-case types`() {
        val lowered = ProviderJson.geminiSchema(ProviderFixtures.referencedTool.parameters)
        assertEquals("OBJECT", lowered.getString("type"))
        assertFalse(lowered.has("additionalProperties"))
        assertFalse(lowered.has("${'$'}defs"))
        assertFalse(lowered.toString().contains("${'$'}ref"))
        val bounds = lowered.getJSONObject("properties").getJSONObject("bounds")
        assertEquals("OBJECT", bounds.getString("type"))
        assertFalse(bounds.has("additionalProperties"))
    }

    @Test
    fun `the openai schema lowering keeps additionalProperties and resolves references`() {
        val lowered = ProviderJson.openAiSchema(ProviderFixtures.referencedTool.parameters)
        assertEquals("object", lowered.getString("type"))
        assertFalse(lowered.has("${'$'}defs"))
        assertFalse(lowered.toString().contains("${'$'}ref"))
        val bounds = lowered.getJSONObject("properties").getJSONObject("bounds")
        assertEquals(false, bounds.getBoolean("additionalProperties"))
        assertEquals(500, bounds.getJSONObject("properties").getJSONObject("limit").getInt("maximum"))
    }

    @Test
    fun `a nullable union survives as a JSON Schema type array and null is accepted`() {
        val declaration = HostedToolDeclaration(
            name = "probe_optional",
            description = "One optional value.",
            parameters = JSONObject(
                """{"type":"object","properties":{"value":{"type":["string","null"]}},
                   "required":["value"],"additionalProperties":false}""".trimIndent(),
            ),
        )
        val lowered = ProviderJson.openAiSchema(declaration.parameters)
        val value = lowered.getJSONObject("properties").getJSONObject("value")
        val schemaTypes = value.getJSONArray("type")
        assertEquals(listOf("string", "null"), (0 until schemaTypes.length()).map(schemaTypes::getString))
        assertFalse(value.has("nullable"))
        val nullCall = ToolCallAssembly.assemble(
            listOf(RawToolCall("call-1", "probe_optional", """{"value":null}""")),
            listOf(declaration),
        ).single()
        val textCall = ToolCallAssembly.assemble(
            listOf(RawToolCall("call-2", "probe_optional", """{"value":"ok"}""")),
            listOf(declaration),
        ).single()
        assertTrue(nullCall.dispatchable)
        assertTrue(textCall.dispatchable)
    }

    @Test
    fun `an enum vocabulary is enforced`() {
        val declaration = HostedToolDeclaration(
            name = "probe_status",
            description = "One declared status.",
            parameters = JSONObject(
                """{"type":"object","properties":{"status":{"type":"string","enum":["PENDING","CONFIRMED"]}},
                   "required":["status"],"additionalProperties":false}""".trimIndent(),
            ),
        )
        val valid = ToolCallAssembly.assemble(
            listOf(RawToolCall("call-1", "probe_status", """{"status":"PENDING"}""")),
            listOf(declaration),
        ).single()
        val invalid = ToolCallAssembly.assemble(
            listOf(RawToolCall("call-2", "probe_status", """{"status":"DELETED"}""")),
            listOf(declaration),
        ).single()
        assertTrue(valid.dispatchable)
        assertEquals(ToolCallDisposition.SCHEMA_INVALID, invalid.disposition)
    }

    @Test
    fun `a declared string pattern is enforced`() {
        val declaration = HostedToolDeclaration(
            name = "probe_time",
            description = "One anchored time.",
            parameters = JSONObject(
                """{"type":"object","properties":{"time":{"type":"string","pattern":"^[0-9]{2}:[0-9]{2}${'$'}"}},
                   "required":["time"],"additionalProperties":false}""".trimIndent(),
            ),
        )
        val valid = ToolCallAssembly.assemble(
            listOf(RawToolCall("call-1", "probe_time", """{"time":"09:30"}""")),
            listOf(declaration),
        ).single()
        val invalid = ToolCallAssembly.assemble(
            listOf(RawToolCall("call-2", "probe_time", """{"time":"9:30"}""")),
            listOf(declaration),
        ).single()
        assertTrue(valid.dispatchable)
        assertEquals(ToolCallDisposition.SCHEMA_INVALID, invalid.disposition)
    }

    @Test
    fun `duplicate call ids are reported so the turn cannot be answered ambiguously`() {
        val duplicates = ToolCallAssembly.duplicateCallIds(
            listOf(
                RawToolCall("same", "probe_echo", """{"value":"A"}"""),
                RawToolCall("same", "probe_echo", """{"value":"B"}"""),
            ),
        )
        assertEquals(listOf("same"), duplicates)
    }

    @Test
    fun `a tool name that is not portable across providers is refused`() {
        try {
            HostedToolDeclaration(
                name = "not portable",
                description = "Bad name.",
                parameters = JSONObject("""{"type":"object"}"""),
            )
            fail("expected a HostedConfigurationException")
        } catch (expected: HostedConfigurationException) {
            assertTrue(expected.message.orEmpty().isNotBlank())
        }
    }

    @Test
    fun `every pending call needs exactly one answer`() {
        val pending = listOf("a", "b")
        assertEquals(
            setOf("a", "b"),
            ToolCallAssembly.answerEveryPendingCall(
                pending,
                listOf(HostedToolResult("a", "{}"), HostedToolResult("b", "{}")),
            ).keys,
        )
        for (answers in listOf(
            listOf(HostedToolResult("a", "{}")),
            listOf(HostedToolResult("a", "{}"), HostedToolResult("a", "{}")),
            listOf(HostedToolResult("a", "{}"), HostedToolResult("c", "{}")),
        )) {
            try {
                ToolCallAssembly.answerEveryPendingCall(pending, answers)
                fail("expected an IllegalArgumentException for ${answers.map { it.callId }}")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().isNotBlank())
            }
        }
    }

    @Test
    fun `the strict parser rejects trailing bytes after a JSON object`() {
        assertNull(ProviderJson.strictObject("""{"value":"A"} {"value":"B"}"""))
        assertEquals("A", ProviderJson.strictObject("""{"value":"A"}""")?.getString("value"))
    }

    private fun assemble(arguments: String): List<HostedToolCall> = ToolCallAssembly.assemble(
        listOf(RawToolCall("call-1", "probe_echo", arguments)),
        listOf(ProviderFixtures.echoTool),
    )
}
