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
 * RESTRICT on delete, so a category rows still point at cannot be removed behind their
 * backs. A permanent category deletion therefore clears those links first, inside the same
 * transaction, and only then deletes the category: the record keeps its id, amount, type,
 * moment, description, confirmation, and provenance, and simply becomes uncategorized.
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
