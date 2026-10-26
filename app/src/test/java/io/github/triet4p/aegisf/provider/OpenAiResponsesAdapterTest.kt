package io.github.triet4p.aegisf.provider

import kotlinx.coroutines.runBlocking
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
 * The Responses adapter's observable contract.
 *
 * Fixtures follow the verified typed-item shape: `output` holds a preserved reasoning item and a
 * `function_call` item with `call_id` and a JSON-string `arguments`; the result is a
 * `function_call_output` item. Truncated items, `incomplete`/`failed`/`cancelled` statuses,
 * missing identities and unanswerable call ids are each reported as their own outcome.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenAiResponsesAdapterTest {

    @Test
    fun `a function-call turn is typed, preserved, and continuable`() = runBlocking {
        val transport = FakeTransport(ProviderFixtures.reply(functionCallReply()))
        val adapter = HostedAdapters.create(ProviderFixtures.responsesConfig(), transport)
        val turn = ProviderFixtures.turn(
            adapter.generate(adapter.begin("system instructions", "probe ALPHA-17"), listOf(ProviderFixtures.echoTool)),
        )

        assertEquals(HostedTurnFinish.ToolCalls, turn.finish)
        assertEquals("gpt-6-luna", turn.reportedModel)
        assertEquals("call_1", turn.toolCalls.single().callId)
        assertEquals(ToolCallDisposition.READY, turn.toolCalls.single().disposition)
        assertEquals(listOf("call_1"), turn.conversation?.pendingCallIds)

        val request = transport.requestJson()
        assertEquals("gpt-6-luna", request.getString("model"))
        assertEquals(4_096, request.getInt("max_output_tokens"))
        assertEquals("medium", request.getJSONObject("reasoning").getString("effort"))
        assertFalse(request.has("previous_response_id"))
        val tool = request.getJSONArray("tools").getJSONObject(0)
        assertEquals("function", tool.getString("type"))
        assertEquals("probe_echo", tool.getString("name"))
        assertTrue(tool.has("parameters"))
        assertEquals("user", request.getJSONArray("input").getJSONObject(0).getString("role"))
        assertEquals(
            "https://opencode.ai/zen/go/v1/responses",
            transport.requests.single().url,
        )
    }

    @Test
    fun `continuation resends retained items and supplies one output per call id`() = runBlocking {
        val transport = FakeTransport(
            ProviderFixtures.reply(functionCallReply()),
            ProviderFixtures.reply(
                """
                {"model":"gpt-6-luna","status":"completed","output":[
                  {"id":"msg_1","type":"message","status":"completed","role":"assistant",
                   "content":[{"type":"output_text","text":"ALPHA-17 DONE"}]}]}
                """.trimIndent(),
            ),
        )
        val adapter = HostedAdapters.create(ProviderFixtures.responsesConfig(), transport)
        val first = adapter.begin("system", "probe ALPHA-17")
        val callTurn = ProviderFixtures.turn(adapter.generate(first, listOf(ProviderFixtures.echoTool)))
        val pending = callTurn.conversation ?: error("continuable turn expected")
        val next = adapter.continueWithToolResults(
            pending,
            listOf(HostedToolResult("call_1", """{"echoed":"ALPHA-17"}""")),
        )
        val finalTurn = ProviderFixtures.turn(adapter.generate(next, listOf(ProviderFixtures.echoTool)))

        assertEquals(HostedTurnFinish.Complete, finalTurn.finish)
        assertEquals("ALPHA-17 DONE", finalTurn.assistantText)
        assertNull(finalTurn.conversation)

        val input = transport.requestJson(1).getJSONArray("input")
        assertEquals(4, input.length())
        assertEquals("user", input.getJSONObject(0).getString("role"))
        assertEquals("reasoning", input.getJSONObject(1).getString("type"))
        assertEquals("opaque-reasoning", input.getJSONObject(1).getString("encrypted_content"))
        assertEquals("function_call", input.getJSONObject(2).getString("type"))
        val output = input.getJSONObject(3)
        assertEquals("function_call_output", output.getString("type"))
        assertEquals("call_1", output.getString("call_id"))
        assertEquals("""{"echoed":"ALPHA-17"}""", output.getString("output"))
    }

    @Test
    fun `an incomplete response is an explicit budget outcome and nothing is executable`() = runBlocking {
        val transport = FakeTransport(
            ProviderFixtures.reply(
                """
                {"model":"gpt-6-luna","status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},
                 "output":[{"id":"fc_1","type":"function_call","status":"completed","name":"probe_echo",
                            "call_id":"call_1","arguments":"{\"value\":\"ALPHA-17\"}"}]}
                """.trimIndent(),
            ),
        )
        val adapter = HostedAdapters.create(ProviderFixtures.responsesConfig(), transport)
        val turn = ProviderFixtures.turn(
            adapter.generate(adapter.begin("system", "probe"), listOf(ProviderFixtures.echoTool)),
        )

        assertEquals(HostedTurnFinish.Incomplete("max_output_tokens"), turn.finish)
        assertEquals(ToolCallDisposition.TRUNCATED_TURN, turn.toolCalls.single().disposition)
        assertFalse(turn.toolCalls.single().dispatchable)
        assertNull(turn.conversation)
    }

    @Test
    fun `an incomplete content-filtered response is a refusal`() = runBlocking {
        val transport = FakeTransport(
            ProviderFixtures.reply(
                """{"model":"gpt-6-luna","status":"incomplete",
                    "incomplete_details":{"reason":"content_filter"},"output":[]}""",
            ),
        )
        val adapter = HostedAdapters.create(ProviderFixtures.responsesConfig(), transport)
        val turn = ProviderFixtures.turn(
            adapter.generate(adapter.begin("system", "probe"), listOf(ProviderFixtures.echoTool)),
        )

        assertEquals(HostedTurnFinish.ContentFiltered("content_filter"), turn.finish)
        assertNull(turn.conversation)
    }

    @Test
    fun `a truncated function_call item is never executable`() = runBlocking {
        val transport = FakeTransport(
            ProviderFixtures.reply(
                """
                {"model":"gpt-6-luna","status":"completed","output":[
                  {"id":"fc_1","type":"function_call","status":"incomplete","name":"probe_echo",
                   "call_id":"call_1","arguments":"{\"value\":\"ALPHA-17\"}"}]}
                """.trimIndent(),
            ),
        )
        val adapter = HostedAdapters.create(ProviderFixtures.responsesConfig(), transport)
        val turn = ProviderFixtures.turn(
            adapter.generate(adapter.begin("system", "probe"), listOf(ProviderFixtures.echoTool)),
        )

        assertEquals(HostedTurnFinish.Incomplete("truncated_function_call"), turn.finish)
        assertEquals(ToolCallDisposition.TRUNCATED_TURN, turn.toolCalls.single().disposition)
        assertNull(turn.conversation)
    }

    @Test
    fun `failed, cancelled and non-terminal statuses are provider states`() = runBlocking {
        val failed = failureFor(
            """{"model":"gpt-6-luna","status":"failed","error":{"code":"server_error","message":"upstream"}}""",
        ) as HostedProviderFailure.ProviderState
        assertEquals("server_error", failed.code)

        val cancelled = failureFor("""{"model":"gpt-6-luna","status":"cancelled"}""")
            as HostedProviderFailure.ProviderState
        assertEquals("cancelled", cancelled.code)

        val queued = failureFor("""{"model":"gpt-6-luna","status":"queued"}""")
            as HostedProviderFailure.ProviderState
        assertEquals("queued", queued.code)
    }

    @Test
    fun `a missing model identity is invalid and a substituted one is a failure`() = runBlocking {
        assertTrue(
            failureFor("""{"status":"completed","output":[{"type":"message","content":[]}]}""")
                is HostedProviderFailure.InvalidResponse,
        )
        assertTrue(
            failureFor(
                """{"model":"gpt-6.1-sol","status":"completed",
                    "output":[{"type":"message","content":[{"type":"output_text","text":"ok"}]}]}""",
            ) is HostedProviderFailure.ModelMismatch,
        )
    }

    @Test
    fun `an unanswerable or non-text call is an invalid response`() = runBlocking {
        val missingCallId = """
            {"model":"gpt-6-luna","status":"completed","output":[
              {"type":"function_call","status":"completed","name":"probe_echo","arguments":"{}"}]}
        """.trimIndent()
        val nonTextArguments = """
            {"model":"gpt-6-luna","status":"completed","output":[
              {"type":"function_call","status":"completed","name":"probe_echo","call_id":"call_1","arguments":{}}]}
        """.trimIndent()
        val duplicateCallIds = """
            {"model":"gpt-6-luna","status":"completed","output":[
              {"type":"function_call","status":"completed","name":"probe_echo","call_id":"same","arguments":"{}"},
              {"type":"function_call","status":"completed","name":"probe_echo","call_id":"same","arguments":"{}"}]}
        """.trimIndent()
        for (body in listOf(missingCallId, nonTextArguments, duplicateCallIds)) {
            assertTrue(failureFor(body) is HostedProviderFailure.InvalidResponse)
        }
    }

    @Test
    fun `a completed response with no text and no call is incomplete`() = runBlocking {
        val transport = FakeTransport(
            ProviderFixtures.reply("""{"model":"gpt-6-luna","status":"completed","output":[]}"""),
        )
        val adapter = HostedAdapters.create(ProviderFixtures.responsesConfig(), transport)
        val turn = ProviderFixtures.turn(
            adapter.generate(adapter.begin("system", "probe"), listOf(ProviderFixtures.echoTool)),
        )

        assertEquals(HostedTurnFinish.Incomplete("empty_answer"), turn.finish)
        assertNull(turn.conversation)
    }

    @Test
    fun `a continuation must answer every pending call exactly once`() = runBlocking {
        val transport = FakeTransport(ProviderFixtures.reply(functionCallReply()))
        val adapter = HostedAdapters.create(ProviderFixtures.responsesConfig(), transport)
        val callTurn = ProviderFixtures.turn(
            adapter.generate(adapter.begin("system", "probe"), listOf(ProviderFixtures.echoTool)),
        )
        val pending = callTurn.conversation ?: error("continuable turn expected")
        try {
            adapter.continueWithToolResults(pending, emptyList())
            fail("expected an IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().isNotBlank())
        }
    }

    @Test
    fun `requested optional fields are sent only as configured`() = runBlocking {
        val transport = FakeTransport(
            ProviderFixtures.reply(
                """{"model":"gpt-6-luna","status":"completed",
                    "output":[{"type":"message","content":[{"type":"output_text","text":"ok"}]}]}""",
            ),
        )
        val adapter = HostedAdapters.create(
            ProviderFixtures.responsesConfig(
                budget = HostedGenerationBudget(
                    maxOutputTokens = 8_192,
                    reasoningEffort = HostedReasoningEffort.HIGH,
                    temperature = 0.3,
                    parallelToolCalls = true,
                ),
                capabilities = HostedProviderCapabilities(
                    supportsToolCalls = true,
                    supportsParallelToolCalls = true,
                    supportsReasoningBudget = true,
                    supportsTemperature = true,
                ),
            ),
            transport,
        )
        ProviderFixtures.turn(adapter.generate(adapter.begin("system", "probe")))

        val request: JSONObject = transport.requestJson()
        assertEquals("high", request.getJSONObject("reasoning").getString("effort"))
        assertEquals(0.3, request.getDouble("temperature"), 0.0)
        assertEquals(true, request.getBoolean("parallel_tool_calls"))
    }

    private suspend fun failureFor(body: String): HostedProviderFailure {
        val adapter = HostedAdapters.create(
            ProviderFixtures.responsesConfig(),
            FakeTransport(ProviderFixtures.reply(body)),
        )
        return ProviderFixtures.failure(
            adapter.generate(adapter.begin("system", "probe"), listOf(ProviderFixtures.echoTool)),
        )
    }

    private fun functionCallReply(): String = """
        {"id":"resp_1","object":"response","model":"gpt-6-luna","status":"completed","output":[
          {"id":"rs_1","type":"reasoning","summary":[],"encrypted_content":"opaque-reasoning"},
          {"id":"fc_1","type":"function_call","status":"completed","name":"probe_echo",
           "call_id":"call_1","arguments":"{\"value\":\"ALPHA-17\"}"}],
         "usage":{"input_tokens":178,"output_tokens":22,"total_tokens":200,
           "output_tokens_details":{"reasoning_tokens":0},"input_tokens_details":{"cached_tokens":0}}}
    """.trimIndent()
}
