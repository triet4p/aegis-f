package io.github.triet4p.aegisf.ui

import android.content.Context
import androidx.compose.runtime.saveable.SaverScope
import androidx.test.core.app.ApplicationProvider
import io.github.triet4p.aegisf.data.CategoryEntity
import io.github.triet4p.aegisf.data.IntakeDirection
import io.github.triet4p.aegisf.data.IntakeFieldState
import io.github.triet4p.aegisf.data.IntakeParseStatus
import io.github.triet4p.aegisf.data.LedgerDatabase
import io.github.triet4p.aegisf.data.PendingBankDraftEntity
import io.github.triet4p.aegisf.data.ReviewQueueOrder
import io.github.triet4p.aegisf.data.SelectDraftCategoryRequest
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.data.draftDeliveryId
import io.github.triet4p.aegisf.data.reviewCommands
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
 * Review inbox focus and bounded visible-row refresh against the real database.
 * Changed rows update without navigation; a changed queue group re-reads the current page so
 * uncertainty-first order and page boundaries remain correct.
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
    fun `a decided visible row refreshes its page without changing the chosen order`() = runTest {
        val fixture = seedQueue()
        val focus = ReviewQueueFocus(query, pageSize = 2)
        focus.refresh()

        assertEquals(0, focus.pageIndex)
        assertEquals(ReviewQueueOrder.UNCERTAINTY_FIRST, focus.order)
        assertEquals(
            listOf(fixture.conflictA, fixture.conflictB),
            focus.page!!.items.map { it.draft.id },
        )

        // A visible row decided elsewhere refreshes this page without navigating.
        reviewCommands(database).dismissDraft(fixture.conflictA)
        focus.updateVisibleEntry(fixture.conflictA, query.reviewQueueEntry(fixture.conflictA))
        assertEquals(ReviewQueueOrder.UNCERTAINTY_FIRST, focus.order)
        assertEquals(0, focus.pageIndex)
        assertEquals(
            listOf(fixture.conflictB, fixture.needA),
            focus.page!!.items.map { it.draft.id },
        )
        assertTrue(focus.page!!.hasMore)
    }

    @Test
    fun `a changed visible conflict refreshes its category without moving unrelated rows`() = runBlocking {
        val fixture = seedQueue()
        val focus = ReviewQueueFocus(query, pageSize = 2)
        focus.refresh()
        val untouched = focus.page!!.items[1]
        val categoryId = database.categoryDao().insert(
            CategoryEntity(name = "Fuel", createdAtEpochMillis = BASE_INSTANT, updatedAtEpochMillis = BASE_INSTANT),
        )
        val draft = database.pendingBankDraftDao().findById(fixture.conflictA)!!
        val updates = Channel<io.github.triet4p.aegisf.data.ReviewQueueEntry?>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.Default) {
            query.observeReviewQueueEntry(draft.id).collect { updates.send(it) }
        }
        withTimeout(5_000) { updates.receive() }
        WorkflowCommandService(database).selectDraftCategory(
            WorkflowCallerScope.User,
            SelectDraftCategoryRequest("visible-category", draft.id, draft.reviewRevision, categoryId),
        )
        val changed = withTimeout(5_000) {
            var entry = updates.receive()
            while (entry?.draft?.reviewCategoryId != categoryId) entry = updates.receive()
            entry
        }
        collector.cancel()
        focus.updateVisibleEntry(draft.id, changed)
        assertEquals(listOf(fixture.conflictA, fixture.conflictB), focus.page!!.items.map { it.draft.id })
        assertEquals(categoryId, focus.page!!.items[0].draft.reviewCategoryId)
        assertEquals(untouched, focus.page!!.items[1])
        assertEquals(0, focus.pageIndex)
    }

    @Test
    fun `a visible category change reorders only the current page without changing focus`() = runTest {
        val fixture = seedQueue()
        val focus = ReviewQueueFocus(query, pageSize = 2)
        focus.refresh()
        focus.nextPage()
        assertEquals(listOf(fixture.needA, fixture.needB), focus.page!!.items.map { it.draft.id })
        val categoryId = database.categoryDao().insert(
            CategoryEntity(name = "Food", createdAtEpochMillis = BASE_INSTANT, updatedAtEpochMillis = BASE_INSTANT),
        )
        val draft = database.pendingBankDraftDao().findById(fixture.needA)!!
        WorkflowCommandService(database).selectDraftCategory(
            WorkflowCallerScope.User,
            SelectDraftCategoryRequest("visible-ready", draft.id, draft.reviewRevision, categoryId),
        )
        focus.updateVisibleEntry(draft.id, query.reviewQueueEntry(draft.id))
        assertEquals(1, focus.pageIndex)
        assertEquals(ReviewQueueOrder.UNCERTAINTY_FIRST, focus.order)
        assertEquals(listOf(fixture.needB, fixture.readyEarly), focus.page!!.items.map { it.draft.id })
    }

    @Test
    fun `an off page conflict enters the visible page in priority order`() = runTest {
        val fixture = seedQueue()
        val focus = ReviewQueueFocus(query, pageSize = 2)
        focus.refresh()
        assertEquals(listOf(fixture.conflictA, fixture.conflictB), focus.page!!.items.map { it.draft.id })
        val prior = database.pendingBankDraftDao().findById(fixture.readyEarly)!!
        database.pendingBankDraftDao().store(
            prior.copy(
                id = 0, deliveryId = "new-duplicate", possibleDuplicateOfDraftId = prior.id,
                createdAtEpochMillis = BASE_INSTANT + 100,
            ),
        )
        val pageIds = focus.observePageIds()
        val next = withContext(Dispatchers.Default) {
            withTimeout(5_000) { pageIds.first { it.items.firstOrNull() == fixture.readyEarly } }
        }
        focus.updatePageMembership(next)
        assertEquals(listOf(fixture.readyEarly, fixture.conflictA), focus.page!!.items.map { it.draft.id })
        assertEquals(0, focus.pageIndex)
    }

    @Test
    fun `a new order starts at the first page`() = runTest {
        val fixture = seedQueue()
        val focus = ReviewQueueFocus(query, pageSize = 2)
        focus.refresh()
        focus.nextPage()
        assertEquals(1, focus.pageIndex)
        assertEquals(
            listOf(fixture.needA, fixture.needB),
            focus.page!!.items.map { it.draft.id },
        )

        focus.setOrder(ReviewQueueOrder.CHRONOLOGICAL)
        assertEquals(0, focus.pageIndex)
        assertEquals(ReviewQueueOrder.CHRONOLOGICAL, focus.order)
        // The ready draft arrived first, so the chronological order shows it first even
        // though the review order put it last.
        assertEquals(
            listOf(fixture.readyEarly, fixture.conflictA),
            focus.page!!.items.map { it.draft.id },
        )

        focus.setOrder(ReviewQueueOrder.UNCERTAINTY_FIRST)
        assertEquals(0, focus.pageIndex)
        assertEquals(
            listOf(fixture.conflictA, fixture.conflictB),
            focus.page!!.items.map { it.draft.id },
        )
    }

    @Test
    fun `paging walks the order in whole pages and never appends past the last one`() = runTest {
        val fixture = seedQueue()
        val focus = ReviewQueueFocus(query, pageSize = 2)
        focus.refresh()

        focus.nextPage()
        assertEquals(1, focus.pageIndex)
        assertEquals(2, focus.page!!.offset)
        focus.nextPage()
        assertEquals(2, focus.pageIndex)
        assertEquals(
            listOf(fixture.readyEarly, fixture.readyLater),
            focus.page!!.items.map { it.draft.id },
        )
        assertFalse(focus.page!!.hasMore)

        // At the last page there is nothing after it, so the action changes nothing.
        focus.nextPage()
        assertEquals(2, focus.pageIndex)

        focus.previousPage()
        assertEquals(1, focus.pageIndex)
        assertEquals(
            listOf(fixture.needA, fixture.needB),
            focus.page!!.items.map { it.draft.id },
        )
    }

    @Test
    fun `a page that lost its rows steps back to the page before it`() = runTest {
        val fixture = seedQueue()
        val focus = ReviewQueueFocus(query, pageSize = 2)
        focus.refresh()
        focus.nextPage()
        focus.nextPage()
        assertEquals(2, focus.pageIndex)
        assertEquals(
            listOf(fixture.readyEarly, fixture.readyLater),
            focus.page!!.items.map { it.draft.id },
        )

        val commands = reviewCommands(database)
        commands.dismissDraft(fixture.readyEarly)
        commands.dismissDraft(fixture.readyLater)
        focus.refresh()

        assertEquals("the empty page stepped back", 1, focus.pageIndex)
        assertEquals(
            listOf(fixture.needA, fixture.needB),
            focus.page!!.items.map { it.draft.id },
        )
    }

    @Test
    fun `next work skips the open draft and refreshes the list it returns to`() = runTest {
        val fixture = seedQueue()
        val focus = ReviewQueueFocus(query, pageSize = 2)
        focus.refresh()

        assertEquals(fixture.conflictA, focus.nextWork(openDraftId = null))
        assertEquals(fixture.conflictB, focus.nextWork(openDraftId = fixture.conflictA))

        // The oldest decision was reviewed elsewhere: next-work re-reads the queue and the
        // list it leaves behind already shows the change.
        val commands = reviewCommands(database)
        commands.dismissDraft(fixture.conflictA)
        assertEquals(fixture.conflictB, focus.nextWork(openDraftId = null))
        assertEquals(
            listOf(fixture.conflictB, fixture.needA),
            focus.page!!.items.map { it.draft.id },
        )

        // With nothing else waiting, the action answers nothing and never reopens the draft
        // the user is on.
        commands.dismissDraft(fixture.conflictB)
        commands.dismissDraft(fixture.needA)
        commands.dismissDraft(fixture.needB)
        commands.dismissDraft(fixture.readyEarly)
        assertNull(focus.nextWork(openDraftId = fixture.readyLater))
    }

    @Test
    fun `the chosen order and page survive recreation and the rows are re-read`() = runTest {
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
            listOf(fixture.conflictB, fixture.needA),
            restored.page!!.items.map { it.draft.id },
        )
    }

    /**
     * One early ready draft, two conflicts, two drafts needing a category, one later ready draft.
     *
     * The early ready draft arrived first, so the review order and the arrival order differ
     * from the first page on — which is what makes a silent reorder visible in a test.
     */
    private suspend fun seedQueue(): FocusFixture {
        val readyEarly = storeDraft(
            key = "ready-early",
            createdAt = BASE_INSTANT,
            categoryResolved = true,
        )
        val conflictA = storeDraft(
            key = "conflict-a",
            createdAt = BASE_INSTANT + 1,
            amountState = IntakeFieldState.UNRESOLVED,
        )
        val conflictB = storeDraft(
            key = "conflict-b",
            createdAt = BASE_INSTANT + 2,
            momentState = IntakeFieldState.UNRESOLVED,
        )
        val needA = storeDraft(key = "need-a", createdAt = BASE_INSTANT + 3)
        val needB = storeDraft(key = "need-b", createdAt = BASE_INSTANT + 4)
        val readyLater = storeDraft(
            key = "ready-later",
            createdAt = BASE_INSTANT + 5,
            categoryResolved = true,
        )

        return FocusFixture(
            readyEarly = readyEarly,
            conflictA = conflictA,
            conflictB = conflictB,
            needA = needA,
            needB = needB,
            readyLater = readyLater,
        )
    }

    private suspend fun storeDraft(
        key: String,
        createdAt: Long,
        amountState: IntakeFieldState = IntakeFieldState.PRESENT,
        momentState: IntakeFieldState = IntakeFieldState.PRESENT,
        categoryResolved: Boolean = false,
    ): Long = database.pendingBankDraftDao().store(
        PendingBankDraftEntity(
            deliveryId = draftDeliveryId(BANK_PACKAGE, key, createdAt),
            sourcePackage = BANK_PACKAGE,
            notificationKey = key,
            postedAtEpochMillis = createdAt,
            bank = "VIETINBANK",
            parseStatus = IntakeParseStatus.PARSED,
            amountState = amountState,
            amountVnd = if (amountState == IntakeFieldState.PRESENT) 50_000L else null,
            amountUnresolvedReason = null,
            direction = IntakeDirection.DEBIT,
            momentState = momentState,
            statedDate = if (momentState == IntakeFieldState.PRESENT) "2026-10-16" else null,
            statedTime = if (momentState == IntakeFieldState.PRESENT) "07:38" else null,
            momentUnresolvedReason = null,
            memoState = IntakeFieldState.PRESENT,
            memo = key,
            memoUnresolvedReason = null,
            reviewCategoryResolved = categoryResolved,
            reviewCategoryId = null,
            createdAtEpochMillis = createdAt,
        ),
    )

    private class FocusFixture(
        val readyEarly: Long,
        val conflictA: Long,
        val conflictB: Long,
        val needA: Long,
        val needB: Long,
        val readyLater: Long,
    )

    private companion object {
        const val BANK_PACKAGE = "com.example.bank"
        const val BASE_INSTANT = 1_797_000_000_000L
    }
}
