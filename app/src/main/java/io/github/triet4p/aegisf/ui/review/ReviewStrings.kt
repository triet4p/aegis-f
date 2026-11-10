package io.github.triet4p.aegisf.ui.review

import io.github.triet4p.aegisf.data.AegisLanguage
import io.github.triet4p.aegisf.data.BankDraftReviewField
import io.github.triet4p.aegisf.data.DuplicateRisk
import io.github.triet4p.aegisf.data.IntakeDirection
import io.github.triet4p.aegisf.data.IntakeFieldState
import io.github.triet4p.aegisf.data.IntakeReviewStatus
import io.github.triet4p.aegisf.data.PendingBankDraftEntity
import io.github.triet4p.aegisf.data.ReviewCategoryOrigin
import io.github.triet4p.aegisf.data.ReviewQueueConflict
import io.github.triet4p.aegisf.data.ReviewQueueGroup
import io.github.triet4p.aegisf.data.VndMoney
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The Review destination's vocabulary in one language.
 *
 * English is the baseline and Vietnamese is a 1:1 translation. It stays out of the shared
 * [io.github.triet4p.aegisf.ui.text.AegisStrings] constructor, whose shell copy is already large:
 * these words belong to the inbox and one draft's review, which S10.6 owns.
 */
internal data class ReviewStrings(
    val language: AegisLanguage,
    val inboxTitle: String,
    val inboxLead: String,
    val inboxReading: String,
    val inboxLoaded: String,
    val inboxReadError: String,
    val inboxEmptyTitle: String,
    val inboxEmptyBody: String,
    val inboxEmptyPage: String,
    val inboxFooter: String,
    val refresh: String,
    val retry: String,
    val orderNeedsFirst: String,
    val orderOldestFirst: String,
    val orderChange: String,
    val orderNeedsFirstHelp: String,
    val orderOldestFirstHelp: String,
    val sectionDecision: String,
    val sectionCategory: String,
    val sectionReady: String,
    val sectionDecisionHelp: String,
    val sectionCategoryHelp: String,
    val sectionReadyHelp: String,
    val continued: String,
    val reviewDraft: String,
    val categoryNotChosen: String,
    val amountUnavailable: String,
    val formatUnsupportedShort: String,
    val conflictDuplicate: String,
    val conflictProposedLink: String,
    val conflictBankData: String,
    val possibleDuplicateLine: String,
    val timeUnavailable: String,
    val statusPending: String,
    val statusConfirmed: String,
    val statusDismissed: String,
    val directionDebit: String,
    val directionCredit: String,
    val directionUnknown: String,
    val detailTitle: String,
    val loading: String,
    val missingTitle: String,
    val missingBody: String,
    val readErrorTitle: String,
    val readErrorBody: String,
    val backToInbox: String,
    val back: String,
    val close: String,
    val dismiss: String,
    val confirmAction: String,
    val confirming: String,
    val previewTitle: String,
    val previewIntro: String,
    val previewStaleTitle: String,
    val previewStaleTerminal: String,
    val previewStaleFields: String,
    val previewStaleValues: String,
    val previewStaleHelp: String,
    val checkCurrentValues: String,
    val checking: String,
    val previewAmount: String,
    val previewType: String,
    val previewWhen: String,
    val previewDescription: String,
    val previewCategory: String,
    val previewNoCategory: String,
    val previewUnknownCategory: String,
    val previewEvidenceKept: String,
    val previewDuplicateRisk: String,
    val confirm: String,
    val backToReview: String,
    val working: String,
    val categorySection: String,
    val categoryChange: String,
    val categorySheetTitle: String,
    val categorySheetHelp: String,
    val categoryNoCategory: String,
    val categoryNoActive: String,
    val categoryOriginUser: String,
    val categoryOriginHosted: String,
    val categoryOriginNone: String,
    val categoryTransferNote: String,
    val editSection: String,
    val typeLabel: String,
    val typeHelp: String,
    val amountLabel: String,
    val amountPlaceholder: String,
    val amountHint: String,
    val amountError: String,
    val dateLabel: String,
    val datePlaceholder: String,
    val dateError: String,
    val timeLabel: String,
    val timePlaceholder: String,
    val timeError: String,
    val timeHelp: String,
    val descriptionLabel: String,
    val descriptionPlaceholder: String,
    val descriptionOptional: String,
    val descriptionUnresolved: String,
    val saveCorrections: String,
    val saving: String,
    val correctionsSaved: String,
    val typeRequired: String,
    val categoryRequired: String,
    val evidenceSection: String,
    val evidenceHelp: String,
    val evidenceBank: String,
    val evidenceSource: String,
    val evidenceFormat: String,
    val evidenceFormatSupported: String,
    val evidenceFormatUnsupported: String,
    val evidenceAmount: String,
    val evidenceDirection: String,
    val evidenceMoment: String,
    val evidenceMemo: String,
    val evidenceArrived: String,
    val unsupportedWarning: String,
    val notStated: String,
    val maskedByBank: String,
    val ambiguousValues: String,
    val unreadableValue: String,
    val unresolvedValue: String,
    val correctedBefore: String,
    val noteContextSection: String,
    val noteContextHelp: String,
    val noteContextLoading: String,
    val noteContextError: String,
    val noteContextRetry: String,
    val noteContextEmpty: String,
    val noteContextAdd: String,
    val noteContextAttached: String,
    val noteContextProposed: String,
    val noteContextRefused: String,
    val noteContextUnlink: String,
    val noteContextAttach: String,
    val noteContextReject: String,
    val noteContextRefusedKept: String,
    val noteContextSourceChanged: String,
    val noteContextReading: String,
    val noteContextFacts: String,
    val noteContextRevisions: String,
    val noteActionAttached: String,
    val noteActionRejected: String,
    val noteActionUnlinked: String,
    val linkActionFailed: String,
    val addContextTitle: String,
    val addContextHelp: String,
    val addContextLoading: String,
    val addContextEmpty: String,
    val addContextError: String,
    val addContextAttach: String,
    val addContextSourceStale: String,
    val addContextExcerpt: String,
    val addContextSaving: String,
    val previous: String,
    val next: String,
    val aiContext: String,
    val aiContextHelp: String,
    val dismissTitle: String,
    val dismissBody: String,
    val dismissConfirm: String,
    val dismissing: String,
    val keepReviewing: String,
    val terminalConfirmed: String,
    val terminalDismissed: String,
    val terminalPending: String,
    val terminalLinkedRecord: String,
    val duplicateSection: String,
    val duplicateLine: String,
    val openDuplicate: String,
    val alreadyDecided: String,
    val categoryUnavailable: String,
    val draftUnavailable: String,
    val requestFailed: String,
    val requestCancelled: String,
    val resolveBeforeConfirm: String,
    val fieldAmount: String,
    val fieldDate: String,
    val fieldTime: String,
    val fieldType: String,
    val fieldCategory: String,
    val correctionsConflict: String,
    val correctionsUnavailable: String,
) {
    /** "3 waiting" / "3 đang chờ". */
    fun waiting(count: Int): String = when (language) {
        AegisLanguage.ENGLISH -> if (count == 1) "1 draft waiting" else "$count drafts waiting"
        AegisLanguage.VIETNAMESE -> "$count biến động đang chờ"
    }

    /** "2 on this page" / "2 trên trang này". */
    fun countOnPage(count: Int): String = when (language) {
        AegisLanguage.ENGLISH -> if (count == 1) "1 on this page" else "$count on this page"
        AegisLanguage.VIETNAMESE -> "$count trên trang này"
    }

    /** "Page 2" / "Trang 2". */
    fun pageLabel(pageIndex: Int): String = when (language) {
        AegisLanguage.ENGLISH -> "Page ${pageIndex + 1}"
        AegisLanguage.VIETNAMESE -> "Trang ${pageIndex + 1}"
    }

    /** The heading of one queue group. */
    fun sectionTitle(group: ReviewQueueGroup): String = when (group) {
        ReviewQueueGroup.NEEDS_DECISION -> sectionDecision
        ReviewQueueGroup.NEEDS_CATEGORY -> sectionCategory
        ReviewQueueGroup.READY -> sectionReady
    }

    /** Why one queue group is where it is in the order. */
    fun sectionHelp(group: ReviewQueueGroup): String = when (group) {
        ReviewQueueGroup.NEEDS_DECISION -> sectionDecisionHelp
        ReviewQueueGroup.NEEDS_CATEGORY -> sectionCategoryHelp
        ReviewQueueGroup.READY -> sectionReadyHelp
    }

    /** "Category: Food" / "Category: not chosen" on one inbox card. */
    fun categoryCardLine(name: String?): String = when (language) {
        AegisLanguage.ENGLISH -> "Category: ${name ?: "not chosen"}"
        AegisLanguage.VIETNAMESE -> "Danh mục: ${name ?: "chưa chọn"}"
    }

    /** The unresolved fact that put one draft in the first group. */
    fun conflict(conflict: ReviewQueueConflict): String = when (conflict) {
        ReviewQueueConflict.POSSIBLE_DUPLICATE -> conflictDuplicate
        ReviewQueueConflict.PROPOSED_LINK -> conflictProposedLink
        ReviewQueueConflict.UNRESOLVED_BANK_DATA -> conflictBankData
    }

    /** A stored review status in the user's words. */
    fun status(status: IntakeReviewStatus): String = when (status) {
        IntakeReviewStatus.PENDING -> statusPending
        IntakeReviewStatus.CONFIRMED -> statusConfirmed
        IntakeReviewStatus.DISMISSED -> statusDismissed
    }

    /** The bank's own direction, never a ledger type. */
    fun direction(direction: IntakeDirection): String = when (direction) {
        IntakeDirection.DEBIT -> directionDebit
        IntakeDirection.CREDIT -> directionCredit
        IntakeDirection.UNKNOWN -> directionUnknown
    }

    /** The sign the bank's own direction justifies on evidence surfaces. */
    fun directionPrefix(direction: IntakeDirection): String = when (direction) {
        IntakeDirection.DEBIT -> "− "
        IntakeDirection.CREDIT -> "+ "
        IntakeDirection.UNKNOWN -> ""
    }

    /** A field the bank did not state, stated ambiguously, or that could not be read. */
    fun fieldReason(state: IntakeFieldState, reason: String?): String = when (state) {
        IntakeFieldState.PRESENT -> ""
        IntakeFieldState.MISSING -> notStated
        IntakeFieldState.UNRESOLVED -> when (reason) {
            "REDACTED" -> maskedByBank
            "AMBIGUOUS" -> ambiguousValues
            "UNREADABLE" -> unreadableValue
            else -> unresolvedValue
        }
    }

    /** One unresolved source field and the request to correct it before confirmation. */
    fun correctedBefore(label: String, state: IntakeFieldState, reason: String?): String {
        if (state == IntakeFieldState.PRESENT) return ""
        val explanation = fieldReason(state, reason)
        return when (language) {
            AegisLanguage.ENGLISH -> "$label: $explanation. Correct it before confirmation when required."
            AegisLanguage.VIETNAMESE -> "$label: $explanation. Hãy sửa trước khi xác nhận nếu cần."
        }
    }

    /** The bank's stated moment, or why it is not available. */
    fun statedMoment(draft: PendingBankDraftEntity): String {
        val date = draft.statedDate
        val time = draft.statedTime
        if (date != null && time != null) {
            return when (language) {
                AegisLanguage.ENGLISH -> "$date $time (bank wall-clock time)"
                AegisLanguage.VIETNAMESE -> "$date $time (giờ trên thông báo ngân hàng)"
            }
        }
        return when (language) {
            AegisLanguage.ENGLISH ->
                "$timeUnavailable — ${fieldReason(draft.momentState, draft.momentUnresolvedReason)}"
            AegisLanguage.VIETNAMESE ->
                "$timeUnavailable — ${fieldReason(draft.momentState, draft.momentUnresolvedReason)}"
        }
    }

    /** One exact bank amount, or why it is not available. */
    fun amount(draft: PendingBankDraftEntity): String = draft.amountVnd
        ?.let { VndMoney.formatAmount(it, " ₫") }
        ?: when (language) {
            AegisLanguage.ENGLISH ->
                "Not available — ${fieldReason(draft.amountState, draft.amountUnresolvedReason)}"
            AegisLanguage.VIETNAMESE ->
                "Không có — ${fieldReason(draft.amountState, draft.amountUnresolvedReason)}"
        }

    /** One bank memo, or why it is not available. */
    fun memo(draft: PendingBankDraftEntity): String = when {
        draft.memoState == IntakeFieldState.PRESENT && draft.memo != null -> draft.memo
        else -> when (language) {
            AegisLanguage.ENGLISH ->
                "Unresolved — ${fieldReason(draft.memoState, draft.memoUnresolvedReason)}"
            AegisLanguage.VIETNAMESE ->
                "Chưa rõ — ${fieldReason(draft.memoState, draft.memoUnresolvedReason)}"
        }
    }

    /** "Today, 14:15" / "Hôm nay, 14:15" for arrival or stored instants. */
    fun instant(epochMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        val zone = TimeZone.getDefault()
        val locale = when (language) {
            AegisLanguage.ENGLISH -> Locale.ENGLISH
            AegisLanguage.VIETNAMESE -> Locale.forLanguageTag("vi-VN")
        }
        val time = SimpleDateFormat("HH:mm", locale).apply { timeZone = zone }
            .format(Date(epochMillis))
        val then = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }
            .format(Date(epochMillis))
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }
            .format(Date(nowMillis))
        val yesterday = Calendar.getInstance(zone, Locale.US).apply {
            timeInMillis = nowMillis
            add(Calendar.DATE, -1)
        }
        val yesterdayDay = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }
            .format(Date(yesterday.timeInMillis))
        val date = SimpleDateFormat("dd/MM/yyyy", Locale.US).apply { timeZone = zone }
            .format(Date(epochMillis))
        return when (language) {
            AegisLanguage.ENGLISH -> when (then) {
                today -> "Today, $time"
                yesterdayDay -> "Yesterday, $time"
                else -> "$date, $time"
            }

            AegisLanguage.VIETNAMESE -> when (then) {
                today -> "Hôm nay, $time"
                yesterdayDay -> "Hôm qua, $time"
                else -> "$date, $time"
            }
        }
    }

    /** Who chose the stored review category, in the user's words. */
    fun categoryOrigin(origin: ReviewCategoryOrigin): String = when (origin) {
        ReviewCategoryOrigin.USER -> categoryOriginUser
        ReviewCategoryOrigin.HOSTED -> categoryOriginHosted
        ReviewCategoryOrigin.NONE -> categoryOriginNone
    }

    /** The description of the stored category provenance for a resolved draft. */
    fun categorySource(draft: PendingBankDraftEntity): String = when {
        !draft.reviewCategoryResolved -> categoryOriginNone
        draft.reviewCategoryOrigin == ReviewCategoryOrigin.NONE -> categoryOriginUser
        else -> categoryOrigin(draft.reviewCategoryOrigin)
    }

    /** The fields a refused save or preview wants stated. */
    fun correction(fields: Set<BankDraftReviewField>): String {
        val names = buildList {
            if (BankDraftReviewField.AMOUNT in fields) add(fieldAmount)
            if (BankDraftReviewField.DATE in fields) add(fieldDate)
            if (BankDraftReviewField.TIME in fields) add(fieldTime)
            if (BankDraftReviewField.TYPE in fields) add(fieldType)
            if (BankDraftReviewField.CATEGORY in fields) add(fieldCategory)
        }
        return resolveBeforeConfirm.format(names.joinToString(", "))
    }

    /** One draft's revision moved while it was open. */
    fun changedElsewhere(fromRevision: Int, toRevision: Int): String = when (language) {
        AegisLanguage.ENGLISH ->
            "This draft changed elsewhere (review revision $fromRevision → $toRevision). " +
                "Your edits are still here; the current values are checked before anything is " +
                "written."
        AegisLanguage.VIETNAMESE ->
            "Biến động này đã thay đổi ở nơi khác (bản sửa đổi $fromRevision → $toRevision). " +
                "Các chỉnh sửa của bạn vẫn còn; giá trị hiện tại sẽ được kiểm tra trước khi ghi."
    }

    /** "Possible duplicate of draft #4 — Pending". */
    fun duplicateOf(draftId: Long): String = when (language) {
        AegisLanguage.ENGLISH -> "Possible duplicate of draft #$draftId"
        AegisLanguage.VIETNAMESE -> "Có thể trùng với biến động #$draftId"
    }

    /** "Open draft #4 — Pending". */
    fun openDuplicate(draftId: Long, status: String): String = when (language) {
        AegisLanguage.ENGLISH -> "Open draft #$draftId — $status"
        AegisLanguage.VIETNAMESE -> "Mở biến động #$draftId — $status"
    }

    /** The stored record the confirmation wrote. */
    fun linkedRecord(transactionId: Long): String = when (language) {
        AegisLanguage.ENGLISH -> "Linked ledger record: #$transactionId"
        AegisLanguage.VIETNAMESE -> "Bản ghi sổ quỹ liên kết: #$transactionId"
    }

    /** "Saved note #3 · detail #7". */
    fun noteIdentity(captureId: Long?, eventId: Long): String = when (language) {
        AegisLanguage.ENGLISH ->
            "Saved note #${captureId?.toString() ?: "unavailable"} · detail #$eventId"
        AegisLanguage.VIETNAMESE ->
            "Ghi chú #${captureId?.toString() ?: "không còn"} · chi tiết #$eventId"
    }

    /** The source excerpt of one linked detail. */
    fun sourceExcerpt(excerpt: String): String = when (language) {
        AegisLanguage.ENGLISH -> "Source excerpt: “$excerpt”"
        AegisLanguage.VIETNAMESE -> "Trích nguồn: “$excerpt”"
    }

    /** Why the excerpt is no longer shown as the user's words. */
    fun excerptStale(captureRevision: Int?, eventRevision: Int?): String = when (language) {
        AegisLanguage.ENGLISH ->
            "The source changed after this reading (saved note revision " +
                "${captureRevision?.toString() ?: "gone"} vs reading revision " +
                "${eventRevision?.toString() ?: "gone"}), so it is not shown as your words."
        AegisLanguage.VIETNAMESE ->
            "Nguồn đã thay đổi sau lần đọc này (bản sửa đổi ghi chú " +
                "${captureRevision?.toString() ?: "không còn"} so với bản sửa đổi lần đọc " +
                "${eventRevision?.toString() ?: "không còn"}), nên không hiển thị như lời của bạn."
    }

    /** The revisions of one link and its reading. */
    fun linkRevisions(eventRevision: Int?, linkRevision: Int): String = when (language) {
        AegisLanguage.ENGLISH ->
            "Reading revision ${eventRevision?.toString() ?: "gone"} · link revision $linkRevision"
        AegisLanguage.VIETNAMESE ->
            "Bản sửa đổi lần đọc ${eventRevision?.toString() ?: "không còn"} · bản sửa đổi liên kết $linkRevision"
    }

    /** "Saved note #3 attached as context. The bank amount and ledger are unchanged." */
    fun noteAttached(captureId: Long): String = when (language) {
        AegisLanguage.ENGLISH ->
            "Saved note #$captureId attached as context. The bank amount and ledger are unchanged."
        AegisLanguage.VIETNAMESE ->
            "Đã gắn ghi chú #$captureId làm ngữ cảnh. Số tiền ngân hàng và sổ quỹ không thay đổi."
    }

    /** The confirmation's duplicate warning, naming the stored candidate. */
    fun duplicateRisk(risk: DuplicateRisk, moment: String): String = when (language) {
        AegisLanguage.ENGLISH ->
            "A stored record may already be this money: ${VndMoney.formatAmount(risk.amountVnd, " ₫")} " +
                "on $moment" +
                (if (risk.description.isEmpty()) "" else " (${risk.description})") +
                ". Confirming adds a second record; the first one stays."
        AegisLanguage.VIETNAMESE ->
            "Một bản ghi đã lưu có thể chính là khoản tiền này: ${VndMoney.formatAmount(risk.amountVnd, " ₫")} " +
                "vào $moment" +
                (if (risk.description.isEmpty()) "" else " (${risk.description})") +
                ". Xác nhận sẽ thêm bản ghi thứ hai; bản ghi đầu tiên vẫn giữ."
    }
}

internal fun reviewStrings(language: AegisLanguage): ReviewStrings = when (language) {
    AegisLanguage.ENGLISH -> englishReviewStrings
    AegisLanguage.VIETNAMESE -> vietnameseReviewStrings
}

private val englishReviewStrings = ReviewStrings(
    language = AegisLanguage.ENGLISH,
    inboxTitle = "Review inbox",
    inboxLead = "Bank notifications are evidence, not transactions. Nothing reaches the ledger " +
        "until you confirm one draft.",
    inboxReading = "Reading the review queue…",
    inboxLoaded = "Drafts update when their review state changes.",
    inboxReadError = "The review queue couldn't be read. No draft was changed.",
    inboxEmptyTitle = "Nothing to review",
    inboxEmptyBody = "Bank notifications that need a decision appear here. Manual entry and the " +
        "ledger keep working on this device.",
    inboxEmptyPage = "No drafts on this page.",
    inboxFooter = "Confirmed and dismissed drafts stay stored for replay protection and are not " +
        "listed here. A possible duplicate stays a candidate; it is never discarded automatically.",
    refresh = "Refresh",
    retry = "Try again",
    orderNeedsFirst = "Needs decision first",
    orderOldestFirst = "Oldest first",
    orderChange = "Change order",
    orderNeedsFirstHelp = "Drafts that need a decision come first, then drafts that still need a " +
        "category, then drafts ready to confirm.",
    orderOldestFirstHelp = "Every waiting draft by arrival, so older work stays reachable.",
    sectionDecision = "Needs your decision",
    sectionCategory = "Needs a category",
    sectionReady = "Ready to confirm",
    sectionDecisionHelp = "Unresolved matches and bank data that needs attention come first.",
    sectionCategoryHelp = "These drafts still need a category; the oldest is first.",
    sectionReadyHelp = "These drafts already have a category and wait for confirm or dismiss.",
    continued = "continued",
    reviewDraft = "Review this draft",
    categoryNotChosen = "Category not chosen yet",
    amountUnavailable = "Amount not read",
    formatUnsupportedShort = "unsupported bank format",
    conflictDuplicate = "possible duplicate",
    conflictProposedLink = "note association not decided",
    conflictBankData = "bank data needs attention",
    possibleDuplicateLine = "Possible duplicate. Both drafts are kept because the evidence cannot " +
        "establish whether these are the same transaction.",
    timeUnavailable = "Time not available",
    statusPending = "Pending",
    statusConfirmed = "Confirmed",
    statusDismissed = "Dismissed",
    directionDebit = "Debit (money left the account)",
    directionCredit = "Credit (money entered the account)",
    directionUnknown = "Direction not clear",
    detailTitle = "Draft review",
    loading = "Reading this draft…",
    missingTitle = "This draft is not here",
    missingBody = "It may have been removed. Nothing was changed.",
    readErrorTitle = "This draft couldn't be read",
    readErrorBody = "No draft or ledger record was changed.",
    backToInbox = "Back to inbox",
    back = "Back",
    close = "Close",
    dismiss = "Dismiss",
    confirmAction = "Confirm and add to ledger",
    confirming = "Confirming…",
    previewTitle = "Confirm this record?",
    previewIntro = "These exact values will be stored:",
    previewStaleTitle = "Preview is stale",
    previewStaleTerminal = "This draft was already confirmed or dismissed after the preview. " +
        "Nothing was written here.",
    previewStaleFields = "The review fields changed after the preview, so the values below are " +
        "no longer what would be written. Nothing has been written.",
    previewStaleValues = "Values from the earlier preview (not saved):",
    previewStaleHelp = "The bank notification, the draft, and your unsaved fields are still " +
        "here. Check the current values before confirming.",
    checkCurrentValues = "Check current values",
    checking = "Checking…",
    previewAmount = "Amount",
    previewType = "Type",
    previewWhen = "When",
    previewDescription = "Description",
    previewCategory = "Category",
    previewNoCategory = "no category",
    previewUnknownCategory = "unknown category",
    previewEvidenceKept = "The bank notification stays stored as evidence either way.",
    previewDuplicateRisk = "Duplicate check",
    confirm = "Confirm",
    backToReview = "Back to review",
    working = "Working…",
    categorySection = "Applied category",
    categoryChange = "Change",
    categorySheetTitle = "Choose a category",
    categorySheetHelp = "Only active categories can be chosen. An archived category is shown " +
        "only when this draft already uses it.",
    categoryNoCategory = "No category",
    categoryNoActive = "There are no active categories. You can explicitly choose No category.",
    categoryOriginUser = "Chosen by you",
    categoryOriginHosted = "Suggested by the AI model",
    categoryOriginNone = "Not chosen yet",
    categoryTransferNote = "An internal transfer moves money between your own holdings and " +
        "carries no category.",
    editSection = "What will be recorded",
    typeLabel = "Ledger type",
    typeHelp = "A debit is not automatically an expense, and a credit is not automatically " +
        "income. Choose the ledger type that matches what happened.",
    amountLabel = "Amount (VND)",
    amountPlaceholder = "50000",
    amountHint = "Enter whole VND digits; no amount is filled in for you.",
    amountError = "Enter a whole VND amount greater than zero, without separators.",
    dateLabel = "Date",
    datePlaceholder = "2026-10-16",
    dateError = "Enter the date as YYYY-MM-DD.",
    timeLabel = "Time",
    timePlaceholder = "07:38",
    timeError = "Enter the time as HH:mm on a 24-hour clock.",
    timeHelp = "The bank supplied a wall-clock time without a time zone. On confirmation, Aegis " +
        "F interprets this date and time in the device's current time zone. Correct either " +
        "field if that is not right.",
    descriptionLabel = "Description (optional)",
    descriptionPlaceholder = "Leave blank if unknown",
    descriptionOptional = "Optional",
    descriptionUnresolved = "The bank memo remains unresolved; you may leave it blank.",
    saveCorrections = "Save corrections",
    saving = "Saving…",
    correctionsSaved = "Corrections saved.",
    typeRequired = "Choose Expense, Income, or Internal transfer.",
    categoryRequired = "Choose a category or explicitly choose No category.",
    evidenceSection = "Bank evidence",
    evidenceHelp = "The raw notification body is not stored on this device. These are the fields " +
        "Aegis read from it, kept exactly as evidence.",
    evidenceBank = "Bank",
    evidenceSource = "Source package",
    evidenceFormat = "Read status",
    evidenceFormatSupported = "Supported format",
    evidenceFormatUnsupported = "Unsupported format",
    evidenceAmount = "Stated amount",
    evidenceDirection = "Bank direction",
    evidenceMoment = "Stated date and time",
    evidenceMemo = "Stated memo",
    evidenceArrived = "Arrived",
    unsupportedWarning = "This notification did not match a supported bank format. Review every " +
        "required field yourself before confirmation.",
    notStated = "not stated",
    maskedByBank = "masked by the bank",
    ambiguousValues = "conflicting values were stated",
    unreadableValue = "stated but not readable",
    unresolvedValue = "stated but unresolved",
    correctedBefore = "",
    noteContextSection = "Linked note context",
    noteContextHelp = "Your own saved words beside the bank's statement. An excerpt is provenance " +
        "only: attaching, refusing or unlinking one never changes the bank amount, the review " +
        "fields or the ledger.",
    noteContextLoading = "Loading note excerpts…",
    noteContextError = "Note context couldn't be loaded. The bank evidence and review fields are " +
        "unchanged.",
    noteContextRetry = "Retry note context",
    noteContextEmpty = "No note excerpt is attached. The bank evidence stands on its own, so you " +
        "can review and confirm without one.",
    noteContextAdd = "Add note context",
    noteContextAttached = "Attached note excerpt",
    noteContextProposed = "Proposed note excerpt",
    noteContextRefused = "Refused note excerpt (decision retained)",
    noteContextUnlink = "Unlink excerpt",
    noteContextAttach = "Attach",
    noteContextReject = "Refuse",
    noteContextRefusedKept = "This pair cannot be proposed again; the refusal is kept.",
    noteContextSourceChanged = "The source changed after this reading, so it is not shown as your " +
        "words.",
    noteContextReading = "Reading: %s",
    noteContextFacts = "",
    noteContextRevisions = "",
    noteActionAttached = "Note excerpt attached. The bank amount and ledger are unchanged.",
    noteActionRejected = "Note excerpt refused. That decision is retained.",
    noteActionUnlinked = "Note excerpt unlinked. The bank amount and ledger are unchanged.",
    linkActionFailed = "The note decision did not complete. No financial amount was changed; " +
        "reload and try again.",
    addContextTitle = "Add note context to draft",
    addContextHelp = "Only saved notes whose reading is complete and not already linked are " +
        "offered. The list is bounded and never selects a match automatically. Attaching an " +
        "excerpt never changes the bank amount, the review fields or the ledger.",
    addContextLoading = "Loading saved note excerpts…",
    addContextEmpty = "No saved note excerpt is available to attach. Save a note first; a complete " +
        "reading appears here.",
    addContextError = "Saved note excerpts couldn't be loaded. No link was changed.",
    addContextAttach = "Attach as context",
    addContextSourceStale = "The source text is no longer current; this excerpt cannot be attached.",
    addContextExcerpt = "Excerpt: “%s”",
    addContextSaving = "Saving the explicit link decision…",
    previous = "Previous",
    next = "Next",
    aiContext = "Ask the AI assistant about this draft",
    aiContextHelp = "Opens AI Chat with this draft's evidence attached as visible context. " +
        "Nothing is sent until you send a message.",
    dismissTitle = "Dismiss draft #%d?",
    dismissBody = "It will not create a ledger record or change totals. Its replay identity stays " +
        "terminal.",
    dismissConfirm = "Dismiss draft",
    dismissing = "Dismissing…",
    keepReviewing = "Keep reviewing",
    terminalConfirmed = "This draft is terminal. Replaying its notification will not create " +
        "another transaction.",
    terminalDismissed = "This draft was dismissed. Replaying its notification will not reopen it.",
    terminalPending = "This draft is no longer pending.",
    terminalLinkedRecord = "",
    duplicateSection = "Possible duplicate",
    duplicateLine = "Possible duplicate — this does not prove the transactions are the same. " +
        "Neither candidate was discarded.",
    openDuplicate = "",
    alreadyDecided = "This draft was already confirmed or dismissed. No additional transaction " +
        "was added.",
    categoryUnavailable = "That category is no longer available. Choose an active category and " +
        "try again.",
    draftUnavailable = "This draft is no longer available.",
    requestFailed = "This draft could not be changed.",
    requestCancelled = "The request was cancelled.",
    resolveBeforeConfirm = "Resolve before confirmation: %s.",
    fieldAmount = "amount",
    fieldDate = "date",
    fieldTime = "time",
    fieldType = "type",
    fieldCategory = "category",
    correctionsConflict = "Conflict: %s. Nothing was written; reload and try again.",
    correctionsUnavailable = "The category is no longer available. Choose an active category and try again.",
)

private val vietnameseReviewStrings = ReviewStrings(
    language = AegisLanguage.VIETNAMESE,
    inboxTitle = "Duyệt tin",
    inboxLead = "Thông báo ngân hàng là bằng chứng, không phải giao dịch. Sổ quỹ chỉ thay đổi khi " +
        "bạn xác nhận một biến động.",
    inboxReading = "Đang đọc danh sách cần duyệt…",
    inboxLoaded = "Biến động tự cập nhật khi trạng thái duyệt thay đổi.",
    inboxReadError = "Không đọc được danh sách cần duyệt. Không có biến động nào bị thay đổi.",
    inboxEmptyTitle = "Không còn gì cần duyệt",
    inboxEmptyBody = "Thông báo ngân hàng cần quyết định sẽ xuất hiện ở đây. Nhập tay và sổ quỹ " +
        "vẫn dùng được trên thiết bị.",
    inboxEmptyPage = "Không có biến động trên trang này.",
    inboxFooter = "Biến động đã xác nhận hoặc đã bỏ qua vẫn được giữ để chống phát lại và không " +
        "hiện ở đây. Biến động có thể trùng vẫn là một lựa chọn; ứng dụng không tự loại bỏ.",
    refresh = "Làm mới",
    retry = "Thử lại",
    orderNeedsFirst = "Cần xử lý trước",
    orderOldestFirst = "Cũ nhất trước",
    orderChange = "Đổi thứ tự",
    orderNeedsFirstHelp = "Biến động cần quyết định trước, rồi biến động còn thiếu danh mục, " +
        "sau đó là biến động sẵn sàng xác nhận.",
    orderOldestFirstHelp = "Mọi biến động đang chờ theo thời điểm đến, để việc cũ vẫn tìm được.",
    sectionDecision = "Cần bạn quyết định",
    sectionCategory = "Cần chọn danh mục",
    sectionReady = "Sẵn sàng xác nhận",
    sectionDecisionHelp = "Liên kết chưa rõ và dữ liệu ngân hàng cần kiểm tra hiện trước.",
    sectionCategoryHelp = "Các biến động này còn thiếu danh mục; cũ nhất hiện trước.",
    sectionReadyHelp = "Các biến động này đã có danh mục và chờ xác nhận hoặc bỏ qua.",
    continued = "tiếp theo",
    reviewDraft = "Xem biến động này",
    categoryNotChosen = "Chưa chọn danh mục",
    amountUnavailable = "Chưa đọc được số tiền",
    formatUnsupportedShort = "định dạng ngân hàng chưa hỗ trợ",
    conflictDuplicate = "có thể trùng",
    conflictProposedLink = "chưa quyết định liên kết ghi chú",
    conflictBankData = "dữ liệu ngân hàng cần kiểm tra",
    possibleDuplicateLine = "Có thể trùng. Cả hai biến động đều được giữ vì bằng chứng không thể " +
        "khẳng định đây là cùng một giao dịch.",
    timeUnavailable = "Không có thời gian",
    statusPending = "Đang chờ",
    statusConfirmed = "Đã xác nhận",
    statusDismissed = "Đã bỏ qua",
    directionDebit = "Ghi nợ (tiền rời tài khoản)",
    directionCredit = "Ghi có (tiền vào tài khoản)",
    directionUnknown = "Chưa rõ chiều tiền",
    detailTitle = "Biến động",
    loading = "Đang đọc biến động…",
    missingTitle = "Không tìm thấy biến động này",
    missingBody = "Có thể nó đã bị xoá. Không có gì bị thay đổi.",
    readErrorTitle = "Không đọc được biến động này",
    readErrorBody = "Không có biến động hay bản ghi sổ quỹ nào bị thay đổi.",
    backToInbox = "Về danh sách",
    back = "Quay lại",
    close = "Đóng",
    dismiss = "Bỏ qua",
    confirmAction = "Xác nhận ghi vào sổ",
    confirming = "Đang xác nhận…",
    previewTitle = "Xác nhận bản ghi này?",
    previewIntro = "Đây là các giá trị chính xác sẽ được lưu:",
    previewStaleTitle = "Bản xem trước đã cũ",
    previewStaleTerminal = "Biến động đã được xác nhận hoặc bỏ qua sau khi xem trước. Không có gì " +
        "được ghi ở đây.",
    previewStaleFields = "Các trường duyệt đã thay đổi sau khi xem trước, nên giá trị bên dưới " +
        "không còn là giá trị sẽ ghi. Không có gì được ghi.",
    previewStaleValues = "Giá trị từ lần xem trước cũ (chưa lưu):",
    previewStaleHelp = "Thông báo ngân hàng, biến động và các trường chưa lưu của bạn vẫn còn. " +
        "Hãy kiểm tra giá trị hiện tại trước khi xác nhận.",
    checkCurrentValues = "Kiểm tra giá trị hiện tại",
    checking = "Đang kiểm tra…",
    previewAmount = "Số tiền",
    previewType = "Loại",
    previewWhen = "Thời điểm",
    previewDescription = "Mô tả",
    previewCategory = "Danh mục",
    previewNoCategory = "không danh mục",
    previewUnknownCategory = "danh mục không xác định",
    previewEvidenceKept = "Thông báo ngân hàng vẫn được giữ làm bằng chứng trong mọi trường hợp.",
    previewDuplicateRisk = "Kiểm tra trùng",
    confirm = "Xác nhận",
    backToReview = "Quay lại duyệt",
    working = "Đang xử lý…",
    categorySection = "Danh mục áp dụng",
    categoryChange = "Đổi",
    categorySheetTitle = "Chọn danh mục",
    categorySheetHelp = "Chỉ chọn được danh mục đang hoạt động. Danh mục đã lưu trữ chỉ hiện khi " +
        "biến động này đang dùng nó.",
    categoryNoCategory = "Không danh mục",
    categoryNoActive = "Không có danh mục đang hoạt động. Bạn có thể chọn rõ ràng Không danh mục.",
    categoryOriginUser = "Bạn đã chọn",
    categoryOriginHosted = "Mô hình AI đề xuất",
    categoryOriginNone = "Chưa chọn",
    categoryTransferNote = "Chuyển khoản nội bộ di chuyển tiền giữa các tài khoản của bạn và " +
        "không cần danh mục.",
    editSection = "Nội dung sẽ ghi",
    typeLabel = "Loại sổ",
    typeHelp = "Ghi nợ không tự động là chi tiêu, ghi có không tự động là thu nhập. Hãy chọn loại " +
        "sổ đúng với việc đã xảy ra.",
    amountLabel = "Số tiền (VND)",
    amountPlaceholder = "50000",
    amountHint = "Chỉ nhập chữ số VND; ứng dụng không tự điền số tiền.",
    amountError = "Nhập số tiền VND nguyên dương, không có dấu phân cách.",
    dateLabel = "Ngày",
    datePlaceholder = "2026-10-16",
    dateError = "Nhập ngày theo dạng YYYY-MM-DD.",
    timeLabel = "Giờ",
    timePlaceholder = "07:38",
    timeError = "Nhập giờ theo dạng HH:mm, 24 giờ.",
    timeHelp = "Ngân hàng cung cấp giờ trên đồng hồ nhưng không kèm múi giờ. Khi xác nhận, Aegis " +
        "F hiểu ngày và giờ này theo múi giờ hiện tại của thiết bị. Hãy sửa một trong hai " +
        "trường nếu chưa đúng.",
    descriptionLabel = "Mô tả (không bắt buộc)",
    descriptionPlaceholder = "Để trống nếu chưa rõ",
    descriptionOptional = "Không bắt buộc",
    descriptionUnresolved = "Ghi chú ngân hàng chưa rõ; bạn có thể để trống.",
    saveCorrections = "Lưu chỉnh sửa",
    saving = "Đang lưu…",
    correctionsSaved = "Đã lưu chỉnh sửa.",
    typeRequired = "Hãy chọn Chi tiêu, Thu nhập hoặc Chuyển khoản nội bộ.",
    categoryRequired = "Hãy chọn một danh mục hoặc chọn rõ ràng Không danh mục.",
    evidenceSection = "Bằng chứng ngân hàng",
    evidenceHelp = "Nội dung gốc của thông báo không được lưu trên thiết bị. Đây là các trường " +
        "Aegis đã đọc, được giữ nguyên làm bằng chứng.",
    evidenceBank = "Ngân hàng",
    evidenceSource = "Ứng dụng nguồn",
    evidenceFormat = "Trạng thái đọc",
    evidenceFormatSupported = "Định dạng được hỗ trợ",
    evidenceFormatUnsupported = "Định dạng chưa hỗ trợ",
    evidenceAmount = "Số tiền ngân hàng",
    evidenceDirection = "Chiều tiền ngân hàng",
    evidenceMoment = "Ngày giờ ngân hàng",
    evidenceMemo = "Ghi chú ngân hàng",
    evidenceArrived = "Đến lúc",
    unsupportedWarning = "Thông báo này không khớp định dạng ngân hàng được hỗ trợ. Hãy tự kiểm " +
        "tra mọi trường bắt buộc trước khi xác nhận.",
    notStated = "không được nêu",
    maskedByBank = "ngân hàng đã che",
    ambiguousValues = "có nhiều giá trị mâu thuẫn",
    unreadableValue = "có nêu nhưng không đọc được",
    unresolvedValue = "có nêu nhưng chưa rõ",
    correctedBefore = "",
    noteContextSection = "Ngữ cảnh ghi chú liên kết",
    noteContextHelp = "Lời bạn tự lưu bên cạnh thông báo ngân hàng. Trích đoạn chỉ là nguồn gốc: " +
        "gắn, từ chối hay huỷ gắn không làm thay đổi số tiền ngân hàng, các trường duyệt hay sổ quỹ.",
    noteContextLoading = "Đang tải trích đoạn ghi chú…",
    noteContextError = "Không tải được ngữ cảnh ghi chú. Bằng chứng ngân hàng và các trường duyệt " +
        "không thay đổi.",
    noteContextRetry = "Thử lại ngữ cảnh ghi chú",
    noteContextEmpty = "Chưa gắn trích đoạn ghi chú nào. Bằng chứng ngân hàng tự đứng một mình, " +
        "nên bạn có thể duyệt và xác nhận mà không cần ghi chú.",
    noteContextAdd = "Thêm ngữ cảnh ghi chú",
    noteContextAttached = "Trích đoạn ghi chú đã gắn",
    noteContextProposed = "Trích đoạn ghi chú được đề xuất",
    noteContextRefused = "Trích đoạn ghi chú đã từ chối (giữ quyết định)",
    noteContextUnlink = "Huỷ gắn trích đoạn",
    noteContextAttach = "Gắn",
    noteContextReject = "Từ chối",
    noteContextRefusedKept = "Cặp này không thể được đề xuất lại; quyết định từ chối được giữ.",
    noteContextSourceChanged = "Nguồn đã thay đổi sau lần đọc này, nên không hiển thị như lời của bạn.",
    noteContextReading = "Nội dung đọc: %s",
    noteContextFacts = "",
    noteContextRevisions = "",
    noteActionAttached = "Đã gắn trích đoạn ghi chú. Số tiền ngân hàng và sổ quỹ không thay đổi.",
    noteActionRejected = "Đã từ chối trích đoạn ghi chú. Quyết định này được giữ.",
    noteActionUnlinked = "Đã huỷ gắn trích đoạn. Số tiền ngân hàng và sổ quỹ không thay đổi.",
    linkActionFailed = "Quyết định ghi chú chưa hoàn tất. Không có số tiền nào bị thay đổi; hãy " +
        "tải lại và thử lại.",
    addContextTitle = "Thêm ngữ cảnh ghi chú vào biến động",
    addContextHelp = "Chỉ các ghi chú đã đọc đầy đủ và chưa được liên kết mới được đề nghị. Danh " +
        "sách có giới hạn và không tự chọn khớp. Gắn trích đoạn không làm thay đổi số tiền " +
        "ngân hàng, các trường duyệt hay sổ quỹ.",
    addContextLoading = "Đang tải trích đoạn ghi chú đã lưu…",
    addContextEmpty = "Không có trích đoạn ghi chú nào để gắn. Hãy lưu một ghi chú trước; nội dung " +
        "đọc đầy đủ sẽ hiện ở đây.",
    addContextError = "Không tải được trích đoạn ghi chú. Không có liên kết nào bị thay đổi.",
    addContextAttach = "Gắn làm ngữ cảnh",
    addContextSourceStale = "Văn bản nguồn không còn hiện hành; không thể gắn trích đoạn này.",
    addContextExcerpt = "Trích đoạn: “%s”",
    addContextSaving = "Đang lưu quyết định liên kết rõ ràng…",
    previous = "Trước",
    next = "Sau",
    aiContext = "Hỏi Trợ lý AI về biến động này",
    aiContextHelp = "Mở Trợ lý AI với bằng chứng của biến động này làm ngữ cảnh hiển thị. Không " +
        "có gì được gửi cho đến khi bạn gửi tin nhắn.",
    dismissTitle = "Bỏ qua biến động #%d?",
    dismissBody = "Việc này không tạo bản ghi sổ quỹ hay thay đổi tổng. Danh tính chống phát lại " +
        "vẫn ở trạng thái kết thúc.",
    dismissConfirm = "Bỏ qua biến động",
    dismissing = "Đang bỏ qua…",
    keepReviewing = "Tiếp tục duyệt",
    terminalConfirmed = "Biến động này đã kết thúc. Phát lại thông báo sẽ không tạo thêm giao dịch.",
    terminalDismissed = "Biến động này đã bị bỏ qua. Phát lại thông báo sẽ không mở lại.",
    terminalPending = "Biến động này không còn chờ duyệt.",
    terminalLinkedRecord = "",
    duplicateSection = "Có thể trùng",
    duplicateLine = "Có thể trùng — điều này không chứng minh hai giao dịch là một. Không bên nào " +
        "bị loại bỏ.",
    openDuplicate = "",
    alreadyDecided = "Biến động đã được xác nhận hoặc bỏ qua. Không thêm giao dịch nào.",
    categoryUnavailable = "Danh mục đó không còn khả dụng. Hãy chọn danh mục đang hoạt động và " +
        "thử lại.",
    draftUnavailable = "Biến động này không còn.",
    requestFailed = "Không thể thay đổi biến động này.",
    requestCancelled = "Yêu cầu đã bị huỷ.",
    resolveBeforeConfirm = "Cần giải quyết trước khi xác nhận: %s.",
    fieldAmount = "số tiền",
    fieldDate = "ngày",
    fieldTime = "giờ",
    fieldType = "loại",
    fieldCategory = "danh mục",
    correctionsConflict = "Xung đột: %s. Không có gì được ghi; hãy tải lại và thử lại.",
    correctionsUnavailable = "Danh mục không còn khả dụng. Hãy chọn danh mục đang hoạt động và thử lại.",
)

/** The display name of a stored bank attribution, or the stored value when it is not known. */
internal fun reviewBankName(bank: String): String = when (bank) {
    "VIETINBANK" -> "VietinBank"
    "" -> "Unknown bank"
    else -> bank
}
