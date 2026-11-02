package io.github.triet4p.aegisf.ui

import android.content.Context
import androidx.compose.runtime.saveable.SaverScope
import androidx.test.core.app.ApplicationProvider
import io.github.triet4p.aegisf.data.ClassificationSnapshotEntity
import io.github.triet4p.aegisf.data.IntakeDirection
import io.github.triet4p.aegisf.data.IntakeFieldState
import io.github.triet4p.aegisf.data.IntakeParseStatus
import io.github.triet4p.aegisf.data.LedgerDatabase
import io.github.triet4p.aegisf.data.PendingBankDraftEntity
import io.github.triet4p.aegisf.data.PredictionSource
import io.github.triet4p.aegisf.data.PredictionStaleReason
import io.github.triet4p.aegisf.data.ReviewQueueOrder
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.data.draftDeliveryId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The focus behavior of the review inbox: what the user sees stays what they were reading.
 *
 * The holder is exercised against the real database, so the assertions are about observable
 * behavior — a stored score that changed elsewhere does not reorder the shown page, an explicit
 * refresh applies it, a new order starts at the first page, a page that lost its rows steps back
 * instead of showing an empty page, and next-work advances to the oldest decision still waiting
 * without the app ever jumping on its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewQueueFocusTest {

    private lateinit var context: Context
    private lateinit var database: LedgerDatabase
    private lateinit var query: WorkflowQueryService

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(LedgerDatabase.NAME)
        database = LedgerDatabase.build(context)
        query = WorkflowQueryService(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a changed score does not move the page until the user refreshes`() =
        runTest {
            val fixture = seedQueue()
            val focus = ReviewQueueFocus(query, pageSize = 2)
            focus.refresh()

            assertEquals(0, focus.pageIndex)
            assertEquals(ReviewQueueOrder.UNCERTAINTY_FIRST, focus.order)
            assertEquals(
                listOf(fixture.missing, fixture.stale),
                focus.page!!.items.map { it.draft.id },
            )

            // A prediction arrives for the unclassified draft, and the least certain
            // suggestion becomes the sharpest one. Both change the order; neither may change
            // what is on screen while the user reads it.
            storeSnapshot(
                draftId = fixture.missing,
                decisionSource = PredictionSource.MODEL,
                confidence = 0.99,
            )
            setConfidence(fixture.suggestionUncertain, 0.99)

            assertEquals(0, focus.pageIndex)
            assertEquals(
                listOf(fixture.missing, fixture.stale),
                focus.page!!.items.map { it.draft.id },
            )

            // The explicit refresh is what applies them.
            focus.refresh()
            assertEquals(0, focus.pageIndex)
            assertEquals(
                listOf(fixture.stale, fixture.suggestionComparable),
                focus.page!!.items.map { it.draft.id },
            )
            assertTrue(focus.page!!.hasMore)
        }

    @Test
    fun `a new order starts at the first page`() =
        runTest {
            val fixture = seedQueue()
            val focus = ReviewQueueFocus(query, pageSize = 2)
            focus.refresh()
            focus.nextPage()
            assertEquals(1, focus.pageIndex)
            assertEquals(
                listOf(fixture.suggestionUncertain, fixture.suggestionComparable),
                focus.page!!.items.map { it.draft.id },
            )

            focus.setOrder(ReviewQueueOrder.CHRONOLOGICAL)
            assertEquals(0, focus.pageIndex)
            assertEquals(ReviewQueueOrder.CHRONOLOGICAL, focus.order)
            // The sharpest suggestion arrived first, so the chronological order shows it first
            // even though the review order put it last.
            assertEquals(
                listOf(fixture.suggestionCertain, fixture.missing),
                focus.page!!.items.map { it.draft.id },
            )

            focus.setOrder(ReviewQueueOrder.UNCERTAINTY_FIRST)
            assertEquals(0, focus.pageIndex)
            assertEquals(
                listOf(fixture.missing, fixture.stale),
                focus.page!!.items.map { it.draft.id },
            )
        }

    @Test
    fun `paging walks the order in whole pages and never appends past the last one`() =
        runTest {
            val fixture = seedQueue()
            val focus = ReviewQueueFocus(query, pageSize = 2)
            focus.refresh()

            focus.nextPage()
            assertEquals(1, focus.pageIndex)
            assertEquals(2, focus.page!!.offset)
            focus.nextPage()
            assertEquals(2, focus.pageIndex)
            assertEquals(
                listOf(fixture.suggestionCertain),
                focus.page!!.items.map { it.draft.id },
            )
            assertFalse(focus.page!!.hasMore)

            // At the last page there is nothing after it, so the action changes nothing.
            focus.nextPage()
            assertEquals(2, focus.pageIndex)

            focus.previousPage()
            assertEquals(1, focus.pageIndex)
            assertEquals(
                listOf(fixture.suggestionUncertain, fixture.suggestionComparable),
                focus.page!!.items.map { it.draft.id },
            )
        }

    @Test
    fun `a page that lost its rows steps back to the page before it`() =
        runTest {
            val fixture = seedQueue()
            val focus = ReviewQueueFocus(query, pageSize = 2)
            focus.refresh()
            focus.nextPage()
            focus.nextPage()
            assertEquals(2, focus.pageIndex)
            assertEquals(
                listOf(fixture.suggestionCertain),
                focus.page!!.items.map { it.draft.id },
            )

            // The page's drafts are reviewed elsewhere, so the page no longer holds a row.
            database.bankDraftReviewDao().dismiss(fixture.suggestionComparable)
            database.bankDraftReviewDao().dismiss(fixture.suggestionCertain)
            focus.refresh()

            assertEquals("the empty page stepped back", 1, focus.pageIndex)
            assertEquals(
                listOf(fixture.suggestionUncertain),
                focus.page!!.items.map { it.draft.id },
            )
        }

    @Test
    fun `next work skips the open draft and refreshes the list it returns to`() =
        runTest {
            val fixture = seedQueue()
            val focus = ReviewQueueFocus(query, pageSize = 2)
            focus.refresh()

            assertEquals(fixture.missing, focus.nextWork(openDraftId = null))
            assertEquals(fixture.stale, focus.nextWork(openDraftId = fixture.missing))

            // The oldest decision was reviewed elsewhere: next-work re-reads the queue and
            // the list it leaves behind already shows the change.
            database.bankDraftReviewDao().dismiss(fixture.missing)
            assertEquals(fixture.stale, focus.nextWork(openDraftId = null))
            assertEquals(
                listOf(fixture.stale, fixture.suggestionUncertain),
                focus.page!!.items.map { it.draft.id },
            )

            // With nothing else waiting, the action answers nothing and never reopens the
            // draft the user is on.
            database.bankDraftReviewDao().dismiss(fixture.stale)
            database.bankDraftReviewDao().dismiss(fixture.suggestionUncertain)
            database.bankDraftReviewDao().dismiss(fixture.suggestionComparable)
            assertNull(focus.nextWork(openDraftId = fixture.suggestionCertain))
        }

    @Test
    fun `the chosen order and page survive recreation and the rows are re-read`() =
        runTest {
            val fixture = seedQueue()
            val focus = ReviewQueueFocus(query, pageSize = 2)
            focus.setOrder(ReviewQueueOrder.CHRONOLOGICAL)
            focus.nextPage()
            assertEquals(1, focus.pageIndex)

            val saver = ReviewQueueFocus.saver(query)
            val saved = with(saver) { SaverScope { true }.save(focus) }
            val restored = saver.restore(saved!!)!!

            assertEquals(ReviewQueueOrder.CHRONOLOGICAL, restored.order)
            assertEquals(1, restored.pageIndex)
            assertNull("the rows are read, never restored", restored.page)

            restored.refresh()
            assertEquals(
                listOf(fixture.stale, fixture.suggestionUncertain),
                restored.page!!.items.map { it.draft.id },
            )
        }

    private suspend fun setConfidence(draftId: Long, confidence: Double) {
        val stored = database.classificationSnapshotDao().findForDraft(draftId)!!
        database.classificationSnapshotDao().replace(
            draftId = draftId,
            contextRevision = stored.contextRevision,
            categoryRevision = stored.categoryRevision,
            ruleRevision = stored.ruleRevision,
            modelRevision = stored.modelRevision,
            candidateSetVersion = stored.candidateSetVersion,
            decisionSource = stored.decisionSource,
            suggestedCategoryId = stored.suggestedCategoryId,
            candidateIds = stored.candidateIds,
            probabilities = stored.probabilities,
            unknownShare = stored.unknownShare,
            confidence = confidence,
            effectiveInputDigest = stored.effectiveInputDigest,
            classificationInputKind = stored.classificationInputKind,
            classificationInputIdentity = stored.classificationInputIdentity,
            translationSourceRevision = stored.translationSourceRevision,
            eventIds = stored.eventIds,
            predictionTime = stored.predictionTime,
        )
    }

    /**
     * Two drafts that are not comparable, then four comparable suggestions.
     *
     * The sharpest suggestion arrived first, so the review order and the arrival order differ
     * from the first page on — which is what makes a silent reorder visible in a test.
     */
    private suspend fun seedQueue(): FocusFixture {
        val missing = storeDraft(key = "missing", createdAt = BASE_INSTANT + 1)
        val stale = storeDraft(key = "stale", createdAt = BASE_INSTANT + 2)
        storeSnapshot(
            draftId = stale,
            decisionSource = PredictionSource.MODEL,
            confidence = 0.9336,
            staleReason = PredictionStaleReason.CATEGORY_CHANGED,
        )

        val suggestionCertain = storeDraft(key = "certain", createdAt = BASE_INSTANT)
        storeSnapshot(
            draftId = suggestionCertain,
            decisionSource = PredictionSource.MODEL,
            confidence = 0.9,
        )
        val suggestionUncertain = storeDraft(key = "uncertain", createdAt = BASE_INSTANT + 3)
        storeSnapshot(
            draftId = suggestionUncertain,
            decisionSource = PredictionSource.MODEL,
            confidence = 0.2,
        )
        val suggestionComparable = storeDraft(key = "comparable", createdAt = BASE_INSTANT + 4)
        storeSnapshot(
            draftId = suggestionComparable,
            decisionSource = PredictionSource.MODEL,
            confidence = 0.7,
        )

        return FocusFixture(
            missing = missing,
            stale = stale,
            suggestionUncertain = suggestionUncertain,
            suggestionComparable = suggestionComparable,
            suggestionCertain = suggestionCertain,
        )
    }

    private suspend fun storeDraft(key: String, createdAt: Long): Long =
        database.pendingBankDraftDao().store(
            PendingBankDraftEntity(
                deliveryId = draftDeliveryId(BANK_PACKAGE, key, createdAt),
                sourcePackage = BANK_PACKAGE,
                notificationKey = key,
                postedAtEpochMillis = createdAt,
                bank = "VIETINBANK",
                parseStatus = IntakeParseStatus.PARSED,
                amountState = IntakeFieldState.PRESENT,
                amountVnd = 50_000L,
                amountUnresolvedReason = null,
                direction = IntakeDirection.DEBIT,
                momentState = IntakeFieldState.PRESENT,
                statedDate = "2026-10-16",
                statedTime = "07:38",
                momentUnresolvedReason = null,
                memoState = IntakeFieldState.PRESENT,
                memo = key,
                memoUnresolvedReason = null,
                createdAtEpochMillis = createdAt,
            ),
        )

    private suspend fun storeSnapshot(
        draftId: Long,
        decisionSource: PredictionSource,
        confidence: Double,
        staleReason: PredictionStaleReason? = null,
    ) {
        database.classificationSnapshotDao().insert(
            ClassificationSnapshotEntity(
                draftId = draftId,
                contextRevision = "context-$draftId",
                categoryRevision = "categories",
                ruleRevision = "rules",
                modelRevision = MODEL_REVISION,
                candidateSetVersion = CANDIDATE_SET_VERSION,
                decisionSource = decisionSource.name,
                suggestedCategoryId = 1L,
                candidateIds = "1,2,3,4,5",
                probabilities = "0.6,0.1,0.1,0.1,0.1",
                unknownShare = 0.1,
                confidence = confidence,
                effectiveInputDigest = "input-$draftId",
                eventIds = "",
                predictionTime = BASE_INSTANT,
                staleReason = staleReason?.name,
            ),
        )
    }

    private class FocusFixture(
        val missing: Long,
        val stale: Long,
        val suggestionUncertain: Long,
        val suggestionComparable: Long,
        val suggestionCertain: Long,
    )

    private companion object {
        const val BANK_PACKAGE = "com.example.bank"
        const val BASE_INSTANT = 1_797_000_000_000L
        const val MODEL_REVISION = "laya-model-test"
        const val CANDIDATE_SET_VERSION = "laya-candidates-test"
    }
}
