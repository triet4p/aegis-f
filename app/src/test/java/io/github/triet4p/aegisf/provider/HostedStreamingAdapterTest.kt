package io.github.triet4p.aegisf.provider

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The streamed turn of each supported protocol, with a scripted SSE transport.
 *
 * Every assertion is on the parsed turn and the reported public progress: the transport is only
 * a script of events, so a parser that mis-joins deltas, dispatches partial tool calls, or leaks
 * private reasoning into progress fails here before any UI exists.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HostedStreamingAdapterTest {

    private val guard = object : HostedResponsePrivacyGuard {
        override fun scan(rawResponse: String, httpStatus: Int?): HostedResponsePrivacyResult =
            HostedResponsePrivacyResult.Accepted
    }

    // ---- Chat Completions ----------------------------------------------------------------

    @Test
    fun `chat completions streams answer text and a tool call assembled from fragments`() = runBlocking {
        val transport = ScriptedStreamTransport(
            mutableListOf(
                listOf(
                    start(),
                    event("""{"model":"deepseek-v4.1-flash","choices":[{"index":0,"delta":{"role":"assistant","content":"Đang "}}]}"""),
                    event("""{"model":"deepseek-v4.1-flash","choices":[{"index":0,"delta":{"content":"xử lý."}}]}"""),
                    event(
                        """{"model":"deepseek-v4.1-flash","choices":[{"index":0,"delta":{"tool_calls":[
                        {"index":0,"id":"call-1","type":"function","function":{"name":"probe_echo","arguments":"{\"val"}}]}}]}""",
                    ),
                    event(
                        """{"model":"deepseek-v4.1-flash","choices":[{"index":0,"delta":{"tool_calls":[
                        {"index":0,"function":{"arguments":"ue\":\"x\"}"}}]}}]}""",
                    ),
                    event("""{"model":"deepseek-v4.1-flash","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"""),
                    event("[DONE]"),
                ),
            ),
        )
        val adapter = OpenAiChatCompletionsAdapter(ProviderFixtures.chatConfig(), transport, transport)
        val progress = mutableListOf<HostedTurnProgress>()
        val result = adapter.streamGuarded(
            adapter.begin("system", "user"),
            listOf(ProviderFixtures.echoTool),
            guard,
        ) { progress += it }

        val turn = ProviderFixtures.turn(result)
        assertEquals("Đang xử lý.", turn.assistantText)
        assertEquals(1, turn.toolCalls.size)
        val call = turn.toolCalls.single()
        assertEquals("probe_echo", call.name)
        assertEquals("""{"value":"x"}""", call.argumentsJson)
        assertEquals(ToolCallDisposition.READY, call.disposition)
        assertTrue(call.dispatchable)
        assertEquals(HostedTurnFinish.ToolCalls, turn.finish)
        assertEquals(listOf("call-1"), turn.conversation?.pendingCallIds)
        assertEquals(
            listOf(
                "Đang ",
                "xử lý.",
            ),
            progress.filterIsInstance<HostedTurnProgress.PublicText>().map { it.text },
        )
        assertEquals(
            listOf("probe_echo"),
            progress.filterIsInstance<HostedTurnProgress.ToolCallStarted>().map { it.name },
        )
        assertEquals(
            listOf("probe_echo"),
            progress.filterIsInstance<HostedTurnProgress.ToolCallCompleted>().map { it.name },
        )
        // The assembled body is the provider's delta content in the ordinary response shape.
        val assembled = JSONObject(turn.assembledResponseBody ?: error("no assembled body"))
        assertEquals(
            """{"value":"x"}""",
            assembled.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getJSONArray("tool_calls").getJSONObject(0)
                .getJSONObject("function").getString("arguments"),
        )
        // Local usage is only claimed when the provider sent it.
        assertNull(turn.usage)
    }

    @Test
    fun `private reasoning is reported as activity and never as answer text`() = runBlocking {
        val transport = ScriptedStreamTransport(
            mutableListOf(
                listOf(
                    start(),
                    event(
                        """{"model":"deepseek-v4.1-flash","choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"private thought"}}]}""",
                    ),
                    event(
                        """{"model":"deepseek-v4.1-flash","choices":[{"index":0,"delta":{"reasoning":"more private thought","content":"Visible."}}]}""",
                    ),
                    event("""{"model":"deepseek-v4.1-flash","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""),
                    event("[DONE]"),
                ),
            ),
        )
        val adapter = OpenAiChatCompletionsAdapter(ProviderFixtures.chatConfig(), transport, transport)
        val progress = mutableListOf<HostedTurnProgress>()
        val result = adapter.streamGuarded(
            adapter.begin("system", "user"),
            emptyList(),
            guard,
        ) { progress += it }

        val turn = ProviderFixtures.turn(result)
        assertEquals("Visible.", turn.assistantText)
        assertEquals(2, progress.count { it is HostedTurnProgress.Reasoning })
        assertEquals(
            listOf("Visible."),
            progress.filterIsInstance<HostedTurnProgress.PublicText>().map { it.text },
        )
        assertFalse(
            "private reasoning must never become public progress",
            progress.filterIsInstance<HostedTurnProgress.PublicText>()
                .any { it.text.contains("private thought") },
        )
    }

    @Test
    fun `a truncated streamed tool call is never dispatchable`() = runBlocking {
        val transport = ScriptedStreamTransport(
            mutableListOf(
                listOf(
                    start(),
                    event(
                        """{"model":"deepseek-v4.1-flash","choices":[{"index":0,"delta":{"tool_calls":[
                        {"index":0,"id":"call-1","type":"function","function":{"name":"probe_echo","arguments":"{\"value\":"}}]}}]}""",
                    ),
                    event("""{"model":"deepseek-v4.1-flash","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"""),
                ),
            ),
        )
        val adapter = OpenAiChatCompletionsAdapter(ProviderFixtures.chatConfig(), transport, transport)
        val turn = ProviderFixtures.turn(
            adapter.streamGuarded(
                adapter.begin("system", "user"),
                listOf(ProviderFixtures.echoTool),
                guard,
            ) {},
        )
        val call = turn.toolCalls.single()
        assertEquals(ToolCallDisposition.MALFORMED_ARGUMENTS, call.disposition)
        assertFalse(call.dispatchable)
    }

    @Test
    fun `explicit null stream fields are absent, never text`() = runBlocking {
        val transport = ScriptedStreamTransport(
            mutableListOf(
                listOf(
                    start(),
                    event(
                        """{"model":null,"choices":[{"index":0,"finish_reason":null,"delta":{"role":null,"content":null,"reasoning_content":"private thought","tool_calls":null}}]}""",
                    ),
                    event(
                        """{"model":"deepseek-v4.1-flash","choices":[{"index":0,"delta":{"tool_calls":[
                        {"index":0,"id":null,"type":null,"function":{"name":"probe_echo","arguments":""}}]}}]}""",
                    ),
                    event(
                        """{"model":"deepseek-v4.1-flash","choices":[{"index":0,"delta":{"tool_calls":[
                        {"index":0,"id":null,"function":{"name":null,"arguments":"{\"value\":\"x\"}"}}]}}]}""",
                    ),
                    event("""{"model":"deepseek-v4.1-flash","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"""),
                    event("[DONE]"),
                ),
            ),
        )
        val adapter = OpenAiChatCompletionsAdapter(ProviderFixtures.chatConfig(), transport, transport)
        val progress = mutableListOf<HostedTurnProgress>()
        val turn = ProviderFixtures.turn(
            adapter.streamGuarded(
                adapter.begin("system", "user"),
                listOf(ProviderFixtures.echoTool),
                guard,
            ) { progress += it },
        )

        assertNull(turn.assistantText)
        val call = turn.toolCalls.single()
        assertEquals("probe_echo", call.name)
        assertTrue(call.callId, call.callId.startsWith("call_"))
        assertEquals("deepseek-v4.1-flash", turn.reportedModel)
        assertEquals(
            listOf("probe_echo"),
            progress.filterIsInstance<HostedTurnProgress.ToolCallStarted>().map { it.name },
        )
        assertTrue(progress.filterIsInstance<HostedTurnProgress.PublicText>().isEmpty())
        val assembled = JSONObject(turn.assembledResponseBody ?: error("no assembled body"))
        assertEquals(
            "probe_echo",
            assembled.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getJSONArray("tool_calls").getJSONObject(0)
                .getJSONObject("function").getString("name"),
        )
    }

    @Test
    fun `a stream without a finish state is a provider failure not a partial answer`() = runBlocking {
        val transport = ScriptedStreamTransport(
            mutableListOf(
                listOf(
                    start(),
                    event("""{"model":"deepseek-v4.1-flash","choices":[{"index":0,"delta":{"content":"half"}}]}"""),
                ),
            ),
        )
        val adapter = OpenAiChatCompletionsAdapter(ProviderFixtures.chatConfig(), transport, transport)
        val failure = ProviderFixtures.failure(
            adapter.streamGuarded(
                adapter.begin("system", "user"),
                listOf(ProviderFixtures.echoTool),
                guard,
            ) {},
        )
        assertTrue(failure.toString(), failure is HostedProviderFailure.InvalidResponse)
    }

    @Test
    fun `a stream error event and a non-2xx stream head are classified`() = runBlocking {
        val errorTransport = ScriptedStreamTransport(
            mutableListOf(
                listOf(
                    start(),
                    event("""{"error":{"code":"rate_limit","message":"slow down"}}"""),
                ),
            ),
        )
        val chat = OpenAiChatCompletionsAdapter(ProviderFixtures.chatConfig(), errorTransport, errorTransport)
        assertTrue(
            ProviderFixtures.failure(
                chat.streamGuarded(chat.begin("system", "user"), emptyList(), guard) {},
            ) is HostedProviderFailure.ProviderState,
        )

        val rejected = ScriptedStreamTransport(
            mutableListOf(
                listOf(
                    HostedStreamFrame.Start(
                        status = 401,
                        headers = emptyMap(),
                        errorBody = """{"error":{"message":"bad credential"}}""",
                    ),
                ),
            ),
        )
        val rejectedAdapter = OpenAiChatCompletionsAdapter(ProviderFixtures.chatConfig(), rejected, rejected)
        assertTrue(
            ProviderFixtures.failure(
                rejectedAdapter.streamGuarded(rejectedAdapter.begin("system", "user"), emptyList(), guard) {},
            ) is HostedProviderFailure.Authentication,
        )
    }

    @Test
    fun `cancellation propagates out of a streamed turn`() = runBlocking {
        val transport = ScriptedStreamTransport(mutableListOf(), failWith = CancellationException("stop"))
        val adapter = OpenAiChatCompletionsAdapter(ProviderFixtures.chatConfig(), transport, transport)
        try {
            adapter.streamGuarded(adapter.begin("system", "user"), emptyList(), guard) {}
            fail("cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals("stop", expected.message)
        }
    }

    @Test
    fun `a transport io failure becomes a transport provider failure`() = runBlocking {
        val transport = ScriptedStreamTransport(mutableListOf(), failWith = IOException("connection reset"))
        val adapter = OpenAiChatCompletionsAdapter(ProviderFixtures.chatConfig(), transport, transport)
        val failure = ProviderFixtures.failure(
            adapter.streamGuarded(adapter.begin("system", "user"), emptyList(), guard) {},
        )
        assertTrue(failure is HostedProviderFailure.Transport)
    }

    // ---- OpenAI Responses ----------------------------------------------------------------

    @Test
    fun `responses streams text deltas and uses the terminal response object`() = runBlocking {
        val finalResponse = JSONObject(
            """
            {"id":"resp_1","model":"gpt-6-luna","status":"completed",
             "output":[
               {"type":"message","role":"assistant","content":[
                 {"type":"output_text","text":"Xong."}]},
               {"type":"function_call","id":"fc_1","call_id":"call_9","name":"probe_echo",
                "arguments":"{\"value\":\"y\"}","status":"completed"}],
             "usage":{"input_tokens":10,"output_tokens":5}}
            """.trimIndent(),
        )
        val transport = ScriptedStreamTransport(
            mutableListOf(
                listOf(
                    start(),
                    namedEvent("response.output_item.added", """{"type":"response.output_item.added","item":{"type":"function_call","id":"fc_1","call_id":"call_9","name":"probe_echo"}}"""),
                    namedEvent("response.output_text.delta", """{"type":"response.output_text.delta","delta":"Xong."}"""),
                    namedEvent("response.function_call_arguments.delta", """{"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"{\"value\":\"y\"}"}"""),
                    namedEvent("response.completed", """{"type":"response.completed","response":$finalResponse}"""),
                ),
            ),
        )
        val adapter = OpenAiResponsesAdapter(ProviderFixtures.responsesConfig(), transport, transport)
        val progress = mutableListOf<HostedTurnProgress>()
        val turn = ProviderFixtures.turn(
            adapter.streamGuarded(
                adapter.begin("system", "user"),
                listOf(ProviderFixtures.echoTool),
                guard,
            ) { progress += it },
        )
        assertEquals("Xong.", turn.assistantText)
        assertEquals("probe_echo", turn.toolCalls.single().name)
        assertEquals(ToolCallDisposition.READY, turn.toolCalls.single().disposition)
        val assembled = JSONObject(turn.assembledResponseBody ?: error("no assembled body"))
        assertEquals("completed", assembled.getString("status"))
        assertEquals(2, assembled.getJSONArray("output").length())
        assertEquals(listOf("Xong."), progress.filterIsInstance<HostedTurnProgress.PublicText>().map { it.text })
        assertEquals(listOf("probe_echo"), progress.filterIsInstance<HostedTurnProgress.ToolCallStarted>().map { it.name })
    }

    @Test
    fun `a responses stream without its terminal event is refused`() = runBlocking {
        val transport = ScriptedStreamTransport(
            mutableListOf(
                listOf(
                    start(),
                    namedEvent("response.output_text.delta", """{"type":"response.output_text.delta","delta":"partial"}"""),
                ),
            ),
        )
        val adapter = OpenAiResponsesAdapter(ProviderFixtures.responsesConfig(), transport, transport)
        val failure = ProviderFixtures.failure(
            adapter.streamGuarded(adapter.begin("system", "user"), emptyList(), guard) {},
        )
        assertTrue(failure is HostedProviderFailure.InvalidResponse)
    }

    // ---- Gemini --------------------------------------------------------------------------

    @Test
    fun `gemini streams plain text and function calls on its sse route`() = runBlocking {
        val transport = ScriptedStreamTransport(
            mutableListOf(
                listOf(
                    start(),
                    event(
                        """{"modelVersion":"gemini-3.5-flash-lite","candidates":[{"content":{"role":"model","parts":[
                        {"text":"Suy nghĩ riêng","thought":true},{"text":"Đã "}]},"index":0}],"usageMetadata":{"promptTokenCount":9}}""",
                    ),
                    event(
                        """{"modelVersion":"gemini-3.5-flash-lite","candidates":[{"content":{"role":"model","parts":[
                        {"text":"xong."},{"functionCall":{"name":"probe_echo","args":{"value":"z"}}}]},"finishReason":"STOP","index":0}],
                        "usageMetadata":{"promptTokenCount":9,"candidatesTokenCount":4}}""",
                    ),
                ),
            ),
        )
        val adapter = GeminiGenerateContentAdapter(ProviderFixtures.geminiConfig(), transport, transport)
        val progress = mutableListOf<HostedTurnProgress>()
        val turn = ProviderFixtures.turn(
            adapter.streamGuarded(
                adapter.begin("system", "user"),
                listOf(ProviderFixtures.echoTool),
                guard,
            ) { progress += it },
        )
        assertEquals("Đã xong.", turn.assistantText)
        assertEquals("probe_echo", turn.toolCalls.single().name)
        assertEquals(ToolCallDisposition.READY, turn.toolCalls.single().disposition)
        assertEquals(HostedTurnFinish.ToolCalls, turn.finish)
        // Private reasoning text never reaches public progress, but stays in the continuation
        // content the thinking model requires.
        assertFalse(progress.filterIsInstance<HostedTurnProgress.PublicText>().any { it.text.contains("Suy nghĩ") })
        assertEquals(listOf("Đã ", "xong."), progress.filterIsInstance<HostedTurnProgress.PublicText>().map { it.text })
        assertTrue(turn.assembledResponseBody.orEmpty().contains("Suy nghĩ riêng"))
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:streamGenerateContent?alt=sse",
            transport.requests.single().url,
        )
        assertNotNull(turn.usage)
        assertEquals(4, turn.usage?.outputTokens)
    }

    // ---- Fake transport ------------------------------------------------------------------

    private fun start() = HostedStreamFrame.Start(200, emptyMap(), null)

    private fun event(data: String) = HostedStreamFrame.Event(HostedSseEvent(null, data))

    private fun namedEvent(name: String, data: String) =
        HostedStreamFrame.Event(HostedSseEvent(name, data))

    private class ScriptedStreamTransport(
        private val scripts: MutableList<List<HostedStreamFrame>>,
        private val failWith: Exception? = null,
    ) : HttpTransport, HttpStreamTransport {
        val requests = mutableListOf<HostedHttpRequest>()
        private var index = 0

        override suspend fun post(request: HostedHttpRequest): HostedHttpResponse =
            error("a streamed turn must use the stream transport")

        override fun postStream(request: HostedHttpRequest): Flow<HostedStreamFrame> = flow {
            requests += request
            failWith?.let { throw it }
            val frames = scripts.getOrNull(index)
                ?: error("the stream transport was called more times than the script allows")
            index += 1
            frames.forEach { emit(it) }
        }
    }
}
