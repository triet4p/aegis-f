package io.github.triet4p.aegisf.ui.text

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.triet4p.aegisf.data.AegisLanguage
import io.github.triet4p.aegisf.data.RecordSource
import io.github.triet4p.aegisf.data.TransactionType

/**
 * The shell's user-facing vocabulary in one language.
 *
 * English is the baseline: every property is authored in English first, and [vietnameseStrings]
 * is a 1:1 translation of the same properties. The type is deliberately a plain immutable value
 * rather than Android resources, because the app has no `res/` string catalogue: selecting a
 * language changes what the shell says without restarting the process.
 *
 * Only the shell and the shared design-system primitives read these strings. Feature screens
 * keep their own copy until their redesign task (S10.3–S10.8) owns it; this is why the shell must
 * not invent copy for a screen it does not own.
 */
/**
 * The Notes destination's vocabulary, including the explicit chat-handoff copy.
 *
 * It is one immutable value per language, so a screen reads one consistent set of words and
 * the shared [AegisStrings] constructor stays a manageable size.
 */
data class NoteStrings(
    val language: AegisLanguage,
    val noteComposerLabel: String,
    val noteComposerPlaceholder: String,
    val noteComposerHelp: String,
    val noteSave: String,
    val noteSaving: String,
    val noteSaved: String,
    val noteSaveFailed: String,
    val noteDraftClear: String,
    val noteDraftCleared: String,
    val noteSavedSection: String,
    val noteBrowseHelp: String,
    val noteEmptyTitle: String,
    val noteEmptyBody: String,
    val noteListLoading: String,
    val noteLoading: String,
    val noteListError: String,
    val noteOpen: String,
    val noteDetailTitle: String,
    val noteOnDevice: String,
    val noteTimeUnknown: String,
    val noteSourceValue: String,
    val noteEdit: String,
    val noteDelete: String,
    val noteDeleteQuestion: String,
    val noteDeleteBody: String,
    val noteDeleted: String,
    val noteDeleteFailed: String,
    val noteMissingTitle: String,
    val noteMissingBody: String,
    val noteReadErrorTitle: String,
    val noteReadErrorBody: String,
    val noteEditTitle: String,
    val noteEditLabel: String,
    val noteSaveChanges: String,
    val noteChangesSaved: String,
    val noteUpdateFailed: String,
    val noteStale: String,
    val noteReloadSaved: String,
    val noteReloadConfirmTitle: String,
    val noteReloadConfirmBody: String,
    val noteChangePolicy: String,
    val noteEditPolicy: String,
    val noteDetailsSection: String,
    val noteDetailsEmpty: String,
    val noteDetailsLoading: String,
    val noteDetailsError: String,
    val noteAddDetail: String,
    val noteDetailReady: String,
    val noteDetailNeedsInfo: String,
    val noteDetailNoMatch: String,
    val noteDetailOutdated: String,
    val noteDetailRecorded: String,
    val noteDetailWhatItSays: String,
    val noteDetailAmount: String,
    val noteDetailMerchant: String,
    val noteDetailStatedTime: String,
    val noteDetailAddTitle: String,
    val noteDetailEditTitle: String,
    val noteDetailExcerpt: String,
    val noteDetailExcerptHelp: String,
    val noteDetailExcerptMissing: String,
    val noteDetailExcerptAmbiguous: String,
    val noteDetailExcerptFixed: String,
    val noteDetailDuplicateRange: String,
    val noteDetailDateFormat: String,
    val noteDetailTimeFormat: String,
    val noteDetailTimeNeedsDate: String,
    val noteDetailAmountError: String,
    val noteDetailAmountHint: String,
    val noteDetailMissingReason: String,
    val noteDetailIntervalError: String,
    val noteDetailLinkError: String,
    val noteDetailUnlinked: String,
    val noteDetailUnlinkFailed: String,
    val noteDetailReading: String,
    val noteDetailReadingHelp: String,
    val noteDetailAmountField: String,
    val noteDetailMerchantField: String,
    val noteDetailDateField: String,
    val noteDetailTimeField: String,
    val noteDetailIntervalDateField: String,
    val noteDetailIntervalTimeField: String,
    val noteDetailMissingField: String,
    val noteDetailSave: String,
    val noteDetailSaved: String,
    val noteDetailSaveFailed: String,
    val noteDetailConflict: String,
    val noteDetailReload: String,
    val noteDetailReloadConfirmTitle: String,
    val noteDetailReloadConfirmBody: String,
    val noteDetailMissingTitle: String,
    val noteDetailMissingBody: String,
    val noteDetailOutdatedBody: String,
    val noteDetailAddFromCurrent: String,
    val noteDetailLinkSection: String,
    val noteDetailNoLinks: String,
    val noteDetailChooseDraft: String,
    val noteDetailAttach: String,
    val noteDetailReject: String,
    val noteDetailUnlink: String,
    val noteDetailAttached: String,
    val noteDetailProposed: String,
    val noteDetailRefused: String,
    val noteDetailLinkHelp: String,
    val noteDetailUnlinkFirst: String,
    val noteDetailManualHelp: String,
    val noteRecordTransaction: String,
    val noteDiscussAi: String,
    val noteDiscussAiHelp: String,
    val noteDraftTitle: String,
    val noteDraftEmpty: String,
    val noteDraftLoading: String,
    val noteDraftLoadError: String,
    val noteDraftHelp: String,
    val noteDraftAmount: String,
    val noteDraftTime: String,
    val noteDraftMemo: String,
    val noteDraftAlreadyAttached: String,
    val noteDraftProposedState: String,
    val noteDraftRefusedState: String,
    val noteDraftOtherActive: String,
    val noteDraftAttachedMsg: String,
    val noteDraftRefusedMsg: String,
    val noteDraftActionFailed: String,
    val noteDraftDeciding: String,
    val noteDraftDirectionOut: String,
    val noteDraftDirectionIn: String,
    val noteDraftDirectionUnknown: String,
    val noteDraftAmountUnread: String,
    val noteDraftTimeUnread: String,
    val noteDraftNoMemo: String,
    val chatAttachedNote: String,
    val chatAttachedNoteHelp: String,
    val chatRemoveAttachedNote: String,
) {
    /** "1 note" / "3 notes" / "3 ghi chú". */
    fun noteCount(count: Int): String = when (language) {
        AegisLanguage.ENGLISH -> if (count == 1) "1 note" else "$count notes"
        AegisLanguage.VIETNAMESE -> "$count ghi chú"
    }

    /** "2 details" / "2 chi tiết". */
    fun noteDetailCount(count: Int): String = when (language) {
        AegisLanguage.ENGLISH -> if (count == 1) "1 detail" else "$count details"
        AegisLanguage.VIETNAMESE -> "$count chi tiết"
    }

    /** "1 linked to a bank notification" / "2 đã gắn thông báo ngân hàng". */
    fun noteLinkedCount(count: Int): String = when (language) {
        AegisLanguage.ENGLISH ->
            if (count == 1) "1 linked to a bank notification"
            else "$count linked to bank notifications"
        AegisLanguage.VIETNAMESE -> "$count đã gắn thông báo ngân hàng"
    }

    /** "1 suggested match" / "2 đề xuất khớp". */
    fun noteProposedCount(count: Int): String = when (language) {
        AegisLanguage.ENGLISH -> if (count == 1) "1 suggested match" else "$count suggested matches"
        AegisLanguage.VIETNAMESE -> "$count đề xuất khớp"
    }

    /** "1 recorded in the ledger" / "2 đã ghi vào sổ quỹ". */
    fun noteRecordedCount(count: Int): String = when (language) {
        AegisLanguage.ENGLISH ->
            if (count == 1) "1 recorded in the ledger" else "$count recorded in the ledger"
        AegisLanguage.VIETNAMESE -> "$count đã ghi vào sổ quỹ"
    }
}

data class AegisStrings(
    val language: AegisLanguage,
    val tabHome: String,
    val tabLedger: String,
    val tabNotes: String,
    val tabChat: String,
    val tabReview: String,
    val homeTitle: String,
    val homePrompt: String,
    val launcherLedger: String,
    val launcherAnalytics: String,
    val launcherNotes: String,
    val launcherChat: String,
    val launcherReview: String,
    val launcherManual: String,
    val launcherCategories: String,
    val launcherSettings: String,
    val analyticsTitle: String,
    val notesTitle: String,
    val chatTitle: String,
    val thisMonthSpending: String,
    val showAmounts: String,
    val hideAmounts: String,
    val amountsHidden: String,
    val reviewPending: String,
    val settingsTitle: String,
    val settingsPreferences: String,
    val settingsLanguage: String,
    val settingsAppearance: String,
    val settingsConfiguration: String,
    val settingsBankNotifications: String,
    val settingsBankDescription: String,
    val settingsHostedProvider: String,
    val settingsHostedDescription: String,
    val chooseLanguage: String,
    val appearanceSystem: String,
    val appearanceLight: String,
    val appearanceDark: String,
    val back: String,
    val close: String,
    val orMore: String,
    val statusConfirmed: String,
    val statusNeedsReview: String,
    val statusDismissed: String,
    val statusProposedLink: String,
    val statusAttached: String,
    val statusRejected: String,
    val statusUnlinked: String,
    val discardTitle: String,
    val discardKeepEditing: String,
    val discardConfirm: String,
    val greetingMorning: String,
    val greetingAfternoon: String,
    val greetingEvening: String,
    val loadingReading: String,
    val retry: String,
    val readErrorStored: String,
    val addRecord: String,
    val analyticsPreviousMonth: String,
    val analyticsNextMonth: String,
    val analyticsCustomRange: String,
    val kpiSpending: String,
    val kpiIncome: String,
    val kpiTransfers: String,
    val kpiNet: String,
    val netUnavailable: String,
    val netNote: String,
    val transfersNote: String,
    val categorySection: String,
    val categoryShare: String,
    val openCategoryRecords: String,
    val dailySection: String,
    val showAsList: String,
    val showAsChart: String,
    val emptyPeriodTitle: String,
    val emptyPeriodBody: String,
    val unknownCategory: String,
    val searchPlaceholder: String,
    val searchDescription: String,
    val clearSearch: String,
    val filtersShow: String,
    val filtersHide: String,
    val filterType: String,
    val filterCategory: String,
    val filterDates: String,
    val allTypes: String,
    val allCategories: String,
    val clearAll: String,
    val fromDate: String,
    val throughDate: String,
    val dateHelp: String,
    val emptyLedgerTitle: String,
    val emptyLedgerBody: String,
    val noMatchesTitle: String,
    val noMatchesBody: String,
    val clearFilters: String,
    val pagePrevious: String,
    val pageNext: String,
    val storedOnDevice: String,
    val typeExpense: String,
    val typeIncome: String,
    val typeTransfer: String,
    val statusDraft: String,
    val detailTitle: String,
    val detailEdit: String,
    val detailDelete: String,
    val detailDeleteQuestion: String,
    val detailDeleteBody: String,
    val detailDeleteConfirm: String,
    val detailDeleting: String,
    val cancel: String,
    val detailLoading: String,
    val detailMissingTitle: String,
    val detailMissingBody: String,
    val detailReadErrorTitle: String,
    val detailReadErrorBody: String,
    val detailCategory: String,
    val detailWhen: String,
    val detailSource: String,
    val detailNote: String,
    val detailRecordId: String,
    val detailRecordedAt: String,
    val detailCorrectedAt: String,
    val detailType: String,
    val sourceManual: String,
    val sourceBank: String,
    val staleConflict: String,
    val reload: String,
    val recordGone: String,
    val deleteFailed: String,
    val today: String,
    val yesterday: String,
    val scopedSpendingTitle: String,
    val scopedIncomeTitle: String,
    val scopedTransfersTitle: String,
    val scopedSpendingNote: String,
    val scopedIncomeNote: String,
    val scopedTransfersNote: String,
    val emptyScopeBody: String,
    val noCategorySpending: String,
    val noDailySpending: String,
    val noCategory: String,
    val archivedLabel: String,
    val thisMonth: String,
    val drillHint: String,
    val note: NoteStrings,
) {

    // The Notes and AI Chat handoff copy lives in [NoteStrings] so the shared vocabulary
    // stays one immutable value without one oversized constructor.
    val noteComposerLabel: String get() = note.noteComposerLabel
    val noteComposerPlaceholder: String get() = note.noteComposerPlaceholder
    val noteComposerHelp: String get() = note.noteComposerHelp
    val noteSave: String get() = note.noteSave
    val noteSaving: String get() = note.noteSaving
    val noteSaved: String get() = note.noteSaved
    val noteSaveFailed: String get() = note.noteSaveFailed
    val noteDraftClear: String get() = note.noteDraftClear
    val noteDraftCleared: String get() = note.noteDraftCleared
    val noteSavedSection: String get() = note.noteSavedSection
    val noteBrowseHelp: String get() = note.noteBrowseHelp
    val noteEmptyTitle: String get() = note.noteEmptyTitle
    val noteEmptyBody: String get() = note.noteEmptyBody
    val noteListLoading: String get() = note.noteListLoading
    val noteLoading: String get() = note.noteLoading
    val noteListError: String get() = note.noteListError
    val noteOpen: String get() = note.noteOpen
    val noteDetailTitle: String get() = note.noteDetailTitle
    val noteOnDevice: String get() = note.noteOnDevice
    val noteTimeUnknown: String get() = note.noteTimeUnknown
    val noteSourceValue: String get() = note.noteSourceValue
    val noteEdit: String get() = note.noteEdit
    val noteDelete: String get() = note.noteDelete
    val noteDeleteQuestion: String get() = note.noteDeleteQuestion
    val noteDeleteBody: String get() = note.noteDeleteBody
    val noteDeleted: String get() = note.noteDeleted
    val noteDeleteFailed: String get() = note.noteDeleteFailed
    val noteMissingTitle: String get() = note.noteMissingTitle
    val noteMissingBody: String get() = note.noteMissingBody
    val noteReadErrorTitle: String get() = note.noteReadErrorTitle
    val noteReadErrorBody: String get() = note.noteReadErrorBody
    val noteEditTitle: String get() = note.noteEditTitle
    val noteEditLabel: String get() = note.noteEditLabel
    val noteSaveChanges: String get() = note.noteSaveChanges
    val noteChangesSaved: String get() = note.noteChangesSaved
    val noteUpdateFailed: String get() = note.noteUpdateFailed
    val noteStale: String get() = note.noteStale
    val noteReloadSaved: String get() = note.noteReloadSaved
    val noteReloadConfirmTitle: String get() = note.noteReloadConfirmTitle
    val noteReloadConfirmBody: String get() = note.noteReloadConfirmBody
    val noteChangePolicy: String get() = note.noteChangePolicy
    val noteEditPolicy: String get() = note.noteEditPolicy
    val noteDetailsSection: String get() = note.noteDetailsSection
    val noteDetailsEmpty: String get() = note.noteDetailsEmpty
    val noteDetailsLoading: String get() = note.noteDetailsLoading
    val noteDetailsError: String get() = note.noteDetailsError
    val noteAddDetail: String get() = note.noteAddDetail
    val noteDetailReady: String get() = note.noteDetailReady
    val noteDetailNeedsInfo: String get() = note.noteDetailNeedsInfo
    val noteDetailNoMatch: String get() = note.noteDetailNoMatch
    val noteDetailOutdated: String get() = note.noteDetailOutdated
    val noteDetailRecorded: String get() = note.noteDetailRecorded
    val noteDetailWhatItSays: String get() = note.noteDetailWhatItSays
    val noteDetailAmount: String get() = note.noteDetailAmount
    val noteDetailMerchant: String get() = note.noteDetailMerchant
    val noteDetailStatedTime: String get() = note.noteDetailStatedTime
    val noteDetailAddTitle: String get() = note.noteDetailAddTitle
    val noteDetailEditTitle: String get() = note.noteDetailEditTitle
    val noteDetailExcerpt: String get() = note.noteDetailExcerpt
    val noteDetailExcerptHelp: String get() = note.noteDetailExcerptHelp
    val noteDetailExcerptMissing: String get() = note.noteDetailExcerptMissing
    val noteDetailExcerptAmbiguous: String get() = note.noteDetailExcerptAmbiguous
    val noteDetailExcerptFixed: String get() = note.noteDetailExcerptFixed
    val noteDetailDuplicateRange: String get() = note.noteDetailDuplicateRange
    val noteDetailDateFormat: String get() = note.noteDetailDateFormat
    val noteDetailTimeFormat: String get() = note.noteDetailTimeFormat
    val noteDetailTimeNeedsDate: String get() = note.noteDetailTimeNeedsDate
    val noteDetailAmountError: String get() = note.noteDetailAmountError
    val noteDetailAmountHint: String get() = note.noteDetailAmountHint
    val noteDetailMissingReason: String get() = note.noteDetailMissingReason
    val noteDetailIntervalError: String get() = note.noteDetailIntervalError
    val noteDetailLinkError: String get() = note.noteDetailLinkError
    val noteDetailUnlinked: String get() = note.noteDetailUnlinked
    val noteDetailUnlinkFailed: String get() = note.noteDetailUnlinkFailed
    val noteDetailReading: String get() = note.noteDetailReading
    val noteDetailReadingHelp: String get() = note.noteDetailReadingHelp
    val noteDetailAmountField: String get() = note.noteDetailAmountField
    val noteDetailMerchantField: String get() = note.noteDetailMerchantField
    val noteDetailDateField: String get() = note.noteDetailDateField
    val noteDetailTimeField: String get() = note.noteDetailTimeField
    val noteDetailIntervalDateField: String get() = note.noteDetailIntervalDateField
    val noteDetailIntervalTimeField: String get() = note.noteDetailIntervalTimeField
    val noteDetailMissingField: String get() = note.noteDetailMissingField
    val noteDetailSave: String get() = note.noteDetailSave
    val noteDetailSaved: String get() = note.noteDetailSaved
    val noteDetailSaveFailed: String get() = note.noteDetailSaveFailed
    val noteDetailConflict: String get() = note.noteDetailConflict
    val noteDetailReload: String get() = note.noteDetailReload
    val noteDetailReloadConfirmTitle: String get() = note.noteDetailReloadConfirmTitle
    val noteDetailReloadConfirmBody: String get() = note.noteDetailReloadConfirmBody
    val noteDetailMissingTitle: String get() = note.noteDetailMissingTitle
    val noteDetailMissingBody: String get() = note.noteDetailMissingBody
    val noteDetailOutdatedBody: String get() = note.noteDetailOutdatedBody
    val noteDetailAddFromCurrent: String get() = note.noteDetailAddFromCurrent
    val noteDetailLinkSection: String get() = note.noteDetailLinkSection
    val noteDetailNoLinks: String get() = note.noteDetailNoLinks
    val noteDetailChooseDraft: String get() = note.noteDetailChooseDraft
    val noteDetailAttach: String get() = note.noteDetailAttach
    val noteDetailReject: String get() = note.noteDetailReject
    val noteDetailUnlink: String get() = note.noteDetailUnlink
    val noteDetailAttached: String get() = note.noteDetailAttached
    val noteDetailProposed: String get() = note.noteDetailProposed
    val noteDetailRefused: String get() = note.noteDetailRefused
    val noteDetailLinkHelp: String get() = note.noteDetailLinkHelp
    val noteDetailUnlinkFirst: String get() = note.noteDetailUnlinkFirst
    val noteDetailManualHelp: String get() = note.noteDetailManualHelp
    val noteRecordTransaction: String get() = note.noteRecordTransaction
    val noteDiscussAi: String get() = note.noteDiscussAi
    val noteDiscussAiHelp: String get() = note.noteDiscussAiHelp
    val noteDraftTitle: String get() = note.noteDraftTitle
    val noteDraftEmpty: String get() = note.noteDraftEmpty
    val noteDraftLoading: String get() = note.noteDraftLoading
    val noteDraftLoadError: String get() = note.noteDraftLoadError
    val noteDraftHelp: String get() = note.noteDraftHelp
    val noteDraftAmount: String get() = note.noteDraftAmount
    val noteDraftTime: String get() = note.noteDraftTime
    val noteDraftMemo: String get() = note.noteDraftMemo
    val noteDraftAlreadyAttached: String get() = note.noteDraftAlreadyAttached
    val noteDraftProposedState: String get() = note.noteDraftProposedState
    val noteDraftRefusedState: String get() = note.noteDraftRefusedState
    val noteDraftOtherActive: String get() = note.noteDraftOtherActive
    val noteDraftAttachedMsg: String get() = note.noteDraftAttachedMsg
    val noteDraftRefusedMsg: String get() = note.noteDraftRefusedMsg
    val noteDraftActionFailed: String get() = note.noteDraftActionFailed
    val noteDraftDeciding: String get() = note.noteDraftDeciding
    val noteDraftDirectionOut: String get() = note.noteDraftDirectionOut
    val noteDraftDirectionIn: String get() = note.noteDraftDirectionIn
    val noteDraftDirectionUnknown: String get() = note.noteDraftDirectionUnknown
    val noteDraftAmountUnread: String get() = note.noteDraftAmountUnread
    val noteDraftTimeUnread: String get() = note.noteDraftTimeUnread
    val noteDraftNoMemo: String get() = note.noteDraftNoMemo
    val chatAttachedNote: String get() = note.chatAttachedNote
    val chatAttachedNoteHelp: String get() = note.chatAttachedNoteHelp
    val chatRemoveAttachedNote: String get() = note.chatRemoveAttachedNote

    /** The label of one root tab. */
    fun tabLabel(tab: ShellTab): String = when (tab) {
        ShellTab.HOME -> tabHome
        ShellTab.LEDGER -> tabLedger
        ShellTab.NOTES -> tabNotes
        ShellTab.CHAT -> tabChat
        ShellTab.REVIEW -> tabReview
    }

    /** The TalkBack description of one root tab, including the Review waiting count. */
    fun tabDescription(tab: ShellTab, reviewCount: Int?): String = when (tab) {
        ShellTab.REVIEW -> reviewWaiting(reviewCount ?: 0)
        else -> tabLabel(tab)
    }

    /** "Review, 3 waiting" / "Duyệt tin, 3 đang chờ". */
    fun reviewWaiting(count: Int): String = when (language) {
        AegisLanguage.ENGLISH -> if (count > 0) "$tabReview, $count waiting" else tabReview
        AegisLanguage.VIETNAMESE -> if (count > 0) "$tabReview, $count đang chờ" else tabReview
    }

    /** "Open Ledger" / "Mở Sổ quỹ": what a feature launcher announces. */
    fun openLauncher(label: String): String = when (language) {
        AegisLanguage.ENGLISH -> "Open $label"
        AegisLanguage.VIETNAMESE -> "Mở $label"
    }

    /** "3 waiting" / "3 đang chờ": the Home badge beside the Review launcher. */
    fun pendingCount(count: Int): String = when (language) {
        AegisLanguage.ENGLISH -> "$count waiting"
        AegisLanguage.VIETNAMESE -> "$count đang chờ"
    }

    /** The name of a language in its own language. */
    fun languageName(language: AegisLanguage): String = when (language) {
        AegisLanguage.ENGLISH -> "English"
        AegisLanguage.VIETNAMESE -> "Tiếng Việt"
    }

    /** The greeting for the device's own clock hour, without inventing a user name. */
    fun greeting(hourOfDay: Int): String = when {
        hourOfDay < 12 -> greetingMorning
        hourOfDay < 18 -> greetingAfternoon
        else -> greetingEvening
    }

    /** "1 active filter" / "2 active filters" / "2 bộ lọc". */
    fun activeFilterCount(count: Int): String = when (language) {
        AegisLanguage.ENGLISH -> if (count == 1) "1 active filter" else "$count active filters"
        AegisLanguage.VIETNAMESE -> "$count bộ lọc"
    }

    /** "Page 2" / "Trang 2". */
    fun pageNumber(pageIndex: Int): String = when (language) {
        AegisLanguage.ENGLISH -> "Page ${pageIndex + 1}"
        AegisLanguage.VIETNAMESE -> "Trang ${pageIndex + 1}"
    }

    /** "30 days" / "30 ngày": the span of the chosen period. */
    fun dayCount(count: Int): String = when (language) {
        AegisLanguage.ENGLISH -> if (count == 1) "1 day" else "$count days"
        AegisLanguage.VIETNAMESE -> "$count ngày"
    }

    /** "1 record" / "12 records" / "12 giao dịch". */
    fun recordCount(count: Int): String = when (language) {
        AegisLanguage.ENGLISH -> if (count == 1) "1 record" else "$count records"
        AegisLanguage.VIETNAMESE -> "$count giao dịch"
    }

    /** The stored record type, in this language. */
    fun typeName(type: TransactionType): String = when (type) {
        TransactionType.EXPENSE -> typeExpense
        TransactionType.INCOME -> typeIncome
        TransactionType.TRANSFER -> typeTransfer
    }

    /** The stored record provenance, in this language. */
    fun sourceName(source: RecordSource): String = when (source) {
        RecordSource.MANUAL -> sourceManual
        RecordSource.BANK_NOTIFICATION -> sourceBank
    }

    // The note journal delegates its wording to [NoteStrings]; these wrappers keep the
    // screen-facing vocabulary in one place.
    fun noteCount(count: Int): String = note.noteCount(count)
    fun noteDetailCount(count: Int): String = note.noteDetailCount(count)
    fun noteLinkedCount(count: Int): String = note.noteLinkedCount(count)
    fun noteProposedCount(count: Int): String = note.noteProposedCount(count)
    fun noteRecordedCount(count: Int): String = note.noteRecordedCount(count)
}

/** The root destinations, named once so the shell and the strings agree. */
enum class ShellTab { HOME, LEDGER, NOTES, CHAT, REVIEW }

private val englishNoteStrings = NoteStrings(
    language = AegisLanguage.ENGLISH,
    noteComposerLabel = "Quick note",
    noteComposerPlaceholder = "Lunch at Phở Thìn 65k…",
    noteComposerHelp = "Saved on this device · Works offline · Instant",
    noteSave = "Save note",
    noteSaving = "Saving…",
    noteSaved = "Saved on this device.",
    noteSaveFailed = "Couldn't save this note. Your text is still here; try again.",
    noteDraftClear = "Clear draft",
    noteDraftCleared = "Draft cleared.",
    noteSavedSection = "Saved notes",
    noteBrowseHelp = "See every note saved on this device.",
    noteEmptyTitle = "No saved notes yet",
    noteEmptyBody = "Notes you save on this device appear here. Save one from the Notes screen.",
    noteListLoading = "Loading saved notes…",
    noteLoading = "Loading note…",
    noteListError = "Saved notes couldn't be read. Nothing was changed.",
    noteOpen = "Open saved note",
    noteDetailTitle = "Saved note",
    noteOnDevice = "Saved on this device",
    noteTimeUnknown = "Saved time unavailable",
    noteSourceValue = "Written on this device",
    noteEdit = "Edit note",
    noteDelete = "Delete note",
    noteDeleteQuestion = "Delete this note?",
    noteDeleteBody = "This removes the note and its details from this device. This cannot be undone.",
    noteDeleted = "Note deleted from this device.",
    noteDeleteFailed = "Couldn't delete this note. It is still here.",
    noteMissingTitle = "This note no longer exists",
    noteMissingBody = "It may have been deleted on this device.",
    noteReadErrorTitle = "Couldn't read this note",
    noteReadErrorBody = "The saved note couldn't be read. Nothing was changed.",
    noteEditTitle = "Edit saved note",
    noteEditLabel = "Note text",
    noteSaveChanges = "Save changes",
    noteChangesSaved = "Changes saved on this device.",
    noteUpdateFailed = "Couldn't save changes. Your text is still here; try again.",
    noteStale = "This note changed after you opened it. Your edits are still here.",
    noteReloadSaved = "Reload saved note",
    noteReloadConfirmTitle = "Reload the saved note?",
    noteReloadConfirmBody = "Your edits in this form will be replaced by the saved note.",
    noteChangePolicy = "Editing or deleting this note also clears the details read from the old " +
        "text and removes links to bank notifications still waiting for review. A refusal you " +
        "already made and a link to a confirmed record stay. Your saved ledger records do not " +
        "change.",
    noteEditPolicy = "Saving new text clears the details read from the old text and the links " +
        "still waiting for review. Ledger records do not change.",
    noteDetailsSection = "Details from this note",
    noteDetailsEmpty = "No details yet. Add what the note says happened so you can match it " +
        "with a bank notification or record it.",
    noteDetailsLoading = "Loading details…",
    noteDetailsError = "Details couldn't be read. Nothing was changed.",
    noteAddDetail = "Add detail",
    noteDetailReady = "Ready",
    noteDetailNeedsInfo = "Needs more information",
    noteDetailNoMatch = "No bank match yet",
    noteDetailOutdated = "Out of date",
    noteDetailRecorded = "Recorded in the ledger",
    noteDetailWhatItSays = "What it says",
    noteDetailAmount = "Amount",
    noteDetailMerchant = "Where",
    noteDetailStatedTime = "Time in the note",
    noteDetailAddTitle = "Add detail",
    noteDetailEditTitle = "Edit detail",
    noteDetailExcerpt = "Exact text from the note",
    noteDetailExcerptHelp = "Choose a phrase that appears once in the note.",
    noteDetailExcerptMissing = "This text is not in the saved note.",
    noteDetailExcerptAmbiguous = "This text appears more than once. Include nearby words to " +
        "pick one.",
    noteDetailExcerptFixed = "The text this detail came from stays fixed.",
    noteDetailDuplicateRange = "This exact text already has a detail. Edit that detail " +
        "instead of adding another.",
    noteDetailDateFormat = "Use YYYY-MM-DD.",
    noteDetailTimeFormat = "Use HH:mm on a 24-hour clock.",
    noteDetailTimeNeedsDate = "A time in the note needs its date.",
    noteDetailAmountError = "Enter a whole amount in VND greater than zero.",
    noteDetailAmountHint = "Add an amount, or explain what is missing.",
    noteDetailMissingReason = "Explain what is missing when there is no amount.",
    noteDetailIntervalError = "The end must follow the start and needs its date and time.",
    noteDetailLinkError = "Link decisions couldn't be read.",
    noteDetailUnlinked = "Bank notification unlinked. The ledger is unchanged.",
    noteDetailUnlinkFailed = "Couldn't unlink. The link is unchanged; try again.",
    noteDetailReading = "What happened",
    noteDetailReadingHelp = "Describe only what this text says. No match is chosen " +
        "automatically.",
    noteDetailAmountField = "Amount in VND (optional)",
    noteDetailMerchantField = "Where (optional)",
    noteDetailDateField = "Date in the note (optional)",
    noteDetailTimeField = "Time in the note (optional)",
    noteDetailIntervalDateField = "End date (optional)",
    noteDetailIntervalTimeField = "End time (optional)",
    noteDetailMissingField = "What is missing (optional)",
    noteDetailSave = "Save detail",
    noteDetailSaved = "Detail saved.",
    noteDetailSaveFailed = "Couldn't save this detail. Your text is still here; try again.",
    noteDetailConflict = "This note or detail changed after you opened it. Your edits are " +
        "still here.",
    noteDetailReload = "Reload saved detail",
    noteDetailReloadConfirmTitle = "Reload the saved detail?",
    noteDetailReloadConfirmBody = "Your edits in this form will be replaced by the saved " +
        "detail.",
    noteDetailMissingTitle = "This detail no longer exists",
    noteDetailMissingBody = "It may have been removed or cleared after the note changed.",
    noteDetailOutdatedBody = "This detail was read from an older version of the note. Add a " +
        "new detail from the current text if it still applies.",
    noteDetailAddFromCurrent = "Add detail from current note",
    noteDetailLinkSection = "Bank notification",
    noteDetailNoLinks = "No bank notification is linked or suggested for this detail.",
    noteDetailChooseDraft = "Choose a pending bank notification",
    noteDetailAttach = "Attach",
    noteDetailReject = "Reject",
    noteDetailUnlink = "Unlink",
    noteDetailAttached = "Linked to a bank notification",
    noteDetailProposed = "Match suggested; not attached",
    noteDetailRefused = "Match refused (decision kept)",
    noteDetailLinkHelp = "Linking never confirms a bank notification and never changes the " +
        "ledger.",
    noteDetailUnlinkFirst = "Unlink the current bank notification before choosing another.",
    noteDetailManualHelp = "Recording by hand needs an amount and no active bank notification.",
    noteRecordTransaction = "Record a transaction",
    noteDiscussAi = "Discuss with AI",
    noteDiscussAiHelp = "Opens AI Chat with this note attached. Nothing is sent until you " +
        "send it.",
    noteDraftTitle = "Pending bank notifications",
    noteDraftEmpty = "No bank notifications are waiting for review.",
    noteDraftLoading = "Loading pending bank notifications…",
    noteDraftLoadError = "Pending bank notifications couldn't be read. No match was chosen.",
    noteDraftHelp = "This list is limited and never selects a match for you. A refusal is " +
        "kept for this detail and notification.",
    noteDraftAmount = "Amount",
    noteDraftTime = "Bank time",
    noteDraftMemo = "Bank note",
    noteDraftAlreadyAttached = "Already attached to this detail.",
    noteDraftProposedState = "Suggested for this detail; choose Attach or Reject.",
    noteDraftRefusedState = "Refused before. This pair cannot be suggested again.",
    noteDraftOtherActive = "Unlink the current bank notification before choosing another.",
    noteDraftAttachedMsg = "Attached. The bank notification and the ledger are unchanged.",
    noteDraftRefusedMsg = "Refused. That decision is kept.",
    noteDraftActionFailed = "The decision didn't complete. Nothing financial changed; reload " +
        "and try again.",
    noteDraftDeciding = "Saving decision…",
    noteDraftDirectionOut = "Money out",
    noteDraftDirectionIn = "Money in",
    noteDraftDirectionUnknown = "Direction not clear",
    noteDraftAmountUnread = "Amount not read",
    noteDraftTimeUnread = "Time not read",
    noteDraftNoMemo = "No bank note",
    chatAttachedNote = "Attached note",
    chatAttachedNoteHelp = "This text is included when you send. Nothing has been sent.",
    chatRemoveAttachedNote = "Remove attached note",
)

val englishStrings = AegisStrings(
    language = AegisLanguage.ENGLISH,
    tabHome = "Home",
    tabLedger = "Ledger",
    tabNotes = "Notes",
    tabChat = "AI Chat",
    tabReview = "Review",
    homeTitle = "Home",
    homePrompt = "What would you like to do?",
    launcherLedger = "Ledger",
    launcherAnalytics = "Analytics",
    launcherNotes = "Notes",
    launcherChat = "AI Chat",
    launcherReview = "Review",
    launcherManual = "Manual record",
    launcherCategories = "Categories & rules",
    launcherSettings = "Settings",
    analyticsTitle = "Analytics",
    notesTitle = "Notes",
    chatTitle = "AI Chat",
    thisMonthSpending = "Spending this month",
    showAmounts = "Show amounts",
    hideAmounts = "Hide amounts",
    amountsHidden = "Amounts hidden",
    reviewPending = "Needs decision",
    settingsTitle = "Settings",
    settingsPreferences = "PREFERENCES",
    settingsLanguage = "Language",
    settingsAppearance = "Appearance",
    settingsConfiguration = "CONFIGURATION",
    settingsBankNotifications = "Bank notification settings",
    settingsBankDescription = "Choose which bank apps may become reviewable drafts. Manual " +
        "recording works with or without it.",
    settingsHostedProvider = "Hosted provider settings",
    settingsHostedDescription = "Choose a provider, endpoint, model and credential. Hosted " +
        "requests need network access and send locally masked context.",
    chooseLanguage = "Interface language",
    appearanceSystem = "System",
    appearanceLight = "Light",
    appearanceDark = "Dark",
    back = "Back",
    close = "Close",
    orMore = "or more",
    statusConfirmed = "Confirmed",
    statusNeedsReview = "Needs decision",
    statusDismissed = "Dismissed",
    statusProposedLink = "Proposed link",
    statusAttached = "Attached",
    statusRejected = "Rejected",
    statusUnlinked = "Unlinked",
    discardTitle = "Discard changes?",
    discardKeepEditing = "Keep editing",
    discardConfirm = "Discard",
    greetingMorning = "Good morning",
    greetingAfternoon = "Good afternoon",
    greetingEvening = "Good evening",
    loadingReading = "Reading…",
    retry = "Try again",
    readErrorStored = "The stored records could not be read. Nothing was changed.",
    addRecord = "Add record",
    analyticsPreviousMonth = "Previous month",
    analyticsNextMonth = "Next month",
    analyticsCustomRange = "Custom range",
    kpiSpending = "Spending",
    kpiIncome = "Income",
    kpiTransfers = "Internal transfers",
    kpiNet = "Net cash flow",
    netUnavailable = "Not exactly known",
    netNote = "Income minus spending · internal transfers excluded",
    transfersNote = "Money moved between your own accounts",
    categorySection = "Spending by category",
    categoryShare = "of spending",
    openCategoryRecords = "Open records",
    dailySection = "Daily spending",
    showAsList = "Show as list",
    showAsChart = "Show as chart",
    emptyPeriodTitle = "No confirmed records in this period",
    emptyPeriodBody = "Confirmed spending, income and transfers recorded for these " +
        "days appear here.",
    unknownCategory = "Unknown category",
    searchPlaceholder = "Search description or note",
    searchDescription = "Search records",
    clearSearch = "Clear search",
    filtersShow = "Filters",
    filtersHide = "Hide filters",
    filterType = "Type",
    filterCategory = "Category",
    filterDates = "Dates",
    allTypes = "All types",
    allCategories = "All categories",
    clearAll = "Clear all",
    fromDate = "From",
    throughDate = "Through",
    dateHelp = "Inclusive YYYY-MM-DD days · device time zone.",
    emptyLedgerTitle = "No transactions yet",
    emptyLedgerBody = "Confirmed records you save appear here, newest first.",
    noMatchesTitle = "No matching transactions",
    noMatchesBody = "No saved records match these filters.",
    clearFilters = "Clear filters",
    pagePrevious = "Previous",
    pageNext = "Next",
    storedOnDevice = "Stored on this device",
    typeExpense = "Expense",
    typeIncome = "Income",
    typeTransfer = "Internal transfer",
    statusDraft = "Draft",
    detailTitle = "Transaction",
    detailEdit = "Edit transaction",
    detailDelete = "Delete transaction",
    detailDeleteQuestion = "Delete this transaction?",
    detailDeleteBody = "Deleting removes this record from the ledger. This cannot be " +
        "undone.",
    detailDeleteConfirm = "Delete",
    detailDeleting = "Deleting…",
    cancel = "Cancel",
    detailLoading = "Loading transaction…",
    detailMissingTitle = "This transaction no longer exists",
    detailMissingBody = "It is not in the ledger, so there is nothing to show or delete " +
        "here.",
    detailReadErrorTitle = "Couldn't read this transaction",
    detailReadErrorBody = "The stored record could not be read. Nothing was changed.",
    detailCategory = "Category",
    detailWhen = "When",
    detailSource = "Source",
    detailNote = "Note",
    detailRecordId = "Record ID",
    detailRecordedAt = "Recorded",
    detailCorrectedAt = "Corrected",
    detailType = "Type",
    sourceManual = "Manual",
    sourceBank = "Bank notification",
    staleConflict = "This record changed elsewhere. Reload it before deleting.",
    reload = "Reload",
    recordGone = "This record is no longer available.",
    deleteFailed = "This record could not be removed.",
    today = "Today",
    yesterday = "Yesterday",
    scopedSpendingTitle = "Spending in this period",
    scopedIncomeTitle = "Income in this period",
    scopedTransfersTitle = "Transfers in this period",
    scopedSpendingNote = "Exactly the confirmed expenses behind the spending figure. " +
        "Income, internal transfers and unconfirmed drafts are not in this list.",
    scopedIncomeNote = "Money arriving, not spending. This list is not narrowed by the " +
        "category filter.",
    scopedTransfersNote = "Money moved between your own accounts: neither spending nor " +
        "income.",
    emptyScopeBody = "No confirmed records match this period and category.",
    noCategorySpending = "No category has spending in this period.",
    noDailySpending = "No confirmed spending on any day of this period.",
    noCategory = "No category",
    archivedLabel = "Archived",
    thisMonth = "This month",
    drillHint = "Select a row to open its records.",
    note = englishNoteStrings,
)

private val vietnameseNoteStrings = NoteStrings(
    language = AegisLanguage.VIETNAMESE,
    noteComposerLabel = "Ghi chú nhanh",
    noteComposerPlaceholder = "Trưa nay ăn phở Thìn 65k…",
    noteComposerHelp = "Lưu trên thiết bị · Không cần mạng · Tức thì",
    noteSave = "Lưu ghi chú",
    noteSaving = "Đang lưu…",
    noteSaved = "Đã lưu trên thiết bị.",
    noteSaveFailed = "Không lưu được ghi chú này. Nội dung vẫn còn đây; hãy thử lại.",
    noteDraftClear = "Xóa bản nháp",
    noteDraftCleared = "Đã xóa bản nháp.",
    noteSavedSection = "Ghi chú đã lưu",
    noteBrowseHelp = "Xem mọi ghi chú đã lưu trên thiết bị.",
    noteEmptyTitle = "Chưa có ghi chú nào",
    noteEmptyBody = "Ghi chú bạn lưu trên thiết bị sẽ hiện ở đây. Hãy lưu một ghi chú từ màn " +
        "hình Ghi chép.",
    noteListLoading = "Đang tải ghi chú đã lưu…",
    noteLoading = "Đang tải ghi chú…",
    noteListError = "Không đọc được ghi chú đã lưu. Không có thay đổi nào.",
    noteOpen = "Mở ghi chú đã lưu",
    noteDetailTitle = "Ghi chú đã lưu",
    noteOnDevice = "Lưu trên thiết bị",
    noteTimeUnknown = "Không rõ thời điểm lưu",
    noteSourceValue = "Viết trên thiết bị này",
    noteEdit = "Sửa ghi chú",
    noteDelete = "Xóa ghi chú",
    noteDeleteQuestion = "Xóa ghi chú này?",
    noteDeleteBody = "Ghi chú và các chi tiết của nó sẽ bị xóa khỏi thiết bị. Không thể hoàn tác.",
    noteDeleted = "Đã xóa ghi chú khỏi thiết bị.",
    noteDeleteFailed = "Không xóa được ghi chú này. Ghi chú vẫn còn.",
    noteMissingTitle = "Ghi chú này không còn tồn tại",
    noteMissingBody = "Có thể ghi chú đã bị xóa trên thiết bị.",
    noteReadErrorTitle = "Không đọc được ghi chú này",
    noteReadErrorBody = "Không đọc được ghi chú đã lưu. Không có thay đổi nào.",
    noteEditTitle = "Sửa ghi chú đã lưu",
    noteEditLabel = "Nội dung ghi chú",
    noteSaveChanges = "Lưu thay đổi",
    noteChangesSaved = "Đã lưu thay đổi trên thiết bị.",
    noteUpdateFailed = "Không lưu được thay đổi. Văn bản vẫn còn đây; hãy thử lại.",
    noteStale = "Ghi chú đã thay đổi sau khi bạn mở. Phần bạn sửa vẫn còn đây.",
    noteReloadSaved = "Tải lại ghi chú đã lưu",
    noteReloadConfirmTitle = "Tải lại ghi chú đã lưu?",
    noteReloadConfirmBody = "Phần bạn sửa trong biểu mẫu sẽ được thay bằng ghi chú đã lưu.",
    noteChangePolicy = "Sửa hoặc xóa ghi chú này cũng xóa các chi tiết đọc từ văn bản cũ và " +
        "gỡ liên kết với thông báo ngân hàng đang chờ duyệt. Quyết định từ chối trước đó và " +
        "liên kết với giao dịch đã xác nhận vẫn được giữ. Các giao dịch đã lưu trong sổ quỹ " +
        "không thay đổi.",
    noteEditPolicy = "Lưu văn bản mới sẽ xóa các chi tiết đọc từ văn bản cũ và liên kết đang " +
        "chờ duyệt. Giao dịch trong sổ quỹ không thay đổi.",
    noteDetailsSection = "Chi tiết từ ghi chú",
    noteDetailsEmpty = "Chưa có chi tiết nào. Thêm điều ghi chú nói để có thể khớp với thông " +
        "báo ngân hàng hoặc ghi giao dịch.",
    noteDetailsLoading = "Đang tải chi tiết…",
    noteDetailsError = "Không đọc được chi tiết. Không có thay đổi nào.",
    noteAddDetail = "Thêm chi tiết",
    noteDetailReady = "Đã đủ thông tin",
    noteDetailNeedsInfo = "Cần thêm thông tin",
    noteDetailNoMatch = "Chưa khớp ngân hàng",
    noteDetailOutdated = "Đã cũ",
    noteDetailRecorded = "Đã ghi vào sổ quỹ",
    noteDetailWhatItSays = "Nội dung ghi",
    noteDetailAmount = "Số tiền",
    noteDetailMerchant = "Nơi giao dịch",
    noteDetailStatedTime = "Thời gian trong ghi chú",
    noteDetailAddTitle = "Thêm chi tiết",
    noteDetailEditTitle = "Sửa chi tiết",
    noteDetailExcerpt = "Đúng đoạn văn trong ghi chú",
    noteDetailExcerptHelp = "Chọn một cụm chỉ xuất hiện một lần trong ghi chú.",
    noteDetailExcerptMissing = "Đoạn này không có trong ghi chú đã lưu.",
    noteDetailExcerptAmbiguous = "Đoạn này xuất hiện nhiều lần. Thêm các từ xung quanh để " +
        "chọn đúng một chỗ.",
    noteDetailExcerptFixed = "Đoạn văn tạo ra chi tiết này được giữ cố định.",
    noteDetailDuplicateRange = "Đoạn văn này đã có chi tiết. Hãy sửa chi tiết đó thay vì " +
        "thêm chi tiết mới.",
    noteDetailDateFormat = "Dùng định dạng YYYY-MM-DD.",
    noteDetailTimeFormat = "Dùng HH:mm theo 24 giờ.",
    noteDetailTimeNeedsDate = "Giờ trong ghi chú cần ngày đi kèm.",
    noteDetailAmountError = "Nhập số tiền VND nguyên, lớn hơn 0.",
    noteDetailAmountHint = "Thêm số tiền, hoặc giải thích điều còn thiếu.",
    noteDetailMissingReason = "Giải thích điều còn thiếu khi chưa có số tiền.",
    noteDetailIntervalError = "Điểm kết thúc phải sau điểm bắt đầu và cần ngày, giờ.",
    noteDetailLinkError = "Không đọc được quyết định liên kết.",
    noteDetailUnlinked = "Đã hủy gắn thông báo ngân hàng. Sổ quỹ không thay đổi.",
    noteDetailUnlinkFailed = "Không hủy gắn được. Liên kết không đổi; hãy thử lại.",
    noteDetailReading = "Điều đã xảy ra",
    noteDetailReadingHelp = "Chỉ mô tả điều đoạn văn nói. Không có khớp nào được chọn tự động.",
    noteDetailAmountField = "Số tiền bằng VND (không bắt buộc)",
    noteDetailMerchantField = "Nơi giao dịch (không bắt buộc)",
    noteDetailDateField = "Ngày trong ghi chú (không bắt buộc)",
    noteDetailTimeField = "Giờ trong ghi chú (không bắt buộc)",
    noteDetailIntervalDateField = "Ngày kết thúc (không bắt buộc)",
    noteDetailIntervalTimeField = "Giờ kết thúc (không bắt buộc)",
    noteDetailMissingField = "Thiếu thông tin gì (không bắt buộc)",
    noteDetailSave = "Lưu chi tiết",
    noteDetailSaved = "Đã lưu chi tiết.",
    noteDetailSaveFailed = "Không lưu được chi tiết này. Nội dung vẫn còn đây; hãy thử lại.",
    noteDetailConflict = "Ghi chú hoặc chi tiết đã thay đổi sau khi bạn mở. Phần bạn sửa vẫn " +
        "còn đây.",
    noteDetailReload = "Tải lại chi tiết đã lưu",
    noteDetailReloadConfirmTitle = "Tải lại chi tiết đã lưu?",
    noteDetailReloadConfirmBody = "Phần bạn sửa trong biểu mẫu sẽ được thay bằng chi tiết đã " +
        "lưu.",
    noteDetailMissingTitle = "Chi tiết này không còn tồn tại",
    noteDetailMissingBody = "Có thể chi tiết đã bị gỡ hoặc xóa khi ghi chú thay đổi.",
    noteDetailOutdatedBody = "Chi tiết này được đọc từ bản ghi chú cũ hơn. Hãy thêm chi tiết " +
        "mới từ văn bản hiện tại nếu vẫn đúng.",
    noteDetailAddFromCurrent = "Thêm chi tiết từ ghi chú hiện tại",
    noteDetailLinkSection = "Thông báo ngân hàng",
    noteDetailNoLinks = "Chưa có thông báo ngân hàng nào được gắn hoặc đề xuất cho chi tiết này.",
    noteDetailChooseDraft = "Chọn thông báo ngân hàng đang chờ",
    noteDetailAttach = "Gắn",
    noteDetailReject = "Từ chối",
    noteDetailUnlink = "Hủy gắn",
    noteDetailAttached = "Đã gắn thông báo ngân hàng",
    noteDetailProposed = "Có đề xuất khớp; chưa gắn",
    noteDetailRefused = "Đã từ chối khớp (giữ quyết định)",
    noteDetailLinkHelp = "Gắn liên kết không xác nhận thông báo ngân hàng và không thay đổi " +
        "sổ quỹ.",
    noteDetailUnlinkFirst = "Hãy hủy gắn thông báo hiện tại trước khi chọn thông báo khác.",
    noteDetailManualHelp = "Ghi tay cần có số tiền và không có thông báo ngân hàng đang gắn.",
    noteRecordTransaction = "Ghi giao dịch",
    noteDiscussAi = "Thảo luận với Trợ lý AI",
    noteDiscussAiHelp = "Mở Trợ lý AI với ghi chú này được đính kèm. Chưa có gì được gửi cho " +
        "đến khi bạn gửi.",
    noteDraftTitle = "Thông báo ngân hàng đang chờ",
    noteDraftEmpty = "Không có thông báo ngân hàng nào đang chờ duyệt.",
    noteDraftLoading = "Đang tải thông báo ngân hàng…",
    noteDraftLoadError = "Không đọc được thông báo ngân hàng đang chờ. Không có khớp nào được " +
        "chọn.",
    noteDraftHelp = "Danh sách có giới hạn và không tự chọn khớp. Quyết định từ chối được giữ " +
        "cho cặp chi tiết và thông báo này.",
    noteDraftAmount = "Số tiền",
    noteDraftTime = "Thời gian ngân hàng",
    noteDraftMemo = "Ghi chú của ngân hàng",
    noteDraftAlreadyAttached = "Đã gắn với chi tiết này.",
    noteDraftProposedState = "Được đề xuất cho chi tiết này; chọn Gắn hoặc Từ chối.",
    noteDraftRefusedState = "Đã từ chối trước đó. Cặp này không thể được đề xuất lại.",
    noteDraftOtherActive = "Hãy hủy gắn thông báo hiện tại trước khi chọn thông báo khác.",
    noteDraftAttachedMsg = "Đã gắn. Thông báo ngân hàng và sổ quỹ không thay đổi.",
    noteDraftRefusedMsg = "Đã từ chối. Quyết định này được giữ.",
    noteDraftActionFailed = "Quyết định chưa hoàn tất. Không có thay đổi tài chính nào; hãy " +
        "tải lại và thử lại.",
    noteDraftDeciding = "Đang lưu quyết định…",
    noteDraftDirectionOut = "Tiền ra",
    noteDraftDirectionIn = "Tiền vào",
    noteDraftDirectionUnknown = "Chưa rõ chiều tiền",
    noteDraftAmountUnread = "Chưa đọc được số tiền",
    noteDraftTimeUnread = "Chưa đọc được thời gian",
    noteDraftNoMemo = "Không có ghi chú ngân hàng",
    chatAttachedNote = "Ghi chú đính kèm",
    chatAttachedNoteHelp = "Văn bản này sẽ được gửi khi bạn bấm Gửi. Hiện chưa gửi gì.",
    chatRemoveAttachedNote = "Bỏ ghi chú đính kèm",
)

val vietnameseStrings = AegisStrings(
    language = AegisLanguage.VIETNAMESE,
    tabHome = "Trang chủ",
    tabLedger = "Sổ quỹ",
    tabNotes = "Ghi chép",
    tabChat = "Trợ lý AI",
    tabReview = "Duyệt tin",
    homeTitle = "Trang chủ",
    homePrompt = "Bạn muốn thực hiện tác vụ nào?",
    launcherLedger = "Sổ quỹ",
    launcherAnalytics = "Báo cáo",
    launcherNotes = "Ghi chép",
    launcherChat = "Trợ lý AI",
    launcherReview = "Duyệt tin",
    launcherManual = "Nhập tay",
    launcherCategories = "Danh mục & quy tắc",
    launcherSettings = "Cài đặt",
    analyticsTitle = "Báo cáo",
    notesTitle = "Ghi chép",
    chatTitle = "Trợ lý AI",
    thisMonthSpending = "Chi tiêu tháng này",
    showAmounts = "Hiện số tiền",
    hideAmounts = "Ẩn số tiền",
    amountsHidden = "Đã ẩn số tiền",
    reviewPending = "Cần xử lý",
    settingsTitle = "Cài đặt",
    settingsPreferences = "TÙY CHỌN",
    settingsLanguage = "Ngôn ngữ",
    settingsAppearance = "Giao diện",
    settingsConfiguration = "CẤU HÌNH",
    settingsBankNotifications = "Cài đặt thông báo ngân hàng",
    settingsBankDescription = "Chọn ứng dụng ngân hàng được tạo bản nháp để duyệt. Ghi tay " +
        "vẫn dùng được dù bật hay tắt mục này.",
    settingsHostedProvider = "Cài đặt nhà cung cấp AI",
    settingsHostedDescription = "Chọn nhà cung cấp, địa chỉ, mô hình và khóa. Yêu cầu trực " +
        "tuyến cần mạng và gửi ngữ cảnh đã được che cục bộ.",
    chooseLanguage = "Ngôn ngữ giao diện",
    appearanceSystem = "Theo hệ thống",
    appearanceLight = "Sáng",
    appearanceDark = "Tối",
    back = "Quay lại",
    close = "Đóng",
    orMore = "trở lên",
    statusConfirmed = "Đã xác nhận",
    statusNeedsReview = "Cần xử lý",
    statusDismissed = "Đã bỏ qua",
    statusProposedLink = "Đề xuất liên kết",
    statusAttached = "Đã gắn",
    statusRejected = "Đã từ chối",
    statusUnlinked = "Đã hủy gắn",
    discardTitle = "Bỏ thay đổi?",
    discardKeepEditing = "Tiếp tục sửa",
    discardConfirm = "Bỏ thay đổi",
    greetingMorning = "Chào buổi sáng",
    greetingAfternoon = "Chào buổi chiều",
    greetingEvening = "Chào buổi tối",
    loadingReading = "Đang đọc…",
    retry = "Thử lại",
    readErrorStored = "Không đọc được dữ liệu đã lưu. Không có thay đổi nào.",
    addRecord = "Thêm giao dịch",
    analyticsPreviousMonth = "Tháng trước",
    analyticsNextMonth = "Tháng sau",
    analyticsCustomRange = "Khoảng tự chọn",
    kpiSpending = "Chi tiêu",
    kpiIncome = "Thu nhập",
    kpiTransfers = "Chuyển khoản nội bộ",
    kpiNet = "Dòng tiền ròng",
    netUnavailable = "Chưa xác định chính xác",
    netNote = "Thu nhập trừ chi tiêu · không tính chuyển khoản nội bộ",
    transfersNote = "Tiền chuyển giữa các tài khoản của bạn",
    categorySection = "Chi tiêu theo danh mục",
    categoryShare = "tổng chi tiêu",
    openCategoryRecords = "Mở sổ",
    dailySection = "Chi tiêu theo ngày",
    showAsList = "Xem dạng danh sách",
    showAsChart = "Xem dạng biểu đồ",
    emptyPeriodTitle = "Không có giao dịch đã xác nhận trong kỳ này",
    emptyPeriodBody = "Chi tiêu, thu nhập và chuyển khoản đã xác nhận trong những ngày " +
        "này sẽ hiện ở đây.",
    unknownCategory = "Danh mục không rõ",
    searchPlaceholder = "Tìm theo mô tả hoặc ghi chú",
    searchDescription = "Tìm giao dịch",
    clearSearch = "Xóa tìm kiếm",
    filtersShow = "Bộ lọc",
    filtersHide = "Ẩn bộ lọc",
    filterType = "Loại",
    filterCategory = "Danh mục",
    filterDates = "Ngày",
    allTypes = "Tất cả loại",
    allCategories = "Tất cả danh mục",
    clearAll = "Xóa tất cả",
    fromDate = "Từ",
    throughDate = "Đến",
    dateHelp = "Ngày YYYY-MM-DD · múi giờ thiết bị · tính cả hai đầu.",
    emptyLedgerTitle = "Chưa có giao dịch",
    emptyLedgerBody = "Giao dịch đã xác nhận bạn lưu sẽ hiện ở đây, mới nhất trước.",
    noMatchesTitle = "Không có giao dịch phù hợp",
    noMatchesBody = "Không có giao dịch nào khớp bộ lọc này.",
    clearFilters = "Xóa bộ lọc",
    pagePrevious = "Trước",
    pageNext = "Sau",
    storedOnDevice = "Lưu trên thiết bị",
    typeExpense = "Chi tiêu",
    typeIncome = "Thu nhập",
    typeTransfer = "Chuyển khoản nội bộ",
    statusDraft = "Bản nháp",
    detailTitle = "Chi tiết giao dịch",
    detailEdit = "Chỉnh sửa giao dịch",
    detailDelete = "Xóa giao dịch",
    detailDeleteQuestion = "Xóa giao dịch này?",
    detailDeleteBody = "Xóa sẽ gỡ giao dịch khỏi sổ quỹ. Không thể hoàn tác.",
    detailDeleteConfirm = "Xóa",
    detailDeleting = "Đang xóa…",
    cancel = "Hủy",
    detailLoading = "Đang tải giao dịch…",
    detailMissingTitle = "Giao dịch này không còn tồn tại",
    detailMissingBody = "Giao dịch không có trong sổ quỹ nên không có gì để xem hay xóa.",
    detailReadErrorTitle = "Không đọc được giao dịch này",
    detailReadErrorBody = "Không đọc được dữ liệu đã lưu. Không có thay đổi nào.",
    detailCategory = "Danh mục",
    detailWhen = "Thời gian",
    detailSource = "Nguồn",
    detailNote = "Ghi chú",
    detailRecordId = "Mã giao dịch",
    detailRecordedAt = "Đã ghi",
    detailCorrectedAt = "Đã sửa",
    detailType = "Loại",
    sourceManual = "Nhập tay",
    sourceBank = "Thông báo ngân hàng",
    staleConflict = "Giao dịch đã thay đổi ở nơi khác. Hãy tải lại trước khi xóa.",
    reload = "Tải lại",
    recordGone = "Giao dịch này không còn khả dụng.",
    deleteFailed = "Không thể xóa giao dịch này.",
    today = "Hôm nay",
    yesterday = "Hôm qua",
    scopedSpendingTitle = "Chi tiêu trong kỳ",
    scopedIncomeTitle = "Thu nhập trong kỳ",
    scopedTransfersTitle = "Chuyển khoản trong kỳ",
    scopedSpendingNote = "Đúng các khoản chi đã xác nhận tạo nên con số chi tiêu. Thu " +
        "nhập, chuyển khoản và bản nháp chưa xác nhận không có trong danh sách này.",
    scopedIncomeNote = "Tiền đến, không phải chi tiêu. Danh sách này không lọc theo danh mục.",
    scopedTransfersNote = "Tiền chuyển giữa các tài khoản của bạn: không phải chi tiêu hay " +
        "thu nhập.",
    emptyScopeBody = "Không có giao dịch đã xác nhận khớp kỳ và danh mục này.",
    noCategorySpending = "Không có danh mục nào phát sinh chi tiêu trong kỳ này.",
    noDailySpending = "Không có chi tiêu đã xác nhận vào ngày nào trong kỳ này.",
    noCategory = "Không có danh mục",
    archivedLabel = "Đã lưu trữ",
    thisMonth = "Tháng này",
    drillHint = "Chọn một dòng để mở các giao dịch của dòng đó.",
    note = vietnameseNoteStrings,
)

/** The strings of one language. */
fun aegisStrings(language: AegisLanguage): AegisStrings = when (language) {
    AegisLanguage.ENGLISH -> englishStrings
    AegisLanguage.VIETNAMESE -> vietnameseStrings
}

/**
 * The shell vocabulary in the current language.
 *
 * English is the default so a screen composed outside the app theme (as several focused UI tests
 * do) still reads sensibly instead of failing on a missing provider.
 */
val LocalAegisStrings = staticCompositionLocalOf { englishStrings }
