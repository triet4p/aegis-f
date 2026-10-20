package io.github.triet4p.aegisf.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.triet4p.aegisf.notification.PermittedNotificationContent
import io.github.triet4p.aegisf.ui.ManualDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

/** Review transitions and their consumer-visible ledger effects on the production Room file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BankDraftReviewTest {

    private class FixedLedgerClock : LedgerClock {
        override fun nowEpochMillis(): Long = CREATED_AT
    }

    private lateinit var context: Context
    private val clock = FixedLedgerClock()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(LedgerDatabase.NAME)
    }

    private fun open(): LedgerDatabase = LedgerDatabase.build(context)

    private fun intake(database: LedgerDatabase) = IntakeRepository(
        database = database,
        clock = clock,
    )

    private fun review(database: LedgerDatabase) = BankDraftReviewRepository(
        draftDao = database.pendingBankDraftDao(),
        reviewDao = database.bankDraftReviewDao(),
        clock = clock,
    )

    private suspend fun storeSample(database: LedgerDatabase, postedAt: Long = POSTED_AT): Long =
        intake(database).storePermitted(
            sourcePackage = BANK_PACKAGE,
            notificationKey = FIRST_KEY,
            postedAtEpochMillis = postedAt,
            content = sample(),
        )

    private fun edits(
        type: TransactionType?,
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

    @Test
    fun `correction confirms one linked record and replay after reopen or deletion stays terminal`() = runTest {
        val originalZone = TimeZone.getDefault()
        val deviceZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
        TimeZone.setDefault(deviceZone)
        val first = open()
        val query = WorkflowQueryService(first)
        try {
            val ledger = LedgerRepository(
                first.categoryDao(),
                first.transactionDao(),
                first.rememberedCategoryRuleDao(),
                first.captureMessageDao(),
                clock,
            )
            val categoryId = ledger.addCategory("Transport")
            val intakeRepository = intake(first)
            val reviewRepository = review(first)
            val draftId = storeSample(first)
            val original = WorkflowQueryService(first).draftCandidates(ALL_DRAFTS).items.single()
            val corrections = edits(
                type = TransactionType.EXPENSE,
                amountText = "60000",
                dateText = "2026-10-17",
                timeText = "08:45",
                memoText = "Taxi fare",
                categoryId = categoryId,
            )

            val result = reviewRepository.confirmDraft(draftId, 0, corrections)

            assertTrue(result is BankDraftReviewResult.Confirmed)
            val transactionId = (result as BankDraftReviewResult.Confirmed).transactionId
            val transaction = query.findLedgerRecord(transactionId)!!
            assertEquals(TransactionType.EXPENSE, transaction.type)
            assertEquals(60_000L, transaction.amountVnd)
            assertEquals(
                ManualDateTime.toEpochMillis("2026-10-17", "08:45", deviceZone),
                transaction.occurredAtEpochMillis,
            )
            assertEquals("Taxi fare", transaction.description)
            assertEquals(categoryId, transaction.categoryId)
            assertEquals(ConfirmationStatus.CONFIRMED, transaction.confirmation)
            assertEquals(RecordSource.BANK_NOTIFICATION, transaction.source)

            val confirmedDraft = WorkflowQueryService(first).draftCandidates(ALL_DRAFTS).items.single()
            assertEquals(IntakeReviewStatus.CONFIRMED, confirmedDraft.reviewStatus)
            assertEquals(transactionId, confirmedDraft.confirmedTransactionId)
            assertEquals(original.deliveryId, confirmedDraft.deliveryId)
            assertEquals(original.evidenceDigest, confirmedDraft.evidenceDigest)
            assertEquals(50_000L, confirmedDraft.amountVnd)
            assertEquals("2026-10-16", confirmedDraft.statedDate)
            assertEquals("07:38", confirmedDraft.statedTime)
            assertEquals("60000", confirmedDraft.reviewAmountText)
            assertEquals("2026-10-17", confirmedDraft.reviewDateText)

            // The exact delivery still identifies the corrected terminal row, not a new draft.
            assertEquals(draftId, storeSample(first))
            assertEquals(1, query.ledgerRecords(ALL_LEDGER_RECORDS).items.size)
            first.close()

            val reopened = open()
            val reopenedQuery = WorkflowQueryService(reopened)
            try {
                val reopenedIntake = intake(reopened)
                val reopenedLedger = LedgerRepository(
                    reopened.categoryDao(),
                    reopened.transactionDao(),
                    reopened.rememberedCategoryRuleDao(),
                    reopened.captureMessageDao(),
                    clock,
                )
                val durable = WorkflowQueryService(reopened).draftCandidates(ALL_DRAFTS).items.single()
                assertEquals(draftId, durable.id)
                assertEquals(IntakeReviewStatus.CONFIRMED, durable.reviewStatus)
                assertEquals(transactionId, durable.confirmedTransactionId)
                assertEquals(1, reopenedQuery.ledgerRecords(ALL_LEDGER_RECORDS).items.size)
                assertEquals(draftId, storeSample(reopened))

                // Existing deletion behavior clears only the FK link; terminal replay identity
                // still prevents the deleted bank-derived record from being recreated.
                reopenedLedger.deleteTransaction(transactionId)
                val afterDelete = WorkflowQueryService(reopened).draftCandidates(ALL_DRAFTS).items.single()
                assertEquals(IntakeReviewStatus.CONFIRMED, afterDelete.reviewStatus)
                assertNull(afterDelete.confirmedTransactionId)
                assertEquals(draftId, storeSample(reopened))
                assertTrue(reopenedQuery.ledgerRecords(ALL_LEDGER_RECORDS).items.isEmpty())
            } finally {
                reopened.close()
            }
        } finally {
            if (first.isOpen) first.close()
            TimeZone.setDefault(originalZone)
        }
    }

    @Test
    fun `income and transfer confirmations do not enter expense totals`() = runTest {
        val originalZone = TimeZone.getDefault()
        val zone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
        TimeZone.setDefault(zone)
        val database = open()
        val query = WorkflowQueryService(database)
        try {
            val ledger = LedgerRepository(
                database.categoryDao(),
                database.transactionDao(),
                database.rememberedCategoryRuleDao(),
                database.captureMessageDao(),
                clock,
            )
            val categoryId = ledger.addCategory("Shopping")
            val reviewRepository = review(database)
            val drafts = intake(database)
            val expenseId = storeSample(database, POSTED_AT)
            val incomeId = storeSample(database, POSTED_AT + 60_000L)
            val transferId = storeSample(database, POSTED_AT + 120_000L)

            assertTrue(
                reviewRepository.confirmDraft(
                    expenseId,
                    0,
                    edits(TransactionType.EXPENSE, categoryId = categoryId),
                ) is BankDraftReviewResult.Confirmed,
            )
            assertTrue(
                reviewRepository.confirmDraft(
                    incomeId,
                    0,
                    edits(TransactionType.INCOME),
                ) is BankDraftReviewResult.Confirmed,
            )
            assertTrue(
                reviewRepository.confirmDraft(
                    transferId,
                    0,
                    edits(TransactionType.TRANSFER, categoryResolved = false),
                ) is BankDraftReviewResult.Confirmed,
            )

            val transactions = query.ledgerRecords(ALL_LEDGER_RECORDS).items
            assertEquals(3, transactions.size)
            assertEquals(1, transactions.count { it.type == TransactionType.EXPENSE })
            assertEquals(1, transactions.count { it.type == TransactionType.INCOME })
            val transfer = transactions.single { it.type == TransactionType.TRANSFER }
            assertNull(transfer.categoryId)
            val monthStart = ManualDateTime.monthStartEpochMillis(
                ManualDateTime.toEpochMillis("2026-10-16", "07:38", zone),
                zone,
            )
            val summary = summarizeConfirmedSpending(
                transactions,
                monthStart,
                ManualDateTime.nextMonthStartEpochMillis(monthStart, zone),
            )
            assertEquals(1, summary.recordCount)
            assertEquals(50_000L, summary.totalVnd)
            val reviewedDrafts = WorkflowQueryService(database).draftCandidates(ALL_DRAFTS).items
            assertEquals(3, reviewedDrafts.count { it.reviewStatus == IntakeReviewStatus.CONFIRMED })
            assertEquals(IntakeFieldState.UNRESOLVED, reviewedDrafts.first { it.id == incomeId }.memoState)
        } finally {
            database.close()
            TimeZone.setDefault(originalZone)
        }
    }

    @Test
    fun `concurrent confirm writes one record and dismiss blocks stale confirmation and replay`() = runTest {
        val database = open()
        val query = WorkflowQueryService(database)
        try {
            val reviewRepository = review(database)
            val ledger = LedgerRepository(
                database.categoryDao(),
                database.transactionDao(),
                database.rememberedCategoryRuleDao(),
                database.captureMessageDao(),
                clock,
            )
            val concurrentDraftId = storeSample(database)
            val results = (1..8).map {
                async(Dispatchers.IO) {
                    reviewRepository.confirmDraft(
                        concurrentDraftId,
                        0,
                        edits(TransactionType.EXPENSE),
                    )
                }
            }.awaitAll()
            assertEquals(1, results.count { it is BankDraftReviewResult.Confirmed })
            assertEquals(7, results.count { it == BankDraftReviewResult.AlreadyReviewed })
            assertEquals(1, query.ledgerRecords(ALL_LEDGER_RECORDS).items.size)
            assertEquals(IntakeReviewStatus.CONFIRMED, WorkflowQueryService(database).draftCandidates(ALL_DRAFTS).items.single().reviewStatus)

            val dismissedId = storeSample(database, POSTED_AT + 60_000L)
            assertEquals(BankDraftReviewResult.Dismissed, reviewRepository.dismissDraft(dismissedId))
            assertEquals(
                BankDraftReviewResult.AlreadyReviewed,
                reviewRepository.confirmDraft(dismissedId, 0, edits(TransactionType.EXPENSE)),
            )
            assertEquals(dismissedId, storeSample(database, POSTED_AT + 60_000L))
            val dismissed = WorkflowQueryService(database).draftCandidates(ALL_DRAFTS).items.single { it.id == dismissedId }
            assertEquals(IntakeReviewStatus.DISMISSED, dismissed.reviewStatus)
            assertNull(dismissed.confirmedTransactionId)
            assertEquals(1, query.ledgerRecords(ALL_LEDGER_RECORDS).items.size)
            val monthMoment = ManualDateTime.toEpochMillis(
                "2026-10-16",
                "07:38",
                TimeZone.getDefault(),
            )
            val monthStart = ManualDateTime.monthStartEpochMillis(monthMoment)
            val summary = summarizeConfirmedSpending(
                query.ledgerRecords(ALL_LEDGER_RECORDS).items,
                monthStart,
                ManualDateTime.nextMonthStartEpochMillis(monthStart),
            )
            assertEquals(1, summary.recordCount)
            assertEquals(50_000L, summary.totalVnd)
        } finally {
            database.close()
        }
    }

    @Test
    fun `missing required values are reported without creating a transaction`() = runTest {
        val database = open()
        val query = WorkflowQueryService(database)
        try {
            val reviewRepository = review(database)
            val ledger = LedgerRepository(
                database.categoryDao(),
                database.transactionDao(),
                database.rememberedCategoryRuleDao(),
                database.captureMessageDao(),
                clock,
            )
            val draftId = intake(database).storePermitted(
                sourcePackage = BANK_PACKAGE,
                notificationKey = null,
                postedAtEpochMillis = POSTED_AT,
                content = PermittedNotificationContent(
                    title = "Account update",
                    text = "Your balance changed",
                    bigText = null,
                ),
            )

            val result = reviewRepository.confirmDraft(
                draftId,
                0,
                edits(
                    type = null,
                    amountText = "",
                    dateText = "",
                    timeText = "",
                    categoryResolved = false,
                ),
            )

            assertEquals(
                BankDraftReviewResult.NeedsCorrection(
                    setOf(
                        BankDraftReviewField.AMOUNT,
                        BankDraftReviewField.DATE,
                        BankDraftReviewField.TIME,
                        BankDraftReviewField.TYPE,
                        BankDraftReviewField.CATEGORY,
                    ),
                ),
                result,
            )
            assertTrue(query.ledgerRecords(ALL_LEDGER_RECORDS).items.isEmpty())
            assertEquals(IntakeReviewStatus.PENDING, WorkflowQueryService(database).draftCandidates(ALL_DRAFTS).items.single().reviewStatus)

            val completed = edits(
                type = TransactionType.INCOME,
                amountText = "120000",
                dateText = "2026-10-16",
                timeText = "07:38",
                categoryResolved = true,
            )
            assertTrue(reviewRepository.confirmDraft(draftId, 0, completed) is BankDraftReviewResult.Confirmed)
            assertEquals(1, query.ledgerRecords(ALL_LEDGER_RECORDS).items.size)
        } finally {
            database.close()
        }
    }

    @Test
    fun `saved corrections reject stale editors and duplicate link keeps prior review state`() = runTest {
        val database = open()
        val query = WorkflowQueryService(database)
        try {
            val reviewRepository = review(database)
            val firstId = storeSample(database)
            val laterId = storeSample(database, POSTED_AT + 60_000L)
            val firstDraft = WorkflowQueryService(database).draftCandidates(ALL_DRAFTS).items.first { it.id == firstId }
            val laterDraft = WorkflowQueryService(database).draftCandidates(ALL_DRAFTS).items.first { it.id == laterId }
            assertEquals(firstId, laterDraft.possibleDuplicateOfDraftId)

            val firstEdits = edits(TransactionType.EXPENSE)
            assertEquals(
                BankDraftReviewResult.Saved(1),
                reviewRepository.saveReview(firstId, 0, firstEdits),
            )
            assertEquals(
                BankDraftReviewResult.Changed,
                reviewRepository.saveReview(firstId, 0, firstEdits),
            )
            assertEquals(
                BankDraftReviewResult.Changed,
                reviewRepository.confirmDraft(firstId, 0, firstEdits),
            )
            assertTrue(reviewRepository.confirmDraft(firstId, 1, firstEdits) is BankDraftReviewResult.Confirmed)

            val latest = WorkflowQueryService(database).draftCandidates(ALL_DRAFTS).items
            val duplicate = latest.single { it.id == laterId }
            val prior = latest.single { it.id == duplicate.possibleDuplicateOfDraftId }
            assertEquals(IntakeReviewStatus.CONFIRMED, prior.reviewStatus)
            assertEquals(IntakeReviewStatus.PENDING, duplicate.reviewStatus)
            assertEquals(firstDraft.deliveryId, prior.deliveryId)

            val dismissedAnchorId = intake(database).storePermitted(
                BANK_PACKAGE,
                FIRST_KEY,
                POSTED_AT + 120_000L,
                sample(amount = "-60,000 VND"),
            )
            assertEquals(BankDraftReviewResult.Dismissed, reviewRepository.dismissDraft(dismissedAnchorId))
            val laterCandidateId = intake(database).storePermitted(
                BANK_PACKAGE,
                FIRST_KEY,
                POSTED_AT + 180_000L,
                sample(amount = "-60,000 VND"),
            )
            val related = WorkflowQueryService(database).draftCandidates(ALL_DRAFTS).items
            val dismissedPrior = related.single { it.id == dismissedAnchorId }
            val laterCandidate = related.single { it.id == laterCandidateId }
            assertEquals(dismissedAnchorId, laterCandidate.possibleDuplicateOfDraftId)
            assertEquals(IntakeReviewStatus.DISMISSED, dismissedPrior.reviewStatus)
            assertEquals(IntakeReviewStatus.PENDING, laterCandidate.reviewStatus)
        } finally {
            database.close()
        }
    }

    private companion object {
        const val BANK_PACKAGE = "com.vietinbank.ipay"
        const val FIRST_KEY = "0|com.vietinbank.ipay|1|sample|100"
        const val POSTED_AT = 1_760_000_000_000L
        const val CREATED_AT = 1_760_000_001_000L

        fun sample(amount: String = "-50,000 VND"): PermittedNotificationContent =
            PermittedNotificationContent(
                title = "Tin biến động số dư",
                text = listOf(
                    "VietinBank: 16/10/2026 07:38",
                    "TK: Redacted",
                    "GD: $amount",
                    "SDC: xxx,000 VND",
                    "ND: ........",
                ).joinToString("\n"),
                bigText = null,
            )
    }
}
