package io.github.triet4p.aegisf.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S10.9 device fixture: one deterministic, synthetic corpus for the real-shell walk.
 *
 * Every row is written through the production command/DAO surfaces of the isolated evidence
 * package after the package data has been cleared. No owner row, note, draft or preference is
 * read or written; the run is repeated from a fresh database each time.
 */
@RunWith(AndroidJUnit4::class)
class S109FixtureInstrumentedTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun stage() = runBlocking {
        assertEquals(ISOLATED_PACKAGE, context.packageName)
        val stage = InstrumentationRegistry.getArguments().getString("s109_stage") ?: "state"
        val database = Room.databaseBuilder(
            context.applicationContext,
            LedgerDatabase::class.java,
            LedgerDatabase.NAME,
        ).build()
        try {
            when (stage) {
                "seed" -> seed(database)
                "state" -> state(database)
                else -> error("unknown stage: $stage")
            }
        } finally {
            database.close()
        }
    }

    private suspend fun seed(database: LedgerDatabase) {
        val commands = WorkflowCommandService(database, timezoneId = { ZONE })
        val food = insertCategory(database, "Food")
        val transport = insertCategory(database, "Transport")
        val salary = insertCategory(database, "Salary")
        val today = LocalDate.now(ZoneId.of(ZONE)).toString()

        // Core exact-money records, dated inside the current month for Home and Analytics.
        commands.saveManualTransaction(
            WorkflowCallerScope.User,
            SaveManualTransactionRequest(
                idempotencyKey = "s109-record-${UUID.randomUUID()}",
                type = TransactionType.EXPENSE,
                amountVnd = 45_000,
                occurredAtEpochMillis = System.currentTimeMillis() - 3_600_000,
                description = "Phở Thìn lunch",
                categoryId = food,
            ),
        )
        commands.saveManualTransaction(
            WorkflowCallerScope.User,
            SaveManualTransactionRequest(
                idempotencyKey = "s109-record-${UUID.randomUUID()}",
                type = TransactionType.EXPENSE,
                amountVnd = 1_234_567,
                occurredAtEpochMillis = System.currentTimeMillis() - 7_200_000,
                description = "Rent share for the month with a deliberately long merchant description that wraps",
                categoryId = food,
            ),
        )
        commands.saveManualTransaction(
            WorkflowCallerScope.User,
            SaveManualTransactionRequest(
                idempotencyKey = "s109-record-${UUID.randomUUID()}",
                type = TransactionType.INCOME,
                amountVnd = 28_500_000,
                occurredAtEpochMillis = System.currentTimeMillis() - 10_800_000,
                description = "Monthly salary",
                categoryId = salary,
            ),
        )
        commands.saveManualTransaction(
            WorkflowCallerScope.User,
            SaveManualTransactionRequest(
                idempotencyKey = "s109-record-${UUID.randomUUID()}",
                type = TransactionType.TRANSFER,
                amountVnd = 5_000_000,
                occurredAtEpochMillis = System.currentTimeMillis() - 14_400_000,
                description = "Move to savings",
                categoryId = transport,
            ),
        )
        // A longer ledger page: 30 small expenses with exact integer dong amounts.
        repeat(30) { index ->
            commands.saveManualTransaction(
                WorkflowCallerScope.User,
                SaveManualTransactionRequest(
                    idempotencyKey = "s109-list-${UUID.randomUUID()}",
                    type = TransactionType.EXPENSE,
                    amountVnd = (index + 1).toLong() * 1_001L,
                    occurredAtEpochMillis = System.currentTimeMillis() - (index + 2) * 86_400_000L,
                    description = "Synthetic long-list record ${index + 1} with a descriptive tail",
                    categoryId = if (index % 2 == 0) transport else food,
                ),
            )
        }

        // Multi-event note used for the note → manual handoff and the note → chat handoff.
        val noteBody =
            "Lunch at Phở Thìn 65k\nCoffee 45k at Highlands\nBook 120k at the shop"
        val note = commands.saveCapture(
            WorkflowCallerScope.User,
            SaveCaptureRequest("s109-capture-${UUID.randomUUID()}", noteBody),
        ) as WorkflowCommandOutcome.Applied
        val captureId = (note.change as WorkflowChange.CaptureSaved).captureId
        val readings = listOf(
            Triple("Lunch at Phở Thìn 65k", 65_000L, "Phở Thìn"),
            Triple("Coffee 45k at Highlands", 45_000L, "Highlands"),
            Triple("Book 120k at the shop", 120_000L, "Book shop"),
        )
        readings.forEach { (excerpt, amount, merchant) ->
            val start = noteBody.indexOf(excerpt)
            commands.updateEventInterpretation(
                WorkflowCallerScope.User,
                UpdateEventRequest(
                    idempotencyKey = "s109-event-${UUID.randomUUID()}",
                    captureId = captureId,
                    expectedCaptureRevision = 0,
                    interpretation = NoteEventInterpretation(
                        spanStart = start,
                        spanEnd = start + excerpt.length,
                        normalizedText = excerpt,
                        amountVnd = amount,
                        merchant = merchant,
                        timeAnchor = NoteEventTimeAnchor(
                            date = today,
                            time = "12:30",
                            timezoneId = ZONE,
                        ),
                    ),
                    extractionVersion = "s109-phone-fixture",
                ),
            )
        }

        // Review inbox: one ready, one missing category, one unreadable, a duplicate pair and a
        // proposed note–draft link that the user must still decide.
        val ready = storeDraft(database, "ready", 500_000, "Synthetic ready draft", today)
        prepareReview(commands, database, ready, TransactionType.EXPENSE, food, "500000")
        storeDraft(database, "needs-category", 65_000, "Synthetic category draft", today)
        storeDraft(
            database,
            "unreadable",
            amount = null,
            memo = "Synthetic unreadable draft",
            today = today,
            amountState = IntakeFieldState.UNRESOLVED,
            parseStatus = IntakeParseStatus.UNSUPPORTED_FORMAT,
        )
        storeDraft(database, "dup-a", 77_000, "Synthetic duplicate evidence", today)
        storeDraft(database, "dup-b", 77_000, "Synthetic duplicate evidence", today)
        val linkedDraft = storeDraft(database, "linked", 45_000, "Synthetic linked draft", today)
        val events = database.noteEventDao().findForCapture(captureId)
        val coffeeEvent = events.first { it.normalizedText.orEmpty().contains("Coffee") }
        commands.proposeLink(
            WorkflowCallerScope.User,
            ProposeLinkRequest("s109-proposal-${UUID.randomUUID()}", coffeeEvent.id, linkedDraft),
        )

        emit(
            "S109SEED categories=${database.categoryDao().findAll().size} " +
                "transactions=${database.transactionDao().findAll().size} " +
                "captures=${database.captureMessageDao().findAllIncludingTombstones().size} " +
                "events=${database.noteEventDao().findForCapture(captureId).size} " +
                "drafts=${database.pendingBankDraftDao().findAll().size} " +
                "pending=${database.pendingBankDraftDao().findAll().count { it.reviewStatus == IntakeReviewStatus.PENDING }}",
        )
        emit("S109SEED captureId=$captureId readyDraft=$ready linkedDraft=$linkedDraft")
    }

    private suspend fun state(database: LedgerDatabase) {
        val aggregate = WorkflowQueryService(database).confirmedLedgerAggregate(
            LedgerDateRange.closed(0L, 4_000_000_000_000L, ZONE),
        )
        emit(
            "S109STATE transactions=${database.transactionDao().findAll().size} " +
                "categories=${database.categoryDao().findAll().size} " +
                "drafts=${database.pendingBankDraftDao().findAll().size} " +
                "spending=${aggregate.spending.totalVnd} income=${aggregate.income.totalVnd} " +
                "transfer=${aggregate.transfers.totalVnd}",
        )
    }

    private suspend fun insertCategory(database: LedgerDatabase, name: String): Long =
        database.categoryDao().insert(
            CategoryEntity(
                name = name,
                createdAtEpochMillis = System.currentTimeMillis(),
                updatedAtEpochMillis = System.currentTimeMillis(),
            ),
        )

    private suspend fun storeDraft(
        database: LedgerDatabase,
        key: String,
        amount: Long?,
        memo: String,
        today: String,
        amountState: IntakeFieldState = IntakeFieldState.PRESENT,
        parseStatus: IntakeParseStatus = IntakeParseStatus.PARSED,
    ): Long = database.pendingBankDraftDao().store(
        PendingBankDraftEntity(
            deliveryId = "s109-$key-${UUID.randomUUID()}",
            sourcePackage = "synthetic.fixture",
            notificationKey = null,
            postedAtEpochMillis = System.currentTimeMillis(),
            bank = "VIETINBANK",
            parseStatus = parseStatus,
            amountState = amountState,
            amountVnd = amount,
            amountUnresolvedReason = if (amountState == IntakeFieldState.UNRESOLVED) {
                "UNREADABLE"
            } else {
                null
            },
            direction = IntakeDirection.DEBIT,
            momentState = IntakeFieldState.PRESENT,
            statedDate = today,
            statedTime = "15:00",
            momentUnresolvedReason = null,
            memoState = IntakeFieldState.PRESENT,
            memo = memo,
            memoUnresolvedReason = null,
            createdAtEpochMillis = System.currentTimeMillis(),
        ),
    )

    private suspend fun prepareReview(
        commands: WorkflowCommandService,
        database: LedgerDatabase,
        draftId: Long,
        type: TransactionType,
        categoryId: Long,
        amountText: String,
    ) {
        val draft = database.pendingBankDraftDao().findById(draftId)
            ?: error("draft $draftId not stored")
        val outcome = commands.saveDraftReview(
            WorkflowCallerScope.User,
            SaveDraftReviewRequest(
                idempotencyKey = "s109-prepare-$draftId-${UUID.randomUUID()}",
                draftId = draftId,
                expectedRevision = draft.reviewRevision,
                edits = BankDraftReviewEdits(
                    amountText = amountText,
                    dateText = draft.statedDate.orEmpty(),
                    timeText = draft.statedTime.orEmpty(),
                    memoText = draft.memo.orEmpty(),
                    type = type,
                    categoryId = categoryId,
                    categoryResolved = true,
                ),
            ),
        )
        check(outcome is WorkflowCommandOutcome.Applied) { "review preparation refused: $outcome" }
    }

    private fun emit(line: String) {
        android.util.Log.i("S109SEED", line)
        println(line)
    }

    private companion object {
        const val ISOLATED_PACKAGE = "io.github.triet4p.aegisf.s109evidence"
        const val ZONE = "Asia/Ho_Chi_Minh"
    }
}
