package io.github.triet4p.aegisf.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes [PendingBankDraftEntity] rows.
 *
 * Intake stores each permitted delivery through [store] as a pending row, or returns the row
 * already stored for that delivery and evidence; it never rewrites the stored values. A replayed
 * callback or re-read post with the same evidence writes nothing, while a changed statement or a
 * later post under a reused key becomes its own pending row. Review overrides and terminal
 * transitions are owned by [BankDraftReviewDao] and leave the delivery/evidence identity intact.
 * The uniqueness is a table constraint, so concurrent callbacks for one delivery still end with
 * one row and the enclosing intake transaction keeps the possible-duplicate lookup consistent
 * with the insert it decides.
 *
 * Rows are read newest first for review and are recovered wholesale after a process restart.
 * Drafts never enter the ledger's transaction streams: confirmation is explicit, and this
 * intake DAO never joins drafts into ledger totals.
 */
@Dao
abstract class PendingBankDraftDao {

    /**
     * Stores [draft] as one pending draft, or returns the draft already stored for the same
     * delivery and the same evidence.
     *
     * The decision is made inside one database transaction:
     *
     * 1. an already-stored (deliveryId, evidenceDigest) pair is that delivery's draft — the
     *    callback was replayed or the same post was re-read — so its id is returned and nothing
     *    is written, whatever review state it holds;
     * 2. otherwise the row is inserted with [PendingBankDraftEntity.possibleDuplicateOfDraftId]
     *    pointing at the earliest stored draft with the same evidence, if there is one, because
     *    identical evidence under a different delivery is an uncertain duplicate rather than a
     *    proven one;
     * 3. if the unique index still refuses the insert — a concurrent write stored the same
     *    delivery and evidence between the lookup and the insert — the row that won is returned.
     *
     * A refusal that leaves no stored row would mean the identity constraint and this decision no
     * longer agree; that fails loudly instead of quietly storing a second draft.
     */
    @Transaction
    open suspend fun store(draft: PendingBankDraftEntity): Long {
        val alreadyStored = storedDraftId(draft.deliveryId, draft.evidenceDigest)
        if (alreadyStored != null) {
            return alreadyStored
        }
        val possibleDuplicate = earliestDraftWithEvidence(draft.evidenceDigest)
        val inserted = insertIgnoringStoredIdentity(
            if (possibleDuplicate == null) {
                draft
            } else {
                draft.copy(possibleDuplicateOfDraftId = possibleDuplicate)
            },
        )
        if (inserted != INSERT_REFUSED) {
            return inserted
        }
        return storedDraftId(draft.deliveryId, draft.evidenceDigest)
            ?: throw IllegalStateException(
                "draft ${draft.deliveryId} was refused by the delivery/evidence index " +
                    "but is not stored",
            )
    }

    /** The id of the draft this delivery already stored with this evidence, or null. */
    @Query(
        "SELECT `id` FROM `pending_bank_drafts` " +
            "WHERE `deliveryId` = :deliveryId AND `evidenceDigest` = :evidenceDigest LIMIT 1",
    )
    protected abstract suspend fun storedDraftId(deliveryId: String, evidenceDigest: String): Long?

    /** The earliest stored draft stating exactly this evidence, or null when there is none. */
    @Query("SELECT MIN(`id`) FROM `pending_bank_drafts` WHERE `evidenceDigest` = :evidenceDigest")
    protected abstract suspend fun earliestDraftWithEvidence(evidenceDigest: String): Long?

    /**
     * Inserts [draft], leaving every stored row unchanged, and returns the new row's id — or
     * [INSERT_REFUSED] when the delivery/evidence constraint already holds that identity.
     *
     * Deliberately never [OnConflictStrategy.REPLACE]: overwriting would rewrite a draft the
     * user has already confirmed or dismissed, and would let a reused notification key erase
     * the earlier statement instead of keeping both.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIgnoringStoredIdentity(draft: PendingBankDraftEntity): Long

    @Query("SELECT * FROM pending_bank_drafts WHERE id = :id")
    abstract suspend fun findById(id: Long): PendingBankDraftEntity?

    @Query("SELECT * FROM pending_bank_drafts ORDER BY createdAtEpochMillis DESC, id DESC")
    abstract suspend fun findAll(): List<PendingBankDraftEntity>

    /**
     * The same rows as [findAll], but as a live stream: every insert re-emits the current
     * rows in the same newest-first order, so a review screen recovers persisted drafts
     * after a process restart instead of starting from an empty list.
     */
    @Query("SELECT * FROM pending_bank_drafts ORDER BY createdAtEpochMillis DESC, id DESC")
    abstract fun observeAll(): Flow<List<PendingBankDraftEntity>>
}

/** What an ignored insert returns: the constraint kept the row that was already stored. */
private const val INSERT_REFUSED = -1L
