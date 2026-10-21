package io.github.triet4p.aegisf.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.triet4p.aegisf.notification.PermittedNotificationContent
import io.github.triet4p.aegisf.ui.ManualDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

/**
 * The behavior of the application's user-only financial commands.
 *
 * These run against the real Room file, so an applied change, a refused request, a
 * conflict, and a recorded operation are observed the way the app observes them after a
 * restart — not as a mocked return value.
 *
 * The classifier is the one collaborator that is stood in for: the local model is not
 * installed on a host test machine, and a financial command never asks it anything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FinancialCommandServiceTest {
    private class FixedClock(@Volatile var now: Long = CAPTURE_TIME) : LedgerClock {
        override fun nowEpochMillis(): Long = now
    }

    private lateinit var context: Context
    private lateinit var clock: FixedClock

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(LedgerDatabase.NAME)
        clock = FixedClock()
        WorkflowClaimGuard.reset()
    }

    private fun open(): LedgerDatabase = LedgerDatabase.build(context)

    private fun commands(database: LedgerDatabase): WorkflowCommandService = WorkflowCommandService(
        database = database,
        clock = clock,
        timezoneId = { CAPTURE_ZONE },
        engine = object : ClassificationEngine {
            override suspend fun classify(
                context: io.github.triet4p.aegisf.data.laya.LayaEffectiveContext,
                categories: List<CategoryEntity>,
            ): ClassificationEngineResult = ClassificationEngineResult.Failed("not used here")
        },
    )

    private fun ledger(database: LedgerDatabase) = LedgerRepository(
        categoryDao = database.categoryDao(),
        transactionDao = database.transactionDao(),
        rememberedCategoryRuleDao = database.rememberedCategoryRuleDao(),
        captureMessageDao = database.captureMessageDao(),
        clock = clock,
    )

    private fun intake(database: LedgerDatabase) = IntakeRepository(
        database = database,
        clock = clock,
    )

    /** Stores one permitted bank notification and returns the draft it became. */
    private suspend fun storeDraft(
        database: LedgerDatabase,
        amount: String = "-50,000 VND",
        postedAt: Long = POSTED_AT,
        key: String = "key-1",
    ): Long = intake(database).storePermitted(
        sourcePackage = BANK_PACKAGE,
        notificationKey = key,
        postedAtEpochMillis = postedAt,
        content = PermittedNotificationContent(
            title = "Tin biến động số dư",
            text = listOf(
                "VietinBank: 16/10/2026 07:38",
                "TK: Redacted",
                "GD: $amount",
                "SDC: xxx,000 VND",
                "ND: ........",
            ).joinToString("\n"),
            bigText = null,
        ),
    )

    private fun edits(
        type: TransactionType? = TransactionType.EXPENSE,
        amountText: String = "50000",
        dateText: String = "2026-10-16",
        timeText: String = "07:38",
        memoText: String = "",
        categoryId: Long? = null,
        categoryResolved: Boolean = true,
    ) = BankDraftReviewEdits(
        amountText = amountText,
        dateText = dateText,
        timeText = timeText,
        memoText = memoText,
        type = type,
        categoryId = categoryId,
        categoryResolved = categoryResolved,
    )

    /** Stores one note and reads one event out of it, the way the user records by hand. */
    private suspend fun storeNoteEvent(
        database: LedgerDatabase,
        body: String = "Fuel 50,000",
        amountVnd: Long? = 50_000L,
        anchorDate: String? = "2026-10-16",
    ): Long {
        val commands = commands(database)
        val captureId = (
            commands.saveCapture(
                WorkflowCallerScope.User,
                SaveCaptureRequest(idempotencyKey = "note-$body", bodyText = body),
            ) as WorkflowCommandOutcome.Applied
            ).change.let { it as WorkflowChange.CaptureSaved }.captureId
        val stored = (
            commands.updateEventInterpretation(
                WorkflowCallerScope.User,
                UpdateEventRequest(
                    idempotencyKey = "event-$body",
                    captureId = captureId,
                    expectedCaptureRevision = 0,
                    interpretation = NoteEventInterpretation(
                        spanStart = 0,
                        spanEnd = body.length,
                        normalizedText = body,
                        amountVnd = amountVnd,
                        merchant = "Petrol",
                        timeAnchor = NoteEventTimeAnchor(date = anchorDate),
                    ),
                    extractionVersion = "test-v1",
                ),
            ) as WorkflowCommandOutcome.Applied
            ).change.let { it as WorkflowChange.EventStored }
        return stored.eventId
    }

    // --- Confirmation ---------------------------------------------------------

    @Test
    fun `two confirmations of one draft under different keys write one ledger record`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val draftId = storeDraft(database)
            val preview = commands.previewDraftConfirmation(
                WorkflowCallerScope.User,
                PreviewDraftConfirmationRequest(draftId = draftId, edits = edits()),
            )
            val ready = preview as DraftConfirmationPreview.Ready

            val first = commands.confirmDraft(
                WorkflowCallerScope.User,
                ConfirmDraftRequest(
                    idempotencyKey = "confirm-1",
                    draftId = draftId,
                    expectedRevision = ready.expectedRevision,
                    expectedBinding = ready.binding,
                    edits = edits(),
                ),
            )
            assertTrue("the first confirmation was refused: $first", first is WorkflowCommandOutcome.Applied)

            // A second confirmation under another key finds the draft terminal: the money
            // is written once, whatever the key is.
            val second = commands.confirmDraft(
                WorkflowCallerScope.User,
                ConfirmDraftRequest(
                    idempotencyKey = "confirm-2",
                    draftId = draftId,
                    expectedRevision = ready.expectedRevision,
                    expectedBinding = ready.binding,
                    edits = edits(),
                ),
            )
            assertTrue(
                "the second confirmation was not a no-change: $second",
                second is WorkflowCommandOutcome.NoChange,
            )
            assertEquals(1, WorkflowQueryService(database).ledgerRecords(ALL_LEDGER_RECORDS).items.size)
            assertEquals(
                50_000L,
                WorkflowQueryService(database).ledgerRecords(ALL_LEDGER_RECORDS).items.single().amountVnd,
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun `a repeated confirmation under the same key changes nothing after a restart`() = runTest {
        val database = open()
        val transactionId = when (val preview = commands(database).previewDraftConfirmation(
            WorkflowCallerScope.User,
            PreviewDraftConfirmationRequest(draftId = storeDraft(database), edits = edits()),
        )) {
            is DraftConfirmationPreview.Ready -> preview
            else -> error("the preview was refused: $preview")
        }
        val request = ConfirmDraftRequest(
            idempotencyKey = "confirm-1",
            draftId = transactionId.draftId,
            expectedRevision = transactionId.expectedRevision,
            expectedBinding = transactionId.binding,
            edits = edits(),
        )
        val first = commands(database).confirmDraft(WorkflowCallerScope.User, request)
        assertTrue(first is WorkflowCommandOutcome.Applied)
        database.close()

        val reopened = open()
        try {
            val again = commands(reopened).confirmDraft(WorkflowCallerScope.User, request)
            assertTrue(
                "the repeat was not a no-change: $again",
                again is WorkflowCommandOutcome.NoChange,
            )
            val change = (again as WorkflowCommandOutcome.NoChange).change
            assertTrue(change is WorkflowChange.DraftConfirmed)
            assertEquals(
                (first as WorkflowCommandOutcome.Applied).change.let {
                    (it as WorkflowChange.DraftConfirmed).transactionId
                },
                (change as WorkflowChange.DraftConfirmed).transactionId,
            )
            assertEquals(1, WorkflowQueryService(reopened).ledgerRecords(ALL_LEDGER_RECORDS).items.size)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `concurrent confirmations of one draft write one ledger record`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val draftId = storeDraft(database)
            val preview = commands.previewDraftConfirmation(
                WorkflowCallerScope.User,
                PreviewDraftConfirmationRequest(draftId = draftId, edits = edits()),
            ) as DraftConfirmationPreview.Ready
            val outcomes = coroutineScope {
                (1..4).map { index ->
                    async(Dispatchers.IO) {
                        commands.confirmDraft(
                            WorkflowCallerScope.User,
                            ConfirmDraftRequest(
                                idempotencyKey = "confirm-$index",
                                draftId = draftId,
                                expectedRevision = preview.expectedRevision,
                                expectedBinding = preview.binding,
                                edits = edits(),
                            ),
                        )
                    }
                }.map { it.await() }
            }
            assertEquals(1, WorkflowQueryService(database).ledgerRecords(ALL_LEDGER_RECORDS).items.size)
            assertEquals(1, outcomes.count { it is WorkflowCommandOutcome.Applied })
        } finally {
            database.close()
        }
    }

    // --- Stale previews -------------------------------------------------------

    @Test
    fun `a preview that went stale cannot overwrite newer data`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val categoryId = ledger(database).addCategory("Transport")
            val draftId = storeDraft(database)
            val preview = commands.previewDraftConfirmation(
                WorkflowCallerScope.User,
                PreviewDraftConfirmationRequest(
                    draftId = draftId,
                    edits = edits(categoryId = categoryId),
                ),
            ) as DraftConfirmationPreview.Ready

            // The review moves on after the preview was made.
            val saved = commands.saveDraftReview(
                WorkflowCallerScope.User,
                SaveDraftReviewRequest(
                    idempotencyKey = "draft-review:$draftId:0",
                    draftId = draftId,
                    expectedRevision = preview.expectedRevision,
                    edits = edits(amountText = "60000", categoryId = categoryId),
                ),
            )
            assertTrue("the review was not stored: $saved", saved is WorkflowCommandOutcome.Applied)

            val stale = commands.confirmDraft(
                WorkflowCallerScope.User,
                ConfirmDraftRequest(
                    idempotencyKey = "confirm-stale",
                    draftId = draftId,
                    expectedRevision = preview.expectedRevision,
                    expectedBinding = preview.binding,
                    edits = edits(categoryId = categoryId),
                ),
            )
            assertTrue("the stale confirmation was not a conflict: $stale", stale is WorkflowCommandOutcome.Conflict)
            assertTrue(WorkflowQueryService(database).ledgerRecords(ALL_LEDGER_RECORDS).items.isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun `a category archived after the preview refuses the confirmation`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val categoryId = ledger(database).addCategory("Transport")
            val draftId = storeDraft(database)
            val preview = commands.previewDraftConfirmation(
                WorkflowCallerScope.User,
                PreviewDraftConfirmationRequest(
                    draftId = draftId,
                    edits = edits(categoryId = categoryId),
                ),
            ) as DraftConfirmationPreview.Ready

            ledger(database).archiveCategory(categoryId)

            val stale = commands.confirmDraft(
                WorkflowCallerScope.User,
                ConfirmDraftRequest(
                    idempotencyKey = "confirm-archived",
                    draftId = draftId,
                    expectedRevision = preview.expectedRevision,
                    expectedBinding = preview.binding,
                    edits = edits(categoryId = categoryId),
                ),
            )
            assertTrue("the stale confirmation was not a conflict: $stale", stale is WorkflowCommandOutcome.Conflict)
            assertTrue(WorkflowQueryService(database).ledgerRecords(ALL_LEDGER_RECORDS).items.isEmpty())
        } finally {
            database.close()
        }
    }

    // --- Authority ------------------------------------------------------------

    @Test
    fun `an agent may not confirm, dismiss, correct, remove, or write a record`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val agent = WorkflowCallerScope.AgentSession(
                scopeId = "agent-1",
                captureIds = emptySet(),
                draftIds = emptySet(),
            )
            val draftId = storeDraft(database)
            val preview = commands.previewDraftConfirmation(
                WorkflowCallerScope.User,
                PreviewDraftConfirmationRequest(draftId = draftId, edits = edits()),
            ) as DraftConfirmationPreview.Ready
            val recordId = ledger(database).addTransaction(
                TransactionInput(
                    type = TransactionType.EXPENSE,
                    amountVnd = 50_000L,
                    occurredAtEpochMillis = ManualDateTime.toEpochMillis("2026-10-16", "07:38"),
                    description = "Manual",
                ),
            )

            val refusals = listOf(
                commands.confirmDraft(
                    agent,
                    ConfirmDraftRequest(
                        idempotencyKey = "agent-confirm",
                        draftId = draftId,
                        expectedRevision = preview.expectedRevision,
                        expectedBinding = preview.binding,
                        edits = edits(),
                    ),
                ),
                commands.dismissDraft(
                    agent,
                    DismissDraftRequest(
                        idempotencyKey = "agent-dismiss",
                        draftId = draftId,
                        expectedRevision = preview.expectedRevision,
                    ),
                ),
                commands.saveDraftReview(
                    agent,
                    SaveDraftReviewRequest(
                        idempotencyKey = "agent-review",
                        draftId = draftId,
                        expectedRevision = preview.expectedRevision,
                        edits = edits(),
                    ),
                ),
                commands.correctTransaction(
                    agent,
                    CorrectTransactionRequest(
                        idempotencyKey = "agent-correct",
                        transactionId = recordId,
                        expectedRevision = 0,
                        type = TransactionType.EXPENSE,
                        amountVnd = 60_000L,
                        occurredAtEpochMillis = ManualDateTime.toEpochMillis("2026-10-16", "07:38"),
                        description = "Agent",
                    ),
                ),
                commands.deleteTransaction(
                    agent,
                    DeleteTransactionRequest(
                        idempotencyKey = "agent-delete",
                        transactionId = recordId,
                        expectedRevision = 0,
                    ),
                ),
                commands.saveManualTransaction(
                    agent,
                    SaveManualTransactionRequest(
                        idempotencyKey = "agent-save",
                        type = TransactionType.EXPENSE,
                        amountVnd = 50_000L,
                        occurredAtEpochMillis = ManualDateTime.toEpochMillis("2026-10-16", "07:38"),
                        description = "Agent",
                    ),
                ),
            )
            refusals.forEach { refusal ->
                assertTrue(
                    "an agent request was not refused: $refusal",
                    refusal is WorkflowCommandOutcome.Denied,
                )
                assertEquals(
                    WorkflowDenialReason.USER_DECISION_REQUIRED,
                    (refusal as WorkflowCommandOutcome.Denied).reason,
                )
            }
            val agentPreview = commands.previewDraftConfirmation(
                agent,
                PreviewDraftConfirmationRequest(draftId = draftId, edits = edits()),
            )
            assertTrue(agentPreview is DraftConfirmationPreview.Denied)

            // Nothing was written by any of them.
            assertEquals(1, WorkflowQueryService(database).ledgerRecords(ALL_LEDGER_RECORDS).items.size)
            assertEquals(
                IntakeReviewStatus.PENDING,
                WorkflowQueryService(database).draftCandidates(ALL_DRAFTS).items.single().reviewStatus,
            )
            val stored = WorkflowQueryService(database).ledgerRecords(ALL_LEDGER_RECORDS).items.single()
            assertEquals(50_000L, stored.amountVnd)
            assertEquals("Manual", stored.description)
        } finally {
            database.close()
        }
    }

    // --- Manual records and corrections ---------------------------------------

    @Test
    fun `two callers racing for one key write one manual record`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val request = SaveManualTransactionRequest(
                idempotencyKey = "manual-race",
                type = TransactionType.EXPENSE,
                amountVnd = 45_000L,
                occurredAtEpochMillis = ManualDateTime.toEpochMillis("2026-10-16", "07:38"),
                description = "Lunch",
            )
            val outcomes = coroutineScope {
                val first = async(Dispatchers.IO) {
                    commands.saveManualTransaction(WorkflowCallerScope.User, request)
                }
                val second = async(Dispatchers.IO) {
                    commands.saveManualTransaction(WorkflowCallerScope.User, request)
                }
                listOf(first.await(), second.await())
            }
            assertEquals(1, WorkflowQueryService(database).ledgerRecords(ALL_LEDGER_RECORDS).items.size)
            assertEquals(1, outcomes.count { it is WorkflowCommandOutcome.Applied })
            assertEquals(1, outcomes.count { it is WorkflowCommandOutcome.NoChange })
        } finally {
            database.close()
        }
    }

    @Test
    fun `a manual record is written once under one key and a repeat changes nothing`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val request = SaveManualTransactionRequest(
                idempotencyKey = "manual-1",
                type = TransactionType.EXPENSE,
                amountVnd = 45_000L,
                occurredAtEpochMillis = ManualDateTime.toEpochMillis("2026-10-16", "07:38"),
                description = "Lunch",
            )
            val first = commands.saveManualTransaction(WorkflowCallerScope.User, request)
            assertTrue("the record was not stored: $first", first is WorkflowCommandOutcome.Applied)
            val second = commands.saveManualTransaction(WorkflowCallerScope.User, request)
            assertTrue("the repeat was not a no-change: $second", second is WorkflowCommandOutcome.NoChange)
            assertEquals(1, WorkflowQueryService(database).ledgerRecords(ALL_LEDGER_RECORDS).items.size)
        } finally {
            database.close()
        }
    }

    @Test
    fun `the same key with another financial request is a conflict and writes nothing`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            commands.saveManualTransaction(
                WorkflowCallerScope.User,
                SaveManualTransactionRequest(
                    idempotencyKey = "manual-1",
                    type = TransactionType.EXPENSE,
                    amountVnd = 45_000L,
                    occurredAtEpochMillis = ManualDateTime.toEpochMillis("2026-10-16", "07:38"),
                    description = "Lunch",
                ),
            )
            val changed = commands.saveManualTransaction(
                WorkflowCallerScope.User,
                SaveManualTransactionRequest(
                    idempotencyKey = "manual-1",
                    type = TransactionType.EXPENSE,
                    amountVnd = 46_000L,
                    occurredAtEpochMillis = ManualDateTime.toEpochMillis("2026-10-16", "07:38"),
                    description = "Lunch",
                ),
            )
            assertTrue("the changed request was not a conflict: $changed", changed is WorkflowCommandOutcome.Conflict)
            assertEquals(1, WorkflowQueryService(database).ledgerRecords(ALL_LEDGER_RECORDS).items.size)
            assertEquals(
                45_000L,
                WorkflowQueryService(database).ledgerRecords(ALL_LEDGER_RECORDS).items.single().amountVnd,
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun `a correction keeps the record's identity and refuses a stale revision`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val recordId = ledger(database).addTransaction(
                TransactionInput(
                    type = TransactionType.EXPENSE,
                    amountVnd = 45_000L,
                    occurredAtEpochMillis = ManualDateTime.toEpochMillis("2026-10-16", "07:38"),
                    description = "Lunch",
                ),
            )
            val corrected = commands.correctTransaction(
                WorkflowCallerScope.User,
                CorrectTransactionRequest(
                    idempotencyKey = "correct-1",
                    transactionId = recordId,
                    expectedRevision = 0,
                    type = TransactionType.EXPENSE,
                    amountVnd = 48_000L,
                    occurredAtEpochMillis = ManualDateTime.toEpochMillis("2026-10-16", "09:00"),
                    description = "Lunch with a friend",
                ),
            )
            assertTrue("the correction was refused: $corrected", corrected is WorkflowCommandOutcome.Applied)
            val stored = WorkflowQueryService(database).ledgerRecords(ALL_LEDGER_RECORDS).items.single()
            assertEquals(recordId, stored.id)
            assertEquals(48_000L, stored.amountVnd)
            assertEquals("Lunch with a friend", stored.description)
            assertEquals(ConfirmationStatus.CONFIRMED, stored.confirmation)
            assertEquals(RecordSource.MANUAL, stored.source)
            assertEquals(1, stored.revision)

            // The correction that was made against the row the caller read is now stale.
            val stale = commands.correctTransaction(
                WorkflowCallerScope.User,
                CorrectTransactionRequest(
                    idempotencyKey = "correct-2",
                    transactionId = recordId,
                    expectedRevision = 0,
                    type = TransactionType.EXPENSE,
                    amountVnd = 49_000L,
                    occurredAtEpochMillis = ManualDateTime.toEpochMillis("2026-10-16", "09:00"),
                    description = "Lunch with a friend",
                ),
            )
            assertTrue("the stale correction was not a conflict: $stale", stale is WorkflowCommandOutcome.Conflict)
            assertEquals(
                48_000L,
                WorkflowQueryService(database).ledgerRecords(ALL_LEDGER_RECORDS).items.single().amountVnd,
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun `an archived category may be kept by its own record and a deleted one keeps the record`() =
        runTest {
            val database = open()
            try {
                val commands = commands(database)
                val ledger = ledger(database)
                val food = ledger.addCategory("Food")
                val transport = ledger.addCategory("Transport")
                val recordId = ledger.addTransaction(
                    TransactionInput(
                        type = TransactionType.EXPENSE,
                        amountVnd = 45_000L,
                        occurredAtEpochMillis = ManualDateTime.toEpochMillis("2026-10-16", "07:38"),
                        description = "Lunch",
                        categoryId = food,
                    ),
                )

                // Archiving keeps the record filed under the category it already carries.
                ledger.archiveCategory(food)
                val kept = commands.correctTransaction(
                    WorkflowCallerScope.User,
                    CorrectTransactionRequest(
                        idempotencyKey = "correct-keep",
                        transactionId = recordId,
                        expectedRevision = 0,
                        type = TransactionType.EXPENSE,
                        amountVnd = 46_000L,
                        occurredAtEpochMillis = ManualDateTime.toEpochMillis("2026-10-16", "07:38"),
                        description = "Lunch",
                        categoryId = food,
                    ),
                )
                assertTrue("the kept category was refused: $kept", kept is WorkflowCommandOutcome.Applied)
                assertEquals(
                    food,
                    WorkflowQueryService(database).ledgerRecords(ALL_LEDGER_RECORDS).items.single().categoryId,
                )

                // Another archived category is refused for a new assignment.
                ledger.archiveCategory(transport)
                val refused = commands.correctTransaction(
                    WorkflowCallerScope.User,
                    CorrectTransactionRequest(
                        idempotencyKey = "correct-archived",
                        transactionId = recordId,
                        expectedRevision = 1,
                        type = TransactionType.EXPENSE,
                        amountVnd = 46_000L,
                        occurredAtEpochMillis = ManualDateTime.toEpochMillis("2026-10-16", "07:38"),
                        description = "Lunch",
                        categoryId = transport,
                    ),
                )
                assertTrue(
                    "the archived category was not refused: $refused",
                    refused is WorkflowCommandOutcome.Denied,
                )
                assertEquals(
                    WorkflowDenialReason.CATEGORY_UNAVAILABLE,
                    (refused as WorkflowCommandOutcome.Denied).reason,
                )

                // Deleting the category clears the link and keeps the record and its total.
                val before = WorkflowQueryService(database).confirmedLedgerAggregate(
                    LedgerDateRange(
                        fromEpochMillis = 0L,
                        throughExclusiveEpochMillis = Long.MAX_VALUE,
                        zoneId = CAPTURE_ZONE,
                    ),
                )
                ledger.deleteCategory(food)
                val after = WorkflowQueryService(database).confirmedLedgerAggregate(
                    LedgerDateRange(
                        fromEpochMillis = 0L,
                        throughExclusiveEpochMillis = Long.MAX_VALUE,
                        zoneId = CAPTURE_ZONE,
                    ),
                )
                val records = WorkflowQueryService(database).ledgerRecords(ALL_LEDGER_RECORDS).items
                assertEquals(1, records.size)
                assertNull(records.single().categoryId)
                assertEquals(46_000L, records.single().amountVnd)
                assertEquals(before.spending.totalVnd, after.spending.totalVnd)
                assertEquals(before.spending.recordCount, after.spending.recordCount)
            } finally {
                database.close()
            }
        }

    // --- The source event -----------------------------------------------------

    @Test
    fun `the request cannot name a note without the revision of its reading`() {
        val constructors = ManualTransactionSource.NoteEvent::class.java.declaredConstructors
        assertEquals("the source of a record has more than one shape", 1, constructors.size)
        val parameters = constructors.single().parameterTypes
        assertEquals(2, parameters.size)
        // A primitive int is never null: the revision of the reading the caller read
        // cannot be left out of the request, so there is no way to name a note without it.
        assertEquals(Int::class.javaPrimitiveType, parameters[1])
        assertEquals(Long::class.javaPrimitiveType, parameters[0])
    }

    @Test
    fun `a manual record without a note is stored and resolves nothing`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val eventId = storeNoteEvent(database)

            val stored = commands.saveManualTransaction(
                WorkflowCallerScope.User,
                SaveManualTransactionRequest(
                    idempotencyKey = "manual-plain",
                    type = TransactionType.EXPENSE,
                    amountVnd = 45_000L,
                    occurredAtEpochMillis = ManualDateTime.toEpochMillis("2026-10-16", "07:38"),
                    description = "Lunch",
                ),
            )
            assertTrue("the record was not stored: $stored", stored is WorkflowCommandOutcome.Applied)
            val change = (stored as WorkflowCommandOutcome.Applied).change.let {
                it as WorkflowChange.TransactionSaved
            }
            assertNull("the record claimed a note it was not written from", change.sourceEventId)

            // The note the user did not write from is untouched, and still waiting.
            val event = database.noteEventDao().findById(eventId)!!
            assertNull(event.resolvedTransactionId)
            assertEquals(0, event.revision)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a record written from a note is bound to the reading the caller read`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val eventId = storeNoteEvent(database)
            val event = database.noteEventDao().findById(eventId)!!

            val stored = commands.saveManualTransaction(
                WorkflowCallerScope.User,
                SaveManualTransactionRequest(
                    idempotencyKey = "manual-note",
                    type = TransactionType.EXPENSE,
                    amountVnd = 50_000L,
                    occurredAtEpochMillis = ManualDateTime.toEpochMillis("2026-10-16", "15:00"),
                    description = "Fuel",
                    source = ManualTransactionSource.NoteEvent(
                        eventId = eventId,
                        expectedRevision = event.revision,
                    ),
                ),
            )
            assertTrue("the record was not stored: $stored", stored is WorkflowCommandOutcome.Applied)
            val change = (stored as WorkflowCommandOutcome.Applied).change.let {
                it as WorkflowChange.TransactionSaved
            }
            assertEquals(eventId, change.sourceEventId)
            val resolved = database.noteEventDao().findById(eventId)!!
            assertEquals(change.transactionId, resolved.resolvedTransactionId)
            // The reading is spent: it moved on, so a request made against it is stale.
            assertTrue("the reading did not move on", resolved.revision > event.revision)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a note that moved on under the caller cannot be recorded`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val eventId = storeNoteEvent(database)
            val event = database.noteEventDao().findById(eventId)!!

            // The note is read again and the reading moves on: the revision the caller
            // holds is not the revision the reading has.
            val revised = commands.updateEventInterpretation(
                WorkflowCallerScope.User,
                UpdateEventRequest(
                    idempotencyKey = "event-revised",
                    captureId = event.captureId,
                    expectedCaptureRevision = 0,
                    interpretation = NoteEventInterpretation(
                        spanStart = 0,
                        spanEnd = "Fuel 50,000".length,
                        normalizedText = "Fuel 50,000",
                        amountVnd = 51_000L,
                        merchant = "Petrol Station",
                        timeAnchor = NoteEventTimeAnchor(date = "2026-10-16"),
                    ),
                    extractionVersion = "test-v1",
                ),
            )
            assertTrue("the reading was not revised: $revised", revised is WorkflowCommandOutcome.Applied)
            val moved = database.noteEventDao().findById(eventId)!!
            assertTrue("the reading did not move on", moved.revision != event.revision)

            val refused = commands.saveManualTransaction(
                WorkflowCallerScope.User,
                SaveManualTransactionRequest(
                    idempotencyKey = "manual-stale",
                    type = TransactionType.EXPENSE,
                    amountVnd = 50_000L,
                    occurredAtEpochMillis = ManualDateTime.toEpochMillis("2026-10-16", "15:00"),
                    description = "Fuel",
                    source = ManualTransactionSource.NoteEvent(
                        eventId = eventId,
                        expectedRevision = event.revision,
                    ),
                ),
            )
            assertTrue("the stale note was recorded: $refused", refused is WorkflowCommandOutcome.Conflict)
            assertTrue(WorkflowQueryService(database).ledgerRecords(ALL_LEDGER_RECORDS).items.isEmpty())
            assertNull(database.noteEventDao().findById(eventId)!!.resolvedTransactionId)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a note whose reading was cleared cannot be recorded`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val eventId = storeNoteEvent(database)
            val event = database.noteEventDao().findById(eventId)!!

            // The note's own text is changed, so the reading taken from it is cleared: the
            // occurrence it described is no longer there to be recorded.
            val revised = commands.reviseCapture(
                WorkflowCallerScope.User,
                ReviseCaptureRequest(
                    idempotencyKey = "capture-revised",
                    captureId = event.captureId,
                    expectedRevision = 0,
                    bodyText = "Fuel 50,000 at the station",
                ),
            )
            assertTrue("the note was not revised: $revised", revised is WorkflowCommandOutcome.Applied)
            val cleared = database.noteEventDao().findById(eventId)!!
            assertNull("the reading was not cleared", cleared.normalizedText)
            assertTrue("the cleared reading did not move on", cleared.revision != event.revision)

            // The reading is gone, so the record cannot be associated with it — not even
            // under the revision the reading has now.
            val refused = commands.saveManualTransaction(
                WorkflowCallerScope.User,
                SaveManualTransactionRequest(
                    idempotencyKey = "manual-cleared",
                    type = TransactionType.EXPENSE,
                    amountVnd = 50_000L,
                    occurredAtEpochMillis = ManualDateTime.toEpochMillis("2026-10-16", "15:00"),
                    description = "Fuel",
                    source = ManualTransactionSource.NoteEvent(
                        eventId = eventId,
                        expectedRevision = cleared.revision,
                    ),
                ),
            )
            assertTrue("the cleared note was recorded: $refused", refused is WorkflowCommandOutcome.Denied)
            assertEquals(
                WorkflowDenialReason.EVENT_UNAVAILABLE,
                (refused as WorkflowCommandOutcome.Denied).reason,
            )
            assertTrue(WorkflowQueryService(database).ledgerRecords(ALL_LEDGER_RECORDS).items.isEmpty())
            assertNull(database.noteEventDao().findById(eventId)!!.resolvedTransactionId)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a record written from a note resolves it and a later bank arrival cannot consume it again`() =
        runTest {
            val database = open()
            try {
                val commands = commands(database)
                val eventId = storeNoteEvent(database)
                val event = database.noteEventDao().findById(eventId)!!
                assertNull(event.resolvedTransactionId)

                val stored = commands.saveManualTransaction(
                    WorkflowCallerScope.User,
                    SaveManualTransactionRequest(
                        idempotencyKey = "manual-note",
                        type = TransactionType.EXPENSE,
                        amountVnd = 50_000L,
                        occurredAtEpochMillis = ManualDateTime.toEpochMillis("2026-10-16", "15:00"),
                        description = "Fuel",
                        source = ManualTransactionSource.NoteEvent(
                            eventId = eventId,
                            expectedRevision = event.revision,
                        ),
                    ),
                )
                assertTrue("the record was not stored: $stored", stored is WorkflowCommandOutcome.Applied)
                val transactionId = (stored as WorkflowCommandOutcome.Applied).change.let {
                    (it as WorkflowChange.TransactionSaved).transactionId
                }

                val resolved = database.noteEventDao().findById(eventId)!!
                assertEquals(transactionId, resolved.resolvedTransactionId)
                assertNotNull(resolved.resolvedAtEpochMillis)

                // The note is spent: a bank notification that arrives later may not be
                // linked to it, so the same occurrence cannot be consumed twice.
                val lateDraftId = storeDraft(database, key = "key-late", postedAt = POSTED_AT + 60_000L)
                val proposed = commands.proposeLink(
                    WorkflowCallerScope.User,
                    ProposeLinkRequest(idempotencyKey = "propose-late", eventId = eventId, draftId = lateDraftId),
                )
                assertTrue(
                    "the resolved note was linked: $proposed",
                    proposed is WorkflowCommandOutcome.Denied,
                )
                assertEquals(
                    WorkflowDenialReason.EVENT_ALREADY_RECORDED,
                    (proposed as WorkflowCommandOutcome.Denied).reason,
                )
                assertTrue(database.contextLinkDao().findForEvent(eventId).isEmpty())
            } finally {
                database.close()
            }
        }

    @Test
    fun `a resolved note stays resolved after a restart and refuses the attachment`() = runTest {
        val database = open()
        val eventId = storeNoteEvent(database)
        val event = database.noteEventDao().findById(eventId)!!
        val draftId = storeDraft(database, key = "key-early", postedAt = POSTED_AT)
        // The link is proposed while the note is still unrecorded.
        val proposed = commands(database).proposeLink(
            WorkflowCallerScope.User,
            ProposeLinkRequest(
                idempotencyKey = "propose-early",
                eventId = eventId,
                draftId = draftId,
            ),
        )
        assertTrue("the link was not proposed: $proposed", proposed is WorkflowCommandOutcome.Applied)
        val link = database.contextLinkDao().findByPair(eventId, draftId)!!

        commands(database).saveManualTransaction(
            WorkflowCallerScope.User,
            SaveManualTransactionRequest(
                idempotencyKey = "manual-note",
                type = TransactionType.EXPENSE,
                amountVnd = 50_000L,
                occurredAtEpochMillis = ManualDateTime.toEpochMillis("2026-10-16", "15:00"),
                description = "Fuel",
                source = ManualTransactionSource.NoteEvent(
                    eventId = eventId,
                    expectedRevision = event.revision,
                ),
            ),
        )
        database.close()

        val reopened = open()
        try {
            val resolved = reopened.noteEventDao().findById(eventId)!!
            assertNotNull("the resolution did not survive the restart", resolved.resolvedTransactionId)

            // The attachment that was proposed before the note was recorded is refused:
            // the note is spent, so the same occurrence may not be consumed again.
            val attached = commands(reopened).attachLink(
                WorkflowCallerScope.User,
                AttachLinkRequest(
                    idempotencyKey = "attach-early",
                    eventId = eventId,
                    draftId = draftId,
                    expectedRevision = link.revision,
                ),
            )
            assertTrue("the resolved note was attached: $attached", attached is WorkflowCommandOutcome.Denied)
            assertEquals(
                WorkflowDenialReason.EVENT_ALREADY_RECORDED,
                (attached as WorkflowCommandOutcome.Denied).reason,
            )
            assertEquals(
                ContextLinkState.PROPOSED,
                reopened.contextLinkDao().findByPair(eventId, draftId)!!.state,
            )

            // A new proposal for the same spent note is refused as well.
            val lateDraftId = storeDraft(reopened, key = "key-late", postedAt = POSTED_AT + 60_000L)
            val lateProposal = commands(reopened).proposeLink(
                WorkflowCallerScope.User,
                ProposeLinkRequest(
                    idempotencyKey = "propose-late",
                    eventId = eventId,
                    draftId = lateDraftId,
                ),
            )
            assertTrue(
                "the resolved note was linked: $lateProposal",
                lateProposal is WorkflowCommandOutcome.Denied,
            )
            assertEquals(
                WorkflowDenialReason.EVENT_ALREADY_RECORDED,
                (lateProposal as WorkflowCommandOutcome.Denied).reason,
            )
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `the late bank evidence is kept and the duplication risk is surfaced without a second record`() =
        runTest {
            val database = open()
            try {
                val commands = commands(database)
                val eventId = storeNoteEvent(database)
                val event = database.noteEventDao().findById(eventId)!!
                commands.saveManualTransaction(
                    WorkflowCallerScope.User,
                    SaveManualTransactionRequest(
                        idempotencyKey = "manual-note",
                        type = TransactionType.EXPENSE,
                        amountVnd = 50_000L,
                        occurredAtEpochMillis = ManualDateTime.toEpochMillis("2026-10-16", "15:00"),
                        description = "Fuel",
                        source = ManualTransactionSource.NoteEvent(
                            eventId = eventId,
                            expectedRevision = event.revision,
                        ),
                    ),
                )

                // The bank notification arrives later and states the same money.
                val lateDraftId = storeDraft(database, key = "key-late", postedAt = POSTED_AT + 60_000L)
                val drafts = WorkflowQueryService(database).draftCandidates(ALL_DRAFTS).items
                assertEquals(1, drafts.size)
                assertEquals(IntakeReviewStatus.PENDING, drafts.single().reviewStatus)
                assertNull(drafts.single().confirmedTransactionId)

                val preview = commands.previewDraftConfirmation(
                    WorkflowCallerScope.User,
                    PreviewDraftConfirmationRequest(
                        draftId = lateDraftId,
                        edits = edits(amountText = "50000", dateText = "2026-10-16"),
                    ),
                ) as DraftConfirmationPreview.Ready
                val risk = preview.duplicateRisk
                assertNotNull("the duplication risk was not surfaced", risk)
                assertEquals(50_000L, risk!!.amountVnd)
                assertTrue(risk.recordedFromNote)

                // The risk is reported, not acted on: nothing was added to the ledger.
                assertEquals(1, WorkflowQueryService(database).ledgerRecords(ALL_LEDGER_RECORDS).items.size)
                assertEquals(
                    IntakeReviewStatus.PENDING,
                    WorkflowQueryService(database).draftCandidates(ALL_DRAFTS).items.single().reviewStatus,
                )
            } finally {
                database.close()
            }
        }

    @Test
    fun `a note that is already recorded cannot be recorded a second time`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val eventId = storeNoteEvent(database)
            val event = database.noteEventDao().findById(eventId)!!
            commands.saveManualTransaction(
                WorkflowCallerScope.User,
                SaveManualTransactionRequest(
                    idempotencyKey = "manual-note",
                    type = TransactionType.EXPENSE,
                    amountVnd = 50_000L,
                    occurredAtEpochMillis = ManualDateTime.toEpochMillis("2026-10-16", "15:00"),
                    description = "Fuel",
                    source = ManualTransactionSource.NoteEvent(
                        eventId = eventId,
                        expectedRevision = event.revision,
                    ),
                ),
            )
            val again = commands.saveManualTransaction(
                WorkflowCallerScope.User,
                SaveManualTransactionRequest(
                    idempotencyKey = "manual-note-2",
                    type = TransactionType.EXPENSE,
                    amountVnd = 50_000L,
                    occurredAtEpochMillis = ManualDateTime.toEpochMillis("2026-10-16", "15:00"),
                    description = "Fuel",
                    source = ManualTransactionSource.NoteEvent(
                        eventId = eventId,
                        expectedRevision = event.revision,
                    ),
                ),
            )
            assertTrue("the recorded note was recorded again: $again", again is WorkflowCommandOutcome.Conflict)
            assertEquals(1, WorkflowQueryService(database).ledgerRecords(ALL_LEDGER_RECORDS).items.size)
        } finally {
            database.close()
        }
    }

    @Test
    fun `removing the record releases the note again`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val eventId = storeNoteEvent(database)
            val event = database.noteEventDao().findById(eventId)!!
            val stored = commands.saveManualTransaction(
                WorkflowCallerScope.User,
                SaveManualTransactionRequest(
                    idempotencyKey = "manual-note",
                    type = TransactionType.EXPENSE,
                    amountVnd = 50_000L,
                    occurredAtEpochMillis = ManualDateTime.toEpochMillis("2026-10-16", "15:00"),
                    description = "Fuel",
                    source = ManualTransactionSource.NoteEvent(
                        eventId = eventId,
                        expectedRevision = event.revision,
                    ),
                ),
            )
            val transactionId = (stored as WorkflowCommandOutcome.Applied).change.let {
                (it as WorkflowChange.TransactionSaved).transactionId
            }
            val removed = commands.deleteTransaction(
                WorkflowCallerScope.User,
                DeleteTransactionRequest(
                    idempotencyKey = "delete-1",
                    transactionId = transactionId,
                    expectedRevision = 0,
                ),
            )
            assertTrue("the record was not removed: $removed", removed is WorkflowCommandOutcome.Applied)
            assertTrue(WorkflowQueryService(database).ledgerRecords(ALL_LEDGER_RECORDS).items.isEmpty())
            assertNull(database.noteEventDao().findById(eventId)!!.resolvedTransactionId)
        } finally {
            database.close()
        }
    }

    // --- Terminal drafts ------------------------------------------------------

    @Test
    fun `a dismissal of a confirmed draft reports the state the draft is really in`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val draftId = storeDraft(database)
            val preview = commands.previewDraftConfirmation(
                WorkflowCallerScope.User,
                PreviewDraftConfirmationRequest(draftId = draftId, edits = edits()),
            ) as DraftConfirmationPreview.Ready
            val confirmed = commands.confirmDraft(
                WorkflowCallerScope.User,
                ConfirmDraftRequest(
                    idempotencyKey = "confirm-1",
                    draftId = draftId,
                    expectedRevision = preview.expectedRevision,
                    expectedBinding = preview.binding,
                    edits = edits(),
                ),
            )
            assertTrue("the draft was not confirmed: $confirmed", confirmed is WorkflowCommandOutcome.Applied)
            val transactionId = (confirmed as WorkflowCommandOutcome.Applied).change.let {
                (it as WorkflowChange.DraftConfirmed).transactionId
            }

            // The draft is confirmed, so the dismissal changes nothing and says what the
            // draft really is: it is a confirmed draft, not a dismissed one.
            val dismissed = commands.dismissDraft(
                WorkflowCallerScope.User,
                DismissDraftRequest(
                    idempotencyKey = "dismiss-after-confirm",
                    draftId = draftId,
                    expectedRevision = preview.expectedRevision,
                ),
            )
            assertTrue(
                "the dismissal was not a no-change: $dismissed",
                dismissed is WorkflowCommandOutcome.NoChange,
            )
            val change = (dismissed as WorkflowCommandOutcome.NoChange).change
            assertTrue("the dismissal reported the wrong state: $change", change is WorkflowChange.DraftConfirmed)
            assertEquals(transactionId, (change as WorkflowChange.DraftConfirmed).transactionId)

            // The draft is still the confirmed one, and the ledger still holds one record.
            val draft = WorkflowQueryService(database).draftCandidates(ALL_DRAFTS).items.single()
            assertEquals(IntakeReviewStatus.CONFIRMED, draft.reviewStatus)
            assertEquals(transactionId, draft.confirmedTransactionId)
            assertEquals(1, WorkflowQueryService(database).ledgerRecords(ALL_LEDGER_RECORDS).items.size)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a repeated dismissal of a confirmed draft keeps reporting the confirmed state`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val draftId = storeDraft(database)
            val preview = commands.previewDraftConfirmation(
                WorkflowCallerScope.User,
                PreviewDraftConfirmationRequest(draftId = draftId, edits = edits()),
            ) as DraftConfirmationPreview.Ready
            commands.confirmDraft(
                WorkflowCallerScope.User,
                ConfirmDraftRequest(
                    idempotencyKey = "confirm-1",
                    draftId = draftId,
                    expectedRevision = preview.expectedRevision,
                    expectedBinding = preview.binding,
                    edits = edits(),
                ),
            )

            val request = DismissDraftRequest(
                idempotencyKey = "dismiss-1",
                draftId = draftId,
                expectedRevision = preview.expectedRevision,
            )
            val first = commands.dismissDraft(WorkflowCallerScope.User, request)
            assertTrue("the dismissal was not a no-change: $first", first is WorkflowCommandOutcome.NoChange)
            val again = commands.dismissDraft(WorkflowCallerScope.User, request)
            assertTrue("the repeat was not a no-change: $again", again is WorkflowCommandOutcome.NoChange)
            val change = (again as WorkflowCommandOutcome.NoChange).change
            assertTrue("the repeat reported the wrong state: $change", change is WorkflowChange.DraftConfirmed)
            assertEquals(1, WorkflowQueryService(database).ledgerRecords(ALL_LEDGER_RECORDS).items.size)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a review of a confirmed draft reports the state the draft is really in`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val draftId = storeDraft(database)
            val preview = commands.previewDraftConfirmation(
                WorkflowCallerScope.User,
                PreviewDraftConfirmationRequest(draftId = draftId, edits = edits()),
            ) as DraftConfirmationPreview.Ready
            commands.confirmDraft(
                WorkflowCallerScope.User,
                ConfirmDraftRequest(
                    idempotencyKey = "confirm-1",
                    draftId = draftId,
                    expectedRevision = preview.expectedRevision,
                    expectedBinding = preview.binding,
                    edits = edits(),
                ),
            )

            // The corrections are not stored on a draft that was already decided, and the
            // answer names the state the draft is really in.
            val reviewed = commands.saveDraftReview(
                WorkflowCallerScope.User,
                SaveDraftReviewRequest(
                    idempotencyKey = "review-after-confirm",
                    draftId = draftId,
                    expectedRevision = preview.expectedRevision,
                    edits = edits(amountText = "60000"),
                ),
            )
            assertTrue("the review was not a no-change: $reviewed", reviewed is WorkflowCommandOutcome.NoChange)
            val change = (reviewed as WorkflowCommandOutcome.NoChange).change
            assertTrue("the review reported the wrong state: $change", change is WorkflowChange.DraftConfirmed)

            // The stored draft still holds the confirmed amount: the later corrections
            // were not written over it.
            val draft = database.pendingBankDraftDao().findById(draftId)!!
            assertEquals("50000", draft.reviewAmountText)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a review of a dismissed draft reports the dismissal`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val draftId = storeDraft(database)
            commands.dismissDraft(
                WorkflowCallerScope.User,
                DismissDraftRequest(
                    idempotencyKey = "dismiss-1",
                    draftId = draftId,
                    expectedRevision = 0,
                ),
            )

            val reviewed = commands.saveDraftReview(
                WorkflowCallerScope.User,
                SaveDraftReviewRequest(
                    idempotencyKey = "review-after-dismiss",
                    draftId = draftId,
                    expectedRevision = 0,
                    edits = edits(amountText = "60000"),
                ),
            )
            assertTrue("the review was not a no-change: $reviewed", reviewed is WorkflowCommandOutcome.NoChange)
            val change = (reviewed as WorkflowCommandOutcome.NoChange).change
            assertTrue("the review reported the wrong state: $change", change is WorkflowChange.DraftDismissed)

            // The dismissed draft holds no correction: the request wrote nothing.
            val draft = database.pendingBankDraftDao().findById(draftId)!!
            assertNull(draft.reviewAmountText)
            assertEquals(IntakeReviewStatus.DISMISSED, draft.reviewStatus)
            assertTrue(WorkflowQueryService(database).ledgerRecords(ALL_LEDGER_RECORDS).items.isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun `a confirmation of a dismissed draft reports the dismissal`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val draftId = storeDraft(database)
            commands.dismissDraft(
                WorkflowCallerScope.User,
                DismissDraftRequest(
                    idempotencyKey = "dismiss-1",
                    draftId = draftId,
                    expectedRevision = 0,
                ),
            )

            val confirmed = commands.confirmDraft(
                WorkflowCallerScope.User,
                ConfirmDraftRequest(
                    idempotencyKey = "confirm-1",
                    draftId = draftId,
                    expectedRevision = 0,
                    expectedBinding = "stale-binding",
                    edits = edits(),
                ),
            )
            assertTrue(
                "the confirmation was not a no-change: $confirmed",
                confirmed is WorkflowCommandOutcome.NoChange,
            )
            val change = (confirmed as WorkflowCommandOutcome.NoChange).change
            assertTrue("the confirmation reported the wrong state: $change", change is WorkflowChange.DraftDismissed)
            assertTrue(WorkflowQueryService(database).ledgerRecords(ALL_LEDGER_RECORDS).items.isEmpty())
        } finally {
            database.close()
        }
    }

    // --- Dismissal ------------------------------------------------------------

    @Test
    fun `a dismissed draft is terminal and never becomes a record`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val draftId = storeDraft(database)
            val dismissed = commands.dismissDraft(
                WorkflowCallerScope.User,
                DismissDraftRequest(
                    idempotencyKey = "dismiss-1",
                    draftId = draftId,
                    expectedRevision = 0,
                ),
            )
            assertTrue("the draft was not dismissed: $dismissed", dismissed is WorkflowCommandOutcome.Applied)
            assertEquals(
                IntakeReviewStatus.DISMISSED,
                WorkflowQueryService(database).draftCandidates(ALL_DRAFTS).items.single().reviewStatus,
            )

            val preview = commands.previewDraftConfirmation(
                WorkflowCallerScope.User,
                PreviewDraftConfirmationRequest(draftId = draftId, edits = edits()),
            )
            assertTrue("the dismissed draft was previewed: $preview", preview is DraftConfirmationPreview.Conflict)
            assertTrue(WorkflowQueryService(database).ledgerRecords(ALL_LEDGER_RECORDS).items.isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun `the stored operations name the financial requests without their text`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            commands.saveManualTransaction(
                WorkflowCallerScope.User,
                SaveManualTransactionRequest(
                    idempotencyKey = "manual-1",
                    type = TransactionType.EXPENSE,
                    amountVnd = 45_000L,
                    occurredAtEpochMillis = ManualDateTime.toEpochMillis("2026-10-16", "07:38"),
                    description = "Lunch",
                ),
            )
            val recorded = database.workflowOperationDao().findByKey("manual-1")
            assertNotNull(recorded)
            assertEquals(WorkflowCommandService.COMMAND_SAVE_MANUAL_TRANSACTION, recorded!!.command)
            assertFalse("the request text was stored", recorded.changeSummary.orEmpty().contains("Lunch"))
        } finally {
            database.close()
        }
    }

    private companion object {
        const val BANK_PACKAGE = "com.example.bank"
        const val POSTED_AT = 1_797_000_000_000L
        const val CAPTURE_TIME = 1_797_000_000_000L
        const val CAPTURE_ZONE = "Asia/Ho_Chi_Minh"
    }
}
