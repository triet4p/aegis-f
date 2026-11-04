package io.github.triet4p.aegisf.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.triet4p.aegisf.notification.PermittedNotificationContent
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises durable reconciliation safety through the production Room and command boundaries. */
@RunWith(AndroidJUnit4::class)
class S94ArrivalLifecyclePhoneInstrumentedTest {
    @Test
    fun userEditsAndModelReextractionKeepOneStableEvent() = runBlocking {
        val context = isolatedContext()
        val runId = UUID.randomUUID().toString()
        val database = openDatabase(context, runId, "user_edit")
        try {
            val commands = commands(database)
            val body = "Synthetic coffee purchase"
            val capture = commands.saveCapture(
                WorkflowCallerScope.User,
                SaveCaptureRequest("s9.4-$runId-capture", body),
            ) as WorkflowCommandOutcome.Applied
            val captureId = (capture.change as WorkflowChange.CaptureSaved).captureId
            val agent = WorkflowCallerScope.AgentSession(
                scopeId = "s9.4-$runId-model",
                captureIds = setOf(captureId),
                draftIds = emptySet(),
                draftScopeComplete = true,
            )
            val first = commands.updateEventInterpretation(
                agent,
                UpdateEventRequest(
                    idempotencyKey = "s9.4-$runId-model-first",
                    captureId = captureId,
                    expectedCaptureRevision = 0,
                    interpretation = reading(body, "Model reading", 28_000L, "2026-11-03", "15:00"),
                    extractionVersion = "model-v1",
                ),
            ) as WorkflowCommandOutcome.Applied
            val eventId = (first.change as WorkflowChange.EventStored).eventId
            val original = database.noteEventDao().findById(eventId)!!

            val regenerated = commands.updateEventInterpretation(
                agent,
                UpdateEventRequest(
                    idempotencyKey = "s9.4-$runId-model-regenerated",
                    captureId = captureId,
                    expectedCaptureRevision = 0,
                    expectedEventRevision = original.revision,
                    interpretation = reading(body, "Regenerated model reading", 28_000L, "2026-11-03", "15:00"),
                    extractionVersion = "model-v2",
                ),
            ) as WorkflowCommandOutcome.Applied
            assertEquals(eventId, (regenerated.change as WorkflowChange.EventRevised).eventId)
            val afterRegeneration = database.noteEventDao().findById(eventId)!!
            assertEquals(original.eventIdentity, afterRegeneration.eventIdentity)
            assertFalse(afterRegeneration.userEdited)
            assertEquals(1, database.noteEventDao().findForCapture(captureId).size)

            val userEdit = commands.updateEventInterpretation(
                WorkflowCallerScope.User,
                UpdateEventRequest(
                    idempotencyKey = "s9.4-$runId-user-edit",
                    captureId = captureId,
                    expectedCaptureRevision = 0,
                    expectedEventRevision = afterRegeneration.revision,
                    interpretation = reading(body, "User-corrected reading", 29_000L, "2026-11-03", "15:00"),
                    extractionVersion = "model-v2",
                ),
            ) as WorkflowCommandOutcome.Applied
            assertEquals(eventId, (userEdit.change as WorkflowChange.EventRevised).eventId)
            val edited = database.noteEventDao().findById(eventId)!!
            assertTrue(edited.userEdited)

            val lateModelResult = commands.updateEventInterpretation(
                agent,
                UpdateEventRequest(
                    idempotencyKey = "s9.4-$runId-model-late",
                    captureId = captureId,
                    expectedCaptureRevision = 0,
                    expectedEventRevision = edited.revision,
                    interpretation = reading(body, "Late regenerated reading", 30_000L, "2026-11-03", "15:00"),
                    extractionVersion = "model-v3",
                ),
            ) as WorkflowCommandOutcome.Denied
            assertEquals(WorkflowDenialReason.USER_DECISION_REQUIRED, lateModelResult.reason)
            assertEquals(edited, database.noteEventDao().findById(eventId))
            assertEquals(1, database.noteEventDao().findForCapture(captureId).size)
            assertEquals(body, database.captureMessageDao().findById(captureId)!!.bodyText)
            assertTrue(database.transactionDao().findAll().isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun ambiguityAndIncompletePendingScopeDoNotAutoAttach() = runBlocking {
        val context = isolatedContext()
        val runId = UUID.randomUUID().toString()
        val database = openDatabase(context, runId, "ambiguous")
        try {
            val commands = commands(database)
            val first = storeNotification(database, runId, "equal-first", 50_000L, "05/11/2026", "15:00")
            val second = storeNotification(database, runId, "equal-second", 50_000L, "05/11/2026", "15:15")
            val ambiguous = storeEvent(commands, runId, "equal", 50_000L, "2026-11-05", "15:00")
            val inspection = commands.inspectLinkEvidence(WorkflowCallerScope.User, ambiguous.eventId, first)!!
            assertEquals(LinkEligibility.AMBIGUOUS, inspection.eligibility)
            assertEquals(2, inspection.candidateDraftCount)
            assertTrue(database.contextLinkDao().findForEvent(ambiguous.eventId).isEmpty())
            assertEquals(0, database.contextLinkDao().findForDraft(second).size)

            repeat(QueryPageRequest.MAX_PAGE_SIZE) { index ->
                storeDraft(
                    database,
                    runId,
                    "scope-decoy-$index",
                    amount = 80_000L + index,
                    date = "2026-11-06",
                    time = "12:00",
                )
            }
            val target = storeNotification(database, runId, "scope-target", 77_000L, "06/11/2026", "13:00")
            val incomplete = storeEvent(commands, runId, "scope", 77_000L, "2026-11-06", "13:00")
            assertNull(database.contextLinkDao().findForEvent(incomplete.eventId).firstOrNull())
            assertEquals(
                ARRIVAL_OUTCOME_INCOMPLETE,
                database.arrivalReconciliationOperationDao()
                    .findById("event:${incomplete.eventId}:0")!!.outcome,
            )
            assertEquals(0, database.contextLinkDao().findForDraft(target).size)
            assertTrue(database.transactionDao().findAll().isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun rejectionAndDismissalRemainTerminalAcrossNotificationReplay() = runBlocking {
        val context = isolatedContext()
        val runId = UUID.randomUUID().toString()
        val database = openDatabase(context, runId, "terminal")
        try {
            val commands = commands(database)
            val draftId = storeNotification(database, runId, "rejected", 32_000L, "07/11/2026", "11:00")
            val event = storeEvent(commands, runId, "rejected", 32_000L, "2026-11-07", "11:00")
            val accepted = database.contextLinkDao().findByPair(event.eventId, draftId)!!
            assertEquals(ContextLinkState.ACCEPTED, accepted.state)
            assertTrue(
                commands.rejectLink(
                    WorkflowCallerScope.User,
                    RejectLinkRequest("s9.4-$runId-reject", event.eventId, draftId, accepted.revision),
                ) is WorkflowCommandOutcome.Applied,
            )
            assertEquals(
                ContextLinkState.REJECTED,
                database.contextLinkDao().findByPair(event.eventId, draftId)!!.state,
            )

            val replayed = storeNotification(database, runId, "rejected", 32_000L, "07/11/2026", "11:00")
            assertEquals(draftId, replayed)
            val duplicate = storeNotification(database, runId, "rejected-duplicate", 32_000L, "07/11/2026", "11:00")
            assertTrue(database.pendingBankDraftDao().findById(duplicate)!!.possibleDuplicateOfDraftId != null)
            assertEquals(
                ContextLinkState.REJECTED,
                database.contextLinkDao().findByPair(event.eventId, draftId)!!.state,
            )
            assertEquals(1, database.contextLinkDao().findForEvent(event.eventId).size)

            val dismissedDraft = storeNotification(database, runId, "dismissed", 71_000L, "08/11/2026", "14:00")
            assertTrue(
                commands.dismissDraft(
                    WorkflowCallerScope.User,
                    DismissDraftRequest("s9.4-$runId-dismiss", dismissedDraft, expectedRevision = 0),
                ) is WorkflowCommandOutcome.Applied,
            )
            assertEquals(
                dismissedDraft,
                storeNotification(database, runId, "dismissed", 71_000L, "08/11/2026", "14:00"),
            )
            assertEquals(IntakeReviewStatus.DISMISSED, database.pendingBankDraftDao().findById(dismissedDraft)!!.reviewStatus)
            val lateEvent = storeEvent(commands, runId, "dismissed", 71_000L, "2026-11-08", "14:00")
            assertTrue(database.contextLinkDao().findForEvent(lateEvent.eventId).isEmpty())
            assertEquals(0, database.transactionDao().findAll().size)
        } finally {
            database.close()
        }
    }

    @Test
    fun pendingWorkResumesAfterRoomReopenAndStaleEventRevisionIsDiscarded() = runBlocking {
        val context = isolatedContext()
        val runId = UUID.randomUUID().toString()
        val databaseName = "s9_4_restart_${runId.replace("-", "")}.db"
        var database = openDatabase(context, databaseName)
        try {
            val commands = commands(database)
            val firstDraft = storeNotification(database, runId, "resume", 45_000L, "09/11/2026", "10:00")
            storeNotification(database, runId, "stale", 67_000L, "10/11/2026", "12:00")
            val resumable = storeUnqueuedEvent(database, commands, runId, "resume", 45_000L, "2026-11-09", "10:00")
            val stale = storeUnqueuedEvent(database, commands, runId, "stale", 67_000L, "2026-11-10", "12:00")
            assertEquals(
                ARRIVAL_OPERATION_PENDING,
                database.arrivalReconciliationOperationDao()
                    .findById("event:${resumable.eventId}:0")!!.state,
            )

            val changed = EventContextRepository(database).recordEvent(
                captureId = stale.captureId,
                expectedCaptureRevision = 0,
                expectedEventRevision = 0,
                interpretation = reading(
                    "Synthetic purchase stale",
                    "Late correction",
                    68_000L,
                    "2026-11-10",
                    "12:00",
                ),
                extractionVersion = "s9.4-late-result",
                userEdited = false,
            )
            assertEquals(NoteEventWriteResult.Revised(stale.eventId, 1), changed)
            database.close()

            database = openDatabase(context, databaseName)
            ArrivalReconciliationCoordinator(database).resumePending()
            assertEquals(
                ContextLinkState.ACCEPTED,
                database.contextLinkDao().findByPair(resumable.eventId, firstDraft)!!.state,
            )
            assertNull(database.contextLinkDao().findByPair(stale.eventId, database.pendingBankDraftDao().findAll().single { it.notificationKey == "s9.4-$runId-stale" }.id))
            assertEquals(
                ARRIVAL_OUTCOME_LINKED,
                database.arrivalReconciliationOperationDao()
                    .findById("event:${resumable.eventId}:0")!!.outcome,
            )
            assertEquals(
                ARRIVAL_OUTCOME_STALE,
                database.arrivalReconciliationOperationDao()
                    .findById("event:${stale.eventId}:0")!!.outcome,
            )
            assertEquals(1, database.noteEventDao().findForCapture(resumable.captureId).size)
            assertTrue(database.transactionDao().findAll().isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun agentFirst32GrantCannotAttachWhenAnotherPendingDraftExists() = runBlocking {
        val context = isolatedContext()
        val runId = UUID.randomUUID().toString()
        val database = openDatabase(context, runId, "agent_scope")
        try {
            val commands = commands(database)
            val target = storeNotification(database, runId, "agent-target", 62_000L, "11/11/2026", "13:00")
            repeat(32) { index ->
                storeDraft(
                    database,
                    runId,
                    "agent-decoy-$index",
                    amount = 90_000L + index,
                    date = "2026-11-12",
                    time = "12:00",
                )
            }
            val body = "Synthetic purchase agent-scope"
            val savedCapture = commands.saveCapture(
                WorkflowCallerScope.User,
                SaveCaptureRequest("s9.4-$runId-agent-capture", body),
            ) as WorkflowCommandOutcome.Applied
            val captureId = (savedCapture.change as WorkflowChange.CaptureSaved).captureId
            val event = EventContextRepository(database).recordEvent(
                captureId = captureId,
                expectedCaptureRevision = 0,
                interpretation = reading(
                    body,
                    body,
                    62_000L,
                    "2026-11-11",
                    "13:00",
                ),
                extractionVersion = "s9.4-agent-scope-fixture",
                userEdited = false,
            ) as NoteEventWriteResult.Stored

            val grant = WorkflowQueryService(database).draftCandidates(
                DraftCandidateQuery(
                    reviewStatus = IntakeReviewStatus.PENDING,
                    bounds = QueryPageRequest(limit = 32),
                ),
            )
            assertTrue(grant.hasMore)
            assertTrue(grant.items.any { draft -> draft.id == target })
            val agent = WorkflowCallerScope.AgentSession(
                scopeId = "s9.4-$runId-first-32",
                captureIds = setOf(captureId),
                draftIds = grant.items.mapTo(linkedSetOf()) { draft -> draft.id },
                draftScopeComplete = !grant.hasMore,
            )
            assertTrue(
                commands.proposeLink(
                    agent,
                    ProposeLinkRequest("s9.4-$runId-propose-first-32", event.eventId, target),
                ) is WorkflowCommandOutcome.Applied,
            )
            val attachment = commands.attachLink(
                agent,
                AttachLinkRequest("s9.4-$runId-attach-first-32", event.eventId, target, 0),
            ) as WorkflowCommandOutcome.Denied
            assertEquals(WorkflowDenialReason.ATTACH_POLICY, attachment.reason)
            assertEquals(
                ContextLinkState.PROPOSED,
                database.contextLinkDao().findByPair(event.eventId, target)!!.state,
            )
            assertTrue(database.transactionDao().findAll().isEmpty())
        } finally {
            database.close()
        }
    }

    private fun isolatedContext(): Context =
        ApplicationProvider.getApplicationContext<Context>().also { context ->
            assertEquals("io.github.triet4p.aegisf.s812evidence", context.packageName)
        }

    private fun openDatabase(context: Context, runId: String, suffix: String): LedgerDatabase =
        openDatabase(context, "s9_4_${suffix}_${runId.replace("-", "")}.db")

    private fun openDatabase(context: Context, name: String): LedgerDatabase =
        Room.databaseBuilder(context.applicationContext, LedgerDatabase::class.java, name).build()

    private fun commands(database: LedgerDatabase) = WorkflowCommandService(
        database = database,
        timezoneId = { "Asia/Ho_Chi_Minh" },
    )

    private suspend fun storeEvent(
        commands: WorkflowCommandService,
        runId: String,
        suffix: String,
        amount: Long,
        date: String,
        time: String,
    ): StoredEvent {
        val body = "Synthetic purchase $suffix"
        val savedCapture = commands.saveCapture(
            WorkflowCallerScope.User,
            SaveCaptureRequest("s9.4-$runId-$suffix-capture", body),
        ) as WorkflowCommandOutcome.Applied
        val captureId = (savedCapture.change as WorkflowChange.CaptureSaved).captureId
        val storedEvent = commands.updateEventInterpretation(
            WorkflowCallerScope.User,
            UpdateEventRequest(
                idempotencyKey = "s9.4-$runId-$suffix-event",
                captureId = captureId,
                expectedCaptureRevision = 0,
                interpretation = reading(body, body, amount, date, time),
                extractionVersion = "s9.4-phone-fixture",
            ),
        ) as WorkflowCommandOutcome.Applied
        return StoredEvent(captureId, (storedEvent.change as WorkflowChange.EventStored).eventId)
    }

    private suspend fun storeUnqueuedEvent(
        database: LedgerDatabase,
        commands: WorkflowCommandService,
        runId: String,
        suffix: String,
        amount: Long,
        date: String,
        time: String,
    ): StoredEvent {
        val body = "Synthetic purchase $suffix"
        val savedCapture = commands.saveCapture(
            WorkflowCallerScope.User,
            SaveCaptureRequest("s9.4-$runId-$suffix-capture", body),
        ) as WorkflowCommandOutcome.Applied
        val captureId = (savedCapture.change as WorkflowChange.CaptureSaved).captureId
        return database.withTransaction {
            val stored = EventContextRepository(database).recordEvent(
                captureId = captureId,
                expectedCaptureRevision = 0,
                interpretation = reading(body, body, amount, date, time),
                extractionVersion = "s9.4-restart-fixture",
                userEdited = false,
            ) as NoteEventWriteResult.Stored
            ArrivalReconciliationCoordinator(database).enqueueEvent(stored.eventId, stored.revision, 0)
            StoredEvent(captureId, stored.eventId)
        }
    }

    private fun reading(
        body: String,
        normalizedText: String,
        amount: Long,
        date: String,
        time: String,
    ) = NoteEventInterpretation(
        spanStart = 0,
        spanEnd = body.length,
        normalizedText = normalizedText,
        amountVnd = amount,
        merchant = "Known Cafe",
        timeAnchor = NoteEventTimeAnchor(date = date, time = time, timezoneId = "Asia/Ho_Chi_Minh"),
    )

    private suspend fun storeNotification(
        database: LedgerDatabase,
        runId: String,
        suffix: String,
        amount: Long,
        date: String,
        time: String,
    ): Long {
        val amountText = "${amount / 1_000},${(amount % 1_000).toString().padStart(3, '0')}"
        return IntakeRepository(database).storePermitted(
            sourcePackage = "com.vietinbank.ipay",
            notificationKey = "s9.4-$runId-$suffix",
            postedAtEpochMillis = FIXTURE_POSTED_AT,
            content = PermittedNotificationContent(
                title = "Tin biến động số dư",
                text = listOf(
                    "VietinBank: $date $time",
                    "TK: Redacted",
                    "GD: -$amountText VND",
                    "SDC: Redacted",
                    "ND: Known Cafe",
                ).joinToString("\n"),
                bigText = null,
            ),
        )
    }

    private suspend fun storeDraft(
        database: LedgerDatabase,
        runId: String,
        suffix: String,
        amount: Long,
        date: String,
        time: String,
    ): Long = database.pendingBankDraftDao().store(
        PendingBankDraftEntity(
            deliveryId = "s9.4-$runId-$suffix",
            sourcePackage = "synthetic.fixture",
            notificationKey = suffix,
            postedAtEpochMillis = FIXTURE_POSTED_AT,
            bank = "Synthetic bank",
            parseStatus = IntakeParseStatus.PARSED,
            amountState = IntakeFieldState.PRESENT,
            amountVnd = amount,
            amountUnresolvedReason = null,
            direction = IntakeDirection.DEBIT,
            momentState = IntakeFieldState.PRESENT,
            statedDate = date,
            statedTime = time,
            momentUnresolvedReason = null,
            memoState = IntakeFieldState.PRESENT,
            memo = "Known Cafe",
            memoUnresolvedReason = null,
            createdAtEpochMillis = 0L,
        ),
    )

    private data class StoredEvent(val captureId: Long, val eventId: Long)

    private companion object {
        const val FIXTURE_POSTED_AT = 1_800_000_000_000L
    }
}
