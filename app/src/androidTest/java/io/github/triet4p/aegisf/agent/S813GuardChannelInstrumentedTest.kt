package io.github.triet4p.aegisf.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.triet4p.aegisf.privacy.MaskedHostedGateway
import io.github.triet4p.aegisf.privacy.MaskedPromptBoundary
import io.github.triet4p.aegisf.privacy.SensitiveVault
import io.github.triet4p.aegisf.provider.HostedAuthentication
import io.github.triet4p.aegisf.provider.HostedGenerationBudget
import io.github.triet4p.aegisf.provider.HostedGenerationResult
import io.github.triet4p.aegisf.provider.HostedAdapters
import io.github.triet4p.aegisf.provider.HostedHttpRequest
import io.github.triet4p.aegisf.provider.HostedHttpResponse
import io.github.triet4p.aegisf.provider.HostedProviderCapabilities
import io.github.triet4p.aegisf.provider.HostedProviderConfig
import io.github.triet4p.aegisf.provider.HostedProtocol
import io.github.triet4p.aegisf.provider.HostedResponsePrivacyRefusalReason
import io.github.triet4p.aegisf.provider.HostedResponsePrivacyResult
import io.github.triet4p.aegisf.provider.HostedToolDeclaration
import io.github.triet4p.aegisf.provider.HostedToolResult
import io.github.triet4p.aegisf.provider.HostedTurnFinish
import io.github.triet4p.aegisf.provider.HttpTransport
import io.github.triet4p.aegisf.provider.responseMetadataPolicy
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Local-only device smoke for the owner-approved guard-channel boundary and the per-turn
 * issued-token inventory.
 *
 * Everything here runs on the real Android runtime: the real [MaskedPromptBoundary] (with
 * `android.util.JsonReader`), the real adapters and the real [MaskedHostedGateway]. A queued
 * in-process transport stands in for the network, so this test opens no socket and reads no
 * credential. It proves that provider reasoning/thinking cannot refuse a response by itself, that
 * the same invalid content in the answer or tool arguments is still blocked, that a malformed
 * envelope still fails, and that every model turn carries the run's complete issued-token list.
 */
@RunWith(AndroidJUnit4::class)
class S813GuardChannelInstrumentedTest {

    private class QueueTransport(private val replies: List<HostedHttpResponse>) : HttpTransport {
        val requests = mutableListOf<HostedHttpRequest>()
        private var index = 0
        override suspend fun post(request: HostedHttpRequest): HostedHttpResponse {
            requests += request
            return replies[index++]
        }
    }

    private enum class Channel { REASONING, ANSWER, ARGUMENTS }

    private fun config(protocol: HostedProtocol): HostedProviderConfig = when (protocol) {
        HostedProtocol.GEMINI_GENERATE_CONTENT -> HostedProviderConfig(
            protocol = protocol,
            endpoint = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
            model = "gemini-3.5-flash-lite",
            authentication = HostedAuthentication.GoogleApiKey(SYNTHETIC_KEY),
            budget = HostedGenerationBudget(maxOutputTokens = 8_192),
            capabilities = capabilities(),
        )

        HostedProtocol.OPENAI_CHAT_COMPLETIONS -> HostedProviderConfig(
            protocol = protocol,
            endpoint = "https://opencode.ai/zen/go/v1/chat/completions",
            model = "deepseek-v4.1-flash",
            authentication = HostedAuthentication.Bearer(SYNTHETIC_KEY),
            budget = HostedGenerationBudget(maxOutputTokens = 8_192),
            capabilities = capabilities(),
        )

        HostedProtocol.OPENAI_RESPONSES -> HostedProviderConfig(
            protocol = protocol,
            endpoint = "https://opencode.ai/zen/go/v1/responses",
            model = "gpt-6-luna",
            authentication = HostedAuthentication.Bearer(SYNTHETIC_KEY),
            budget = HostedGenerationBudget(maxOutputTokens = 8_192),
            capabilities = capabilities(),
        )
    }

    private fun capabilities(): HostedProviderCapabilities = HostedProviderCapabilities(
        supportsToolCalls = true,
        supportsParallelToolCalls = true,
        supportsReasoningBudget = true,
        supportsTemperature = true,
    )

    private fun body(protocol: HostedProtocol, text: String, channel: Channel): String =
        when (protocol) {
            HostedProtocol.GEMINI_GENERATE_CONTENT -> JSONObject()
                .put("modelVersion", config(protocol).model)
                .put(
                    "candidates",
                    JSONArray().put(
                        JSONObject()
                            .put(
                                "content",
                                JSONObject()
                                    .put("role", "model")
                                    .put(
                                        "parts",
                                        JSONArray().put(
                                            when (channel) {
                                                Channel.REASONING ->
                                                    JSONObject().put("text", text).put("thought", true)

                                                Channel.ANSWER -> JSONObject().put("text", text)

                                                Channel.ARGUMENTS -> JSONObject().put(
                                                    "functionCall",
                                                    JSONObject()
                                                        .put("name", "probe_echo")
                                                        .put("args", JSONObject().put("value", text))
                                                        .put("id", "call_1"),
                                                )
                                            },
                                        ),
                                    ),
                            )
                            .put("finishReason", "STOP"),
                    ),
                )
                .toString()

            HostedProtocol.OPENAI_CHAT_COMPLETIONS -> JSONObject()
                .put("id", "chatcmpl-device")
                .put("object", "chat.completion")
                .put("created", 1)
                .put("model", config(protocol).model)
                .put(
                    "choices",
                    JSONArray().put(
                        JSONObject()
                            .put("index", 0)
                            .put("finish_reason", if (channel == Channel.ARGUMENTS) "tool_calls" else "stop")
                            .put(
                                "message",
                                JSONObject()
                                    .put("role", "assistant")
                                    .put("content", if (channel == Channel.ANSWER) text else "ack")
                                    .put(
                                        "reasoning_content",
                                        if (channel == Channel.REASONING) text else "thinking",
                                    )
                                    .put(
                                        "tool_calls",
                                        if (channel == Channel.ARGUMENTS) {
                                            JSONArray().put(
                                                JSONObject()
                                                    .put("id", "call_1")
                                                    .put("type", "function")
                                                    .put(
                                                        "function",
                                                        JSONObject()
                                                            .put("name", "probe_echo")
                                                            .put(
                                                                "arguments",
                                                                JSONObject().put("value", text).toString(),
                                                            ),
                                                    ),
                                            )
                                        } else {
                                            JSONObject.NULL
                                        },
                                    ),
                            ),
                    ),
                )
                .toString()

            HostedProtocol.OPENAI_RESPONSES -> JSONObject()
                .put("model", config(protocol).model)
                .put("status", "completed")
                .put(
                    "output",
                    when (channel) {
                        Channel.REASONING -> JSONArray().put(
                            JSONObject()
                                .put("id", "rs_1")
                                .put("type", "reasoning")
                                .put("status", "completed")
                                .put("encrypted_content", text),
                        )

                        Channel.ANSWER -> JSONArray().put(
                            JSONObject()
                                .put("id", "msg_1")
                                .put("type", "message")
                                .put("status", "completed")
                                .put("role", "assistant")
                                .put(
                                    "content",
                                    JSONArray().put(
                                        JSONObject().put("type", "output_text").put("text", text),
                                    ),
                                ),
                        )

                        Channel.ARGUMENTS -> JSONArray().put(
                            JSONObject()
                                .put("id", "fc_1")
                                .put("type", "function_call")
                                .put("status", "completed")
                                .put("name", "probe_echo")
                                .put("call_id", "call_1")
                                .put("arguments", JSONObject().put("value", text).toString()),
                        )
                    },
                )
                .toString()
        }

    private fun scan(
        boundary: MaskedPromptBoundary,
        protocol: HostedProtocol,
        text: String,
        channel: Channel,
    ): HostedResponsePrivacyResult = boundary.scanInboundResponseJson(
        body = body(protocol, text, channel),
        protocol = protocol,
        httpStatus = 200,
        metadataPolicy = config(protocol).responseMetadataPolicy(),
    )

    @Test
    fun realBoundaryClassifiesProviderChannelsOnEveryProtocol() {
        val raw = "Salary 15,000,000 VND on 2026-10-25 to account ending 4821."
        val vault = SensitiveVault("s813-device-channel-boundary")
        vault.maskText(raw)
        val boundary = MaskedPromptBoundary(vault)
        for (protocol in HostedProtocol.values()) {
            // Provider thinking never refuses the response by itself.
            assertAccepted(scan(boundary, protocol, "⟦AMT-ab-1234⟧ and then 28,000 VND.", Channel.REASONING))
            assertAccepted(scan(boundary, protocol, raw, Channel.REASONING))
            // The user-visible answer is enforced before publication.
            val forgedAnswer = scan(boundary, protocol, "⟦AMT-ab-1234⟧", Channel.ANSWER)
            assertRefused(forgedAnswer, HostedResponsePrivacyRefusalReason.UNKNOWN_TOKEN)
            val rawAnswer = scan(boundary, protocol, raw, Channel.ANSWER)
            assertRefused(rawAnswer, HostedResponsePrivacyRefusalReason.DECODED_SENSITIVE_MATCH)
            // Tool-call arguments are enforced before dispatch.
            val forgedArguments = scan(boundary, protocol, "⟦AMT-ab-1234⟧", Channel.ARGUMENTS)
            assertRefused(forgedArguments, HostedResponsePrivacyRefusalReason.UNKNOWN_TOKEN)
            val rawArguments = scan(boundary, protocol, raw, Channel.ARGUMENTS)
            assertRefused(rawArguments, HostedResponsePrivacyRefusalReason.DECODED_SENSITIVE_MATCH)
            // The safe equivalent still parses, so this is a channel decision, not a blanket stop.
            assertAccepted(scan(boundary, protocol, "ALPHA-17", Channel.ANSWER))
        }
        // A genuinely truncated envelope still fails before any channel decision.
        val malformed = boundary.scanInboundResponseJson(
            body = """{"choices":[{"message":{"content":"safe"}}""",
            protocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS,
            httpStatus = 200,
        )
        assertRefused(malformed, HostedResponsePrivacyRefusalReason.INVALID_JSON)
        // Duplicate keys fail even when their values are safe.
        val duplicate = boundary.scanInboundResponseJson(
            body = """{"untrusted":"safe","untrusted":"safe"}""",
            protocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS,
            httpStatus = 200,
        )
        assertRefused(duplicate, HostedResponsePrivacyRefusalReason.DUPLICATE_KEY)
        // Excessive nesting is still refused.
        val deep = boundary.scanInboundResponseJson(
            body = """{"nested":${"[".repeat(65)}"safe"${"]".repeat(65)}}""",
            protocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS,
            httpStatus = 200,
        )
        assertRefused(deep, HostedResponsePrivacyRefusalReason.DEPTH_LIMIT)
        assertEquals("inbound scanning never mints a mapping", 3, vault.entryCount())
    }

    @Test
    fun realGatewayCarriesTheCompleteInventoryOnEveryTurn() = runBlocking {
        val raw = "Salary 15,000,000 VND on 2026-10-25 to account ending 4821."
        val vault = SensitiveVault("s813-device-inventory")
        val boundary = MaskedPromptBoundary(vault)
        val config = config(HostedProtocol.OPENAI_CHAT_COMPLETIONS)
        val transport = QueueTransport(
            listOf(
                reply(
                    """{"model":"${config.model}","choices":[{"message":{"role":"assistant","content":null,
                       "tool_calls":[{"id":"call_1","type":"function",
                         "function":{"name":"probe_echo","arguments":"{\"value\":\"ALPHA-17\"}"}}]},
                       "finish_reason":"tool_calls"}]}""",
                ),
                reply(
                    """{"model":"${config.model}","choices":[{"message":{"role":"assistant","content":"done"},
                       "finish_reason":"stop"}]}""",
                ),
            ),
        )
        val gateway = MaskedHostedGateway(HostedAdapters.create(config, transport), boundary)
        val conversation = gateway.begin("Extract one reading.", "Read: $raw")
        val first = gateway.generate(conversation, emptyList())
        val pending = (first as? HostedGenerationResult.Turn)?.turn?.conversation
        assertNotNull("the device round trip must return a continuable turn", pending)
        val next = gateway.continueWithToolResults(
            io.github.triet4p.aegisf.privacy.MaskedConversation(
                pending!!,
                gateway.boundary,
            ),
            listOf(HostedToolResult("call_1", """{"stored":"kept"}""")),
            AgentContextManager.tokenInventoryBlock(vault.issuedTokens()),
        )
        assertTrue(gateway.generate(next, emptyList()) is HostedGenerationResult.Turn)

        val initialBody = transport.requests[0].body
        val continuationBody = transport.requests[1].body
        val issued = vault.issuedTokens()
        assertTrue("the initial turn must list the issued tokens", issued.isNotEmpty())
        for (token in issued) {
            assertTrue("initial prompt misses $token", initialBody.contains(token.token))
            assertTrue("continuation prompt misses $token", continuationBody.contains(token.token))
        }
        for (literal in listOf("15,000,000", "2026-10-25", "4821")) {
            assertFalse("request leaks $literal", initialBody.contains(literal))
            assertFalse("request leaks $literal", continuationBody.contains(literal))
        }
        assertTrue(
            "the continuation turn must carry the inventory, body=$continuationBody",
            continuationBody.contains("issued_mask_token_inventory"),
        )
    }

    @Test
    fun realDeviceThoughtCallNeverDispatchesAndMixedContinuationStaysMasked() = runBlocking {
        val raw = "Salary 15,000,000 VND on 2026-10-25 to account ending 4821."
        val vault = SensitiveVault("s813-device-thought-mixed")
        vault.maskText(raw)
        val boundary = MaskedPromptBoundary(vault)
        val thoughtBody = JSONObject()
            .put("modelVersion", "gemini-3.5-flash-lite")
            .put(
                "candidates",
                JSONArray().put(
                    JSONObject()
                        .put(
                            "content",
                            JSONObject()
                                .put("role", "model")
                                .put(
                                    "parts",
                                    JSONArray()
                                        .put(
                                            JSONObject()
                                                .put(
                                                    "functionCall",
                                                    JSONObject()
                                                        .put("name", "probe_echo")
                                                        .put("args", JSONObject().put("value", "ALPHA-17"))
                                                        .put("id", "call_1"),
                                                )
                                                .put("thought", true)
                                                .put("thoughtSignature", "sig"),
                                        )
                                        .put(JSONObject().put("text", "planning")),
                                ),
                        )
                        .put("finishReason", "STOP"),
                ),
            )
            .toString()
        val thoughtConfig = config(HostedProtocol.GEMINI_GENERATE_CONTENT)
        val thoughtTransport = QueueTransport(listOf(reply(thoughtBody)))
        val thoughtGateway = MaskedHostedGateway(HostedAdapters.create(thoughtConfig, thoughtTransport), boundary)
        val thoughtTurn = thoughtGateway.generate(
            thoughtGateway.begin("Read the synthetic reading.", "Read it."),
            listOf(HostedToolDeclaration("probe_echo", "Echo one probe value.", JSONObject("""{"type":"object","properties":{"value":{"type":"string"}},"required":["value"],"additionalProperties":false}"""))),
        )
        val parsed = (thoughtTurn as HostedGenerationResult.Turn).turn
        assertEquals(HostedTurnFinish.Complete, parsed.finish)
        assertEquals("planning", parsed.assistantText)
        assertTrue(parsed.toolCalls.isEmpty())
        assertEquals(1, thoughtTransport.requests.size)

        val mixedBody = JSONObject()
            .put("model", "gpt-6-luna")
            .put("status", "completed")
            .put(
                "output",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("id", "rs_1")
                            .put("type", "reasoning")
                            .put("status", "completed")
                            .put(
                                "summary",
                                JSONArray().put(JSONObject().put("type", "summary_text").put("text", raw)),
                            )
                            .put("encrypted_content", "opaque-continuation-state"),
                    )
                    .put(
                        JSONObject()
                            .put("id", "fc_1")
                            .put("type", "function_call")
                            .put("status", "completed")
                            .put("name", "probe_echo")
                            .put("call_id", "call_1")
                            .put("arguments", JSONObject().put("value", "ALPHA-17").toString()),
                    ),
            )
            .toString()
        val closingBody = JSONObject()
            .put("model", "gpt-6-luna")
            .put("status", "completed")
            .put(
                "output",
                JSONArray().put(
                    JSONObject()
                        .put("id", "msg_2")
                        .put("type", "message")
                        .put("status", "completed")
                        .put("role", "assistant")
                        .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "done"))),
                ),
            )
            .toString()
        val responsesConfig = config(HostedProtocol.OPENAI_RESPONSES)
        val mixedTransport = QueueTransport(listOf(reply(mixedBody), reply(closingBody)))
        val mixedGateway = MaskedHostedGateway(HostedAdapters.create(responsesConfig, mixedTransport), boundary)
        val mixedConversation = mixedGateway.begin("Extract one reading.", "Read: $raw")
        val mixedTurn = (mixedGateway.generate(mixedConversation, emptyList()) as HostedGenerationResult.Turn).turn
        assertEquals(HostedTurnFinish.ToolCalls, mixedTurn.finish)
        val pending = mixedTurn.conversation ?: throw AssertionError("expected a continuable turn")
        val inventory = AgentContextManager.tokenInventoryBlock(vault.issuedTokens())
        val next = mixedGateway.continueWithToolResults(
            io.github.triet4p.aegisf.privacy.MaskedConversation(pending, boundary),
            listOf(HostedToolResult("call_1", boundary.maskToolResultJson("""{"stored":"kept"}"""))),
            inventory,
        )
        assertTrue(mixedGateway.generate(next, emptyList()) is HostedGenerationResult.Turn)
        val continuation = mixedTransport.requests[1].body
        val stripped = SensitiveVault.TOKEN_PATTERN.replace(continuation, " ")
        for (literal in listOf("15,000,000", "2026-10-25", "4821")) {
            assertFalse("device continuation leaks $literal", stripped.contains(literal))
        }
        assertFalse("device continuation replays the mapped raw", continuation.contains(raw))
        for (token in vault.issuedTokens()) {
            assertTrue("device continuation misses $token", continuation.contains(token.token))
        }
        assertTrue(continuation.contains("call_1"))
        assertTrue("device continuation must keep opaque state", continuation.contains("opaque-continuation-state"))
    }


    private fun assertAccepted(result: HostedResponsePrivacyResult) {
        assertTrue(
            "expected an accepted response, got $result",
            result is HostedResponsePrivacyResult.Accepted ||
                result is HostedResponsePrivacyResult.AcceptedWithValidatedCounters ||
                result is HostedResponsePrivacyResult.AcceptedWithValidatedMetadata,
        )
    }

    private fun assertRefused(result: HostedResponsePrivacyResult, reason: HostedResponsePrivacyRefusalReason) {
        assertTrue("expected a privacy refusal, got $result", result is HostedResponsePrivacyResult.Refused)
        assertEquals(reason, (result as HostedResponsePrivacyResult.Refused).refusal.reason)
    }

    private fun reply(json: String): HostedHttpResponse =
        HostedHttpResponse(status = 200, headers = emptyMap(), body = json)

    private companion object {
        const val SYNTHETIC_KEY = "synthetic-device-only-credential"
    }
}
