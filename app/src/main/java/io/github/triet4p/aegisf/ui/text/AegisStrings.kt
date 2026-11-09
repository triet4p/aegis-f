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
) {
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
}

/** The root destinations, named once so the shell and the strings agree. */
enum class ShellTab { HOME, LEDGER, NOTES, CHAT, REVIEW }

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
