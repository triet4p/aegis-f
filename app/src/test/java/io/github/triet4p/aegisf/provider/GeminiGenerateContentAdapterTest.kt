package io.github.triet4p.aegisf.provider

import kotlinx.coroutines.runBlocking
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
 * The Gemini `generateContent` adapter's observable contract.
 *
 * The wire fixtures mirror the shapes the live endpoint returned: a function-call turn carries
 * `functionCall` parts with `thoughtSignature`, a result is a `functionResponse` part in a
 * **user** turn, and `STOP` accompanies a call-only turn. Failures are classified from the
 * endpoint's own status, and a tool call with unusable arguments is never executable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeminiGenerateContentAdapterTest {

    @Test
    fun `a call-only STOP turn is a tool-call turn with a ready call`() = runBlocking {
        val transport = FakeTransport(
            ProviderFixtures.reply(
                """
                {"modelVersion":"gemini-3.5-flash-lite","candidates":[{"content":{"role":"model","parts":[
                  {"functionCall":{"name":"probe_echo","args":{"value":"ALPHA-17"},"id":"call_1"},"thoughtSignature":"sig"}
                ]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":5,"thoughtsTokenCount":2}}
                """.trimIndent(),
            ),
        )
        val adapter = HostedAdapters.create(ProviderFixtures.geminiConfig(), transport)
        val turn = ProviderFixtures.turn(
            adapter.generate(
                adapter.begin("system instructions", "call probe_echo with ALPHA-17"),
                listOf(ProviderFixtures.echoTool),
            ),
        )

        assertEquals(HostedTurnFinish.ToolCalls, turn.finish)
        assertEquals("gemini-3.5-flash-lite", turn.reportedModel)
        assertNull(turn.assistantText)
        assertEquals(listOf(ToolCallDisposition.READY), turn.toolCalls.map { it.disposition })
        assertEquals("ALPHA-17", turn.toolCalls.single().arguments?.get("value"))
        assertEquals(listOf("call_1"), turn.conversation?.pendingCallIds)
        assertEquals(10, turn.usage?.inputTokens)
        assertEquals(2, turn.usage?.reasoningTokens)

        val request = transport.requestJson()
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent",
            transport.requests.single().url,
        )
        assertEquals(ProviderFixtures.TOKEN, transport.requests.single().headers["x-goog-api-key"])
        assertEquals(8_192, request.getJSONObject("generationConfig").getInt("maxOutputTokens"))
        assertEquals(
            "minimal",
            request.getJSONObject("generationConfig").getJSONObject("thinkingConfig").getString("thinkingLevel"),
        )
        val declaration = request.getJSONArray("tools").getJSONObject(0)
            .getJSONArray("functionDeclarations").getJSONObject(0)
        assertEquals("OBJECT", declaration.getJSONObject("parameters").getString("type"))
        assertFalse(declaration.getJSONObject("parameters").has("additionalProperties"))
    }

    @Test
    fun `full token guidance survives Gemini schema lowering on the wire`() = runBlocking {
        val config = ProviderFixtures.geminiConfig()
        val transport = FakeTransport(
            ProviderFixtures.reply(
                """{"modelVersion":"${config.model}","candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]},"finishReason":"STOP"}]}""",
            ),
        )
        val adapter = HostedAdapters.create(config, transport)
        ProviderFixtures.turn(
            adapter.generate(
                adapter.begin("system instructions", "inspect the declared tool"),
                listOf(runtimeRecordEventTool()),
            ),
        )

        val declaration = transport.requestJson()
            .getJSONArray("tools").getJSONObject(0)
            .getJSONArray("functionDeclarations").getJSONObject(0)
        val parameters = declaration.getJSONObject("parameters")
        val fields = parameters.getJSONObject("properties")
        assertEquals("OBJECT", parameters.getString("type"))
        assertEquals(6, parameters.getJSONArray("required").length())
        for (name in listOf("source_ref", "summary", "amount_ref", "anchor_ref", "merchant_ref", "clarification")) {
            val requiredNames = (0 until parameters.getJSONArray("required").length())
                .map { parameters.getJSONArray("required").getString(it) }
            assertTrue("missing ${name}", requiredNames.contains(name))
            assertTrue("missing schema for ${name}", fields.has(name))
        }
        assertEquals("STRING", fields.getJSONObject("amount_ref").getString("type"))
        assertTrue(fields.getJSONObject("amount_ref").getBoolean("nullable"))
        assertTrue(fields.getJSONObject("amount_ref").getString("description").contains("AMOUNT alias"))
        assertEquals("STRING", fields.getJSONObject("source_ref").getString("type"))
        assertFalse(fields.getJSONObject("source_ref").optBoolean("nullable", false))
        assertTrue(fields.getJSONObject("source_ref").getString("description").contains("expected existing reading or expected absence"))
        val clarification = fields.getJSONObject("clarification")
        val enumArray = clarification.getJSONArray("enum")
        val enum = (0 until enumArray.length()).map { enumArray.getString(it) }
        assertEquals(listOf("none", "amount", "date", "time", "merchant"), enum)
        assertFalse(fields.getJSONObject("amount_ref").has("pattern"))
        assertFalse(parameters.has("additionalProperties"))
    }

    @Test
    fun `a function response continues in the accepted user turn and echoes the call id`() = runBlocking {
        val transport = FakeTransport(
            ProviderFixtures.reply(functionCallReply()),
            ProviderFixtures.reply(
                """
                {"modelVersion":"gemini-3.5-flash-lite","candidates":[{"content":{"role":"model","parts":[
                  {"text":"ALPHA-17 DONE"}
                ]},"finishReason":"STOP"}]}
                """.trimIndent(),
            ),
        )
        val adapter = HostedAdapters.create(ProviderFixtures.geminiConfig(), transport)
        val first = adapter.begin("system instructions", "call probe_echo with ALPHA-17")
        val callTurn = ProviderFixtures.turn(adapter.generate(first, listOf(ProviderFixtures.echoTool)))
        val pending = callTurn.conversation ?: error("a tool-call turn must be continuable")

        val next = adapter.continueWithToolResults(
            pending,
            listOf(HostedToolResult("call_1", """{"echoed":"ALPHA-17"}""")),
        )
        val finalTurn = ProviderFixtures.turn(adapter.generate(next, listOf(ProviderFixtures.echoTool)))

        assertEquals(HostedTurnFinish.Complete, finalTurn.finish)
        assertEquals("ALPHA-17 DONE", finalTurn.assistantText)
        assertNull(finalTurn.conversation)

        val continuation = transport.requestJson(1)
        val contents = continuation.getJSONArray("contents")
        assertEquals(3, contents.length())
        assertEquals("user", contents.getJSONObject(0).getString("role"))
        assertEquals("model", contents.getJSONObject(1).getString("role"))
        val retainedPart = contents.getJSONObject(1).getJSONArray("parts").getJSONObject(0)
        assertEquals(
            "sig",
            retainedPart.getString("thoughtSignature"),
        )
        assertFalse("thought prose must not ride the continuation", retainedPart.has("text"))
        assertEquals("user", contents.getJSONObject(2).getString("role"))
        val functionResponse = contents.getJSONObject(2).getJSONArray("parts").getJSONObject(0)
            .getJSONObject("functionResponse")
        assertEquals("probe_echo", functionResponse.getString("name"))
        assertEquals("call_1", functionResponse.getString("id"))
        assertEquals("ALPHA-17", functionResponse.getJSONObject("response").getString("echoed"))
    }

    @Test
    fun `a truncated turn is incomplete and nothing in it is dispatchable`() = runBlocking {
        val transport = FakeTransport(
            ProviderFixtures.reply(
                """
                {"modelVersion":"gemini-3.5-flash-lite","candidates":[{"content":{"role":"model","parts":[
                  {"functionCall":{"name":"probe_echo","args":{"value":"ALPHA-17"},"id":"call_1"}}
                ]},"finishReason":"MAX_TOKENS"}]}
                """.trimIndent(),
            ),
        )
        val adapter = HostedAdapters.create(ProviderFixtures.geminiConfig(), transport)
        val turn = ProviderFixtures.turn(
            adapter.generate(adapter.begin("system", "prompt"), listOf(ProviderFixtures.echoTool)),
        )

        assertEquals(HostedTurnFinish.Incomplete("max_tokens"), turn.finish)
        assertTrue(turn.toolCalls.none { it.dispatchable })
        assertEquals(ToolCallDisposition.TRUNCATED_TURN, turn.toolCalls.single().disposition)
        assertNull(turn.conversation)
    }

    @Test
    fun `a safety finish is a provider refusal with no executable content`() = runBlocking {
        val transport = FakeTransport(
            ProviderFixtures.reply(
                """
                {"modelVersion":"gemini-3.5-flash-lite","candidates":[{"finishReason":"SAFETY"}]}
                """.trimIndent(),
            ),
        )
        val adapter = HostedAdapters.create(ProviderFixtures.geminiConfig(), transport)
        val turn = ProviderFixtures.turn(
            adapter.generate(adapter.begin("system", "prompt"), listOf(ProviderFixtures.echoTool)),
        )

        assertEquals(HostedTurnFinish.ContentFiltered("safety"), turn.finish)
        assertNull(turn.assistantText)
        assertTrue(turn.toolCalls.isEmpty())
        assertNull(turn.conversation)
    }

    @Test
    fun `a prompt-level block is a provider refusal`() = runBlocking {
        val transport = FakeTransport(
            ProviderFixtures.reply(
                """{"promptFeedback":{"blockReason":"PROHIBITED_CONTENT"},"modelVersion":"gemini-3.5-flash-lite"}""",
            ),
        )
        val adapter = HostedAdapters.create(ProviderFixtures.geminiConfig(), transport)
        val turn = ProviderFixtures.turn(
            adapter.generate(adapter.begin("system", "prompt"), listOf(ProviderFixtures.echoTool)),
        )

        assertEquals(HostedTurnFinish.ContentFiltered("prohibited_content"), turn.finish)
        assertNull(turn.conversation)
    }

    @Test
    fun `a call without arguments is malformed and cannot be dispatched`() = runBlocking {
        val transport = FakeTransport(
            ProviderFixtures.reply(
                """
                {"modelVersion":"gemini-3.5-flash-lite","candidates":[{"content":{"role":"model","parts":[
                  {"functionCall":{"name":"probe_echo","id":"call_1"}}
                ]},"finishReason":"STOP"}]}
                """.trimIndent(),
            ),
        )
        val adapter = HostedAdapters.create(ProviderFixtures.geminiConfig(), transport)
        val turn = ProviderFixtures.turn(
            adapter.generate(adapter.begin("system", "prompt"), listOf(ProviderFixtures.echoTool)),
        )

        assertEquals(ToolCallDisposition.MALFORMED_ARGUMENTS, turn.toolCalls.single().disposition)
        assertFalse(turn.toolCalls.single().dispatchable)
    }

    @Test
    fun `a STOP with no text and no call is incomplete rather than a complete answer`() = runBlocking {
        val transport = FakeTransport(
            ProviderFixtures.reply(
                """{"modelVersion":"gemini-3.5-flash-lite","candidates":[{"finishReason":"STOP"}]}""",
            ),
        )
        val adapter = HostedAdapters.create(ProviderFixtures.geminiConfig(), transport)
        val turn = ProviderFixtures.turn(
            adapter.generate(adapter.begin("system", "prompt"), listOf(ProviderFixtures.echoTool)),
        )

        assertEquals(HostedTurnFinish.Incomplete("empty_answer"), turn.finish)
        assertNull(turn.conversation)
    }

    @Test
    fun `a different echoed model is a substitution failure`() = runBlocking {
        val transport = FakeTransport(
            ProviderFixtures.reply(
                """{"modelVersion":"gemini-2.5-flash","candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"ok"}]}}]}""",
            ),
        )
        val adapter = HostedAdapters.create(ProviderFixtures.geminiConfig(), transport)
        val failure = ProviderFixtures.failure(
            adapter.generate(adapter.begin("system", "prompt"), listOf(ProviderFixtures.echoTool)),
        )

        assertEquals(
            HostedProviderFailure.ModelMismatch("gemini-3.5-flash-lite", "gemini-2.5-flash"),
            failure,
        )
    }

    @Test
    fun `a response without a model echo is invalid rather than silently trusted`() = runBlocking {
        val transport = FakeTransport(
            ProviderFixtures.reply(
                """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"ok"}]}}]}""",
            ),
        )
        val adapter = HostedAdapters.create(ProviderFixtures.geminiConfig(), transport)
        val failure = ProviderFixtures.failure(
            adapter.generate(adapter.begin("system", "prompt"), listOf(ProviderFixtures.echoTool)),
        )

        assertTrue(failure is HostedProviderFailure.InvalidResponse)
    }

    @Test
    fun `transport and endpoint statuses are classified distinctly`() = runBlocking {
        assertEquals(
            HostedProviderFailure.Authentication::class.java,
            failureFor(401, """{"error":{"message":"bad key"}}""").javaClass,
        )
        assertEquals(
            HostedProviderFailure.Account::class.java,
            failureFor(402, """{"error":{"message":"billing"}}""").javaClass,
        )
        val limited = failureFor(
            429,
            """{"error":{"message":"quota"}}""",
            headers = mapOf("retry-after" to "17"),
        ) as HostedProviderFailure.RateLimited
        assertEquals(17L, limited.retryAfterSeconds)
        assertEquals(
            HostedProviderFailure.Unavailable::class.java,
            failureFor(503, """{"error":{"message":"overloaded"}}""").javaClass,
        )
        assertEquals(
            HostedProviderFailure.Rejected::class.java,
            failureFor(400, """{"error":{"message":"bad request"}}""").javaClass,
        )
        assertEquals(
            HostedProviderFailure.Redirected::class.java,
            failureFor(302, "moved").javaClass,
        )
        assertEquals(
            HostedProviderFailure.InvalidResponse::class.java,
            failureFor(200, "<html>not json</html>").javaClass,
        )
    }

    @Test
    fun `a failure detail never contains the configured credential`() = runBlocking {
        val transport = FakeTransport(
            ProviderFixtures.reply(
                """{"error":{"message":"rejected ${ProviderFixtures.TOKEN} for this request"}}""",
                status = 401,
            ),
        )
        val adapter = HostedAdapters.create(ProviderFixtures.geminiConfig(), transport)
        val failure = ProviderFixtures.failure(
            adapter.generate(adapter.begin("system", "prompt")),
        ) as HostedProviderFailure.Authentication
        assertFalse(failure.detail.orEmpty().contains(ProviderFixtures.TOKEN))
        assertTrue(failure.detail.orEmpty().contains("[redacted-credential]"))
    }

    @Test
    fun `a tool result must be a JSON object for Gemini`() = runBlocking {
        val transport = FakeTransport(ProviderFixtures.reply(functionCallReply()))
        val adapter = HostedAdapters.create(ProviderFixtures.geminiConfig(), transport)
        val callTurn = ProviderFixtures.turn(
            adapter.generate(adapter.begin("system", "prompt"), listOf(ProviderFixtures.echoTool)),
        )
        try {
            adapter.continueWithToolResults(
                callTurn.conversation ?: error("continuable turn expected"),
                listOf(HostedToolResult("call_1", "plain text")),
            )
            fail("expected a HostedConfigurationException")
        } catch (expected: HostedConfigurationException) {
            assertTrue(expected.message.orEmpty().isNotBlank())
        }
    }

    @Test
    fun `a continuation must answer every pending call exactly once`() = runBlocking {
        val transport = FakeTransport(ProviderFixtures.reply(functionCallReply()))
        val adapter = HostedAdapters.create(ProviderFixtures.geminiConfig(), transport)
        val callTurn = ProviderFixtures.turn(
            adapter.generate(adapter.begin("system", "prompt"), listOf(ProviderFixtures.echoTool)),
        )
        val pending = callTurn.conversation ?: error("continuable turn expected")

        try {
            adapter.continueWithToolResults(pending, emptyList())
            fail("expected an IllegalArgumentException for a dropped result")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().isNotBlank())
        }
        try {
            adapter.continueWithToolResults(
                pending,
                listOf(
                    HostedToolResult("call_1", "{}"),
                    HostedToolResult("call_2", "{}"),
                ),
            )
            fail("expected an IllegalArgumentException for an extra result")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().isNotBlank())
        }
    }

    @Test
    fun `offering tools without declared support is a configuration error`() = runBlocking {
        val transport = FakeTransport(ProviderFixtures.reply(functionCallReply()))
        val adapter = HostedAdapters.create(
            ProviderFixtures.geminiConfig(
                capabilities = HostedProviderCapabilities(
                    supportsToolCalls = false,
                    supportsParallelToolCalls = false,
                    supportsReasoningBudget = true,
                    supportsTemperature = true,
                ),
            ),
            transport,
        )
        try {
            adapter.generate(
                adapter.begin("system", "prompt"),
                listOf(ProviderFixtures.echoTool),
            )
            fail("expected a HostedConfigurationException")
        } catch (expected: HostedConfigurationException) {
            assertTrue(expected.message.orEmpty().isNotBlank())
        }
    }

    private suspend fun failureFor(
        status: Int,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): HostedProviderFailure {
        val adapter = HostedAdapters.create(
            ProviderFixtures.geminiConfig(),
            FakeTransport(ProviderFixtures.reply(body, status = status, headers = headers)),
        )
        return ProviderFixtures.failure(
            adapter.generate(adapter.begin("system", "prompt"), listOf(ProviderFixtures.echoTool)),
        )
    }

    private fun functionCallReply(): String = """
        {"modelVersion":"gemini-3.5-flash-lite","candidates":[{"content":{"role":"model","parts":[
          {"functionCall":{"name":"probe_echo","args":{"value":"ALPHA-17"},"id":"call_1"},"thoughtSignature":"sig"}
        ]},"finishReason":"STOP"}]}
    """.trimIndent()
}
