package io.github.triet4p.aegisf.data

import java.security.MessageDigest

/**
 * The two identities one stored draft keeps, and the canonical forms behind them.
 *
 * A bank notification can arrive more than once — the system replays a callback, the bank
 * updates the notification, the listener re-reads an active notification after a reconnect —
 * and the user's review must not turn those deliveries into extra transactions. At the same
 * time, two genuinely different transactions must never be collapsed because their amounts
 * and moments look alike, and a notification key is not proof of identity: a bank that posts
 * its alerts under one reused key sends a later real transaction through the same key.
 *
 * Two separate identities therefore decide what a delivery is:
 *
 * - the **delivery identity** ([draftDeliveryId]) — which platform post this is: the source
 *   package, the platform's notification key when it supplied one, and the time the post was
 *   made. It says nothing about the transaction; it only names the one notification the system
 *   delivered. The posted time is a part of the delivery, not of the statement, and it stays
 *   here rather than in the digest because it is what tells two posts apart: a bank that reuses
 *   one key for a later real transaction posts under a new time, and that later post must not
 *   be mistaken for a replay of the earlier one.
 * - the **evidence digest** ([draftEvidenceDigest]) — a canonical digest of the permitted
 *   fields the payload stated, and of nothing else. It is what makes two deliveries the same
 *   *statement*: the same delivery with the same digest is a replay, while the same delivery
 *   with a different digest is a changed statement under that post, and a different delivery
 *   with the same digest is an uncertain cross-notification duplicate.
 *
 * Only permitted data feeds the digest: the bank attribution, the parse outcome, and the
 * amount, direction, moment, and memo fields with their explicit unresolved states. The raw
 * body is never hashed, and neither are the delivery metadata (package, notification key,
 * posted time) — which are the delivery identity's own parts — nor the review lifecycle: those
 * say when and how a delivery arrived, not what the bank stated. Because that field list is
 * exhaustive and the values are length-prefixed, two different evidence sets cannot encode to
 * the same canonical form, so the digest is decided by the evidence alone; changing this
 * canonical form later would stop stored digests from matching newly computed ones for the
 * same evidence — that is a schema migration's job, not a silent re-derivation.
 */

/**
 * The canonical delivery identity of one posted notification: the delivery is the platform post
 * itself, so this names the package that posted it, the platform's notification key when there
 * is one, and the time the post was made.
 *
 * [notificationKey] is nullable because a post can arrive without one, and SQLite treats NULL
 * values as distinct, so a nullable column cannot carry the uniqueness constraint that makes
 * a replay idempotent. The posted time ([postedAtEpochMillis], the platform's own post time,
 * also stored as the row's provenance) is part of the identity because it is the only thing
 * available that tells one post from another under the same key: two posts made at different
 * times are two deliveries, whatever their parsed evidence says. Without it, a bank that reuses
 * one notification key for a later real transaction whose permitted evidence is byte-identical
 * to the earlier one — same amount, direction, stated minute, and memo, which is what the user's
 * redacted sample can state twice inside one minute — would collide with the earlier delivery and
 * the later real transaction would be silently discarded as a replay.
 *
 * This encoding is that constraint's value: it exists once here so the listener, the storage
 * constraint, and any later reconciliation agree on what "the same delivery" means. It is
 * injective: the posted time is a decimal integer, so it carries no `|`, and the last
 * `|`-separated segment therefore always splits off as the time whatever separators the
 * platform's notification key itself contains.
 */
internal fun draftDeliveryId(
    sourcePackage: String,
    notificationKey: String?,
    postedAtEpochMillis: Long,
): String =
    if (notificationKey == null) {
        "$sourcePackage|nokey|$postedAtEpochMillis"
    } else {
        "$sourcePackage|key:$notificationKey|$postedAtEpochMillis"
    }

/**
 * The canonical digest of the permitted evidence one delivery stated.
 *
 * Every value is length-prefixed, so an amount of `1` beside a memo of `2` can never encode
 * like an amount of `12` beside no memo. `null` means the field was not present in this
 * evidence — a missing field, an unresolved field, or an absence of the reason a field is
 * unresolved; the surrounding [IntakeFieldState] keeps those apart, so a missing field is not
 * stored as equal to one the bank stated and could not be read.
 */
internal fun draftEvidenceDigest(
    bank: String,
    parseStatus: IntakeParseStatus,
    amountState: IntakeFieldState,
    amountVnd: Long?,
    amountUnresolvedReason: String?,
    direction: IntakeDirection,
    momentState: IntakeFieldState,
    statedDate: String?,
    statedTime: String?,
    momentUnresolvedReason: String?,
    memoState: IntakeFieldState,
    memo: String?,
    memoUnresolvedReason: String?,
): String {
    val canonical = listOf(
        bank,
        parseStatus.name,
        amountState.name,
        amountVnd?.toString(),
        amountUnresolvedReason,
        direction.name,
        momentState.name,
        statedDate,
        statedTime,
        momentUnresolvedReason,
        memoState.name,
        memo,
        memoUnresolvedReason,
    ).joinToString(separator = EVIDENCE_FIELD_SEPARATOR) { value ->
        if (value == null) ABSENT_FIELD else "v${value.length}:$value"
    }
    return MessageDigest.getInstance(SHA_256)
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }
}

/** Separates the canonical evidence fields; never a separator in a stored bank value. */
private const val EVIDENCE_FIELD_SEPARATOR = "\u001F"

/** Marks a field this evidence did not carry, distinct from a present empty value. */
private const val ABSENT_FIELD = "n"

private const val SHA_256 = "SHA-256"
