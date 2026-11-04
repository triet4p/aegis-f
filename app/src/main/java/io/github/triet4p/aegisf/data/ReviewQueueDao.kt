package io.github.triet4p.aegisf.data

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One pending draft's place in the review queue: the ordering facts, without the draft row
 * itself.
 *
 * The facts are the stored conflict flags and whether the draft still needs a category.
 * [queuePossibleDuplicate] and [queueProposedLink] say which unresolved-link facts put the
 * draft in the first group, so a surface can name the reason instead of only the position.
 *
 * The row is a read of the stored state and nothing else: it invents no score and reads no
 * model prediction.
 */
data class ReviewQueueRow(
    val draftId: Long,
    /** 0 needs the user's decision, 1 still needs a category, 2 ready to confirm. */
    val queueRank: Int,
    /** 1 when the draft belongs to a possible-duplicate family, 0 otherwise. */
    val queuePossibleDuplicate: Int,
    /** 1 when a link to this draft is still proposed, 0 otherwise. */
    val queueProposedLink: Int,
)

/**
 * The uncertainty-first review queue, read from the stored drafts alone.
 *
 * The queue is a *reading* of the state the app already stores: every pending draft appears
 * exactly once, assigned to one group, and nothing here writes. The order is total — a page
 * boundary always falls between two whole drafts — and deterministic because the last two
 * terms are the draft's creation instant and its unique id.
 */
@Dao
abstract class ReviewQueueDao {

    /**
     * A bounded page of the queue order.
     *
     * [chronological] selects the chronological override: every pending draft by creation
     * instant and id, which is the order a user reads when they want to see the work in the
     * order it arrived. Otherwise the uncertainty-first order applies. [excludingDraftId]
     * leaves one draft out, which is what lets the next-work action advance past the draft the
     * user already has open.
     *
     * The read asks for the order only; the rows themselves are read by [findDrafts] and
     * reassembled in the order this page returned them.
     */
    @Query(REVIEW_QUEUE_PAGE_SQL)
    abstract suspend fun findOrderPage(
        chronological: Int,
        excludingDraftId: Long?,
        limit: Int,
        offset: Int,
    ): List<ReviewQueueRow>

    /** Watches the bounded ordered ids for the currently visible page. */
    @Query(REVIEW_QUEUE_PAGE_SQL)
    abstract fun observeOrderPage(
        chronological: Int,
        excludingDraftId: Long?,
        limit: Int,
        offset: Int,
    ): Flow<List<ReviewQueueRow>>

    /** One pending draft's queue facts by stable id. */
    @Query(REVIEW_QUEUE_ENTRY_SQL)
    abstract suspend fun findOrderRow(
        draftId: Long,
        excludingDraftId: Long? = null,
    ): ReviewQueueRow?

    /** Observes queue facts for one displayed draft. */
    @Query(REVIEW_QUEUE_ENTRY_SQL)
    abstract fun observeOrderRow(draftId: Long, excludingDraftId: Long? = null): Flow<ReviewQueueRow?>

    /** The stored drafts named by one queue page, in any order. */
    @Query("SELECT * FROM pending_bank_drafts WHERE id IN (:draftIds)")
    abstract suspend fun findDrafts(draftIds: List<Long>): List<PendingBankDraftEntity>
}

/**
 * The facts one draft's place in the queue is computed from.
 *
 * The join-shaped facts are `CASE … THEN 1 ELSE 0` expressions rather than bare `EXISTS`s,
 * so every projection column has the integer type a stored flag has and no row can fail to
 * read.
 */
private const val REVIEW_QUEUE_FACTS_SQL =
    "SELECT " +
        "d.id AS draftId, " +
        "d.createdAtEpochMillis AS queueCreatedAt, " +
        "CASE WHEN d.possibleDuplicateOfDraftId IS NOT NULL " +
        "OR EXISTS (SELECT 1 FROM pending_bank_drafts p " +
        "WHERE p.possibleDuplicateOfDraftId = d.id) " +
        "THEN 1 ELSE 0 END AS queuePossibleDuplicate, " +
        "CASE WHEN EXISTS (SELECT 1 FROM context_links l " +
        "WHERE l.draftId = d.id AND l.state = 'PROPOSED') " +
        "THEN 1 ELSE 0 END AS queueProposedLink, " +
        "CASE WHEN d.possibleDuplicateOfDraftId IS NOT NULL " +
        "OR EXISTS (SELECT 1 FROM pending_bank_drafts p " +
        "WHERE p.possibleDuplicateOfDraftId = d.id) " +
        "OR EXISTS (SELECT 1 FROM context_links l " +
        "WHERE l.draftId = d.id AND l.state = 'PROPOSED') " +
        "OR d.amountState = 'UNRESOLVED' " +
        "OR d.momentState = 'UNRESOLVED' " +
        "OR d.parseStatus = 'UNSUPPORTED_FORMAT' " +
        "THEN 1 ELSE 0 END AS queueConflict, " +
        "d.reviewCategoryResolved AS queueCategoryResolved " +
        "FROM pending_bank_drafts d " +
        "WHERE d.reviewStatus = 'PENDING' " +
        "AND (:excludingDraftId IS NULL OR d.id != :excludingDraftId)"

/**
 * The queue order, computed once per pending draft.
 *
 * Conflict first, then a draft with no chosen category, then a draft ready for confirm or
 * dismiss. Within a group the oldest work comes first.
 */
private const val REVIEW_QUEUE_ORDERED_SQL =
    "SELECT " +
        "f.draftId AS draftId, " +
        "CASE WHEN f.queueConflict = 1 THEN 0 " +
        "WHEN f.queueCategoryResolved = 0 THEN 1 " +
        "ELSE 2 END AS queueRank, " +
        "f.queuePossibleDuplicate AS queuePossibleDuplicate, " +
        "f.queueProposedLink AS queueProposedLink, " +
        "f.queueCreatedAt AS queueCreatedAt " +
        "FROM (" + REVIEW_QUEUE_FACTS_SQL + ") f"

private const val REVIEW_QUEUE_SELECT_COLUMNS =
    "q.draftId AS draftId, " +
        "q.queueRank AS queueRank, " +
        "q.queuePossibleDuplicate AS queuePossibleDuplicate, " +
        "q.queueProposedLink AS queueProposedLink"

private const val REVIEW_QUEUE_ENTRY_SQL =
    "SELECT " + REVIEW_QUEUE_SELECT_COLUMNS + " " +
        "FROM (" + REVIEW_QUEUE_ORDERED_SQL + ") q " +
        "WHERE q.draftId = :draftId"

private const val REVIEW_QUEUE_PAGE_SQL =
    "SELECT " + REVIEW_QUEUE_SELECT_COLUMNS + " " +
        "FROM (" + REVIEW_QUEUE_ORDERED_SQL + ") q " +
        "ORDER BY " +
        "CASE WHEN :chronological = 0 THEN q.queueRank END ASC, " +
        "q.queueCreatedAt ASC, " +
        "q.draftId ASC " +
        "LIMIT :limit OFFSET :offset"
