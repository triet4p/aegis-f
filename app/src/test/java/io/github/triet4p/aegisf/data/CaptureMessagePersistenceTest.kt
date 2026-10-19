package io.github.triet4p.aegisf.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.triet4p.aegisf.notification.PermittedNotificationContent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureMessagePersistenceTest {
    private class FixedClock(var now: Long = CAPTURE_TIME) : LedgerClock {
        override fun nowEpochMillis(): Long = now
    }

    private lateinit var context: Context
    private lateinit var clock: FixedClock

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(LedgerDatabase.NAME)
        clock = FixedClock()
    }

    private fun open(): LedgerDatabase = LedgerDatabase.build(context)

    private fun captures(database: LedgerDatabase) = CaptureMessageRepository(
        database = database,
        clock = clock,
        timezoneId = { CAPTURE_ZONE },
    )

    @Test
    fun `quick capture edits use revisions and deletion leaves no retained text after restart`() = runTest {
        val first = open()
        val captureId = captures(first).saveQuickCapture(ORIGINAL_NOTE)
        val saved = first.captureMessageDao().findById(captureId)!!
        assertEquals(CaptureKind.USER_NOTE, saved.captureKind)
        assertEquals(CaptureSourceKind.USER_QUICK_CAPTURE, saved.sourceKind)
        assertEquals(ORIGINAL_NOTE, saved.bodyText)
        assertEquals(CAPTURE_TIME, saved.capturedAtEpochMillis)
        assertEquals(CAPTURE_ZONE, saved.captureTimezone)
        assertEquals(0, saved.revision)
        assertNull(saved.originRef)
        assertFalse(saved.tombstone)
        first.close()

        val reopened = open()
        try {
            val repository = captures(reopened)
            val restored = repository.findById(captureId)!!
            assertEquals(saved, restored)
            assertEquals(
                CaptureMessageWriteResult.Saved(1),
                repository.revise(captureId, expectedRevision = 0, bodyText = REVISED_NOTE),
            )
            assertEquals(
                CaptureMessageWriteResult.Changed,
                repository.revise(captureId, expectedRevision = 0, bodyText = "stale replacement"),
            )
            val revised = repository.findById(captureId)!!
            assertEquals(REVISED_NOTE, revised.bodyText)
            assertEquals(1, revised.revision)
            assertEquals(saved.sourceKind, revised.sourceKind)
            assertEquals(saved.sourceIdentity, revised.sourceIdentity)
            assertEquals(saved.capturedAtEpochMillis, revised.capturedAtEpochMillis)
            assertEquals(saved.captureTimezone, revised.captureTimezone)

            assertEquals(
                CaptureMessageWriteResult.Deleted(2),
                repository.delete(captureId, expectedRevision = 1),
            )
            val deleted = repository.findById(captureId)!!
            assertNull(deleted.bodyText)
            assertTrue(deleted.tombstone)
            assertEquals(2, deleted.revision)
            assertEquals(saved.sourceKind, deleted.sourceKind)
            assertEquals(saved.sourceIdentity, deleted.sourceIdentity)
            assertEquals(saved.capturedAtEpochMillis, deleted.capturedAtEpochMillis)
            assertEquals(saved.captureTimezone, deleted.captureTimezone)
            assertTrue(repository.activeCaptures().isEmpty())
            assertEquals(1, reopened.captureMessageDao().findAllIncludingTombstones().size)
            assertEquals(
                CaptureMessageWriteResult.AlreadyDeleted,
                repository.revise(captureId, expectedRevision = 2, bodyText = ORIGINAL_NOTE),
            )
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `bank intake retains only the parsed allowed memo as immutable source evidence`() = runTest {
        val first = open()
        val intake = IntakeRepository(first, clock)
        val allowedContent = PermittedNotificationContent(
            title = "Transaction notice",
            text = listOf(
                "VietinBank: 16/10/2026 07:38",
                "TK: account-redacted",
                "GD: -50,000 VND",
                "SDC: balance-redacted",
                "ND: $BANK_MEMO",
            ).joinToString("\n"),
            bigText = null,
        )
        val draftId = intake.storePermitted(
            sourcePackage = BANK_PACKAGE,
            notificationKey = DELIVERY_KEY,
            postedAtEpochMillis = POSTED_AT,
            content = allowedContent,
        )
        assertEquals(
            draftId,
            intake.storePermitted(
                sourcePackage = BANK_PACKAGE,
                notificationKey = DELIVERY_KEY,
                postedAtEpochMillis = POSTED_AT,
                content = allowedContent,
            ),
        )
        val evidence = first.captureMessageDao().findAllIncludingTombstones().single()
        assertEquals(CaptureKind.BANK_EVIDENCE_REF, evidence.captureKind)
        assertEquals(CaptureSourceKind.BANK_NOTIFICATION, evidence.sourceKind)
        assertEquals(
            bankEvidenceSourceIdentity(draftId),
            evidence.sourceIdentity,
        )
        assertEquals(BANK_MEMO, evidence.bodyText)
        assertEquals(POSTED_AT, evidence.capturedAtEpochMillis)
        assertNull(evidence.captureTimezone)
        assertEquals(0, evidence.revision)
        assertEquals("BANK_DRAFT:$draftId", evidence.originRef)
        assertFalse(evidence.sourceIdentity.contains(BANK_MEMO))
        assertEquals(listOf(BANK_MEMO), listOfNotNull(evidence.bodyText))

        val repository = captures(first)
        assertEquals(
            CaptureMessageWriteResult.ImmutableSource,
            repository.revise(evidence.id, 0, "annotation must be separate"),
        )
        assertEquals(
            CaptureMessageWriteResult.ImmutableSource,
            repository.delete(evidence.id, 0),
        )
        assertEquals(evidence, repository.findById(evidence.id))
        first.close()

        val reopened = open()
        try {
            assertEquals(evidence, reopened.captureMessageDao().findById(evidence.id))
            assertEquals(draftId, IntakeRepository(reopened, clock).drafts().single().id)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `unsupported bank content does not enter the capture source table`() = runTest {
        val database = open()
        try {
            val intake = IntakeRepository(database, clock)
            intake.storePermitted(
                sourcePackage = BANK_PACKAGE,
                notificationKey = DELIVERY_KEY,
                postedAtEpochMillis = POSTED_AT,
                content = PermittedNotificationContent(
                    title = "Balance alert",
                    text = "Balance-only content is not a supported transaction.",
                    bigText = null,
                ),
            )
            assertEquals(IntakeParseStatus.UNSUPPORTED_FORMAT, intake.drafts().single().parseStatus)
            assertTrue(database.captureMessageDao().findAllIncludingTombstones().isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun `manual annotations remain independent of confirmed ledger descriptions`() = runTest {
        val database = open()
        try {
            val ledger = LedgerRepository(
                categoryDao = database.categoryDao(),
                transactionDao = database.transactionDao(),
                rememberedCategoryRuleDao = database.rememberedCategoryRuleDao(),
                captureMessageDao = database.captureMessageDao(),
                clock = clock,
            )
            val transactionId = ledger.addTransaction(
                TransactionInput(
                    type = TransactionType.EXPENSE,
                    amountVnd = 45_000L,
                    occurredAtEpochMillis = POSTED_AT,
                    description = "Ledger description",
                ),
            )
            val repository = captures(database)
            val annotationId = repository.saveManualAnnotation(ORIGINAL_ANNOTATION)
            val originalAnnotation = repository.findById(annotationId)!!
            assertEquals(CaptureKind.MANUAL_ANNOTATION, originalAnnotation.captureKind)
            assertEquals(CaptureSourceKind.USER_ANNOTATION, originalAnnotation.sourceKind)
            assertEquals(ORIGINAL_ANNOTATION, originalAnnotation.bodyText)
            assertEquals(CAPTURE_TIME, originalAnnotation.capturedAtEpochMillis)
            assertEquals(CAPTURE_ZONE, originalAnnotation.captureTimezone)
            assertEquals(0, originalAnnotation.revision)
            assertEquals(
                CaptureMessageWriteResult.Saved(1),
                repository.revise(annotationId, 0, REVISED_ANNOTATION),
            )
            assertEquals(originalAnnotation.sourceIdentity, repository.findById(annotationId)!!.sourceIdentity)
            assertEquals("Ledger description", ledger.findTransaction(transactionId)!!.description)
            assertEquals(
                CaptureMessageWriteResult.Deleted(2),
                repository.delete(annotationId, 1),
            )
            assertNull(repository.findById(annotationId)!!.bodyText)
            assertEquals("Ledger description", ledger.findTransaction(transactionId)!!.description)
        } finally {
            database.close()
        }
    }

    @Test
    fun `failed bank evidence write rolls back the new draft and preserves the old source row`() = runTest {
        val database = open()
        try {
            val sourceDao = database.captureMessageDao()
            val priorSourceId = sourceDao.insert(
                CaptureMessageEntity(
                    captureKind = CaptureKind.BANK_EVIDENCE_REF,
                    sourceKind = CaptureSourceKind.BANK_NOTIFICATION,
                    sourceIdentity = bankEvidenceSourceIdentity(1L),
                    bodyText = "prior source",
                    capturedAtEpochMillis = POSTED_AT,
                    captureTimezone = null,
                    revision = 0,
                    originRef = "BANK_DRAFT:99",
                ),
            )
            val priorSource = sourceDao.findById(priorSourceId)!!
            val intake = IntakeRepository(database, clock)
            var failed = false
            try {
                intake.storePermitted(
                    sourcePackage = BANK_PACKAGE,
                    notificationKey = DELIVERY_KEY,
                    postedAtEpochMillis = POSTED_AT,
                    content = PermittedNotificationContent(
                        title = null,
                        text = listOf(
                            "VietinBank: 16/10/2026 07:38",
                            "GD: -50,000 VND",
                            "ND: $BANK_MEMO",
                        ).joinToString("\n"),
                        bigText = null,
                    ),
                )
            } catch (_: IllegalStateException) {
                failed = true
            }

            assertTrue(failed)
            assertTrue(intake.drafts().isEmpty())
            assertEquals(priorSource, sourceDao.findById(priorSourceId))
            assertEquals(1, sourceDao.findAllIncludingTombstones().size)
        } finally {
            database.close()
        }
    }

    private companion object {
        const val BANK_PACKAGE = "com.example.bank"
        const val DELIVERY_KEY = "notification-key-17"
        const val BANK_MEMO = "Groceries"
        const val CAPTURE_TIME = 1_797_000_000_000L
        const val CAPTURE_ZONE = "Asia/Ho_Chi_Minh"
        const val POSTED_AT = 1_797_000_123_000L
        const val ORIGINAL_NOTE = "Lunch at the market"
        const val REVISED_NOTE = "Lunch with a colleague"
        const val ORIGINAL_ANNOTATION = "Context kept separately"
        const val REVISED_ANNOTATION = "Updated context kept separately"
    }
}
