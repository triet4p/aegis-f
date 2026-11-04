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
 * The fixture stores the states the queue sorts: unresolved link/bank-data conflicts, drafts
 * that still need a category, and drafts waiting for confirm or dismiss. The assertions are
 * the properties a review surface depends on — the exact order, the reason each draft is where
 * it is, that a page never skips or repeats a draft, and that the chronological override
 * returns every draft by arrival — not the internal statement that reads them.
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
    fun `conflicts come first then drafts needing a category then ready drafts`() = runTest {
        val fixture = seedQueue()

        val order = pageOfAll(ReviewQueueQuery(bounds = QueryPageRequest(LIST_PAGE_SIZE)))
            .map { it.draft.id }

        assertEquals(
            listOf(
                // Conflict group, oldest first.
                fixture.duplicateMember,
                fixture.proposedLink,
                fixture.unresolvedAmount,
                fixture.unsupportedFormat,
                // No category chosen yet, oldest first.
                fixture.noCategory,
                fixture.noCategoryLater,
                // Category chosen (or explicitly none): ready for confirm or dismiss.
                fixture.ready,
                fixture.explicitlyNoCategory,
            ),
            order,
        )
    }

    @Test
    fun `each entry names the conflict that placed it in the first group`() = runTest {
        val fixture = seedQueue()
        val entries = pageOfAll(
            ReviewQueueQuery(bounds = QueryPageRequest(LIST_PAGE_SIZE)),
        ).associateBy { it.draft.id }

        val duplicate = entries.getValue(fixture.duplicateMember)
        assertEquals(ReviewQueueGroup.NEEDS_DECISION, duplicate.group)
        assertEquals(listOf(ReviewQueueConflict.POSSIBLE_DUPLICATE), duplicate.conflicts)

        val proposed = entries.getValue(fixture.proposedLink)
        assertEquals(ReviewQueueGroup.NEEDS_DECISION, proposed.group)
        assertEquals(listOf(ReviewQueueConflict.PROPOSED_LINK), proposed.conflicts)

        val unresolved = entries.getValue(fixture.unresolvedAmount)
        assertEquals(ReviewQueueGroup.NEEDS_DECISION, unresolved.group)
        assertEquals(listOf(ReviewQueueConflict.UNRESOLVED_BANK_DATA), unresolved.conflicts)

        val unsupported = entries.getValue(fixture.unsupportedFormat)
        assertEquals(listOf(ReviewQueueConflict.UNRESOLVED_BANK_DATA), unsupported.conflicts)

        assertEquals(ReviewQueueGroup.NEEDS_CATEGORY, entries.getValue(fixture.noCategory).group)
        assertEquals(ReviewQueueGroup.READY, entries.getValue(fixture.ready).group)
        assertEquals(
            ReviewQueueGroup.READY,
            entries.getValue(fixture.explicitlyNoCategory).group,
        )
    }

    @Test
    fun `saved category never outranks unresolved link or bank data`() = runTest {
        val fixture = seedQueue()
        val categoryId = requireNotNull(database.pendingBankDraftDao().findById(fixture.ready)!!.reviewCategoryId)
        val commands = WorkflowCommandService(database, clock = clock)
        for (draftId in listOf(fixture.proposedLink, fixture.unresolvedAmount)) {
            val draft = database.pendingBankDraftDao().findById(draftId)!!
            val result = commands.selectDraftCategory(
                WorkflowCallerScope.User,
                SelectDraftCategoryRequest("queue-category-$draftId", draftId, draft.reviewRevision, categoryId),
            )
            assertTrue(result is WorkflowCommandOutcome.Applied)
        }
        val entries = pageOfAll(ReviewQueueQuery(bounds = QueryPageRequest(LIST_PAGE_SIZE)))
        val byId = entries.associateBy { it.draft.id }
        assertEquals(ReviewQueueGroup.NEEDS_DECISION, byId.getValue(fixture.proposedLink).group)
        assertEquals(ReviewQueueGroup.NEEDS_DECISION, byId.getValue(fixture.unresolvedAmount).group)
        assertTrue(entries.indexOf(byId.getValue(fixture.proposedLink)) < entries.indexOf(byId.getValue(fixture.noCategory)))
        assertTrue(entries.indexOf(byId.getValue(fixture.unresolvedAmount)) < entries.indexOf(byId.getValue(fixture.ready)))
        assertEquals(categoryId, byId.getValue(fixture.proposedLink).draft.reviewCategoryId)
        assertEquals(categoryId, byId.getValue(fixture.unresolvedAmount).draft.reviewCategoryId)
        assertTrue(database.transactionDao().findAll().isEmpty())
    }

    @Test
    fun `a page boundary never skips or repeats a waiting draft`() = runTest {
        val fixture = seedQueue()
        val pageSize = 3
        val seen = mutableListOf<Long>()
        var offset = 0
        while (true) {
            val page = query.reviewQueue(
                ReviewQueueQuery(bounds = QueryPageRequest(pageSize, offset)),
            )
            seen += page.items.map { it.draft.id }
            if (!page.hasMore) break
            offset += page.items.size
        }

        assertEquals(8, seen.size)
        assertEquals(seen.distinct(), seen)
        assertEquals(
            listOf(
                fixture.duplicateMember,
                fixture.proposedLink,
                fixture.unresolvedAmount,
                fixture.unsupportedFormat,
                fixture.noCategory,
                fixture.noCategoryLater,
                fixture.ready,
                fixture.explicitlyNoCategory,
            ),
            seen,
        )
    }

    @Test
    fun `the chronological override returns every waiting draft by arrival`() = runTest {
        val fixture = seedQueue()
        val chronological = pageOfAll(
            ReviewQueueQuery(order = ReviewQueueOrder.CHRONOLOGICAL, bounds = QueryPageRequest(LIST_PAGE_SIZE)),
        ).map { it.draft.id }

        assertEquals(
            listOf(
                fixture.duplicateMember,
                fixture.proposedLink,
                fixture.unresolvedAmount,
                fixture.unsupportedFormat,
                fixture.noCategory,
                fixture.noCategoryLater,
                fixture.ready,
                fixture.explicitlyNoCategory,
            ),
            chronological,
        )
    }

    @Test
    fun `one entry read by id states the same group as the page`() = runTest {
        val fixture = seedQueue()
        val page = pageOfAll(ReviewQueueQuery(bounds = QueryPageRequest(LIST_PAGE_SIZE)))
            .associateBy { it.draft.id }

        for (draftId in page.keys) {
            val entry = query.reviewQueueEntry(draftId)
            assertEquals(page.getValue(draftId).group, entry?.group)
            assertEquals(page.getValue(draftId).conflicts, entry?.conflicts)
        }
    }

    @Test
    fun `a confirmed or dismissed draft leaves the queue`() = runTest {
        val fixture = seedQueue()
        val commands = reviewCommands(database, clock)
        commands.dismissDraft(fixture.ready)

        assertNull(query.reviewQueueEntry(fixture.ready))
        assertTrue(query.reviewQueueEntry(fixture.noCategory) != null)
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

    private suspend fun seedQueue(): QueueFixture {
        val duplicateRoot = storeDraft(key = "duplicate-root", createdAt = BASE_INSTANT)
        reviewCommands(database, clock).dismissDraft(duplicateRoot)
        val duplicateMember = storeDraft(
            key = "duplicate-member",
            createdAt = BASE_INSTANT + 1,
            possibleDuplicateOf = duplicateRoot,
        )

        val proposedLink = storeDraft(key = "proposed-link", createdAt = BASE_INSTANT + 2)
        val captureId = database.captureMessageDao().insert(
            CaptureMessageEntity(
                captureKind = CaptureKind.USER_NOTE,
                sourceKind = CaptureSourceKind.USER_QUICK_CAPTURE,
                sourceIdentity = "review-queue-proposed-link",
                bodyText = "Lunch 50,000 on 2026-10-16",
                capturedAtEpochMillis = BASE_INSTANT,
                captureTimezone = "Asia/Ho_Chi_Minh",
                revision = 0,
                originRef = null,
            ),
        )
        val eventWrite = EventContextRepository(database, clock).recordEvent(
            captureId = captureId,
            expectedCaptureRevision = 0,
            interpretation = NoteEventInterpretation(
                spanStart = 0,
                spanEnd = 5,
                normalizedText = "Lunch",
                amountVnd = 50_000L,
            ),
            extractionVersion = "test",
        )
        val eventId = (eventWrite as NoteEventWriteResult.Stored).eventId
        EventContextRepository(database, clock).proposeLink(eventId, proposedLink)

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
        val noCategory = storeDraft(key = "no-category", createdAt = BASE_INSTANT + 5)
        val noCategoryLater = storeDraft(key = "no-category-later", createdAt = BASE_INSTANT + 6)
        val categoryId = database.categoryDao().insert(
            CategoryEntity(
                name = "Foods",
                createdAtEpochMillis = BASE_INSTANT,
                updatedAtEpochMillis = BASE_INSTANT,
            ),
        )
        val ready = storeDraft(
            key = "ready",
            createdAt = BASE_INSTANT + 7,
            reviewCategoryResolved = true,
            reviewCategoryId = categoryId,
        )
        val explicitlyNoCategory = storeDraft(
            key = "explicitly-no-category",
            createdAt = BASE_INSTANT + 8,
            reviewCategoryResolved = true,
            reviewCategoryId = null,
        )

        return QueueFixture(
            duplicateMember = duplicateMember,
            proposedLink = proposedLink,
            unresolvedAmount = unresolvedAmount,
            unsupportedFormat = unsupportedFormat,
            noCategory = noCategory,
            noCategoryLater = noCategoryLater,
            ready = ready,
            explicitlyNoCategory = explicitlyNoCategory,
        )
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
        possibleDuplicateOf: Long? = null,
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
            possibleDuplicateOfDraftId = possibleDuplicateOf,
            reviewCategoryResolved = reviewCategoryResolved,
            reviewCategoryId = reviewCategoryId,
            createdAtEpochMillis = createdAt,
        ),
    )

    private class QueueFixture(
        val duplicateMember: Long,
        val proposedLink: Long,
        val unresolvedAmount: Long,
        val unsupportedFormat: Long,
        val noCategory: Long,
        val noCategoryLater: Long,
        val ready: Long,
        val explicitlyNoCategory: Long,
    )

    private companion object {
        const val BANK_PACKAGE = "com.example.bank"
        const val BASE_INSTANT = 1_797_000_000_000L
    }
}
