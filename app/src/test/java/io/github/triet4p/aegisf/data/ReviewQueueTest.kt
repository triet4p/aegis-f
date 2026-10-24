package io.github.triet4p.aegisf.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The uncertainty-first review queue, read from the real database.
 *
 * The fixture is the policy's own boundary set (`docs/sprint-7-confidence-queue-policy.md` §9),
 * stored as real drafts, real context links and real prediction rows: an unresolved duplicate
 * with the sharpest score in the fixture, a proposed link over a sharp prediction, unresolved
 * bank data, an unsupported format, a draft no prediction ever reached, a stale prediction
 * whose score must not order anything, an unknown-topped prediction, a direct answer with no
 * score, a draft the user already categorised, four comparable fresh predictions — one of them
 * wrong — and one fresh prediction over a different evaluated set.
 *
 * The assertions are the properties a review surface depends on, not the statement that reads
 * them: the exact order, the reason each draft is where it is, that a page never skips or
 * repeats a draft, that the chronological override returns every draft by arrival, and that a
 * sharp score never outranks an unresolved decision.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewQueueTest {

    private class FixedClock(private val now: Long = BASE_INSTANT) : LedgerClock {
        override fun nowEpochMillis(): Long = now
    }

    private lateinit var context: Context
    private lateinit var database: LedgerDatabase
    private lateinit var query: WorkflowQueryService
    private lateinit var clock: FixedClock

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(LedgerDatabase.NAME)
        database = LedgerDatabase.build(context)
        query = WorkflowQueryService(database)
        clock = FixedClock()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `an unresolved decision outranks every score and a stale score orders nothing`() =
        runTest {
            val fixture = seedQueue()
            val order = pageOfAll(ReviewQueueQuery(bounds = QueryPageRequest(LIST_PAGE_SIZE)))
                .map { it.draft.id }

            assertEquals(
                listOf(
                    // Rank 0: unresolved link/data decisions, oldest first.
                    fixture.duplicateMember,
                    fixture.proposedLink,
                    fixture.unresolvedAmount,
                    fixture.unsupportedFormat,
                    // Rank 1: missing, stale, unknown, direct, user-chosen.
                    fixture.missing,
                    fixture.stale,
                    fixture.unknown,
                    fixture.direct,
                    fixture.userResolved,
                    // Rank 2: comparable fresh predictions, least certain first.
                    fixture.suggestionLow,
                    fixture.suggestionWrong,
                    fixture.suggestionTieEarlier,
                    fixture.suggestionTieLater,
                    // Rank 2: a different evaluated set is its own section, never interleaved.
                    fixture.otherGroupHigh,
                ),
                order,
            )
        }

    @Test
    fun `the scored-but-unresolved and scored-but-stale drafts stay where their group puts them`() =
        runTest {
            val fixture = seedQueue()
            val entries = pageOfAll(
                ReviewQueueQuery(bounds = QueryPageRequest(LIST_PAGE_SIZE)),
            ).associateBy { it.draft.id }

            // The sharpest prediction in the fixture is on a possible-duplicate draft.
            val duplicate = entries.getValue(fixture.duplicateMember)
            assertEquals(ReviewQueueGroup.NEEDS_DECISION, duplicate.group)
            assertEquals(
                listOf(ReviewQueueConflict.POSSIBLE_DUPLICATE),
                duplicate.conflicts,
            )
            assertNull("a conflicted draft carries no ordering score", duplicate.confidence)
            assertNull(duplicate.comparabilityKey)

            // A sharp prediction over an undecided link is still an unresolved association.
            val proposed = entries.getValue(fixture.proposedLink)
            assertEquals(ReviewQueueGroup.NEEDS_DECISION, proposed.group)
            assertEquals(listOf(ReviewQueueConflict.PROPOSED_LINK), proposed.conflicts)
            assertNull(proposed.confidence)

            val unresolved = entries.getValue(fixture.unresolvedAmount)
            assertEquals(listOf(ReviewQueueConflict.UNRESOLVED_BANK_DATA), unresolved.conflicts)
            val unsupported = entries.getValue(fixture.unsupportedFormat)
            assertEquals(listOf(ReviewQueueConflict.UNRESOLVED_BANK_DATA), unsupported.conflicts)

            // A stored 0.9336 on a stale prediction is reported, but orders nothing.
            val stale = entries.getValue(fixture.stale)
            assertEquals(ReviewQueueGroup.NOT_COMPARABLE, stale.group)
            assertEquals(ReviewQueueSubkind.STALE, stale.subkind)
            assertEquals(PredictionStaleReason.CATEGORY_CHANGED, stale.staleReason)
            assertNull(stale.confidence)
            assertNull(stale.comparabilityKey)

            // An unknown-topped prediction keeps its rows and promotes nothing.
            val unknown = entries.getValue(fixture.unknown)
            assertEquals(ReviewQueueSubkind.UNKNOWN, unknown.subkind)
            assertNull(unknown.confidence)

            // A direct answer has no score to compare.
            val direct = entries.getValue(fixture.direct)
            assertEquals(ReviewQueueSubkind.DIRECT, direct.subkind)
            assertNull(direct.confidence)
            assertTrue(direct.candidateIds.isEmpty())

            // The user's choice is its own subkind even though it is also what made the
            // stored prediction stale.
            val chosen = entries.getValue(fixture.userResolved)
            assertEquals(ReviewQueueSubkind.USER_RESOLVED, chosen.subkind)

            // The dismissed earlier partner of the duplicate family is not queue work.
            assertNull(entries[fixture.duplicateEarlierDismissed])
        }

    @Test
    fun `comparable predictions of one evaluated set are ordered by confidence and never across sets`() =
        runTest {
            val fixture = seedQueue()
            val suggestions = pageOfAll(
                ReviewQueueQuery(bounds = QueryPageRequest(LIST_PAGE_SIZE)),
            ).filter { it.group == ReviewQueueGroup.SUGGESTION }

            val firstGroup = suggestions.filter { it.draft.id != fixture.otherGroupHigh }
            assertEquals(
                listOf(
                    fixture.suggestionLow,
                    fixture.suggestionWrong,
                    fixture.suggestionTieEarlier,
                    fixture.suggestionTieLater,
                ),
                firstGroup.map { it.draft.id },
            )
            assertEquals(
                listOf(0.5638, 0.6976, 0.801, 0.801),
                firstGroup.map { it.confidence!! },
            )
            // One section is one evaluated set, in evaluated order.
            assertEquals(1, firstGroup.map { it.comparabilityKey }.distinct().size)
            assertEquals(5, firstGroup.first().candidateIds.size)

            // A sharper prediction over another evaluated set is not interleaved into the
            // first set's section; it sorts as its own section after it.
            val other = suggestions.last()
            assertEquals(fixture.otherGroupHigh, other.draft.id)
            assertEquals(0.9526, other.confidence ?: -1.0, 0.0)
            assertEquals(11, other.candidateIds.size)
            assertTrue(
                "the sections must be different comparability groups",
                other.comparabilityKey != firstGroup.first().comparabilityKey,
            )
        }

    @Test
    fun `bounded paging reaches every draft exactly once in the same order`() =
        runTest {
            seedQueue()
            val expected = pageOfAll(
                ReviewQueueQuery(bounds = QueryPageRequest(LIST_PAGE_SIZE)),
            ).map { it.draft.id }

            val collected = mutableListOf<Long>()
            var offset = 0
            var pages = 0
            while (true) {
                val page = query.reviewQueue(
                    ReviewQueueQuery(bounds = QueryPageRequest(3, offset)),
                )
                pages += 1
                collected += page.items.map { it.draft.id }
                assertTrue("a page of three never returns more than three", page.items.size <= 3)
                if (!page.hasMore) break
                offset += page.items.size
            }

            assertEquals(14, expected.size)
            assertEquals("the page walk repeats or splits no row", expected, collected)
            assertEquals(5, pages)
        }

    @Test
    fun `the chronological override reads every pending draft by arrival`() =
        runTest {
            val fixture = seedQueue()
            val chronological = pageOfAll(
                ReviewQueueQuery(
                    order = ReviewQueueOrder.CHRONOLOGICAL,
                    bounds = QueryPageRequest(LIST_PAGE_SIZE),
                ),
            ).map { it.draft.id }

            assertEquals(
                listOf(
                    fixture.duplicateMember,
                    fixture.proposedLink,
                    fixture.unresolvedAmount,
                    fixture.unsupportedFormat,
                    // A sharp prediction that arrives early is reachable here, near the front,
                    // even though the review order puts its section last.
                    fixture.otherGroupHigh,
                    fixture.missing,
                    fixture.stale,
                    fixture.unknown,
                    fixture.direct,
                    fixture.userResolved,
                    fixture.suggestionLow,
                    fixture.suggestionWrong,
                    fixture.suggestionTieEarlier,
                    fixture.suggestionTieLater,
                ),
                chronological,
            )
            assertEquals(
                "both orders read the same drafts",
                pageOfAll(ReviewQueueQuery(bounds = QueryPageRequest(LIST_PAGE_SIZE)))
                    .map { it.draft.id }
                    .toSet(),
                chronological.toSet(),
            )
        }

    @Test
    fun `next work leaves the open draft out and answers the oldest decision still waiting`() =
        runTest {
            val fixture = seedQueue()
            val first = query.reviewQueue(
                ReviewQueueQuery(bounds = QueryPageRequest(1)),
            ).items.single()
            assertEquals(fixture.duplicateMember, first.draft.id)

            val afterOpen = query.reviewQueue(
                ReviewQueueQuery(excludingDraftId = first.draft.id, bounds = QueryPageRequest(1)),
            ).items.single()
            assertEquals(fixture.proposedLink, afterOpen.draft.id)
        }

    /**
     * The policy's boundary fixture, stored through the app's own draft and link paths.
     *
     * Ages increase with the order the drafts arrived in, so the chronological override is
     * the arrival order and every group's own oldest work is unambiguous.
     */
    private suspend fun seedQueue(): QueueFixture {
        val categories = (1..16).map { index ->
            database.categoryDao().insert(
                CategoryEntity(
                    name = "Category $index",
                    createdAtEpochMillis = BASE_INSTANT,
                    updatedAtEpochMillis = BASE_INSTANT,
                ),
            )
        }
        val firstSet = categories.take(5)
        val secondSet = categories.drop(5)

        // A real duplicate family: the same permitted evidence under a different delivery is
        // an uncertain identity, and the earlier partner is dismissed so only the later
        // member is still waiting. The queue must still see the family.
        val duplicateEarlier = storeDraft(
            key = "duplicate-earlier",
            createdAt = BASE_INSTANT,
            memo = "Same evidence under two deliveries",
            amountVnd = 45_000L,
        )
        val duplicateMember = storeDraft(
            key = "duplicate-member",
            createdAt = BASE_INSTANT + 1,
            memo = "Same evidence under two deliveries",
            amountVnd = 45_000L,
        )
        assertEquals(
            duplicateEarlier,
            database.pendingBankDraftDao().findById(duplicateMember)!!.possibleDuplicateOfDraftId,
        )
        assertEquals(BankDraftReviewResult.Dismissed, database.bankDraftReviewDao().dismiss(duplicateEarlier))
        storeSnapshot(
            draftId = duplicateMember,
            source = PredictionSource.MODEL,
            confidence = 0.9526,
            candidateIds = firstSet,
        )

        // A real proposed link: the reading is attached but undecided. The prediction is
        // written afterwards, the way a re-classification of the linked context would store
        // one, and its sharpness must not move the association out of the first group.
        val proposedLink = storeDraft(
            key = "proposed-link",
            createdAt = BASE_INSTANT + 2,
            amountVnd = 60_000L,
        )
        val captureId = CaptureMessageRepository(
            database = database,
            clock = clock,
            timezoneId = { "Asia/Bangkok" },
        ).saveQuickCapture("Lunch 60,000 at 12:00")
        val eventWrite = EventContextRepository(database, clock).recordEvent(
            captureId = captureId,
            expectedCaptureRevision = 0,
            interpretation = NoteEventInterpretation(
                spanStart = 0,
                spanEnd = 5,
                normalizedText = "Lunch",
                amountVnd = 60_000L,
            ),
            extractionVersion = "extract-v1",
        )
        val eventId = (eventWrite as NoteEventWriteResult.Stored).eventId
        EventContextRepository(database, clock).proposeLink(eventId, proposedLink)
        storeSnapshot(
            draftId = proposedLink,
            source = PredictionSource.MODEL,
            confidence = 0.99,
            candidateIds = firstSet,
        )

        val unresolvedAmount = storeDraft(
            key = "unresolved-amount",
            createdAt = BASE_INSTANT + 3,
            amountState = IntakeFieldState.UNRESOLVED,
            amountVnd = null,
        )
        val unsupportedFormat = storeDraft(
            key = "unsupported-format",
            createdAt = BASE_INSTANT + 4,
            parseStatus = IntakeParseStatus.UNSUPPORTED_FORMAT,
            amountState = IntakeFieldState.MISSING,
            amountVnd = null,
            momentState = IntakeFieldState.MISSING,
        )

        // A sharp prediction that arrived early: its own evaluated set sorts after the first
        // one in review, and the chronological override still shows it near the front.
        val otherGroupHigh = storeDraft(
            key = "other-group-high",
            createdAt = BASE_INSTANT + 5,
            amountVnd = 77_000L,
        )
        storeSnapshot(
            draftId = otherGroupHigh,
            source = PredictionSource.MODEL,
            confidence = 0.9526,
            candidateIds = secondSet,
        )

        val missing = storeDraft(key = "missing", createdAt = BASE_INSTANT + 6, amountVnd = 10_000L)

        val stale = storeDraft(key = "stale", createdAt = BASE_INSTANT + 7, amountVnd = 20_000L)
        storeSnapshot(
            draftId = stale,
            source = PredictionSource.MODEL,
            confidence = 0.9336,
            candidateIds = firstSet,
            staleReason = PredictionStaleReason.CATEGORY_CHANGED,
        )

        val unknown = storeDraft(key = "unknown", createdAt = BASE_INSTANT + 8, amountVnd = 30_000L)
        storeSnapshot(
            draftId = unknown,
            source = PredictionSource.NO_FIT,
            confidence = 0.8565,
            candidateIds = firstSet,
        )

        val direct = storeDraft(key = "direct", createdAt = BASE_INSTANT + 9, amountVnd = 40_000L)
        storeSnapshot(
            draftId = direct,
            source = PredictionSource.REMEMBERED_RULE,
            confidence = null,
            candidateIds = emptyList(),
        )

        val userResolved = storeDraft(
            key = "user-resolved",
            createdAt = BASE_INSTANT + 10,
            amountVnd = 55_000L,
            reviewCategoryResolved = true,
            reviewCategoryId = firstSet.first(),
        )
        storeSnapshot(
            draftId = userResolved,
            source = PredictionSource.MODEL,
            confidence = 0.71,
            candidateIds = firstSet,
            staleReason = PredictionStaleReason.USER_CATEGORY_CHOSEN,
        )

        val suggestionLow = storeDraft(
            key = "suggestion-low",
            createdAt = BASE_INSTANT + 11,
            amountVnd = 65_000L,
        )
        storeSnapshot(
            draftId = suggestionLow,
            source = PredictionSource.MODEL,
            confidence = 0.5638,
            candidateIds = firstSet,
        )

        // A wrong top choice at 0.6976: the queue orders sharpness, never correctness.
        val suggestionWrong = storeDraft(
            key = "suggestion-wrong",
            createdAt = BASE_INSTANT + 12,
            amountVnd = 70_000L,
        )
        storeSnapshot(
            draftId = suggestionWrong,
            source = PredictionSource.MODEL,
            confidence = 0.6976,
            candidateIds = firstSet,
        )

        val suggestionTieEarlier = storeDraft(
            key = "suggestion-tie-earlier",
            createdAt = BASE_INSTANT + 13,
            amountVnd = 80_000L,
        )
        storeSnapshot(
            draftId = suggestionTieEarlier,
            source = PredictionSource.MODEL,
            confidence = 0.801,
            candidateIds = firstSet,
        )
        val suggestionTieLater = storeDraft(
            key = "suggestion-tie-later",
            createdAt = BASE_INSTANT + 14,
            amountVnd = 90_000L,
        )
        storeSnapshot(
            draftId = suggestionTieLater,
            source = PredictionSource.MODEL,
            confidence = 0.801,
            candidateIds = firstSet,
        )

        return QueueFixture(
            duplicateEarlierDismissed = duplicateEarlier,
            duplicateMember = duplicateMember,
            proposedLink = proposedLink,
            unresolvedAmount = unresolvedAmount,
            unsupportedFormat = unsupportedFormat,
            missing = missing,
            stale = stale,
            unknown = unknown,
            direct = direct,
            userResolved = userResolved,
            suggestionLow = suggestionLow,
            suggestionWrong = suggestionWrong,
            suggestionTieEarlier = suggestionTieEarlier,
            suggestionTieLater = suggestionTieLater,
            otherGroupHigh = otherGroupHigh,
        )
    }

    private suspend fun pageOfAll(request: ReviewQueueQuery): List<ReviewQueueEntry> {
        val items = mutableListOf<ReviewQueueEntry>()
        var offset = 0
        while (true) {
            val page = query.reviewQueue(
                request.copy(bounds = QueryPageRequest(LIST_PAGE_SIZE, offset)),
            )
            items += page.items
            if (!page.hasMore) return items
            offset += page.items.size
        }
    }

    private suspend fun storeDraft(
        key: String,
        createdAt: Long,
        memo: String = key,
        amountState: IntakeFieldState = IntakeFieldState.PRESENT,
        amountVnd: Long? = 50_000L,
        momentState: IntakeFieldState = IntakeFieldState.PRESENT,
        parseStatus: IntakeParseStatus = IntakeParseStatus.PARSED,
        reviewCategoryResolved: Boolean = false,
        reviewCategoryId: Long? = null,
    ): Long = database.pendingBankDraftDao().store(
        PendingBankDraftEntity(
            deliveryId = draftDeliveryId(BANK_PACKAGE, key, createdAt),
            sourcePackage = BANK_PACKAGE,
            notificationKey = key,
            postedAtEpochMillis = createdAt,
            bank = "VIETINBANK",
            parseStatus = parseStatus,
            amountState = amountState,
            amountVnd = if (amountState == IntakeFieldState.PRESENT) amountVnd else null,
            amountUnresolvedReason = if (amountState == IntakeFieldState.UNRESOLVED) {
                "the amount was stated but could not be read"
            } else {
                null
            },
            direction = IntakeDirection.DEBIT,
            momentState = momentState,
            statedDate = if (momentState == IntakeFieldState.PRESENT) "2026-10-16" else null,
            statedTime = if (momentState == IntakeFieldState.PRESENT) "07:38" else null,
            momentUnresolvedReason = if (momentState == IntakeFieldState.UNRESOLVED) {
                "the moment was stated but could not be read"
            } else {
                null
            },
            memoState = IntakeFieldState.PRESENT,
            memo = memo,
            memoUnresolvedReason = null,
            reviewCategoryResolved = reviewCategoryResolved,
            reviewCategoryId = reviewCategoryId,
            createdAtEpochMillis = createdAt,
        ),
    )

    private suspend fun storeSnapshot(
        draftId: Long,
        source: PredictionSource,
        confidence: Double?,
        candidateIds: List<Long>,
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
                decisionSource = source.name,
                suggestedCategoryId = candidateIds.firstOrNull(),
                candidateIds = candidateIds.joinToString(","),
                probabilities = candidateIds.joinToString(",") { "0.1" },
                unknownShare = 0.2,
                confidence = confidence,
                effectiveInputDigest = "input-$draftId",
                eventIds = "",
                predictionTime = BASE_INSTANT,
                staleReason = staleReason?.name,
            ),
        )
    }

    private class QueueFixture(
        val duplicateEarlierDismissed: Long,
        val duplicateMember: Long,
        val proposedLink: Long,
        val unresolvedAmount: Long,
        val unsupportedFormat: Long,
        val missing: Long,
        val stale: Long,
        val unknown: Long,
        val direct: Long,
        val userResolved: Long,
        val suggestionLow: Long,
        val suggestionWrong: Long,
        val suggestionTieEarlier: Long,
        val suggestionTieLater: Long,
        val otherGroupHigh: Long,
    )

    private companion object {
        const val BANK_PACKAGE = "com.example.bank"
        const val BASE_INSTANT = 1_797_000_000_000L
        const val MODEL_REVISION = "laya-model-test"
        const val CANDIDATE_SET_VERSION = "laya-candidates-test"
    }
}
