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
 * The Chat Completions adapter's observable contract.
 *
 * The fixtures follow the verified OpenCode Go answer: `finish_reason: tool_calls`, several
 * `tool_calls` entries with JSON-string `arguments`, and a `reasoning_content` field that is
 * deliberately not carried into the continuation. Truncation, filtering, malformed arguments and
 * every pending id are covered, because the runtime may never execute or drop what this adapter
 * fails to classify.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenAiChatCompletionsAdapterTest {

    @Test
    fun `a multi-call turn is fully assembled and every pending call is retained`() = runBlocking {
        val transport = FakeTransport(ProviderFixtures.reply(twoCallReply()))
        val adapter = HostedAdapters.create(ProviderFixtures.chatConfig(), transport)
        val turn = ProviderFixtures.turn(
            adapter.generate(
                adapter.begin("system instructions", "probe twice"),
                listOf(ProviderFixtures.echoTool, ProviderFixtures.secondTool),
            ),
        )

        assertEquals(HostedTurnFinish.ToolCalls, turn.finish)
        assertEquals("deepseek-v4.1-flash", turn.reportedModel)
        assertEquals(
            listOf("call_a", "call_b"),
            turn.toolCalls.map { it.callId },
        )
        assertTrue(turn.toolCalls.all { it.dispatchable })
        assertEquals("ALPHA-17", turn.toolCalls[0].arguments?.get("value"))
        assertEquals(3, turn.toolCalls[1].arguments?.get("count"))
        assertEquals(listOf("call_a", "call_b"), turn.conversation?.pendingCallIds)
        assertEquals(59, turn.usage?.reasoningTokens)

        val request = transport.requestJson()
        assertEquals("deepseek-v4.1-flash", request.getString("model"))
        assertEquals(8_192, request.getInt("max_completion_tokens"))
        assertFalse(request.has("max_tokens"))
        assertEquals("auto", request.getString("tool_choice"))
        assertEquals(false, request.getBoolean("parallel_tool_calls"))
        assertFalse(request.has("reasoning_effort"))
        assertEquals(
            "https://opencode.ai/zen/go/v1/chat/completions",
            transport.requests.single().url,
        )
        assertEquals(
            "Bearer ${ProviderFixtures.TOKEN}",
            transport.requests.single().headers["Authorization"],
        )
        assertEquals("synthetic-session", transport.requests.single().headers["x-opencode-session"])
        assertEquals("aegis-f-test/1.0", transport.requests.single().headers["User-Agent"])
    }

    @Test
    fun `continuation answers every call and does not retain hidden reasoning`() = runBlocking {
        val transport = FakeTransport(
            ProviderFixtures.reply(twoCallReply()),
            ProviderFixtures.reply(
                """
                {"model":"deepseek-v4.1-flash","choices":[{"message":{"role":"assistant","content":"ALPHA-17 DONE"},
                 "finish_reason":"stop"}],"usage":{"prompt_tokens":30,"completion_tokens":4,"total_tokens":34}}
                """.trimIndent(),
            ),
        )
        val adapter = HostedAdapters.create(ProviderFixtures.chatConfig(), transport)
        val first = adapter.begin("system", "probe twice")
        val callTurn = ProviderFixtures.turn(
            adapter.generate(first, listOf(ProviderFixtures.echoTool, ProviderFixtures.secondTool)),
        )
        val pending = callTurn.conversation ?: error("continuable turn expected")
        val next = adapter.continueWithToolResults(
            pending,
            listOf(
                HostedToolResult("call_b", """{"counted":3}"""),
                HostedToolResult("call_a", """{"echoed":"ALPHA-17"}"""),
            ),
        )
        val finalTurn = ProviderFixtures.turn(
            adapter.generate(next, listOf(ProviderFixtures.echoTool, ProviderFixtures.secondTool)),
        )

        assertEquals(HostedTurnFinish.Complete, finalTurn.finish)
        assertEquals("ALPHA-17 DONE", finalTurn.assistantText)
        assertNull(finalTurn.conversation)

        val messages = transport.requestJson(1).getJSONArray("messages")
        assertEquals(2 + 3, messages.length())
        val assistant = messages.getJSONObject(2)
        assertEquals("assistant", assistant.getString("role"))
        assertTrue(assistant.has("tool_calls"))
        assertFalse("hidden reasoning must not be retained", assistant.has("reasoning_content"))
        val firstTool = messages.getJSONObject(3)
        assertEquals("tool", firstTool.getString("role"))
        assertEquals("call_a", firstTool.getString("tool_call_id"))
        assertEquals("""{"echoed":"ALPHA-17"}""", firstTool.getString("content"))
        assertEquals("call_b", messages.getJSONObject(4).getString("tool_call_id"))
    }

    @Test
    fun `partial argument text is malformed and never dispatchable`() = runBlocking {
        val transport = FakeTransport(
            ProviderFixtures.reply(
                """
                {"model":"deepseek-v4.1-flash","choices":[{"message":{"role":"assistant","content":null,
                 "tool_calls":[{"id":"call_a","type":"function",
                   "function":{"name":"probe_echo","arguments":"{\"value\":\"ALPHA"}}]},"finish_reason":"tool_calls"}]}
                """.trimIndent(),
            ),
        )
        val adapter = HostedAdapters.create(ProviderFixtures.chatConfig(), transport)
        val turn = ProviderFixtures.turn(
            adapter.generate(adapter.begin("system", "probe"), listOf(ProviderFixtures.echoTool)),
        )

        assertEquals(ToolCallDisposition.MALFORMED_ARGUMENTS, turn.toolCalls.single().disposition)
        assertFalse(turn.toolCalls.single().dispatchable)
        assertNull(turn.toolCalls.single().arguments)
    }

    @Test
    fun `schema-invalid arguments are not dispatchable`() = runBlocking {
        val transport = FakeTransport(
            ProviderFixtures.reply(
                """
                {"model":"deepseek-v4.1-flash","choices":[{"message":{"role":"assistant","content":null,
                 "tool_calls":[{"id":"call_a","type":"function",
                   "function":{"name":"probe_echo","arguments":"{\"value\":\"\"}"}}]},"finish_reason":"tool_calls"}]}
                """.trimIndent(),
            ),
        )
        val adapter = HostedAdapters.create(ProviderFixtures.chatConfig(), transport)
        val turn = ProviderFixtures.turn(
            adapter.generate(adapter.begin("system", "probe"), listOf(ProviderFixtures.echoTool)),
        )

        assertEquals(ToolCallDisposition.SCHEMA_INVALID, turn.toolCalls.single().disposition)
        assertFalse(turn.toolCalls.single().dispatchable)
    }

    @Test
    fun `a length stop is an explicit incomplete budget outcome`() = runBlocking {
        val transport = FakeTransport(
            ProviderFixtures.reply(
                """
                {"model":"deepseek-v4.1-flash","choices":[{"message":{"role":"assistant","content":"partial",
                 "tool_calls":[{"id":"call_a","type":"function",
                   "function":{"name":"probe_echo","arguments":"{\"value\":\"ALPHA-17\"}"}}]},"finish_reason":"length"}]}
                """.trimIndent(),
            ),
        )
        val adapter = HostedAdapters.create(ProviderFixtures.chatConfig(), transport)
        val turn = ProviderFixtures.turn(
            adapter.generate(adapter.begin("system", "probe"), listOf(ProviderFixtures.echoTool)),
        )

        assertEquals(HostedTurnFinish.Incomplete("length"), turn.finish)
        assertTrue(turn.toolCalls.none { it.dispatchable })
        assertEquals(ToolCallDisposition.TRUNCATED_TURN, turn.toolCalls.single().disposition)
        assertNull(turn.conversation)
    }

    @Test
    fun `a content filter is a provider refusal with no executable call`() = runBlocking {
        val transport = FakeTransport(
            ProviderFixtures.reply(
                """{"model":"deepseek-v4.1-flash","choices":[{"message":{"role":"assistant","content":null},
                   "finish_reason":"content_filter"}]}""",
            ),
        )
        val adapter = HostedAdapters.create(ProviderFixtures.chatConfig(), transport)
        val turn = ProviderFixtures.turn(
            adapter.generate(adapter.begin("system", "probe"), listOf(ProviderFixtures.echoTool)),
        )

        assertEquals(HostedTurnFinish.ContentFiltered("content_filter"), turn.finish)
        assertTrue(turn.toolCalls.isEmpty())
        assertNull(turn.conversation)
    }

    @Test
    fun `a tool call without an id or with non-string arguments is an invalid response`() = runBlocking {
        val missingId = """
            {"model":"deepseek-v4.1-flash","choices":[{"message":{"role":"assistant","content":null,
             "tool_calls":[{"type":"function","function":{"name":"probe_echo","arguments":"{}"}}]},
             "finish_reason":"tool_calls"}]}
        """.trimIndent()
        val nonString = """
            {"model":"deepseek-v4.1-flash","choices":[{"message":{"role":"assistant","content":null,
             "tool_calls":[{"id":"call_a","type":"function","function":{"name":"probe_echo","arguments":{}}}]},
             "finish_reason":"tool_calls"}]}
        """.trimIndent()
        for (body in listOf(missingId, nonString)) {
            val failure = failureFor(body)
            assertTrue("$body must be rejected", failure is HostedProviderFailure.InvalidResponse)
        }
    }

    @Test
    fun `a repeated tool call id is rejected rather than answered ambiguously`() = runBlocking {
        val failure = failureFor(
            """
            {"model":"deepseek-v4.1-flash","choices":[{"message":{"role":"assistant","content":null,
             "tool_calls":[
               {"id":"same","type":"function","function":{"name":"probe_echo","arguments":"{\"value\":\"A\"}"}},
               {"id":"same","type":"function","function":{"name":"probe_echo","arguments":"{\"value\":\"B\"}"}}
             ]},"finish_reason":"tool_calls"}]}
            """.trimIndent(),
        )
        assertTrue(failure is HostedProviderFailure.InvalidResponse)
    }

    @Test
    fun `a substituted model echo is a failure`() = runBlocking {
        val failure = failureFor(
            """
            {"model":"some-other-model","choices":[{"message":{"role":"assistant","content":"ok"},
             "finish_reason":"stop"}]}
            """.trimIndent(),
        )
        assertTrue(failure is HostedProviderFailure.ModelMismatch)
    }

    @Test
    fun `a declared reasoning budget is sent as reasoning_effort`() = runBlocking {
        val transport = FakeTransport(
            ProviderFixtures.reply(
                """{"model":"deepseek-v4.1-flash","choices":[{"message":{"role":"assistant","content":"ok"},
                   "finish_reason":"stop"}]}""",
            ),
        )
        val adapter = HostedAdapters.create(
            ProviderFixtures.chatConfig(
                budget = HostedGenerationBudget(
                    maxOutputTokens = 16_384,
                    reasoningEffort = HostedReasoningEffort.HIGH,
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

        val request = transport.requestJson()
        assertEquals("high", request.getString("reasoning_effort"))
        assertEquals(16_384, request.getInt("max_completion_tokens"))
    }

    private suspend fun failureFor(body: String): HostedProviderFailure {
        val adapter = HostedAdapters.create(
            ProviderFixtures.chatConfig(),
            FakeTransport(ProviderFixtures.reply(body)),
        )
        return ProviderFixtures.failure(
            adapter.generate(adapter.begin("system", "probe"), listOf(ProviderFixtures.echoTool)),
        )
    }

    private fun twoCallReply(): String = """
        {"id":"chatcmpl-1","model":"deepseek-v4.1-flash","choices":[{"index":0,"message":{
          "role":"assistant","content":null,
          "reasoning_content":"hidden planning text",
          "tool_calls":[
            {"id":"call_a","type":"function","function":{"name":"probe_echo","arguments":"{\"value\":\"ALPHA-17\"}"}},
            {"id":"call_b","type":"function","function":{"name":"probe_count","arguments":"{\"count\":3}"}}
          ]},"finish_reason":"tool_calls"}],
         "usage":{"prompt_tokens":352,"completion_tokens":104,"total_tokens":456,
           "completion_tokens_details":{"reasoning_tokens":59},"prompt_tokens_details":{"cached_tokens":0}}}
    """.trimIndent()
}
