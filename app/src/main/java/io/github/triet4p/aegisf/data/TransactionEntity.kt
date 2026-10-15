package io.github.triet4p.aegisf.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One ledger record: money leaving, money arriving, or money moving between the user's own
 * holdings.
 *
 * The amount is [amountVnd], a positive whole-dong magnitude of at least 1. No sign is
 * stored: [type] supplies the direction. [categoryId] may be null because an unknown
 * category must not prevent saving a record, and it references a [CategoryEntity] with
 * RESTRICT on delete — a category that transactions still refer to cannot be deleted,
 * which keeps the historical link instead of silently detaching it.
 */
@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["categoryId"])],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val type: TransactionType,
    val amountVnd: Long,
    val occurredAtEpochMillis: Long,
    val description: String,
    val categoryId: Long? = null,
    val confirmation: ConfirmationStatus = ConfirmationStatus.CONFIRMED,
    val source: RecordSource = RecordSource.MANUAL,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)
