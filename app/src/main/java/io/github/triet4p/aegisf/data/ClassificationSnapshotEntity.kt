package io.github.triet4p.aegisf.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.security.MessageDigest

/**
 * One historical prompt-time prediction about one bank draft.
 *
 * The row is retained because it is the owner's stored provenance of how drafts were
 * classified before the hosted category cutover. Nothing in the active application writes
 * it, reads it for a decision, or treats its decision source as a hosted choice: a hosted
 * category is stored on the draft's own review state, never here. Keeping the table (and
 * this schema) means an existing populated database upgrades without losing the rows, and
 * the frozen S8.13 evidence still corresponds to the data it measured.
 *
 * The composed model input itself was never stored: only its digest.
 */
@Entity(
    tableName = "classification_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = PendingBankDraftEntity::class,
            parentColumns = ["id"],
            childColumns = ["draftId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["draftId"], unique = true),
    ],
)
data class ClassificationSnapshotEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    /** The draft this prediction is about. */
    val draftId: Long,
    /** Digest of the model input identity the prediction was made against. */
    val contextRevision: String,
    /** Digest of the category catalog the prediction was made against. */
    val categoryRevision: String,
    /** Digest of the remembered category rules the prediction was made against. */
    val ruleRevision: String,
    /** The model revision that produced the prediction. */
    val modelRevision: String,
    /** The candidate-set construction that produced the prediction. */
    val candidateSetVersion: String,
    /** The historical decision source (`MODEL`, `REMEMBERED_RULE`, `NO_FIT`, ...). */
    val decisionSource: String,
    /** The category the decision pointed at, or null when it pointed at none. */
    val suggestedCategoryId: Long?,
    /** The evaluated category ids, in the order the model evaluated them. */
    val candidateIds: String,
    /** The calibrated share of each evaluated category, in the same order. */
    val probabilities: String,
    /** The calibrated no-fit share, or null when no distribution was produced. */
    val unknownShare: Double?,
    /** The one Jev sharpness value of the same distribution, or null when none was produced. */
    val confidence: Double?,
    /** The digest of the composed model input; the input itself was never stored. */
    val effectiveInputDigest: String,
    /** How the historical input was derived; translated text itself was never stored. */
    @ColumnInfo(defaultValue = "'STORED_SOURCE'")
    val classificationInputKind: String = "STORED_SOURCE",
    /** Hash of input kind, source revision (when translated), and the composed input digest. */
    @ColumnInfo(defaultValue = "''")
    val classificationInputIdentity: String = "",
    /** Source/draft/link revision identity required to keep a hosted translation current. */
    val translationSourceRevision: String? = null,
    /** The stored event readings the input was composed from, ascending. */
    val eventIds: String,
    /** When the prediction was recorded. */
    val predictionTime: Long,
    /** Why the prediction was no longer current, or null while it was. */
    val staleReason: String?,
)

/**
 * The canonical digest of a set of dependency values.
 *
 * Every value is length-prefixed, so an amount of `1` beside a memo of `2` can never encode
 * like an amount of `12` beside no memo. A null value means the dependency was not present
 * and is distinct from a present empty value.
 */
internal fun canonicalDependencyDigest(parts: List<String?>): String {
    val canonical = parts.joinToString(separator = DEPENDENCY_FIELD_SEPARATOR) { value ->
        if (value == null) ABSENT_DEPENDENCY else "v${value.length}:$value"
    }
    return sha256Hex(canonical)
}

/** The hex SHA-256 digest of one UTF-8 string. */
private fun sha256Hex(value: String): String =
    MessageDigest.getInstance(SHA_256)
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }

/** Separates the canonical dependency fields; never a separator in a stored value. */
private const val DEPENDENCY_FIELD_SEPARATOR = "\u001F"

/** Marks a dependency that was not present, distinct from a present empty value. */
private const val ABSENT_DEPENDENCY = "n"

private const val SHA_256 = "SHA-256"
