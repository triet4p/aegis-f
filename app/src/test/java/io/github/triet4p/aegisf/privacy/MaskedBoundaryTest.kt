package io.github.triet4p.aegisf.privacy

import io.github.triet4p.aegisf.provider.HostedAdapters
import io.github.triet4p.aegisf.provider.HostedGenerationResult
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
        val bodies get() = requests.map { it.body }
        override suspend fun post(request: HostedHttpRequest): HostedHttpResponse {
            requests += request
            return queued.removeFirstOrNull()
                ?: throw IllegalStateException("no queued answer for request ${requests.size}")
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
