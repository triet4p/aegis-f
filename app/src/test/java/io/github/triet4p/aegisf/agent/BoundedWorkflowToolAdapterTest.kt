package io.github.triet4p.aegisf.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.triet4p.aegisf.data.ClassificationEngine
import io.github.triet4p.aegisf.data.ClassificationEngineResult
import io.github.triet4p.aegisf.data.ContextLinkState
import io.github.triet4p.aegisf.data.IntakeDirection
import io.github.triet4p.aegisf.data.IntakeFieldState
import io.github.triet4p.aegisf.data.IntakeParseStatus
import io.github.triet4p.aegisf.data.IntakeReviewStatus
import io.github.triet4p.aegisf.data.LinkOperationQuery
import io.github.triet4p.aegisf.data.LedgerDatabase
import io.github.triet4p.aegisf.data.PendingBankDraftEntity
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.SaveCaptureRequest
import io.github.triet4p.aegisf.data.SystemLedgerClock
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowChange
import io.github.triet4p.aegisf.data.WorkflowCommandOutcome
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.data.laya.LayaEffectiveContext
import io.github.triet4p.aegisf.privacy.MaskedPromptBoundary
import io.github.triet4p.aegisf.privacy.SensitiveVault
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
class BoundedWorkflowToolAdapterTest {
    private lateinit var context: Context
    private lateinit var database: LedgerDatabase
    private lateinit var commands: WorkflowCommandService
    private lateinit var queries: WorkflowQueryService
    private lateinit var scope: WorkflowCallerScope.AgentSession
    private lateinit var boundary: MaskedPromptBoundary
    private lateinit var adapter: BoundedWorkflowToolAdapter
    private var captureId: Long = 0L

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        commands = WorkflowCommandService(
            database = database,
            clock = SystemLedgerClock,
            timezoneId = { "Asia/Ho_Chi_Minh" },
            engine = RefusingEngine,
        )
        queries = WorkflowQueryService(database)
        val saved = commands.saveCapture(
            WorkflowCallerScope.User,
            SaveCaptureRequest("s8.7-test-capture", SOURCE_TEXT),
        ) as WorkflowCommandOutcome.Applied
        captureId = (saved.change as WorkflowChange.CaptureSaved).captureId
        scope = WorkflowCallerScope.AgentSession(
            scopeId = "s8.7-test-session",
            captureIds = setOf(captureId),
            draftIds = emptySet(),
        )
        boundary = MaskedPromptBoundary(SensitiveVault(scope.scopeId))
        val declarations = S8AgentToolDeclarations.load(context.assets::open)
        adapter = BoundedWorkflowToolAdapter(
            commands = commands,
            queries = queries,
            session = WorkflowToolSession(scope, boundary, OPERATION_KEY),
            declarations = declarations,
        )
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    @Test
    fun `masked read interpretation and durable retry apply one real service operation`() = runBlocking {
        val contextResult = adapter.observeCaptureContext(captureId)
        assertEquals("2026-10-21", boundary.vault.rehydrateDate(token(contextResult, "DATE")))
        val maskedSource = contextResult.getString("sourceText")
        assertFalse(maskedSource.contains("28,000"))
        assertFalse(maskedSource.contains("2026-10-21"))
        assertFalse(maskedSource.contains("12:30"))
        assertNotEquals(SOURCE_TEXT.length, maskedSource.length)

        val amountToken = token(contextResult, "AMT")
        val dateToken = token(contextResult, "DATE")
        val timeToken = token(contextResult, "TIME")
        val call = updateCall(
            captureId = captureId,
            captureRevision = 0,
            eventRevision = JSONObject.NULL,
            maskedSource = maskedSource,
            amountToken = amountToken,
            dateToken = dateToken,
            timeToken = timeToken,
        )
        val first = adapter.execute(BoundedWorkflowToolAdapter.UPDATE_EVENT_INTERPRETATION, call)
        assertEquals("applied", first.getString("status"))
        val stored = database.noteEventDao().findForCapture(captureId).single()
        assertEquals(28_000L, stored.amountVnd)
        assertEquals("2026-10-21", stored.anchorDate)
        assertEquals("12:30", stored.anchorTime)
        assertEquals(SOURCE_TEXT, stored.normalizedText)
        assertEquals(0, stored.spanStart)
        assertEquals(SOURCE_TEXT.length, stored.spanEnd)
        assertEquals(0, stored.revision)
        val reread = adapter.observeCaptureContext(captureId).getJSONArray("events").getJSONObject(0)
        assertEquals(0, reread.getInt("spanStart"))
        assertEquals(maskedSource.length, reread.getInt("spanEnd"))
        val freshAdapter = BoundedWorkflowToolAdapter(
            commands = commands,
            queries = queries,
            session = WorkflowToolSession(
                scope,
                MaskedPromptBoundary(SensitiveVault(scope.scopeId)),
                "s8.7-fresh-observation",
            ),
            declarations = S8AgentToolDeclarations.load(context.assets::open),
        )
        val freshObservation = freshAdapter.observeCaptureContext(captureId)
        val freshMaskedSource = freshObservation.getString("sourceText")
        assertNotEquals(SOURCE_TEXT, freshMaskedSource)
        val freshEvent = freshObservation.getJSONArray("events").getJSONObject(0)
        assertEquals(0, freshEvent.getInt("spanStart"))
        assertEquals(freshMaskedSource.length, freshEvent.getInt("spanEnd"))
        assertTrue(database.transactionDao().findAll().isEmpty())
        assertEquals(1L, database.workflowOperationDao().countOf("UPDATE_EVENT"))
        assertEquals(OPERATION_KEY, database.workflowOperationDao().findByKey(OPERATION_KEY)?.idempotencyKey)
        assertEquals(null, database.workflowOperationDao().findByKey("model-does-not-own-this-key"))

        val replay = adapter.execute(BoundedWorkflowToolAdapter.UPDATE_EVENT_INTERPRETATION, call)
        assertEquals("no_change", replay.getString("status"))
        val afterReplay = database.noteEventDao().findForCapture(captureId).single()
        assertEquals(stored.id, afterReplay.id)
        assertEquals(stored.revision, afterReplay.revision)
        assertEquals(stored.amountVnd, afterReplay.amountVnd)
        assertEquals(stored.anchorDate, afterReplay.anchorDate)
        assertEquals(stored.anchorTime, afterReplay.anchorTime)
        assertEquals(SOURCE_TEXT, afterReplay.normalizedText)
        assertEquals(1L, database.workflowOperationDao().countOf("UPDATE_EVENT"))
        assertTrue(database.transactionDao().findAll().isEmpty())
        val draftId = database.pendingBankDraftDao().store(
            PendingBankDraftEntity(
                deliveryId = "s8.7-link-fixture",
                sourcePackage = "synthetic.fixture",
                notificationKey = null,
                postedAtEpochMillis = 1_798_000_000_000L,
                bank = "Fixture bank",
                parseStatus = IntakeParseStatus.PARSED,
                amountState = IntakeFieldState.PRESENT,
                amountVnd = 28_000L,
                amountUnresolvedReason = null,
                direction = IntakeDirection.DEBIT,
                momentState = IntakeFieldState.PRESENT,
                statedDate = "2026-10-21",
                statedTime = "12:30",
                momentUnresolvedReason = null,
                memoState = IntakeFieldState.PRESENT,
                memo = "Lunch 28,000 VND",
                memoUnresolvedReason = null,
                createdAtEpochMillis = 1_798_000_000_000L,
            ),
        )
        val linkScope = WorkflowCallerScope.AgentSession(
            scopeId = scope.scopeId,
            captureIds = scope.captureIds,
            draftIds = setOf(draftId),
        )
        fun scopedAdapter(key: String) = BoundedWorkflowToolAdapter(
            commands = commands,
            queries = queries,
            session = WorkflowToolSession(linkScope, boundary, key),
            declarations = S8AgentToolDeclarations.load(context.assets::open),
        )
        val queryAdapter = scopedAdapter("s8.7-query-op")
        val candidates = queryAdapter.execute(
            BoundedWorkflowToolAdapter.QUERY_DRAFT_CANDIDATES,
            JSONObject().put(
                "query",
                JSONObject()
                    .put("reviewStatus", IntakeReviewStatus.PENDING.name)
                    .put("onlyPossibleDuplicates", false)
                    .put("bounds", JSONObject().put("limit", 5).put("offset", 0)),
            ),
        )
        val candidate = candidates.getJSONArray("items").getJSONObject(0)
        assertEquals(draftId, candidate.getLong("draftId"))
        assertEquals(0, candidate.getInt("reviewRevision"))
        assertFalse(candidate.getString("memo").contains("28,000"))
        assertEquals(28_000L, boundary.vault.rehydrateAmount(token(candidate, "AMT")))

        val eventId = stored.id
        val proposal = scopedAdapter("s8.7-propose-op").execute(
            BoundedWorkflowToolAdapter.PROPOSE_LINK,
            JSONObject()
                .put("eventId", eventId)
                .put("draftId", draftId),
        )
        assertEquals("applied", proposal.getString("status"))
        val proposed = queries.linkOperations(
            LinkOperationQuery(eventId = eventId, draftId = draftId, bounds = QueryPageRequest(1, 0)),
        ).items.single()
        assertEquals(ContextLinkState.PROPOSED, proposed.state)
        assertEquals(0, database.transactionDao().findAll().size)

        val attachCall = JSONObject()
            .put("eventId", eventId)
            .put("draftId", draftId)
            .put("expectedRevision", proposed.revision)
            .put("userOverride", JSONObject.NULL)
        val attachAdapter = scopedAdapter("s8.7-attach-op")
        val attached = attachAdapter.execute(BoundedWorkflowToolAdapter.ATTACH_LINK, attachCall)
        assertEquals("applied", attached.getString("status"))
        val accepted = queries.linkOperations(
            LinkOperationQuery(eventId = eventId, draftId = draftId, bounds = QueryPageRequest(1, 0)),
        ).items.single()
        assertEquals(ContextLinkState.ACCEPTED, accepted.state)
        val retry = scopedAdapter("s8.7-attach-op").execute(
            BoundedWorkflowToolAdapter.ATTACH_LINK,
            attachCall,
        )
        assertEquals("no_change", retry.getString("status"))
        assertEquals(accepted, queries.linkOperations(
            LinkOperationQuery(eventId = eventId, draftId = draftId, bounds = QueryPageRequest(1, 0)),
        ).items.single())
        assertTrue(database.transactionDao().findAll().isEmpty())
    }

    @Test
    fun `malformed unknown spoofed stale cross-scope and swapped-token calls preserve exact state`() = runBlocking {
        val initial = adapter.observeCaptureContext(captureId)
        val maskedSource = initial.getString("sourceText")
        val complete = updateCall(
            captureId = captureId,
            captureRevision = 0,
            eventRevision = JSONObject.NULL,
            maskedSource = maskedSource,
            amountToken = token(initial, "AMT"),
            dateToken = token(initial, "DATE"),
            timeToken = token(initial, "TIME"),
        )
        assertEquals("schema_invalid", adapter.execute(
            BoundedWorkflowToolAdapter.UPDATE_EVENT_INTERPRETATION,
            JSONObject().put("captureId", captureId),
        ).getString("reason"))
        val callerSpoof = JSONObject(complete.toString()).put("scope", JSONObject().put("scopeId", "user"))
        assertEquals("schema_invalid", adapter.execute(
            BoundedWorkflowToolAdapter.UPDATE_EVENT_INTERPRETATION,
            callerSpoof,
        ).getString("reason"))
        val modelOperationSpoof = JSONObject(complete.toString()).put("idempotencyKey", "attacker-chosen")
        assertEquals("schema_invalid", adapter.execute(
            BoundedWorkflowToolAdapter.UPDATE_EVENT_INTERPRETATION,
            modelOperationSpoof,
        ).getString("reason"))
        assertEquals("unknown_tool", adapter.execute("execute_sql", JSONObject()).getString("reason"))
        assertEquals("unknown_tool", adapter.execute("confirm", JSONObject()).getString("reason"))
        assertEquals("unknown_tool", adapter.execute("delete", JSONObject()).getString("reason"))

        val otherCapture = commands.saveCapture(
            WorkflowCallerScope.User,
            SaveCaptureRequest("s8.7-test-other-capture", "A private fixture outside this agent scope."),
        ) as WorkflowCommandOutcome.Applied
        val outsideId = (otherCapture.change as WorkflowChange.CaptureSaved).captureId
        val crossCapture = JSONObject(complete.toString()).put("captureId", outsideId)
        assertEquals("caller_scope", adapter.execute(
            BoundedWorkflowToolAdapter.UPDATE_EVENT_INTERPRETATION,
            crossCapture,
        ).getString("reason"))
        val unknownCapture = JSONObject(complete.toString()).put("captureId", 999_999L)
        assertEquals("caller_scope", adapter.execute(
            BoundedWorkflowToolAdapter.UPDATE_EVENT_INTERPRETATION,
            unknownCapture,
        ).getString("reason"))

        val stale = updateCall(
            captureId = captureId,
            captureRevision = 99,
            eventRevision = JSONObject.NULL,
            maskedSource = maskedSource,
            amountToken = token(initial, "AMT"),
            dateToken = token(initial, "DATE"),
            timeToken = token(initial, "TIME"),
        )
        assertEquals("conflict", adapter.execute(
            BoundedWorkflowToolAdapter.UPDATE_EVENT_INTERPRETATION,
            stale,
        ).getString("status"))
        val afterStale = adapter.observeCaptureContext(captureId)
        assertEquals(0, afterStale.getInt("captureRevision"))
        assertTrue(afterStale.getJSONArray("events").length() == 0)

        val swapped = updateCall(
            captureId = captureId,
            captureRevision = 0,
            eventRevision = JSONObject.NULL,
            maskedSource = maskedSource,
            amountToken = token(initial, "DATE"),
            dateToken = token(initial, "DATE"),
            timeToken = token(initial, "TIME"),
        )
        assertEquals("invalid_scoped_value", adapter.execute(
            BoundedWorkflowToolAdapter.UPDATE_EVENT_INTERPRETATION,
            swapped,
        ).getString("reason"))
        val foreignToken = SensitiveVault("foreign-session").maskText("16,000 VND")
        val foreignScopeSwap = updateCall(
            captureId = captureId,
            captureRevision = 0,
            eventRevision = JSONObject.NULL,
            maskedSource = maskedSource,
            amountToken = foreignToken,
            dateToken = token(initial, "DATE"),
            timeToken = token(initial, "TIME"),
        )
        assertEquals("invalid_scoped_value", adapter.execute(
            BoundedWorkflowToolAdapter.UPDATE_EVENT_INTERPRETATION,
            foreignScopeSwap,
        ).getString("reason"))

        assertTrue(database.noteEventDao().findForCapture(captureId).isEmpty())
        assertTrue(database.noteEventDao().findForCapture(outsideId).isEmpty())
        assertTrue(queries.linkOperations(LinkOperationQuery(bounds = QueryPageRequest(500, 0))).items.isEmpty())
        assertTrue(database.transactionDao().findAll().isEmpty())
        assertEquals(0L, database.workflowOperationDao().countOf("UPDATE_EVENT"))
    }

    private fun updateCall(
        captureId: Long,
        captureRevision: Int,
        eventRevision: Any,
        maskedSource: String,
        amountToken: String,
        dateToken: String,
        timeToken: String,
    ): JSONObject = JSONObject()
        .put("captureId", captureId)
        .put("expectedCaptureRevision", captureRevision)
        .put("expectedEventRevision", eventRevision)
        .put("extractionVersion", "s8.7-test")
        .put(
            "interpretation",
            JSONObject()
                .put("amountVnd", amountToken)
                .put("merchant", JSONObject.NULL)
                .put("maskedSpanText", maskedSource)
                .put("normalizedText", maskedSource)
                .put("spanStart", 0)
                .put("spanEnd", maskedSource.length)
                .put(
                    "timeAnchor",
                    JSONObject()
                        .put("date", dateToken)
                        .put("intervalEndDate", JSONObject.NULL)
                        .put("intervalEndTime", JSONObject.NULL)
                        .put("time", timeToken)
                        .put("timezoneId", JSONObject.NULL),
                )
                .put("unresolvedReason", JSONObject.NULL),
        )

    private fun token(observation: JSONObject, prefix: String): String {
        val text = observation.toString()
        return Regex("⟦$prefix-[A-Za-z0-9]{4,12}-\\d{4}⟧").find(text)?.value
            ?: error("expected a scoped $prefix token in the masked observation")
    }

    private object RefusingEngine : ClassificationEngine {
        override suspend fun classify(
            context: LayaEffectiveContext,
            categories: List<io.github.triet4p.aegisf.data.CategoryEntity>,
        ): ClassificationEngineResult = ClassificationEngineResult.Failed("Laya was not invoked in this unit test.")
    }

    private companion object {
        const val OPERATION_KEY = "s8.7-trusted-operation-01"
        const val SOURCE_TEXT = "Lunch 28,000 VND on 2026-10-21 at 12:30"
    }
}
