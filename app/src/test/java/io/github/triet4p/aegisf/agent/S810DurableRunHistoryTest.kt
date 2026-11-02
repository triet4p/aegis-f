package io.github.triet4p.aegisf.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.triet4p.aegisf.data.CanonicalAgentRetryPolicy
import io.github.triet4p.aegisf.data.ContextLinkState
import io.github.triet4p.aegisf.data.IntakeDirection
import io.github.triet4p.aegisf.data.IntakeFieldState
import io.github.triet4p.aegisf.data.IntakeParseStatus
import io.github.triet4p.aegisf.data.IntakeReviewStatus
import io.github.triet4p.aegisf.data.LedgerDatabase
import io.github.triet4p.aegisf.data.LinkOperationQuery
import io.github.triet4p.aegisf.data.PendingBankDraftEntity
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.SaveCaptureRequest
import io.github.triet4p.aegisf.data.SystemLedgerClock
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowChange
import io.github.triet4p.aegisf.data.WorkflowCommandOutcome
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.privacy.MaskedHostedGateway
import io.github.triet4p.aegisf.privacy.MaskedPath
import io.github.triet4p.aegisf.privacy.MaskedPromptBoundary
import io.github.triet4p.aegisf.privacy.SensitiveVault
import io.github.triet4p.aegisf.provider.HostedConversation
import io.github.triet4p.aegisf.provider.HostedGenerationAdapter
import io.github.triet4p.aegisf.provider.HostedGenerationResult
import io.github.triet4p.aegisf.provider.HostedProtocol
import io.github.triet4p.aegisf.provider.HostedToolCall
import io.github.triet4p.aegisf.provider.HostedToolResult
import io.github.triet4p.aegisf.provider.HostedTurn
import io.github.triet4p.aegisf.provider.HostedTurnFinish
import io.github.triet4p.aegisf.provider.ToolCallDisposition
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The S8.10 durable history and safe retry/cancel/restart tests.
 *
 * Real Room/services/journal/history; only the provider is scripted at the adapter
 * seam. Every assertion about an effect reads stored rows; the conversation read and
 * the safe export are asserted to carry no operation keys, digests, mappings,
 * credentials, or raw sensitive literals.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class S810DurableRunHistoryTest {
    private lateinit var database: LedgerDatabase
    private lateinit var commands: WorkflowCommandService
    private lateinit var queries: WorkflowQueryService
    private lateinit var history: AgentRunHistory
    /** The most recently prepared fixture's vault, for parse-based value selection. */
    private lateinit var activeVault: SensitiveVault
    private lateinit var declarations: List<io.github.triet4p.aegisf.provider.HostedToolDeclaration>
    private lateinit var skills: WorkflowSkillCatalog
    private val sequence = AtomicInteger()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        commands = WorkflowCommandService(
            database = database,
            clock = SystemLedgerClock,
            timezoneId = { "Asia/Ho_Chi_Minh" },
        )
        queries = WorkflowQueryService(database)
        history = AgentRunHistory(database)
        declarations = AgentToolDeclarations.load(context.assets::open)
        skills = WorkflowSkillCatalog.load(context.assets::open)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `repeated user send replays one run without duplicating the effect`() = runBlocking {
        val fixture = prepare()
        lateinit var adapter: ScriptedAdapter
        val script = scripted()
        script += {
            toolTurn(call("select_source", JSONObject()
                .put("capture_ref", captureRef(adapter))
                .put("quote", sourceText(adapter))
                .put("occurrence", 1)))
        }
        script += {
            val source = previousEnvelope(adapter, 1).getJSONObject("data")
            toolTurn(call("make_time_anchor", JSONObject()
                .put("date_ref", valueRef(source, "date"))
                .put("time_ref", valueRef(source, "time"))
                .put("end_date_ref", JSONObject.NULL)
                .put("end_time_ref", JSONObject.NULL)))
        }
        script += {
            val source = previousEnvelope(adapter, 2).getJSONObject("data")
            toolTurn(call("record_event", JSONObject()
                .put("source_ref", source.getString("source_ref"))
                .put("summary", "Coffee")
                .put("amount_ref", valueRef(source, "amount"))
                .put("anchor_ref", lastEnvelope(adapter).getJSONObject("data").getString("anchor_ref"))
                .put("merchant_ref", JSONObject.NULL)
                .put("clarification", "none")))
        }
        script += { complete("Prepared the synthetic reading.") }
        adapter = ScriptedAdapter(script)
        val request = WorkflowRunRequest(
            intents = setOf(WorkflowIntent.SEARCH_DRAFTS, WorkflowIntent.INTERPRET_EVENTS),
            userRequest = "Prepare the synthetic pair.",
        )
        val first = runTracked(fixture, adapter, request, key("run-a"))
        val secondRequest = WorkflowRunRequest(
            intents = setOf(WorkflowIntent.SEARCH_DRAFTS, WorkflowIntent.INTERPRET_EVENTS),
            userRequest = "Prepare the synthetic pair.",
        )
        val attempted = history.admitRun(fixture.scope, secondRequest, fixture.boundary, 8192)
        assertTrue("the repeated send must replay the same run", attempted.replayed)
        assertEquals(first, attempted.runId)
        assertEquals(1L, database.workflowOperationDao().countOf("UPDATE_EVENT"))
        val conversation = history.conversationForRun(first)
        assertTrue(conversation.any { it.role == "USER" })
        assertTrue("no operation key may enter the visible conversation", conversation.none {
            it.text.contains("attempt-") || it.text.contains("run-")
        })
    }

    @Test
    fun `interrupted update reconciles to applied instead of duplicating`() = runBlocking {
        val fixture = prepare()
        lateinit var adapter: ScriptedAdapter
        val script = scripted()
        script += {
            toolTurn(call("select_source", JSONObject()
                .put("capture_ref", captureRef(adapter))
                .put("quote", sourceText(adapter))
                .put("occurrence", 1)))
        }
        script += {
            val source = previousEnvelope(adapter, 1).getJSONObject("data")
            toolTurn(call("record_event", JSONObject()
                .put("source_ref", source.getString("source_ref"))
                .put("summary", "Coffee")
                .put("amount_ref", valueRef(source, "amount"))
                .put("anchor_ref", JSONObject.NULL)
                .put("merchant_ref", JSONObject.NULL)
                .put("clarification", "none")))
        }
        script += { complete("The synthetic event is interpreted.") }
        adapter = ScriptedAdapter(script)
        val request = WorkflowRunRequest(
            intents = setOf(WorkflowIntent.INTERPRET_EVENTS),
            userRequest = "Interpret the synthetic note.",
        )
        val runId = runTracked(fixture, adapter, request, key("run-b"))
        assertEquals("one event row", 1, database.noteEventDao().findForCapture(fixture.captureId).size)
        assertEquals(1L, database.workflowOperationDao().countOf("UPDATE_EVENT"))
        val verdict = history.reconcileUncertainWrite(runId, 1)
        assertTrue("the applied write must reconcile as applied, got $verdict", verdict is UncertainWriteVerdict.Applied)
    }

    @Test
    fun `an exact repeated call replays without a second journal row or event`() = runBlocking {
        val fixture = prepare()
        lateinit var adapter: ScriptedAdapter
        val script = scripted()
        script += {
            toolTurn(call("select_source", JSONObject()
                .put("capture_ref", captureRef(adapter))
                .put("quote", sourceText(adapter))
                .put("occurrence", 1)))
        }
        script += {
            val source = previousEnvelope(adapter, 1).getJSONObject("data")
            toolTurn(call("record_event", JSONObject()
                .put("source_ref", source.getString("source_ref"))
                .put("summary", "Coffee")
                .put("amount_ref", valueRef(source, "amount"))
                .put("anchor_ref", JSONObject.NULL)
                .put("merchant_ref", JSONObject.NULL)
                .put("clarification", "none")))
        }
        script += {
            // Exact repetition is the recorded operation alias, not a reconstructed payload.
            val operationRef = previousEnvelope(adapter, 1).getString("operation_ref")
            toolTurn(call("replay_operation", JSONObject().put("operation_ref", operationRef)))
        }
        script += { complete("done") }
        adapter = ScriptedAdapter(script)
        val request = WorkflowRunRequest(
            intents = setOf(WorkflowIntent.INTERPRET_EVENTS, WorkflowIntent.REPLAY_OPERATION),
            userRequest = "Interpret the synthetic note and confirm the exact operation.",
        )
        val runId = runTracked(fixture, adapter, request, key("run-c"))
        assertEquals(1, database.noteEventDao().findForCapture(fixture.captureId).size)
        assertEquals("one journal row per mutation key", 1L, database.workflowOperationDao().countOf("UPDATE_EVENT"))
        val attempts = database.agentRunAttemptDao().findForRun(runId)
        assertEquals(1, attempts.size)
        val payload = JSONObject(adapter.toolResultPayloads.last().single())
        assertEquals("replay_operation", payload.getString("tool"))
        assertEquals("unchanged", payload.getString("effect"))
        assertEquals("no_change", payload.getJSONObject("data").getString("journal_outcome"))
        assertTrue(payload.getBoolean("verified"))
        assertEquals(1L, database.workflowOperationDao().countOf("UPDATE_EVENT"))
    }

    @Test
    fun `a masking refusal after an applied write stops hosted work and reconciles locally`() = runBlocking {
        val fixture = prepare()
        lateinit var adapter: ScriptedAdapter
        val script = scripted()
        script += {
            toolTurn(call("select_source", JSONObject()
                .put("capture_ref", captureRef(adapter))
                .put("quote", sourceText(adapter))
                .put("occurrence", 1)))
        }
        script += {
            val source = previousEnvelope(adapter, 1).getJSONObject("data")
            toolTurn(call("record_event", JSONObject()
                .put("source_ref", source.getString("source_ref"))
                .put("summary", "Coffee")
                .put("amount_ref", valueRef(source, "amount"))
                .put("anchor_ref", JSONObject.NULL)
                .put("merchant_ref", JSONObject.NULL)
                .put("clarification", "none")))
        }
        script += {
            // The write is already applied when this later turn carries a raw literal.
            toolTurn(call("observe", JSONObject().put("ref", "25,000 VND")))
        }
        script += { complete("This turn must never be requested.") }
        adapter = ScriptedAdapter(script)
        val request = WorkflowRunRequest(
            intents = setOf(WorkflowIntent.INTERPRET_EVENTS),
            userRequest = "Interpret the synthetic note.",
        )
        val admitted = history.admitRun(fixture.scope, request, fixture.boundary, 8192)
        val outcome = BoundedWorkflowExecutor(hostOf(fixture, adapter)).startTracked(
            request = request,
            history = history,
            attemptKey = admitted.attemptKey,
            maxOutputTokens = 8192,
        )
        assertTrue("expected a masking stop, got $outcome", outcome is WorkflowRunOutcome.Incomplete)
        assertEquals(
            WorkflowRunStopReason.MASKING_BLOCKED,
            (outcome as WorkflowRunOutcome.Incomplete).reason,
        )
        // The earlier call really applied; the host reconciled it locally instead of retrying.
        val operationCount = database.workflowOperationDao().countOf("UPDATE_EVENT")
        assertEquals(
            "no event after masking stop; ops=$operationCount outcome=$outcome",
            1,
            database.noteEventDao().findForCapture(fixture.captureId).size,
        )
        assertEquals(1L, database.workflowOperationDao().countOf("UPDATE_EVENT"))
        // No continuation turn was requested after the refusal.
        assertEquals(2, adapter.prompts.size)
        assertTrue(adapter.prompts.none { it.contains("25,000") })
    }

    @Test
    fun `cancel leaves the stored effect intact and never claims rollback`() = runBlocking {
        val fixture = prepare()
        val request = WorkflowRunRequest(
            intents = setOf(WorkflowIntent.SEARCH_DRAFTS),
            userRequest = "Find the pending drafts.",
        )
        val admitted = history.admitRun(fixture.scope, request, fixture.boundary, 8192)
        assertTrue(history.cancelRun(admitted.runId, "user_cancelled"))
        val runId = admitted.runId
        val stored = database.agentRunDao().findByRunId(runId)!!
        assertEquals("CANCELLED", stored.state)
        val conversation = history.conversationForRun(runId)
        assertTrue(conversation.any { it.role == "STATUS" && it.text.contains("cancelled", ignoreCase = true) })
        assertTrue("a query-only run writes no ledger row", database.transactionDao().findAll().isEmpty())
    }

    @Test
    fun `request text is masked before history persistence`() = runBlocking {
        val fixture = prepare()
        val keyLikeValue = "SYNTHETICAPIKEY123456"
        val userRequest = "Find notes for $keyLikeValue."
        val request = WorkflowRunRequest(
            intents = setOf(WorkflowIntent.SEARCH_DRAFTS),
            userRequest = userRequest,
        )
        val expectedMasked = fixture.boundary.maskOutbound(userRequest, MaskedPath.HISTORY)
        val admitted = history.admitRun(fixture.scope, request, fixture.boundary, 8192)
        val storedRun = database.agentRunDao().findByRunId(admitted.runId)!!
        val storedUserLine = history.conversationForRun(admitted.runId).single { it.role == "USER" }

        assertFalse(storedRun.requestText.contains(keyLikeValue))
        assertFalse(storedUserLine.text.contains(keyLikeValue))
        assertEquals(expectedMasked, storedRun.requestText)
        assertEquals(expectedMasked, storedUserLine.text)
    }

    @Test
    fun `retention prunes history and export excludes keys and mappings`() = runBlocking {
        val fixture = prepare()
        repeat(3) { index ->
            history.admitRun(
                fixture.scope,
                WorkflowRunRequest(
                    intents = setOf(WorkflowIntent.SEARCH_DRAFTS),
                    userRequest = "History probe $index ${sequence.incrementAndGet()}.",
                ),
                fixture.boundary,
                8192,
            )
        }
        assertEquals(3L, database.agentRunDao().countOf(fixture.scope.scopeId))
        val exported = history.safeExport(fixture.scope.scopeId, 50, 0)
        val body = exported.toString()
        assertFalse("attempt keys must not export", body.contains("attempt-"))
        assertFalse("digests must not export", body.contains("digest"))
        assertFalse("operation journal must not export", body.contains("UPDATE_EVENT"))
        assertFalse("raw amounts must not export", body.contains("25,000"))
        assertTrue(history.deleteScopeHistory(fixture.scope.scopeId) >= 3)
        assertEquals(0L, database.agentRunDao().countOf(fixture.scope.scopeId))
        // Business stores are separate from the conversation: the draft survives deletion.
        assertTrue(queries.draftCandidate(fixture.draftId) != null)
    }

    @Test
    fun `late reply cannot settle a cancelled attempt`() = runBlocking {
        val fixture = prepare()
        val admitted = history.admitRun(
            fixture.scope,
            WorkflowRunRequest(
                intents = setOf(WorkflowIntent.SEARCH_DRAFTS),
                userRequest = "Find the pending drafts ${sequence.incrementAndGet()}.",
            ),
            fixture.boundary,
            8192,
        )
        assertTrue(history.claimForExecution(admitted.runId, 1))
        assertTrue(history.cancelRun(admitted.runId, "user_cancelled"))
        val row = database.agentRunAttemptDao().findByKey(admitted.attemptKey)!!
        assertEquals("CANCELLED", row.state)
        assertEquals(null, row.ownerToken)
        // A late reply under the old token cannot settle the cancelled row.
        assertEquals(
            0,
            database.agentRunAttemptDao().settle(
                id = row.id,
                ownerToken = admitted.ownerToken ?: "missing",
                state = "COMPLETED",
                stopReason = null,
                effectKind = "query",
                eventId = null,
                eventRevision = null,
                linkId = null,
                linkState = null,
                linkRevision = null,
                draftId = null,
                decisionSource = null,
                selectedCategoryId = null,
                observationJson = "{}",
                now = System.currentTimeMillis(),
            ),
        )
    }

    // ---- Harness -------------------------------------------------------------------------

    private suspend fun runTracked(
        fixture: Fixture,
        adapter: ScriptedAdapter,
        request: WorkflowRunRequest,
        attemptKey: String,
    ): String {
        val admitted = history.admitRun(fixture.scope, request, fixture.boundary, 8192)
        check(history.claimForExecution(admitted.runId, admitted.attempt)) { "run not claimed" }
        val host = hostOf(fixture, adapter)
        val executor = BoundedWorkflowExecutor(host)
        val outcome = executor.startWithAttemptKey(request, attemptKey, 8192)
        history.recordAttemptOutcome(admitted.runId, admitted.attempt, outcome, fixture.boundary)
        return admitted.runId
    }

    private fun hostOf(fixture: Fixture, adapter: ScriptedAdapter): WorkflowRunHost {
        val gateway = MaskedHostedGateway(adapter, fixture.boundary)
        return WorkflowRunHost(
            scope = fixture.scope,
            boundary = fixture.boundary,
            gateway = gateway,
            declarations = declarations,
            skills = skills,
            commands = commands,
            queries = queries,
            operationKeys = WorkflowOperationKeySource { "s810-key-${sequence.incrementAndGet()}" },
        )
    }

    private fun key(name: String): String = "s810-$name-tracked"

    private suspend fun prepare(
        source: String = SOURCE,
        draftMemo: String = "Coffee",
    ): Fixture {
        val captureOutcome = commands.saveCapture(
            WorkflowCallerScope.User,
            SaveCaptureRequest("s810-capture-${sequence.incrementAndGet()}", source),
        )
        check(captureOutcome is WorkflowCommandOutcome.Applied) { "capture refused: $captureOutcome" }
        val captureId = (captureOutcome.change as WorkflowChange.CaptureSaved).captureId
        val draftId = database.pendingBankDraftDao().store(
            PendingBankDraftEntity(
                deliveryId = "s810-draft-${sequence.incrementAndGet()}",
                sourcePackage = "synthetic.fixture",
                notificationKey = null,
                postedAtEpochMillis = System.currentTimeMillis(),
                bank = "Synthetic Bank",
                parseStatus = IntakeParseStatus.PARSED,
                amountState = IntakeFieldState.PRESENT,
                amountVnd = 25_000,
                amountUnresolvedReason = null,
                direction = IntakeDirection.DEBIT,
                momentState = IntakeFieldState.PRESENT,
                statedDate = "2026-10-22",
                statedTime = "09:15",
                momentUnresolvedReason = null,
                memoState = IntakeFieldState.PRESENT,
                memo = draftMemo,
                memoUnresolvedReason = null,
                createdAtEpochMillis = System.currentTimeMillis(),
            ),
        )
        val scope = WorkflowCallerScope.AgentSession(
            "s810-session-${sequence.incrementAndGet()}",
            setOf(captureId),
            setOf(draftId),
        )
        val boundary = MaskedPromptBoundary(SensitiveVault(scope.scopeId))
        activeVault = boundary.vault
        val maskedSource = boundary.maskOutbound(source, MaskedPath.CONTEXT)
        return Fixture(captureId, draftId, scope, boundary, maskedSource)
    }

    private fun initialObservations(adapter: ScriptedAdapter): JSONArray {
        val prompt = adapter.prompts.first { it.contains("Trusted host observations") }
        val lines = prompt.lines()
        val start = lines.indexOfFirst { it.trimStart().startsWith("[") }
        return JSONArray(lines[start].trim())
    }

    private fun captureRef(adapter: ScriptedAdapter): String {
        val observations = initialObservations(adapter)
        for (index in 0 until observations.length()) {
            val item = observations.getJSONObject(index)
            if (item.optString("kind") == "capture") return item.getString("ref")
        }
        error("no capture alias in the initial observation")
    }

    private fun sourceText(adapter: ScriptedAdapter): String {
        val observations = initialObservations(adapter)
        for (index in 0 until observations.length()) {
            val item = observations.getJSONObject(index)
            if (item.optString("kind") == "capture") return item.getString("source_text")
        }
        error("no capture source text in the initial observation")
    }

    private fun lastEnvelope(adapter: ScriptedAdapter): JSONObject =
        JSONObject(adapter.toolResultPayloads.last().single())

    private fun previousEnvelope(adapter: ScriptedAdapter, turnsBack: Int): JSONObject =
        JSONObject(adapter.toolResultPayloads[adapter.toolResultPayloads.size - turnsBack].single())

    /** The test model's semantic choice: the alias whose value parses for the role. */
    private fun valueRef(source: JSONObject, role: String): String {
        val values = source.getJSONArray("values")
        for (index in 0 until values.length()) {
            val item = values.getJSONObject(index)
            val token = item.getString("display")
            val parses = when (role) {
                "amount" -> runCatching { activeVault.parseAmount(token) }.isSuccess
                "date" -> runCatching { activeVault.parseDate(token, 2026) }.isSuccess
                "time" -> runCatching { activeVault.parseTime(token) }.isSuccess
                else -> false
            }
            if (parses) return item.getString("ref")
        }
        error("expected a value usable as $role in the selected source")
    }

    private fun scripted(): MutableList<suspend () -> ScriptedTurn> = mutableListOf()
    private fun toolTurn(vararg calls: ScriptedCall) =
        ScriptedTurn(calls = calls.toList(), finish = HostedTurnFinish.ToolCalls)
    private fun complete(text: String) = ScriptedTurn(text = text, finish = HostedTurnFinish.Complete)
    private fun call(name: String, arguments: JSONObject) = ScriptedCall(name, arguments.toString())

    private data class Fixture(
        val captureId: Long,
        val draftId: Long,
        val scope: WorkflowCallerScope.AgentSession,
        val boundary: MaskedPromptBoundary,
        val maskedSource: String,
    )

    private class ScriptedCall(val name: String, val argumentsJson: String?)

    private class ScriptedTurn(
        val text: String? = null,
        val calls: List<ScriptedCall> = emptyList(),
        val finish: HostedTurnFinish,
    )

    private class ScriptedConversation(
        override val protocol: HostedProtocol,
        override val pendingCallIds: List<String>,
    ) : HostedConversation

    private class ScriptedAdapter(
        private val turns: List<suspend () -> ScriptedTurn>,
    ) : HostedGenerationAdapter {
        override val protocol: HostedProtocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS
        private var next = 0
        val prompts = mutableListOf<String>()
        val toolResultPayloads = mutableListOf<List<String>>()

        override fun begin(systemInstruction: String, userInstruction: String): HostedConversation {
            prompts += systemInstruction
            prompts += userInstruction
            return ScriptedConversation(protocol, emptyList())
        }

        override suspend fun generate(
            conversation: HostedConversation,
            tools: List<io.github.triet4p.aegisf.provider.HostedToolDeclaration>,
        ): HostedGenerationResult {
            val producer = turns.getOrNull(next) ?: error("no scripted turn ${next + 1}")
            next += 1
            val turn = producer()
            val calls = turn.calls.mapIndexed { index, call ->
                HostedToolCall(
                    callId = "call-$next-$index",
                    name = call.name,
                    argumentsJson = call.argumentsJson,
                    arguments = null,
                    disposition = ToolCallDisposition.READY,
                )
            }
            val continuation = if (turn.finish == HostedTurnFinish.ToolCalls) {
                ScriptedConversation(protocol, calls.map(HostedToolCall::callId))
            } else {
                null
            }
            return HostedGenerationResult.Turn(
                HostedTurn(
                    protocol = protocol,
                    requestedModel = MODEL,
                    reportedModel = MODEL,
                    assistantText = turn.text,
                    toolCalls = calls,
                    finish = turn.finish,
                    usage = null,
                    conversation = continuation,
                ),
            )
        }

        override fun continueWithToolResults(
            conversation: HostedConversation,
            results: List<HostedToolResult>,
            hostNotice: String?,
        ): HostedConversation {
            toolResultPayloads += results.map(HostedToolResult::outputJson)
            return ScriptedConversation(protocol, emptyList())
        }
    }

    private companion object {
        const val MODEL = "s810-scripted-model"
        const val SOURCE =
            "Coffee 25,000 VND on 2026-10-22 at 09:15. Note: synthetic fixture only."
    }
}
