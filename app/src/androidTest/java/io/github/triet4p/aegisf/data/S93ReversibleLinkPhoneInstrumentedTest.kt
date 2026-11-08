package io.github.triet4p.aegisf.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises reversible matching through production Room and the command boundary on an isolated phone DB. */
@RunWith(AndroidJUnit4::class)
class S93ReversibleLinkPhoneInstrumentedTest {
    @Test
    fun incompleteSearchCannotAutoAttachAndUserCanChooseAnotherCandidate() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("io.github.triet4p.aegisf.s812evidence", context.packageName)
        val runId = UUID.randomUUID().toString()
        val databaseName = "s9_3_reversible_${runId.replace("-", "")}.db"
        var database = openEvidenceDatabase(context, databaseName)
        try {
            val commands = commands(database)
            val queries = WorkflowQueryService(database)
            val ambiguousEvent = storeEvent(
                commands = commands,
                runId = runId,
                suffix = "nearby",
                amount = 28_000L,
                time = "15:00",
                merchant = null,
            )
            val firstDraft = storeDraft(database, runId, "nearby-first", 28_000L, "15:00")
            val secondDraft = storeDraft(database, runId, "nearby-second", 28_000L, "15:15")
            val completePage = queries.draftCandidates(
                DraftCandidateQuery(
                    reviewStatus = IntakeReviewStatus.PENDING,
                    bounds = QueryPageRequest(limit = QueryPageRequest.MAX_PAGE_SIZE),
                ),
            )
            assertFalse(completePage.hasMore)
            val ambiguousCandidates = queries.matchDraftCandidates(
                ambiguousEvent.eventId,
                completePage.items.map { it.id }.toSet(),
                pendingDatabaseComplete = !completePage.hasMore,
            )!!
            assertEquals(DraftCandidateMatchOutcome.AMBIGUOUS, ambiguousCandidates.outcome)
            assertEquals(setOf(firstDraft, secondDraft), ambiguousCandidates.candidates.map { it.draft.id }.toSet())
            assertTrue(database.contextLinkDao().findForDraft(firstDraft).isEmpty())
            assertTrue(database.contextLinkDao().findForDraft(secondDraft).isEmpty())
            val ambiguousEvidence = commands.inspectLinkEvidence(
                WorkflowCallerScope.User,
                ambiguousEvent.eventId,
                firstDraft,
            )!!
            assertEquals(LinkEligibility.AMBIGUOUS, ambiguousEvidence.eligibility)
            assertEquals(2, ambiguousEvidence.candidateDraftCount)

            val agent = WorkflowCallerScope.AgentSession(
                scopeId = "s9.3-phone-$runId-ambiguous",
                captureIds = setOf(ambiguousEvent.captureId),
                draftIds = completePage.items.map { it.id }.toSet(),
                draftScopeComplete = !completePage.hasMore,
            )
            val proposedFirst = commands.proposeLink(
                agent,
                ProposeLinkRequest("s9.3-$runId-propose-first", ambiguousEvent.eventId, firstDraft),
            )
            assertTrue(proposedFirst is WorkflowCommandOutcome.Applied)
            val unaccepted = commands.attachLink(
                agent,
                AttachLinkRequest("s9.3-$runId-auto-ambiguous", ambiguousEvent.eventId, firstDraft, 0),
            )
            assertTrue("ambiguous agent attachment was accepted: $unaccepted", unaccepted is WorkflowCommandOutcome.Denied)
            assertEquals(
                WorkflowDenialReason.ATTACH_POLICY,
                (unaccepted as WorkflowCommandOutcome.Denied).reason,
            )
            assertEquals(ContextLinkState.PROPOSED, database.contextLinkDao().findByPair(ambiguousEvent.eventId, firstDraft)!!.state)

            val firstRejected = commands.rejectLink(
                WorkflowCallerScope.User,
                RejectLinkRequest("s9.3-$runId-reject-first", ambiguousEvent.eventId, firstDraft, 0),
            )
            assertTrue(firstRejected is WorkflowCommandOutcome.Applied)
            val proposedAlternative = commands.proposeLink(
                WorkflowCallerScope.User,
                ProposeLinkRequest("s9.3-$runId-propose-alternative", ambiguousEvent.eventId, secondDraft),
            )
            assertTrue(proposedAlternative is WorkflowCommandOutcome.Applied)
            val chosenAlternative = commands.attachLink(
                WorkflowCallerScope.User,
                AttachLinkRequest("s9.3-$runId-accept-alternative", ambiguousEvent.eventId, secondDraft, 0),
            )
            assertTrue(chosenAlternative is WorkflowCommandOutcome.Applied)
            val staleUndo = commands.unlinkLink(
                WorkflowCallerScope.User,
                UnlinkLinkRequest("s9.3-$runId-stale-undo", ambiguousEvent.eventId, secondDraft, 0),
            )
            assertTrue("a stale undo did not conflict: $staleUndo", staleUndo is WorkflowCommandOutcome.Conflict)
            assertEquals(ContextLinkState.ACCEPTED, database.contextLinkDao().findByPair(ambiguousEvent.eventId, secondDraft)!!.state)
            val uniqueEvent = storeEvent(commands, runId, "unique", 64_000L, "16:00", "Known Cafe")
            val uniqueDraft = storeDraft(database, runId, "unique-target", 64_000L, "16:00", "Known Cafe")
            val uniquePage = queries.draftCandidates(
                DraftCandidateQuery(
                    reviewStatus = IntakeReviewStatus.PENDING,
                    bounds = QueryPageRequest(limit = QueryPageRequest.MAX_PAGE_SIZE),
                ),
            )
            assertFalse(uniquePage.hasMore)
            val uniqueMatches = queries.matchDraftCandidates(
                uniqueEvent.eventId,
                uniquePage.items.map { it.id }.toSet(),
                pendingDatabaseComplete = !uniquePage.hasMore,
            )!!
            assertEquals(DraftCandidateMatchOutcome.UNIQUE, uniqueMatches.outcome)
            assertEquals(listOf(uniqueDraft), uniqueMatches.candidates.map { it.draft.id })
            val uniqueEvidence = commands.inspectLinkEvidence(
                WorkflowCallerScope.User,
                uniqueEvent.eventId,
                uniqueDraft,
            )!!
            assertEquals(LinkEligibility.ELIGIBLE, uniqueEvidence.eligibility)
            assertEquals(1, uniqueEvidence.candidateDraftCount)
            val uniqueAgent = WorkflowCallerScope.AgentSession(
                scopeId = "s9.3-phone-$runId-unique",
                captureIds = setOf(uniqueEvent.captureId),
                draftIds = uniquePage.items.map { it.id }.toSet(),
                draftScopeComplete = !uniquePage.hasMore,
            )
            assertTrue(
                commands.proposeLink(
                    uniqueAgent,
                    ProposeLinkRequest("s9.3-$runId-propose-unique", uniqueEvent.eventId, uniqueDraft),
                ) is WorkflowCommandOutcome.Applied,
            )
            assertTrue(
                commands.attachLink(
                    uniqueAgent,
                    AttachLinkRequest("s9.3-$runId-auto-unique", uniqueEvent.eventId, uniqueDraft, 0),
                ) is WorkflowCommandOutcome.Applied,
            )
            assertEquals(
                ContextLinkState.ACCEPTED,
                database.contextLinkDao().findByPair(uniqueEvent.eventId, uniqueDraft)!!.state,
            )

            // A dismissed duplicate no longer competes: the surviving draft is the only live
            // candidate, so the app's own inspection must report it as eligible. While the
            // candidate count included rows in every state, one earlier dismissed draft made
            // every later draft with the same amount and day permanently ambiguous.
            val dismissedEvent = storeEvent(
                commands = commands,
                runId = runId,
                suffix = "dismissed-partner",
                amount = 91_000L,
                time = "18:00",
                merchant = null,
            )
            val keptDraft = storeDraft(database, runId, "dismissed-kept", 91_000L, "18:00")
            val droppedDraft = storeDraft(database, runId, "dismissed-dropped", 91_000L, "18:05")
            assertEquals(
                2,
                commands.inspectLinkEvidence(
                    WorkflowCallerScope.User,
                    dismissedEvent.eventId,
                    keptDraft,
                )!!.candidateDraftCount,
            )
            val dismissed = commands.dismissDraft(
                WorkflowCallerScope.User,
                DismissDraftRequest("s9.3-$runId-dismiss-partner", droppedDraft, 0),
            )
            assertTrue(
                "the duplicate draft was not dismissed: $dismissed",
                dismissed is WorkflowCommandOutcome.Applied,
            )
            val survivingEvidence = commands.inspectLinkEvidence(
                WorkflowCallerScope.User,
                dismissedEvent.eventId,
                keptDraft,
            )!!
            assertEquals(1, survivingEvidence.candidateDraftCount)
            assertEquals(LinkEligibility.ELIGIBLE, survivingEvidence.eligibility)

            val uniqueEventBeforeRestart = database.noteEventDao().findById(uniqueEvent.eventId)!!
            val uniqueDraftBeforeRestart = database.pendingBankDraftDao().findById(uniqueDraft)!!

            val captureBeforeRestart = database.captureMessageDao().findById(ambiguousEvent.captureId)!!
            val eventBeforeRestart = database.noteEventDao().findById(ambiguousEvent.eventId)!!
            val firstDraftBeforeRestart = database.pendingBankDraftDao().findById(firstDraft)!!
            val secondDraftBeforeRestart = database.pendingBankDraftDao().findById(secondDraft)!!
            val ledgerIdsBeforeRestart = database.transactionDao().findAll().map { it.id }

            database.close()
            database = openEvidenceDatabase(context, databaseName)
            val restartedCommands = commands(database)
            assertEquals(ContextLinkState.REJECTED, database.contextLinkDao().findByPair(ambiguousEvent.eventId, firstDraft)!!.state)
            assertEquals(ContextLinkState.ACCEPTED, database.contextLinkDao().findByPair(ambiguousEvent.eventId, secondDraft)!!.state)
            assertEquals(ContextLinkState.ACCEPTED, database.contextLinkDao().findByPair(uniqueEvent.eventId, uniqueDraft)!!.state)
            val rejectedRetry = restartedCommands.proposeLink(
                WorkflowCallerScope.User,
                ProposeLinkRequest("s9.3-$runId-retry-rejected", ambiguousEvent.eventId, firstDraft),
            )
            assertTrue("a rejected pair was recreated after restart: $rejectedRetry", rejectedRetry is WorkflowCommandOutcome.Denied)
            assertEquals(
                WorkflowDenialReason.LINK_ALREADY_REJECTED,
                (rejectedRetry as WorkflowCommandOutcome.Denied).reason,
            )
            assertEquals(1, database.contextLinkDao().findForEvent(ambiguousEvent.eventId).count { it.draftId == firstDraft })

            val undone = restartedCommands.unlinkLink(
                WorkflowCallerScope.User,
                UnlinkLinkRequest("s9.3-$runId-undo", ambiguousEvent.eventId, secondDraft, 1),
            )
            assertTrue(undone is WorkflowCommandOutcome.Applied)
            assertEquals(null, database.contextLinkDao().findByPair(ambiguousEvent.eventId, secondDraft))
            assertEquals(captureBeforeRestart.bodyText, database.captureMessageDao().findById(ambiguousEvent.captureId)!!.bodyText)
            val eventAfterUndo = database.noteEventDao().findById(ambiguousEvent.eventId)!!
            assertEquals(eventBeforeRestart.normalizedText, eventAfterUndo.normalizedText)
            assertEquals(eventBeforeRestart.amountVnd, eventAfterUndo.amountVnd)
            assertEquals(eventBeforeRestart.anchorDate, eventAfterUndo.anchorDate)
            assertEquals(firstDraftBeforeRestart, database.pendingBankDraftDao().findById(firstDraft))
            assertEquals(secondDraftBeforeRestart, database.pendingBankDraftDao().findById(secondDraft))
            assertEquals(ledgerIdsBeforeRestart, database.transactionDao().findAll().map { it.id })
            assertEquals(uniqueEventBeforeRestart.normalizedText, database.noteEventDao().findById(uniqueEvent.eventId)!!.normalizedText)
            assertEquals(uniqueEventBeforeRestart.amountVnd, database.noteEventDao().findById(uniqueEvent.eventId)!!.amountVnd)
            assertEquals(uniqueDraftBeforeRestart, database.pendingBankDraftDao().findById(uniqueDraft))

            val incompleteEvent = storeEvent(
                commands = restartedCommands,
                runId = runId,
                suffix = "incomplete",
                amount = 77_000L,
                time = "13:00",
                merchant = "Unique Shop",
            )
            repeat(QueryPageRequest.MAX_PAGE_SIZE) { index ->
                storeDraft(
                    database,
                    runId,
                    "incomplete-decoy-$index",
                    80_000L + index,
                    "13:00",
                )
            }
            val incompleteTargetDraft = storeDraft(database, runId, "incomplete-target", 77_000L, "13:00", "Unique Shop")
            val truncatedPage = WorkflowQueryService(database).draftCandidates(
                DraftCandidateQuery(
                    reviewStatus = IntakeReviewStatus.PENDING,
                    bounds = QueryPageRequest(limit = QueryPageRequest.MAX_PAGE_SIZE),
                ),
            )
            assertTrue(truncatedPage.hasMore)
            assertTrue(truncatedPage.items.any { it.id == incompleteTargetDraft })
            val incompleteMatches = WorkflowQueryService(database).matchDraftCandidates(
                incompleteEvent.eventId,
                truncatedPage.items.map { it.id }.toSet(),
                pendingDatabaseComplete = !truncatedPage.hasMore,
            )!!
            assertEquals(DraftCandidateMatchOutcome.INCOMPLETE, incompleteMatches.outcome)
            assertEquals(listOf(incompleteTargetDraft), incompleteMatches.candidates.map { it.draft.id })
            val incompleteAgent = WorkflowCallerScope.AgentSession(
                scopeId = "s9.3-phone-$runId-incomplete",
                captureIds = setOf(incompleteEvent.captureId),
                draftIds = truncatedPage.items.map { it.id }.toSet(),
                draftScopeComplete = !truncatedPage.hasMore,
            )
            val proposedUnique = restartedCommands.proposeLink(
                incompleteAgent,
                ProposeLinkRequest("s9.3-$runId-propose-incomplete", incompleteEvent.eventId, incompleteTargetDraft),
            )
            assertTrue(proposedUnique is WorkflowCommandOutcome.Applied)
            val incompleteAttach = restartedCommands.attachLink(
                incompleteAgent,
                AttachLinkRequest("s9.3-$runId-auto-incomplete", incompleteEvent.eventId, incompleteTargetDraft, 0),
            )
            assertTrue("an incomplete search authorized automatic attachment: $incompleteAttach", incompleteAttach is WorkflowCommandOutcome.Denied)
            assertEquals(
                WorkflowDenialReason.ATTACH_POLICY,
                (incompleteAttach as WorkflowCommandOutcome.Denied).reason,
            )
            val retainedProposal = database.contextLinkDao().findByPair(incompleteEvent.eventId, incompleteTargetDraft)!!
            assertEquals(ContextLinkState.PROPOSED, retainedProposal.state)
            assertEquals(0, retainedProposal.revision)
            assertEquals(ledgerIdsBeforeRestart, database.transactionDao().findAll().map { it.id })
        } finally {
            database.close()
        }
    }

    private fun openEvidenceDatabase(context: Context, name: String): LedgerDatabase =
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
        time: String,
        merchant: String?,
    ): StoredEvent {
        val body = "$suffix $amount VND on 2026-11-03 at $time"
        val savedCapture = commands.saveCapture(
            WorkflowCallerScope.User,
            SaveCaptureRequest("s9.3-$runId-$suffix-capture", body),
        ) as WorkflowCommandOutcome.Applied
        val captureId = (savedCapture.change as WorkflowChange.CaptureSaved).captureId
        val storedEvent = commands.updateEventInterpretation(
            WorkflowCallerScope.User,
            UpdateEventRequest(
                idempotencyKey = "s9.3-$runId-$suffix-event",
                captureId = captureId,
                expectedCaptureRevision = 0,
                interpretation = NoteEventInterpretation(
                    spanStart = 0,
                    spanEnd = body.length,
                    normalizedText = "$suffix purchase",
                    amountVnd = amount,
                    merchant = merchant,
                    timeAnchor = NoteEventTimeAnchor(
                        date = "2026-11-03",
                        time = time,
                        timezoneId = "Asia/Ho_Chi_Minh",
                    ),
                ),
                extractionVersion = "s9.3-phone-fixture",
            ),
        ) as WorkflowCommandOutcome.Applied
        val eventId = (storedEvent.change as WorkflowChange.EventStored).eventId
        return StoredEvent(captureId, eventId)
    }

    private suspend fun storeDraft(
        database: LedgerDatabase,
        runId: String,
        suffix: String,
        amount: Long,
        time: String,
        memo: String = "Synthetic purchase",
    ): Long = database.pendingBankDraftDao().store(
        PendingBankDraftEntity(
            deliveryId = "s9.3-$runId-$suffix",
            sourcePackage = "synthetic.fixture",
            notificationKey = null,
            postedAtEpochMillis = System.currentTimeMillis(),
            bank = "Synthetic bank",
            parseStatus = IntakeParseStatus.PARSED,
            amountState = IntakeFieldState.PRESENT,
            amountVnd = amount,
            amountUnresolvedReason = null,
            direction = IntakeDirection.DEBIT,
            momentState = IntakeFieldState.PRESENT,
            statedDate = "2026-11-03",
            statedTime = time,
            momentUnresolvedReason = null,
            memoState = IntakeFieldState.PRESENT,
            memo = memo,
            memoUnresolvedReason = null,
            createdAtEpochMillis = System.currentTimeMillis(),
        ),
    )

    private data class StoredEvent(val captureId: Long, val eventId: Long)
}
