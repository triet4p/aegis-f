package io.github.triet4p.aegisf.data

import androidx.room.Dao
import androidx.room.Query

/**
 * One pending draft's place in the review queue: the ordering facts, without the draft row
 * itself.
 *
 * The facts are the ones `docs/sprint-7-confidence-queue-policy.md` §7 orders by: the group
 * the draft waits in ([queueRank]), the subkind inside the stale/missing/unknown group
 * ([queueSubkind]), the comparability group of a fresh prediction ([queueComparableGroup]),
 * and the stored sharpness that orders the drafts of one such group ([queueConfidence]).
 * [queuePossibleDuplicate] and [queueProposedLink] say which unresolved-link facts put the
 * draft in the first group, so a surface can name the reason instead of only the position.
 *
 * The row is a read of the stored state and nothing else: it invents no score, and a stored
 * confidence is reported only where the policy lets it order anything.
 */
data class ReviewQueueRow(
    val draftId: Long,
    /** 0 needs the user's decision, 1 not comparable, 2 a fresh comparable prediction. */
    val queueRank: Int,
    /** 1..5 inside the not-comparable group by policy §7.1 order; 0 outside it. */
    val queueSubkind: Int,
    /** The canonical comparability key of a fresh prediction, or null outside that group. */
    val queueComparableGroup: String?,
    /** The stored sharpness of a fresh comparable prediction, or null outside that group. */
    val queueConfidence: Double?,
    /** 1 when the draft belongs to a possible-duplicate family, 0 otherwise. */
    val queuePossibleDuplicate: Int,
    /** 1 when a link to this draft is still proposed, 0 otherwise. */
    val queueProposedLink: Int,
    /** The stored decision source, or null when no prediction is stored. */
    val queueDecisionSource: String?,
    /** Why the stored prediction no longer applies, or null while it does. */
    val queueStaleReason: String?,
    /** The evaluated category ids of the stored prediction, or null when there are none. */
    val queueCandidateIds: String?,
)

/**
 * The uncertainty-first review queue, read from the stored drafts and their predictions.
 *
 * The queue is a *reading* of the state the app already stores: every pending draft appears
 * exactly once, assigned to one group by the frozen policy, and nothing here writes. The
 * order is total — a page boundary always falls between two whole drafts — and deterministic
 * because the last two terms are the draft's creation instant and its unique id.
 *
 * The two facts that are not columns of the draft's own row are read with subqueries over the
 * rows the app stores: the possible-duplicate family (either direction) and a link that is
 * still proposed. Both are facts about stored rows, never scores, and neither can be widened
 * by a caller: the statement is fixed.
 */
@Dao
abstract class ReviewQueueDao {

    /**
     * A bounded page of the queue order.
     *
     * [chronological] selects the chronological override: every pending draft by creation
     * instant and id, which is the order a user reads when they want to see the work in the
     * order it arrived — high-confidence items included. Otherwise the uncertainty-first
     * order applies. [excludingDraftId] leaves one draft out, which is what lets the
     * next-work action advance past the draft the user already has open.
     *
     * The read asks for the order only; the rows themselves are read by [findDrafts] and
     * reassembled in the order this page returned them, so the queue's projection never has
     * to carry a draft row and a page never holds more drafts than it asked for.
     */
    @Query(REVIEW_QUEUE_PAGE_SQL)
    abstract suspend fun findOrderPage(
        chronological: Int,
        excludingDraftId: Long?,
        limit: Int,
        offset: Int,
    ): List<ReviewQueueRow>

    /** The stored drafts named by one queue page, in any order. */
    @Query("SELECT * FROM pending_bank_drafts WHERE id IN (:draftIds)")
    abstract suspend fun findDrafts(draftIds: List<Long>): List<PendingBankDraftEntity>
}

/**
 * The facts one draft's place in the queue is computed from.
 *
 * The two join-shaped facts are `CASE … THEN 1 ELSE 0` expressions rather than bare
 * `EXISTS`s, so every projection column has the integer type a stored flag has and no row
 * can fail to read.
 */
private const val REVIEW_QUEUE_FACTS_SQL =
    "SELECT " +
        "d.id AS draftId, " +
        "d.createdAtEpochMillis AS queueCreatedAt, " +
        "d.reviewCategoryResolved AS queueCategoryResolved, " +
        "CASE WHEN s.draftId IS NULL THEN 1 ELSE 0 END AS queueMissing, " +
        "s.decisionSource AS queueDecisionSource, " +
        "s.staleReason AS queueStaleReason, " +
        "s.candidateIds AS queueCandidateIds, " +
        "s.confidence AS queueConfidence, " +
        "CASE WHEN s.draftId IS NOT NULL " +
        "AND s.decisionSource = 'MODEL' " +
        "AND s.staleReason IS NULL " +
        "AND d.reviewCategoryResolved = 0 " +
        "THEN s.modelRevision || ' | ' || s.candidateSetVersion || ' | ' || s.candidateIds " +
        "ELSE NULL END AS queueComparableGroup, " +
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
        "THEN 1 ELSE 0 END AS queueConflict " +
        "FROM pending_bank_drafts d " +
        "LEFT JOIN classification_snapshots s ON s.draftId = d.id " +
        "WHERE d.reviewStatus = 'PENDING' " +
        "AND (:excludingDraftId IS NULL OR d.id != :excludingDraftId)"

/**
 * The queue order, computed once per pending draft.
 *
 * The precedence inside the not-comparable group is deliberate: a draft the user already
 * chose a category for is classified as the user-resolved subkind even though that decision
 * is also what marked its prediction stale. The user's decision is the current fact; the
 * stale prediction is its consequence, not a second reason to review the suggestion. Every
 * other check follows the policy's listed order, and the subkind numbers are that listed
 * order, so a group's own oldest work is always first.
 */
private const val REVIEW_QUEUE_ORDERED_SQL =
    "SELECT " +
        "f.draftId AS draftId, " +
        "CASE WHEN f.queueConflict = 1 THEN 0 " +
        "WHEN f.queueMissing = 1 THEN 1 " +
        "WHEN f.queueCategoryResolved = 1 THEN 1 " +
        "WHEN f.queueStaleReason IS NOT NULL THEN 1 " +
        "WHEN f.queueDecisionSource IS NULL OR f.queueDecisionSource != 'MODEL' THEN 1 " +
        "ELSE 2 END AS queueRank, " +
        "CASE WHEN f.queueConflict = 1 THEN 0 " +
        "WHEN f.queueMissing = 1 THEN 1 " +
        "WHEN f.queueCategoryResolved = 1 THEN 5 " +
        "WHEN f.queueStaleReason IS NOT NULL THEN 2 " +
        "WHEN f.queueDecisionSource = 'NO_FIT' THEN 3 " +
        "WHEN f.queueDecisionSource = 'MODEL' THEN 0 " +
        "ELSE 4 END AS queueSubkind, " +
        "CASE WHEN f.queueConflict = 1 OR f.queueMissing = 1 " +
        "OR f.queueCategoryResolved = 1 OR f.queueStaleReason IS NOT NULL " +
        "OR f.queueDecisionSource IS NULL OR f.queueDecisionSource != 'MODEL' " +
        "THEN NULL ELSE f.queueComparableGroup END AS queueComparableGroup, " +
        "CASE WHEN f.queueConflict = 0 AND f.queueMissing = 0 " +
        "AND f.queueCategoryResolved = 0 AND f.queueStaleReason IS NULL " +
        "AND f.queueDecisionSource = 'MODEL' " +
        "THEN f.queueConfidence ELSE NULL END AS queueConfidence, " +
        "f.queuePossibleDuplicate AS queuePossibleDuplicate, " +
        "f.queueProposedLink AS queueProposedLink, " +
        "f.queueDecisionSource AS queueDecisionSource, " +
        "f.queueStaleReason AS queueStaleReason, " +
        "f.queueCandidateIds AS queueCandidateIds, " +
        "f.queueCreatedAt AS queueCreatedAt " +
        "FROM (" + REVIEW_QUEUE_FACTS_SQL + ") f"

/**
 * The bounded page of the queue.
 *
 * The four gated order terms are the uncertainty-first order; in chronological mode they are
 * constant, which leaves the creation instant and the unique id — the chronological override
 * the policy keeps always available. The last two terms are unconditional, so both orders are
 * total and a page boundary never splits a tie.
 */
private const val REVIEW_QUEUE_PAGE_SQL =
    "SELECT " +
        "q.draftId AS draftId, " +
        "q.queueRank AS queueRank, " +
        "q.queueSubkind AS queueSubkind, " +
        "q.queueComparableGroup AS queueComparableGroup, " +
        "q.queueConfidence AS queueConfidence, " +
        "q.queuePossibleDuplicate AS queuePossibleDuplicate, " +
        "q.queueProposedLink AS queueProposedLink, " +
        "q.queueDecisionSource AS queueDecisionSource, " +
        "q.queueStaleReason AS queueStaleReason, " +
        "q.queueCandidateIds AS queueCandidateIds " +
        "FROM (" + REVIEW_QUEUE_ORDERED_SQL + ") q " +
        "ORDER BY " +
        "CASE WHEN :chronological = 0 THEN q.queueRank END ASC, " +
        "CASE WHEN :chronological = 0 AND q.queueRank = 1 THEN q.queueSubkind END ASC, " +
        "CASE WHEN :chronological = 0 AND q.queueRank = 2 THEN q.queueComparableGroup END ASC, " +
        "CASE WHEN :chronological = 0 AND q.queueRank = 2 THEN q.queueConfidence END ASC, " +
        "q.queueCreatedAt ASC, " +
        "q.draftId ASC " +
        "LIMIT :limit OFFSET :offset"
