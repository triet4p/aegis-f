package io.github.triet4p.aegisf.provider

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.URL
import java.time.Instant

/**
 * Bounded live protocol smoke for the three adapters.
 *
 * This test is deliberately **not** part of ordinary verification: it runs only when
 * `AEGIS_S85_LIVE_PROTOCOL_SMOKE=1`, and it uses the developer trial credentials already in the
 * repository-local `.env` (read in-process, never printed, never written to the evidence). It
 * sends only synthetic probe text.
 *
 * It is protocol-slice evidence, not runtime qualification: it proves that each configured
 * endpoint accepts the adapter's own wire encoding, that a real function call is parsed, that the
 * native result is accepted, that the model answers from it, and that the served model identity
 * matches the configured one. Qualification of a model-plus-harness configuration remains S8.13.
 *
 * Sanitized wire evidence is written under `app/build/s85-live/` and copied into the sprint-8
 * evidence directory by the worker. Header **names** are recorded; header values are not, so no
 * credential can reach the artifact.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HostedProtocolLiveSmokeTest {

    @Test
    fun `gemini generateContent completes a two-turn tool-result exchange`() = liveExchange {
        val apiKey = credentials()[GOOGLE_KEY]
        assumeTrue("$GOOGLE_KEY is not present in .env", !apiKey.isNullOrBlank())
        val config = HostedProviderConfig(
            protocol = HostedProtocol.GEMINI_GENERATE_CONTENT,
            endpoint = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
            model = "gemini-3.5-flash-lite",
            authentication = HostedAuthentication.GoogleApiKey(apiKey!!),
            budget = HostedGenerationBudget(
                maxOutputTokens = 8_192,
                reasoningEffort = HostedReasoningEffort.MINIMAL,
            ),
            capabilities = HostedProviderCapabilities(
                supportsToolCalls = true,
                supportsParallelToolCalls = false,
                supportsReasoningBudget = true,
                supportsTemperature = true,
                maxOutputTokensLimit = 65_536,
            ),
        )
        val file = "gemini-generate-content"
        exchange(config, "Gemini generateContent", file)
    }

    @Test
    fun `opencode go chat completions completes a two-turn tool-result exchange`() = liveExchange {
        val apiKey = credentials()[GO_KEY]
        assumeTrue("$GO_KEY is not present in .env", !apiKey.isNullOrBlank())
        val config = HostedProviderConfig(
            protocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS,
            endpoint = "https://opencode.ai/zen/go/v1/chat/completions",
            model = "deepseek-v4.1-flash",
            authentication = HostedAuthentication.Bearer(apiKey!!),
            budget = HostedGenerationBudget(maxOutputTokens = 8_192, parallelToolCalls = true),
            capabilities = HostedProviderCapabilities(
                supportsToolCalls = true,
                supportsParallelToolCalls = true,
                supportsReasoningBudget = false,
                supportsTemperature = true,
            ),
            clientHeaders = mapOf(
                "x-opencode-session" to "aegis-f-s8.5-live-smoke",
                "User-Agent" to "aegis-f-protocol-probe/1.0",
            ),
        )
        val file = "opencode-go-chat-completions"
        exchange(config, "OpenCode Go chat completions", file)
    }

    @Test
    fun `opencode go responses completes a two-turn tool-result exchange`() = liveExchange {
        val apiKey = credentials()[GO_KEY]
        assumeTrue("$GO_KEY is not present in .env", !apiKey.isNullOrBlank())
        val config = HostedProviderConfig(
            protocol = HostedProtocol.OPENAI_RESPONSES,
            endpoint = "https://opencode.ai/zen/go/v1/responses",
            model = "gpt-6-luna",
            authentication = HostedAuthentication.Bearer(apiKey!!),
            budget = HostedGenerationBudget(
                maxOutputTokens = 4_096,
                reasoningEffort = HostedReasoningEffort.MEDIUM,
                parallelToolCalls = true,
            ),
            capabilities = HostedProviderCapabilities(
                supportsToolCalls = true,
                supportsParallelToolCalls = true,
                supportsReasoningBudget = true,
                supportsTemperature = false,
            ),
            clientHeaders = mapOf(
                "x-opencode-session" to "aegis-f-s8.5-live-smoke",
                "User-Agent" to "aegis-f-protocol-probe/1.0",
            ),
        )
        val file = "opencode-go-responses"
        exchange(config, "OpenCode Go Responses", file)
    }

    /**
     * Runs the actual adapter twice over the real transport: one call turn, one result-fed answer.
     * The evidence file records configured identity, classified outcomes and the exact synthetic
     * wire bodies, with header values removed.
     */
    private suspend fun exchange(config: HostedProviderConfig, label: String, fileStem: String) {
        val recording = RecordingTransport(UrlConnectionHttpTransport())
        val adapter = HostedAdapters.create(config, recording)

        val tool = HostedToolDeclaration(
            name = "probe_echo",
            description = "Echo a probe value back to the caller.",
            parameters = JSONObject(
                """
                {"type":"object","properties":{"value":{"type":"string","minLength":1}},
                 "required":["value"],"additionalProperties":false}
                """.trimIndent(),
            ),
        )
        val system = "You are a bounded protocol probe. Use the declared tool when asked; " +
            "do not claim any value the tool did not return."
        val user = "Call probe_echo with value exactly ALPHA-17 now. After the tool result " +
            "returns, reply with the echoed value followed by the word DONE, and make no further " +
            "tool calls."

        val firstConversation = adapter.begin(system, user)
        val firstTurn = ProviderFixtures.turn(adapter.generate(firstConversation, listOf(tool)))
        assertEquals("$label: the first turn must await the tool result", HostedTurnFinish.ToolCalls, firstTurn.finish)
        assertEquals("$label: the served model must be the configured model", config.model, firstTurn.reportedModel)
        val call = firstTurn.toolCalls.firstOrNull { it.dispatchable }
        assertNotNull("$label: no dispatchable function call was returned", call)
        val conversation = firstTurn.conversation
        assertNotNull("$label: a tool-call turn must carry its continuation", conversation)
        val echoed = call!!.arguments?.get("value")?.toString().orEmpty()
        assertTrue("$label: the call carried no echo value", echoed.isNotBlank())

        val secondConversation = adapter.continueWithToolResults(
            conversation!!,
            listOf(HostedToolResult(call.callId, JSONObject().put("echoed", echoed).toString())),
        )
        val secondTurn = ProviderFixtures.turn(adapter.generate(secondConversation, listOf(tool)))
        assertEquals("$label: the result-fed turn must complete", HostedTurnFinish.Complete, secondTurn.finish)
        assertEquals("$label: the served model must stay the configured model", config.model, secondTurn.reportedModel)
        val answer = secondTurn.assistantText.orEmpty()
        assertTrue(
            "$label: the final answer did not use the tool result ($answer)",
            answer.contains(echoed),
        )

        val evidence = JSONObject()
            .put("protocol", config.protocol.name)
            .put("label", label)
            .put("endpoint", config.endpoint)
            .put("configured_model", config.model)
            .put("max_output_tokens", config.budget.maxOutputTokens)
            .put("reasoning_effort", config.budget.reasoningEffort?.wireName ?: JSONObject.NULL)
            .put(
                "configured_capabilities",
                JSONObject()
                    .put("tool_calls", config.capabilities.supportsToolCalls)
                    .put("parallel_tool_calls", config.capabilities.supportsParallelToolCalls)
                    .put("reasoning_budget", config.capabilities.supportsReasoningBudget)
                    .put("temperature", config.capabilities.supportsTemperature),
            )
            .put("turn_1", summarize(firstTurn))
            .put("turn_2", summarize(secondTurn))
            .put("final_text", answer)
            .put("continuation_call_ids", JSONArray(conversation.pendingCallIds))
            .put("echo_value", echoed)
            .put("wire", recording.evidence())
            .put("synthetic_inputs_only", true)
            .put("credential_recorded", false)
            .put("captured_at", Instant.now().toString())

        val output = File("build/s85-live").apply { mkdirs() }
        File(output, "$fileStem.json").writeText(evidence.toString(2))
        println("S8.5 live smoke PASS: $label (served ${firstTurn.reportedModel})")
    }

    private fun summarize(turn: HostedTurn): JSONObject = JSONObject()
        .put(
            "finish",
            when (val finish = turn.finish) {
                HostedTurnFinish.Complete -> "complete"
                HostedTurnFinish.ToolCalls -> "tool_calls"
                is HostedTurnFinish.Incomplete -> "incomplete:${finish.reason}"
                is HostedTurnFinish.ContentFiltered -> "content_filtered:${finish.reason ?: "unspecified"}"
            },
        )
        .put("requested_model", turn.requestedModel)
        .put("reported_model", turn.reportedModel ?: JSONObject.NULL)
        .put("text", turn.assistantText ?: JSONObject.NULL)
        .put(
            "calls",
            JSONArray().also { calls ->
                turn.toolCalls.forEach { call ->
                    calls.put(
                        JSONObject()
                            .put("call_id", call.callId)
                            .put("name", call.name)
                            .put("arguments", call.argumentsJson ?: JSONObject.NULL)
                            .put("disposition", call.disposition.name)
                            .put("dispatchable", call.dispatchable),
                    )
                }
            },
        )
        .put("pending_call_ids", JSONArray(turn.conversation?.pendingCallIds.orEmpty()))
        .put(
            "usage",
            turn.usage?.let { usage ->
                JSONObject()
                    .put("input_tokens", usage.inputTokens ?: JSONObject.NULL)
                    .put("output_tokens", usage.outputTokens ?: JSONObject.NULL)
                    .put("reasoning_tokens", usage.reasoningTokens ?: JSONObject.NULL)
                    .put("cached_input_tokens", usage.cachedInputTokens ?: JSONObject.NULL)
            } ?: JSONObject.NULL,
        )

    /** Records the exact synthetic wire exchange; header values never enter the recording. */
    private inner class RecordingTransport(private val delegate: HttpTransport) : HttpTransport {
        private val exchanges = JSONArray()

        override suspend fun post(request: HostedHttpRequest): HostedHttpResponse {
            val response = delegate.post(request)
            exchanges.put(
                JSONObject()
                    .put("url", request.url)
                    .put("url_host", URL(request.url).host)
                    .put("header_names", JSONArray(request.headers.keys.sorted()))
                    .put("request", parseOrText(request.body))
                    .put("status", response.status)
                    .put("response", parseOrText(response.body)),
            )
            return response
        }

        fun evidence(): JSONArray = JSONArray(exchanges.toString())
    }

    private fun liveExchange(block: suspend () -> Unit) {
        assumeTrue(
            "set AEGIS_S85_LIVE_PROTOCOL_SMOKE=1 to run the bounded live protocol smoke",
            System.getenv("AEGIS_S85_LIVE_PROTOCOL_SMOKE") == "1",
        )
        runBlocking { block() }
    }

    private fun credentials(): Map<String, String> {
        val file = listOf(File("../.env"), File(".env")).firstOrNull { it.isFile } ?: return emptyMap()
        return file.readLines().mapNotNull { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || !line.contains('=')) return@mapNotNull null
            val name = line.substringBefore('=').trim()
            val value = line.substringAfter('=').trim().removeSurrounding("\"").removeSurrounding("'")
            name to value
        }.toMap()
    }

    private fun parseOrText(body: String): Any = try {
        JSONObject(body)
    } catch (exception: Exception) {
        body.take(2_000)
    }

    private companion object {
        const val GOOGLE_KEY = "GOOGLE_AI_STUDIO_KEY"
        const val GO_KEY = "OPENCODE_GO_API_KEY"
    }
}
