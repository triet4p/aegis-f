package io.github.triet4p.aegisf.ui.text

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.triet4p.aegisf.data.AegisLanguage

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
