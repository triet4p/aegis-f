package io.github.triet4p.aegisf.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.triet4p.aegisf.notification.PermittedNotificationContent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The stored behavior of the effective context and the prediction snapshots.
 *
 * These run against the real Room file, so a composed context, a stored prediction, and a
 * stale state are observed the way the app observes them after a restart, not as a mocked
 * return value.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClassificationSnapshotTest {
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

    private fun events(database: LedgerDatabase) = EventContextRepository(database, clock)

    private fun snapshots(database: LedgerDatabase, modelRevision: String = CURRENT_MODEL_REVISION) =
        ClassificationSnapshotRepository(
            database = database,
            clock = clock,
            modelRevision = modelRevision,
        )

    private fun ledger(database: LedgerDatabase) = LedgerRepository(
        categoryDao = database.categoryDao(),
        transactionDao = database.transactionDao(),
        rememberedCategoryRuleDao = database.rememberedCategoryRuleDao(),
        captureMessageDao = database.captureMessageDao(),
        clock = clock,
    )

    @Test
    fun `two events of one note give two drafts their own context and never the whole note`() =
        runTest {
            val database = open()
            try {
                val captureId = captures(database).saveQuickCapture(NOTE_TEXT)
                val fuelEvent = storeEvent(database, captureId, "Fuel 50,000 at 15:00", "Fuel")
                val snackEvent = storeEvent(
                    database,
                    captureId,
                    "Circle K snack 20,000 at 15:15",
                    "Circle K snack",
                )
                val fuelDraft = storeDraft(database, "fuel-key", 50_000L)
                val snackDraft = storeDraft(database, "snack-key", 20_000L)
                assertTrue(
                    events(database).proposeLink(fuelEvent.id, fuelDraft) is
                        ContextLinkResult.Linked,
                )
                assertTrue(
                    events(database).proposeLink(snackEvent.id, snackDraft) is
                        ContextLinkResult.Linked,
                )

                val fuelContext = snapshots(database).effectiveContextFor(fuelDraft, BANK_MEMO)
                val snackContext = snapshots(database).effectiveContextFor(snackDraft, BANK_MEMO)
                assertNotNull(fuelContext)
                assertNotNull(snackContext)

                // Each draft carries only the reading of the event linked to it.
                assertEquals(listOf(fuelEvent.id), fuelContext!!.context.eventReadings.map { it.eventId })
                assertEquals(
                    listOf(snackEvent.id),
                    snackContext!!.context.eventReadings.map { it.eventId },
                )
                // The whole note is never copied into a draft.
                assertTrue(!fuelContext.context.eventReadings.any { it.text.contains("snack") })
                assertTrue(!snackContext.context.eventReadings.any { it.text.contains("Fuel") })
                assertEquals(BANK_MEMO, fuelContext.context.primaryText)
            } finally {
                database.close()
            }
        }

    @Test
    fun `a stored prediction keeps its dependencies and reads back as current`() = runTest {
        val database = open()
        try {
            val categoryId = ledger(database).addCategory("Food")
            val draftId = storeDraft(database, "fuel-key", 50_000L)
            val repository = snapshots(database)
            val effective = repository.effectiveContextFor(draftId, BANK_MEMO)!!
            val result = repository.recordPrediction(
                draftId = draftId,
                prediction = modelPrediction(effective.dependencies, categoryId),
                expected = effective.dependencies,
            )
            assertTrue(result is SnapshotWriteResult.Stored)

            val stored = repository.snapshotFor(draftId)
            assertNotNull(stored)
            assertNull(stored!!.staleReason)
            assertEquals(PredictionSource.MODEL, stored.source)
            assertEquals(categoryId, stored.suggestedCategoryId)
            assertEquals(listOf(categoryId), stored.candidateIds)
            assertEquals(1, stored.probabilities.size)
            assertNotNull(stored.unknownShare)
            assertNotNull(stored.confidence)
            assertEquals(
                effective.dependencies.contextRevision,
                repository.storedSnapshot(draftId)!!.contextRevision,
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun `re-reading one event invalidates only the drafts that event describes`() = runTest {
        val database = open()
        try {
            val captureId = captures(database).saveQuickCapture(NOTE_TEXT)
            val fuelEvent = storeEvent(database, captureId, "Fuel 50,000 at 15:00", "Fuel")
            val snackEvent = storeEvent(
                database,
                captureId,
                "Circle K snack 20,000 at 15:15",
                "Circle K snack",
            )
            val fuelDraft = storeDraft(database, "fuel-key", 50_000L)
            val snackDraft = storeDraft(database, "snack-key", 20_000L)
            events(database).proposeLink(fuelEvent.id, fuelDraft)
            events(database).proposeLink(snackEvent.id, snackDraft)

            val repository = snapshots(database)
            repository.recordPrediction(
                draftId = fuelDraft,
                prediction = modelPrediction(
                    repository.effectiveContextFor(fuelDraft, BANK_MEMO)!!.dependencies,
                ),
                expected = repository.effectiveContextFor(fuelDraft, BANK_MEMO)!!.dependencies,
            )
            repository.recordPrediction(
                draftId = snackDraft,
                prediction = modelPrediction(
                    repository.effectiveContextFor(snackDraft, BANK_MEMO)!!.dependencies,
                ),
                expected = repository.effectiveContextFor(snackDraft, BANK_MEMO)!!.dependencies,
            )

            // Re-read the fuel event only.
            val revised = events(database).recordEvent(
                captureId = captureId,
                expectedCaptureRevision = 0,
                interpretation = NoteEventInterpretation(
                    spanStart = fuelSpanStart(),
                    spanEnd = fuelSpanEnd(),
                    normalizedText = "Fuel 55,000 at 15:05",
                    amountVnd = 55_000L,
                    merchant = "Petrol",
                ),
                extractionVersion = EXTRACTION_VERSION,
            )
            assertTrue(revised is NoteEventWriteResult.Revised)

            assertEquals(
                PredictionStaleReason.CONTEXT_CHANGED,
                repository.snapshotFor(fuelDraft)!!.staleReason,
            )
            assertNull(repository.snapshotFor(snackDraft)!!.staleReason)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a late result cannot replace a newer context`() = runTest {
        val database = open()
        try {
            val captureId = captures(database).saveQuickCapture(NOTE_TEXT)
            val fuelEvent = storeEvent(database, captureId, "Fuel 50,000 at 15:00", "Fuel")
            val fuelDraft = storeDraft(database, "fuel-key", 50_000L)
            events(database).proposeLink(fuelEvent.id, fuelDraft)

            val repository = snapshots(database)
            val expected = repository.effectiveContextFor(fuelDraft, BANK_MEMO)!!.dependencies

            // The context moves on while the inference is running.
            events(database).recordEvent(
                captureId = captureId,
                expectedCaptureRevision = 0,
                interpretation = NoteEventInterpretation(
                    spanStart = fuelSpanStart(),
                    spanEnd = fuelSpanEnd(),
                    normalizedText = "Fuel 55,000 at 15:05",
                    amountVnd = 55_000L,
                    merchant = "Petrol",
                ),
                extractionVersion = EXTRACTION_VERSION,
            )

            val result = repository.recordPrediction(
                draftId = fuelDraft,
                prediction = modelPrediction(expected),
                expected = expected,
            )
            assertEquals(SnapshotWriteResult.Stale, result)
            assertNull(repository.snapshotFor(fuelDraft))
        } finally {
            database.close()
        }
    }

    @Test
    fun `the user's own category choice is preserved over a prediction`() = runTest {
        val database = open()
        try {
            val categoryId = ledger(database).addCategory("Food")
            val draftId = storeDraft(database, "fuel-key", 50_000L)
            val repository = snapshots(database)
            val effective = repository.effectiveContextFor(draftId, BANK_MEMO)!!
            repository.recordPrediction(
                draftId = draftId,
                prediction = modelPrediction(effective.dependencies, categoryId),
                expected = effective.dependencies,
            )

            // The user resolves the draft's category.
            val draft = database.pendingBankDraftDao().findById(draftId)!!
            val saved = BankDraftReviewRepository(
                draftDao = database.pendingBankDraftDao(),
                reviewDao = database.bankDraftReviewDao(),
                clock = clock,
            ).saveReview(
                draftId = draftId,
                expectedRevision = draft.reviewRevision,
                edits = BankDraftReviewEdits(
                    amountText = draft.amountVnd.toString(),
                    dateText = draft.statedDate.orEmpty(),
                    timeText = draft.statedTime.orEmpty(),
                    memoText = draft.memo.orEmpty(),
                    type = null,
                    categoryId = categoryId,
                    categoryResolved = true,
                ),
            )
            assertTrue("the review was not saved: $saved", saved is BankDraftReviewResult.Saved)

            val result = repository.recordPrediction(
                draftId = draftId,
                prediction = modelPrediction(effective.dependencies, categoryId),
                expected = effective.dependencies,
            )
            assertEquals(SnapshotWriteResult.UserSelectionPreserved, result)
            assertEquals(
                PredictionStaleReason.USER_CATEGORY_CHOSEN,
                repository.snapshotFor(draftId)!!.staleReason,
            )
            // The user's choice is still the draft's own category.
            val reviewed = database.pendingBankDraftDao().findById(draftId)!!
            assertEquals(categoryId, reviewed.reviewCategoryId)
            assertTrue(reviewed.reviewCategoryResolved)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a category, a rule, and a model change each make a stored prediction stale`() = runTest {
        val database = open()
        try {
            val draftId = storeDraft(database, "fuel-key", 50_000L)
            val repository = snapshots(database)
            val effective = repository.effectiveContextFor(draftId, BANK_MEMO)!!
            repository.recordPrediction(
                draftId = draftId,
                prediction = modelPrediction(effective.dependencies),
                expected = effective.dependencies,
            )
            assertNull(repository.snapshotFor(draftId)!!.staleReason)

            // A new category changes the candidate set the model would be offered.
            ledger(database).addCategory("Transport")
            assertEquals(
                PredictionStaleReason.CATEGORY_CHANGED,
                repository.snapshotFor(draftId)!!.staleReason,
            )
            // The verdict is stored, not only returned.
            assertEquals(
                PredictionStaleReason.CATEGORY_CHANGED.name,
                repository.storedSnapshot(draftId)!!.staleReason,
            )
        } finally {
            database.close()
        }

        // A changed rule set.
        val ruleDatabase = open()
        try {
            val categoryId = ledger(ruleDatabase).addCategory("Food")
            val draftId = storeDraft(ruleDatabase, "fuel-key", 50_000L)
            val repository = snapshots(ruleDatabase)
            val effective = repository.effectiveContextFor(draftId, BANK_MEMO)!!
            repository.recordPrediction(
                draftId = draftId,
                prediction = modelPrediction(effective.dependencies, categoryId),
                expected = effective.dependencies,
            )
            ledger(ruleDatabase).rememberCategoryForDescription(BANK_MEMO, categoryId)
            assertEquals(
                PredictionStaleReason.RULE_CHANGED,
                repository.snapshotFor(draftId)!!.staleReason,
            )
            assertEquals(
                PredictionStaleReason.RULE_CHANGED.name,
                repository.storedSnapshot(draftId)!!.staleReason,
            )
        } finally {
            ruleDatabase.close()
        }

        // A different model revision.
        val modelDatabase = open()
        try {
            val draftId = storeDraft(modelDatabase, "fuel-key", 50_000L)
            val stored = snapshots(modelDatabase)
            val effective = stored.effectiveContextFor(draftId, BANK_MEMO)!!
            stored.recordPrediction(
                draftId = draftId,
                prediction = modelPrediction(effective.dependencies),
                expected = effective.dependencies,
            )
            val other = snapshots(modelDatabase, modelRevision = "other-model-revision")
            assertEquals(
                PredictionStaleReason.MODEL_CHANGED,
                other.snapshotFor(draftId)!!.staleReason,
            )
        } finally {
            modelDatabase.close()
        }
    }

    @Test
    fun `a prediction and an invalidation never touch the bank's own fields`() = runTest {
        val database = open()
        try {
            val captureId = captures(database).saveQuickCapture(NOTE_TEXT)
            val fuelEvent = storeEvent(database, captureId, "Fuel 50,000 at 15:00", "Fuel")
            val fuelDraft = storeDraft(database, "fuel-key", 50_000L)
            events(database).proposeLink(fuelEvent.id, fuelDraft)

            val before = database.pendingBankDraftDao().findById(fuelDraft)!!
            val repository = snapshots(database)
            val effective = repository.effectiveContextFor(fuelDraft, BANK_MEMO)!!
            repository.recordPrediction(
                draftId = fuelDraft,
                prediction = modelPrediction(effective.dependencies),
                expected = effective.dependencies,
            )
            events(database).recordEvent(
                captureId = captureId,
                expectedCaptureRevision = 0,
                interpretation = NoteEventInterpretation(
                    spanStart = fuelSpanStart(),
                    spanEnd = fuelSpanEnd(),
                    normalizedText = "Fuel 55,000 at 15:05",
                    amountVnd = 55_000L,
                    merchant = "Petrol",
                ),
                extractionVersion = EXTRACTION_VERSION,
            )
            val after = database.pendingBankDraftDao().findById(fuelDraft)!!
            assertEquals(before.amountVnd, after.amountVnd)
            assertEquals(before.statedDate, after.statedDate)
            assertEquals(before.statedTime, after.statedTime)
            assertEquals(before.memo, after.memo)
            assertEquals(before.direction, after.direction)
            assertEquals(before.reviewCategoryId, after.reviewCategoryId)
            assertEquals(before.reviewRevision, after.reviewRevision)
            assertEquals(before.reviewStatus, after.reviewStatus)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a stale prediction stays stale across a restart`() = runTest {
        val database = open()
        val draftId = storeDraft(database, "fuel-key", 50_000L)
        val captureId = captures(database).saveQuickCapture(NOTE_TEXT)
        val fuelEvent = storeEvent(database, captureId, "Fuel 50,000 at 15:00", "Fuel")
        events(database).proposeLink(fuelEvent.id, draftId)
        val repository = snapshots(database)
        val effective = repository.effectiveContextFor(draftId, BANK_MEMO)!!
        repository.recordPrediction(
            draftId = draftId,
            prediction = modelPrediction(effective.dependencies),
            expected = effective.dependencies,
        )
        events(database).recordEvent(
            captureId = captureId,
            expectedCaptureRevision = 0,
            interpretation = NoteEventInterpretation(
                spanStart = fuelSpanStart(),
                spanEnd = fuelSpanEnd(),
                normalizedText = "Fuel 55,000 at 15:05",
                amountVnd = 55_000L,
                merchant = "Petrol",
            ),
            extractionVersion = EXTRACTION_VERSION,
        )
        assertNotNull(repository.snapshotFor(draftId)!!.staleReason)
        database.close()

        val reopened = open()
        try {
            val stored = snapshots(reopened).snapshotFor(draftId)
            assertNotNull(stored)
            assertEquals(PredictionStaleReason.CONTEXT_CHANGED, stored!!.staleReason)
            assertNotNull(snapshots(reopened).storedSnapshot(draftId))
        } finally {
            reopened.close()
        }
    }

    private fun modelPrediction(
        dependencies: PredictionDependencies,
        categoryId: Long = FOOD_CATEGORY_ID,
    ) = ClassificationPrediction(
        source = PredictionSource.MODEL,
        suggestedCategoryId = categoryId,
        candidateIds = listOf(categoryId),
        probabilities = listOf(0.75),
        unknownShare = 0.25,
        confidence = 0.5,
        effectiveInputDigest = "digest-${dependencies.contextRevision}",
    )

    private suspend fun storeEvent(
        database: LedgerDatabase,
        captureId: Long,
        span: String,
        normalizedText: String,
    ): NoteEventEntity {
        val start = NOTE_TEXT.indexOf(span)
        val result = events(database).recordEvent(
            captureId = captureId,
            expectedCaptureRevision = 0,
            interpretation = NoteEventInterpretation(
                spanStart = start,
                spanEnd = start + span.length,
                normalizedText = normalizedText,
                amountVnd = 50_000L,
                merchant = "Petrol",
            ),
            extractionVersion = EXTRACTION_VERSION,
        )
        assertTrue("the reading was refused: $result", result is NoteEventWriteResult.Stored)
        return events(database).findEvent((result as NoteEventWriteResult.Stored).eventId)!!
    }

    private suspend fun storeDraft(
        database: LedgerDatabase,
        key: String,
        amountVnd: Long,
    ): Long = IntakeRepository(database, clock).storePermitted(
        sourcePackage = BANK_PACKAGE,
        notificationKey = key,
        postedAtEpochMillis = POSTED_AT + key.length,
        content = PermittedNotificationContent(
            title = "Transaction notice",
            text = listOf(
                "VietinBank: 16/10/2026 07:38",
                "TK: account-redacted",
                "GD: -$amountVnd VND",
                "ND: $BANK_MEMO",
            ).joinToString("\n"),
            bigText = null,
        ),
    )

    private companion object {
        const val BANK_PACKAGE = "com.example.bank"
        const val BANK_MEMO = "Circle K store"
        const val CAPTURE_ZONE = "Asia/Bangkok"
        const val CAPTURE_TIME = 1_797_000_000_000L
        const val POSTED_AT = 1_797_000_000_000L
        const val EXTRACTION_VERSION = "extract-v1"
        const val FOOD_CATEGORY_ID = 1L
        const val NOTE_TEXT = "Fuel 50,000 at 15:00; Circle K snack 20,000 at 15:15"

        fun fuelSpanStart(): Int = NOTE_TEXT.indexOf("Fuel 50,000 at 15:00")

        fun fuelSpanEnd(): Int = fuelSpanStart() + "Fuel 50,000 at 15:00".length
    }
}
