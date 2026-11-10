package io.github.triet4p.aegisf.ui.chat

import io.github.triet4p.aegisf.data.AegisLanguage
import io.github.triet4p.aegisf.ui.ChatHistoryStatus
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The AI Chat destination's vocabulary.
 *
 * It is one immutable value per language, kept out of the shared shell constructor: the shell
 * strings are already at the Dalvik constructor limit, and this screen's copy is owned by S10.5.
 * English is the baseline; Vietnamese is a 1:1 translation of the same properties.
 */
internal data class ChatStrings(
    val language: AegisLanguage,
    val title: String,
    val historyAction: String,
    val newChatAction: String,
    val historyTitle: String,
    val historyEmptyTitle: String,
    val historyEmptyBody: String,
    val historyLoading: String,
    val historyError: String,
    val historyRetry: String,
    val historyOpen: String,
    val historyBusyNote: String,
    val composerPlaceholder: String,
    val send: String,
    val stop: String,
    val waiting: String,
    val streaming: String,
    val streamingUnavailable: String,
    val workingGeneric: String,
    val assistantName: String,
    val youName: String,
    val emptyTitle: String,
    val emptyBody: String,
    val noProviderTitle: String,
    val noProviderBody: String,
    val offlineTitle: String,
    val offlineBody: String,
    val openProviderSettings: String,
    val providerReadError: String,
    val maskingNote: String,
    val maskedValue: String,
    val contextStale: String,
    val contextUnreadable: String,
    val contextUseCurrent: String,
    val contextRemove: String,
    val contextChecking: String,
    val draftContextTitle: String,
    val draftContextHelp: String,
    val draftContextChecking: String,
    val draftContextMissing: String,
    val draftContextDecided: String,
    val draftContextUnreadable: String,
    val draftContextBank: String,
    val draftContextAmount: String,
    val draftContextMoment: String,
    val draftContextDescription: String,
    val draftContextNotStated: String,
    val stoppedLine: String,
    val stoppedBeforeReply: String,
    val interruptedLine: String,
    val clarificationExpiredLine: String,
    val retry: String,
    val retryUnavailable: String,
    val retryUncertain: String,
    val errorGeneric: String,
    val errorTransport: String,
    val errorNoProvider: String,
    val errorRefused: String,
    val errorMasking: String,
    val clarificationHeading: String,
    val clarificationAnswerLabel: String,
    val clarificationContinue: String,
    val clarificationUnavailable: String,
    val jumpLatest: String,
    val savedConversation: String,
    val liveConversation: String,
    val messageFromYou: String,
    val messageFromAssistant: String,
    val close: String,
    val restart: String,
) {
    /** "Sends locally masked context to Gemini API · Gemma". */
    fun providerLine(name: String, model: String): String = when (language) {
        AegisLanguage.ENGLISH -> "Masked context → $name · $model"
        AegisLanguage.VIETNAMESE -> "Ngữ cảnh đã che → $name · $model"
    }

    /** A friendly label for one declared tool; the raw tool name and arguments stay private. */
    fun toolActivity(name: String): String {
        val key = name.lowercase(Locale.ROOT)
        val english = when {
            key.contains("select") || key.contains("source") -> "Reading your saved note…"
            key.contains("draft") || key.contains("search") -> "Comparing bank notifications…"
            key.contains("record") || key.contains("event") -> "Writing the interpretation…"
            key.contains("link") -> "Checking a possible match…"
            key.contains("time") || key.contains("anchor") -> "Working out the date and time…"
            else -> workingGeneric
        }
        if (language == AegisLanguage.ENGLISH) return english
        return when (english) {
            "Reading your saved note…" -> "Đang đọc ghi chú đã lưu…"
            "Comparing bank notifications…" -> "Đang đối chiếu biến động ngân hàng…"
            "Writing the interpretation…" -> "Đang ghi lại nội dung đã đọc…"
            "Checking a possible match…" -> "Đang kiểm tra một liên kết khả dĩ…"
            "Working out the date and time…" -> "Đang xác định ngày và giờ…"
            else -> workingGeneric
        }
    }

    /** One history lifecycle in user words; always a word, never only a colour. */
    fun historyStatus(status: ChatHistoryStatus): String = when (status) {
        ChatHistoryStatus.COMPLETED -> when (language) {
            AegisLanguage.ENGLISH -> "Completed"
            AegisLanguage.VIETNAMESE -> "Hoàn thành"
        }

        ChatHistoryStatus.STOPPED -> when (language) {
            AegisLanguage.ENGLISH -> "Stopped"
            AegisLanguage.VIETNAMESE -> "Đã dừng"
        }

        ChatHistoryStatus.FAILED -> when (language) {
            AegisLanguage.ENGLISH -> "Failed"
            AegisLanguage.VIETNAMESE -> "Thất bại"
        }

        ChatHistoryStatus.NEEDS_ANSWER -> when (language) {
            AegisLanguage.ENGLISH -> "Needs your answer"
            AegisLanguage.VIETNAMESE -> "Cần bạn trả lời"
        }

        ChatHistoryStatus.IN_PROGRESS -> when (language) {
            AegisLanguage.ENGLISH -> "In progress"
            AegisLanguage.VIETNAMESE -> "Đang chạy"
        }

        ChatHistoryStatus.INTERRUPTED -> when (language) {
            AegisLanguage.ENGLISH -> "Interrupted"
            AegisLanguage.VIETNAMESE -> "Bị gián đoạn"
        }
    }
}

internal fun chatStrings(language: AegisLanguage): ChatStrings = when (language) {
    AegisLanguage.ENGLISH -> englishChatStrings
    AegisLanguage.VIETNAMESE -> vietnameseChatStrings
}

private val englishChatStrings = ChatStrings(
    language = AegisLanguage.ENGLISH,
    title = "AI Chat",
    historyAction = "History",
    newChatAction = "New chat",
    historyTitle = "Conversation history",
    historyEmptyTitle = "No conversations yet",
    historyEmptyBody = "Your AI conversations appear here after you send a message.",
    historyLoading = "Loading conversations…",
    historyError = "Conversations couldn't be loaded. Nothing was changed.",
    historyRetry = "Try again",
    historyOpen = "Open conversation",
    historyBusyNote = "Stop the current reply before opening another conversation.",
    composerPlaceholder = "Ask about your notes or bank drafts…",
    send = "Send",
    stop = "Stop",
    waiting = "Waiting for the model…",
    streaming = "Receiving the reply…",
    streamingUnavailable = "This provider route reports progress without live text.",
    workingGeneric = "Working on your request…",
    assistantName = "AI assistant",
    youName = "You",
    emptyTitle = "What can I help with?",
    emptyBody = "Ask about a saved note or a waiting bank notification. The reply appears here as it arrives.",
    noProviderTitle = "No AI provider is set up",
    noProviderBody = "Set up a provider, endpoint, model and credential to use AI Chat. Notes and manual entry keep working offline.",
    offlineTitle = "You're offline",
    offlineBody = "AI Chat needs a network connection. Notes, review and manual entry keep working on this device.",
    openProviderSettings = "Open AI settings",
    providerReadError = "The AI provider settings couldn't be read. Open settings and check the provider.",
    maskingNote = "Only locally masked context is sent. Masking reduces direct personal values; it is not anonymity.",
    maskedValue = "a masked value",
    contextStale = "The attached note changed or was deleted after you attached it. Remove it or use the current text before sending.",
    contextUnreadable = "The attached note couldn't be checked. Remove it or try again before sending.",
    contextUseCurrent = "Use current text",
    contextRemove = "Remove",
    contextChecking = "Checking the attached note…",
    draftContextTitle = "Bank draft context (attached by you)",
    draftContextHelp = "Visible here until you send. The assistant can discuss it but can never " +
        "confirm it; confirmation stays in Review.",
    draftContextChecking = "Checking the attached draft…",
    draftContextMissing = "This draft is no longer available. Remove it before sending.",
    draftContextDecided = "This draft was already confirmed or dismissed. Remove it before sending.",
    draftContextUnreadable = "This draft couldn't be checked. Remove it or try again before sending.",
    draftContextBank = "Bank",
    draftContextAmount = "Amount",
    draftContextMoment = "Date and time",
    draftContextDescription = "Description",
    draftContextNotStated = "not stated",
    stoppedLine = "Stopped. The words above were received before you stopped.",
    stoppedBeforeReply = "Stopped before the reply arrived.",
    interruptedLine = "This conversation was interrupted when the app closed. Nothing was resumed automatically.",
    clarificationExpiredLine = "This question can no longer be answered because its run is no longer open. Ask again.",
    retry = "Retry",
    retryUnavailable = "This attempt can't be retried automatically. Start a new message with the text above.",
    retryUncertain = "The last attempt's stored outcome is uncertain. Check Review before sending it again.",
    errorGeneric = "The reply couldn't be completed. Your words and any received text are kept.",
    errorTransport = "The provider couldn't be reached. Check your network connection, then retry.",
    errorNoProvider = "No AI provider is set up. Your message wasn't sent.",
    errorRefused = "The assistant could not accept this request.",
    errorMasking = "Privacy protection stopped this request before it was sent. No application change was made.",
    clarificationHeading = "The assistant needs one answer",
    clarificationAnswerLabel = "Your answer",
    clarificationContinue = "Continue",
    clarificationUnavailable = "This question is no longer open. Start a new message.",
    jumpLatest = "Jump to latest",
    savedConversation = "Saved conversation",
    liveConversation = "Live",
    messageFromYou = "You",
    messageFromAssistant = "AI assistant",
    close = "Close",
    restart = "Start over",
)

private val vietnameseChatStrings = ChatStrings(
    language = AegisLanguage.VIETNAMESE,
    title = "Trợ lý AI",
    historyAction = "Lịch sử",
    newChatAction = "Hội thoại mới",
    historyTitle = "Lịch sử hội thoại",
    historyEmptyTitle = "Chưa có hội thoại",
    historyEmptyBody = "Hội thoại AI xuất hiện ở đây sau khi bạn gửi tin nhắn.",
    historyLoading = "Đang tải hội thoại…",
    historyError = "Không tải được hội thoại. Không có gì bị thay đổi.",
    historyRetry = "Thử lại",
    historyOpen = "Mở hội thoại",
    historyBusyNote = "Hãy dừng câu trả lời hiện tại trước khi mở hội thoại khác.",
    composerPlaceholder = "Hỏi về ghi chú hoặc biến động ngân hàng…",
    send = "Gửi",
    stop = "Dừng",
    waiting = "Đang chờ mô hình…",
    streaming = "Đang nhận câu trả lời…",
    streamingUnavailable = "Tuyến nhà cung cấp này chỉ báo tiến trình, không hiện văn bản trực tiếp.",
    workingGeneric = "Đang xử lý yêu cầu…",
    assistantName = "Trợ lý AI",
    youName = "Bạn",
    emptyTitle = "Tôi có thể giúp gì?",
    emptyBody = "Hỏi về một ghi chú đã lưu hoặc một biến động ngân hàng đang chờ. Câu trả lời hiện ra ngay khi được nhận.",
    noProviderTitle = "Chưa thiết lập nhà cung cấp AI",
    noProviderBody = "Thiết lập nhà cung cấp, địa chỉ, mô hình và khóa để dùng Trợ lý AI. Ghi chú và nhập tay vẫn dùng được ngoại tuyến.",
    offlineTitle = "Bạn đang ngoại tuyến",
    offlineBody = "Trợ lý AI cần kết nối mạng. Ghi chú, duyệt tin và nhập tay vẫn dùng được trên thiết bị.",
    openProviderSettings = "Mở cài đặt AI",
    providerReadError = "Không đọc được cài đặt nhà cung cấp AI. Hãy mở cài đặt và kiểm tra.",
    maskingNote = "Chỉ ngữ cảnh đã che cục bộ được gửi đi. Việc che làm giảm giá trị cá nhân trực tiếp; đây không phải ẩn danh.",
    maskedValue = "một giá trị đã che",
    contextStale = "Ghi chú đính kèm đã thay đổi hoặc bị xoá sau khi bạn đính kèm. Hãy bỏ đính kèm hoặc dùng nội dung hiện tại trước khi gửi.",
    contextUnreadable = "Không kiểm tra được ghi chú đính kèm. Hãy bỏ đính kèm hoặc thử lại trước khi gửi.",
    contextUseCurrent = "Dùng nội dung hiện tại",
    contextRemove = "Bỏ",
    contextChecking = "Đang kiểm tra ghi chú đính kèm…",
    draftContextTitle = "Ngữ cảnh biến động (bạn đính kèm)",
    draftContextHelp = "Chỉ hiện ở đây cho đến khi bạn gửi. Trợ lý có thể thảo luận nhưng không " +
        "bao giờ tự xác nhận; việc xác nhận vẫn ở Duyệt tin.",
    draftContextChecking = "Đang kiểm tra biến động đính kèm…",
    draftContextMissing = "Biến động này không còn. Hãy bỏ đính kèm trước khi gửi.",
    draftContextDecided = "Biến động đã được xác nhận hoặc bỏ qua. Hãy bỏ đính kèm trước khi gửi.",
    draftContextUnreadable = "Không kiểm tra được biến động đính kèm. Hãy bỏ đính kèm hoặc thử lại " +
        "trước khi gửi.",
    draftContextBank = "Ngân hàng",
    draftContextAmount = "Số tiền",
    draftContextMoment = "Ngày giờ",
    draftContextDescription = "Mô tả",
    draftContextNotStated = "không được nêu",
    stoppedLine = "Đã dừng. Nội dung phía trên là phần đã nhận trước khi bạn dừng.",
    stoppedBeforeReply = "Đã dừng trước khi có câu trả lời.",
    interruptedLine = "Hội thoại này bị gián đoạn khi ứng dụng đóng. Không có gì được tự động chạy tiếp.",
    clarificationExpiredLine = "Không thể trả lời câu hỏi này vì lượt chạy đã đóng. Hãy hỏi lại.",
    retry = "Thử lại",
    retryUnavailable = "Không thể tự động thử lại lượt này. Hãy gửi tin nhắn mới với nội dung phía trên.",
    retryUncertain = "Kết quả đã lưu của lượt trước chưa chắc chắn. Hãy kiểm tra Duyệt tin trước khi gửi lại.",
    errorGeneric = "Không thể hoàn tất câu trả lời. Nội dung bạn gửi và phần đã nhận được giữ nguyên.",
    errorTransport = "Không kết nối được nhà cung cấp. Hãy kiểm tra mạng rồi thử lại.",
    errorNoProvider = "Chưa thiết lập nhà cung cấp AI. Tin nhắn chưa được gửi.",
    errorRefused = "Trợ lý không thể nhận yêu cầu này.",
    errorMasking = "Bảo vệ quyền riêng tư đã chặn yêu cầu trước khi gửi. Không có thay đổi nào được ghi.",
    clarificationHeading = "Trợ lý cần một câu trả lời",
    clarificationAnswerLabel = "Câu trả lời của bạn",
    clarificationContinue = "Tiếp tục",
    clarificationUnavailable = "Câu hỏi này không còn mở. Hãy gửi tin nhắn mới.",
    jumpLatest = "Đến mới nhất",
    savedConversation = "Hội thoại đã lưu",
    liveConversation = "Trực tiếp",
    messageFromYou = "Bạn",
    messageFromAssistant = "Trợ lý AI",
    close = "Đóng",
    restart = "Bắt đầu lại",
)

private const val MAX_HISTORY_TIME_CHARS = 40

/**
 * "Today, 12:35" / "Hôm qua, 18:40": a stored run's moment in the interface language.
 *
 * The comparison is by device-local calendar day, not by elapsed hours, so a conversation just
 * after midnight still says "Today" and one from yesterday says so across a daylight-saving day.
 */
internal fun chatRelativeMoment(
    epochMillis: Long,
    language: AegisLanguage,
    nowMillis: Long = System.currentTimeMillis(),
    zone: TimeZone = TimeZone.getDefault(),
): String {
    val locale = when (language) {
        AegisLanguage.ENGLISH -> Locale.US
        AegisLanguage.VIETNAMESE -> Locale.forLanguageTag("vi-VN")
    }
    val time = SimpleDateFormat("HH:mm", locale).apply { timeZone = zone }.format(Date(epochMillis))
    val then = calendarDay(epochMillis, zone)
    val today = calendarDay(nowMillis, zone)
    val yesterday = Calendar.getInstance(zone, Locale.US).apply {
        timeInMillis = nowMillis
        add(Calendar.DATE, -1)
    }
    val yesterdayDay = calendarDay(yesterday.timeInMillis, zone)
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
    }.take(MAX_HISTORY_TIME_CHARS)
}

private fun calendarDay(epochMillis: Long, zone: TimeZone): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }
        .format(Date(epochMillis))
