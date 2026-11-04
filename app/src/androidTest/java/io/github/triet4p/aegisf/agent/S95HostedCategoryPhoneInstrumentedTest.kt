package io.github.triet4p.aegisf.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.triet4p.aegisf.data.*
import io.github.triet4p.aegisf.privacy.MaskedHostedGateway
import io.github.triet4p.aegisf.privacy.MaskedPath
import io.github.triet4p.aegisf.privacy.MaskedPromptBoundary
import io.github.triet4p.aegisf.privacy.SensitiveVault
import io.github.triet4p.aegisf.provider.HostedAdapters
import io.github.triet4p.aegisf.provider.HostedProtocol
import io.github.triet4p.aegisf.provider.HostedProviderSettingsStore
import io.github.triet4p.aegisf.provider.UrlConnectionHttpTransport
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.json.JSONObject
import org.junit.runner.RunWith

/** Explicitly opted-in live hosted smoke, using a fresh synthetic Room database only. */
@RunWith(AndroidJUnit4::class)
class S95HostedCategoryPhoneInstrumentedTest {
    @Test
    fun hostedRunUsesIndependentAcceptedContextWithoutConfirming() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("s95_live") == "yes")
        val linkInRun = InstrumentationRegistry.getArguments().getString("s95_link_in_run") == "yes"
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("io.github.triet4p.aegisf.s812evidence", context.packageName)
        val provider = requireNotNull(HostedProviderSettingsStore(context).active()) { "No active hosted provider" }
        assertEquals(HostedProtocol.GEMINI_GENERATE_CONTENT, provider.protocol)
        val runId = UUID.randomUUID().toString()
        val database = Room.databaseBuilder(context, LedgerDatabase::class.java, "s95_${runId.replace("-", "")}.db").build()
        try {
            val commands = WorkflowCommandService(database, timezoneId = { "Asia/Ho_Chi_Minh" })
            val query = WorkflowQueryService(database)
            val fuelCategory = database.categoryDao().insert(CategoryEntity(name = "Transport", createdAtEpochMillis = 1, updatedAtEpochMillis = 1))
            val foodCategory = database.categoryDao().insert(CategoryEntity(name = "Food", createdAtEpochMillis = 1, updatedAtEpochMillis = 1))
            suspend fun draft(suffix: String, amount: Long, time: String): Long = database.pendingBankDraftDao().store(
                PendingBankDraftEntity(
                    deliveryId = "$runId-$suffix", sourcePackage = "synthetic.fixture", notificationKey = null,
                    postedAtEpochMillis = 1_800_000_000_000L, bank = "Synthetic bank",
                    parseStatus = IntakeParseStatus.PARSED, amountState = IntakeFieldState.PRESENT,
                    amountVnd = amount, amountUnresolvedReason = null, direction = IntakeDirection.DEBIT,
                    momentState = IntakeFieldState.PRESENT, statedDate = "2026-11-04", statedTime = time,
                    momentUnresolvedReason = null, memoState = IntakeFieldState.PRESENT, memo = "Card purchase",
                    memoUnresolvedReason = null, createdAtEpochMillis = 1_800_000_000_000L,
                ),
            )
            val fuelDraft = draft("fuel", 50000, "15:00")
            val snackDraft = draft("snack", 20000, "15:15")
            val text = "Fuel for car 50000 at 15:00; snack at Circle K 20000 at 15:15"
            val saved = commands.saveCapture(WorkflowCallerScope.User, SaveCaptureRequest("$runId-capture", text)) as WorkflowCommandOutcome.Applied
            val captureId = (saved.change as WorkflowChange.CaptureSaved).captureId
            suspend fun event(span: String, amount: Long, time: String) {
                val start = text.indexOf(span)
                val interpretation = NoteEventInterpretation(
                    spanStart = start, spanEnd = start + span.length, normalizedText = span,
                    amountVnd = amount,
                    timeAnchor = NoteEventTimeAnchor(date = "2026-11-04", time = time, timezoneId = "Asia/Ho_Chi_Minh"),
                )
                if (linkInRun) {
                    val outcome = EventContextRepository(database).recordEvent(
                        captureId = captureId, expectedCaptureRevision = 0,
                        interpretation = interpretation, extractionVersion = "s95-phone-fixture",
                    )
                    assertTrue(outcome is NoteEventWriteResult.Stored)
                } else {
                    val outcome = commands.updateEventInterpretation(
                        WorkflowCallerScope.User,
                        UpdateEventRequest(
                            idempotencyKey = "$runId-$time", captureId = captureId, expectedCaptureRevision = 0,
                            interpretation = interpretation, extractionVersion = "s95-phone-fixture",
                        ),
                    )
                    assertTrue(outcome is WorkflowCommandOutcome.Applied)
                }
            }
            event("Fuel for car 50000 at 15:00", 50000, "15:00")
            event("snack at Circle K 20000 at 15:15", 20000, "15:15")
            if (linkInRun) {
                assertTrue(query.draftContextLinks(fuelDraft, QueryPageRequest(10, 0)).items.isEmpty())
                assertTrue(query.draftContextLinks(snackDraft, QueryPageRequest(10, 0)).items.isEmpty())
            }
            val scope = WorkflowCallerScope.AgentSession("s95-$runId", setOf(captureId), setOf(fuelDraft, snackDraft))
            val boundary = MaskedPromptBoundary(SensitiveVault(scope.scopeId))
            val traceStore = AgentTraceStore(database.agentTraceDao())
            val trace = AgentTraceRecorder.enabled(traceStore)
            val adapter = HostedAdapters.create(provider.validatedConfig(scope.scopeId), TracingHttpTransport(UrlConnectionHttpTransport(), trace))
            val host = WorkflowRunHost(
                scope, boundary, MaskedHostedGateway(adapter, boundary),
                AgentToolDeclarations.load(context.assets::open), WorkflowSkillCatalog.load(context.assets::open),
                commands, query, WorkflowOperationKeySource { UUID.randomUUID().toString() }, trace = trace,
            )
            val request = WorkflowRunRequest(
                intents = if (linkInRun) setOf(
                    WorkflowIntent.INTERPRET_EVENTS, WorkflowIntent.SEARCH_DRAFTS,
                    WorkflowIntent.LINK_EVENT_TO_DRAFT, WorkflowIntent.DRAFT_CATEGORY_REVIEW,
                ) else setOf(WorkflowIntent.DRAFT_CATEGORY_REVIEW),
                userRequest = if (linkInRun) {
                    "For both existing note events, inspect matching pending drafts, attach each unique supported match, then save each affected draft's appropriate existing category using its own linked context. Never confirm."
                } else {
                    "Review both pending drafts using each linked note. Save the appropriate existing category for each draft, without confirming either."
                },
                limits = WorkflowRunLimits(maxTurns = 32, maxToolCalls = 64, maxWallClockMillis = 600_000),
                requiredIntents = emptySet(),
            )
            val history = AgentRunHistory(database, traces = traceStore)
            val admitted = history.admitRun(scope, request, boundary, provider.maxOutputTokens)
            val outcome = BoundedWorkflowExecutor(host).startTracked(admitted, request, history, provider.maxOutputTokens)
            val fuelLinks = query.draftContextLinks(fuelDraft, QueryPageRequest(10, 0)).items
            val snackLinks = query.draftContextLinks(snackDraft, QueryPageRequest(10, 0)).items
            assertEquals(ContextLinkState.ACCEPTED, fuelLinks.single().state)
            assertEquals(ContextLinkState.ACCEPTED, snackLinks.single().state)
            assertTrue(fuelLinks.single().readingText!!.contains("Fuel"))
            assertTrue(snackLinks.single().readingText!!.contains("snack"))
            val fuel = database.pendingBankDraftDao().findById(fuelDraft)!!
            val snack = database.pendingBankDraftDao().findById(snackDraft)!!
            assertEquals("Hosted outcome: ${outcome.javaClass.simpleName}", fuelCategory, fuel.reviewCategoryId)
            assertEquals(foodCategory, snack.reviewCategoryId)
            assertEquals(IntakeReviewStatus.PENDING, fuel.reviewStatus)
            assertEquals(IntakeReviewStatus.PENDING, snack.reviewStatus)
            assertTrue(database.transactionDao().findAll().isEmpty())
            val events = traceStore.findForRun(admitted.runId)
            assertTrue(events.any { it.kind == AGENT_TRACE_KIND_PROVIDER_RESPONSE || it.kind == AGENT_TRACE_KIND_PROVIDER_STREAM_END })
            assertTrue(events.any { it.kind == AGENT_TRACE_KIND_TOOL_CALL && it.payloadJson.contains("update_draft_review") })
            if (linkInRun) {
                val attachResults = events.filter { it.kind == AGENT_TRACE_KIND_TOOL_RESULT }
                    .map { JSONObject(it.payloadJson) }
                    .filter { it.optString("name") == "attach_link" }
                    .map { JSONObject(it.getString("resultJson")) }
                val contexts = attachResults.flatMap { result ->
                    val observations = result.getJSONArray("observations")
                    (0 until observations.length()).map { observations.getJSONObject(it) }
                }.filter { it.optString("kind") == "draft" }
                    .map { it.getJSONArray("linked_context") }
                    .filter { it.length() == 1 }
                    .map { it.getJSONObject(0).getString("text") }
                val expectedFuel = boundary.maskOutbound("Fuel for car 50000 at 15:00", MaskedPath.TOOL_RESULT)
                val expectedSnack = boundary.maskOutbound("snack at Circle K 20000 at 15:15", MaskedPath.TOOL_RESULT)
                assertTrue("Fuel context missing from accepted-link result", expectedFuel in contexts)
                assertTrue("Snack context missing from accepted-link result", expectedSnack in contexts)
            }
        } finally {
            database.close()
        }
    }
}
