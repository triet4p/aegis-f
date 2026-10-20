package io.github.triet4p.aegisf.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.triet4p.aegisf.notification.PermittedNotificationContent
import io.github.triet4p.aegisf.ui.ManualDateTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

/**
 * The S3.4 draft lifecycle on the real database file: what a permitted notification
 * persists, what an unsupported payload persists, and what never affects the ledger.
 *
 * Every case runs the production [IntakeRepository] against the production builder
 * [LedgerDatabase.build], so closing and reopening an instance is the same thing the app
 * does when its process is killed and restarted. The schema upgrade paths from the earlier
 * versions are covered separately by [PendingBankDraftMigrationTest]. The fixtures are the
 * user's own redacted VietinBank sample
 * (`artifacts/sprint-3/handoffs/vietinbank-notification-context.md`), kept verbatim:
 * the padded account line and the dotted memo are never extracted, and no account or
 * balance value is asserted anywhere.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingBankDraftPersistenceTest {

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

    private fun repository(database: LedgerDatabase) = IntakeRepository(
        database = database,
        clock = clock,
    )

    @Test
    fun `a permitted sample persists with unresolved fields and survives reopen`() = runTest {
        val first = open()
        val id = repository(first).storePermitted(
            sourcePackage = BANK_PACKAGE,
            notificationKey = "0|com.vietinbank.ipay|1|sample|100",
            postedAtEpochMillis = POSTED_AT,
            content = PermittedNotificationContent(title = SAMPLE_TITLE, text = sampleBody(), bigText = null),
        )
        val stored = WorkflowQueryService(first).draftCandidates(ALL_DRAFTS).items.single()
        assertEquals(id, stored.id)
        assertEquals(BANK_PACKAGE, stored.sourcePackage)
        assertEquals("VIETINBANK", stored.bank)
        assertEquals(IntakeParseStatus.PARSED, stored.parseStatus)
        assertEquals(IntakeFieldState.PRESENT, stored.amountState)
        assertEquals(50_000L, stored.amountVnd)
        assertNull(stored.amountUnresolvedReason)
        // A debit is stored as the bank's debit, never as a ledger expense.
        assertEquals(IntakeDirection.DEBIT, stored.direction)
        // The bank's wall clock is kept, with no invented instant or zone.
        assertEquals(IntakeFieldState.PRESENT, stored.momentState)
        assertEquals("2026-10-16", stored.statedDate)
        assertEquals("07:38", stored.statedTime)
        assertNull(stored.momentUnresolvedReason)
        // ND: ........ is masking, so the memo stays unresolved rather than the dots.
        assertEquals(IntakeFieldState.UNRESOLVED, stored.memoState)
        assertNull(stored.memo)
        assertEquals("REDACTED", stored.memoUnresolvedReason)
        assertEquals(IntakeReviewStatus.PENDING, stored.reviewStatus)
        assertNull(stored.confirmedTransactionId)
        first.close()

        val second = open()
        assertEquals(listOf(stored), WorkflowQueryService(second).draftCandidates(ALL_DRAFTS).items)
        second.close()
    }

    @Test
    fun `an unsupported payload persists as reviewable intake without finance`() = runTest {
        val database = open()
        val query = WorkflowQueryService(database)
        try {
            repository(database).storePermitted(
                sourcePackage = BANK_PACKAGE,
                notificationKey = null,
                postedAtEpochMillis = POSTED_AT,
                content = PermittedNotificationContent(
                    title = "Tin biến động số dư",
                    text = "Your balance is 1,000,000 VND",
                    bigText = null,
                ),
            )
            val stored = WorkflowQueryService(database).draftCandidates(ALL_DRAFTS).items.single()
            assertEquals(IntakeParseStatus.UNSUPPORTED_FORMAT, stored.parseStatus)
            assertEquals("UNSUPPORTED", stored.bank)
            assertEquals(IntakeFieldState.MISSING, stored.amountState)
            assertNull(stored.amountVnd)
            assertEquals(IntakeDirection.UNKNOWN, stored.direction)
            assertEquals(IntakeFieldState.MISSING, stored.momentState)
            assertNull(stored.statedDate)
            assertNull(stored.statedTime)
            assertEquals(IntakeFieldState.MISSING, stored.memoState)
            assertNull(stored.memo)
            assertEquals(IntakeReviewStatus.PENDING, stored.reviewStatus)
        } finally {
            database.close()
        }
    }

    @Test
    fun `pending drafts never enter month summaries`() = runTest {
        val zone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
        val monthStart = ManualDateTime.monthStartEpochMillis(
            ManualDateTime.toEpochMillis("2026-10-15", "12:00", zone),
            zone,
        )
        val monthEnd = ManualDateTime.nextMonthStartEpochMillis(monthStart, zone)
        val database = open()
        val query = WorkflowQueryService(database)
        try {
            val ledger = LedgerRepository(
                categoryDao = database.categoryDao(),
                transactionDao = database.transactionDao(),
                rememberedCategoryRuleDao = database.rememberedCategoryRuleDao(),
                captureMessageDao = database.captureMessageDao(),
                clock = clock,
            )
            ledger.addTransaction(
                TransactionInput(
                    type = TransactionType.EXPENSE,
                    amountVnd = 45_000L,
                    occurredAtEpochMillis = ManualDateTime.toEpochMillis("2026-10-15", "12:00", zone),
                    description = "Lunch",
                ),
            )
            repository(database).storePermitted(
                sourcePackage = BANK_PACKAGE,
                notificationKey = "0|com.vietinbank.ipay|2|sample|101",
                postedAtEpochMillis = POSTED_AT,
                content = PermittedNotificationContent(title = SAMPLE_TITLE, text = sampleBody(), bigText = null),
            )
            val summary = summarizeConfirmedSpending(query.ledgerRecords(ALL_LEDGER_RECORDS).items, monthStart, monthEnd)
            assertEquals(1, summary.recordCount)
            assertEquals(45_000L, summary.totalVnd)
        } finally {
            database.close()
        }
    }

    private companion object {
        const val BANK_PACKAGE = "com.vietinbank.ipay"
        const val SAMPLE_TITLE = "Tin biến động số dư"
        const val POSTED_AT = 1_760_000_000_000L
        const val CREATED_AT = 1_760_000_001_000L

        fun sampleBody(): String = listOf(
            "VietinBank: 16/10/2026 07:38",
            "TK: Redacted",
            "GD: -50,000 VND",
            "SDC: xxx,000 VND",
            "ND: ........",
        ).joinToString("\n")
    }
}
