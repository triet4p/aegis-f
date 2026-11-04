package io.github.triet4p.aegisf.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import io.github.triet4p.aegisf.data.LIST_PAGE_SIZE
import io.github.triet4p.aegisf.data.QueryPage
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.ReviewQueueEntry
import io.github.triet4p.aegisf.data.ReviewQueueOrder
import io.github.triet4p.aegisf.data.ReviewQueueQuery
import io.github.triet4p.aegisf.data.WorkflowQueryService

/**
 * The review queue the inbox shows: which order, which page, and the rows currently shown.
 *
 * Visible rows refresh individually. When one changes group or leaves the queue, the bounded
 * current page is re-read so conflict-first ordering and page boundaries remain authoritative.
 * That never changes the selected order or opens a different draft.
 *
 * The holder also keeps the paging rules: a new order starts at the first page, a page that
 * lost its rows steps back to the nearest page that still holds some, and [nextWork] answers
 * which draft to open next instead of the app jumping on its own.
 */
internal class ReviewQueueFocus(
    private val queryService: WorkflowQueryService,
    /** How many drafts one page of the inbox shows. */
    private val pageSize: Int = LIST_PAGE_SIZE,
) {
    /** The order the shown rows were read in. */
    var order by mutableStateOf(ReviewQueueOrder.UNCERTAINTY_FIRST)
        private set

    /** The page of the shown rows. */
    var pageIndex by mutableIntStateOf(0)
        private set

    /** The rows the inbox shows, or null before the first read of this page finishes. */
    var page by mutableStateOf<QueryPage<ReviewQueueEntry>?>(null)
        private set

    /** Whether a read is in flight, so the surface can say it is reading. */
    var reading by mutableStateOf(false)
        private set

    private var readGeneration = 0L

    /** Re-reads the current order at the current page. */
    suspend fun refresh() {
        read(order, pageIndex)
    }

    /** Watches only the current page boundary and its next-page sentinel. */
    fun observePageIds() = queryService.observeReviewQueueIds(query(order, pageIndex))

    /** An off-page change may alter which rows belong on this page. */
    suspend fun updatePageMembership(ids: QueryPage<Long>) {
        val current = page ?: return
        if (ids.offset != current.offset) return
        if (ids.items != current.items.map { it.draft.id } || ids.hasMore != current.hasMore) {
            read(order, pageIndex)
        }
    }

    /** Refreshes only a visible changed row, re-reading its page when its sort position changes. */
    suspend fun updateVisibleEntry(draftId: Long, updated: ReviewQueueEntry?) {
        val current = page ?: return
        val index = current.items.indexOfFirst { it.draft.id == draftId }
        if (index < 0) return
        val previous = current.items[index]
        if (updated == previous) return
        if (updated == null || updated.group != previous.group) {
            read(order, pageIndex)
        } else {
            val rows = current.items.toMutableList()
            rows[index] = updated
            page = current.copy(items = rows)
        }
    }

    /** Switches the order and starts it at the first page, never at an offset in the old one. */
    suspend fun setOrder(next: ReviewQueueOrder) {
        order = next
        read(next, 0)
    }

    /** Shows the page after this one, when the read said more rows match. */
    suspend fun nextPage() {
        if (page?.hasMore == true) read(order, pageIndex + 1)
    }

    /** Shows the page before this one. */
    suspend fun previousPage() {
        if (pageIndex > 0) read(order, pageIndex - 1)
    }

    /**
     * The draft the next-work action opens, and a refreshed list to go with it.
     *
     * The target is the first draft of the uncertainty-first queue, leaving out the draft the
     * user already has open: the oldest draft that still needs a decision. The shown rows are
     * re-read as part of the same explicit action, so the list the user returns to is current
     * without ever having moved on its own.
     */
    suspend fun nextWork(openDraftId: Long?): Long? {
        val target = queryService.reviewQueue(
            ReviewQueueQuery(
                order = ReviewQueueOrder.UNCERTAINTY_FIRST,
                excludingDraftId = openDraftId,
                bounds = QueryPageRequest(1),
            ),
        ).items.firstOrNull()?.draft?.id
        read(order, pageIndex)
        return target
    }

    private suspend fun read(order: ReviewQueueOrder, startPageIndex: Int) {
        val generation = ++readGeneration
        reading = true
        try {
            var index = startPageIndex
            var loaded = queryService.reviewQueue(query(order, index))
            // A page that lost its rows steps back to the nearest page that still has rows.
            while (loaded.items.isEmpty() && index > 0) {
                index -= 1
                loaded = queryService.reviewQueue(query(order, index))
            }
            if (generation == readGeneration) {
                pageIndex = index
                page = loaded
            }
        } finally {
            if (generation == readGeneration) reading = false
        }
    }

    private fun query(order: ReviewQueueOrder, pageIndex: Int): ReviewQueueQuery =
        ReviewQueueQuery(
            order = order,
            bounds = QueryPageRequest(pageSize, offset = pageIndex * pageSize),
        )

    companion object {
        /**
         * Keeps the chosen order and page across a configuration change or process
         * recreation; the rows themselves are always re-read, never restored, because the
         * stored state may have moved on.
         */
        fun saver(queryService: WorkflowQueryService): Saver<ReviewQueueFocus, Any> = listSaver(
            save = { focus -> listOf(focus.order.name, focus.pageIndex, focus.pageSize) },
            restore = { saved ->
                ReviewQueueFocus(queryService, saved[2] as Int).apply {
                    order = ReviewQueueOrder.valueOf(saved[0] as String)
                    pageIndex = saved[1] as Int
                }
            },
        )
    }
}
