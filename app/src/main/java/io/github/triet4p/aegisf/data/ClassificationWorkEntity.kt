package io.github.triet4p.aegisf.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.triet4p.aegisf.data.laya.LayaEffectiveContext

/**
 * One durable classification job for one bank draft.
 *
 * The row is the job, not a copy of it: a draft has at most one classification question
 * in flight, so a newer request for the same draft updates this row instead of adding a
 * second job. That is what coalesces a burst of edits — the newest question is the one
 * the row carries, and the older question is not asked at all once it has been replaced
 * before the worker picked it up.
 *
 * The row is also what makes the work resumable. [state] is written before and after the
 * expensive part, so a process that ends mid-work leaves a truthfully running row behind
 * and the next process resumes it instead of losing the draft's classification or asking
 * the same question twice. The states form one fixed lifecycle:
 *
 * - [CLASSIFICATION_WORK_PENDING]: admitted, waiting for the one worker.
 * - [CLASSIFICATION_WORK_RUNNING]: the worker claimed it and is asking the model.
 * - [CLASSIFICATION_WORK_RESULT]: a prediction was stored for the question in
 *   [executedQuestion]; [snapshotId] names it when the command recorded one.
 * - [CLASSIFICATION_WORK_CANCELLED]: nothing is asked or asked again for this question —
 *   the draft was resolved, reviewed, deleted, or has no text to classify.
 * - [CLASSIFICATION_WORK_FAILED]: the attempt ended without a prediction (a missing or
 *   unusable model, or an inference that failed). The row stays as the durable record of
 *   the miss until a relevant change or a new intake report re-arms it.
 *
 * Nothing here carries text: the question is a digest, the reason is a code, and the only
 * identifiers are the draft, the snapshot, and the attempt's own token. The bank memo, the
 * user's wording, and the model's input never reach this table.
 *
 * The row is internal to the app module: [BankDraftClassificationQueue] is its only
 * writer, and the command boundary remains the only writer of predictions.
 */
@Entity(
    tableName = "classification_work",
    indices = [
        Index(value = ["draftId"], unique = true),
        Index(value = ["state", "updatedAtEpochMillis"]),
    ],
)
data class ClassificationWorkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    /** The draft this job classifies. Unique: one logical job per draft. */
    val draftId: Long,
    /** The lifecycle state; one of the `CLASSIFICATION_WORK_*` values. */
    val state: String,
    /**
     * The newest question the app has decided to ask, as the digest of everything that
     * decides the answer: the description, the event readings, and the catalog, rule,
     * model, and candidate-set revisions.
     *
     * A newer question replaces this value in place, which is the coalescing rule. The
     * same question is the same job, whatever number of notifications or edits named it.
     */
    val requestedQuestion: String,
    /** The question the current or last attempt actually asked, or null before the first. */
    val executedQuestion: String?,
    /**
     * How many consecutive attempts ran for the current question.
     *
     * A new question resets it, so a user who keeps editing is not failed for the edits
     * of an earlier question. It bounds retries of one question so a context that moves
     * on under every attempt cannot spin the model forever.
     */
    val attemptCount: Int,
    /** The stored prediction this job produced, or null when it produced none. */
    val snapshotId: Long?,
    /** Why the job is cancelled or failed, as a privacy-safe code, or null. */
    val statusReason: String?,
    /**
     * The token of the attempt that holds a running job, or null.
     *
     * Every transition of a running job is conditional on this token, so an attempt whose
     * job was re-armed or cancelled can never write its late state over the newer one.
     */
    val ownerToken: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

/** Waiting for the one classification worker. */
internal const val CLASSIFICATION_WORK_PENDING = "PENDING"

/** Claimed by the worker; the model is being asked. */
internal const val CLASSIFICATION_WORK_RUNNING = "RUNNING"

/** A prediction was stored for [ClassificationWorkEntity.executedQuestion]. */
internal const val CLASSIFICATION_WORK_RESULT = "RESULT"

/** Nothing more is asked for this question until a relevant change re-arms the job. */
internal const val CLASSIFICATION_WORK_CANCELLED = "CANCELLED"

/** The attempt ended without a prediction; the miss is recorded, not retried automatically. */
internal const val CLASSIFICATION_WORK_FAILED = "FAILED"

/**
 * The privacy-safe codes a job's [ClassificationWorkEntity.statusReason] can hold.
 *
 * A code says which rule ended the job, never what the draft or the model said.
 */
internal object ClassificationWorkReason {
    /** The draft has no description to classify (blank memo, or blocked text). */
    const val DESCRIPTION_MISSING = "DESCRIPTION_MISSING"

    /** The user already chose the draft's category; the choice stands. */
    const val DRAFT_RESOLVED = "DRAFT_RESOLVED"

    /** The draft was confirmed or dismissed; there is nothing left to classify. */
    const val DRAFT_REVIEWED = "DRAFT_REVIEWED"

    /** The draft no longer exists. */
    const val DRAFT_MISSING = "DRAFT_MISSING"

    /** The request was refused by the command boundary's rules. */
    const val DENIED = "DENIED"

    /** Another live attempt in this process holds the question; the job waits. */
    const val SUPERSEDED = "SUPERSEDED"

    /** The context moved on while the model was asked; the newer question is queued. */
    const val CONTEXT_CHANGED = "CONTEXT_CHANGED"

    /** The model was unavailable or the inference itself failed. */
    const val MODEL_OR_INFERENCE_FAILED = "MODEL_OR_INFERENCE_FAILED"

    /** The command recorded a classification without a stored prediction. */
    const val NOT_RECORDED = "NOT_RECORDED"

    /** The user explicitly cancelled this draft's waiting or running check. */
    const val USER_CANCELLED = "USER_CANCELLED"
}

/**
 * The description a classification of [draft] is about.
 *
 * It is the same working description the review form opens with: the user's own wording
 * when they corrected it, the permitted bank memo otherwise. The bank memo and a review
 * note are never concatenated.
 */
internal fun PendingBankDraftEntity.classificationDescription(): String =
    if (reviewMemoEdited) reviewMemo.orEmpty() else memo.orEmpty()

/**
 * The identity of one classification question.
 *
 * Two questions are the same question exactly when the model would be offered the same
 * input and the same answer space: the description, the linked event readings, and the
 * category catalog, remembered-rule, model, and candidate-set revisions. The draft's own
 * revision is deliberately not part of it: correcting an amount changes the draft, but it
 * does not change what the model is asked, so it never schedules a redundant inference.
 */
internal fun classificationQuestionDigest(
    description: String,
    context: LayaEffectiveContext,
    dependencies: PredictionDependencies,
): String = canonicalDependencyDigest(
    buildList {
        add("CLASSIFICATION_QUESTION")
        add(description.trim())
        add(dependencies.categoryRevision)
        add(dependencies.ruleRevision)
        add(dependencies.modelRevision)
        add(dependencies.candidateSetVersion)
        for (reading in context.eventReadings) {
            add(reading.eventId.toString())
            add(reading.text)
        }
    },
)
