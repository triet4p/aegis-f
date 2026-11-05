package io.github.triet4p.aegisf.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage-driven device fixture for the S9.7 changed-surface proof.
 *
 * The `seed` stage writes two synthetic fixtures into the isolated app's own database: a
 * pending draft whose category was chosen by the hosted workflow from its note, and a
 * confirmed record whose note is its provenance. After the host edits both notes through the
 * real Quick Capture surface, the `verify` stage reads the same database and asserts the stored
 * correction: the hosted category was reopened, the user-visible ledger row and its note
 * reference were kept, and no draft was confirmed by the edit.
 *
 * The isolated package's database is cleared in the seed stage only; the owner's app is never
 * touched, and this test never runs as the default `verify` stage itself.
 */
@RunWith(AndroidJUnit4::class)
class S97DeviceSurfaceInstrumentedTest {

    @Test
    fun correctionSurfaceFixture() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("io.github.triet4p.aegisf.s812evidence", context.packageName)
        val stage = InstrumentationRegistry.getArguments().getString(STAGE_ARGUMENT)
        if (stage == SEED_STAGE) {
            seed(context)
            return@runBlocking
        }
        verify(context)
    }

    private suspend fun seed(context: Context) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val runId = UUID.randomUUID().toString().replace("-", "")
        preferences.edit().clear().putString(RUN_KEY, runId).commit()
        val database = openProductionDatabase(context)
        try {
            val commands = WorkflowCommandService(database, timezoneId = { "Asia/Ho_Chi_Minh" })
            val queries = WorkflowQueryService(database)
            val fuel = insertCategory(database, "Fuel")

            val pending = storeFixture(
                commands = commands,
                database = database,
                runId = runId,
                suffix = "pending",
                body = "$SEED_PENDING_BODY $runId",
                amount = 50_000L,
                time = "15:00",
            )
            val pendingDraft = database.pendingBankDraftDao().findById(pending.draftId)!!
            val hosted = commands.selectDraftCategory(
                WorkflowCallerScope.AgentSession(
                    "s97-surface-$runId-hosted",
                    setOf(pending.captureId),
                    setOf(pending.draftId),
                ),
                SelectDraftCategoryRequest(
                    "s97-surface-$runId-hosted-pick",
                    pending.draftId,
                    pendingDraft.reviewRevision,
                    fuel,
                    queries.draftContextSnapshot(pending.draftId).second,
                    categoryCatalogRevision(queries.categoryCatalog()),
                ),
            )
            assertTrue("hosted category was refused: $hosted", hosted is WorkflowCommandOutcome.Applied)

            val confirmed = storeFixture(
                commands = commands,
                database = database,
                runId = runId,
                suffix = "confirmed",
                body = "$SEED_CONFIRMED_BODY $runId",
                amount = 20_000L,
                time = "15:15",
            )
            val transactionId = confirmDraft(commands, confirmed.draftId)
            assertTrue(
                "the seeded confirmation is not stored",
                database.transactionDao().findAll().any { it.id == transactionId },
            )

            assertEquals(
                ReviewQueueGroup.READY,
                queries.reviewQueueEntry(pending.draftId)!!.group,
            )
            assertEquals(
                IntakeReviewStatus.CONFIRMED,
                database.pendingBankDraftDao().findById(confirmed.draftId)!!.reviewStatus,
            )
            preferences.edit()
                .putLong(PENDING_CAPTURE_KEY, pending.captureId)
                .putLong(PENDING_DRAFT_KEY, pending.draftId)
                .putLong(CONFIRMED_CAPTURE_KEY, confirmed.captureId)
                .putLong(CONFIRMED_DRAFT_KEY, confirmed.draftId)
                .putLong(TRANSACTION_KEY, transactionId)
                .commit()
        } finally {
            database.close()
        }
    }

    private suspend fun verify(context: Context) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val pendingCaptureId = preferences.getLong(PENDING_CAPTURE_KEY, -1L)
        val pendingDraftId = preferences.getLong(PENDING_DRAFT_KEY, -1L)
        val confirmedCaptureId = preferences.getLong(CONFIRMED_CAPTURE_KEY, -1L)
        val confirmedDraftId = preferences.getLong(CONFIRMED_DRAFT_KEY, -1L)
        val transactionId = preferences.getLong(TRANSACTION_KEY, -1L)
        val runId = preferences.getString(RUN_KEY, null)
        assertTrue("the seed stage was not run", pendingCaptureId > 0L)
        assertTrue(confirmedCaptureId > 0L)
        assertNotNull("the seed stage did not record its run", runId)
        val database = openProductionDatabase(context)
        try {
            val queries = WorkflowQueryService(database)
            val editedPending = database.captureMessageDao().findById(pendingCaptureId)!!
            assertEquals("the pending note was not edited on the device", 1, editedPending.revision)
            assertTrue(editedPending.bodyText != "$SEED_PENDING_BODY $runId")
            val pendingDraft = database.pendingBankDraftDao().findById(pendingDraftId)!!
            assertEquals(IntakeReviewStatus.PENDING, pendingDraft.reviewStatus)
            assertNull("the hosted category survived the UI note edit", pendingDraft.reviewCategoryId)
            assertFalse(pendingDraft.reviewCategoryResolved)
            assertEquals(ReviewCategoryOrigin.NONE, pendingDraft.reviewCategoryOrigin)
            assertEquals(
                ReviewQueueGroup.NEEDS_CATEGORY,
                queries.reviewQueueEntry(pendingDraftId)!!.group,
            )
            assertTrue(database.contextLinkDao().findForDraft(pendingDraftId).isEmpty())

            val editedConfirmed = database.captureMessageDao().findById(confirmedCaptureId)!!
            assertEquals("the confirmed note was not edited on the device", 1, editedConfirmed.revision)
            assertTrue(editedConfirmed.bodyText != "$SEED_CONFIRMED_BODY $runId")
            val transaction = database.transactionDao().findAll().single { it.id == transactionId }
            assertEquals("S97B confirmed coffee", transaction.description)
            assertEquals(20_000L, transaction.amountVnd ?: -1L)
            assertEquals(ConfirmationStatus.CONFIRMED, transaction.confirmation)
            val confirmedDraft = database.pendingBankDraftDao().findById(confirmedDraftId)!!
            assertEquals(IntakeReviewStatus.CONFIRMED, confirmedDraft.reviewStatus)
            assertEquals(transactionId, confirmedDraft.confirmedTransactionId)
            val retained = queries.draftContextLinks(
                confirmedDraftId,
                QueryPageRequest(limit = 10, offset = 0),
            ).items.single()
            assertEquals(ContextLinkState.ACCEPTED, retained.state)
            assertFalse(retained.sourceCurrent)
            assertNull(retained.excerpt)
            val events = database.noteEventDao().findForCapture(confirmedCaptureId)
            assertEquals(1, events.size)
            assertNull(events.single().normalizedText)
            assertEquals("SOURCE_EDITED", events.single().unresolvedReason)
            val retainedLink = database.contextLinkDao().findForDraft(confirmedDraftId).single()
            assertNotNull(retainedLink.evidence)
        } finally {
            database.close()
            preferences.edit().clear().commit()
        }
    }

    // --- Fixtures ------------------------------------------------------------

    private fun openProductionDatabase(context: Context): LedgerDatabase =
        Room.databaseBuilder(context.applicationContext, LedgerDatabase::class.java, LedgerDatabase.NAME)
            .build()

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
        val draftId: Long,
    )

    private suspend fun storeFixture(
        commands: WorkflowCommandService,
        database: LedgerDatabase,
        runId: String,
        suffix: String,
        body: String,
        amount: Long,
        time: String,
    ): StoredFixture {
        val saved = commands.saveCapture(
            WorkflowCallerScope.User,
            SaveCaptureRequest("s97-surface-$runId-$suffix-capture", body),
        ) as WorkflowCommandOutcome.Applied
        val captureId = (saved.change as WorkflowChange.CaptureSaved).captureId
        val stored = commands.updateEventInterpretation(
            WorkflowCallerScope.User,
            UpdateEventRequest(
                idempotencyKey = "s97-surface-$runId-$suffix-event",
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
                extractionVersion = "s97-surface-fixture",
            ),
        ) as WorkflowCommandOutcome.Applied
        val eventId = (stored.change as WorkflowChange.EventStored).eventId
        val draftId = database.pendingBankDraftDao().store(
            PendingBankDraftEntity(
                deliveryId = "s97-surface-$runId-$suffix",
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
                memo = "S97 synthetic purchase $runId",
                memoUnresolvedReason = null,
                createdAtEpochMillis = System.currentTimeMillis(),
            ),
        )
        val proposed = commands.proposeLink(
            WorkflowCallerScope.User,
            ProposeLinkRequest("s97-surface-$runId-$suffix-proposal", eventId, draftId),
        ) as WorkflowCommandOutcome.Applied
        val revision = (proposed.change as WorkflowChange.LinkDecided).revision
        val attached = commands.attachLink(
            WorkflowCallerScope.User,
            AttachLinkRequest("s97-surface-$runId-$suffix-attach", eventId, draftId, revision),
        )
        assertTrue("attachment was refused: $attached", attached is WorkflowCommandOutcome.Applied)
        return StoredFixture(captureId, draftId)
    }

    private suspend fun confirmDraft(commands: WorkflowCommandService, draftId: Long): Long {
        val edits = BankDraftReviewEdits(
            amountText = "20000",
            dateText = "2026-11-05",
            timeText = "15:15",
            memoText = "S97B confirmed coffee",
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
                idempotencyKey = "s97-surface-confirm-$draftId",
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

    private companion object {
        const val PREFERENCES = "s97_device_surface_evidence"
        const val STAGE_ARGUMENT = "s97_stage"
        const val SEED_STAGE = "seed"
        const val PENDING_CAPTURE_KEY = "pending_capture_id"
        const val PENDING_DRAFT_KEY = "pending_draft_id"
        const val CONFIRMED_CAPTURE_KEY = "confirmed_capture_id"
        const val CONFIRMED_DRAFT_KEY = "confirmed_draft_id"
        const val TRANSACTION_KEY = "transaction_id"
        const val RUN_KEY = "seed_run_id"
        const val SEED_PENDING_BODY = "S97A pending note fuel 50000"
        const val SEED_CONFIRMED_BODY = "S97B confirmed note coffee 20000"
    }
}
