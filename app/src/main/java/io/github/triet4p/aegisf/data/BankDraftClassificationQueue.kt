package io.github.triet4p.aegisf.data

import android.util.Log
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Process-owned, bounded classification work for durably persisted bank drafts.
 *
 * [IntakeRepository] reports every supported draft it stored — the newly inserted one and,
 * on a replayed delivery, the draft that delivery already names — and this queue runs one
 * classification request for it through [WorkflowCommandService]. Admission is a bounded
 * in-memory hand-off: it neither reads the database nor waits for the model, so a missing,
 * busy, or slow model can never delay or fail notification persistence.
 *
 * One logical work item per draft, and one inference per question:
 *
 * - A draft id is tracked from admission until its work ends, so a duplicate notification
 *   that resolves to the same stored draft adds no second item while its work is pending.
 * - The work is bound to the context and catalog revision it was scheduled for, and the
 *   idempotency key is derived from that revision. The command boundary claims the key
 *   durably before the model is asked, so a repeated enqueue for the same draft and the
 *   same revision replays the recorded request instead of asking the model again. A
 *   changed revision is a different question and gets its own key.
 * - Correction rules, the manual-selection rule, safety-blocked text, the dependency check
 *   and the refusal of a settled draft are not re-implemented here: the request goes
 *   through the same [WorkflowCommandService.requestClassification] the review screen uses.
 *   A draft the user already resolved is skipped before the model is asked, because the
 *   boundary would preserve that choice anyway and automatic work must not spend a bounded
 *   inference on it.
 *
 * The queue is process-owned, not durable. A process that ends mid-work leaves the drafts
 * stored — they were persisted before admission — and their classification missing, which
 * review shows as unclassified rather than as a guessed answer. Resumable
 * pending/running/result/cancelled/failed work, revision-aware coalescing and retry
 * admission are S7.3's separate policy; this queue only does what "trigger classification
 * from bank intake" requires. Nothing here writes a ledger record or selects a category.
 */
class BankDraftClassificationQueue(
    database: LedgerDatabase,
    private val commands: WorkflowCommandService,
    maxPending: Int = MAX_PENDING,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : Closeable {
    private val drafts = database.pendingBankDraftDao()
    private val snapshots = ClassificationSnapshotRepository(database = database)

    /** The drafts admitted and not yet finished: one logical work item per draft id. */
    private val tracked = HashSet<Long>()
    private val lock = Any()
    private val pending = Channel<Long>(capacity = maxPending)

    @Volatile
    private var closed = false

    /**
     * The one worker that runs the admitted work.
     *
     * One worker, not one job per draft: the local model answers one request at a time, so
     * admitting a second draft must queue it behind the first instead of piling native work
     * onto the inference lifecycle. The worker is a suspended coroutine between items, never
     * a thread and never an open model session.
     */
    private val worker = scope.launch {
        for (draftId in pending) {
            try {
                classify(draftId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // The draft is already stored and reviewable; a failed scheduling step is
                // reported as the miss it is and never turns into a claim about the draft.
                Log.w(
                    TAG,
                    "intake classification failed for draft=$draftId " +
                        "(${error.javaClass.simpleName})",
                )
            } finally {
                synchronized(lock) { tracked.remove(draftId) }
            }
        }
    }

    /**
     * Admits one logical classification work item for [draftId].
     *
     * Called after the draft's own transaction has committed. The call never suspends, never
     * throws into the caller, and never reads the database; a draft already admitted is
     * ignored, so the same stored draft cannot occupy the queue twice. A full queue refuses
     * admission and says so in the log: the draft stays visible as unclassified instead of
     * being silently replaced by a second guess, and intake is unaffected either way.
     */
    fun enqueue(draftId: Long) {
        if (draftId <= 0L) return
        synchronized(lock) {
            if (closed) return
            if (!tracked.add(draftId)) return
            if (pending.trySend(draftId).isFailure) {
                tracked.remove(draftId)
                Log.w(
                    TAG,
                    "intake classification queue is full; draft=$draftId stays unclassified",
                )
            }
        }
    }

    /** Stops admission and abandons queued work; stored drafts are untouched. */
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        pending.close()
        worker.cancel()
        scope.cancel()
    }

    /** Runs one admitted work item against the state it is scheduled for. */
    private suspend fun classify(draftId: Long) {
        val draft = drafts.findById(draftId)
        if (draft == null) {
            Log.i(TAG, "intake classification skipped: draft=$draftId no longer exists")
            return
        }
        if (draft.reviewStatus != IntakeReviewStatus.PENDING) {
            Log.i(
                TAG,
                "intake classification skipped: draft=$draftId is ${draft.reviewStatus.name}",
            )
            return
        }
        if (draft.reviewCategoryResolved) {
            Log.i(
                TAG,
                "intake classification skipped: draft=$draftId already carries the user's category",
            )
            return
        }
        // The same working description the review form opens with: the user's own wording
        // when they corrected it, the permitted bank memo otherwise. The bank memo and a
        // review note are never concatenated, and a blank one is left to the boundary to
        // report as needing a description.
        val description = if (draft.reviewMemoEdited) {
            draft.reviewMemo.orEmpty()
        } else {
            draft.memo.orEmpty()
        }
        val effective = snapshots.effectiveContextFor(draftId, description) ?: return
        val key = intakeClassificationRequestKey(draftId, effective.dependencies)
        Log.i(TAG, "intake classification draft=$draftId scheduled against its revision")
        val outcome = commands.requestClassification(
            scope = WorkflowCallerScope.User,
            request = ClassificationRequest(
                idempotencyKey = key,
                draftId = draftId,
                description = description,
                expectedReviewRevision = draft.reviewRevision,
            ),
        )
        Log.i(TAG, "intake classification draft=$draftId outcome=${outcome.logName()}")
    }

    private companion object {
        const val TAG = "BankDraftClassification"

        /**
         * How many drafts may wait for classification at once.
         *
         * A bound on admitted work, not on intake: a bank delivers a handful of notifications
         * at human time, so this is far above any real burst, and one draft needs only eight
         * bytes here. A queue that somehow filled would leave the drafts beyond it visible as
         * unclassified — never guessed and never dropped from review.
         */
        const val MAX_PENDING = 32
    }
}

/**
 * The idempotency key of one intake-scheduled classification.
 *
 * The key names the question, not the answer: the same draft at the same context revision
 * under the same category catalog is the same question, so a repeated notification replays
 * the recorded request instead of asking the model again. Any change of the draft's review
 * state, its links, its stored readings, or the category catalog is a different question and
 * gets its own key. The prefix keeps these keys apart from the review screen's own requests.
 */
internal fun intakeClassificationRequestKey(
    draftId: Long,
    dependencies: PredictionDependencies,
): String = canonicalDependencyDigest(
    listOf(
        "INTAKE_CLASSIFICATION",
        draftId.toString(),
        dependencies.contextRevision,
        dependencies.categoryRevision,
    ),
)

/** The outcome's kind, without any of its payload, for a privacy-safe log line. */
private fun WorkflowCommandOutcome.logName(): String = when (this) {
    is WorkflowCommandOutcome.Applied -> "APPLIED"
    is WorkflowCommandOutcome.NoChange -> "NO_CHANGE"
    is WorkflowCommandOutcome.NeedsClarification -> "NEEDS_CLARIFICATION"
    is WorkflowCommandOutcome.Conflict -> "CONFLICT"
    WorkflowCommandOutcome.NotFound -> "NOT_FOUND"
    is WorkflowCommandOutcome.Denied -> "DENIED"
    WorkflowCommandOutcome.Cancelled -> "CANCELLED"
    is WorkflowCommandOutcome.Failed -> "FAILED"
}
