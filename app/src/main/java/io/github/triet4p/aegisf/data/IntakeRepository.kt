package io.github.triet4p.aegisf.data

import androidx.room.withTransaction
import io.github.triet4p.aegisf.notification.BankDirection
import io.github.triet4p.aegisf.notification.BankNotificationParse
import io.github.triet4p.aegisf.notification.BankSource
import io.github.triet4p.aegisf.notification.BankStatedMoment
import io.github.triet4p.aegisf.notification.NotificationField
import io.github.triet4p.aegisf.notification.PermittedNotificationContent
import io.github.triet4p.aegisf.notification.UnresolvedReason
import io.github.triet4p.aegisf.notification.parseBankNotification

/**
 * Stores one permitted notification as pending intake on a background thread.
 *
 * This is the S3.4 seam between the listener callback and the database: the callback
 * stays free of disk work, and every write below runs on the repository's caller
 * context, which the listener supplies as its background dispatcher. A rejected
 * notification never reaches here — the listener returns before calling — so there is
 * nothing to persist for OTP, security, group-summary, empty, or redaction-only
 * payloads.
 *
 * What is stored for one permitted payload: the notification's routing provenance
 * (package, key, posted time), the bank attribution, and the deterministic parse result
 * with every field's explicit present/unresolved/missing state. Unsupported or malformed
 * content becomes a reviewable [IntakeParseStatus.UNSUPPORTED_FORMAT] row with null
 * financial columns rather than a false transaction; nothing here invents an amount,
 * a ledger type, a time zone, or a memo.
 *
 * Storing is idempotent per delivery post and statement, and the database decides that rather
 * than this class: the row carries a delivery identity ([draftDeliveryId]) — the package, the
 * platform's notification key when there is one, and the platform's post time — and an evidence
 * digest ([draftEvidenceDigest]), and [PendingBankDraftDao.store] turns a repeated pair into
 * no write at all. A replayed callback, a notification re-read after a reconnect, and a payload
 * revision restating the same evidence therefore add nothing, while a changed statement under
 * the same notification key adds a new draft beside the old one instead of overwriting it. That
 * includes the case a reused key makes dangerous: a later post under that key whose permitted
 * evidence happens to be byte-identical to the earlier delivery's is a *different delivery* and
 * is stored as its own draft, flagged as a possible duplicate — never dropped as a replay. The
 * supported format states no transaction reference, so nothing here is allowed to decide that
 * two similar amounts and moments are one transaction.
 */
open class IntakeRepository(
    private val database: LedgerDatabase,
    private val clock: LedgerClock = SystemLedgerClock,
) {
    private val draftDao = database.pendingBankDraftDao()
    private val captureMessageDao = database.captureMessageDao()

    /**
     * Parses [content] deterministically and stores it as a pending draft, returning the id of
     * the draft that represents this delivery.
     *
     * The [PendingBankDraftEntity] row carries only minimal permitted data — never the
     * raw body, never an OTP, account identifier, or balance — and starts at
     * [IntakeReviewStatus.PENDING] with no ledger link. The bank's wall-clock moment is
     * stored unchanged as stated date/time; no instant is asserted here.
     *
     * An identical delivery — the same package, the same platform notification key, the same
     * post time, stating exactly the same evidence — stores nothing and returns the id already
     * stored for it, whatever review state that draft holds; so a replayed callback and a
     * re-read active notification cannot promote a confirmed or dismissed draft back into review.
     * Every other delivery is stored as its own new pending draft, leaving all earlier rows as
     * they are: a changed statement under a reused key, and equally a later post under a reused
     * key whose evidence is byte-identical to an earlier delivery's (that one is additionally
     * flagged as a possible duplicate rather than discarded).
     */
    open suspend fun storePermitted(
        sourcePackage: String,
        notificationKey: String?,
        postedAtEpochMillis: Long,
        content: PermittedNotificationContent,
    ): Long {
        val parsed = parseBankNotification(content)
        val draft = PendingBankDraftEntity(
            deliveryId = draftDeliveryId(sourcePackage, notificationKey, postedAtEpochMillis),
            sourcePackage = sourcePackage,
            notificationKey = notificationKey,
            postedAtEpochMillis = postedAtEpochMillis,
            bank = parsed.bankName(),
            parseStatus = parsed.parseStatus(),
            amountState = parsed.amountField().state(),
            amountVnd = parsed.amountField().value(),
            amountUnresolvedReason = parsed.amountField().reasonName(),
            direction = parsed.bankDirection().toIntakeDirection(),
            momentState = parsed.momentField().state(),
            statedDate = parsed.momentField().value()?.date,
            statedTime = parsed.momentField().value()?.time,
            momentUnresolvedReason = parsed.momentField().reasonName(),
            memoState = parsed.memoField().state(),
            memo = parsed.memoField().value(),
            memoUnresolvedReason = parsed.memoField().reasonName(),
            createdAtEpochMillis = clock.nowEpochMillis(),
        )
        return database.withTransaction {
            val draftId = draftDao.store(draft)
            if (draft.parseStatus == IntakeParseStatus.PARSED) {
                storeBankEvidenceReference(draftId, draft)
            }
            draftId
        }
    }

    /**
     * Stores only the parser's permitted memo as bank source text. The draft and its source
     * reference share one Room transaction so a failed reference write leaves the earlier
     * database state intact.
     */
    private suspend fun storeBankEvidenceReference(
        draftId: Long,
        draft: PendingBankDraftEntity,
    ) {
        val sourceIdentity = bankEvidenceSourceIdentity(draftId)
        val expected = CaptureMessageEntity(
            captureKind = CaptureKind.BANK_EVIDENCE_REF,
            sourceKind = CaptureSourceKind.BANK_NOTIFICATION,
            sourceIdentity = sourceIdentity,
            bodyText = draft.memo.takeIf { draft.memoState == IntakeFieldState.PRESENT },
            capturedAtEpochMillis = draft.postedAtEpochMillis,
            captureTimezone = null,
            revision = 0,
            originRef = sourceIdentity,
        )
        val existing = captureMessageDao.findBySourceIdentity(
            CaptureSourceKind.BANK_NOTIFICATION,
            sourceIdentity,
        )
        if (existing != null) {
            check(existing.matchesSourceReference(expected)) {
                "Bank evidence source identity $sourceIdentity has conflicting stored provenance."
            }
            return
        }
        val insertedId = captureMessageDao.insert(expected)
        if (insertedId == -1L) {
            val raced = captureMessageDao.findBySourceIdentity(
                CaptureSourceKind.BANK_NOTIFICATION,
                sourceIdentity,
            )
            check(raced != null && raced.matchesSourceReference(expected)) {
                "Bank evidence source identity $sourceIdentity could not be stored consistently."
            }
        }
    }

    private fun CaptureMessageEntity.matchesSourceReference(
        expected: CaptureMessageEntity,
    ): Boolean =
        captureKind == expected.captureKind &&
            sourceKind == expected.sourceKind &&
            sourceIdentity == expected.sourceIdentity &&
            bodyText == expected.bodyText &&
            capturedAtEpochMillis == expected.capturedAtEpochMillis &&
            captureTimezone == expected.captureTimezone &&
            revision == expected.revision &&
            originRef == expected.originRef &&
            tombstone == expected.tombstone

    /**
     * The persisted drafts are read through the shared query surface — see
     * [WorkflowQueryService.draftCandidates] — so this repository keeps the write that
     * stores them and no second read path.
     */

    private fun BankNotificationParse.bankName(): String = when (this) {
        is BankNotificationParse.Parsed -> bank.storageName()
        BankNotificationParse.UnsupportedFormat -> UNSUPPORTED_BANK_NAME
    }

    private fun BankSource.storageName(): String = when (this) {
        BankSource.VIETINBANK -> "VIETINBANK"
    }

    private fun BankNotificationParse.parseStatus(): IntakeParseStatus = when (this) {
        is BankNotificationParse.Parsed -> IntakeParseStatus.PARSED
        BankNotificationParse.UnsupportedFormat -> IntakeParseStatus.UNSUPPORTED_FORMAT
    }

    private fun BankNotificationParse.amountField(): NotificationField<Long> = when (this) {
        is BankNotificationParse.Parsed -> amountVnd
        BankNotificationParse.UnsupportedFormat -> NotificationField.Missing
    }

    private fun BankNotificationParse.bankDirection(): BankDirection = when (this) {
        is BankNotificationParse.Parsed -> direction
        BankNotificationParse.UnsupportedFormat -> BankDirection.UNKNOWN
    }

    private fun BankNotificationParse.momentField(): NotificationField<BankStatedMoment> = when (this) {
        is BankNotificationParse.Parsed -> occurredAt
        BankNotificationParse.UnsupportedFormat -> NotificationField.Missing
    }

    private fun BankNotificationParse.memoField(): NotificationField<String> = when (this) {
        is BankNotificationParse.Parsed -> memo
        BankNotificationParse.UnsupportedFormat -> NotificationField.Missing
    }

    private fun <T> NotificationField<T>.state(): IntakeFieldState = when (this) {
        is NotificationField.Present -> IntakeFieldState.PRESENT
        is NotificationField.Unresolved -> IntakeFieldState.UNRESOLVED
        NotificationField.Missing -> IntakeFieldState.MISSING
    }

    /** Present values only; unresolved and missing fields store null, never a guess. */
    private fun <T> NotificationField<T>.value(): T? = when (this) {
        is NotificationField.Present -> value
        is NotificationField.Unresolved -> null
        NotificationField.Missing -> null
    }

    /** The unresolved reason, or null when the field is present or was never stated. */
    private fun <T> NotificationField<T>.reasonName(): String? = when (this) {
        is NotificationField.Present -> null
        is NotificationField.Unresolved -> reason.storageName()
        NotificationField.Missing -> null
    }

    private fun BankDirection.toIntakeDirection(): IntakeDirection = when (this) {
        BankDirection.DEBIT -> IntakeDirection.DEBIT
        BankDirection.CREDIT -> IntakeDirection.CREDIT
        BankDirection.UNKNOWN -> IntakeDirection.UNKNOWN
    }

    private companion object {
        /**
         * Bank attribution stored for payloads that matched no supported format. A plain
         * marker, not a bank name: nothing about an unsupported payload is attributed to
         * a bank.
         */
        const val UNSUPPORTED_BANK_NAME = "UNSUPPORTED"
    }
}

/** Why a labelled bank field the parser could not read carries that unresolved marker. */
internal fun UnresolvedReason.storageName(): String = when (this) {
    UnresolvedReason.Unreadable -> "UNREADABLE"
    UnresolvedReason.Redacted -> "REDACTED"
    UnresolvedReason.Ambiguous -> "AMBIGUOUS"
}
