package io.github.triet4p.aegisf.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes the durable classification jobs.
 *
 * Every transition is a conditional single statement, so two callers cannot both move
 * one job: a claim moves only a pending row, a completion moves only the row that still
 * carries the attempt's own token, and re-arming a running row changes only its
 * requested question. The transitions are the state machine, and no caller can write an
 * arbitrary state through them.
 *
 * The draft id is unique, so "one logical job per draft" is a table constraint and not a
 * convention the queue has to remember.
 */
@Dao
abstract class ClassificationWorkDao {

    /** The job of one draft, or null when the draft was never admitted. */
    @Query("SELECT * FROM `classification_work` WHERE `draftId` = :draftId LIMIT 1")
    abstract suspend fun findForDraft(draftId: Long): ClassificationWorkEntity?

    /**
     * The job of one draft as a live stream.
     *
     * The review detail follows its own draft's job, so a queued job becomes busy and a busy
     * one becomes a result without the screen polling or re-reading an unrelated draft.
     */
    @Query("SELECT * FROM `classification_work` WHERE `draftId` = :draftId LIMIT 1")
    abstract fun observeForDraft(draftId: Long): Flow<ClassificationWorkEntity?>

    /**
     * The jobs of a bounded set of drafts as a live stream.
     *
     * The inbox reads exactly the drafts on its shown page, so the status of a card follows
     * the stored job while the page's ordering stays the snapshot the user read.
     */
    @Query(
        "SELECT * FROM `classification_work` WHERE `draftId` IN (:draftIds) " +
            "ORDER BY `draftId` ASC",
    )
    abstract fun observeForDrafts(draftIds: List<Long>): Flow<List<ClassificationWorkEntity>>

    /** One job by its stored id. */
    @Query("SELECT * FROM `classification_work` WHERE `id` = :id")
    abstract suspend fun findById(id: Long): ClassificationWorkEntity?

    /**
     * The job the worker runs next: the oldest pending question first.
     *
     * Ordering by the last update keeps an older waiting draft ahead of a newer one and
     * lets a re-armed job take its place in the queue instead of jumping in front of the
     * work already waiting.
     */
    @Query(
        "SELECT * FROM `classification_work` WHERE `state` = :state " +
            "ORDER BY `updatedAtEpochMillis` ASC, `id` ASC LIMIT 1",
    )
    abstract suspend fun oldestInState(state: String): ClassificationWorkEntity?

    /**
     * A bounded keyset page of jobs in one set of states, for reconciliation.
     *
     * The keyset is the stored id, so re-arming a row mid-page cannot shift the page and
     * skip the rows after it.
     */
    @Query(
        "SELECT `id` FROM `classification_work` WHERE `state` IN (:states) AND `id` > :afterId " +
            "ORDER BY `id` ASC LIMIT :limit",
    )
    abstract suspend fun idsInStatesAfter(
        states: List<String>,
        afterId: Long,
        limit: Int,
    ): List<Long>

    /** How many jobs are in one state, for the admission bound. */
    @Query("SELECT COUNT(*) FROM `classification_work` WHERE `state` = :state")
    abstract suspend fun countInState(state: String): Long

    /** A bounded read of every job in one state, oldest update first. */
    @Query(
        "SELECT * FROM `classification_work` WHERE `state` = :state " +
            "ORDER BY `updatedAtEpochMillis` ASC, `id` ASC LIMIT :limit",
    )
    abstract suspend fun findInState(state: String, limit: Int): List<ClassificationWorkEntity>

    /** Records a new job; the unique draft identity refuses a second job for one draft. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(work: ClassificationWorkEntity): Long

    /**
     * Queues a newer question for an existing job, resetting that question's attempt
     * budget and any earlier outcome.
     *
     * The row stays one row: an older queued question is replaced, never added to.
     */
    @Query(
        "UPDATE `classification_work` SET `state` = '$CLASSIFICATION_WORK_PENDING', " +
            "`requestedQuestion` = :question, `attemptCount` = 0, `statusReason` = NULL, " +
            "`ownerToken` = NULL, `updatedAtEpochMillis` = :now WHERE `id` = :id",
    )
    abstract suspend fun arm(id: Long, question: String, now: Long): Int

    /**
     * Replaces the requested question of a job that is already running.
     *
     * The running attempt keeps its claim and finishes its own question, and the newer
     * question runs next; the row is not reset to pending, so nothing is lost if the
     * process ends between the two.
     */
    @Query(
        "UPDATE `classification_work` SET `requestedQuestion` = :question, " +
            "`updatedAtEpochMillis` = :now WHERE `id` = :id AND `state` = :expectedState",
    )
    abstract suspend fun retarget(
        id: Long,
        expectedState: String,
        question: String,
        now: Long,
    ): Int

    /**
     * Claims the pending job for one attempt.
     *
     * The claim moves only a pending row, so a job that was cancelled or already claimed
     * is not taken, and the attempt count is incremented in the same statement that
     * makes the attempt exclusive.
     */
    @Query(
        "UPDATE `classification_work` SET `state` = '$CLASSIFICATION_WORK_RUNNING', " +
            "`ownerToken` = :token, `attemptCount` = `attemptCount` + 1, " +
            "`statusReason` = NULL, `updatedAtEpochMillis` = :now " +
            "WHERE `id` = :id AND `state` = '$CLASSIFICATION_WORK_PENDING'",
    )
    abstract suspend fun claim(id: Long, token: String, now: Long): Int

    /**
     * Records the question this attempt is really asking, once it is known.
     *
     * The attempt reads the current state, so its question is by construction the newest
     * one it could ask; the requested question is aligned with it in the same statement.
     * Without that alignment a job admitted under an older state (or carried over from a
     * previous build) would look permanently out of date to its own completion and requeue
     * itself without end. A newer change that arrives from now on updates only the
     * requested question, and the attempt's own dependency check refuses its stale answer.
     */
    @Query(
        "UPDATE `classification_work` SET `executedQuestion` = :question, " +
            "`requestedQuestion` = :question " +
            "WHERE `id` = :id AND `state` = '$CLASSIFICATION_WORK_RUNNING' " +
            "AND `ownerToken` = :token",
    )
    abstract suspend fun markExecuting(id: Long, token: String, question: String): Int

    /**
     * Finishes the attempt with a terminal state.
     *
     * The row moves only when it still carries this attempt's token, so an attempt whose
     * job was cancelled or re-armed cannot write its late outcome over the newer state.
     */
    @Query(
        "UPDATE `classification_work` SET `state` = :state, `snapshotId` = :snapshotId, " +
            "`statusReason` = :reason, `ownerToken` = NULL, `updatedAtEpochMillis` = :now " +
            "WHERE `id` = :id AND `state` = '$CLASSIFICATION_WORK_RUNNING' " +
            "AND `ownerToken` = :token",
    )
    abstract suspend fun settle(
        id: Long,
        token: String,
        state: String,
        snapshotId: Long?,
        reason: String?,
        now: Long,
    ): Int

    /**
     * Puts the attempt's job back in the queue without losing it.
     *
     * [attempts] carries the retry budget: the same number keeps the budget of one
     * question, and zero gives a newer question its own budget.
     */
    @Query(
        "UPDATE `classification_work` SET `state` = '$CLASSIFICATION_WORK_PENDING', " +
            "`attemptCount` = :attempts, `statusReason` = :reason, `ownerToken` = NULL, " +
            "`updatedAtEpochMillis` = :now WHERE `id` = :id " +
            "AND `state` = '$CLASSIFICATION_WORK_RUNNING' AND `ownerToken` = :token",
    )
    abstract suspend fun requeue(
        id: Long,
        token: String,
        attempts: Int,
        reason: String?,
        now: Long,
    ): Int

    /**
     * Returns every interrupted running job to the queue.
     *
     * A running row is written before the model is asked and finished only by the
     * attempt that wrote it, so after a process ends every running row belongs to an
     * attempt that no longer exists. The next process resumes those jobs instead of
     * leaving the drafts unclassified.
     */
    @Query(
        "UPDATE `classification_work` SET `state` = '$CLASSIFICATION_WORK_PENDING', " +
            "`ownerToken` = NULL, `updatedAtEpochMillis` = :now " +
            "WHERE `state` = '$CLASSIFICATION_WORK_RUNNING'",
    )
    abstract suspend fun resumeInterrupted(now: Long): Int

    /**
     * Ends the job of a draft that no longer needs classification.
     *
     * Used when the user resolves, confirms, or dismisses the draft: the stored result is
     * kept as evidence, and nothing further is asked for it.
     */
    @Query(
        "UPDATE `classification_work` SET `state` = '$CLASSIFICATION_WORK_CANCELLED', " +
            "`statusReason` = :reason, `ownerToken` = NULL, `updatedAtEpochMillis` = :now " +
            "WHERE `draftId` = :draftId",
    )
    abstract suspend fun cancelForDraft(draftId: Long, reason: String, now: Long): Int

    /**
     * Ends a draft's waiting or running job because the user cancelled it.
     *
     * Only a job that is still waiting or running is moved: a stored result and an already
     * settled miss are not "cancelled" by a late tap, so the recorded outcome keeps its own
     * meaning. The owner token is cleared, so a late attempt under the old token cannot write
     * its state over the cancellation.
     */
    @Query(
        "UPDATE `classification_work` SET `state` = '$CLASSIFICATION_WORK_CANCELLED', " +
            "`statusReason` = :reason, `ownerToken` = NULL, `updatedAtEpochMillis` = :now " +
            "WHERE `draftId` = :draftId " +
            "AND `state` IN ('$CLASSIFICATION_WORK_PENDING', '$CLASSIFICATION_WORK_RUNNING')",
    )
    abstract suspend fun cancelActiveForDraft(draftId: Long, reason: String, now: Long): Int
}
