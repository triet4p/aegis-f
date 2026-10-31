package io.github.triet4p.aegisf.privacy

import io.github.triet4p.aegisf.agent.AgentContextManager
import io.github.triet4p.aegisf.provider.HostedAdapters
import io.github.triet4p.aegisf.provider.HostedProviderFailure
import io.github.triet4p.aegisf.provider.HostedGenerationResult
import io.github.triet4p.aegisf.provider.HostedProtocol
import io.github.triet4p.aegisf.provider.HostedResponseMetadataKind
import io.github.triet4p.aegisf.provider.HostedResponseMetadataPolicy
import io.github.triet4p.aegisf.provider.HostedResponsePrivacyRefusal
import io.github.triet4p.aegisf.provider.HostedResponsePrivacyRefusalReason
import io.github.triet4p.aegisf.provider.HostedResponsePrivacyResult
import io.github.triet4p.aegisf.provider.HostedResponseProtocolErrorReason
import io.github.triet4p.aegisf.provider.HostedResponseRecognizedCounter
import io.github.triet4p.aegisf.provider.HostedResponseValidatedMetadata
import io.github.triet4p.aegisf.provider.HostedHttpRequest
import io.github.triet4p.aegisf.provider.HostedHttpResponse
import io.github.triet4p.aegisf.provider.HostedToolResult
import io.github.triet4p.aegisf.provider.HttpTransport
import io.github.triet4p.aegisf.provider.ProviderFixtures
import kotlinx.coroutines.runBlocking
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
 * The S8.6 local masking boundary through the real provider path.
 *
 * A recording transport stands in for the network: the masked gateway drives the actual
 * adapters and their exact serialized requests are intercepted before they would leave. Every
 * test asserts on observed behavior — request bytes, stored rows, refused outcomes — never on
 * source text or wiring. Fixture values come from `tools/slm/s8_agent_fixture.json` capture
 * 118 (`Salary 15,000,000 VND on 2026-10-25 to account ending 4821.`); the capture text is
 * saved for real and the designated literals are the leak assertions.
 *
 * Covered: all three protocols carry no designated literal in any request; context, history
 * and tool results are masked; equality/order/relative-time survive in masked form; unknown,
 * cross-session, swapped, colliding and adversarial tokens refuse; overflow/ambiguity fails
 * closed; masker failure emits no request; rehydration stores exact values and spans.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MaskedBoundaryTest {

    private val captureText = "Salary 15,000,000 VND on 2026-10-25 to account ending 4821."
    private val rawLiterals = listOf("15,000,000", "15000000", "2026-10-25", "2026", "10-25", "4821")
    private val probePrompt =
        "Salary 15,000,000 VND on 2026-10-25 arrived to an account ending 4821. Mask the account number, keep the exact amount and date, and store the reading."

    private class RecordingTransport(vararg replies: HostedHttpResponse) : HttpTransport {
        private val queued = ArrayDeque(replies.toList())
        val requests = mutableListOf<HostedHttpRequest>()
        val responses = mutableListOf<HostedHttpResponse>()
        val bodies get() = requests.map { it.body }
        override suspend fun post(request: HostedHttpRequest): HostedHttpResponse {
            requests += request
            val response = queued.removeFirstOrNull()
                ?: throw IllegalStateException("no queued answer for request ${requests.size}")
            responses += response
            return response
        }
    }

    private fun gatewayOf(
        scopeId: String,
        transport: RecordingTransport,
        protocol: io.github.triet4p.aegisf.provider.HostedProtocol,
    ): MaskedHostedGateway {
        val config = when (protocol) {
            io.github.triet4p.aegisf.provider.HostedProtocol.GEMINI_GENERATE_CONTENT ->
                ProviderFixtures.geminiConfig()
            io.github.triet4p.aegisf.provider.HostedProtocol.OPENAI_CHAT_COMPLETIONS ->
                ProviderFixtures.chatConfig()
            io.github.triet4p.aegisf.provider.HostedProtocol.OPENAI_RESPONSES ->
                ProviderFixtures.responsesConfig()
        }
        return MaskedHostedGateway(
            HostedAdapters.create(config, transport),
            MaskedPromptBoundary(SensitiveVault(scopeId)),
        )
    }

    private fun responseRefusal(
        boundary: MaskedPromptBoundary,
        body: String,
        protocol: HostedProtocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS,
        httpStatus: Int? = 200,
        metadataPolicy: HostedResponseMetadataPolicy? = null,
    ): HostedResponsePrivacyRefusal {
        val result = boundary.scanInboundResponseJson(body, protocol, httpStatus, metadataPolicy)
        assertTrue(result is HostedResponsePrivacyResult.Refused)
        return (result as HostedResponsePrivacyResult.Refused).refusal
    }

    private fun assertResponseAccepted(
        boundary: MaskedPromptBoundary,
        body: String,
        protocol: HostedProtocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS,
        httpStatus: Int? = 200,
        metadataPolicy: HostedResponseMetadataPolicy? = null,
    ): HostedResponsePrivacyResult {
        val result = boundary.scanInboundResponseJson(body, protocol, httpStatus, metadataPolicy)
        assertTrue(
            result is HostedResponsePrivacyResult.Accepted ||
                result is HostedResponsePrivacyResult.AcceptedWithValidatedCounters ||
                result is HostedResponsePrivacyResult.AcceptedWithValidatedMetadata,
        )
        return result
    }

    private fun assertResponseRefused(
        boundary: MaskedPromptBoundary,
        body: String,
        reason: HostedResponsePrivacyRefusalReason,
        protocol: HostedProtocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS,
        httpStatus: Int? = 200,
        metadataPolicy: HostedResponseMetadataPolicy? = null,
    ) {
        assertEquals(reason, responseRefusal(boundary, body, protocol, httpStatus, metadataPolicy).reason)
    }

    private fun responseProtocolError(
        boundary: MaskedPromptBoundary,
        body: String,
        protocol: HostedProtocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS,
        httpStatus: Int? = 200,
    ): HostedResponsePrivacyResult.ProtocolError {
        val result = boundary.scanInboundResponseJson(body, protocol, httpStatus)
        assertTrue(result is HostedResponsePrivacyResult.ProtocolError)
        return result as HostedResponsePrivacyResult.ProtocolError
    }

    private fun maskedAmounts(gateway: MaskedHostedGateway, values: List<String>): List<String> =
        values.map { gateway.maskContext(it) }

    private fun assertNoRawLiteral(bodies: List<String>, where: String) {
        for (literal in rawLiterals) {
            for (body in bodies) {
                val stripped = SensitiveVault.TOKEN_PATTERN.replace(body, " ")
                assertFalse(
                    "request $where leaks designated literal '$literal': $body",
                    literal in stripped,
                )
            }
        }
    }

    @Test
    fun `all protocol requests carry no designated literal across prompt and continuation`() = runBlocking {
        for (protocol in io.github.triet4p.aegisf.provider.HostedProtocol.values()) {
            val (firstReply, secondReply) = repliesFor(protocol)
            val transport = RecordingTransport(firstReply, secondReply)
            val gateway = gatewayOf("s8.6-$protocol", transport, protocol)
            val conversation = gateway.begin("You extract readings.", probePrompt)
            val turn = when (
                val result = gateway.generate(conversation, listOf(ProviderFixtures.echoTool))
            ) {
                is HostedGenerationResult.Turn -> result.turn
                is HostedGenerationResult.ProviderFailure -> error("masked generate failed: ${result.failure}")
            }
            val pending = turn.conversation ?: error("expected a continuable turn for $protocol")
            val next = gateway.continueWithToolResults(
                MaskedConversation(pending, gateway.boundary),
                listOf(HostedToolResult(pending.pendingCallIds.single(), maskedObservation(gateway))),
            )
            gateway.generate(next, listOf(ProviderFixtures.echoTool))
            assertEquals(2, transport.requests.size)
            assertNoRawLiteral(transport.bodies, protocol.name.lowercase())
        }
    }

    @Test
    fun `masked context history and tool results carry relations without literals`() {
        val gateway = MaskedHostedGateway(
            HostedAdapters.create(ProviderFixtures.chatConfig(), RecordingTransport()),
            MaskedPromptBoundary(SensitiveVault("s8.6-relations")),
        )
        // Equality: the same amount/date pair masks identically with or without a trailer.
        val first = gateway.maskContext("Salary 15,000,000 VND on 2026-10-25")
        val second = gateway.maskContext("Salary 15,000,000 VND")
        val other = gateway.maskContext("Fuel 50,000 VND on 2026-10-20")
        assertEquals(first.substringBefore(" on "), second)
        val history = gateway.maskContext("$first then $other", MaskedPath.HISTORY)
        assertNoRawLiteral(listOf(first, second, other, history), "masked-context")
        val vault = gateway.boundary.vault
        val maskedAmounts = maskedAmounts(gateway, listOf("28,000 VND", "65,000 VND", "28,000 VND"))
        val amountTokens = maskedAmounts.map { SensitiveVault.TOKEN_PATTERN.find(it)!!.value }
        assertEquals(amountTokens[0], amountTokens[2])
        assertTrue(vault.compareAmounts(amountTokens[0], amountTokens[1]) < 0)
        val dateTexts = listOf(
            gateway.maskContext("on 2026-10-24"),
            gateway.maskContext("on 2026-10-25"),
            gateway.maskContext("on 2026-10-26"),
        )
        val dateTokens = dateTexts.map { SensitiveVault.TOKEN_PATTERN.find(it)!!.value }
        assertTrue(vault.compareDates(dateTokens[0], dateTokens[1]) < 0)
        assertTrue(vault.compareDates(dateTokens[1], dateTokens[2]) < 0)
        val toolJson = gateway.boundary.maskToolResultJson(
            """{"echoed":"Salary 15,000,000 VND on 2026-10-25 to 4821"}""",
        )
        assertNoRawLiteral(listOf(toolJson), "tool-result")
    }

    @Test
    fun `unknown cross-session and swapped tokens refuse before any effect`() {
        val vault = SensitiveVault("s8.6-local")
        val boundary = MaskedPromptBoundary(vault)
        val foreign = MaskedPromptBoundary(SensitiveVault("s8.6-foreign"))
        val amountToken = vault.maskText("28,000 VND")
        val dateToken = vault.maskText("2026-10-21")
        // Unknown token: well-formed shape, never minted here.
        assertTrue(boundary.scanInbound("⟦AMT-${vault.scopeTag}-9999⟧") is InboundScan.Refused)
        // Cross-session token minted for the same raw value elsewhere.
        val foreignToken = foreign.maskOutbound("28,000 VND", MaskedPath.CONTEXT)
        assertTrue(boundary.scanInbound(foreignToken) is InboundScan.Refused)
        // Swapped: an amount token is not valid for an account field.
        try {
            vault.rehydrateAccount(amountToken)
            fail("expected a swapped token to be refused")
        } catch (expected: TokenValidationException) {
            assertTrue(expected.message.orEmpty().isNotBlank())
        }
        // Raw literal emitted by the model is refused even with no tokens present.
        assertTrue(boundary.scanInbound("the amount is 28,000 VND") is InboundScan.Refused)
        // Collision: a token that names two raw shapes resolves to the first surface only.
        val dupA = vault.maskText("BANK 382K26A0 ref")
        val dupB = vault.maskText("382K26A0")
        assertTrue(boundary.scanInbound(dupA) is InboundScan.Accepted)
        assertTrue(boundary.scanInbound(dupB) is InboundScan.Accepted)
        assertEquals(dupA.substringAfter("BANK ").substringBefore(" ref"), dupB)
    }

    @Test
    fun `adversarial token syntax refuses without minting`() {
        val vault = SensitiveVault("s8.6-adversarial")
        val boundary = MaskedPromptBoundary(vault)
        val before = vault.entryCount()
        for (adversarial in listOf(
            "⟦AMT-other-scope-0001⟧",
            "⟦AMT-${vault.scopeTag}-9999⟧",
            "⟦AMT-${vault.scopeTag}-12⟧",
            "⟦EVIL-${vault.scopeTag}-0001⟧",
            "⟦UNMAPPED⟧",
            "⟦AMT-${vault.scopeTag}-0001⟧ extra ⟦AMT-${vault.scopeTag}-0001⟧",
        )) {
            assertTrue(
                "adversarial '$adversarial' must be refused",
                boundary.scanInbound("value $adversarial here") is InboundScan.Refused,
            )
        }
        assertEquals(before, vault.entryCount())
    }

    @Test
    fun `provider reasoning is not guard input while the answer stays enforced`() {
        val vault = SensitiveVault("s8.13-reasoning-token")
        val boundary = MaskedPromptBoundary(vault)
        val amountToken = vault.maskText("28,000 VND").substringBefore(' ')
        val foreignToken = MaskedPromptBoundary(SensitiveVault("s8.13-other-scope"))
            .maskOutbound("28,000 VND", MaskedPath.CONTEXT)
            .substringBefore(' ')
        fun chatBody(reasoning: String, answer: String = "ack"): String = JSONObject()
            .put("id", "req_1")
            .put("object", "chat.completion")
            .put("created", 1)
            .put("model", "m")
            .put("choices", JSONArray().put(JSONObject()
                .put("index", 0)
                .put("message", JSONObject()
                    .put("role", "assistant")
                    .put("content", answer)
                    .put("reasoning_content", reasoning))))
            .toString()

        // The provider's own thinking never blocks a response: arbitrary invented notation and
        // sensitive-looking raw text inside it are not guard input.
        val mutatedDigit = if (amountToken[amountToken.length - 2] == '9') '8' else '9'
        val mutated = amountToken.dropLast(2) + mutatedDigit + "⟧"
        for (reasoning in listOf(
            "I will reuse $mutated for the amount.",
            "Placeholder ⟦AMT-demo-1⟧ stands for the amount.",
            "The syntax is ⟦token⟧ and ⟦UNMAPPED⟧ in this interface.",
            "A cross-session value like $foreignToken must never appear.",
            "The raw amount is 28,000 VND and the account is 4821.",
            "The observed amount is $amountToken in the source.",
        )) {
            assertResponseAccepted(boundary, chatBody(reasoning))
        }
        // The same invalid content in the user-visible answer remains blocked.
        assertResponseRefused(
            boundary,
            chatBody("thinking", answer = "I will reuse $mutated for the amount."),
            HostedResponsePrivacyRefusalReason.UNKNOWN_TOKEN,
        )
        assertResponseRefused(
            boundary,
            chatBody("thinking", answer = "Placeholder ⟦AMT-demo-1⟧ stands for the amount."),
            HostedResponsePrivacyRefusalReason.UNKNOWN_TOKEN,
        )
        assertResponseRefused(
            boundary,
            chatBody("thinking", answer = "A cross-session value like $foreignToken must never appear."),
            HostedResponsePrivacyRefusalReason.FOREIGN_TOKEN,
        )
        assertResponseRefused(
            boundary,
            chatBody("thinking", answer = "The raw amount is 28,000 VND."),
            HostedResponsePrivacyRefusalReason.DECODED_SENSITIVE_MATCH,
        )
        // Naming the concept without bracket syntax stays acceptable.
        assertResponseAccepted(
            boundary,
            chatBody("thinking", answer = "The masked amount token is shown in the source text."),
        )
        assertEquals("no refusal may mint or rehydrate a value", 1, vault.entryCount())
    }

    @Test
    fun `overflow and ambiguous rehydration fail closed`() {
        val vault = SensitiveVault("s8.6-overflow", maxEntries = 2)
        val boundary = MaskedPromptBoundary(vault)
        vault.maskText("28,000 VND")
        vault.maskText("2026-10-21")
        try {
            vault.maskText("65,000 VND")
            fail("expected the full vault to refuse")
        } catch (expected: MaskingBlockedException) {
            assertTrue(expected.message.orEmpty().isNotBlank())
        }
        // A capture whose masked span carries an unknown value refuses instead of minting.
        val scope = io.github.triet4p.aegisf.data.WorkflowCallerScope.AgentSession(
            scopeId = "s8.6-overflow",
            captureIds = setOf(118L),
            draftIds = emptySet(),
        )
        try {
            MaskedApplicationEffect.rehydrateInterpretation(
                scope = scope,
                boundary = boundary,
                captureId = 118L,
                captureText = "Lunch 99,999 VND at noon.",
                spanStart = 6,
                spanEnd = 20,
                maskedSpanText = "Lunch ⟦AMT-${vault.scopeTag}-0099⟧ at noon.",
                masked = MaskedInterpretation(
                    normalizedText = "Lunch",
                    amountToken = null,
                    dateToken = null,
                    timeToken = null,
                    spanSourceStart = 6,
                    spanSourceEnd = 20,
                ),
            )
            fail("expected ambiguous rehydration to be refused")
        } catch (expected: TokenValidationException) {
            assertTrue(expected.message.orEmpty().isNotBlank())
        }
    }

    @Test
    fun `masker failure emits no network request`() = runBlocking {
        val vault = SensitiveVault("s8.6-failure", maxEntries = 1)
        val boundary = MaskedPromptBoundary(vault)
        vault.maskText("28,000 VND")
        val transport = RecordingTransport()
        val gateway = MaskedHostedGateway(
            HostedAdapters.create(ProviderFixtures.chatConfig(), transport),
            boundary,
        )
        val amount = "65,000 VND and 70,000 VND and 80,000 VND and 90,000 VND"
        try {
            gateway.maskContext("Fuel $amount on 2026-10-20", MaskedPath.CONTEXT)
            fail("expected the full vault to refuse masking")
        } catch (expected: MaskingBlockedException) {
            assertTrue(expected.message.orEmpty().isNotBlank())
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `unparsed raw response fields are refused before tool continuation`() = runBlocking {
        val rawSource = "Salary 15,000,000 VND on 2026-10-25 to account ending 4821."
        for (protocol in io.github.triet4p.aegisf.provider.HostedProtocol.values()) {
            val transport = RecordingTransport(
                ProviderFixtures.reply(privacyResponseWithHiddenRawValue(protocol, rawSource)),
            )
            val gateway = gatewayOf("s8.13-raw-response-$protocol", transport, protocol)
            val conversation = gateway.begin("Read synthetic data.", rawSource)

            val result = gateway.generate(conversation, listOf(ProviderFixtures.echoTool))

            assertTrue(result is HostedGenerationResult.ProviderFailure)
            val failure = (result as HostedGenerationResult.ProviderFailure).failure
            assertTrue(failure is HostedProviderFailure.PrivacyBlocked)
            val refusal = (failure as HostedProviderFailure.PrivacyBlocked).refusal
            assertEquals(HostedResponsePrivacyRefusalReason.DECODED_SENSITIVE_MATCH, refusal.reason)
            assertTrue(refusal.sensitiveKind == "AMOUNT" || refusal.sensitiveKind == "DATE" ||
                refusal.sensitiveKind == "TIME" || refusal.sensitiveKind == "ACCOUNT" ||
                refusal.sensitiveKind == "BANK")
            assertTrue(refusal.safeLocation.startsWith("response_body"))
            assertFalse(rawSource in refusal.safeLocation)
            assertEquals(1, transport.requests.size)
        }
    }

    @Test
    fun `gateway accepts invented reasoning notation and blocks the same answer content`() = runBlocking {
        val vault = SensitiveVault("s8.13-gateway-reasoning")
        val issued = vault.maskText("28,000 VND").substringBefore(' ')
        val boundary = MaskedPromptBoundary(vault)
        fun body(reasoning: String, content: String = "ack"): String = JSONObject()
            .put("id", "chatcmpl-1")
            .put("object", "chat.completion")
            .put("created", 1)
            .put("model", ProviderFixtures.chatConfig().model)
            .put("choices", JSONArray().put(JSONObject()
                .put("index", 0)
                .put("finish_reason", "stop")
                .put("message", JSONObject()
                    .put("role", "assistant")
                    .put("content", content)
                    .put("reasoning_content", reasoning))))
            .toString()
        fun gateway(transport: RecordingTransport) = MaskedHostedGateway(
            HostedAdapters.create(ProviderFixtures.chatConfig(), transport),
            boundary,
        )

        // Invented, foreign and raw-looking notation in the provider's own thinking parses.
        val acceptedTransport = RecordingTransport(
            ProviderFixtures.reply(body("I will reuse ⟦AMT-ab-1234⟧ and pay 28,000 VND.")),
        )
        val acceptedGateway = gateway(acceptedTransport)
        val turn = ProviderFixtures.turn(
            acceptedGateway.generate(
                acceptedGateway.begin("Read synthetic data.", "Lunch 28,000 VND"),
                emptyList(),
            ),
        )
        assertEquals(io.github.triet4p.aegisf.provider.HostedTurnFinish.Complete, turn.finish)
        assertEquals("ack", turn.assistantText)
        // Reasoning is neither promoted into the answer nor replayed as answer text.
        assertFalse(turn.assistantText!!.contains("⟦"))
        assertFalse(turn.assistantText!!.contains("28,000"))
        assertEquals(1, acceptedTransport.requests.size)

        // The same forged token in the final answer is refused before publication.
        val refusedTransport = RecordingTransport(
            ProviderFixtures.reply(body("thinking", "I will reuse ⟦AMT-ab-1234⟧ for the amount.")),
        )
        val refusedGateway = gateway(refusedTransport)
        val refused = refusedGateway.generate(
            refusedGateway.begin("Read synthetic data.", "Lunch 28,000 VND"),
            emptyList(),
        )
        val failure = ProviderFixtures.failure(refused)
        assertTrue(failure is HostedProviderFailure.PrivacyBlocked)
        assertEquals(
            HostedResponsePrivacyRefusalReason.UNKNOWN_TOKEN,
            (failure as HostedProviderFailure.PrivacyBlocked).refusal.reason,
        )
        assertEquals("response_body.choices[].message.content",
            failure.refusal.safeLocation)
        assertEquals(1, refusedTransport.requests.size)

        // A sensitive raw value in the answer is refused too, while the issued token is fine.
        val leakedTransport = RecordingTransport(
            ProviderFixtures.reply(body("thinking", "It cost 28,000 VND.")),
        )
        val leakedGateway = gateway(leakedTransport)
        val leaked = leakedGateway.generate(
            leakedGateway.begin("Read synthetic data.", "Lunch 28,000 VND"),
            emptyList(),
        )
        assertEquals(
            HostedResponsePrivacyRefusalReason.DECODED_SENSITIVE_MATCH,
            (ProviderFixtures.failure(leaked) as HostedProviderFailure.PrivacyBlocked).refusal.reason,
        )
        val issuedTransport = RecordingTransport(
            ProviderFixtures.reply(body("thinking", "The observed amount is $issued.")),
        )
        val issuedGateway = gateway(issuedTransport)
        val issuedTurn = ProviderFixtures.turn(
            issuedGateway.generate(
                issuedGateway.begin("Read synthetic data.", "Lunch 28,000 VND"),
                emptyList(),
            ),
        )
        assertEquals(io.github.triet4p.aegisf.provider.HostedTurnFinish.Complete, issuedTurn.finish)
    }

    @Test
    fun `duplicate and malformed provider JSON are refused before parsing`() = runBlocking {
        val rawSource = "Salary 15,000,000 VND on 2026-10-25 to account ending 4821."
        val bodies = listOf(
            """{"untrusted":"safe","untrusted":"safe"}""",
            """{"untrusted":""",
        )
        for (protocol in io.github.triet4p.aegisf.provider.HostedProtocol.values()) {
            for ((index, body) in bodies.withIndex()) {
                val transport = RecordingTransport(ProviderFixtures.reply(body))
                val gateway = gatewayOf("s8.13-invalid-json-$protocol-$index", transport, protocol)
                val conversation = gateway.begin("Read synthetic data.", rawSource)

                val result = gateway.generate(conversation, listOf(ProviderFixtures.echoTool))

                assertTrue(result is HostedGenerationResult.ProviderFailure)
                val failure = (result as HostedGenerationResult.ProviderFailure).failure
                assertTrue(failure is HostedProviderFailure.PrivacyBlocked)
                val expectedReason = if (index == 0) {
                    HostedResponsePrivacyRefusalReason.DUPLICATE_KEY
                } else {
                    HostedResponsePrivacyRefusalReason.INVALID_JSON
                }
                assertEquals(
                    expectedReason,
                    (failure as HostedProviderFailure.PrivacyBlocked).refusal.reason,
                )
                assertEquals(1, transport.requests.size)
            }
        }
    }

    @Test
    fun `provider adapter scans original serialized response before parsing`() = runBlocking {
        val rawSource = "account ending 4821"
        val response = ProviderFixtures.reply(
            """{"service_tier":"\u4821","model":"deepseek-v4.1-flash","choices":[{"message":{"role":"assistant","content":"safe"},"finish_reason":"stop"}]}""",
        )
        val transport = RecordingTransport(response)
        val gateway = gatewayOf(
            "s8.13-raw-escape-adapter",
            transport,
            io.github.triet4p.aegisf.provider.HostedProtocol.OPENAI_CHAT_COMPLETIONS,
        )
        val conversation = gateway.begin("Read synthetic data.", rawSource)

        assertTrue(gateway.boundary.vault.scanLeak(response.body))
        assertResponseAccepted(gateway.boundary, response.body)
        val result = gateway.generate(conversation, emptyList())

        assertTrue(result is HostedGenerationResult.Turn)
        assertTrue(transport.responses.single() === response)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `documented root metadata is schema validated before any exemption`() {
        val vault = SensitiveVault("s8.13-root-metadata")
        vault.maskText("account ending 7319")
        val boundary = MaskedPromptBoundary(vault)
        val goPolicy = HostedResponseMetadataPolicy(
            protocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS,
            configuredModel = ProviderFixtures.chatConfig().model,
            goUsageExtensions = true,
        )
        val nonGoPolicy = goPolicy.copy(goUsageExtensions = false)

        val root = assertResponseAccepted(
            boundary,
            """{"id":"chatcmpl-7319","object":"chat.completion","created":1750000000,"model":"deepseek-v4.1-flash"}""",
            metadataPolicy = goPolicy,
        )
        assertTrue(root is HostedResponsePrivacyResult.AcceptedWithValidatedMetadata)
        assertEquals(
            listOf(
                HostedResponseValidatedMetadata(
                    HostedResponseMetadataKind.PROVIDER_ID,
                    "response_body.id",
                    "STRING",
                ),
                HostedResponseValidatedMetadata(
                    HostedResponseMetadataKind.OBJECT_DISCRIMINATOR,
                    "response_body.object",
                    "STRING",
                ),
                HostedResponseValidatedMetadata(
                    HostedResponseMetadataKind.TIMESTAMP,
                    "response_body.created",
                    "NUMBER",
                ),
                HostedResponseValidatedMetadata(
                    HostedResponseMetadataKind.MODEL_ECHO,
                    "response_body.model",
                    "STRING",
                ),
            ),
            (root as HostedResponsePrivacyResult.AcceptedWithValidatedMetadata).metadata,
        )

        val accountingBody = """{"usage":{"opencode":{"allowance":7319.5,"list_cost":7319}}}"""
        val accounting = assertResponseAccepted(boundary, accountingBody, metadataPolicy = goPolicy)
        assertTrue(accounting is HostedResponsePrivacyResult.AcceptedWithValidatedMetadata)
        assertEquals(
            listOf(
                "response_body.usage.opencode.allowance",
                "response_body.usage.opencode.list_cost",
            ),
            (accounting as HostedResponsePrivacyResult.AcceptedWithValidatedMetadata)
                .metadata
                .map { it.safeLocation },
        )
        assertTrue(accounting.metadata.all { it.kind == HostedResponseMetadataKind.GO_USAGE_AMOUNT })
        assertTrue(accounting.metadata.all { it.nodeType == "NUMBER" })

        val nonGoRefusal = boundary.scanInboundResponseJson(
            accountingBody,
            HostedProtocol.OPENAI_CHAT_COMPLETIONS,
            200,
            nonGoPolicy,
        )
        assertTrue(nonGoRefusal is HostedResponsePrivacyResult.Refused)
        val nonGo = (nonGoRefusal as HostedResponsePrivacyResult.Refused).refusal
        assertEquals(HostedResponsePrivacyRefusalReason.DECODED_SENSITIVE_MATCH, nonGo.reason)
        assertEquals("response_body.usage.opencode.allowance", nonGo.safeLocation)
        assertEquals("NUMBER", nonGo.nodeType)
        assertEquals("ACCOUNT", nonGo.sensitiveKind)

        val malformedBodies = listOf(
            """{"id":7319}""",
            """{"id":""}""",
            """{"id":"caf\u00e9"}""",
            """{"created":"1750000000"}""",
            """{"created":-1}""",
            """{"created":1.5}""",
            """{"object":"chat.completion.chunk"}""",
            """{"model":7319}""",
            """{"usage":{"opencode":{"allowance":"7319"}}}""",
            """{"usage":{"opencode":{"allowance":-7319}}}""",
            """{"usage":{"opencode":{"list_cost":1e999}}}""",
            """{"usage":{"opencode":{"list_cost":true}}}""",
        )
        for (body in malformedBodies) {
            val result = boundary.scanInboundResponseJson(
                body,
                HostedProtocol.OPENAI_CHAT_COMPLETIONS,
                200,
                goPolicy,
            )
            assertTrue("expected a protocol error for $body", result is HostedResponsePrivacyResult.ProtocolError)
            assertEquals(
                HostedResponseProtocolErrorReason.INVALID_PROTOCOL_METADATA,
                (result as HostedResponsePrivacyResult.ProtocolError).reason,
            )
        }

        assertResponseRefused(
            boundary,
            """{"choices":[{"message":{"id":"7319"}}]}""",
            HostedResponsePrivacyRefusalReason.DECODED_SENSITIVE_MATCH,
            metadataPolicy = goPolicy,
        )
        assertResponseRefused(
            boundary,
            """{"model":"other-model-7319"}""",
            HostedResponsePrivacyRefusalReason.DECODED_SENSITIVE_MATCH,
            metadataPolicy = goPolicy,
        )
        assertResponseRefused(
            boundary,
            """{"id":"chatcmpl-7319","id":"chatcmpl-7319"}""",
            HostedResponsePrivacyRefusalReason.DUPLICATE_KEY,
            metadataPolicy = goPolicy,
        )
    }

    @Test
    fun `metadata collision passes only at validated root fields through the real adapters`() = runBlocking {
        val source = "account ending 7319"
        val acceptedBody =
            """{"id":"chatcmpl-7319","object":"chat.completion","created":1750000000,"model":"deepseek-v4.1-flash","choices":[{"message":{"role":"assistant","content":"safe"},"finish_reason":"stop"}],"usage":{"opencode":{"allowance":7319.5,"list_cost":7319}}}"""
        val acceptedTransport = RecordingTransport(ProviderFixtures.reply(acceptedBody))
        val acceptedGateway = gatewayOf(
            "s8.13-root-metadata-adapter",
            acceptedTransport,
            HostedProtocol.OPENAI_CHAT_COMPLETIONS,
        )
        val turn = ProviderFixtures.turn(
            acceptedGateway.generate(
                acceptedGateway.begin("Read synthetic data.", source),
                emptyList(),
            ),
        )
        assertEquals("safe", turn.assistantText)
        assertEquals(1, acceptedTransport.requests.size)

        val contentBody =
            """{"id":"chatcmpl-7319","model":"deepseek-v4.1-flash","choices":[{"message":{"role":"assistant","content":"7319"},"finish_reason":"stop"}]}"""
        val contentTransport = RecordingTransport(ProviderFixtures.reply(contentBody))
        val contentGateway = gatewayOf(
            "s8.13-root-metadata-content-block",
            contentTransport,
            HostedProtocol.OPENAI_CHAT_COMPLETIONS,
        )
        val contentResult = contentGateway.generate(
            contentGateway.begin("Read synthetic data.", source),
            emptyList(),
        )
        assertTrue(contentResult is HostedGenerationResult.ProviderFailure)
        val contentFailure = (contentResult as HostedGenerationResult.ProviderFailure).failure
        assertTrue(contentFailure is HostedProviderFailure.PrivacyBlocked)
        assertEquals(
            "response_body.choices[].message.content",
            (contentFailure as HostedProviderFailure.PrivacyBlocked).refusal.safeLocation,
        )
        assertEquals(1, contentTransport.requests.size)

        val toolBody =
            """{"id":"chatcmpl-7319","model":"deepseek-v4.1-flash","choices":[{"message":{"role":"assistant","tool_calls":[{"id":"call-1","type":"function","function":{"name":"probe_echo","arguments":"{\"value\":\"7319\"}"}}]},"finish_reason":"tool_calls"}]}"""
        val toolTransport = RecordingTransport(ProviderFixtures.reply(toolBody))
        val toolGateway = gatewayOf(
            "s8.13-root-metadata-tool-block",
            toolTransport,
            HostedProtocol.OPENAI_CHAT_COMPLETIONS,
        )
        val toolResult = toolGateway.generate(
            toolGateway.begin("Read synthetic data.", source),
            listOf(ProviderFixtures.echoTool),
        )
        assertTrue(toolResult is HostedGenerationResult.ProviderFailure)
        val toolFailure = (toolResult as HostedGenerationResult.ProviderFailure).failure
        assertTrue(toolFailure is HostedProviderFailure.PrivacyBlocked)
        assertEquals(
            "response_body.choices[].message.tool_calls[].other_field.arguments",
            (toolFailure as HostedProviderFailure.PrivacyBlocked).refusal.safeLocation,
        )

        val malformedTransport = RecordingTransport(
            ProviderFixtures.reply("""{"id":7319,"model":"deepseek-v4.1-flash","choices":[]}"""),
        )
        val malformedGateway = gatewayOf(
            "s8.13-root-metadata-protocol-error",
            malformedTransport,
            HostedProtocol.OPENAI_CHAT_COMPLETIONS,
        )
        val malformedResult = malformedGateway.generate(
            malformedGateway.begin("Read synthetic data.", source),
            emptyList(),
        )
        assertTrue(malformedResult is HostedGenerationResult.ProviderFailure)
        assertEquals(
            HostedProviderFailure.InvalidResponse("invalid provider protocol metadata"),
            (malformedResult as HostedGenerationResult.ProviderFailure).failure,
        )

        val nonGoTransport = RecordingTransport(ProviderFixtures.reply(acceptedBody))
        val nonGoGateway = MaskedHostedGateway(
            HostedAdapters.create(
                ProviderFixtures.chatConfig().copy(endpoint = "https://api.openai.com/v1/chat/completions"),
                nonGoTransport,
            ),
            MaskedPromptBoundary(SensitiveVault("s8.13-root-metadata-non-go")),
        )
        val nonGoResult = nonGoGateway.generate(
            nonGoGateway.begin("Read synthetic data.", source),
            emptyList(),
        )
        assertTrue(nonGoResult is HostedGenerationResult.ProviderFailure)
        val nonGoFailure = (nonGoResult as HostedGenerationResult.ProviderFailure).failure
        assertTrue(nonGoFailure is HostedProviderFailure.PrivacyBlocked)
        assertEquals(
            "response_body.usage.opencode.allowance",
            (nonGoFailure as HostedProviderFailure.PrivacyBlocked).refusal.safeLocation,
        )
    }

    @Test
    fun `validated usage counters pass the adapter while other response values remain guarded`() = runBlocking {
        val source = "account ending 7319"
        val cleanResponse = ProviderFixtures.reply(
            """{"model":"deepseek-v4.1-flash","choices":[{"message":{"role":"assistant","content":"safe"},"finish_reason":"stop"}],"usage":{"total_tokens":7319}}""",
        )
        val cleanTransport = RecordingTransport(cleanResponse)
        val cleanGateway = gatewayOf(
            "s8.13-usage-counter-adapter",
            cleanTransport,
            HostedProtocol.OPENAI_CHAT_COMPLETIONS,
        )
        val cleanResult = cleanGateway.generate(
            cleanGateway.begin("Read synthetic data.", source),
            emptyList(),
        )
        assertTrue(cleanResult is HostedGenerationResult.Turn)
        assertTrue(cleanTransport.responses.single() === cleanResponse)
        assertEquals(1, cleanTransport.requests.size)

        val leakingResponse = ProviderFixtures.reply(
            """{"model":"deepseek-v4.1-flash","choices":[{"message":{"role":"assistant","content":"7319"},"finish_reason":"stop"}],"usage":{"total_tokens":7319}}""",
        )
        val leakingTransport = RecordingTransport(leakingResponse)
        val leakingGateway = gatewayOf(
            "s8.13-usage-counter-content-block",
            leakingTransport,
            HostedProtocol.OPENAI_CHAT_COMPLETIONS,
        )
        val leakingResult = leakingGateway.generate(
            leakingGateway.begin("Read synthetic data.", source),
            emptyList(),
        )
        assertTrue(leakingResult is HostedGenerationResult.ProviderFailure)
        val privacyFailure = (leakingResult as HostedGenerationResult.ProviderFailure).failure
        assertTrue(privacyFailure is HostedProviderFailure.PrivacyBlocked)
        val refusal = (privacyFailure as HostedProviderFailure.PrivacyBlocked).refusal
        assertEquals(HostedResponsePrivacyRefusalReason.DECODED_SENSITIVE_MATCH, refusal.reason)
        assertEquals("response_body.choices[].message.content", refusal.safeLocation)
        assertFalse(refusal.recognizedCounter)
        assertTrue(leakingTransport.responses.single() === leakingResponse)
        assertEquals(1, leakingTransport.requests.size)

        val invalidResponse = ProviderFixtures.reply(
            """{"model":"deepseek-v4.1-flash","choices":[{"message":{"role":"assistant","content":"safe"},"finish_reason":"stop"}],"usage":{"total_tokens":"12"}}""",
        )
        val invalidTransport = RecordingTransport(invalidResponse)
        val invalidGateway = gatewayOf(
            "s8.13-usage-counter-protocol-error",
            invalidTransport,
            HostedProtocol.OPENAI_CHAT_COMPLETIONS,
        )
        val invalidResult = invalidGateway.generate(
            invalidGateway.begin("Read synthetic data.", source),
            emptyList(),
        )
        assertTrue(invalidResult is HostedGenerationResult.ProviderFailure)
        assertEquals(
            HostedProviderFailure.InvalidResponse("invalid provider usage counter"),
            (invalidResult as HostedGenerationResult.ProviderFailure).failure,
        )
        assertTrue(invalidTransport.responses.single() === invalidResponse)
        assertEquals(1, invalidTransport.requests.size)
    }

    @Test
    fun `response JSON scans decoded values and refuses ambiguous objects`() {
        val vault = SensitiveVault("s8.13-response-json")
        val token = vault.maskText("account ending 7319")
        val boundary = MaskedPromptBoundary(vault)

        assertTrue(vault.scanLeak("\\u7319"))
        assertResponseAccepted(boundary, """{"id":"\u7319"}""")
        assertResponseAccepted(boundary, """{"content":"$token"}""")
        assertResponseRefused(
            boundary,
            """{"content":"\u0037\u0033\u0031\u0039"}""",
            HostedResponsePrivacyRefusalReason.DECODED_SENSITIVE_MATCH,
        )
        assertResponseAccepted(boundary, """{"flag":true,"nothing":null}""")
        assertResponseRefused(
            boundary,
            """{"token":"⟦ACCT-OTHER1234-9999⟧"}""",
            HostedResponsePrivacyRefusalReason.FOREIGN_TOKEN,
        )
        assertResponseRefused(
            boundary,
            """{"token":"⟦UNKNOWN-TOKEN⟧"}""",
            HostedResponsePrivacyRefusalReason.UNKNOWN_TOKEN,
        )

        val contentRefusal = responseRefusal(
            boundary,
            """{"choices":[{"message":{"content":"account ending 7319"}}]}""",
        )
        assertEquals(HostedResponsePrivacyRefusalReason.DECODED_SENSITIVE_MATCH, contentRefusal.reason)
        assertEquals("ACCOUNT", contentRefusal.sensitiveKind)
        assertEquals("response_body.choices[].message.content", contentRefusal.safeLocation)
        assertResponseRefused(
            boundary,
            """{"provider_meta":{"created":"account ending 7319"}}""",
            HostedResponsePrivacyRefusalReason.DECODED_SENSITIVE_MATCH,
        )
        // Provider reasoning is not guard input: a sensitive-looking value there is accepted.
        assertResponseAccepted(boundary, """{"choices":[{"message":{"reasoning_content":"7319"}}]}""")
        val knownCounter = assertResponseAccepted(boundary, """{"usage":{"total_tokens":7319}}""")
        assertTrue(knownCounter is HostedResponsePrivacyResult.AcceptedWithValidatedCounters)
        val knownMatch =
            (knownCounter as HostedResponsePrivacyResult.AcceptedWithValidatedCounters).matches.single()
        assertEquals(
            HostedResponseRecognizedCounter("ACCOUNT", "response_body.usage.total_tokens"),
            knownMatch,
        )
        val unknownUsageRefusal = responseRefusal(boundary, """{"usage":{"unlisted":7319}}""")
        assertEquals(HostedResponsePrivacyRefusalReason.DECODED_SENSITIVE_MATCH, unknownUsageRefusal.reason)
        assertEquals("NUMBER", unknownUsageRefusal.nodeType)
        assertFalse(unknownUsageRefusal.recognizedCounter)
        assertEquals("response_body.usage.unlisted", unknownUsageRefusal.safeLocation)
        val stringCounterRefusal =
            responseRefusal(boundary, """{"usage":{"total_tokens":"7319"}}""")
        assertEquals(HostedResponsePrivacyRefusalReason.DECODED_SENSITIVE_MATCH, stringCounterRefusal.reason)
        assertEquals("STRING", stringCounterRefusal.nodeType)
        assertFalse(stringCounterRefusal.recognizedCounter)
        val safeStringCounterError =
            responseProtocolError(boundary, """{"usage":{"total_tokens":"12"}}""")
        assertEquals(HostedResponseProtocolErrorReason.INVALID_USAGE_COUNTER, safeStringCounterError.reason)
        assertEquals("STRING", safeStringCounterError.nodeType)
        assertFalse(safeStringCounterError.recognizedCounter)
        val negativeCounterError =
            responseProtocolError(boundary, """{"usage":{"total_tokens":-1}}""")
        assertEquals(HostedResponseProtocolErrorReason.INVALID_USAGE_COUNTER, negativeCounterError.reason)
        assertResponseRefused(
            boundary,
            """{"usage":{"total_tokens":-7319}}""",
            HostedResponsePrivacyRefusalReason.DECODED_SENSITIVE_MATCH,
        )
        assertResponseRefused(
            boundary,
            """{"usage":{"total_tokens":7319}}""",
            HostedResponsePrivacyRefusalReason.DECODED_SENSITIVE_MATCH,
            HostedProtocol.OPENAI_CHAT_COMPLETIONS,
            httpStatus = 500,
        )
        assertResponseRefused(
            boundary,
            """{"created":7319}""",
            HostedResponsePrivacyRefusalReason.DECODED_SENSITIVE_MATCH,
        )
        assertResponseRefused(
            boundary,
            """{"unlisted":{"payload":"7319"}}""",
            HostedResponsePrivacyRefusalReason.DECODED_SENSITIVE_MATCH,
        )
        val keyRefusal = responseRefusal(boundary, """{"7319":"safe"}""")
        assertEquals(HostedResponsePrivacyRefusalReason.DECODED_SENSITIVE_MATCH, keyRefusal.reason)
        assertEquals("ACCOUNT", keyRefusal.sensitiveKind)
        assertEquals("response_body.field_name", keyRefusal.safeLocation)
        assertResponseRefused(
            boundary,
            """{"id":"safe","id":"safe"}""",
            HostedResponsePrivacyRefusalReason.DUPLICATE_KEY,
        )
        assertResponseRefused(
            boundary,
            """{"id":"safe","\u0069d":"safe"}""",
            HostedResponsePrivacyRefusalReason.DUPLICATE_KEY,
        )
        assertResponseRefused(
            boundary,
            """{"id":}""",
            HostedResponsePrivacyRefusalReason.INVALID_JSON,
        )
        assertResponseRefused(
            boundary,
            """{"id":"safe"} trailing""",
            HostedResponsePrivacyRefusalReason.INVALID_JSON,
        )
        assertResponseRefused(
            boundary,
            """["7319"]""",
            HostedResponsePrivacyRefusalReason.UNSUPPORTED_ROOT,
        )
        val deepJson = """{"nested":${"[".repeat(65)}"safe"${"]".repeat(65)}}"""
        assertResponseRefused(
            boundary,
            deepJson,
            HostedResponsePrivacyRefusalReason.DEPTH_LIMIT,
        )
    }

    @Test
    fun `only protocol-valid numeric usage counters bypass sensitive scanning`() {
        val vault = SensitiveVault("s8.13-validated-usage-counter")
        vault.maskText("account ending 7319")
        val boundary = MaskedPromptBoundary(vault)
        val recognizedCases = listOf(
            Triple(
                HostedProtocol.OPENAI_CHAT_COMPLETIONS,
                """{"usage":{"prompt_tokens":7319,"completion_tokens":7319,"total_tokens":7319,"completion_tokens_details":{"reasoning_tokens":7319},"prompt_tokens_details":{"cached_tokens":7319}}}""",
                setOf(
                    "response_body.usage.prompt_tokens",
                    "response_body.usage.completion_tokens",
                    "response_body.usage.total_tokens",
                    "response_body.usage.completion_tokens_details.reasoning_tokens",
                    "response_body.usage.prompt_tokens_details.cached_tokens",
                ),
            ),
            Triple(
                HostedProtocol.OPENAI_RESPONSES,
                """{"usage":{"input_tokens":7319,"output_tokens":7319,"total_tokens":7319,"output_tokens_details":{"reasoning_tokens":7319},"input_tokens_details":{"cached_tokens":7319}}}""",
                setOf(
                    "response_body.usage.input_tokens",
                    "response_body.usage.output_tokens",
                    "response_body.usage.total_tokens",
                    "response_body.usage.output_tokens_details.reasoning_tokens",
                    "response_body.usage.input_tokens_details.cached_tokens",
                ),
            ),
            Triple(
                HostedProtocol.GEMINI_GENERATE_CONTENT,
                """{"usageMetadata":{"promptTokenCount":7319,"candidatesTokenCount":7319,"thoughtsTokenCount":7319,"cachedContentTokenCount":7319}}""",
                setOf(
                    "response_body.usageMetadata.promptTokenCount",
                    "response_body.usageMetadata.candidatesTokenCount",
                    "response_body.usageMetadata.thoughtsTokenCount",
                    "response_body.usageMetadata.cachedContentTokenCount",
                ),
            ),
        )
        for ((protocol, body, expectedPaths) in recognizedCases) {
            val result = assertResponseAccepted(boundary, body, protocol)
            assertTrue(result is HostedResponsePrivacyResult.AcceptedWithValidatedCounters)
            val matches = (result as HostedResponsePrivacyResult.AcceptedWithValidatedCounters).matches
            assertEquals(expectedPaths, matches.map { it.safeLocation }.toSet())
            assertTrue(matches.all {
                it.sensitiveKind == "ACCOUNT" && it.nodeType == "NUMBER" && it.recognizedCounter &&
                    !it.knownOtherProtocol
            })
        }

        val blockedBodies = listOf(
            """{"usage":{"total_tokens":7319},"choices":[{"message":{"content":"7319"}}]}""",
            """{"usage":{"total_tokens":7319},"choices":[{"message":{"tool_calls":[{"function":{"arguments":"{\"amount\":7319}"}}]}}]}""",
            """{"usage":{"total_tokens":7319},"7319":"safe"}""",
            """{"usage":{"total_tokens":"7319"}}""",
        )
        for (body in blockedBodies) {
            val refusal = responseRefusal(boundary, body)
            assertEquals(HostedResponsePrivacyRefusalReason.DECODED_SENSITIVE_MATCH, refusal.reason)
            assertFalse(refusal.recognizedCounter)
        }
        // The same match inside provider reasoning is not guard input; the counter stays valid.
        assertResponseAccepted(
            boundary,
            """{"usage":{"total_tokens":7319},"choices":[{"message":{"reasoning_content":"7319"}}]}""",
        )

        val invalidCounterBodies = listOf(
            """{"usage":{"total_tokens":"12"}}""",
            """{"usage":{"total_tokens":-1}}""",
            """{"usage":{"total_tokens":1.5}}""",
            """{"usage":{"total_tokens":true}}""",
            """{"usage":{"total_tokens":null}}""",
            """{"usage":"safe"}""",
            """{"usage":{"completion_tokens_details":"safe"}}""",
        )
        for (body in invalidCounterBodies) {
            val error = responseProtocolError(boundary, body)
            assertEquals(HostedResponseProtocolErrorReason.INVALID_USAGE_COUNTER, error.reason)
            assertFalse(error.recognizedCounter)
            assertFalse(error.knownOtherProtocol)
        }
    }

    @Test
    fun `usage diagnostics retain only safe identifiers and identify foreign protocol paths`() {
        val vault = SensitiveVault("s8.13-safe-usage-key-diagnostics")
        vault.maskText("account ending 7319")
        val boundary = MaskedPromptBoundary(vault)

        val safeIdentifier = responseRefusal(
            boundary,
            """{"usage":{"vendor_metrics":{"model_usage":7319}}}""",
        )
        assertEquals(
            "response_body.usage.vendor_metrics.model_usage",
            safeIdentifier.safeLocation,
        )
        assertEquals("NUMBER", safeIdentifier.nodeType)
        assertFalse(safeIdentifier.recognizedCounter)
        assertFalse(safeIdentifier.knownOtherProtocol)

        val unsafeIdentifier = responseRefusal(
            boundary,
            """{"usage":{"vendor_metrics2":{"model_usage":7319}}}""",
        )
        assertEquals(
            "response_body.usage.other_field.model_usage",
            unsafeIdentifier.safeLocation,
        )
        assertFalse(unsafeIdentifier.knownOtherProtocol)

        val modelIdentifier = responseRefusal(
            boundary,
            """{"choices":[{"message":{"model_usage":7319}}]}""",
        )
        assertEquals(
            "response_body.choices[].message.other_field",
            modelIdentifier.safeLocation,
        )
        assertFalse(modelIdentifier.knownOtherProtocol)

        val responsesCounter = responseRefusal(
            boundary,
            """{"usage":{"input_tokens":7319}}""",
        )
        assertEquals("response_body.usage.input_tokens", responsesCounter.safeLocation)
        assertTrue(responsesCounter.knownOtherProtocol)
        assertFalse(responsesCounter.recognizedCounter)

        val geminiCounter = responseRefusal(
            boundary,
            """{"usageMetadata":{"promptTokenCount":7319}}""",
        )
        assertEquals("response_body.usageMetadata.promptTokenCount", geminiCounter.safeLocation)
        assertTrue(geminiCounter.knownOtherProtocol)
        assertFalse(geminiCounter.recognizedCounter)

        val unknownTokenForeignCounter = responseRefusal(
            boundary,
            """{"usage":{"input_tokens":"⟦UNKNOWN-TOKEN⟧"}}""",
        )
        assertEquals(
            HostedResponsePrivacyRefusalReason.UNKNOWN_TOKEN,
            unknownTokenForeignCounter.reason,
        )
        assertEquals("response_body.usage.input_tokens", unknownTokenForeignCounter.safeLocation)
        assertTrue(unknownTokenForeignCounter.knownOtherProtocol)

        val sensitiveKey = responseRefusal(
            boundary,
            """{"usage":{"account ending 7319":7319}}""",
        )
        assertEquals("response_body.usage.field_name", sensitiveKey.safeLocation)
        assertFalse(sensitiveKey.knownOtherProtocol)
    }

    private enum class Channel { REASONING, ANSWER, ARGUMENTS }

    /**
     * One protocol-correct body carrying [text] in exactly one model-output channel. The same
     * bytes therefore cross the boundary differently only because of their channel.
     */
    private fun channelBody(
        protocol: io.github.triet4p.aegisf.provider.HostedProtocol,
        text: String,
        channel: Channel,
    ): String = when (protocol) {
        io.github.triet4p.aegisf.provider.HostedProtocol.GEMINI_GENERATE_CONTENT ->
            JSONObject()
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
                                        when (channel) {
                                            Channel.REASONING -> JSONArray()
                                                .put(JSONObject().put("text", text).put("thought", true))
                                                .put(JSONObject().put("text", "ack"))

                                            Channel.ANSWER -> JSONArray().put(JSONObject().put("text", text))

                                            Channel.ARGUMENTS -> JSONArray().put(
                                                JSONObject().put(
                                                    "functionCall",
                                                    JSONObject()
                                                        .put("name", "probe_echo")
                                                        .put("args", JSONObject().put("value", text))
                                                        .put("id", "call_1"),
                                                ),
                                            )
                                        },
                                    ),
                            )
                            .put("finishReason", "STOP"),
                    ),
                )
                .toString()

        io.github.triet4p.aegisf.provider.HostedProtocol.OPENAI_CHAT_COMPLETIONS ->
            JSONObject()
                .put("id", "chatcmpl-1")
                .put("object", "chat.completion")
                .put("created", 1)
                .put("model", ProviderFixtures.chatConfig().model)
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

        io.github.triet4p.aegisf.provider.HostedProtocol.OPENAI_RESPONSES ->
            JSONObject()
                .put("model", "gpt-6-luna")
                .put("status", "completed")
                .put(
                    "output",
                    when (channel) {
                        Channel.REASONING -> JSONArray()
                            .put(
                                JSONObject()
                                    .put("id", "rs_1")
                                    .put("type", "reasoning")
                                    .put("status", "completed")
                                    .put(
                                        "summary",
                                        JSONArray().put(
                                            JSONObject().put("type", "summary_text").put("text", text),
                                        ),
                                    )
                                    .put("encrypted_content", text),
                            )
                            .put(
                                JSONObject()
                                    .put("id", "msg_1")
                                    .put("type", "message")
                                    .put("status", "completed")
                                    .put("role", "assistant")
                                    .put(
                                        "content",
                                        JSONArray().put(
                                            JSONObject().put("type", "output_text").put("text", "ack"),
                                        ),
                                    ),
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

    @Test
    fun `reasoning is exempt while the answer and tool arguments stay guarded on all protocols`() {
        val raw = "Salary 15,000,000 VND on 2026-10-25 to account ending 4821."
        val vault = SensitiveVault("s8.13-channel-boundary")
        vault.maskText(raw)
        val boundary = MaskedPromptBoundary(vault)
        val forged = "⟦AMT-ab-1234⟧"
        val issued = vault.maskText("15,000,000 VND").substringBefore(' ')

        for (protocol in io.github.triet4p.aegisf.provider.HostedProtocol.values()) {
            // Provider thinking: invented notation, a forged token and raw-looking values accept.
            assertResponseAccepted(boundary, channelBody(protocol, forged, Channel.REASONING), protocol = protocol)
            assertResponseAccepted(boundary, channelBody(protocol, raw, Channel.REASONING), protocol = protocol)
            assertResponseAccepted(boundary, channelBody(protocol, issued, Channel.REASONING), protocol = protocol)
            // The user-visible answer is guarded before publication.
            assertResponseRefused(
                boundary,
                channelBody(protocol, forged, Channel.ANSWER),
                HostedResponsePrivacyRefusalReason.UNKNOWN_TOKEN,
                protocol = protocol,
            )
            assertResponseRefused(
                boundary,
                channelBody(protocol, raw, Channel.ANSWER),
                HostedResponsePrivacyRefusalReason.DECODED_SENSITIVE_MATCH,
                protocol = protocol,
            )
            assertResponseAccepted(boundary, channelBody(protocol, issued, Channel.ANSWER), protocol = protocol)
            // Tool-call arguments are guarded before any dispatch.
            assertResponseRefused(
                boundary,
                channelBody(protocol, forged, Channel.ARGUMENTS),
                HostedResponsePrivacyRefusalReason.UNKNOWN_TOKEN,
                protocol = protocol,
            )
            assertResponseRefused(
                boundary,
                channelBody(protocol, raw, Channel.ARGUMENTS),
                HostedResponsePrivacyRefusalReason.DECODED_SENSITIVE_MATCH,
                protocol = protocol,
            )
            assertResponseAccepted(boundary, channelBody(protocol, issued, Channel.ARGUMENTS), protocol = protocol)
        }
        // An inbound scan never mints or rehydrates a value.
        assertEquals(3, vault.entryCount())
    }

    @Test
    fun `real adapters accept invented reasoning and block the same answer or arguments`() = runBlocking {
        val forged = "⟦AMT-ab-1234⟧"
        for (protocol in io.github.triet4p.aegisf.provider.HostedProtocol.values()) {
            val acceptedTransport = RecordingTransport(
                ProviderFixtures.reply(channelBody(protocol, forged, Channel.REASONING)),
            )
            val acceptedGateway = gatewayOf(
                "s8.13-adapter-reasoning-$protocol",
                acceptedTransport,
                protocol,
            )
            val turn = ProviderFixtures.turn(
                acceptedGateway.generate(
                    acceptedGateway.begin("Read the synthetic reading.", "Read it."),
                    emptyList(),
                ),
            )
            assertEquals(
                "provider reasoning must not block ${protocol.name}: $turn",
                io.github.triet4p.aegisf.provider.HostedTurnFinish.Complete,
                turn.finish,
            )
            assertEquals("ack", turn.assistantText)
            assertEquals(1, acceptedTransport.requests.size)

            for (channel in listOf(Channel.ANSWER, Channel.ARGUMENTS)) {
                val transport = RecordingTransport(
                    ProviderFixtures.reply(channelBody(protocol, forged, channel)),
                )
                val gateway = gatewayOf(
                    "s8.13-adapter-enforced-$protocol-${channel.name}",
                    transport,
                    protocol,
                )
                val failure = ProviderFixtures.failure(
                    gateway.generate(
                        gateway.begin("Read the synthetic reading.", "Read it."),
                        emptyList(),
                    ),
                )
                assertTrue("expected a privacy refusal for ${protocol.name}/${channel.name}", failure is HostedProviderFailure.PrivacyBlocked)
                assertEquals(
                    HostedResponsePrivacyRefusalReason.UNKNOWN_TOKEN,
                    (failure as HostedProviderFailure.PrivacyBlocked).refusal.reason,
                )
                assertEquals("a refused response dispatches nothing", 1, transport.requests.size)
            }
        }
    }

    @Test
    fun `a thought-marked gemini call never dispatches while ordinary arguments stay guarded`() = runBlocking {
        val protocol = io.github.triet4p.aegisf.provider.HostedProtocol.GEMINI_GENERATE_CONTENT
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
        val transport = RecordingTransport(ProviderFixtures.reply(thoughtBody))
        val gateway = gatewayOf("s8.13-thought-call", transport, protocol)
        val turn = ProviderFixtures.turn(
            gateway.generate(gateway.begin("Read the synthetic reading.", "Read it."), listOf(ProviderFixtures.echoTool)),
        )
        assertEquals(io.github.triet4p.aegisf.provider.HostedTurnFinish.Complete, turn.finish)
        assertEquals("planning", turn.assistantText)
        assertTrue("a thought-marked call must not become a tool call, got ${turn.toolCalls}", turn.toolCalls.isEmpty())
        assertNull("a thought-only call must not hold a continuation", turn.conversation)
        assertEquals(1, transport.requests.size)

        val mixedBody = JSONObject()
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
                                                        .put("id", "call_9"),
                                                )
                                                .put("thought", true),
                                        )
                                        .put(
                                            JSONObject()
                                                .put(
                                                    "functionCall",
                                                    JSONObject()
                                                        .put("name", "probe_echo")
                                                        .put("args", JSONObject().put("value", "ALPHA-17"))
                                                        .put("id", "call_1"),
                                                ),
                                        ),
                                ),
                        )
                        .put("finishReason", "STOP"),
                ),
            )
            .toString()
        val mixedTransport = RecordingTransport(ProviderFixtures.reply(mixedBody))
        val mixedGateway = gatewayOf("s8.13-thought-mixed", mixedTransport, protocol)
        val mixedTurn = ProviderFixtures.turn(
            mixedGateway.generate(
                mixedGateway.begin("Read the synthetic reading.", "Read it."),
                listOf(ProviderFixtures.echoTool),
            ),
        )
        assertEquals(io.github.triet4p.aegisf.provider.HostedTurnFinish.ToolCalls, mixedTurn.finish)
        assertEquals(listOf("call_1"), mixedTurn.toolCalls.map { it.callId })
        assertTrue(mixedTurn.toolCalls.single().dispatchable)

        val guardedBody = channelBody(protocol, "reasoning-like args ⟦AMT-ab-1234⟧", Channel.ARGUMENTS)
        val guardedTransport = RecordingTransport(ProviderFixtures.reply(guardedBody))
        val guardedGateway = gatewayOf("s8.13-thought-args-guarded", guardedTransport, protocol)
        val failure = ProviderFixtures.failure(
            guardedGateway.generate(
                guardedGateway.begin("Read the synthetic reading.", "Read it."),
                listOf(ProviderFixtures.echoTool),
            ),
        )
        assertTrue(failure is HostedProviderFailure.PrivacyBlocked)
    }


    @Test
    fun `mixed reasoning and tool calls never leak raw literals on the next request`() = runBlocking {
        val raw = "Salary 15,000,000 VND on 2026-10-25 to account ending 4821."
        for (protocol in io.github.triet4p.aegisf.provider.HostedProtocol.values()) {
            val scopeId = "s8.13-mixed-egress-$protocol"
            val firstGatewayProbe = gatewayOf(scopeId, RecordingTransport(), protocol)
            val maskedProbe = firstGatewayProbe.maskContext(raw)
            fun literalIn(continuation: String): Boolean {
                val stripped = SensitiveVault.TOKEN_PATTERN.replace(continuation, " ")
                return rawLiterals.any { it in stripped }
            }
            val mixedReply = when (protocol) {
                io.github.triet4p.aegisf.provider.HostedProtocol.GEMINI_GENERATE_CONTENT -> JSONObject()
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
                                                        .put("text", raw)
                                                        .put("thought", true)
                                                        .put("thoughtSignature", "sig"),
                                                )
                                                .put(
                                                    JSONObject().put(
                                                        "functionCall",
                                                        JSONObject()
                                                            .put("name", "probe_echo")
                                                            .put("args", JSONObject().put("value", "ALPHA-17"))
                                                            .put("id", "call_1"),
                                                    ),
                                                ),
                                        ),
                                )
                                .put("finishReason", "STOP"),
                        ),
                    )
                    .toString()
                io.github.triet4p.aegisf.provider.HostedProtocol.OPENAI_CHAT_COMPLETIONS -> JSONObject()
                    .put("id", "chatcmpl-1")
                    .put("object", "chat.completion")
                    .put("created", 1)
                    .put("model", ProviderFixtures.chatConfig().model)
                    .put(
                        "choices",
                        JSONArray().put(
                            JSONObject()
                                .put("index", 0)
                                .put("finish_reason", "tool_calls")
                                .put(
                                    "message",
                                    JSONObject()
                                        .put("role", "assistant")
                                        .put("content", "ack")
                                        .put("reasoning_content", raw)
                                        .put(
                                            "tool_calls",
                                            JSONArray().put(
                                                JSONObject()
                                                    .put("id", "call_1")
                                                    .put("type", "function")
                                                    .put(
                                                        "function",
                                                        JSONObject()
                                                            .put("name", "probe_echo")
                                                            .put("arguments", JSONObject().put("value", "ALPHA-17").toString()),
                                                    ),
                                            ),
                                        ),
                                ),
                        ),
                    )
                    .toString()
                io.github.triet4p.aegisf.provider.HostedProtocol.OPENAI_RESPONSES -> JSONObject()
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
                                    .put("summary", JSONArray().put(JSONObject().put("type", "summary_text").put("text", raw)))
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
            }
            val closingReply = when (protocol) {
                io.github.triet4p.aegisf.provider.HostedProtocol.GEMINI_GENERATE_CONTENT ->
                    """{"modelVersion":"gemini-3.5-flash-lite","candidates":[{"content":{"role":"model","parts":[{"text":"done"}]},"finishReason":"STOP"}]}"""
                io.github.triet4p.aegisf.provider.HostedProtocol.OPENAI_CHAT_COMPLETIONS ->
                    """{"model":"${ProviderFixtures.chatConfig().model}","choices":[{"message":{"role":"assistant","content":"done"},"finish_reason":"stop"}]}"""
                io.github.triet4p.aegisf.provider.HostedProtocol.OPENAI_RESPONSES ->
                    """{"model":"gpt-6-luna","status":"completed","output":[{"id":"msg_1","type":"message","status":"completed","role":"assistant","content":[{"type":"output_text","text":"done"}]}]}"""
            }
            val transport = RecordingTransport(ProviderFixtures.reply(mixedReply), ProviderFixtures.reply(closingReply))
            val gateway = gatewayOf(scopeId, transport, protocol)
            val conversation = gateway.begin("Read the synthetic reading.", maskedProbe)
            val turn = ProviderFixtures.turn(
                gateway.generate(conversation, listOf(ProviderFixtures.echoTool)),
            )
            assertEquals(
                "mixed reasoning plus a call must stay a tool-call turn for $protocol",
                io.github.triet4p.aegisf.provider.HostedTurnFinish.ToolCalls,
                turn.finish,
            )
            assertEquals(listOf("call_1"), turn.toolCalls.map { it.callId })
            val pending = turn.conversation ?: error("expected a continuable turn for $protocol")
            val next = gateway.continueWithToolResults(
                MaskedConversation(pending, gateway.boundary),
                listOf(HostedToolResult("call_1", gateway.boundary.maskToolResultJson("""{"stored":"kept"}"""))),
                AgentContextManager.tokenInventoryBlock(gateway.boundary.vault.issuedTokens()),
            )
            gateway.generate(next, listOf(ProviderFixtures.echoTool))
            assertEquals(2, transport.requests.size)
            val continuation = transport.requests[1].body
            assertFalse("the next request must not carry the mapped raw literal for $protocol", literalIn(continuation))
            assertFalse("no raw mapping may leave the device for $protocol", raw in continuation)
            assertTrue("the issued inventory must ride the continuation for $protocol", continuation.contains("issued_mask_token_inventory"))
            for (token in gateway.boundary.vault.issuedTokens()) {
                assertTrue("missing issued token ${token.token} for $protocol", continuation.contains(token.token))
            }
            assertTrue("call/result pairing must survive for $protocol", continuation.contains("call_1"))
            if (protocol == io.github.triet4p.aegisf.provider.HostedProtocol.OPENAI_RESPONSES) {
                assertTrue("opaque Responses continuation state must survive scrubbing", continuation.contains("opaque-continuation-state"))
                assertFalse("plaintext Responses reasoning must not ride the continuation", "summary_text" in continuation)
            }
        }
    }


    private fun privacyResponseWithHiddenRawValue(
        protocol: io.github.triet4p.aegisf.provider.HostedProtocol,
        rawSource: String,
    ): String = when (protocol) {
        io.github.triet4p.aegisf.provider.HostedProtocol.GEMINI_GENERATE_CONTENT ->
            JSONObject()
                .put("modelVersion", "gemini-3.5-flash-lite")
                .put("x_provider_diagnostic", rawSource)
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
                                                    .put("thoughtSignature", "sig"),
                                            ),
                                    ),
                            )
                            .put("finishReason", "STOP"),
                    ),
                )
                .toString()

        io.github.triet4p.aegisf.provider.HostedProtocol.OPENAI_CHAT_COMPLETIONS ->
            JSONObject()
                .put("model", "deepseek-v4.1-flash")
                .put("x_provider_diagnostic", rawSource)
                .put(
                    "choices",
                    JSONArray().put(
                        JSONObject()
                            .put(
                                "message",
                                JSONObject()
                                    .put("role", "assistant")
                                    .put("content", JSONObject.NULL)
                                    .put("reasoning_content", "thinking about the observation")
                                    .put(
                                        "tool_calls",
                                        JSONArray().put(
                                            JSONObject()
                                                .put("id", "call_1")
                                                .put("type", "function")
                                                .put(
                                                    "function",
                                                    JSONObject()
                                                        .put("name", "probe_echo")
                                                        .put("arguments", """{"value":"ALPHA-17"}"""),
                                                ),
                                        ),
                                    ),
                            )
                            .put("finish_reason", "tool_calls"),
                    ),
                )
                .toString()

        io.github.triet4p.aegisf.provider.HostedProtocol.OPENAI_RESPONSES ->
            JSONObject()
                .put("model", "gpt-6-luna")
                .put("status", "completed")
                .put("x_provider_diagnostic", rawSource)
                .put(
                    "output",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("id", "rs_1")
                                .put("type", "reasoning")
                                .put("status", "completed")
                                .put("encrypted_content", "opaque-reasoning"),
                        )
                        .put(
                            JSONObject()
                                .put("type", "function_call")
                                .put("status", "completed")
                                .put("name", "probe_echo")
                                .put("call_id", "call_1")
                                .put("arguments", """{"value":"ALPHA-17"}"""),
                        ),
                )
                .toString()
    }

    @Test
    fun `masked reading stores exact money date and span through the real services`() {
        MaskingHarnessIsolation.runIsolated { harness ->
            val captureId = harness.saveCapture("s8.6-local-effect", captureText)
            val gateway = MaskedHostedGateway(
                HostedAdapters.create(ProviderFixtures.chatConfig(), RecordingTransport()),
                MaskedPromptBoundary(SensitiveVault("s8.6-local-effect")),
            )
            val scope = io.github.triet4p.aegisf.data.WorkflowCallerScope.AgentSession(
                scopeId = "s8.6-local-effect",
                captureIds = setOf(captureId),
                draftIds = emptySet(),
            )
            val maskedPrompt = gateway.maskContext(probePrompt)
            assertNoRawLiteral(listOf(maskedPrompt), "prompt")
            val maskedSource = gateway.maskContext(captureText)
            // Span 0..35 is the `Salary 15,000,000 VND on 2026-10-25` span of capture 118:
            // the masked prompt carries the same amount/date tokens, so they decode locally.
            val amountToken = SensitiveVault.TOKEN_PATTERN.find(maskedPrompt)!!.value
            val dateToken = SensitiveVault.TOKEN_PATTERN.findAll(maskedPrompt).toList()[1].value
            val maskedSlice = maskedSource.substring(0, maskedSource.indexOf(" to "))
            val eventId = harness.storeMaskedReading(
                scope = scope,
                boundary = gateway.boundary,
                key = "s8.6-local-effect-1",
                captureId = captureId,
                captureText = captureText,
                spanStart = 0,
                spanEnd = 35,
                maskedSpanText = maskedSlice,
                masked = MaskedInterpretation(
                    normalizedText = gateway.maskContext("Salary purchase"),
                    amountToken = amountToken,
                    dateToken = dateToken,
                    timeToken = null,
                    spanSourceStart = 0,
                    spanSourceEnd = 35,
                ),
            )
            val stored = harness.database.noteEventDao().findForCapture(captureId).single()
            assertEquals(eventId, stored.id)
            assertEquals(15_000_000L, stored.amountVnd)
            assertEquals("2026-10-25", stored.anchorDate)
            assertEquals(0, stored.spanStart)
            assertEquals(35, stored.spanEnd)
            assertEquals("Salary purchase", stored.normalizedText)
            assertEquals(1, harness.database.workflowOperationDao().countOf("UPDATE_EVENT"))
            assertEquals(0, harness.database.transactionDao().findAll().size)
            // Rehydrated tokens are local authority: the same tokens decode to exact values.
            assertEquals(15_000_000L, gateway.boundary.vault.rehydrateAmount(amountToken))
            assertEquals("2026-10-25", gateway.boundary.vault.rehydrateDate(dateToken))
        }
    }

    @Test
    fun `equality order collisions and scope isolation hold under adversarial reuse`() {
        MaskingHarnessIsolation.runIsolated { harness ->
            val captureId = harness.saveCapture("s8.6-swap", "Dinner 95,000 VND and 95,000 VND again.")
            val vault = SensitiveVault("s8.6-swap")
            val boundary = MaskedPromptBoundary(vault)
            val scope = io.github.triet4p.aegisf.data.WorkflowCallerScope.AgentSession(
                scopeId = "s8.6-swap",
                captureIds = setOf(captureId),
                draftIds = setOf(411L),
            )
            // Equal values mask equally within scope.
            val first = vault.maskText("95,000 VND")
            val second = vault.maskText("95,000 VND")
            assertEquals(first, second)
            // Unknown tokens and cross-scope ids cannot redirect scope or change finances.
            val foreign = SensitiveVault("s8.6-other").maskText("95,000 VND")
            try {
                MaskedApplicationEffect.rehydrateInterpretation(
                    scope = scope,
                    boundary = boundary,
                    captureId = 999L,
                    captureText = "x",
                    spanStart = 0,
                    spanEnd = 1,
                    maskedSpanText = "x",
                    masked = MaskedInterpretation(first, null, null, null, 0, 1),
                )
                fail("expected cross-capture scope to be refused")
            } catch (expected: TokenValidationException) {
                assertTrue(expected.message.orEmpty().isNotBlank())
            }
            try {
                vault.rehydrateAmount(foreign)
                fail("expected a cross-session token to be refused")
            } catch (expected: TokenValidationException) {
                assertTrue(expected.message.orEmpty().isNotBlank())
            }
            try {
                MaskedApplicationEffect.validatedLinkPair(scope, eventId = 311L, draftId = 999L)
                fail("expected a cross-scope draft to be refused")
            } catch (expected: TokenValidationException) {
                assertTrue(expected.message.orEmpty().isNotBlank())
            }
            // Swapping event and draft ids is a type error the scope check refuses.
            try {
                MaskedApplicationEffect.validatedLinkPair(scope, eventId = 411L, draftId = 311L)
                fail("expected a swapped pair to be refused")
            } catch (expected: TokenValidationException) {
                assertTrue(expected.message.orEmpty().isNotBlank())
            }
            assertEquals(0, harness.database.transactionDao().findAll().size)
        }
    }

    private fun repliesFor(
        protocol: io.github.triet4p.aegisf.provider.HostedProtocol,
    ): Pair<HostedHttpResponse, HostedHttpResponse> = when (protocol) {
        io.github.triet4p.aegisf.provider.HostedProtocol.GEMINI_GENERATE_CONTENT -> Pair(
            ProviderFixtures.reply(
                """
                {"modelVersion":"gemini-3.5-flash-lite","candidates":[{"content":{"role":"model","parts":[
                  {"functionCall":{"name":"probe_echo","args":{"value":"ALPHA-17"},"id":"call_1"},"thoughtSignature":"sig"}
                ]},"finishReason":"STOP"}]}
                """.trimIndent(),
            ),
            ProviderFixtures.reply(
                """
                {"modelVersion":"gemini-3.5-flash-lite","candidates":[{"content":{"role":"model","parts":[
                  {"text":"ALPHA-17 DONE"}
                ]},"finishReason":"STOP"}]}
                """.trimIndent(),
            ),
        )
        io.github.triet4p.aegisf.provider.HostedProtocol.OPENAI_CHAT_COMPLETIONS -> Pair(
            ProviderFixtures.reply(
                """
                {"model":"deepseek-v4.1-flash","choices":[{"message":{"role":"assistant","content":null,
                 "tool_calls":[{"id":"call_1","type":"function",
                   "function":{"name":"probe_echo","arguments":"{\"value\":\"ALPHA-17\"}"}}]},
                 "finish_reason":"tool_calls"}]}
                """.trimIndent(),
            ),
            ProviderFixtures.reply(
                """
                {"model":"deepseek-v4.1-flash","choices":[{"message":{"role":"assistant","content":"ALPHA-17 DONE"},
                 "finish_reason":"stop"}]}
                """.trimIndent(),
            ),
        )
        io.github.triet4p.aegisf.provider.HostedProtocol.OPENAI_RESPONSES -> Pair(
            ProviderFixtures.reply(
                """
                {"model":"gpt-6-luna","status":"completed","output":[
                  {"id":"rs_1","type":"reasoning","status":"completed","encrypted_content":"opaque-reasoning"},
                  {"id":"fc_1","type":"function_call","status":"completed","name":"probe_echo",
                   "call_id":"call_1","arguments":"{\"value\":\"ALPHA-17\"}"}]}
                """.trimIndent(),
            ),
            ProviderFixtures.reply(
                """
                {"model":"gpt-6-luna","status":"completed","output":[
                  {"id":"msg_1","type":"message","status":"completed","role":"assistant",
                   "content":[{"type":"output_text","text":"ALPHA-17 DONE"}]}]}
                """.trimIndent(),
            ),
        )
    }

    private fun maskedObservation(gateway: MaskedHostedGateway): String =
        gateway.boundary.maskToolResultJson("""{"stored":"Salary reading kept"}""")
}
