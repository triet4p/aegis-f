package io.github.triet4p.aegisf.data

import android.util.Log
import androidx.room.withTransaction
import java.io.Closeable
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The process-owned worker that turns durably stored bank drafts into review-only Laya
 * predictions — and the durable queue that makes that work resumable.
 *
 * The jobs live in [ClassificationWorkEntity]: one row per draft, with a real
 * pending/running/result/cancelled/failed lifecycle. The in-memory channel here is only a
 * wake-up signal; it holds no work, so a burst that fills it cannot lose a job and a
 * process that ends cannot lose a queued question either.
 *
 * What the queue guarantees:
 *
 * - **One logical job per draft, at the newest relevant revision.** A newer question
 *   replaces the queued one in place ([ClassificationWorkDao.arm]) or updates the one a
 *   running attempt already holds ([ClassificationWorkDao.retarget]). Rapid edits
 *   therefore cost one job and one model question, not one per edit.
 * - **Only relevant changes schedule work.** A question is identified by the digest of
 *   the description, the linked event readings, and the catalog, rule, model, and
 *   candidate-set revisions ([classificationQuestionDigest]). An accepted command asks
 *   the queue to re-check its jobs, and a job whose question did not change is not asked
 *   again. Redrawing a screen, re-reading a draft, or correcting an amount (none of which
 *   changes the model's input) schedules nothing.
 * - **Resumable across process death.** A running job is written before the model is
 *   asked and finished only by the attempt that wrote it. When the queue starts in a new
 *   process every interrupted running job returns to pending before the worker claims
 *   anything, so the draft is classified after a restart instead of staying unclassified.
 * - **Bounded resources.** One worker, one model request at a time, one inference session
 *   at a time through the shared lifecycle, a bounded number of waiting jobs, and a
 *   bounded number of attempts per question. A missing or busy model ends the attempt in
 *   a recorded state and never touches intake.
 * - **The user's decisions stand.** A draft the user resolved, confirmed, or dismissed is
 *   cancelled before the model is asked; a late result is refused by the command boundary
 *   and by the job's own conditional transitions.
 *
 * The queue never selects a category, confirms a draft, or writes a ledger record. The
 * notification listener only reports a stored draft id; it never constructs this queue,
 * never opens the model, and never holds an inference session.
 */
class BankDraftClassificationQueue(
    private val database: LedgerDatabase,
    private val commands: WorkflowCommandService,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: LedgerClock = SystemLedgerClock,
    private val maxQueued: Int = MAX_QUEUED,
    private val retryBackoffMillis: Long = RETRY_BACKOFF_MILLIS,
) : Closeable {
    private val drafts = database.pendingBankDraftDao()
    private val work = database.classificationWorkDao()
    private val snapshots = ClassificationSnapshotRepository(database = database, clock = clock)

    /** Wakes the worker; an empty signal is enough because the jobs are in the database. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var closed = false

    /**
     * The one worker that runs the queued jobs.
     *
     * One worker, not one job per draft: the local model answers one request at a time, so
     * admitting a second draft queues it behind the first instead of piling native work
     * onto the inference lifecycle. The worker is a suspended coroutine between items,
     * never a thread and never an open model session.
     */
    private val worker = scope.launch {
        resumeInterruptedWork()
        while (isActive) {
            val job = try {
                work.oldestInState(CLASSIFICATION_WORK_PENDING)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "classification work could not be read (${error.javaClass.simpleName})")
                null
            }
            if (job == null) {
                wake.receive()
                continue
            }
            if (runAttempt(job)) delay(retryBackoffMillis)
        }
    }

    /**
     * Reports one durably stored draft, or one whose delivery was re-read, to the queue.
     *
     * Called after the draft's own transaction has committed. The call records the job
     * before it returns, so the work survives a process that ends immediately afterwards,
     * and it never waits for the model. A draft that is already resolved or no longer
     * pending is cancelled instead of being queued, and a job that already produced a
     * prediction for the same question is left alone. The one case that is retried under
     * the same question is a job that ended failed or cancelled: a re-read of the delivery
     * is the app's honest recovery signal, so it re-arms that miss.
     */
    suspend fun admit(draftId: Long) {
        if (draftId <= 0L || closed) return
        admitCurrentQuestion(draftId, rearmSameQuestion = true)
    }

    /**
     * Reconsiders the queued work after an accepted command changed the draft's context.
     *
     * The command boundary calls this only after it stored an accepted change and its
     * commit succeeded; a redraw, a read, or a refused command never reaches it. A
     * confirmed or dismissed draft has its job cancelled at once; every context, rule,
     * catalog, or description change re-checks the jobs whose question may now differ.
     */
    suspend fun onAcceptedChange(change: WorkflowChange) {
        if (closed) return
        when (change) {
            is WorkflowChange.DraftConfirmed ->
                cancelForDraft(change.draftId, ClassificationWorkReason.DRAFT_REVIEWED)
            is WorkflowChange.DraftDismissed ->
                cancelForDraft(change.draftId, ClassificationWorkReason.DRAFT_REVIEWED)
            // The change names its draft, so no full scan is needed: a resolved draft has
            // its job cancelled before the model is asked, a changed description re-arms
            // it, and an unchanged one is left alone.
            is WorkflowChange.DraftReviewSaved ->
                admitCurrentQuestion(change.draftId, rearmSameQuestion = false)
            else -> if (change.affectsClassification()) reconcile()
        }
    }

    /** Stops admission and abandons waiting work; stored drafts and jobs are untouched. */
    override fun close() {
        if (closed) return
        closed = true
        worker.cancel()
        scope.cancel()
        wake.close()
    }

    // --- Admission ---------------------------------------------------------

    /**
     * Admits the draft's current question, or coalesces it into the job it already has.
     *
     * The question is computed from the state the draft is in now, so a burst of accepted
     * edits leaves exactly the newest question queued. The draft's own guards — pending
     * review, no user category, a description — are applied here as well as before the
     * model is asked, so a resolved draft is never queued and a later resolution cancels
     * the queued job.
     */
    private suspend fun admitCurrentQuestion(draftId: Long, rearmSameQuestion: Boolean) {
        val draft = try {
            drafts.findById(draftId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "classification admission could not read draft=$draftId")
            return
        } ?: run {
            cancelForDraft(draftId, ClassificationWorkReason.DRAFT_MISSING)
            return
        }
        when {
            draft.reviewStatus != IntakeReviewStatus.PENDING ->
                cancelForDraft(draftId, ClassificationWorkReason.DRAFT_REVIEWED)
            draft.reviewCategoryResolved ->
                cancelForDraft(draftId, ClassificationWorkReason.DRAFT_RESOLVED)
            else -> {
                val description = draft.classificationDescription()
                val effective = try {
                    snapshots.effectiveContextFor(draftId, description)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "classification admission could not read context for draft=$draftId")
                    null
                } ?: return
                admitQuestion(
                    draftId = draftId,
                    question = classificationQuestionDigest(
                        description = description,
                        context = effective.context,
                        dependencies = effective.dependencies,
                    ),
                    rearmSameQuestion = rearmSameQuestion,
                    freshSnapshotId = freshSnapshotId(draftId),
                )
            }
        }
    }

    /**
     * The stored prediction of the draft when it still answers the question the draft
     * poses now, or null.
     *
     * A prediction is fresh only while every dependency it was recorded against still
     * matches, and those dependencies include the description and the linked readings —
     * so a fresh prediction is the answer to the same question the queue would ask. A
     * re-read delivery, or a draft carried over from a build that did not keep durable
     * jobs, therefore reuses it instead of spending an inference on a known answer.
     */
    private suspend fun freshSnapshotId(draftId: Long): Long? = try {
        val fresh = snapshots.snapshotFor(draftId)?.takeIf { it.staleReason == null }
        if (fresh == null) null else snapshots.storedSnapshot(draftId)?.id
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w(TAG, "classification admission could not read the stored prediction for draft=$draftId")
        null
    }

    /**
     * Queues [question] for [draftId], coalescing with the job the draft already has.
     *
     * One transaction decides which of "insert", "replace the queued question", "update
     * the question a running attempt will run next", and "leave it alone" applies, so two
     * concurrent admissions cannot both insert a second job for one draft.
     */
    private suspend fun admitQuestion(
        draftId: Long,
        question: String,
        rearmSameQuestion: Boolean,
        freshSnapshotId: Long? = null,
    ) {
        val now = clock.nowEpochMillis()
        var queued = false
        database.withTransaction {
            val job = work.findForDraft(draftId)
            if (job == null) {
                if (isFull()) {
                    Log.w(TAG, "classification queue is full; draft=$draftId stays unclassified")
                    return@withTransaction
                }
                if (freshSnapshotId != null) {
                    // A fresh prediction is the answer to this very question (its
                    // dependencies cover the description and the readings), so the job is
                    // recorded as a result without asking the model again.
                    work.insert(
                        ClassificationWorkEntity(
                            draftId = draftId,
                            state = CLASSIFICATION_WORK_RESULT,
                            requestedQuestion = question,
                            executedQuestion = question,
                            attemptCount = 0,
                            snapshotId = freshSnapshotId,
                            statusReason = null,
                            ownerToken = null,
                            createdAtEpochMillis = now,
                            updatedAtEpochMillis = now,
                        ),
                    )
                    return@withTransaction
                }
                work.insert(
                    ClassificationWorkEntity(
                        draftId = draftId,
                        state = CLASSIFICATION_WORK_PENDING,
                        requestedQuestion = question,
                        executedQuestion = null,
                        attemptCount = 0,
                        snapshotId = null,
                        statusReason = null,
                        ownerToken = null,
                        createdAtEpochMillis = now,
                        updatedAtEpochMillis = now,
                    ),
                )
                queued = true
                return@withTransaction
            }
            when {
                job.requestedQuestion == question -> {
                    // The same question is already queued, running, or answered. Only a
                    // recorded miss is retried by an explicit re-report of the delivery.
                    if (rearmSameQuestion && job.state in RETRYABLE_STATES) {
                        queued = work.arm(job.id, question, now) == 1
                    }
                }
                job.state == CLASSIFICATION_WORK_RUNNING ->
                    work.retarget(job.id, CLASSIFICATION_WORK_RUNNING, question, now)
                else -> {
                    if (isFull()) {
                        Log.w(TAG, "classification queue is full; draft=$draftId stays unclassified")
                        return@withTransaction
                    }
                    queued = work.arm(job.id, question, now) == 1
                }
            }
        }
        if (queued) wake.trySend(Unit)
    }

    /** A bounded number of waiting jobs is the admission limit, not a limit on intake. */
    private suspend fun isFull(): Boolean =
        work.countInState(CLASSIFICATION_WORK_PENDING) >= maxQueued

    // --- Reconciliation ----------------------------------------------------

    /**
     * Re-checks every job that is no longer waiting against the question the draft poses
     * now, and re-arms the ones that differ.
     *
     * Only released jobs are visited: a pending job runs whatever the newest state is, and
     * a running job is covered by the attempt's own freshness check. A job whose question
     * did not change stays exactly as it is, which is what keeps a catalog or rule change
     * from re-asking the model for drafts it cannot affect.
     */
    private suspend fun reconcile() {
        var afterId = 0L
        while (true) {
            val ids = try {
                work.idsInStatesAfter(RECONCILED_STATES, afterId, RECONCILE_PAGE)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "classification work could not be reconciled")
                return
            }
            if (ids.isEmpty()) return
            for (id in ids) {
                afterId = id
                val job = work.findById(id) ?: continue
                admitCurrentQuestion(job.draftId, rearmSameQuestion = false)
            }
            if (ids.size < RECONCILE_PAGE) return
        }
    }

    // --- The worker --------------------------------------------------------

    /**
     * Runs one claimed job.
     *
     * @return true when the job asked to be tried again shortly, so the worker backs off
     * instead of spinning on a question another live attempt holds.
     */
    private suspend fun runAttempt(job: ClassificationWorkEntity): Boolean {
        val token = UUID.randomUUID().toString()
        val claimed = try {
            work.claim(job.id, token, clock.nowEpochMillis())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "classification job could not be claimed (${error.javaClass.simpleName})")
            0
        }
        if (claimed != 1) return false
        return try {
            classify(job.id, token)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // The draft is already stored and reviewable; a failed attempt is recorded as
            // the miss it is and never turns into a claim about the draft.
            Log.w(TAG, "classification attempt failed (${error.javaClass.simpleName})")
            settle(job.id, token, CLASSIFICATION_WORK_FAILED, null, ClassificationWorkReason.MODEL_OR_INFERENCE_FAILED)
            false
        }
    }

    /** Runs one claimed job against the draft's current state. */
    private suspend fun classify(jobId: Long, token: String): Boolean {
        val job = work.findById(jobId) ?: return false
        val draft = drafts.findById(job.draftId)
            ?: return cancel(jobId, token, ClassificationWorkReason.DRAFT_MISSING)
        when {
            draft.reviewStatus != IntakeReviewStatus.PENDING ->
                return cancel(jobId, token, ClassificationWorkReason.DRAFT_REVIEWED)
            draft.reviewCategoryResolved ->
                return cancel(jobId, token, ClassificationWorkReason.DRAFT_RESOLVED)
        }
        val description = draft.classificationDescription()
        if (description.isBlank()) {
            return cancel(jobId, token, ClassificationWorkReason.DESCRIPTION_MISSING)
        }
        // The question is recomputed from what is stored now, not from what the row held
        // when it was admitted: the durable queue may have waited while the user edited.
        val effective = snapshots.effectiveContextFor(draft.id, description)
            ?: return cancel(jobId, token, ClassificationWorkReason.DRAFT_MISSING)
        val question = classificationQuestionDigest(
            description = description,
            context = effective.context,
            dependencies = effective.dependencies,
        )
        if (work.markExecuting(jobId, token, question) != 1) return false
        // A fresh key per attempt: the attempt's own token distinguishes it from an
        // earlier attempt at the same question, even when a link, a wording, or a catalog
        // change moved the question away and back again. An attempt that ended with the
        // process is still cleaned up with the token its row carries, so nothing stays
        // reserved and a reverted question is never answered from an older record.
        val key = classificationRequestKey(
            draftId = draft.id,
            question = question,
            attempt = job.attemptCount,
            ownerToken = token,
        )
        Log.i(
            TAG,
            "intake classification draft=${draft.id} scheduled question=${question.take(QUESTION_LOG_CHARS)}",
        )
        val outcome = commands.requestClassification(
            scope = WorkflowCallerScope.User,
            request = ClassificationRequest(
                idempotencyKey = key,
                draftId = draft.id,
                description = description,
                expectedReviewRevision = draft.reviewRevision,
            ),
        )
        Log.i(TAG, "intake classification draft=${draft.id} outcome=${outcome.logName()}")
        return settleOutcome(jobId, token, question, key, outcome)
    }

    /**
     * Records what one attempt reached, in the same transaction that checks the attempt
     * still owns the job.
     *
     * A newer question that arrived while the model was working requeues the newer one;
     * a context that moved on requeues within the attempt budget; a user decision or a
     * missing description cancels; a missing model records the miss. The transitions are
     * conditional on the attempt's own token, so a job cancelled or re-armed while the
     * model worked cannot be overwritten by this attempt's late answer.
     */
    private suspend fun settleOutcome(
        jobId: Long,
        token: String,
        question: String,
        key: String,
        outcome: WorkflowCommandOutcome,
    ): Boolean {
        val now = clock.nowEpochMillis()
        var retry = false
        database.withTransaction {
            val job = work.findById(jobId) ?: return@withTransaction
            if (job.state != CLASSIFICATION_WORK_RUNNING || job.ownerToken != token) {
                return@withTransaction
            }
            val newerQuestion = job.requestedQuestion != question
            fun retryOrSettle(reason: String): JobTransition =
                when {
                    newerQuestion -> JobTransition.Requeue(attempts = 0, reason = reason)
                    job.attemptCount < MAX_ATTEMPTS ->
                        JobTransition.Requeue(attempts = job.attemptCount, reason = reason)
                    else -> JobTransition.Settle(CLASSIFICATION_WORK_FAILED, null, reason)
                }
            val transition: JobTransition = when (outcome) {
                is WorkflowCommandOutcome.Applied -> when (val change = outcome.change) {
                    is WorkflowChange.Classified -> when {
                        newerQuestion -> JobTransition.Requeue(0, ClassificationWorkReason.CONTEXT_CHANGED)
                        change.snapshotId > 0L -> JobTransition.Settle(
                            CLASSIFICATION_WORK_RESULT,
                            change.snapshotId,
                            null,
                        )
                        else -> JobTransition.Settle(
                            CLASSIFICATION_WORK_FAILED,
                            null,
                            ClassificationWorkReason.NOT_RECORDED,
                        )
                    }
                    else -> JobTransition.Settle(
                        CLASSIFICATION_WORK_FAILED,
                        null,
                        ClassificationWorkReason.NOT_RECORDED,
                    )
                }
                is WorkflowCommandOutcome.NoChange -> when (val change = outcome.change) {
                    is WorkflowChange.Classified -> when {
                        newerQuestion -> JobTransition.Requeue(0, ClassificationWorkReason.CONTEXT_CHANGED)
                        change.snapshotId > 0L -> JobTransition.Settle(
                            CLASSIFICATION_WORK_RESULT,
                            change.snapshotId,
                            null,
                        )
                        // The user's own category choice stands: the job is settled, not
                        // retried, and the choice is left exactly as the user made it.
                        else -> JobTransition.Settle(
                            CLASSIFICATION_WORK_CANCELLED,
                            null,
                            ClassificationWorkReason.DRAFT_RESOLVED,
                        )
                    }
                    null -> if (newerQuestion) {
                        JobTransition.Requeue(0, ClassificationWorkReason.CONTEXT_CHANGED)
                    } else {
                        JobTransition.Settle(CLASSIFICATION_WORK_RESULT, null, null)
                    }
                    else -> JobTransition.Settle(CLASSIFICATION_WORK_RESULT, null, null)
                }
                is WorkflowCommandOutcome.NeedsClarification ->
                    JobTransition.Settle(
                        CLASSIFICATION_WORK_CANCELLED,
                        null,
                        ClassificationWorkReason.DESCRIPTION_MISSING,
                    )
                is WorkflowCommandOutcome.Conflict ->
                    retryOrSettle(ClassificationWorkReason.CONTEXT_CHANGED)
                WorkflowCommandOutcome.Cancelled -> retryOrSettle(ClassificationWorkReason.SUPERSEDED)
                WorkflowCommandOutcome.NotFound ->
                    JobTransition.Settle(
                        CLASSIFICATION_WORK_CANCELLED,
                        null,
                        ClassificationWorkReason.DRAFT_MISSING,
                    )
                is WorkflowCommandOutcome.Denied -> when {
                    newerQuestion -> JobTransition.Requeue(0, ClassificationWorkReason.CONTEXT_CHANGED)
                    else -> JobTransition.Settle(
                        CLASSIFICATION_WORK_CANCELLED,
                        null,
                        ClassificationWorkReason.DENIED,
                    )
                }
                is WorkflowCommandOutcome.Failed -> when {
                    newerQuestion -> JobTransition.Requeue(
                        0,
                        ClassificationWorkReason.MODEL_OR_INFERENCE_FAILED,
                    )
                    // Another live attempt in this process holds the question: the job is
                    // not failed for a race, it waits for that attempt to finish.
                    WorkflowClaimGuard.isHeld(key) ->
                        retryOrSettle(ClassificationWorkReason.SUPERSEDED)
                    else -> JobTransition.Settle(
                        CLASSIFICATION_WORK_FAILED,
                        null,
                        ClassificationWorkReason.MODEL_OR_INFERENCE_FAILED,
                    )
                }
            }
            when (transition) {
                is JobTransition.Requeue -> {
                    work.requeue(job.id, token, transition.attempts, transition.reason, now)
                    retry = true
                }
                is JobTransition.Settle -> work.settle(
                    job.id,
                    token,
                    transition.state,
                    transition.snapshotId,
                    transition.reason,
                    now,
                )
            }
        }
        return retry
    }

    /** Ends one running job under its own claim. */
    private suspend fun cancel(jobId: Long, token: String, reason: String): Boolean {
        settle(jobId, token, CLASSIFICATION_WORK_CANCELLED, null, reason)
        return false
    }

    /** Ends a draft's job outside an attempt, for decisions the worker must not override. */
    private suspend fun cancelForDraft(draftId: Long, reason: String) {
        try {
            val changed = database.withTransaction {
                work.cancelForDraft(draftId, reason, clock.nowEpochMillis())
            }
            if (changed > 0) {
                Log.i(TAG, "classification cancelled: draft=$draftId reason=$reason")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "classification cancellation could not be stored for draft=$draftId")
        }
    }

    private suspend fun settle(
        jobId: Long,
        token: String,
        state: String,
        snapshotId: Long?,
        reason: String?,
    ) {
        try {
            work.settle(jobId, token, state, snapshotId, reason, clock.nowEpochMillis())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "classification state could not be stored (${error.javaClass.simpleName})")
        }
    }

    /**
     * Returns the jobs an interrupted process left running to the durable queue.
     *
     * A running row belongs to an attempt that wrote it before asking the model, and only
     * that attempt may finish it. When this queue starts, no such attempt exists in the
     * process, so every running row is resumed before the worker claims anything, and the
     * durable claim of the attempt that died is dropped so no request stays reserved by a
     * process that is gone.
     */
    private suspend fun resumeInterruptedWork() {
        try {
            val interrupted = work.findInState(CLASSIFICATION_WORK_RUNNING, RESUME_PAGE)
            for (job in interrupted) {
                val question = job.executedQuestion ?: continue
                val ownerToken = job.ownerToken ?: continue
                commands.abandonInterruptedClassification(
                    classificationRequestKey(
                        draftId = job.draftId,
                        question = question,
                        attempt = job.attemptCount,
                        ownerToken = ownerToken,
                    ),
                )
            }
            val resumed = work.resumeInterrupted(clock.nowEpochMillis())
            if (resumed > 0) {
                Log.i(TAG, "resumed $resumed interrupted classification job(s)")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "interrupted classification work could not be resumed")
        }
    }

    /** One settled or requeued result of an attempt. */
    private sealed interface JobTransition {
        data class Settle(
            val state: String,
            val snapshotId: Long?,
            val reason: String?,
        ) : JobTransition

        data class Requeue(val attempts: Int, val reason: String?) : JobTransition
    }

    private companion object {
        const val TAG = "BankDraftClassification"

        /**
         * How many drafts may wait for classification at once.
         *
         * A bound on admitted work, not on intake: each job is one small row and the one
         * worker drains them in order, so this is far above any real burst. A queue that
         * somehow filled leaves the drafts beyond it visible as unclassified — never
         * guessed and never dropped from review — and the next accepted change or a
         * re-read delivery re-arms them.
         */
        const val MAX_QUEUED = 64

        /**
         * How many times one question may be retried after a context change.
         *
         * A new question gets a fresh budget; this only bounds a draft whose context
         * moves on under every attempt, so the model cannot be asked without end.
         */
        const val MAX_ATTEMPTS = 3

        /** How many jobs one reconciliation page reads. */
        const val RECONCILE_PAGE = 64

        /** How many interrupted running jobs one start-up recovery reads. */
        const val RESUME_PAGE = 64

        /** How long the worker waits before retrying a question another attempt holds. */
        const val RETRY_BACKOFF_MILLIS = 250L

        /**
         * How much of a question digest a log line names.
         *
         * The digest identifies the question for an operator comparing a scheduling line
         * with the stored job; it is one-way and carries none of the text it was made
         * from.
         */
        const val QUESTION_LOG_CHARS = 12

        /** States a re-read delivery may re-arm: a recorded miss, not a queued or answered job. */
        val RETRYABLE_STATES = listOf(CLASSIFICATION_WORK_FAILED, CLASSIFICATION_WORK_CANCELLED)

        /** States reconciliation re-checks: waiting jobs already run whatever the newest state is. */
        val RECONCILED_STATES = listOf(
            CLASSIFICATION_WORK_RUNNING,
            CLASSIFICATION_WORK_RESULT,
            CLASSIFICATION_WORK_FAILED,
            CLASSIFICATION_WORK_CANCELLED,
        )
    }
}

/**
 * The idempotency key of one attempt at one classification question.
 *
 * The key names the question, the attempt, and the attempt's own owner token. The token is
 * what makes it per attempt rather than per question count: a question that moves away and
 * back again (a link proposed and then unlinked, for example) is a new attempt with a new
 * key, so a later answer is never answered from a record an earlier, different state wrote.
 * An attempt whose process ended still leaves its token on the running row, so the next
 * process can name and release exactly that claim.
 */
internal fun classificationRequestKey(
    draftId: Long,
    question: String,
    attempt: Int,
    ownerToken: String,
): String = canonicalDependencyDigest(
    listOf(
        "INTAKE_CLASSIFICATION",
        draftId.toString(),
        question,
        attempt.toString(),
        ownerToken,
    ),
)

/**
 * Whether an accepted change can alter a classification question.
 *
 * A change that does not touch the description, the linked readings, the category
 * catalog, or the remembered rules cannot change what the model would be asked, so it
 * never schedules work. The list is deliberately explicit: a new accepted change has to
 * be classified here before it may re-open a job.
 */
private fun WorkflowChange.affectsClassification(): Boolean = when (this) {
    is WorkflowChange.CaptureRevised,
    is WorkflowChange.CaptureDeleted,
    is WorkflowChange.EventStored,
    is WorkflowChange.EventRevised,
    is WorkflowChange.LinkDecided,
    is WorkflowChange.LinkUnlinked,
    is WorkflowChange.CategoryAdded,
    is WorkflowChange.CategoryRenamed,
    is WorkflowChange.CategoryArchived,
    is WorkflowChange.CategoryDeleted,
    is WorkflowChange.CategoryRuleRemembered,
    is WorkflowChange.CategoryRuleRemoved,
    -> true
    else -> false
}

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
