package io.github.triet4p.aegisf.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.triet4p.aegisf.notification.PermittedNotificationContent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The S3.5 identity rules on the real database and the production repository: what a repeated
 * delivery of one post does, what a changed statement under a reused notification key does, what a
 * later post under a reused key stating *identical* evidence does, and what two equal statements
 * under different deliveries do.
 *
 * A delivery is the platform post — source package, notification key, and post time — so a bank
 * that reuses one key for a later transaction posts a different delivery whenever the post time
 * differs, even when the permitted evidence is byte-identical: that case is loss, not a replay,
 * and the tests below pin it as two kept, flagged candidates.
 *
 * Every case runs [IntakeRepository] and [PendingBankDraftDao] against the production database
 * builder [LedgerDatabase.build], so the unique delivery/evidence constraint, the transactional
 * store, and the review-state columns are the real ones; closing and reopening the database is
 * what a process restart does. Drafts are moved to their terminal review states with SQL on the
 * real table, because no status-transition API exists yet — S3.6 owns confirmation and dismissal,
 * and this task deliberately adds no production method just for tests.
 *
 * The fixtures are the user's own redacted VietinBank sample
 * (`artifacts/sprint-3/handoffs/vietinbank-notification-context.md`), kept verbatim: the padded
 * account line and the dotted memo are never extracted, no account or balance value is asserted,
 * and no transaction reference is invented — the sample states none, which is exactly why equal
 * evidence from two deliveries must stay two drafts.
 *
 * The two cases are kept apart. With the user's masked `ND:`, two deliveries inside one minute
 * state byte-identical evidence and stay two flagged candidates. With a description the message
 * actually states — including a reference-like `ND: REF#101` — the description is ordinary `ND:`
 * text, and nothing here reads it as, or invents, a transaction reference; because it is part of
 * the evidence identity, two deliveries stating different descriptions are different statements:
 * both are kept, neither is flagged, and each can be confirmed to its own ledger record.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingBankDraftDeduplicationTest {

    private class FixedLedgerClock(var now: Long = CREATED_AT) : LedgerClock {
        override fun nowEpochMillis(): Long = now
    }

    private lateinit var context: Context
    private lateinit var clock: FixedLedgerClock

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(LedgerDatabase.NAME)
        clock = FixedLedgerClock()
    }

    private fun open(): LedgerDatabase = LedgerDatabase.build(context)

    private fun repository(database: LedgerDatabase) =
        IntakeRepository(database = database, clock = clock)

    @Test
    fun `an exact callback replay stores one draft and reports the same id`() = runTest {
        val database = open()
        val query = WorkflowQueryService(database)
        try {
            val drafts = repository(database)
            val first = drafts.storePermitted(BANK_PACKAGE, FIRST_KEY, POSTED_AT, sample())
            val replay = drafts.storePermitted(BANK_PACKAGE, FIRST_KEY, POSTED_AT, sample())

            assertEquals(first, replay)
            assertEquals(1, WorkflowQueryService(database).draftCandidates(ALL_DRAFTS).items.size)
            assertEquals(POSTED_AT, WorkflowQueryService(database).draftCandidates(ALL_DRAFTS).items.single().postedAtEpochMillis)
        } finally {
            database.close()
        }
    }

    @Test
    fun `the same delivered post seen again with its body in another field stores nothing`() = runTest {
        val database = open()
        val query = WorkflowQueryService(database)
        try {
            val drafts = repository(database)
            val first = drafts.storePermitted(BANK_PACKAGE, FIRST_KEY, POSTED_AT, sample())
            // The same delivery — same package, same key, same post time — read again with the
            // body carried by the big-text field instead of the text field: which field the bank
            // uses is device evidence, and the same post stating the same evidence is not a
            // second transaction.
            val reread = drafts.storePermitted(
                BANK_PACKAGE,
                FIRST_KEY,
                POSTED_AT,
                sampleInBigText(),
            )

            assertEquals(first, reread)
            assertEquals(1, WorkflowQueryService(database).draftCandidates(ALL_DRAFTS).items.size)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a delivery without a notification key still stores its own post once`() = runTest {
        val database = open()
        val query = WorkflowQueryService(database)
        try {
            val drafts = repository(database)
            val first = drafts.storePermitted(BANK_PACKAGE, null, POSTED_AT, sample())
            val reread = drafts.storePermitted(BANK_PACKAGE, null, POSTED_AT, sample())

            assertEquals(first, reread)
            assertEquals(1, WorkflowQueryService(database).draftCandidates(ALL_DRAFTS).items.size)
        } finally {
            database.close()
        }
    }

    @Test
    fun `two identical statements under a reused key at different posts stay two candidates`() =
        runTest {
            val database = open()
            val query = WorkflowQueryService(database)
            try {
                val drafts = repository(database)
                // The user's sample carries only a debit amount, a stated minute, a masked memo,
                // and no transaction reference, so two real transactions inside one minute state
                // byte-identical permitted evidence. A bank that reuses one notification key posts
                // both through FIRST_KEY; the later post is a real delivery and must not be
                // discarded as a replay of the earlier one.
                val firstId = drafts.storePermitted(BANK_PACKAGE, FIRST_KEY, POSTED_AT, sample())
                val secondId = drafts.storePermitted(
                    BANK_PACKAGE,
                    FIRST_KEY,
                    POSTED_AT + UPDATE_DELAY,
                    sample(),
                )

                assertNotEquals(firstId, secondId)
                val stored = WorkflowQueryService(database).draftCandidates(ALL_DRAFTS).items.sortedBy { it.id }
                assertEquals(2, stored.size)
                assertEquals(50_000L, stored[0].amountVnd)
                assertEquals(50_000L, stored[1].amountVnd)
                assertEquals(IntakeReviewStatus.PENDING, stored[1].reviewStatus)
                // Identical evidence cannot prove the two are one payment, so the later delivery is
                // kept as its own candidate and flagged for the user instead of being dropped.
                assertNull(stored[0].possibleDuplicateOfDraftId)
                assertEquals(firstId, stored[1].possibleDuplicateOfDraftId)
            } finally {
                database.close()
            }
        }

    @Test
    fun `keyless deliveries of equal evidence at different posts are both kept`() = runTest {
        val database = open()
        val query = WorkflowQueryService(database)
        try {
            val drafts = repository(database)
            // Without a notification key the post time is the only thing distinguishing the two
            // deliveries, so a package that posts two equal alerts must not lose the second one.
            val firstId = drafts.storePermitted(BANK_PACKAGE, null, POSTED_AT, sample())
            val secondId = drafts.storePermitted(
                BANK_PACKAGE,
                null,
                POSTED_AT + UPDATE_DELAY,
                sample(),
            )

            assertNotEquals(firstId, secondId)
            val stored = WorkflowQueryService(database).draftCandidates(ALL_DRAFTS).items.sortedBy { it.id }
            assertEquals(2, stored.size)
            assertEquals(firstId, stored[1].possibleDuplicateOfDraftId)
        } finally {
            database.close()
        }
    }

    @Test
    fun `an unsupported payload replay stores nothing`() = runTest {
        val database = open()
        val query = WorkflowQueryService(database)
        try {
            val drafts = repository(database)
            val unsupported = PermittedNotificationContent(
                title = "Tin biến động số dư",
                text = "Your balance is 1,000,000 VND",
                bigText = null,
            )
            val first = drafts.storePermitted(BANK_PACKAGE, FIRST_KEY, POSTED_AT, unsupported)
            val replay = drafts.storePermitted(BANK_PACKAGE, FIRST_KEY, POSTED_AT, unsupported)

            assertEquals(first, replay)
            assertEquals(1, WorkflowQueryService(database).draftCandidates(ALL_DRAFTS).items.size)
            assertEquals(IntakeParseStatus.UNSUPPORTED_FORMAT, WorkflowQueryService(database).draftCandidates(ALL_DRAFTS).items.single().parseStatus)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a changed statement under a reused key is stored beside the first`() = runTest {
        val database = open()
        val query = WorkflowQueryService(database)
        try {
            val drafts = repository(database)
            val firstId = drafts.storePermitted(BANK_PACKAGE, FIRST_KEY, POSTED_AT, sample())
            val secondId = drafts.storePermitted(
                BANK_PACKAGE,
                FIRST_KEY,
                POSTED_AT + UPDATE_DELAY,
                sample(amount = "-60,000 VND"),
            )

            assertNotEquals(firstId, secondId)
            val stored = WorkflowQueryService(database).draftCandidates(ALL_DRAFTS).items.sortedBy { it.id }
            assertEquals(2, stored.size)
            // The first statement is untouched: no key-based overwrite, no lost draft.
            assertEquals(50_000L, stored[0].amountVnd)
            assertEquals(IntakeReviewStatus.PENDING, stored[0].reviewStatus)
            assertNull(stored[0].possibleDuplicateOfDraftId)
            // The later statement is its own pending draft, stating what it stated. Its evidence
            // differs from the earlier one, so nothing claims the two are one payment: key reuse
            // alone is not identity.
            assertEquals(60_000L, stored[1].amountVnd)
            assertEquals(IntakeDirection.DEBIT, stored[1].direction)
            assertEquals(IntakeReviewStatus.PENDING, stored[1].reviewStatus)
            assertNull(stored[1].possibleDuplicateOfDraftId)
        } finally {
            database.close()
        }
    }

    @Test
    fun `two equal value transactions under different deliveries both remain, flagged for review`() =
        runTest {
            val database = open()
            val query = WorkflowQueryService(database)
            try {
                val drafts = repository(database)
                val firstId = drafts.storePermitted(BANK_PACKAGE, FIRST_KEY, POSTED_AT, sample())
                val secondId = drafts.storePermitted(BANK_PACKAGE, SECOND_KEY, POSTED_AT, sample())

                assertNotEquals(firstId, secondId)
                val stored = WorkflowQueryService(database).draftCandidates(ALL_DRAFTS).items.sortedBy { it.id }
                assertEquals(2, stored.size)
                assertEquals(50_000L, stored[0].amountVnd)
                assertEquals(50_000L, stored[1].amountVnd)
                // Identical evidence under another delivery is uncertain identity: the pair is
                // kept and linked instead of one of the two being discarded.
                assertNull(stored[0].possibleDuplicateOfDraftId)
                assertEquals(firstId, stored[1].possibleDuplicateOfDraftId)
                assertEquals(IntakeReviewStatus.PENDING, stored[1].reviewStatus)
            } finally {
                database.close()
            }
        }

    @Test
    fun `two equal value transactions with different descriptions stay separate and unflagged`() =
        runTest {
            val database = open()
            val query = WorkflowQueryService(database)
            try {
                val drafts = repository(database)
                // The same amount, bank minute, and account/balance lines on both deliveries, but
                // each message states a different description on its `ND:` line. That description
                // is part of the permitted evidence this identity digests, so the two are
                // different statements of the same value: both are kept, and neither is flagged
                // as a possible duplicate of the other.
                val firstId = drafts.storePermitted(
                    BANK_PACKAGE,
                    FIRST_KEY,
                    POSTED_AT,
                    sample(memo = "REF#101"),
                )
                val secondId = drafts.storePermitted(
                    BANK_PACKAGE,
                    FIRST_KEY,
                    POSTED_AT + UPDATE_DELAY,
                    sample(memo = "REF#102"),
                )

                assertNotEquals(firstId, secondId)
                val stored = WorkflowQueryService(database).draftCandidates(ALL_DRAFTS).items.sortedBy { it.id }
                assertEquals(2, stored.size)
                assertEquals(listOf(50_000L, 50_000L), stored.map { it.amountVnd })
                assertEquals(listOf("2026-10-16", "2026-10-16"), stored.map { it.statedDate })
                assertEquals(listOf("07:38", "07:38"), stored.map { it.statedTime })
                assertEquals(listOf("REF#101", "REF#102"), stored.map { it.memo })
                assertEquals(
                    listOf(IntakeFieldState.PRESENT, IntakeFieldState.PRESENT),
                    stored.map { it.memoState },
                )
                assertTrue(stored.all { it.possibleDuplicateOfDraftId == null })
                assertTrue(stored.all { it.reviewStatus == IntakeReviewStatus.PENDING })
                assertTrue(stored.all { it.confirmedTransactionId == null })

                // Each is independently confirmable, and each becomes its own ledger record
                // stating its own description: two transactions of equal value, not one.
                val review = reviewCommands(database, clock)
                val ledger = LedgerRepository(
                    categoryDao = database.categoryDao(),
                    transactionDao = database.transactionDao(),
                    rememberedCategoryRuleDao = database.rememberedCategoryRuleDao(),
                    captureMessageDao = database.captureMessageDao(),
                    clock = clock,
                )
                val firstReview = review.confirmDraft(firstId, 0, confirmableEdits("REF#101"))
                val secondReview = review.confirmDraft(secondId, 0, confirmableEdits("REF#102"))

                assertTrue(firstReview is BankDraftReviewResult.Confirmed)
                assertTrue(secondReview is BankDraftReviewResult.Confirmed)
                val transactions = query.ledgerRecords(ALL_LEDGER_RECORDS).items.sortedBy { it.id }
                assertEquals(2, transactions.size)
                assertEquals(listOf("REF#101", "REF#102"), transactions.map { it.description })
                assertEquals(listOf(50_000L, 50_000L), transactions.map { it.amountVnd })
            } finally {
                database.close()
            }
        }

    @Test
    fun `a third identical statement points at the earliest one`() = runTest {
        val database = open()
        val query = WorkflowQueryService(database)
        try {
            val drafts = repository(database)
            val firstId = drafts.storePermitted(BANK_PACKAGE, FIRST_KEY, POSTED_AT, sample())
            drafts.storePermitted(BANK_PACKAGE, SECOND_KEY, POSTED_AT, sample())
            drafts.storePermitted(BANK_PACKAGE, THIRD_KEY, POSTED_AT, sample())

            val stored = WorkflowQueryService(database).draftCandidates(ALL_DRAFTS).items.sortedBy { it.id }
            assertEquals(3, stored.size)
            assertNull(stored[0].possibleDuplicateOfDraftId)
            assertEquals(firstId, stored[1].possibleDuplicateOfDraftId)
            assertEquals(firstId, stored[2].possibleDuplicateOfDraftId)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a confirmed draft keeps its review state and ledger link through a replay`() = runTest {
        val database = open()
        val query = WorkflowQueryService(database)
        try {
            val drafts = repository(database)
            val draftId = drafts.storePermitted(BANK_PACKAGE, FIRST_KEY, POSTED_AT, sample())
            val ledger = LedgerRepository(
                categoryDao = database.categoryDao(),
                transactionDao = database.transactionDao(),
                rememberedCategoryRuleDao = database.rememberedCategoryRuleDao(),
                captureMessageDao = database.captureMessageDao(),
                clock = clock,
            )
            val confirmed = ledger.addTransaction(
                TransactionInput(
                    type = TransactionType.EXPENSE,
                    amountVnd = 50_000L,
                    occurredAtEpochMillis = STATED_MOMENT_MILLIS,
                    description = "VietinBank debit",
                    source = RecordSource.BANK_NOTIFICATION,
                ),
            )
            setReviewState(database, draftId, IntakeReviewStatus.CONFIRMED, confirmed)

            val replay = drafts.storePermitted(BANK_PACKAGE, FIRST_KEY, POSTED_AT, sample())

            assertEquals(draftId, replay)
            val stored = WorkflowQueryService(database).draftCandidates(ALL_DRAFTS).items.single()
            assertEquals(IntakeReviewStatus.CONFIRMED, stored.reviewStatus)
            assertEquals(confirmed, stored.confirmedTransactionId)
            assertEquals(1, query.ledgerRecords(ALL_LEDGER_RECORDS).items.size)
            assertEquals(confirmed, query.findLedgerRecord(confirmed)!!.id)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a dismissed draft stays dismissed through a replay`() = runTest {
        val database = open()
        val query = WorkflowQueryService(database)
        try {
            val drafts = repository(database)
            val draftId = drafts.storePermitted(BANK_PACKAGE, FIRST_KEY, POSTED_AT, sample())
            setReviewState(database, draftId, IntakeReviewStatus.DISMISSED, null)

            val replay = drafts.storePermitted(BANK_PACKAGE, FIRST_KEY, POSTED_AT, sample())

            assertEquals(draftId, replay)
            val stored = WorkflowQueryService(database).draftCandidates(ALL_DRAFTS).items.single()
            assertEquals(IntakeReviewStatus.DISMISSED, stored.reviewStatus)
            assertNull(stored.confirmedTransactionId)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a reused notification key never rewrites a reviewed draft`() = runTest {
        val database = open()
        val query = WorkflowQueryService(database)
        try {
            val drafts = repository(database)
            val draftId = drafts.storePermitted(BANK_PACKAGE, FIRST_KEY, POSTED_AT, sample())
            val ledger = LedgerRepository(
                categoryDao = database.categoryDao(),
                transactionDao = database.transactionDao(),
                rememberedCategoryRuleDao = database.rememberedCategoryRuleDao(),
                captureMessageDao = database.captureMessageDao(),
                clock = clock,
            )
            val confirmed = ledger.addTransaction(
                TransactionInput(
                    type = TransactionType.EXPENSE,
                    amountVnd = 50_000L,
                    occurredAtEpochMillis = STATED_MOMENT_MILLIS,
                    description = "VietinBank debit",
                    source = RecordSource.BANK_NOTIFICATION,
                ),
            )
            setReviewState(database, draftId, IntakeReviewStatus.CONFIRMED, confirmed)

            // A bank that reuses one notification key sends the later transaction through a new
            // post under that key; it must not overwrite the statement the user already reviewed.
            val later = drafts.storePermitted(
                BANK_PACKAGE,
                FIRST_KEY,
                POSTED_AT + UPDATE_DELAY,
                sample(amount = "-60,000 VND"),
            )

            assertNotEquals(draftId, later)
            val stored = WorkflowQueryService(database).draftCandidates(ALL_DRAFTS).items.sortedBy { it.id }
            assertEquals(2, stored.size)
            assertEquals(IntakeReviewStatus.CONFIRMED, stored[0].reviewStatus)
            assertEquals(confirmed, stored[0].confirmedTransactionId)
            assertEquals(50_000L, stored[0].amountVnd)
            assertEquals(IntakeReviewStatus.PENDING, stored[1].reviewStatus)
            assertEquals(60_000L, stored[1].amountVnd)
            assertEquals(1, query.ledgerRecords(ALL_LEDGER_RECORDS).items.size)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a later identical post under a reviewed key is a new candidate, never a reopened row`() =
        runTest {
            val database = open()
            val query = WorkflowQueryService(database)
            try {
                val drafts = repository(database)
                val draftId = drafts.storePermitted(BANK_PACKAGE, FIRST_KEY, POSTED_AT, sample())
                val ledger = LedgerRepository(
                    categoryDao = database.categoryDao(),
                    rememberedCategoryRuleDao = database.rememberedCategoryRuleDao(),
                    captureMessageDao = database.captureMessageDao(),
                    transactionDao = database.transactionDao(),
                    clock = clock,
                )
                val confirmed = ledger.addTransaction(
                    TransactionInput(
                        type = TransactionType.EXPENSE,
                        amountVnd = 50_000L,
                        occurredAtEpochMillis = STATED_MOMENT_MILLIS,
                        description = "VietinBank debit",
                        source = RecordSource.BANK_NOTIFICATION,
                    ),
                )
                setReviewState(database, draftId, IntakeReviewStatus.CONFIRMED, confirmed)

                // The same reused key posts a later transaction whose permitted evidence is
                // byte-identical to the reviewed one. The reviewed row keeps its terminal state and
                // its ledger link, and the later delivery becomes its own pending candidate flagged
                // against it — neither dropped as a replay nor merged into the confirmed record.
                val laterId = drafts.storePermitted(
                    BANK_PACKAGE,
                    FIRST_KEY,
                    POSTED_AT + UPDATE_DELAY,
                    sample(),
                )

                assertNotEquals(draftId, laterId)
                val stored = WorkflowQueryService(database).draftCandidates(ALL_DRAFTS).items.sortedBy { it.id }
                assertEquals(2, stored.size)
                assertEquals(IntakeReviewStatus.CONFIRMED, stored[0].reviewStatus)
                assertEquals(confirmed, stored[0].confirmedTransactionId)
                assertEquals(50_000L, stored[0].amountVnd)
                assertNull(stored[0].possibleDuplicateOfDraftId)
                assertEquals(IntakeReviewStatus.PENDING, stored[1].reviewStatus)
                assertNull(stored[1].confirmedTransactionId)
                assertEquals(draftId, stored[1].possibleDuplicateOfDraftId)
                assertEquals(1, query.ledgerRecords(ALL_LEDGER_RECORDS).items.size)
            } finally {
                database.close()
            }
        }

    @Test
    fun `a restart neither repeats the same post nor drops a later one`() = runTest {
        val first = open()
        val draftId: Long
        try {
            draftId = repository(first).storePermitted(BANK_PACKAGE, FIRST_KEY, POSTED_AT, sample())
        } finally {
            first.close()
        }

        val restarted = open()
        try {
            // The platform re-delivers the same post — same key, same post time — to the new
            // process, which is the same delivery and stores nothing.
            val replay = repository(restarted).storePermitted(
                BANK_PACKAGE,
                FIRST_KEY,
                POSTED_AT,
                sample(),
            )

            assertEquals(draftId, replay)
            assertEquals(1, WorkflowQueryService(restarted).draftCandidates(ALL_DRAFTS).items.size)

            // A later post under the reused key is a different delivery, and it survives the
            // restart as its own flagged candidate instead of being deduplicated away.
            val laterId = repository(restarted).storePermitted(
                BANK_PACKAGE,
                FIRST_KEY,
                POSTED_AT + UPDATE_DELAY,
                sample(),
            )

            assertNotEquals(draftId, laterId)
            val stored = WorkflowQueryService(restarted).draftCandidates(ALL_DRAFTS).items.sortedBy { it.id }
            assertEquals(2, stored.size)
            assertEquals(draftId, stored[1].possibleDuplicateOfDraftId)
        } finally {
            restarted.close()
        }
    }

    @Test
    fun `the delivery and evidence identity is enforced by the table, not only by a lookup`() =
        runTest {
            val database = open()
            val query = WorkflowQueryService(database)
            try {
                val drafts = repository(database)
                drafts.storePermitted(BANK_PACKAGE, FIRST_KEY, POSTED_AT, sample())
                val stored = WorkflowQueryService(database).draftCandidates(ALL_DRAFTS).items.single()

                // A second row for the same delivery and evidence is refused by the unique index
                // even when it bypasses the store lookup, which is what keeps concurrent callbacks
                // — or a crash between the lookup and the insert — from storing a duplicate.
                val refused = runCatching {
                    database.openHelper.writableDatabase.execSQL(
                        "INSERT INTO `pending_bank_drafts` (`id`, `deliveryId`, `sourcePackage`, " +
                            "`notificationKey`, `postedAtEpochMillis`, `bank`, `parseStatus`, " +
                            "`amountState`, `amountVnd`, `amountUnresolvedReason`, `direction`, " +
                            "`momentState`, `statedDate`, `statedTime`, `momentUnresolvedReason`, " +
                            "`memoState`, `memo`, `memoUnresolvedReason`, `evidenceDigest`, " +
                            "`reviewStatus`, `confirmedTransactionId`, " +
                            "`possibleDuplicateOfDraftId`, `createdAtEpochMillis`) SELECT " +
                            "$PARALLEL_ID, `deliveryId`, `sourcePackage`, `notificationKey`, " +
                            "`postedAtEpochMillis`, `bank`, `parseStatus`, `amountState`, " +
                            "`amountVnd`, `amountUnresolvedReason`, `direction`, `momentState`, " +
                            "`statedDate`, `statedTime`, `momentUnresolvedReason`, `memoState`, " +
                            "`memo`, `memoUnresolvedReason`, `evidenceDigest`, `reviewStatus`, " +
                            "`confirmedTransactionId`, `possibleDuplicateOfDraftId`, " +
                            "`createdAtEpochMillis` FROM `pending_bank_drafts` WHERE `id` = ?",
                        arrayOf<Any?>(stored.id),
                    )
                }

                assertTrue(refused.isFailure)
                assertEquals(1, WorkflowQueryService(database).draftCandidates(ALL_DRAFTS).items.size)
            } finally {
                database.close()
            }
        }

    @Test
    fun `concurrent callbacks for one delivery stay one draft`() = runTest {
        val database = open()
        val query = WorkflowQueryService(database)
        try {
            val drafts = repository(database)
            val ids = coroutineScopeOfStores(drafts, FIRST_KEY, sample())

            assertEquals(1, ids.toSet().size)
            assertEquals(1, WorkflowQueryService(database).draftCandidates(ALL_DRAFTS).items.size)
        } finally {
            database.close()
        }
    }

    @Test
    fun `concurrent deliveries stating identical evidence both remain and one is flagged`() = runTest {
        val database = open()
        val query = WorkflowQueryService(database)
        try {
            val drafts = repository(database)
            val ids = coroutineScopeOfStores(drafts, FIRST_KEY, sample()) +
                coroutineScopeOfStores(drafts, SECOND_KEY, sample())

            assertEquals(2, ids.toSet().size)
            val stored = WorkflowQueryService(database).draftCandidates(ALL_DRAFTS).items
            assertEquals(2, stored.size)
            assertEquals(1, stored.count { it.possibleDuplicateOfDraftId != null })
            assertEquals(
                stored.minByOrNull { it.id }!!.id,
                stored.single { it.possibleDuplicateOfDraftId != null }.possibleDuplicateOfDraftId,
            )
        } finally {
            database.close()
        }
    }

    /** Stores the same delivery [CONCURRENT_STORES] times at once, as replayed callbacks arrive. */
    private suspend fun coroutineScopeOfStores(
        drafts: IntakeRepository,
        notificationKey: String,
        content: PermittedNotificationContent,
    ) = coroutineScope {
        (1..CONCURRENT_STORES).map {
            async { drafts.storePermitted(BANK_PACKAGE, notificationKey, POSTED_AT, content) }
        }.awaitAll()
    }

    /**
     * Moves a stored draft to its terminal review state the way S3.6's confirmation will, at the
     * table level: this task adds no production status-transition method, so the test writes the
     * same two columns that transition writes.
     */
    private fun setReviewState(
        database: LedgerDatabase,
        draftId: Long,
        status: IntakeReviewStatus,
        transactionId: Long?,
    ) {
        database.openHelper.writableDatabase.execSQL(
            "UPDATE `pending_bank_drafts` SET `reviewStatus` = ?, `confirmedTransactionId` = ? " +
                "WHERE `id` = ?",
            arrayOf<Any?>(status.name, transactionId, draftId),
        )
    }

    /** The user's redacted VietinBank sample, kept verbatim except the stated amount and memo. */
    private fun sample(
        amount: String = "-50,000 VND",
        memo: String = MASKED_MEMO,
    ): PermittedNotificationContent = PermittedNotificationContent(
        title = SAMPLE_TITLE,
        text = sampleLines(amount, memo),
        bigText = null,
    )

    /** The same statement carried by the big-text field instead of the text field. */
    private fun sampleInBigText(): PermittedNotificationContent = PermittedNotificationContent(
        title = SAMPLE_TITLE,
        text = null,
        bigText = sampleLines("-50,000 VND", MASKED_MEMO),
    )

    private fun sampleLines(amount: String, memo: String = MASKED_MEMO): String = listOf(
        "VietinBank: 16/10/2026 07:38",
        "TK: Redacted",
        "GD: $amount",
        "SDC: xxx,000 VND",
        "ND: $memo",
    ).joinToString("\n")

    /** The explicit review values the inbox requires before a draft can be confirmed. */
    private fun confirmableEdits(memoText: String) = BankDraftReviewEdits(
        amountText = "50000",
        dateText = "2026-10-16",
        timeText = "07:38",
        memoText = memoText,
        type = TransactionType.EXPENSE,
        categoryId = null,
        categoryResolved = true,
    )

    private companion object {
        const val BANK_PACKAGE = "com.vietinbank.ipay"
        const val SAMPLE_TITLE = "Tin biến động số dư"

        /** The user's sample masks the description with dots; it is not a stated memo. */
        const val MASKED_MEMO = "........"
        const val FIRST_KEY = "0|com.vietinbank.ipay|1|sample|100"
        const val SECOND_KEY = "0|com.vietinbank.ipay|2|sample|100"
        const val THIRD_KEY = "0|com.vietinbank.ipay|3|sample|100"
        const val POSTED_AT = 1_760_000_000_000L
        const val UPDATE_DELAY = 60_000L
        const val CREATED_AT = 1_760_000_001_000L
        const val STATED_MOMENT_MILLIS = 1_760_000_000_000L
        const val CONCURRENT_STORES = 8

        /** An id no stored draft uses, so a duplicate row is refused by identity, not by the key. */
        const val PARALLEL_ID = 999L
    }
}
