package io.github.triet4p.aegisf.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S9.7 correction and confirmed-record boundaries through production Room and the one command
 * boundary on an isolated phone database.
 *
 * The cases are the ones the acceptance names: a note edit or delete reopens a category the
 * hosted workflow chose from that context, refuses a write that observed the old context, and
 * keeps a category the user chose; a confirmed record keeps its note provenance and ledger row
 * while the note text is gone; and a link decision or undo never mutates a finalized record.
 */
@RunWith(AndroidJUnit4::class)
class S97CorrectionBoundaryPhoneInstrumentedTest {

    @Test
    fun noteEditReopensHostedReviewAndConfirmedRecordKeepsItsProvenance() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("io.github.triet4p.aegisf.s812evidence", context.packageName)
        val runId = UUID.randomUUID().toString().replace("-", "")
        val databaseName = "s9_7_boundary_$runId.db"
        var pendingDraftId = -1L
        var confirmedDraftId = -1L
        var database = openEvidenceDatabase(context, databaseName)
        try {
            val commands = commands(database)
            val queries = WorkflowQueryService(database)
            val categoryId = insertCategory(database, "Fuel")

            // --- A pending draft whose category was chosen by the hosted workflow ---------
            val pending = storeFixture(
                commands = commands,
                database = database,
                runId = runId,
                suffix = "pending",
                amount = 50_000L,
                time = "15:00",
                attach = true,
            )
            val pendingDraft = database.pendingBankDraftDao().findById(pending.draftId)!!
            pendingDraftId = pending.draftId
            val hostedContext = queries.draftContextSnapshot(pending.draftId).second
            val hostedCatalog = categoryCatalogRevision(queries.categoryCatalog())
            val hosted = commands.selectDraftCategory(
                WorkflowCallerScope.AgentSession(
                    "s9.7-$runId-hosted",
                    setOf(pending.captureId),
                    setOf(pending.draftId),
                ),
                SelectDraftCategoryRequest(
                    "s9.7-$runId-hosted-pick",
                    pending.draftId,
                    pendingDraft.reviewRevision,
                    categoryId,
                    hostedContext,
                    hostedCatalog,
                ),
            )
            assertTrue("hosted category was refused: $hosted", hosted is WorkflowCommandOutcome.Applied)
            val chosen = database.pendingBankDraftDao().findById(pending.draftId)!!
            assertEquals(categoryId, chosen.reviewCategoryId)
            assertEquals(ReviewCategoryOrigin.HOSTED, chosen.reviewCategoryOrigin)
            assertEquals(ReviewQueueGroup.READY, queries.reviewQueueEntry(pending.draftId)!!.group)

            val pendingCapture = database.captureMessageDao().findById(pending.captureId)!!
            val edited = commands.reviseCapture(
                WorkflowCallerScope.User,
                ReviseCaptureRequest(
                    "s9.7-$runId-edit",
                    pending.captureId,
                    pendingCapture.revision,
                    "S97 corrected pending note",
                ),
            )
            assertTrue("note edit was refused: $edited", edited is WorkflowCommandOutcome.Applied)
            val reopened = database.pendingBankDraftDao().findById(pending.draftId)!!
            assertNull("hosted category survived the note edit", reopened.reviewCategoryId)
            assertFalse(reopened.reviewCategoryResolved)
            assertEquals(ReviewCategoryOrigin.NONE, reopened.reviewCategoryOrigin)
            assertEquals(
                ReviewQueueGroup.NEEDS_CATEGORY,
                queries.reviewQueueEntry(pending.draftId)!!.group,
            )
            assertTrue(
                "pending link survived the note edit",
                database.contextLinkDao().findForEvent(pending.eventId).isEmpty(),
            )
            val stale = commands.selectDraftCategory(
                WorkflowCallerScope.AgentSession(
                    "s9.7-$runId-hosted",
                    setOf(pending.captureId),
                    setOf(pending.draftId),
                ),
                SelectDraftCategoryRequest(
                    "s9.7-$runId-hosted-stale",
                    pending.draftId,
                    pendingDraft.reviewRevision,
                    categoryId,
                    hostedContext,
                    hostedCatalog,
                ),
            )
            assertTrue("old-context write was accepted: $stale", stale is WorkflowCommandOutcome.Conflict)
            assertNull(database.pendingBankDraftDao().findById(pending.draftId)!!.reviewCategoryId)
            assertTrue(database.transactionDao().findAll().isEmpty())

            // --- A confirmed record linked to a note that is then deleted ------------------
            val confirmedFixture = storeFixture(
                commands = commands,
                database = database,
                runId = runId,
                suffix = "confirmed",
                amount = 20_000L,
                time = "15:15",
                attach = true,
            )
            val transactionId = confirmDraft(commands, confirmedFixture.draftId)
            confirmedDraftId = confirmedFixture.draftId
            val ledgerBefore = database.transactionDao().findAll().single()
            assertEquals(transactionId, ledgerBefore.id)
            val linkBefore = database.contextLinkDao()
                .findForEvent(confirmedFixture.eventId).single()
            val confirmedCapture = database.captureMessageDao().findById(confirmedFixture.captureId)!!
            val deleted = commands.deleteCapture(
                WorkflowCallerScope.User,
                DeleteCaptureRequest(
                    "s9.7-$runId-delete",
                    confirmedFixture.captureId,
                    confirmedCapture.revision,
                ),
            )
            assertTrue("note delete was refused: $deleted", deleted is WorkflowCommandOutcome.Applied)

            assertEquals(
                "the finalized ledger row changed with the note",
                ledgerBefore,
                database.transactionDao().findAll().single(),
            )
            val confirmedAfter = database.pendingBankDraftDao()
                .findById(confirmedFixture.draftId)!!
            assertEquals(IntakeReviewStatus.CONFIRMED, confirmedAfter.reviewStatus)
            assertEquals(transactionId, confirmedAfter.confirmedTransactionId)
            val linkAfter = database.contextLinkDao()
                .findForEvent(confirmedFixture.eventId).single()
            assertEquals(ContextLinkState.ACCEPTED, linkAfter.state)
            assertEquals(linkBefore.revision, linkAfter.revision)
            assertEquals(linkBefore.evidence, linkAfter.evidence)
            val confirmedEvent = database.noteEventDao().findById(confirmedFixture.eventId)!!
            assertNull(confirmedEvent.normalizedText)
            assertEquals("SOURCE_DELETED", confirmedEvent.unresolvedReason)
            val retained = queries.draftContextLinks(
                confirmedFixture.draftId,
                QueryPageRequest(limit = 10, offset = 0),
            ).items.single()
            assertEquals(ContextLinkState.ACCEPTED, retained.state)
            assertFalse(retained.sourceCurrent)
            assertNull(retained.excerpt)
            val tombstone = database.captureMessageDao().findById(confirmedFixture.captureId)!!
            assertTrue(tombstone.tombstone)
            assertNull(tombstone.bodyText)
            assertEquals(confirmedCapture.sourceIdentity, tombstone.sourceIdentity)

            val undo = commands.unlinkLink(
                WorkflowCallerScope.User,
                UnlinkLinkRequest(
                    "s9.7-$runId-confirmed-unlink",
                    confirmedFixture.eventId,
                    confirmedFixture.draftId,
                    linkAfter.revision,
                ),
            )
            assertEquals(
                WorkflowCommandOutcome.Denied(WorkflowDenialReason.CONFIRMED_RECORD),
                undo,
            )
            assertEquals(
                ContextLinkState.ACCEPTED,
                database.contextLinkDao().findForEvent(confirmedFixture.eventId).single().state,
            )
        } finally {
            database.close()
        }

        // The correction and its retained provenance survive a process-local reopen.
        database = openEvidenceDatabase(context, databaseName)
        try {
            val queries = WorkflowQueryService(database)
            val tombstone = database.captureMessageDao()
                .findAllIncludingTombstones()
                .single { it.tombstone }
            assertNull(tombstone.bodyText)
            assertEquals(1, database.transactionDao().findAll().size)
            val retained = queries.draftContextLinks(
                confirmedDraftId,
                QueryPageRequest(limit = 10, offset = 0),
            ).items.single()
            assertEquals(ContextLinkState.ACCEPTED, retained.state)
            assertFalse(retained.sourceCurrent)
            val pendingDraft = database.pendingBankDraftDao().findById(pendingDraftId)!!
            assertNull(pendingDraft.reviewCategoryId)
            assertFalse(pendingDraft.reviewCategoryResolved)
            assertEquals(ReviewQueueGroup.NEEDS_CATEGORY, queries.reviewQueueEntry(pendingDraftId)!!.group)
        } finally {
            database.close()
        }
    }

    @Test
    fun userCategorySurvivesNoteDeleteAndStaleUndoNeverOverwritesNewerWork() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("io.github.triet4p.aegisf.s812evidence", context.packageName)
        val runId = UUID.randomUUID().toString().replace("-", "")
        val database = openEvidenceDatabase(context, "s9_7_user_keep_$runId.db")
        try {
            val commands = commands(database)
            val queries = WorkflowQueryService(database)
            val fuel = insertCategory(database, "Fuel")

            // A user's own category is not withdrawn by deleting the note it was read with.
            val userFixture = storeFixture(
                commands = commands,
                database = database,
                runId = runId,
                suffix = "userkeep",
                amount = 30_000L,
                time = "16:00",
                attach = true,
            )
            val userDraft = database.pendingBankDraftDao().findById(userFixture.draftId)!!
            val saved = commands.saveDraftReview(
                WorkflowCallerScope.User,
                SaveDraftReviewRequest(
                    "s9.7-$runId-user-review",
                    userFixture.draftId,
                    userDraft.reviewRevision,
                    reviewEdits(fuel),
                ),
            )
            assertTrue("user review was refused: $saved", saved is WorkflowCommandOutcome.Applied)
            val userCapture = database.captureMessageDao().findById(userFixture.captureId)!!
            val deleted = commands.deleteCapture(
                WorkflowCallerScope.User,
                DeleteCaptureRequest(
                    "s9.7-$runId-user-delete",
                    userFixture.captureId,
                    userCapture.revision,
                ),
            )
            assertTrue("note delete was refused: $deleted", deleted is WorkflowCommandOutcome.Applied)
            val kept = database.pendingBankDraftDao().findById(userFixture.draftId)!!
            assertEquals(fuel, kept.reviewCategoryId)
            assertTrue(kept.reviewCategoryResolved)
            assertEquals(ReviewCategoryOrigin.USER, kept.reviewCategoryOrigin)
            assertEquals(ReviewQueueGroup.READY, queries.reviewQueueEntry(userFixture.draftId)!!.group)
            assertTrue(database.transactionDao().findAll().isEmpty())

            // A stale undo conflicts; the current undo removes only the link and keeps the
            // newer user category; a pre-undo hosted write cannot overwrite it.
            val undoFixture = storeFixture(
                commands = commands,
                database = database,
                runId = runId,
                suffix = "undo",
                amount = 40_000L,
                time = "17:00",
                attach = true,
            )
            val link = database.contextLinkDao().findForEvent(undoFixture.eventId).single()
            val undoDraft = database.pendingBankDraftDao().findById(undoFixture.draftId)!!
            val afterUser = commands.saveDraftReview(
                WorkflowCallerScope.User,
                SaveDraftReviewRequest(
                    "s9.7-$runId-undo-review",
                    undoFixture.draftId,
                    undoDraft.reviewRevision,
                    reviewEdits(fuel),
                ),
            )
            assertTrue("user review was refused: $afterUser", afterUser is WorkflowCommandOutcome.Applied)
            val contextBeforeUndo = queries.draftContextSnapshot(undoFixture.draftId).second
            val catalog = categoryCatalogRevision(queries.categoryCatalog())
            val revisionBeforeUndo = database.pendingBankDraftDao()
                .findById(undoFixture.draftId)!!.reviewRevision

            val staleUndo = commands.unlinkLink(
                WorkflowCallerScope.User,
                UnlinkLinkRequest(
                    "s9.7-$runId-undo-stale",
                    undoFixture.eventId,
                    undoFixture.draftId,
                    link.revision - 1,
                ),
            )
            assertTrue("stale undo was accepted: $staleUndo", staleUndo is WorkflowCommandOutcome.Conflict)
            assertEquals(
                ContextLinkState.ACCEPTED,
                database.contextLinkDao().findForEvent(undoFixture.eventId).single().state,
            )
            val undo = commands.unlinkLink(
                WorkflowCallerScope.User,
                UnlinkLinkRequest(
                    "s9.7-$runId-undo-current",
                    undoFixture.eventId,
                    undoFixture.draftId,
                    link.revision,
                ),
            )
            assertTrue("current undo was refused: $undo", undo is WorkflowCommandOutcome.Applied)
            val afterUndo = database.pendingBankDraftDao().findById(undoFixture.draftId)!!
            assertEquals(fuel, afterUndo.reviewCategoryId)
            assertTrue(afterUndo.reviewCategoryResolved)
            assertEquals(ReviewCategoryOrigin.USER, afterUndo.reviewCategoryOrigin)
            assertEquals(ReviewQueueGroup.READY, queries.reviewQueueEntry(undoFixture.draftId)!!.group)

            val staleHosted = commands.selectDraftCategory(
                WorkflowCallerScope.AgentSession(
                    "s9.7-$runId-undo-hosted",
                    setOf(undoFixture.captureId),
                    setOf(undoFixture.draftId),
                ),
                SelectDraftCategoryRequest(
                    "s9.7-$runId-undo-hosted-write",
                    undoFixture.draftId,
                    revisionBeforeUndo,
                    fuel,
                    contextBeforeUndo,
                    catalog,
                ),
            )
            assertTrue(
                "pre-undo hosted write was accepted: $staleHosted",
                staleHosted is WorkflowCommandOutcome.Conflict,
            )
            assertEquals(
                fuel,
                database.pendingBankDraftDao().findById(undoFixture.draftId)!!.reviewCategoryId,
            )
            assertTrue(database.transactionDao().findAll().isEmpty())
        } finally {
            database.close()
        }
    }

    // --- Fixtures ------------------------------------------------------------

    private fun openEvidenceDatabase(context: Context, name: String): LedgerDatabase =
        Room.databaseBuilder(context.applicationContext, LedgerDatabase::class.java, name).build()

    private fun commands(database: LedgerDatabase) = WorkflowCommandService(
        database = database,
        timezoneId = { "Asia/Ho_Chi_Minh" },
    )

    private suspend fun insertCategory(database: LedgerDatabase, name: String): Long =
        database.categoryDao().insert(
            CategoryEntity(
                name = name,
                createdAtEpochMillis = System.currentTimeMillis(),
                updatedAtEpochMillis = System.currentTimeMillis(),
            ),
        )

    private data class StoredFixture(
        val captureId: Long,
        val eventId: Long,
        val draftId: Long,
    )

    private suspend fun storeFixture(
        commands: WorkflowCommandService,
        database: LedgerDatabase,
        runId: String,
        suffix: String,
        amount: Long,
        time: String,
        attach: Boolean,
    ): StoredFixture {
        val body = "S97 $suffix $amount VND on 2026-11-05 at $time"
        val saved = commands.saveCapture(
            WorkflowCallerScope.User,
            SaveCaptureRequest("s9.7-$runId-$suffix-capture", body),
        ) as WorkflowCommandOutcome.Applied
        val captureId = (saved.change as WorkflowChange.CaptureSaved).captureId
        val stored = commands.updateEventInterpretation(
            WorkflowCallerScope.User,
            UpdateEventRequest(
                idempotencyKey = "s9.7-$runId-$suffix-event",
                captureId = captureId,
                expectedCaptureRevision = 0,
                interpretation = NoteEventInterpretation(
                    spanStart = 0,
                    spanEnd = body.length,
                    normalizedText = "S97 $suffix purchase",
                    amountVnd = amount,
                    merchant = "Synthetic merchant",
                    timeAnchor = NoteEventTimeAnchor(
                        date = "2026-11-05",
                        time = time,
                        timezoneId = "Asia/Ho_Chi_Minh",
                    ),
                ),
                extractionVersion = "s9.7-phone-fixture",
            ),
        ) as WorkflowCommandOutcome.Applied
        val eventId = (stored.change as WorkflowChange.EventStored).eventId
        val draftId = database.pendingBankDraftDao().store(
            PendingBankDraftEntity(
                deliveryId = "s9.7-$runId-$suffix",
                sourcePackage = "synthetic.fixture",
                notificationKey = null,
                postedAtEpochMillis = System.currentTimeMillis(),
                bank = "Synthetic bank",
                parseStatus = IntakeParseStatus.PARSED,
                amountState = IntakeFieldState.PRESENT,
                amountVnd = amount + 1_000L,
                amountUnresolvedReason = null,
                direction = IntakeDirection.DEBIT,
                momentState = IntakeFieldState.PRESENT,
                statedDate = "2026-11-05",
                statedTime = time,
                momentUnresolvedReason = null,
                memoState = IntakeFieldState.PRESENT,
                memo = "S97 synthetic purchase",
                memoUnresolvedReason = null,
                createdAtEpochMillis = System.currentTimeMillis(),
            ),
        )
        val proposed = commands.proposeLink(
            WorkflowCallerScope.User,
            ProposeLinkRequest("s9.7-$runId-$suffix-proposal", eventId, draftId),
        )
        assertTrue("proposal was refused: $proposed", proposed is WorkflowCommandOutcome.Applied)
        if (attach) {
            val proposalRevision = (proposed as WorkflowCommandOutcome.Applied).change
                .let { (it as WorkflowChange.LinkDecided).revision }
            val attached = commands.attachLink(
                WorkflowCallerScope.User,
                AttachLinkRequest(
                    "s9.7-$runId-$suffix-attach",
                    eventId,
                    draftId,
                    proposalRevision,
                ),
            )
            assertTrue("attachment was refused: $attached", attached is WorkflowCommandOutcome.Applied)
        }
        return StoredFixture(captureId, eventId, draftId)
    }

    private suspend fun confirmDraft(
        commands: WorkflowCommandService,
        draftId: Long,
    ): Long {
        val edits = BankDraftReviewEdits(
            amountText = "20000",
            dateText = "2026-11-05",
            timeText = "15:15",
            memoText = "S97 confirmed purchase",
            type = TransactionType.EXPENSE,
            categoryId = null,
            categoryResolved = true,
        )
        val preview = commands.previewDraftConfirmation(
            WorkflowCallerScope.User,
            PreviewDraftConfirmationRequest(draftId = draftId, edits = edits),
        )
        val ready = preview as? DraftConfirmationPreview.Ready
            ?: error("preview was refused: $preview")
        val confirmed = commands.confirmDraft(
            WorkflowCallerScope.User,
            ConfirmDraftRequest(
                idempotencyKey = "s9.7-confirm-$draftId",
                draftId = draftId,
                expectedRevision = ready.expectedRevision,
                expectedBinding = ready.binding,
                edits = edits,
            ),
        )
        val applied = confirmed as? WorkflowCommandOutcome.Applied
            ?: error("confirmation was refused: $confirmed")
        return (applied.change as WorkflowChange.DraftConfirmed).transactionId
    }

    private fun reviewEdits(categoryId: Long) = BankDraftReviewEdits(
        amountText = "30000",
        dateText = "2026-11-05",
        timeText = "16:00",
        memoText = "S97 user review",
        type = TransactionType.EXPENSE,
        categoryId = categoryId,
        categoryResolved = true,
    )
}
