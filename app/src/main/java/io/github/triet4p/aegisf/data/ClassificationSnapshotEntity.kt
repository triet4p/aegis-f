package io.github.triet4p.aegisf.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.security.MessageDigest

/**
 * Why a stored prediction is no longer current.
 *
 * The reason names the dependency that moved. It is stored rather than recomputed on every
 * read, so a stale prediction stays visibly stale across restarts instead of looking fresh
 * again because nothing happened to read it while it was stale.
 */
enum class PredictionStaleReason {
    /**
     * The context the prediction was made against changed: a linked event was re-read, a
     * link changed, or the draft's own review state moved on.
     */
    CONTEXT_CHANGED,

    /**
     * The source text one linked event was read from was edited or deleted, so the reading
     * the prediction relied on no longer exists.
     */
    SOURCE_CHANGED,

    /**
     * The category catalog changed: a category was added, renamed, archived, or deleted, so
     * the evaluated candidate set is no longer the one the model saw.
     */
    CATEGORY_CHANGED,

    /**
     * The user's remembered category rules changed, so the saved rule that short-circuits
     * inference may now answer differently.
     */
    RULE_CHANGED,

    /**
     * The model revision changed, so the prediction was made by a model this build no
     * longer runs.
     */
    MODEL_CHANGED,

    /**
     * The candidate-set construction changed, so the same description would now be offered a
     * different set of categories.
     */
    CANDIDATE_SET_CHANGED,

    /**
     * The user already chose a category for this draft. Their choice stands, and a
     * prediction is never applied over it.
     */
    USER_CATEGORY_CHOSEN,
}

/** Where a recorded decision came from. */
enum class PredictionSource {
    /** A saved exact-description rule answered before inference ran. */
    REMEMBERED_RULE,

    /** An explicit correction rule named the category. */
    EXPLICIT_CORRECTION,

    /** The description contained the category's own name. */
    CATEGORY_METADATA,

    /** The model evaluated a candidate set. */
    MODEL,

    /** Nothing fit, and the review stays manual. */
    NO_FIT,
}

/**
 * One stored prediction about one bank draft, with the dependencies it was made against.
 *
 * The snapshot is review-only evidence: it records what was suggested, against which
 * categories, with which shares, and — the point of the row — which state the suggestion was
 * made in. The dependencies are stored as digests, so a later read can tell whether the
 * context, the categories, the rules, or the model have moved on since, and can then say
 * that the prediction is stale instead of applying it.
 *
 * The composed model input itself is NOT stored: only its digest. A prompt is ephemeral by
 * contract, and the digest is enough to recognise that the same input produced the same
 * prediction.
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
    /** Digest of the context the prediction was made against: the linked events, the links, and the draft's own review state. */
    val contextRevision: String,
    /** Digest of the category catalog the prediction was made against. */
    val categoryRevision: String,
    /** Digest of the remembered category rules the prediction was made against. */
    val ruleRevision: String,
    /** The model revision that produced the prediction. */
    val modelRevision: String,
    /** The candidate-set construction that produced the prediction. */
    val candidateSetVersion: String,
    /** Where the recorded decision came from; see [PredictionSource]. */
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
    /** The digest of the composed model input; the input itself is never stored. */
    val effectiveInputDigest: String,
    /** The stored event readings the input was composed from, ascending. */
    val eventIds: String,
    /** When the prediction was recorded. */
    val predictionTime: Long,
    /** Why the prediction is no longer current, or null while it is. */
    val staleReason: String?,
)

/**
 * The digest of one composed model input.
 *
 * Only the digest is ever stored: the composed text is the user's own text, and a prompt
 * stays ephemeral.
 */
fun effectiveInputDigest(text: String): String = sha256Hex(text)

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
