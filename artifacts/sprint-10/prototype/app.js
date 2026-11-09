/* Aegis Mobile Redesign - Sprint 10 Interactive Prototype Controller (Round 2 Refined) */

// Cohesive SVG Vector Icon System
const SVG_ICONS = {
  home: '<svg class="svg-icon" viewBox="0 0 24 24"><path d="M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z"/></svg>',
  ledger: '<svg class="svg-icon" viewBox="0 0 24 24"><path d="M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm-5 14H7v-2h7v2zm3-4H7v-2h10v2zm0-4H7V7h10v2z"/></svg>',
  analytics: '<svg class="svg-icon" viewBox="0 0 24 24"><path d="M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zM9 17H7v-7h2v7zm4 0h-2V7h2v10zm4 0h-2v-4h2v4z"/></svg>',
  capture: '<svg class="svg-icon" viewBox="0 0 24 24"><path d="M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z"/></svg>',
  review: '<svg class="svg-icon" viewBox="0 0 24 24"><path d="M12 22c1.1 0 2-.9 2-2h-4c0 1.1.9 2 2 2zm6-6v-5c0-3.07-1.63-5.64-4.5-6.32V4c0-.83-.67-1.5-1.5-1.5s-1.5.67-1.5 1.5v.68C7.64 5.36 6 7.92 6 11v5l-2 2v1h16v-1l-2-2z"/></svg>',
  record: '<svg class="svg-icon" viewBox="0 0 24 24"><path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"/></svg>',
  categories: '<svg class="svg-icon" viewBox="0 0 24 24"><path d="M21.41 11.58l-9-9C12.05 2.22 11.55 2 11 2H4c-1.1 0-2 .9-2 2v7c0 .55.22 1.05.59 1.42l9 9c.36.36.86.58 1.41.58.55 0 1.05-.22 1.41-.59l7-7c.37-.36.59-.86.59-1.41 0-.55-.23-1.06-.59-1.42zM5.5 7C4.67 7 4 6.33 4 5.5S4.67 4 5.5 4 7 4.67 7 5.5 6.33 7 5.5 7z"/></svg>',
  bank: '<svg class="svg-icon" viewBox="0 0 24 24"><path d="M4 10v7h3v-7H4zm6 0v7h3v-7h-3zM2 22h19v-3H2v3zm14-12v7h3v-7h-3zm-5-9L2 6v2h19V6l-9-5z"/></svg>',
  settings: '<svg class="svg-icon" viewBox="0 0 24 24"><path d="M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"/></svg>',
  eye: '<svg class="svg-icon-sm" viewBox="0 0 24 24"><path d="M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zM12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z"/></svg>',
  check: '<svg class="svg-icon-sm" viewBox="0 0 24 24"><path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"/></svg>',
  warning: '<svg class="svg-icon-sm" viewBox="0 0 24 24"><path d="M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z"/></svg>',
  trash: '<svg class="svg-icon-sm" viewBox="0 0 24 24"><path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z"/></svg>',
  edit: '<svg class="svg-icon-sm" viewBox="0 0 24 24"><path d="M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z"/></svg>',
  link: '<svg class="svg-icon-sm" viewBox="0 0 24 24"><path d="M3.9 12c0-1.71 1.39-3.1 3.1-3.1h4V7H7c-2.76 0-5 2.24-5 5s2.24 5 5 5h4v-1.9H7c-1.71 0-3.1-1.39-3.1-3.1zM8 13h8v-2H8v2zm9-6h-4v1.9h4c1.71 0 3.1 1.39 3.1 3.1s-1.39 3.1-3.1 3.1h-4V17h4c2.76 0 5-2.24 5-5s-2.24-5-5-5z"/></svg>',
  // Category glyphs
  food: '<svg class="svg-icon" viewBox="0 0 24 24"><path d="M18.06 22.99h1.66c.84 0 1.53-.64 1.63-1.48L23 5.05h-5V1h-1.97v4.05h-4.97l.3 2.34c1.71.47 3.31 1.32 4.27 2.26 1.44 1.42 2.43 2.89 2.43 5.29v8.05zM1 21.99V21h15.03v.99c0 .55-.45 1-1 1H2c-.55 0-1-.45-1-1zm15.03-7c0-8-15.03-8-15.03 0h15.03zM1.02 17h15v2h-15z"/></svg>',
  shopping: '<svg class="svg-icon" viewBox="0 0 24 24"><path d="M18 6h-2c0-2.21-1.79-4-4-4S8 3.79 8 6H6c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zm-6-2c1.1 0 2 .9 2 2h-4c0-1.1.9-2 2-2zm6 16H6V8h2v2c0 .55.45 1 1 1s1-.45 1-1V8h4v2c0 .55.45 1 1 1s1-.45 1-1V8h2v12z"/></svg>',
  transport: '<svg class="svg-icon" viewBox="0 0 24 24"><path d="M18.92 6.01C18.72 5.42 18.16 5 17.5 5h-11c-.66 0-1.21.42-1.42 1.01L3 12v8c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-1h12v1c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-8l-2.08-5.99zM6.5 16c-.83 0-1.5-.67-1.5-1.5S5.67 13 6.5 13s1.5.67 1.5 1.5S7.33 16 6.5 16zm11 0c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5zM5 11l1.5-4.5h11L19 11H5z"/></svg>',
  housing: '<svg class="svg-icon" viewBox="0 0 24 24"><path d="M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z"/></svg>',
  entertainment: '<svg class="svg-icon" viewBox="0 0 24 24"><path d="M18 4l2 4h-3l-2-4h-2l2 4h-3l-2-4H8l2 4H7L5 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V4h-4z"/></svg>',
  health: '<svg class="svg-icon" viewBox="0 0 24 24"><path d="M20.57 14.86L22 13.43 20.57 12 17 15.57 8.43 7 12 3.43 10.57 2 9.14 3.43 7.71 2 5.57 4.14 4.14 2.71 2.71 4.14l1.43 1.43L2 7.71l1.43 1.43L2 10.57 3.43 12 7 8.43 15.57 17 12 20.57 13.43 22l1.43-1.43 1.43 1.43 2.14-2.14 1.43 1.43 1.43-1.43-1.43-1.43L22 16.29l-1.43-1.43z"/></svg>',
  pet: '<svg class="svg-icon" viewBox="0 0 24 24"><path d="M12 11c1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3 1.34 3 3 3zm-6 2c1.38 0 2.5-1.12 2.5-2.5S7.38 8 6 8s-2.5 1.12-2.5 2.5S4.62 13 6 13zm12 0c1.38 0 2.5-1.12 2.5-2.5S19.38 8 18 8s-2.5 1.12-2.5 2.5 1.12 2.5 2.5 2.5zm-6 3.5c-2.33 0-4.31 1.46-5.11 3.5h10.22c-.8-2.04-2.78-3.5-5.11-3.5z"/></svg>',
  income: '<svg class="svg-icon" viewBox="0 0 24 24"><path d="M11.8 10.9c-2.27-.59-3-1.2-3-2.15 0-1.09 1.01-1.85 2.7-1.85 1.78 0 2.44.85 2.5 2.1h2.21c-.07-1.72-1.12-3.3-3.21-3.81V3h-3v2.16c-1.94.42-3.5 1.68-3.5 3.61 0 2.31 1.91 3.46 4.7 4.13 2.5.6 3 1.48 3 2.41 0 .69-.49 1.79-2.7 1.79-2.06 0-2.87-.92-2.98-2.1h-2.2c.12 2.19 1.76 3.42 3.68 3.83V21h3v-2.15c1.95-.37 3.5-1.5 3.5-3.55 0-2.84-2.43-3.81-4.7-4.4z"/></svg>',
  chat: '<svg class="svg-icon" viewBox="0 0 24 24"><path d="M19 9l1.25-2.75L23 5l-2.75-1.25L19 1l-1.25 2.75L15 5l2.75 1.25L19 9zm-7.5.5L9 4 6.5 9.5 1 12l5.5 2.5L9 20l2.5-5.5L17 12l-5.5-2.5zM19 15l-1.25 2.75L15 19l2.75 1.25L19 23l1.25-2.75L23 19l-2.75-1.25L19 15z"/></svg>',
  send: '<svg class="svg-icon-sm" viewBox="0 0 24 24"><path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z"/></svg>',
  stop: '<svg class="svg-icon-sm" viewBox="0 0 24 24"><path d="M6 6h12v12H6z"/></svg>',
  history: '<svg class="svg-icon-sm" viewBox="0 0 24 24"><path d="M13 3c-4.97 0-9 4.03-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42C8.27 19.99 10.51 21 13 21c4.97 0 9-4.03 9-9s-4.03-9-9-9zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z"/></svg>',
  search: '<svg class="svg-icon-sm" viewBox="0 0 24 24"><path d="M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"/></svg>',
  arrowDown: '<svg class="svg-icon-sm" viewBox="0 0 24 24"><path d="M7.41 8.59L12 13.17l4.59-4.58L18 10l-6 6-6-6 1.41-1.41z"/></svg>',
};

// Bilingual Strings Dictionary (English-First Baseline with Vietnamese)
const I18N = {
  en: {
    app_title: "Aegis Mobile Redesign",
    greeting: "Good morning,",
    user_name: "Triet Pham",
    hero_title: "TOTAL SPENDING THIS MONTH",
    privacy_hide: "Hide figures",
    privacy_show: "Show figures",
    total_income: "Total Income",
    net_cashflow: "Net Cashflow",
    features_heading: "FEATURES",
    feature_ledger: "Ledger",
    feature_analytics: "Analytics",
    feature_capture: "Notes",
    feature_chat: "AI Chat",
    feature_review: "Review",
    feature_record: "New Record",
    feature_categories: "Categories",
    feature_banks: "Bank Sync",
    feature_settings: "Settings",
    review_alert_title: "bank drafts awaiting review",
    review_alert_sub: "Conflicts & missing categories require confirmation",
    recent_heading: "RECENT TRANSACTIONS",
    view_all: "View all ›",
    tab_home: "Home",
    tab_ledger: "Ledger",
    tab_capture: "Notes",
    tab_chat: "AI Chat",
    tab_review: "Review",
    tab_settings: "Settings",
    chat_title: "AI Assistant",
    synthetic_stream_badge: "SYNTHETIC STREAM",
    waiting_state: "Connecting to provider…",
    streaming_state: "Generating response…",
    tool_working_label: "SYSTEM TOOL",
    tool_working_state: "Reconciling unconfirmed bank transactions...",
    completed_state: "Completed",
    stopped_state: "Stopped by user (content preserved)",
    error_state: "Generation failed",
    retry: "Retry",
    stop: "Stop",
    send: "Send",
    chat_placeholder: "Ask AI about spending, notes, drafts…",
    attached_context: "Attached Context",
    remove_context: "Dismiss",
    chat_offline_banner: "Offline — AI Assistant requires network. Use local notes.",
    chat_no_key_banner: "API key not configured. Set up in AI Settings or choose Ollama.",
    scroll_to_bottom: "Scroll to latest ↓",
    conversation_history: "Conversation History",
    send_to_ai: "Ask AI Assistant ›",
    save_note_local: "Save Note (Local)",
    note_save_hint: "Saved to device · Offline · Instant",
    note_saved_success: "Note safely saved to device",
    glance_spend_label: "Spent this month:",
    view_reports: "Reports ›",
    home_task_prompt: "What would you like to do?",
    messages_count_label: "messages",
    quick_notes_heading: "LOCALLY SAVED NOTES",
    spending: "Spending",
    income: "Income",
    transfer: "Transfer",
    all: "All",
    search_placeholder: "Search merchant, category, notes...",
    save_record: "SAVE TO LEDGER",
    confirm_record: "CONFIRM INTO LEDGER",
    dismiss_draft: "Dismiss Draft",
    edit_record: "Edit Record",
    delete_record: "Delete Record",
    unrecorded_note_origin: "Linked with note event",
    raw_notification_header: "Original Bank Notification",
    user_category_tag: "User Category",
    hosted_category_tag: "Standard Category",
    empty_ledger_title: "No transactions found",
    empty_ledger_desc: "No entries match the selected filter.",
    offline_banner: "Offline Mode · Local database fully functional",
    error_banner: "Service connection error · Tap to retry",
    stale_banner: "Updates available · Tap to refresh",
  },
  vi: {
    app_title: "Aegis Mobile Redesign",
    greeting: "Chào buổi sáng,",
    user_name: "Triết Phạm",
    hero_title: "TỔNG CHI TIÊU THÁNG NÀY",
    privacy_hide: "Ẩn số",
    privacy_show: "Hiện số",
    total_income: "Tổng thu nhập",
    net_cashflow: "Dư tiền mặt",
    features_heading: "TÍNH NĂNG CHÍNH",
    feature_ledger: "Sổ quỹ",
    feature_analytics: "Báo cáo",
    feature_capture: "Ghi chép",
    feature_chat: "Trợ lý AI",
    feature_review: "Duyệt tin",
    feature_record: "Nhập tay",
    feature_categories: "Danh mục",
    feature_banks: "Kết nối",
    feature_settings: "Cài đặt",
    review_alert_title: "biến động ngân hàng đang chờ duyệt",
    review_alert_sub: "Có xung đột liên kết và danh mục cần xác nhận",
    recent_heading: "GIAO DỊCH GẦN ĐÂY",
    view_all: "Xem tất cả ›",
    tab_home: "Trang chủ",
    tab_ledger: "Sổ quỹ",
    tab_capture: "Ghi chép",
    tab_chat: "Trợ lý AI",
    tab_review: "Duyệt tin",
    tab_settings: "Cài đặt",
    chat_title: "Trợ lý AI",
    synthetic_stream_badge: "DÒNG PHẢN HỒI MÔ PHỎNG",
    waiting_state: "Đang kết nối mô hình…",
    streaming_state: "Đang tạo câu trả lời…",
    tool_working_label: "CÔNG CỤ HỆ THỐNG",
    tool_working_state: "Đang đối chiếu biến động ngân hàng chưa khớp...",
    completed_state: "Hoàn thành",
    stopped_state: "Đã dừng bởi người dùng (giữ nguyên nội dung)",
    error_state: "Không thể hoàn thành phản hồi",
    retry: "Thử lại",
    stop: "Dừng",
    send: "Gửi",
    chat_placeholder: "Hỏi Trợ lý AI về chi tiêu, hóa đơn, biến động...",
    attached_context: "Ngữ cảnh đính kèm",
    remove_context: "Bỏ",
    chat_offline_banner: "Ngoại tuyến — Trợ lý AI cần mạng. Hãy dùng Ghi chép cục bộ.",
    chat_no_key_banner: "Chưa cấu hình khóa API — Vào Cài đặt AI để thiết lập khóa.",
    scroll_to_bottom: "Cuộn xuống mới nhất ↓",
    conversation_history: "Lịch sử hội thoại",
    send_to_ai: "Gửi vào Trợ lý AI ›",
    save_note_local: "Lưu ghi chú (Cục bộ)",
    note_save_hint: "Lưu trên thiết bị · Không cần mạng · Tức thì",
    note_saved_success: "Đã lưu ghi chú vào thiết bị an toàn",
    glance_spend_label: "Chi tiêu tháng này:",
    view_reports: "Báo cáo ›",
    home_task_prompt: "Bạn muốn thực hiện tác vụ nào?",
    messages_count_label: "tin nhắn",
    quick_notes_heading: "GHI CHÚ ĐÃ LƯU CỤC BỘ",
    spending: "Chi tiêu",
    income: "Thu nhập",
    transfer: "Chuyển khoản",
    all: "Tất cả",
    search_placeholder: "Tìm đối tác, danh mục, ghi chú...",
    save_record: "LƯU VÀO SỔ GIAO DỊCH",
    confirm_record: "XÁC NHẬN GHI VÀO SỔ",
    dismiss_draft: "Bỏ qua",
    edit_record: "Chỉnh sửa giao dịch",
    delete_record: "Xoá giao dịch khỏi sổ",
    unrecorded_note_origin: "Đã liên kết với sự kiện ghi chú",
    raw_notification_header: "Nội dung thông báo từ ngân hàng",
    user_category_tag: "Danh mục người dùng",
    hosted_category_tag: "Danh mục hệ thống",
    empty_ledger_title: "Không tìm thấy giao dịch",
    empty_ledger_desc: "Không có bản ghi nào khớp với bộ lọc.",
    offline_banner: "Chế độ ngoại tuyến · Dữ liệu cục bộ khả dụng",
    error_banner: "Lỗi kết nối dịch vụ · Chạm để thử lại",
    stale_banner: "Dữ liệu có bản cập nhật mới · Chạm làm mới",
  },
};

// Global Application State
const appState = {
  lang: "vi", // Default to Vietnamese, togglable to English
  currentScreen: "screen-home",
  currentStateFamily: "normal", // normal, empty, loading, error, offline, ambiguous, stale, cancelled
  isMasked: false,
  currentTheme: "light",
  currentMonthIndex: 10, // 0-indexed: 10 = Nov
  navStack: [],
  selectedLedgerType: "ALL",
  selectedLedgerCategory: null,
  selectedDraftId: null,
  selectedTxnId: null,
  reviewQueueFilter: "NEEDS_DECISION", // NEEDS_DECISION, READY
  reviewQueueOrder: "UNCERTAINTY_FIRST", // UNCERTAINTY_FIRST, CHRONOLOGICAL
  selectedCategoryTab: "cats", // cats, rules
  manualRecordForm: {
    type: "EXPENSE",
    amount: 150000,
    category: "Ăn uống",
    merchant: "Highlands Coffee",
    account: "Tiền mặt (Cash)",
    note: "Ăn trưa & cafe gặp đối tác",
  },

  summary: {
    spentVnd: 14850000,
    incomeVnd: 28500000,
    netVnd: 13650000,
  },

  // Corrected category catalog: all categories are user-defined and mutable!
  categories: [
    { id: 1, name: "Ăn uống", iconKey: "food", bg: "#FFE4E6", fg: "#E11D48", spent: 6250000, percent: 42, isDefault: true },
    { id: 2, name: "Mua sắm", iconKey: "shopping", bg: "#F3E8FF", fg: "#9333EA", spent: 3560000, percent: 24, isDefault: true },
    { id: 3, name: "Nhà cửa & Hoá đơn", iconKey: "housing", bg: "#FEF3C7", fg: "#D97706", spent: 2670000, percent: 18, isDefault: true },
    { id: 4, name: "Di chuyển", iconKey: "transport", bg: "#E0F2FE", fg: "#0284C7", spent: 1485000, percent: 10, isDefault: true },
    { id: 5, name: "Giải trí", iconKey: "entertainment", bg: "#FCE7F3", fg: "#DB2777", spent: 885000, percent: 6, isDefault: true },
    { id: 6, name: "Gym & Sức khoẻ", iconKey: "health", bg: "#DCFCE7", fg: "#16A34A", spent: 0, percent: 0, isDefault: false },
    { id: 7, name: "Thú cưng", iconKey: "pet", bg: "#FFEDD5", fg: "#EA580C", spent: 0, percent: 0, isDefault: false },
  ],

  transactions: [
    { id: 101, title: "Phở Thìn Bờ Hồ", amount: -65000, type: "EXPENSE", category: "Ăn uống", iconKey: "food", iconBg: "#FFE4E6", iconFg: "#E11D48", date: "12:30 · Hôm nay (09/11)", source: "Tiền mặt", note: "Ăn trưa cùng đồng nghiệp" },
    { id: 102, title: "Uniqlo Đồng Khởi", amount: -899000, type: "EXPENSE", category: "Mua sắm", iconKey: "shopping", iconBg: "#F3E8FF", iconFg: "#9333EA", date: "15:45 · Hôm nay (09/11)", source: "Vietcombank", note: "Áo sơ mi công sở" },
    { id: 103, title: "Lương tháng 10", amount: 28500000, type: "INCOME", category: "Thu nhập", iconKey: "income", iconBg: "#D1FAE5", iconFg: "#059669", date: "09:15 · Hôm qua (08/11)", source: "Techcombank", note: "Chuyển khoản lương công ty" },
    { id: 104, title: "Chuyển tiền tiết kiệm", amount: -5000000, type: "INTERNAL_TRANSFER", category: "Chuyển khoản", iconKey: "bank", iconBg: "#EEF2FF", iconFg: "#6366F1", date: "18:00 · 07/11/2024", source: "VCB -> MBBank", note: "Gửi tiết kiệm định kỳ" },
    { id: 105, title: "Highlands Coffee", amount: -45000, type: "EXPENSE", category: "Ăn uống", iconKey: "food", iconBg: "#FFE4E6", iconFg: "#E11D48", date: "08:20 · 07/11/2024", source: "Ví MoMo", note: "Cà phê sữa đá sáng" },
    { id: 106, title: "Petrolimex Cây Xăng 04", amount: -80000, type: "EXPENSE", category: "Di chuyển", iconKey: "transport", iconBg: "#E0F2FE", iconFg: "#0284C7", date: "19:40 · 06/11/2024", source: "Tiền mặt", note: "Đổ xăng xe máy" },
  ],

  drafts: [
    {
      id: 201,
      bank: "Vietcombank",
      bankPkg: "com.VCB",
      amount: -150000,
      timestamp: "14:15:22 · 09/11/2024",
      account: "007100****6690",
      counterparty: "HIGHLANDS COFFEE CR7",
      rawText: "TK 007100... SD: -150,000VND vao 14:15 09/11/2024. ND: HIGHLANDS COFFEE CR7 POS 98213",
      status: "NEEDS_DECISION",
      conflict: "POSSIBLE_DUPLICATE",
      conflictText: "Nghi ngờ trùng lặp với giao dịch #105 (cùng số tiền trong ngày)",
      category: "Ăn uống",
      categoryOrigin: "USER", // USER rule vs HOSTED classifier
      linkedNote: "Ăn trưa & cafe gặp đối tác 150k",
      linkedNoteStatus: "ATTACHED",
    },
    {
      id: 202,
      bank: "MB Bank",
      bankPkg: "com.mbmobile",
      amount: -45000,
      timestamp: "11:30:10 · 09/11/2024",
      account: "0982****1234",
      counterparty: "CIRCLE K NGUYEN THI MINH KHAI",
      rawText: "MB: -45.000VND luc 11:30 09/11. So du: 2.450.000VND. ND: CIRCLE K GD 781263",
      status: "NEEDS_DECISION",
      conflict: "PROPOSED_LINK",
      conflictText: "Đề xuất liên kết từ ghi chú: 'Mua bánh mì Circle K 45k'",
      category: "Chưa phân loại",
      categoryOrigin: "HOSTED",
      linkedNote: "Mua bánh mì Circle K 45k",
      linkedNoteStatus: "PROPOSED",
    },
    {
      id: 203,
      bank: "Techcombank",
      bankPkg: "com.techcombank.bb.app",
      amount: 5000000,
      timestamp: "08:00:15 · 09/11/2024",
      account: "1903****8899",
      counterparty: "NGUYEN VAN B CHUYEN TIEN",
      rawText: "TCB: +5,000,000VND luc 08:00 09/11. ND: NGUYEN VAN B CHUYEN TIEN HOAN TRA",
      status: "READY",
      conflict: null,
      conflictText: null,
      category: "Thu nhập khác",
      categoryOrigin: "USER",
      linkedNote: null,
      linkedNoteStatus: null,
    },
  ],

  notes: [
    {
      id: 301,
      text: "Trưa nay ăn phở Thìn 65k, uống cafe Highland 35k",
      timestamp: "13:00 · 09/11/2024",
      parsed: true,
      events: [
        { id: 1, title: "Phở Thìn", amount: 65000, category: "Ăn uống", status: "RECORDED" },
        { id: 2, title: "Cafe Highland", amount: 35000, category: "Ăn uống", status: "PENDING_LINK" },
      ],
    },
    {
      id: 302,
      text: "Mua xăng 80k cây xăng Petrolimex",
      timestamp: "19:45 · 06/11/2024",
      parsed: true,
      events: [
        { id: 3, title: "Cây xăng Petrolimex", amount: 80000, category: "Di chuyển", status: "RECORDED" },
      ],
    },
  ],

  rules: [
    { id: 1, pattern: "HIGHLANDS*", category: "Ăn uống", active: true },
    { id: 2, pattern: "CIRCLE K*", category: "Ăn uống", active: true },
    { id: 3, pattern: "PETROLIMEX*", category: "Di chuyển", active: true },
    { id: 4, pattern: "UNIQLO*", category: "Mua sắm", active: true },
    { id: 5, pattern: "SHOPEE*", category: "Mua sắm", active: true },
  ],

  // Expanded Settings Profiles
  aiSettings: {
    selectedPreset: "openai",
    modelName: "gpt-4o-mini",
    baseUrl: "https://api.openai.com/v1",
    apiKeyMasked: "sk-proj-••••••••••••3a9f",
    reasoningEffort: "medium",
    parallelToolCalls: true,
    lastTested: "09/11/2024 14:02 (HTTP 200 OK - 240ms)",
  },

  bankSettings: {
    listenerActive: true,
    permissionGranted: true,
    allowedPackages: [
      { pkg: "com.VCB", name: "Vietcombank VCB Digibank", active: true },
      { pkg: "com.mbmobile", name: "MB Bank", active: true },
      { pkg: "com.techcombank.bb.app", name: "Techcombank Mobile", active: true },
    ],
  },

  // Dedicated AI Chat State (Sprint 10.1 Refinement)
  chatState: {
    status: "COMPLETED", // IDLE, WAITING, STREAMING, TOOL_WORKING, COMPLETED, STOPPED, ERROR
    statusMessage: "✓ Hoàn thành",
    streamText: "",
    typingTimer: null,
    attachedContext: null, // e.g. { type: "NOTE", id: 301, text: "Trưa nay ăn phở Thìn 65k..." }
    showHistory: false,
    userScrolledUp: false,
    lastPrompt: "Trưa nay ăn phở Thìn 65k, uống cafe Highland 35k. Đối chiếu với giao dịch ngân hàng giúp tôi.",
    proposedLink: {
      id: 501,
      draftId: 201,
      bank: "Vietcombank",
      amount: -150000,
      counterparty: "HIGHLANDS COFFEE CR7",
      timestamp: "14:15 · 09/11/2024",
      reason: "Khớp số tiền 150.000 ₫ và mốc thời gian 14:15 với ghi chú",
      status: "PROPOSED", // PROPOSED, ATTACHED, REJECTED
    },
    messages: [
      {
        id: 1,
        role: "user",
        text: "Trưa nay ăn phở Thìn 65k, uống cafe Highland 35k. Đối chiếu với giao dịch ngân hàng giúp tôi.",
        timestamp: "12:35",
        context: "Ghi chú #301: Trưa nay ăn phở Thìn 65k, uống cafe Highland 35k",
      },
      {
        id: 2,
        role: "assistant",
        text: "Tôi đã tiếp nhận ghi chú của bạn:\n• Khoản 1: **Phở Thìn Bờ Hồ** — 65.000 ₫ (Ăn uống, tiền mặt).\n• Khoản 2: **Highlands Coffee** — 35.000 ₫ (Ăn uống).\n\nĐang kích hoạt công cụ đối chiếu biến động ngân hàng chưa khớp...\nPhát hiện 01 giao dịch trùng khớp trên Vietcombank: -150.000 ₫ lúc 14:15 tại HIGHLANDS COFFEE. Tôi đã tạo đề xuất liên kết bên dưới.",
        timestamp: "12:35",
        status: "COMPLETED",
        hasLinkCard: true,
      },
    ],
    historySessions: [
      {
        id: "sess-1",
        runId: "run-20241109-01",
        title: "Ăn trưa phở Thìn & cafe",
        dateDisplay: "Hôm nay, 12:35",
        prompt: "Trưa nay ăn phở Thìn 65k, uống cafe Highland 35k...",
        status: "COMPLETED",
        messages: [
          {
            id: 101,
            role: "user",
            text: "Trưa nay ăn phở Thìn 65k, uống cafe Highland 35k...",
            timestamp: "12:34",
          },
          {
            id: 102,
            role: "assistant",
            text: "Tôi đã tiếp nhận ghi chú của bạn:\n• Khoản 1: **Phở Thìn Bờ Hồ** — 65.000 ₫ (Ăn uống, tiền mặt).\n• Khoản 2: **Highlands Coffee** — 35.000 ₫ (Ăn uống).\n\nĐang kích hoạt công cụ đối chiếu biến động ngân hàng chưa khớp...\nPhát hiện 01 giao dịch trùng khớp trên Vietcombank: -150.000 ₫ lúc 14:15 tại HIGHLANDS COFFEE. Tôi đã tạo đề xuất liên kết bên dưới.",
            timestamp: "12:35",
            status: "COMPLETED",
            hasLinkCard: true,
          },
        ],
      },
      {
        id: "sess-2",
        runId: "run-20241108-02",
        title: "Tìm giao dịch siêu thị WinMart",
        dateDisplay: "Hôm qua, 18:40",
        prompt: "Tìm giao dịch siêu thị WinMart cuối tuần trước",
        status: "COMPLETED",
        messages: [
          {
            id: 201,
            role: "user",
            text: "Tìm giao dịch siêu thị WinMart cuối tuần trước",
            timestamp: "18:39",
          },
          {
            id: 202,
            role: "assistant",
            text: "Tìm thấy 01 giao dịch tại WinMart Landmark:\n• Ngày: 02/11/2024 lúc 16:20\n• Số tiền: -342.000 ₫\n• Danh mục: Mua sắm (Thực phẩm & tạp hoá)\n• Phương thức: Thẻ Vietcombank POS",
            timestamp: "18:40",
            status: "COMPLETED",
            hasLinkCard: false,
          },
        ],
      },
      {
        id: "sess-3",
        runId: "run-20241108-01",
        title: "Đối chiếu chuyến Grab 42k",
        dateDisplay: "Hôm qua, 09:10",
        prompt: "Đối chiếu chuyến Grab 42k tối qua",
        status: "STOPPED",
        messages: [
          {
            id: 301,
            role: "user",
            text: "Đối chiếu chuyến Grab 42k tối qua",
            timestamp: "09:09",
          },
          {
            id: 302,
            role: "assistant",
            text: "Đang kiểm tra biến động giao thông tối qua... [Đã dừng phản hồi]",
            timestamp: "09:10",
            status: "STOPPED",
            hasLinkCard: false,
          },
        ],
      },
    ],
  },
};

// Translation Helper
function t(key) {
  return (I18N[appState.lang] && I18N[appState.lang][key]) || key;
}
function updateI18nElements() {
  document.querySelectorAll("[data-i18n]").forEach((el) => {
    const key = el.getAttribute("data-i18n");
    if (key && I18N[appState.lang] && I18N[appState.lang][key]) {
      el.textContent = I18N[appState.lang][key];
    }
  });
}

function toggleLanguage() {
  appState.lang = appState.lang === "vi" ? "en" : "vi";
  const btn = document.getElementById("lang-toggle-btn");
  if (btn) btn.innerText = `🌐 ${appState.lang.toUpperCase()}`;
  updateI18nElements();
  renderScreen();
  updateBottomNavSelection();
}

// Formatting
function formatVnd(amount) {
  if (appState.isMasked) return "*** *** ₫";
  const isNegative = amount < 0;
  const absVal = Math.abs(amount);
  const formatted = absVal.toLocaleString("vi-VN") + " ₫";
  return isNegative ? "- " + formatted : formatted;
}

function formatVndPositive(amount) {
  if (appState.isMasked) return "*** *** ₫";
  return "+ " + amount.toLocaleString("vi-VN") + " ₫";
}

// Navigation & Deep Screen Handling
function navigateTo(screenId, pushToStack = true) {
  if (pushToStack && appState.currentScreen !== screenId) {
    appState.navStack.push(appState.currentScreen);
  }
  appState.currentScreen = screenId;

  // Finding 3: Hide bottom navigation bar on deep child/detail/form screens
  const deepScreens = ["screen-detail", "screen-record", "screen-draft-detail", "screen-categories"];
  const isDeep = deepScreens.includes(screenId);

  const bottomNav = document.getElementById("bottom-nav-bar");
  const viewport = document.getElementById("app-viewport");
  if (bottomNav) bottomNav.classList.toggle("hidden", isDeep);
  if (viewport) viewport.classList.toggle("no-bottom-nav", isDeep);

  const screenSelect = document.getElementById("proto-screen-select");
  if (screenSelect) screenSelect.value = screenId;

  renderScreen();
  updateBottomNavSelection();
}

function navigateBack() {
  if (appState.navStack.length > 0) {
    const prev = appState.navStack.pop();
    navigateTo(prev, false);
  } else {
    navigateTo("screen-home", false);
  }
}

function updateBottomNavSelection() {
  const tabMap = {
    "screen-home": "tab-home",
    "screen-ledger": "tab-ledger",
    "screen-capture": "tab-capture",
    "screen-chat": "tab-chat",
    "screen-review": "tab-review",
  };
  const activeTabId = tabMap[appState.currentScreen];
  document.querySelectorAll(".nav-tab-item").forEach((tab) => {
    tab.classList.toggle("active", tab.id === activeTabId);
  });
}

// State Family Switcher
function setStateFamily(stateName) {
  appState.currentStateFamily = stateName;
  document.querySelectorAll(".proto-btn[data-state]").forEach((btn) => {
    btn.classList.toggle("active", btn.dataset.state === stateName);
  });
  renderScreen();
}

function togglePrivacyMask() {
  appState.isMasked = !appState.isMasked;
  renderScreen();
}

function toggleTheme(themeName) {
  appState.currentTheme = themeName;
  document.body.dataset.theme = themeName;
  const screen = document.getElementById("device-screen");
  if (screen) screen.dataset.theme = themeName;
}

// ==========================================
// RENDERERS FOR ALL SCREENS
// ==========================================

function renderScreen() {
  const container = document.getElementById("app-viewport");
  if (!container) return;
  container.innerHTML = "";

  let stateBannerHtml = "";
  if (appState.currentStateFamily === "offline") {
    stateBannerHtml = `<div class="state-banner offline">📡 ${t("offline_banner")}</div>`;
  } else if (appState.currentStateFamily === "error") {
    stateBannerHtml = `<div class="state-banner error">⚠️ ${t("error_banner")}</div>`;
  } else if (appState.currentStateFamily === "stale") {
    stateBannerHtml = `<div class="state-banner stale">⏳ ${t("stale_banner")}</div>`;
  }

  if (appState.currentStateFamily === "loading") {
    container.innerHTML = `
      <div style="padding: 16px; display: flex; flex-direction: column; gap: 16px;">
        <div class="skeleton" style="height: 48px; width: 100%;"></div>
        <div class="skeleton" style="height: 140px; width: 100%;"></div>
        <div class="skeleton" style="height: 180px; width: 100%;"></div>
        <div class="skeleton" style="height: 200px; width: 100%;"></div>
      </div>
    `;
    return;
  }

  switch (appState.currentScreen) {
    case "screen-home":
      renderHomeScreen(container, stateBannerHtml);
      break;
    case "screen-analytics":
      renderAnalyticsScreen(container, stateBannerHtml);
      break;
    case "screen-ledger":
      renderLedgerScreen(container, stateBannerHtml);
      break;
    case "screen-detail":
      renderTransactionDetailScreen(container, stateBannerHtml);
      break;
    case "screen-record":
      renderManualRecordScreen(container, stateBannerHtml);
      break;
    case "screen-capture":
      renderQuickCaptureScreen(container, stateBannerHtml);
      break;
    case "screen-chat":
      renderAiChatScreen(container, stateBannerHtml);
      break;
    case "screen-review":
      renderReviewInboxScreen(container, stateBannerHtml);
      break;
    case "screen-draft-detail":
      renderDraftDetailScreen(container, stateBannerHtml);
      break;
    case "screen-categories":
      renderCategoryRulesScreen(container, stateBannerHtml);
      break;
    case "screen-settings":
      renderSettingsScreen(container, stateBannerHtml);
      break;
    default:
      renderHomeScreen(container, stateBannerHtml);
  }
}

// Screen 1: Home Dashboard (Lightweight Single-Purpose Task Launcher)
function renderHomeScreen(container, stateBannerHtml) {
  const pendingCount = appState.drafts.filter((d) => d.status !== "CONFIRMED" && d.status !== "DISMISSED").length;

  container.innerHTML = `
    <div class="screen-view active">
      ${stateBannerHtml}
      <!-- Top App Bar: User Greeting & Alert Bell (No redundant settings gear) -->
      <div class="top-app-bar" style="margin-bottom: 16px;">
        <div class="top-bar-left">
          <div class="user-avatar-btn" onclick="navigateTo('screen-settings')" title="${t("feature_settings")}">T</div>
          <div class="greeting-block">
            <span class="greeting-small">${t("greeting")}</span>
            <span class="greeting-name">${t("user_name")}</span>
          </div>
        </div>
        <div class="top-bar-actions">
          <button class="icon-btn" onclick="navigateTo('screen-review')" title="${t("feature_review")}">
            ${SVG_ICONS.review}
            ${pendingCount > 0 ? `<span class="badge-counter">${pendingCount}</span>` : ""}
          </button>
        </div>
      </div>

      <!-- Lightweight Financial Glance Bar (Single Row, Not a Stacked Card) -->
      <div class="home-glance-bar" onclick="navigateTo('screen-analytics')">
        <div class="glance-left">
          <div class="glance-icon-badge">${SVG_ICONS.analytics}</div>
          <div class="glance-text-group">
            <span class="glance-period">${t("glance_spend_label")}</span>
            <span class="glance-amount">${formatVnd(appState.summary.spentVnd)}</span>
          </div>
        </div>
        <div class="glance-right">
          <button class="glance-eye-btn" onclick="event.stopPropagation(); togglePrivacyMask();" title="${appState.isMasked ? t("privacy_show") : t("privacy_hide")}">
            ${SVG_ICONS.eye}
          </button>
          <span class="glance-link-text">${t("view_reports")}</span>
        </div>
      </div>

      <!-- Primary Task Launcher (8 Feature Launchers Directly on Screen Surface) -->
      <div class="home-launcher-section">
        <div class="home-launcher-prompt">${t("home_task_prompt")}</div>
        <div class="home-launcher-grid">
          <div class="home-launcher-item" onclick="navigateTo('screen-ledger')">
            <div class="home-launcher-squircle" style="background: #E0F2FE; color: #0284C7;">${SVG_ICONS.ledger}</div>
            <span class="home-launcher-label">${t("feature_ledger")}</span>
          </div>
          <div class="home-launcher-item" onclick="navigateTo('screen-analytics')">
            <div class="home-launcher-squircle" style="background: #F3E8FF; color: #7C3AED;">${SVG_ICONS.analytics}</div>
            <span class="home-launcher-label">${t("feature_analytics")}</span>
          </div>
          <div class="home-launcher-item" onclick="navigateTo('screen-capture')">
            <div class="home-launcher-squircle" style="background: #DCFCE7; color: #059669;">${SVG_ICONS.capture}</div>
            <span class="home-launcher-label">${t("feature_capture")}</span>
          </div>
          <div class="home-launcher-item" onclick="navigateTo('screen-chat')">
            <div class="home-launcher-squircle featured-ai">${SVG_ICONS.chat}</div>
            <span class="home-launcher-label" style="font-weight: 700; color: var(--brand-primary);">${t("feature_chat")}</span>
          </div>
          <div class="home-launcher-item" onclick="navigateTo('screen-review')">
            <div class="home-launcher-squircle" style="background: #FEF3C7; color: #D97706;">
              ${SVG_ICONS.review}
              ${pendingCount > 0 ? `<span class="badge-counter" style="top:-3px; right:-3px;">${pendingCount}</span>` : ""}
            </div>
            <span class="home-launcher-label">${t("feature_review")}</span>
          </div>
          <div class="home-launcher-item" onclick="navigateTo('screen-record')">
            <div class="home-launcher-squircle" style="background: #FFE4E6; color: #E11D48;">${SVG_ICONS.record}</div>
            <span class="home-launcher-label">${t("feature_record")}</span>
          </div>
          <div class="home-launcher-item" onclick="navigateTo('screen-categories')">
            <div class="home-launcher-squircle" style="background: #FFEDD5; color: #EA580C;">${SVG_ICONS.categories}</div>
            <span class="home-launcher-label">${t("feature_categories")}</span>
          </div>
          <div class="home-launcher-item" onclick="navigateTo('screen-settings')">
            <div class="home-launcher-squircle" style="background: #F1F5F9; color: #334155;">${SVG_ICONS.settings}</div>
            <span class="home-launcher-label">${t("feature_settings")}</span>
          </div>
        </div>
      </div>
    </div>
  `;
}

// Screen 2: Analytics & Trends
function renderAnalyticsScreen(container, stateBannerHtml) {
  if (appState.currentStateFamily === "empty") {
    container.innerHTML = `
      <div class="screen-view active">
        <div class="screen-header-bar">
          <button class="back-btn" onclick="navigateBack()">←</button>
          <span class="screen-header-title">${t("feature_analytics")}</span>
          <span style="width: 36px;"></span>
        </div>
        <div class="state-empty-container">
          <div class="empty-icon">${SVG_ICONS.analytics}</div>
          <div class="empty-title">Chưa có chi tiêu tháng này</div>
          <div class="empty-desc">Khi bạn xác nhận các giao dịch chi tiêu hoặc biến động ngân hàng, phân tích tài chính sẽ xuất hiện tại đây.</div>
          <button class="btn-primary" style="max-width: 200px;" onclick="navigateTo('screen-record')">+ Thêm giao dịch</button>
        </div>
      </div>
    `;
    return;
  }

  const monthNames = ["Tháng 1", "Tháng 2", "Tháng 3", "Tháng 4", "Tháng 5", "Tháng 6", "Tháng 7", "Tháng 8", "Tháng 9", "Tháng 10", "Tháng 11/2024", "Tháng 12"];
  const currentMonthStr = monthNames[appState.currentMonthIndex] || "Tháng 11/2024";

  container.innerHTML = `
    <div class="screen-view active">
      ${stateBannerHtml}
      <div class="screen-header-bar">
        <button class="back-btn" onclick="navigateBack()">←</button>
        <span class="screen-header-title">${t("feature_analytics")}</span>
        <button class="icon-btn" style="border:none; background:transparent;" onclick="navigateTo('screen-ledger')">${SVG_ICONS.ledger}</button>
      </div>

      <!-- Month Switcher with Working Handler -->
      <div class="month-switcher">
        <button class="arrow-btn" onclick="changeMonth(-1)">‹</button>
        <span>${currentMonthStr}</span>
        <button class="arrow-btn" onclick="changeMonth(1)">›</button>
      </div>

      <!-- KPI Comparison Cards -->
      <div style="display: flex; gap: 12px;">
        <div class="app-card" style="flex: 1; border-top: 4px solid var(--semantic-expense);">
          <span style="font-size: 11px; font-weight: 600; color: var(--fg-secondary);">${t("spending").toUpperCase()}</span>
          <span class="txn-amount" style="font-size: 18px; font-weight: 800; color: var(--semantic-expense);">${formatVnd(appState.summary.spentVnd)}</span>
          <span style="font-size: 11px; color: var(--semantic-expense);">↑ 12% vs trước</span>
        </div>
        <div class="app-card" style="flex: 1; border-top: 4px solid var(--semantic-income);">
          <span style="font-size: 11px; font-weight: 600; color: var(--fg-secondary);">${t("income").toUpperCase()}</span>
          <span class="txn-amount" style="font-size: 18px; font-weight: 800; color: var(--semantic-income);">${formatVndPositive(appState.summary.incomeVnd)}</span>
          <span style="font-size: 11px; color: var(--semantic-income);">↓ 5% vs trước</span>
        </div>
      </div>

      <!-- Net Cashflow Pill -->
      <div style="background: var(--bg-surface); border: 1px solid var(--border-subtle); border-radius: var(--radius-md); padding: 10px 16px; display: flex; justify-content: space-between; align-items: center;">
        <span style="font-size: 12px; font-weight: 600;">${t("net_cashflow").toUpperCase()}</span>
        <span class="txn-amount" style="font-size: 14px; font-weight: 800; color: var(--semantic-income);">${formatVndPositive(appState.summary.netVnd)}</span>
      </div>

      <!-- Proportional Category Bar Breakdown -->
      <div class="app-card">
        <div class="section-header">
          <span class="section-title">PHÂN BỔ THEO DANH MỤC</span>
          <span style="font-size: 12px; color: var(--fg-muted);">5 danh mục</span>
        </div>
        <div style="display: flex; height: 14px; border-radius: var(--radius-full); overflow: hidden; gap: 2px;">
          <div style="width: 42%; background: #E11D48;" title="Ăn uống 42%"></div>
          <div style="width: 24%; background: #9333EA;" title="Mua sắm 24%"></div>
          <div style="width: 18%; background: #D97706;" title="Nhà cửa 18%"></div>
          <div style="width: 10%; background: #0284C7;" title="Di chuyển 10%"></div>
          <div style="width: 6%; background: #DB2777;" title="Khác 6%"></div>
        </div>

        <!-- Category Drill-Down List with working filterLedgerByCategory -->
        <div style="display: flex; flex-direction: column; gap: 8px; margin-top: 6px;">
          ${appState.categories
            .filter((c) => c.spent > 0)
            .map(
              (c) => `
            <div class="txn-row" style="padding: 8px 0;" onclick="filterLedgerByCategory('${c.name}')">
              <div class="txn-left">
                <div class="txn-category-icon" style="background: ${c.bg}; color: ${c.fg};">${SVG_ICONS[c.iconKey]}</div>
                <div class="txn-info">
                  <span class="txn-title">${c.name}</span>
                  <span class="txn-meta">${c.percent}% tổng chi tiêu</span>
                </div>
              </div>
              <div class="txn-right">
                <span class="txn-amount" style="color: var(--fg-primary);">${formatVnd(c.spent)}</span>
                <span style="font-size: 11px; color: var(--brand-primary); font-weight: 600;">Lọc sổ ›</span>
              </div>
            </div>
          `
            )
            .join("")}
        </div>
      </div>

      <!-- Daily Spending Velocity Chart -->
      <div class="app-card">
        <div class="section-header">
          <span class="section-title">CHI TIÊU THEO NGÀY</span>
          <span style="font-size: 12px; color: var(--fg-muted);">Tuần này</span>
        </div>
        <div class="chart-bar-container">
          <div class="chart-bar-col"><div class="chart-bar" style="height: 35%;"></div><span class="chart-bar-label">T2</span></div>
          <div class="chart-bar-col"><div class="chart-bar" style="height: 80%;"></div><span class="chart-bar-label">T3</span></div>
          <div class="chart-bar-col"><div class="chart-bar" style="height: 50%;"></div><span class="chart-bar-label">T4</span></div>
          <div class="chart-bar-col"><div class="chart-bar" style="height: 95%;"></div><span class="chart-bar-label">T5</span></div>
          <div class="chart-bar-col"><div class="chart-bar" style="height: 40%;"></div><span class="chart-bar-label">T6</span></div>
          <div class="chart-bar-col"><div class="chart-bar" style="height: 70%; background: var(--semantic-expense);"></div><span class="chart-bar-label">T7</span></div>
          <div class="chart-bar-col"><div class="chart-bar" style="height: 20%;"></div><span class="chart-bar-label">CN</span></div>
        </div>
      </div>
    </div>
  `;
}

// Screen 3: Ledger / Transaction List
function renderLedgerScreen(container, stateBannerHtml) {
  let filteredList = appState.transactions;
  if (appState.selectedLedgerType !== "ALL") {
    filteredList = filteredList.filter((tItem) => tItem.type === appState.selectedLedgerType);
  }
  if (appState.selectedLedgerCategory) {
    filteredList = filteredList.filter((tItem) => tItem.category === appState.selectedLedgerCategory);
  }

  container.innerHTML = `
    <div class="screen-view active">
      ${stateBannerHtml}
      <div class="screen-header-bar">
        <span class="screen-header-title">${t("tab_ledger")}</span>
        <button class="icon-btn" onclick="navigateTo('screen-record')">${SVG_ICONS.record}</button>
      </div>

      <!-- Search Input -->
      <input class="form-input" type="text" placeholder="${t("search_placeholder")}" oninput="searchLedger(this.value)">

      <!-- Filter Chips -->
      <div style="display: flex; gap: 8px; overflow-x: auto; padding-bottom: 4px;">
        <div class="seg-tab ${appState.selectedLedgerType === "ALL" && !appState.selectedLedgerCategory ? "active" : ""}" onclick="resetLedgerFilters()">${t("all")}</div>
        <div class="seg-tab ${appState.selectedLedgerType === "EXPENSE" ? "active" : ""}" onclick="setLedgerTypeFilter('EXPENSE')">${t("spending")}</div>
        <div class="seg-tab ${appState.selectedLedgerType === "INCOME" ? "active" : ""}" onclick="setLedgerTypeFilter('INCOME')">${t("income")}</div>
        <div class="seg-tab ${appState.selectedLedgerType === "INTERNAL_TRANSFER" ? "active" : ""}" onclick="setLedgerTypeFilter('INTERNAL_TRANSFER')">${t("transfer")}</div>
      </div>

      ${
        appState.selectedLedgerCategory
          ? `
        <div style="display: flex; align-items: center; justify-content: space-between; background: var(--brand-primary-container); color: var(--brand-primary); padding: 6px 12px; border-radius: var(--radius-sm); font-size: 12px; font-weight: 600;">
          <span>Đang lọc danh mục: <strong>${appState.selectedLedgerCategory}</strong></span>
          <button style="border: none; background: transparent; color: inherit; cursor: pointer; font-weight: 700;" onclick="clearCategoryFilter()">✕ Xoá</button>
        </div>
      `
          : ""
      }

      <!-- Transaction List -->
      ${
        filteredList.length === 0
          ? `
        <div class="state-empty-container">
          <div class="empty-icon">${SVG_ICONS.ledger}</div>
          <div class="empty-title">${t("empty_ledger_title")}</div>
          <div class="empty-desc">${t("empty_ledger_desc")}</div>
          <button class="btn-secondary" style="max-width: 160px;" onclick="resetLedgerFilters()">Xem tất cả</button>
        </div>
      `
          : `
        <div class="recent-feed-card" style="padding: 4px 12px;">
          ${filteredList
            .map(
              (tItem) => `
            <div class="txn-row" onclick="openTransactionDetail(${tItem.id})">
              <div class="txn-left">
                <div class="txn-category-icon" style="background: ${tItem.iconBg}; color: ${tItem.iconFg};">
                  ${SVG_ICONS[tItem.iconKey] || SVG_ICONS.categories}
                </div>
                <div class="txn-info">
                  <span class="txn-title">${tItem.title}</span>
                  <span class="txn-meta">${tItem.category} · ${tItem.date}</span>
                </div>
              </div>
              <div class="txn-right">
                <span class="txn-amount ${tItem.type.toLowerCase()}">${tItem.amount < 0 ? formatVnd(tItem.amount) : formatVndPositive(tItem.amount)}</span>
                <span style="font-size: 11px; color: var(--fg-muted);">${tItem.source}</span>
              </div>
            </div>
          `
            )
            .join("")}
        </div>
      `
      }
    </div>
  `;
}

// Screen 4: Transaction Detail Screen (with Sticky Bottom CTA)
function renderTransactionDetailScreen(container, stateBannerHtml) {
  const txn = appState.transactions.find((tItem) => tItem.id === appState.selectedTxnId) || appState.transactions[0];

  container.innerHTML = `
    <div class="screen-view active">
      ${stateBannerHtml}
      <div class="screen-header-bar">
        <button class="back-btn" onclick="navigateBack()">←</button>
        <span class="screen-header-title">Chi tiết giao dịch</span>
        <span style="width: 36px;"></span>
      </div>

      <!-- Digital Receipt Card -->
      <div class="receipt-card">
        <div style="width: 56px; height: 56px; border-radius: var(--radius-full); background: ${txn.iconBg}; color: ${txn.iconFg}; display: flex; align-items: center; justify-content: center;">
          ${SVG_ICONS[txn.iconKey] || SVG_ICONS.categories}
        </div>
        <div style="font-size: 18px; font-weight: 700;">${txn.title}</div>
        <div class="receipt-amount ${txn.type === "INCOME" ? "income" : ""}">${txn.amount < 0 ? formatVnd(txn.amount) : formatVndPositive(txn.amount)}</div>
        <div style="display: inline-flex; align-items: center; gap: 6px; padding: 4px 12px; background: var(--bg-surface-subtle); border-radius: var(--radius-full); font-size: 12px; font-weight: 600; color: var(--semantic-income);">
          ${SVG_ICONS.check}
          <span>Đã xác nhận vào sổ</span>
        </div>
      </div>

      <!-- Metadata Breakdown (No developer jargon) -->
      <div class="app-card">
        <div style="display: flex; justify-content: space-between; font-size: 13px;">
          <span style="color: var(--fg-secondary);">Danh mục</span>
          <span style="font-weight: 600;">${txn.category}</span>
        </div>
        <div style="display: flex; justify-content: space-between; font-size: 13px;">
          <span style="color: var(--fg-secondary);">Thời gian</span>
          <span style="font-weight: 600;">${txn.date}</span>
        </div>
        <div style="display: flex; justify-content: space-between; font-size: 13px;">
          <span style="color: var(--fg-secondary);">Nguồn tiền</span>
          <span style="font-weight: 600;">${txn.source}</span>
        </div>
        <div style="display: flex; justify-content: space-between; font-size: 13px;">
          <span style="color: var(--fg-secondary);">Ghi chú</span>
          <span style="font-weight: 600;">${txn.note}</span>
        </div>
        <div style="display: flex; justify-content: space-between; font-size: 13px;">
          <span style="color: var(--fg-secondary);">Mã giao dịch</span>
          <span style="font-family: monospace; font-size: 11px;">TXN-${txn.id}-20241109</span>
        </div>
      </div>

      <!-- Sticky Bottom Action Bar (Finding 3) -->
      <div class="sticky-bottom-bar">
        <button class="btn-primary" style="flex: 2;" onclick="navigateTo('screen-record')">${SVG_ICONS.edit} ${t("edit_record")}</button>
        <button class="btn-danger" style="flex: 1;" onclick="deleteTransaction(${txn.id})">${SVG_ICONS.trash}</button>
      </div>
    </div>
  `;
}

// Screen 5: Manual Record Screen (with Working Type/Swatch Selection & Sticky CTA)
function renderManualRecordScreen(container, stateBannerHtml) {
  const form = appState.manualRecordForm;

  container.innerHTML = `
    <div class="screen-view active">
      ${stateBannerHtml}
      <div class="screen-header-bar">
        <button class="back-btn" onclick="navigateBack()">✕</button>
        <span class="screen-header-title">Thêm giao dịch mới</span>
        <span style="width: 36px;"></span>
      </div>

      <!-- Segmented Type Selector with working selectFormType -->
      <div class="segmented-tabs">
        <div class="seg-tab ${form.type === "EXPENSE" ? "active" : ""}" onclick="selectFormType('EXPENSE')">${t("spending")}</div>
        <div class="seg-tab ${form.type === "INCOME" ? "active" : ""}" onclick="selectFormType('INCOME')">${t("income")}</div>
        <div class="seg-tab ${form.type === "INTERNAL_TRANSFER" ? "active" : ""}" onclick="selectFormType('INTERNAL_TRANSFER')">${t("transfer")}</div>
      </div>

      <!-- Hero Amount Input -->
      <div class="app-card" style="text-align: center; padding: 20px;">
        <span class="form-label">SỐ TIỀN GIAO DỊCH (VND)</span>
        <input id="form-amount-input" class="form-input" type="number" style="font-size: 32px; font-weight: 800; text-align: center; margin: 8px 0; border: none; background: transparent;" value="${form.amount}" oninput="updateFormAmount(this.value)">
        <span id="form-amount-words" style="font-size: 12px; color: var(--brand-primary); font-weight: 600;">Một trăm năm mươi nghìn đồng</span>
      </div>

      <!-- Category Picker with working selectCategorySwatch -->
      <div class="app-card">
        <span class="form-label">CHỌN DANH MỤC</span>
        <div style="display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin-top: 6px;">
          ${appState.categories
            .map(
              (c) => `
            <div style="display: flex; flex-direction: column; align-items: center; gap: 4px; cursor: pointer; padding: 8px; border-radius: var(--radius-md); border: 2px solid ${form.category === c.name ? "var(--brand-primary)" : "transparent"}; background: ${form.category === c.name ? "var(--brand-primary-container)" : "var(--bg-surface-subtle)"};" onclick="selectCategorySwatch(this, '${c.name}')">
              <div style="color: ${c.fg};">${SVG_ICONS[c.iconKey]}</div>
              <span style="font-size: 11px; font-weight: 600; text-align: center;">${c.name}</span>
            </div>
          `
            )
            .join("")}
        </div>
      </div>

      <!-- Additional Details -->
      <div class="app-card">
        <div class="form-group">
          <label class="form-label">Đối tác / Merchant</label>
          <input id="form-merchant-input" class="form-input" type="text" value="${form.merchant}">
        </div>
        <div class="form-group">
          <label class="form-label">Ví / Tài khoản</label>
          <select id="form-account-select" class="form-input">
            <option ${form.account.includes("Tiền mặt") ? "selected" : ""}>Tiền mặt (Cash)</option>
            <option ${form.account.includes("Vietcombank") ? "selected" : ""}>Vietcombank (***6690)</option>
            <option ${form.account.includes("Techcombank") ? "selected" : ""}>Techcombank (***8899)</option>
            <option ${form.account.includes("MoMo") ? "selected" : ""}>Ví MoMo</option>
          </select>
        </div>
        <div class="form-group">
          <label class="form-label">Ghi chú</label>
          <input id="form-note-input" class="form-input" type="text" value="${form.note}">
        </div>
      </div>

      <!-- Sticky Bottom Action Bar (Always in view!) -->
      <div class="sticky-bottom-bar">
        <button class="btn-primary" onclick="saveManualRecord()">${t("save_record")}</button>
      </div>
    </div>
  `;
}

// Helper functions for Chat Markdown and escaping
function escapeHtml(str) {
  if (!str) return "";
  return str.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

function renderChatMarkdown(str) {
  if (!str) return "";
  let out = escapeHtml(str);
  out = out.replace(/\*\*(.*?)\*\*/g, "<strong>$1</strong>");
  return out;
}

function renderChatLinkCardHtml(link) {
  return `
    <div class="chat-link-card">
      <div class="chat-link-card-header">${SVG_ICONS.link} ĐỀ XUẤT LIÊN KẾT BIẾN ĐỘNG</div>
      <div class="chat-link-body">
        <strong>${link.bank}</strong> · <span class="txn-amount expense">${formatVnd(link.amount)}</span> · ${link.counterparty}
        <div style="font-size: 11px; color: var(--fg-muted);">${link.timestamp}</div>
      </div>
      <div class="chat-link-reason">Lý do: ${link.reason}</div>
      ${
        link.status === "PROPOSED"
          ? `
        <div class="chat-link-actions">
          <button class="chat-btn-attach" onclick="attachLinkFromChat()">${SVG_ICONS.check} Gắn kết (Attach)</button>
          <button class="chat-btn-reject" onclick="rejectLinkFromChat()">Từ chối</button>
        </div>
      `
          : link.status === "ATTACHED"
          ? `
        <div style="font-size: 12px; font-weight: 700; color: var(--semantic-income); display: flex; align-items: center; gap: 4px;">
          ${SVG_ICONS.check} Đã gắn kết thành công
          <button class="proto-btn" style="padding: 2px 6px; font-size: 10px; margin-left: 8px;" onclick="unlinkFromChat()">Huỷ gắn (Unlink)</button>
        </div>
      `
          : `
        <div style="font-size: 12px; color: var(--fg-muted);">✕ Đã từ chối liên kết này</div>
      `
      }
      <div class="chat-disclaimer">ℹ️ Xác nhận tài chính vào sổ quỹ chỉ diễn ra khi nhấn Xác nhận tại màn hình Duyệt biến động.</div>
    </div>
  `;
}

// Screen 6: Quick Note Journal (Focused, Single-Task Note Capture)
function renderQuickCaptureScreen(container, stateBannerHtml) {
  container.innerHTML = `
    <div class="screen-view active">
      ${stateBannerHtml}
      <div class="screen-header-bar">
        <button class="back-btn" onclick="navigateBack()">←</button>
        <span class="screen-header-title">${t("tab_capture")}</span>
        <button class="icon-btn" onclick="clearNotes()" title="Xoá tất cả ghi chú">${SVG_ICONS.trash}</button>
      </div>

      <!-- Focused Note Composer -->
      <div class="app-card" style="border: 2px solid var(--brand-primary-container);">
        <span class="form-label">GHI CHÉP NHANH (LƯU CỤC BỘ)</span>
        <textarea id="capture-note-input" class="form-input" rows="3" placeholder="Nhập ghi chú chi tiêu: 'Trưa nay ăn phở Thìn 65k, uống cafe Highland 35k'..." style="resize: none; border: none; background: transparent; padding: 4px; font-size: 15px;">Trưa nay ăn phở Thìn 65k, uống cafe Highland 35k</textarea>
        
        <div style="display: flex; justify-content: space-between; align-items: center; padding-top: 10px; border-top: 1px solid var(--border-subtle); margin-top: 4px;">
          <span style="font-size: 11px; color: var(--fg-muted);">${t("note_save_hint")}</span>
          <button class="btn-primary" style="padding: 10px 18px; min-height: 40px; font-size: 13px; width: auto;" onclick="saveLocalNoteOnly()">${SVG_ICONS.check} ${t("save_note_local")}</button>
        </div>
      </div>

      <!-- Explicit Handoff to AI Assistant Screen -->
      <div style="background: linear-gradient(135deg, #f5f3ff 0%, #eff6ff 100%); border: 1px solid #ddd6fe; border-radius: var(--radius-md); padding: 12px 14px; display: flex; justify-content: space-between; align-items: center; cursor: pointer;" onclick="sendCurrentNoteToAi()">
        <div style="display: flex; align-items: center; gap: 10px;">
          <div style="color: #7c3aed;">${SVG_ICONS.chat}</div>
          <div>
            <div style="font-size: 13px; font-weight: 700; color: #6d28d9;">Thảo luận với Trợ lý AI</div>
            <div style="font-size: 11px; color: #7c3aed;">Chuyển nội dung sang màn hình AI Chat để đối chiếu với biến động ngân hàng</div>
          </div>
        </div>
        <span style="font-size: 16px; font-weight: 700; color: #7c3aed;">›</span>
      </div>

      <!-- Locally Saved Notes List -->
      <div class="recent-feed-card">
        <div class="section-header" style="padding: 8px 4px 10px 4px;">
          <span class="section-title">${t("quick_notes_heading")}</span>
          <span style="font-size: 11px; color: var(--fg-muted);">${appState.notes.length} ghi chú</span>
        </div>
        ${
          appState.notes.length === 0
            ? '<div style="padding: 16px; text-align: center; color: var(--fg-muted); font-size: 13px;">Chưa có ghi chú nào được lưu.</div>'
            : appState.notes
                .map(
                  (n) => `
          <div class="txn-row" style="flex-direction: column; align-items: flex-start; gap: 6px; padding: 12px 4px;">
            <div style="font-size: 14px; font-weight: 500; color: var(--fg-primary); line-height: 1.4;">"${n.text}"</div>
            <div style="display: flex; justify-content: space-between; align-items: center; width: 100%; font-size: 11px;">
              <span style="color: var(--fg-muted);">${n.timestamp}</span>
              <div style="display: flex; gap: 8px;">
                <button class="proto-btn" style="padding: 3px 8px; font-size: 10px;" onclick="sendNoteToAi(${n.id})">Gửi vào AI ›</button>
                <button class="proto-btn" style="padding: 3px 8px; font-size: 10px; color: var(--semantic-expense);" onclick="deleteNote(${n.id})">Xoá</button>
              </div>
            </div>
          </div>
        `
                )
                .join("")
        }
      </div>

      <!-- Truthful Prototype Notice (Web Fixture vs Android Room) -->
      <div style="margin-top: 16px; padding: 10px 12px; background: rgba(148, 163, 184, 0.08); border-radius: var(--radius-sm); border: 1px dashed var(--border-subtle); font-size: 11px; color: var(--fg-muted); line-height: 1.4;">
        ℹ️ <strong>Mô phỏng Web Prototype:</strong> Ghi chú lưu vào bộ nhớ tạm in-memory mô phỏng bảng <code>CaptureMessageEntity</code> của Android Room. Bản ghi trên ứng dụng thực tế được lưu cục bộ an toàn, không tốn token và không phụ thuộc mạng.
      </div>
    </div>
  `;
}

// Screen 7: Dedicated Streaming AI Chat Screen (Sprint 10.1 Owner Refinement)
function renderAiChatScreen(container, stateBannerHtml) {
  const cs = appState.chatState;
  const isStreaming = cs.status === "STREAMING" || cs.status === "WAITING" || cs.status === "TOOL_WORKING";
  const isOffline = appState.currentStateFamily === "offline";

  container.innerHTML = `
    <div class="chat-screen-container">
      ${stateBannerHtml}
      
      <!-- Top Screen Header Bar -->
      <div class="screen-header-bar" style="padding: 8px 12px; background: var(--bg-surface); z-index: 20;">
        <div style="display: flex; align-items: center; gap: 8px;">
          <button class="back-btn" onclick="navigateBack()">←</button>
          <div>
            <div style="font-size: 15px; font-weight: 700; color: var(--fg-primary); display: flex; align-items: center; gap: 6px;">
              ${t("chat_title")}
              <span class="synthetic-pill">🔶 SYNTHETIC STREAM</span>
            </div>
            <div style="font-size: 11px; color: var(--fg-secondary);">Mô hình: ${appState.aiSettings.modelName} · Trợ lý đối chiếu Aegis</div>
          </div>
        </div>
        <div style="display: flex; align-items: center; gap: 6px;">
          <button class="icon-btn" onclick="openChatHistory()" title="${t("conversation_history")}">
            ${SVG_ICONS.history}
          </button>
          <button class="icon-btn" onclick="clearActiveChat()" title="Làm mới đoạn chat">
            ${SVG_ICONS.trash}
          </button>
        </div>
      </div>

      <!-- Attached Explicit Context Chip (if present) -->
      ${
        cs.attachedContext
          ? `
        <div style="padding: 6px 12px; background: var(--bg-surface-subtle); border-bottom: 1px solid var(--border-subtle);">
          <div class="chat-context-chip">
            <span style="overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 90%;">
              📝 <strong>${t("attached_context")}:</strong> "${cs.attachedContext.text}"
            </span>
            <button class="chat-context-close" onclick="removeChatContext()" title="${t("remove_context")}">✕</button>
          </div>
        </div>
      `
          : ""
      }

      <!-- Scrollable Message Transcript -->
      <div id="chat-transcript-area" class="chat-transcript-area" onscroll="onChatScroll(this)">
        ${cs.messages
          .map((msg) => {
            if (msg.role === "user") {
              return `
                <div class="chat-message-row user">
                  <div class="chat-avatar user-avatar">U</div>
                  <div class="chat-bubble">
                    ${msg.context ? `<div style="font-size: 11px; opacity: 0.85; margin-bottom: 4px; padding-bottom: 4px; border-bottom: 1px solid rgba(255,255,255,0.2);">📎 ${msg.context}</div>` : ""}
                    <div>${escapeHtml(msg.text)}</div>
                    <div style="font-size: 10px; opacity: 0.75; text-align: right; margin-top: 4px;">${msg.timestamp}</div>
                  </div>
                </div>
              `;
            } else {
              return `
                <div class="chat-message-row assistant">
                  <div class="chat-avatar ai-avatar">${SVG_ICONS.chat}</div>
                  <div class="chat-bubble">
                    <div style="white-space: pre-wrap;">${renderChatMarkdown(msg.text)}</div>
                    ${
                      msg.status === "STOPPED"
                        ? `<div class="chat-status-pill stopped">⏹ ${t("stopped_state")}</div>`
                        : msg.status === "COMPLETED"
                        ? `<div class="chat-status-pill completed">✓ ${t("completed_state")}</div>`
                        : ""
                    }
                    ${
                      msg.hasLinkCard && cs.proposedLink
                        ? renderChatLinkCardHtml(cs.proposedLink)
                        : ""
                    }
                    <div style="font-size: 10px; color: var(--fg-muted); margin-top: 6px;">${msg.timestamp}</div>
                  </div>
                </div>
              `;
            }
          })
          .join("")}

        <!-- Active Streaming Bubble (while waiting/streaming/tool-working) -->
        ${
          isStreaming
            ? `
          <div class="chat-message-row assistant">
            <div class="chat-avatar ai-avatar">${SVG_ICONS.chat}</div>
            <div class="chat-bubble">
              <div style="white-space: pre-wrap;">
                <span id="chat-active-stream-text">${cs.streamText}</span><span class="cursor-blink">▍</span>
              </div>
              <div id="chat-active-status-container">
                ${
                  cs.status === "TOOL_WORKING"
                    ? `
                  <div class="tool-step-indicator">
                    <span class="tool-step-pulse"></span>
                    <span class="tool-step-icon">${SVG_ICONS.search}</span>
                    <div class="tool-step-content">
                      <span class="tool-step-label">${t("tool_working_label")}</span>
                      <span class="tool-step-desc">${t("tool_working_state")}</span>
                    </div>
                  </div>
                `
                    : `
                  <div id="chat-active-status-pill" class="chat-status-pill ${cs.status.toLowerCase()}">
                    ${cs.status === "WAITING" ? `⏳ ${t("waiting_state")}` : `⚡ ${t("streaming_state")}`}
                  </div>
                `
                }
              </div>
            </div>
          </div>
        `
            : ""
        }
      </div>

      <!-- Floating Scroll-to-Bottom Button (shown when user scrolled up) -->
      <button class="scroll-bottom-fab" style="display: ${cs.userScrolledUp ? "flex" : "none"};" onclick="scrollChatToBottom()">
        ${SVG_ICONS.arrowDown} ${t("scroll_to_bottom")}
      </button>

      <!-- Bottom Sticky Composer -->
      <div class="chat-composer-bar">
        ${
          isOffline
            ? `<div class="chat-disabled-banner">📡 ${t("chat_offline_banner")}</div>`
            : appState.aiSettings.apiKeyMasked === ""
            ? `<div class="chat-disabled-banner">🔑 ${t("chat_no_key_banner")}</div>`
            : ""
        }

        <!-- Input Row -->
        <div class="chat-composer-row">
          <textarea
            id="chat-user-input"
            class="chat-input"
            rows="1"
            placeholder="${t("chat_placeholder")}"
            ${isOffline ? "disabled" : ""}
            onkeydown="handleChatInputKeyDown(event)"
          ></textarea>
          
          ${
            isStreaming
              ? `
            <button class="btn-stop" onclick="stopChatStreaming()" title="${t("stop")}">
              ${SVG_ICONS.stop}
            </button>
          `
              : `
            <button class="btn-send" onclick="sendChatMessage()" ${isOffline ? "disabled" : ""} title="${t("send")}">
              ${SVG_ICONS.send}
            </button>
          `
          }
        </div>

        <!-- Quick Prompt Suggestions (when idle) -->
        ${
          !isStreaming
            ? `
          <div class="chat-suggestions-row">
            <span class="chat-suggestion-chip" onclick="quickFillPrompt('Phân tích chi tiêu hôm nay và tìm hoá đơn chưa khớp')">💡 Phân tích chi tiêu hôm nay</span>
            <span class="chat-suggestion-chip" onclick="quickFillPrompt('Tìm giao dịch Highlands Coffee 35k')">☕ Đối chiếu cafe Highlands</span>
            <span class="chat-suggestion-chip" onclick="quickFillPrompt('Tóm tắt dòng tiền tháng 11')">📊 Tóm tắt dòng tiền</span>
          </div>
        `
            : ""
        }
      </div>

      <!-- Conversation History Modal Sheet -->
      ${
        cs.showHistory
          ? `
        <div class="chat-history-overlay" onclick="closeChatHistory()">
          <div class="chat-history-sheet" onclick="event.stopPropagation()">
            <div class="chat-history-header">
              <span style="font-weight: 700; font-size: 16px;">${t("conversation_history")}</span>
              <button class="back-btn" onclick="closeChatHistory()">✕</button>
            </div>
            <div class="chat-history-list">
              ${cs.historySessions
                .map(
                  (sess) => `
                <div class="chat-history-item" onclick="loadChatSession('${sess.id}')">
                  <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 2px;">
                    <span style="font-size: 13px; font-weight: 700; color: var(--fg-primary);">${sess.title}</span>
                    <span class="chat-status-pill ${sess.status === "COMPLETED" ? "completed" : "stopped"}" style="margin: 0; padding: 2px 6px; font-size: 9px;">${sess.status === "COMPLETED" ? t("completed_state") : t("stopped_state")}</span>
                  </div>
                  <div style="font-size: 12px; color: var(--fg-secondary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">"${sess.prompt}"</div>
                  <div style="font-size: 11px; color: var(--fg-muted); margin-top: 2px;">${sess.dateDisplay} · ${sess.messages ? sess.messages.length : 2} ${t("messages_count_label")}</div>
                </div>
              `
                )
                .join("")}
            </div>
          </div>
        </div>
      `
          : ""
      }
    </div>
  `;
}

// Screen 7: Bank Draft Review Inbox (with working toggleReviewOrder)
function renderReviewInboxScreen(container, stateBannerHtml) {
  const needsDecisionDrafts = appState.drafts.filter((d) => d.status === "NEEDS_DECISION");
  const readyDrafts = appState.drafts.filter((d) => d.status === "READY");
  let currentList = appState.reviewQueueFilter === "NEEDS_DECISION" ? needsDecisionDrafts : readyDrafts;

  if (appState.reviewQueueOrder === "CHRONOLOGICAL") {
    currentList = [...currentList].sort((a, b) => b.id - a.id);
  }

  if (appState.currentStateFamily === "empty" || (needsDecisionDrafts.length === 0 && readyDrafts.length === 0)) {
    container.innerHTML = `
      <div class="screen-view active">
        <div class="screen-header-bar">
          <span class="screen-header-title">Duyệt biến động ngân hàng</span>
        </div>
        <div class="state-empty-container">
          <div class="empty-icon">${SVG_ICONS.check}</div>
          <div class="empty-title">Tuyệt vời! Không có tin chờ duyệt</div>
          <div class="empty-desc">Tất cả thông báo ngân hàng đã được xác nhận hoặc bỏ qua an toàn.</div>
          <button class="btn-secondary" style="max-width: 180px;" onclick="navigateTo('screen-home')">Về trang chủ</button>
        </div>
      </div>
    `;
    return;
  }

  container.innerHTML = `
    <div class="screen-view active">
      ${stateBannerHtml}
      <div class="screen-header-bar">
        <span class="screen-header-title">Duyệt biến động ngân hàng</span>
        <button class="icon-btn" onclick="toggleReviewOrder()" title="Đổi thứ tự sắp xếp">
          ${SVG_ICONS.categories}
        </button>
      </div>

      <!-- Segmented Queue Filter -->
      <div class="segmented-tabs">
        <div class="seg-tab ${appState.reviewQueueFilter === "NEEDS_DECISION" ? "active" : ""}" onclick="setReviewFilter('NEEDS_DECISION')">
          Cần xử lý (${needsDecisionDrafts.length})
        </div>
        <div class="seg-tab ${appState.reviewQueueFilter === "READY" ? "active" : ""}" onclick="setReviewFilter('READY')">
          Sẵn sàng duyệt (${readyDrafts.length})
        </div>
      </div>

      <div style="font-size: 11px; color: var(--fg-muted); padding: 0 4px;">
        Thứ tự: ${appState.reviewQueueOrder === "UNCERTAINTY_FIRST" ? "Ưu tiên xung đột trước (Conflict-First)" : "Theo thời gian mới nhất"}
      </div>

      <!-- Queue Cards -->
      <div style="display: flex; flex-direction: column; gap: 12px;">
        ${currentList
          .map(
            (d) => `
          <div class="app-card" style="cursor: pointer; border-left: 4px solid ${d.status === "NEEDS_DECISION" ? "var(--semantic-warning)" : "var(--semantic-income)"};" onclick="openDraftDetail(${d.id})">
            <div style="display: flex; justify-content: space-between; align-items: center;">
              <div style="display: flex; align-items: center; gap: 8px;">
                <div style="color: var(--brand-primary);">${SVG_ICONS.bank}</div>
                <span style="font-weight: 700; font-size: 14px;">${d.bank}</span>
              </div>
              <span class="txn-amount" style="font-size: 16px; font-weight: 800; color: ${d.amount < 0 ? "var(--semantic-expense)" : "var(--semantic-income)"};">${formatVnd(d.amount)}</span>
            </div>

            <div style="font-size: 12px; color: var(--fg-secondary);">${d.counterparty} · ${d.timestamp}</div>

            ${
              d.conflictText
                ? `
              <div style="background: var(--semantic-warning-bg); color: var(--semantic-warning); border-radius: var(--radius-sm); padding: 6px 10px; font-size: 11px; font-weight: 600; display: flex; align-items: center; gap: 6px;">
                ${SVG_ICONS.warning}
                <span>${d.conflictText}</span>
              </div>
            `
                : ""
            }

            <div style="display: flex; justify-content: space-between; align-items: center; padding-top: 6px; border-top: 1px solid var(--border-subtle);">
              <span style="font-size: 12px; color: var(--brand-primary); font-weight: 600;">Danh mục: ${d.category}</span>
              <span style="font-size: 12px; font-weight: 600; color: var(--fg-muted);">Xem chi tiết ›</span>
            </div>
          </div>
        `
          )
          .join("")}
      </div>
    </div>
  `;
}

// Screen 8: Draft Detail Screen (with Clean Wording & Sticky Bottom CTA)
function renderDraftDetailScreen(container, stateBannerHtml) {
  const draft = appState.drafts.find((d) => d.id === appState.selectedDraftId) || appState.drafts[0];

  container.innerHTML = `
    <div class="screen-view active">
      ${stateBannerHtml}
      <div class="screen-header-bar">
        <button class="back-btn" onclick="navigateBack()">←</button>
        <span class="screen-header-title">Chi tiết biến động</span>
        <button class="icon-btn" style="border:none; color: var(--semantic-expense); font-weight: 700;" onclick="dismissDraft(${draft.id})">Bỏ qua</button>
      </div>

      <!-- Bank Receipt Card -->
      <div class="receipt-card">
        <div style="color: var(--brand-primary);">${SVG_ICONS.bank}</div>
        <div style="font-size: 16px; font-weight: 700;">${draft.bank}</div>
        <div class="receipt-amount">${formatVnd(draft.amount)}</div>
        <div style="font-size: 12px; color: var(--fg-secondary);">${draft.timestamp}</div>
      </div>

      ${
        draft.conflictText
          ? `
        <div class="pending-alert-card" style="cursor: default;">
          <div class="alert-left">
            <span style="color: var(--semantic-warning);">${SVG_ICONS.warning}</span>
            <span class="alert-text">${draft.conflictText}</span>
          </div>
        </div>
      `
          : ""
      }

      <!-- Category Selection with Provenance (Finding 4) -->
      <div class="app-card">
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <span class="form-label">DANH MỤC ÁP DỤNG</span>
          <span style="font-size: 11px; color: var(--brand-primary); font-weight: 600;">
            ${draft.categoryOrigin === "USER" ? "Nguồn: Quy tắc người dùng" : "Nguồn: Gợi ý phân loại AI"}
          </span>
        </div>
        <select class="form-input" onchange="changeDraftCategory(${draft.id}, this.value)">
          ${appState.categories.map((c) => `<option value="${c.name}" ${c.name === draft.category ? "selected" : ""}>${c.name}</option>`).join("")}
        </select>
      </div>

      <!-- Linked Context Note (Clean user-friendly copy - Finding 5) -->
      <div class="app-card">
        <span class="form-label">THÔNG TIN BỐI CẢNH LIÊN KẾT</span>
        ${
          draft.linkedNote
            ? `
          <div style="background: var(--bg-surface-subtle); border-radius: var(--radius-md); padding: 10px; display: flex; justify-content: space-between; align-items: center;">
            <div>
              <div style="font-size: 13px; font-weight: 600;">"${draft.linkedNote}"</div>
              <div style="font-size: 11px; color: var(--semantic-income); font-weight: 600; display: flex; align-items: center; gap: 4px;">
                ${SVG_ICONS.check} ${t("unrecorded_note_origin")}
              </div>
            </div>
            <button class="btn-secondary" style="padding: 4px 8px; font-size: 11px; min-height: 28px;" onclick="unlinkDraftNote(${draft.id})">Huỷ gắn</button>
          </div>
        `
            : `
          <div style="font-size: 13px; color: var(--fg-muted);">Chưa có ghi chú nào được gắn kết.</div>
        `
        }
      </div>

      <!-- Raw Bank SMS (Finding 5: clean title) -->
      <div class="app-card">
        <span class="form-label">${t("raw_notification_header").toUpperCase()}</span>
        <div style="font-family: monospace; font-size: 12px; background: var(--bg-surface-subtle); padding: 10px; border-radius: var(--radius-sm); line-height: 1.4;">
          ${draft.rawText}
        </div>
      </div>

      <!-- Sticky Bottom Action Bar (Always visible!) -->
      <div class="sticky-bottom-bar">
        <button class="btn-primary" onclick="confirmDraft(${draft.id})">${t("confirm_record")}</button>
      </div>
    </div>
  `;
}

// Screen 9: Categories & Automation Rules (Finding 4: ALL categories mutable!)
function renderCategoryRulesScreen(container, stateBannerHtml) {
  const isCatsTab = appState.selectedCategoryTab === "cats";

  container.innerHTML = `
    <div class="screen-view active">
      ${stateBannerHtml}
      <div class="screen-header-bar">
        <button class="back-btn" onclick="navigateBack()">←</button>
        <span class="screen-header-title">Danh mục & Quy tắc</span>
        <button class="icon-btn" onclick="addNewCategoryPrompt()" title="Thêm danh mục mới">${SVG_ICONS.record}</button>
      </div>

      <!-- Segmented Tab Bar with working toggleCategoryTab -->
      <div class="segmented-tabs">
        <div class="seg-tab ${isCatsTab ? "active" : ""}" onclick="toggleCategoryTab('cats')">Danh mục (${appState.categories.length})</div>
        <div class="seg-tab ${!isCatsTab ? "active" : ""}" onclick="toggleCategoryTab('rules')">Quy tắc tự động (${appState.rules.length})</div>
      </div>

      ${
        isCatsTab
          ? `
        <!-- Categories Catalog: All mutable by user! (Finding 4) -->
        <div class="recent-feed-card" style="padding: 4px 12px;">
          <div class="section-header" style="padding: 10px 4px 6px 4px;">
            <span class="section-title">DANH SÁCH DANH MỤC</span>
            <span style="font-size: 11px; color: var(--fg-muted);">Có thể sửa hoặc lưu trữ toàn quyền</span>
          </div>
          ${appState.categories
            .map(
              (c) => `
            <div class="txn-row">
              <div class="txn-left">
                <div class="txn-category-icon" style="background: ${c.bg}; color: ${c.fg};">${SVG_ICONS[c.iconKey] || SVG_ICONS.categories}</div>
                <div class="txn-info">
                  <span class="txn-title">${c.name}</span>
                  <span class="txn-meta">${c.isDefault ? t("hosted_category_tag") : t("user_category_tag")}</span>
                </div>
              </div>
              <div class="txn-right" style="flex-direction: row; gap: 6px;">
                <button class="btn-secondary" style="padding: 4px 10px; font-size: 11px; min-height: 28px;" onclick="renameCategoryPrompt(${c.id}, '${c.name}')">Sửa</button>
                <button class="btn-secondary" style="padding: 4px 8px; font-size: 11px; min-height: 28px; color: var(--semantic-expense);" onclick="deleteCategoryPrompt(${c.id}, '${c.name}')">Xoá</button>
              </div>
            </div>
          `
            )
            .join("")}
        </div>
      `
          : `
        <!-- Automation Rules Tab -->
        <div class="recent-feed-card" style="padding: 4px 12px;">
          <div class="section-header" style="padding: 10px 4px 6px 4px;">
            <span class="section-title">QUY TẮC NHẬN DIỆN TỰ ĐỘNG</span>
            <span class="section-action" onclick="alert('Đã thêm quy tắc mẫu')">+ Thêm quy tắc</span>
          </div>
          ${appState.rules
            .map(
              (r) => `
            <div class="txn-row">
              <div class="txn-info">
                <span class="txn-title" style="font-family: monospace;">"${r.pattern}"</span>
                <span class="txn-meta">Gán vào: <strong>${r.category}</strong></span>
              </div>
              <span style="color: var(--semantic-income); font-weight: 700; font-size: 12px;">Đang bật</span>
            </div>
          `
            )
            .join("")}
        </div>
      `
      }

      <div class="sticky-bottom-bar">
        <button class="btn-primary" onclick="addNewCategoryPrompt()">+ Thêm danh mục mới</button>
      </div>
    </div>
  `;
}

// Screen 10: Settings Hub (Expanded per Finding 9)
function renderSettingsScreen(container, stateBannerHtml) {
  const ai = appState.aiSettings;
  const bank = appState.bankSettings;

  container.innerHTML = `
    <div class="screen-view active">
      ${stateBannerHtml}
      <div class="screen-header-bar">
        <button class="back-btn" onclick="navigateBack()">←</button>
        <span class="screen-header-title">${t("feature_settings")}</span>
        <span style="width: 36px;"></span>
      </div>

      <!-- Section: Bank Notification Listener & Lifecycle (Finding 9) -->
      <div class="app-card">
        <span class="section-title">KẾT NỐI & THÔNG BÁO NGÂN HÀNG</span>
        <div style="display: flex; justify-content: space-between; align-items: center; padding: 6px 0;">
          <div>
            <div style="font-size: 14px; font-weight: 700;">Dịch vụ lắng nghe thông báo</div>
            <div style="font-size: 12px; color: var(--fg-secondary);">Quyền hệ thống: <strong style="color: var(--semantic-income);">Đã cấp quyền (Granted)</strong></div>
          </div>
          <input type="checkbox" ${bank.listenerActive ? "checked" : ""} style="width: 20px; height: 20px;" onchange="toggleListenerService(this.checked)">
        </div>

        <div style="display: flex; gap: 8px;">
          <button class="btn-secondary" style="font-size: 12px; padding: 6px 12px; min-height: 36px;" onclick="checkSystemPermission()">Kiểm tra quyền hệ thống</button>
          <button class="btn-secondary" style="font-size: 12px; padding: 6px 12px; min-height: 36px;" onclick="simulateTestIntake()">Gửi tin thử nghiệm</button>
        </div>

        <div style="font-size: 12px; font-weight: 600; color: var(--fg-secondary); margin-top: 6px;">DANH SÁCH ỨNG DỤNG NGÂN HÀNG CHO PHÉP</div>
        <div style="display: flex; flex-direction: column; gap: 6px;">
          ${bank.allowedPackages
            .map(
              (p) => `
            <div style="display: flex; justify-content: space-between; align-items: center; background: var(--bg-surface-subtle); padding: 8px 10px; border-radius: var(--radius-sm); font-size: 12px;">
              <div>
                <strong>${p.name}</strong>
                <div style="font-family: monospace; font-size: 10px; color: var(--fg-muted);">${p.pkg}</div>
              </div>
              <span style="color: var(--semantic-income); font-weight: 700;">Hoạt động</span>
            </div>
          `
            )
            .join("")}
        </div>
      </div>

      <!-- Section: Multi-Profile Hosted AI Configuration (Finding 9) -->
      <div class="app-card">
        <span class="section-title">CẤU HÌNH MÔ HÌNH TRÍ TUỆ NHÂN TẠO</span>
        <div class="form-group">
          <label class="form-label">Nhà cung cấp AI (Provider Preset)</label>
          <select class="form-input" onchange="changeAiPreset(this.value)">
            <option value="openai" ${ai.selectedPreset === "openai" ? "selected" : ""}>OpenAI (Official API)</option>
            <option value="gemini" ${ai.selectedPreset === "gemini" ? "selected" : ""}>Google Gemini</option>
            <option value="ollama" ${ai.selectedPreset === "ollama" ? "selected" : ""}>Ollama (Local on-device)</option>
            <option value="custom" ${ai.selectedPreset === "custom" ? "selected" : ""}>Custom Compatible Endpoint</option>
          </select>
        </div>
        <div class="form-group">
          <label class="form-label">Tên mô hình (Model Name)</label>
          <input class="form-input" type="text" value="${ai.modelName}">
        </div>
        <div class="form-group">
          <label class="form-label">Endpoint Base URL</label>
          <input class="form-input" type="text" value="${ai.baseUrl}">
        </div>
        <div class="form-group">
          <label class="form-label">Khoá API bí mật (Vault Masked)</label>
          <input class="form-input" type="password" value="${ai.apiKeyMasked}" readonly>
          <span style="font-size: 11px; color: var(--semantic-income); font-weight: 600;">🔒 Đã bảo vệ an toàn trong Android Keystore</span>
        </div>
        <div class="form-group">
          <label class="form-label">Mức độ suy luận (Reasoning Effort)</label>
          <select class="form-input">
            <option>Thấp (Low - Nhanh nhất)</option>
            <option selected>Trung bình (Medium - Cân bằng)</option>
            <option>Cao (High - Chi tiết nhất)</option>
          </select>
        </div>
        <div style="display: flex; justify-content: space-between; align-items: center; padding: 4px 0;">
          <span style="font-size: 13px; font-weight: 500;">Gọi công cụ song song (Parallel Tools)</span>
          <input type="checkbox" checked style="width: 18px; height: 18px;">
        </div>

        <button class="btn-secondary" style="font-size: 13px;" onclick="testAiConnection()">Kiểm tra kết nối AI</button>
        <span id="ai-test-status" style="font-size: 11px; color: var(--fg-muted); text-align: center;">Lần test gần nhất: ${ai.lastTested}</span>
      </div>

      <!-- Section: Privacy & Security Disclosures -->
      <div class="app-card">
        <span class="section-title">BẢO MẬT & KHO DỮ LIỆU VAULT</span>
        <div style="font-size: 13px; color: var(--fg-secondary); line-height: 1.4;">
          Aegis xử lý toàn bộ biến động ngân hàng và số dư trên thiết bị cục bộ. Dữ liệu tài chính không bao giờ bị bán hoặc chia sẻ cho bên thứ ba.
        </div>
        <div style="display: flex; gap: 8px;">
          <button class="btn-secondary" style="font-size: 12px;" onclick="clearAiCache()">Xoá bộ nhớ đệm AI</button>
          <button class="btn-secondary" style="font-size: 12px;" onclick="alert('Kho Vault Keystore đang an toàn!')">Trạng thái Vault</button>
        </div>
      </div>

      <!-- Version & Contract Parity -->
      <div style="text-align: center; font-size: 12px; color: var(--fg-muted); padding: 12px 0;">
        Aegis Mobile Redesign · Sprint 10 Architecture · v1.0.0 (Round 2)
      </div>
    </div>
  `;
}

// ==========================================
// ALL JS HANDLERS (Resolves Finding 1)
// ==========================================

// 1. filterLedgerByCategory
function filterLedgerByCategory(categoryName) {
  appState.selectedLedgerCategory = categoryName;
  navigateTo("screen-ledger");
}

function clearCategoryFilter() {
  appState.selectedLedgerCategory = null;
  renderScreen();
}

function resetLedgerFilters() {
  appState.selectedLedgerType = "ALL";
  appState.selectedLedgerCategory = null;
  renderScreen();
}

// 2. selectFormType
function selectFormType(type) {
  appState.manualRecordForm.type = type;
  renderScreen();
}

// 3. selectCategorySwatch
function selectCategorySwatch(element, categoryName) {
  appState.manualRecordForm.category = categoryName;
  renderScreen();
}

function updateFormAmount(val) {
  const num = parseInt(val, 10) || 0;
  appState.manualRecordForm.amount = num;
  const wordsEl = document.getElementById("form-amount-words");
  if (wordsEl) {
    wordsEl.innerText = num > 0 ? `${num.toLocaleString("vi-VN")} Đồng Việt Nam` : "Không đồng";
  }
}

// 4. clearNotes
function clearNotes() {
  if (confirm("Bạn có muốn xoá toàn bộ lịch sử ghi chép cục bộ?")) {
    appState.notes = [];
    alert("Đã làm sạch nhật ký ghi chép.");
    renderScreen();
  }
}

// 5. toggleReviewOrder
function toggleReviewOrder() {
  appState.reviewQueueOrder = appState.reviewQueueOrder === "UNCERTAINTY_FIRST" ? "CHRONOLOGICAL" : "UNCERTAINTY_FIRST";
  renderScreen();
}

// 6. addNewCategoryPrompt & category management
function addNewCategoryPrompt() {
  const name = prompt("Nhập tên danh mục mới:");
  if (name && name.trim()) {
    const trimmed = name.trim();
    if (appState.categories.some((c) => c.name.toLowerCase() === trimmed.toLowerCase())) {
      alert("Danh mục này đã tồn tại!");
      return;
    }
    appState.categories.push({
      id: Date.now(),
      name: trimmed,
      iconKey: "categories",
      bg: "#FEF3C7",
      fg: "#D97706",
      spent: 0,
      percent: 0,
      isDefault: false,
    });
    alert(`Đã thêm danh mục "${trimmed}" thành công!`);
    renderScreen();
  }
}

function renameCategoryPrompt(id, currentName) {
  const newName = prompt(`Đổi tên danh mục "${currentName}" thành:`, currentName);
  if (newName && newName.trim()) {
    const c = appState.categories.find((item) => item.id === id);
    if (c) {
      c.name = newName.trim();
      alert(`Đã đổi tên thành "${c.name}".`);
      renderScreen();
    }
  }
}

function deleteCategoryPrompt(id, name) {
  if (confirm(`Bạn có chắc chắn muốn xoá danh mục "${name}"?`)) {
    appState.categories = appState.categories.filter((c) => c.id !== id);
    alert(`Đã xoá danh mục "${name}".`);
    renderScreen();
  }
}

// 7. toggleCategoryTab
function toggleCategoryTab(tab) {
  appState.selectedCategoryTab = tab;
  renderScreen();
}

// Month switcher
function changeMonth(delta) {
  appState.currentMonthIndex = Math.max(0, Math.min(11, appState.currentMonthIndex + delta));
  renderScreen();
}

function setLedgerTypeFilter(type) {
  appState.selectedLedgerType = type;
  renderScreen();
}

function setReviewFilter(filter) {
  appState.reviewQueueFilter = filter;
  renderScreen();
}

function searchLedger(query) {
  // Realtime search simulation
  const term = query.toLowerCase().trim();
  const rows = document.querySelectorAll(".recent-feed-card .txn-row");
  rows.forEach((r) => {
    const text = r.innerText.toLowerCase();
    r.style.display = text.includes(term) ? "flex" : "none";
  });
}

function openTransactionDetail(id) {
  appState.selectedTxnId = id;
  navigateTo("screen-detail");
}

function openDraftDetail(id) {
  appState.selectedDraftId = id;
  navigateTo("screen-draft-detail");
}

function confirmDraft(id) {
  const d = appState.drafts.find((item) => item.id === id);
  if (d) {
    d.status = "CONFIRMED";
    appState.transactions.unshift({
      id: Date.now(),
      title: d.counterparty,
      amount: d.amount,
      type: "EXPENSE",
      category: d.category,
      iconKey: "bank",
      iconBg: "#FFE4E6",
      iconFg: "#E11D48",
      date: "Vừa xong",
      source: d.bank,
      note: "Xác nhận từ thông báo " + d.bank,
    });
    alert(`Đã xác nhận ${formatVnd(d.amount)} vào sổ giao dịch!`);
    navigateTo("screen-ledger");
  }
}

function dismissDraft(id) {
  const d = appState.drafts.find((item) => item.id === id);
  if (d) {
    d.status = "DISMISSED";
    alert("Đã bỏ qua biến động.");
    navigateTo("screen-review");
  }
}

function changeDraftCategory(id, categoryName) {
  const d = appState.drafts.find((item) => item.id === id);
  if (d) {
    d.category = categoryName;
    d.categoryOrigin = "USER";
    renderScreen();
  }
}

function unlinkDraftNote(id) {
  const d = appState.drafts.find((item) => item.id === id);
  if (d) {
    d.linkedNote = null;
    d.linkedNoteStatus = null;
    alert("Đã huỷ gắn kết ghi chú thành công.");
    renderScreen();
  }
}

function saveManualRecord() {
  const form = appState.manualRecordForm;
  const merchantEl = document.getElementById("form-merchant-input");
  const noteEl = document.getElementById("form-note-input");
  const accountEl = document.getElementById("form-account-select");

  const merchant = (merchantEl && merchantEl.value) || form.merchant;
  const note = (noteEl && noteEl.value) || form.note;
  const source = (accountEl && accountEl.value) || form.account;

  const amt = form.type === "INCOME" ? form.amount : -form.amount;

  appState.transactions.unshift({
    id: Date.now(),
    title: merchant,
    amount: amt,
    type: form.type,
    category: form.category,
    iconKey: form.type === "INCOME" ? "income" : "food",
    iconBg: form.type === "INCOME" ? "#D1FAE5" : "#FFE4E6",
    iconFg: form.type === "INCOME" ? "#059669" : "#E11D48",
    date: "Vừa xong",
    source: source,
    note: note,
  });

  alert(`Đã lưu giao dịch ${formatVnd(amt)} vào sổ quỹ!`);
  navigateTo("screen-ledger");
}

function deleteTransaction(id) {
  if (confirm("Bạn có chắc chắn muốn xoá giao dịch này khỏi sổ quỹ?")) {
    appState.transactions = appState.transactions.filter((tItem) => tItem.id !== id);
    alert("Đã xoá giao dịch.");
    navigateTo("screen-ledger");
  }
}

function saveLocalNoteOnly() {
  const inputEl = document.getElementById("capture-note-input");
  const text = inputEl ? inputEl.value : "";
  if (!text.trim()) return;
  appState.notes.unshift({
    id: Date.now(),
    text: text,
    timestamp: "Vừa xong",
    parsed: false,
    events: [],
  });
  if (inputEl) inputEl.value = "";
  showToast(t("note_saved_success"));
  renderScreen();
}

function saveEventToLedger(amount, title, category) {
  appState.transactions.unshift({
    id: Date.now(),
    title: title,
    amount: -amount,
    type: "EXPENSE",
    category: category,
    iconKey: "food",
    iconBg: "#FFE4E6",
    iconFg: "#E11D48",
    date: "Vừa xong",
    source: "Tiền mặt",
    note: "Tạo từ sự kiện ghi chú AI",
  });
  alert(`Đã tạo giao dịch ${formatVnd(-amount)} vào sổ quỹ!`);
  navigateTo("screen-ledger");
}

// Quick Note Actions
function sendCurrentNoteToAi() {
  const el = document.getElementById("capture-note-input");
  const text = (el && el.value.trim()) || "Trưa nay ăn phở Thìn 65k, uống cafe Highland 35k";
  appState.chatState.attachedContext = { type: "NOTE", text: text };
  navigateTo("screen-chat");
}

function sendNoteToAi(id) {
  const note = appState.notes.find((n) => n.id === id);
  if (note) {
    appState.chatState.attachedContext = { type: "NOTE", id: note.id, text: note.text };
    navigateTo("screen-chat");
  }
}

function deleteNote(id) {
  if (confirm("Bạn có chắc muốn xoá ghi chú này khỏi nhật ký cục bộ?")) {
    appState.notes = appState.notes.filter((n) => n.id !== id);
    renderScreen();
  }
}

// AI Chat Handlers
function sendChatMessage() {
  const el = document.getElementById("chat-user-input");
  if (!el) return;
  const text = el.value.trim();
  if (!text) return;
  el.value = "";
  startSyntheticChat(text, appState.chatState.attachedContext);
}

function handleChatInputKeyDown(event) {
  if (event.key === "Enter" && !event.shiftKey) {
    event.preventDefault();
    sendChatMessage();
  }
}

function quickFillPrompt(text) {
  startSyntheticChat(text, appState.chatState.attachedContext);
}

function startSyntheticChat(promptText, attachedContext) {
  if (!promptText || promptText.trim() === "") return;

  if (appState.chatState.typingTimer) {
    clearInterval(appState.chatState.typingTimer);
    appState.chatState.typingTimer = null;
  }

  appState.chatState.lastPrompt = promptText;

  const now = new Date();
  const timeStr = `${String(now.getHours()).padStart(2, "0")}:${String(now.getMinutes()).padStart(2, "0")}`;

  // 1. Add user message
  appState.chatState.messages.push({
    id: Date.now(),
    role: "user",
    text: promptText,
    timestamp: timeStr,
    context: attachedContext ? `Ghi chú đính kèm: "${attachedContext.text}"` : null,
  });

  // 2. Set WAITING status
  appState.chatState.status = "WAITING";
  appState.chatState.statusMessage = t("waiting_state");
  appState.chatState.streamText = "";
  appState.chatState.userScrolledUp = false;

  renderScreen();
  scrollChatToBottom();

  // 3. Define incremental response chunks
  const chunks = [
    "Tôi đã tiếp nhận yêu cầu của bạn",
    attachedContext ? ` cùng ngữ cảnh ghi chú đính kèm` : "",
    ".\n\nĐang tiến hành phân tích chi tiết...\n",
    "• Nhận diện khoản chi: **Highlands Coffee** — 35.000 ₫ (Ăn uống).\n",
    "• Mốc thời gian đối soát: 14:15 hôm nay.\n\n",
    "Đang kích hoạt công cụ đối chiếu biến động ngân hàng chưa khớp...\n",
    "Phát hiện 01 giao dịch trùng khớp trên Vietcombank: -150.000 ₫ lúc 14:15 (POS HIGHLANDS COFFEE CR7).\n",
    "Tôi đã tạo đề xuất liên kết bên dưới. Bạn có thể xác nhận liên kết hoặc từ chối.",
  ];

  let chunkIdx = 0;

  setTimeout(() => {
    if (appState.chatState.status !== "WAITING") return;
    appState.chatState.status = "STREAMING";
    appState.chatState.statusMessage = t("streaming_state");
    renderScreen();
    scrollChatToBottom();

    appState.chatState.typingTimer = setInterval(() => {
      if (chunkIdx < chunks.length) {
        appState.chatState.streamText += chunks[chunkIdx];
        chunkIdx++;

        if (chunkIdx === 5) {
          appState.chatState.status = "TOOL_WORKING";
          appState.chatState.statusMessage = t("tool_working_state");
        } else if (chunkIdx > 5 && appState.chatState.status === "TOOL_WORKING") {
          appState.chatState.status = "STREAMING";
          appState.chatState.statusMessage = t("streaming_state");
        }

        const textEl = document.getElementById("chat-active-stream-text");
        const statusContainer = document.getElementById("chat-active-status-container");
        if (textEl) textEl.innerText = appState.chatState.streamText;
        if (statusContainer) {
          if (appState.chatState.status === "TOOL_WORKING") {
            statusContainer.innerHTML = `
              <div class="tool-step-indicator">
                <span class="tool-step-pulse"></span>
                <span class="tool-step-icon">${SVG_ICONS.search}</span>
                <div class="tool-step-content">
                  <span class="tool-step-label">${t("tool_working_label")}</span>
                  <span class="tool-step-desc">${t("tool_working_state")}</span>
                </div>
              </div>
            `;
          } else {
            statusContainer.innerHTML = `
              <div id="chat-active-status-pill" class="chat-status-pill ${appState.chatState.status.toLowerCase()}">
                ${appState.chatState.status === "WAITING" ? `⏳ ${t("waiting_state")}` : `⚡ ${t("streaming_state")}`}
              </div>
            `;
          }
        }

        if (!appState.chatState.userScrolledUp) {
          scrollChatToBottom();
        }
      } else {
        clearInterval(appState.chatState.typingTimer);
        appState.chatState.typingTimer = null;
        appState.chatState.status = "COMPLETED";
        appState.chatState.statusMessage = t("completed_state");

        appState.chatState.messages.push({
          id: Date.now() + 1,
          role: "assistant",
          text: appState.chatState.streamText,
          timestamp: timeStr,
          status: "COMPLETED",
          hasLinkCard: true,
        });
        appState.chatState.streamText = "";
        appState.chatState.attachedContext = null;

        renderScreen();
        scrollChatToBottom();
      }
    }, 180);
  }, 350);
}

function stopChatStreaming() {
  if (appState.chatState.typingTimer) {
    clearInterval(appState.chatState.typingTimer);
    appState.chatState.typingTimer = null;
  }

  const partialText = appState.chatState.streamText;
  appState.chatState.status = "STOPPED";
  appState.chatState.statusMessage = t("stopped_state");

  const now = new Date();
  const timeStr = `${String(now.getHours()).padStart(2, "0")}:${String(now.getMinutes()).padStart(2, "0")}`;

  if (partialText && partialText.trim().length > 0) {
    appState.chatState.messages.push({
      id: Date.now(),
      role: "assistant",
      text: partialText + " [Đã dừng phản hồi]",
      timestamp: timeStr,
      status: "STOPPED",
      hasLinkCard: false,
    });
  }
  appState.chatState.streamText = "";
  renderScreen();
  scrollChatToBottom();
}

function retryLastChat() {
  if (appState.chatState.lastPrompt) {
    startSyntheticChat(appState.chatState.lastPrompt, appState.chatState.attachedContext);
  }
}

function removeChatContext() {
  appState.chatState.attachedContext = null;
  renderScreen();
}

function clearActiveChat() {
  if (confirm("Làm mới đoạn hội thoại này?")) {
    if (appState.chatState.typingTimer) {
      clearInterval(appState.chatState.typingTimer);
      appState.chatState.typingTimer = null;
    }
    appState.chatState.messages = [];
    appState.chatState.streamText = "";
    appState.chatState.status = "IDLE";
    appState.chatState.statusMessage = "";
    renderScreen();
  }
}

function attachLinkFromChat() {
  appState.chatState.proposedLink.status = "ATTACHED";
  const d = appState.drafts.find((item) => item.id === 201);
  if (d) {
    d.linkedNote = "Ghi chú #301 (Gắn kết qua AI Chat)";
    d.linkedNoteStatus = "ATTACHED";
  }
  alert("✓ Đã gắn kết ghi chú với biến động Vietcombank thành công!");
  renderScreen();
}

function rejectLinkFromChat() {
  appState.chatState.proposedLink.status = "REJECTED";
  alert("✕ Đã từ chối liên kết đề xuất.");
  renderScreen();
}

function unlinkFromChat() {
  appState.chatState.proposedLink.status = "PROPOSED";
  alert("Đã huỷ gắn kết đề xuất.");
  renderScreen();
}

function openChatHistory() {
  appState.chatState.showHistory = true;
  renderScreen();
}

function closeChatHistory() {
  appState.chatState.showHistory = false;
  renderScreen();
}

function loadChatSession(sessionId) {
  const sess = appState.chatState.historySessions.find((s) => s.id === sessionId || s.runId === sessionId);
  if (sess && sess.messages) {
    appState.chatState.messages = JSON.parse(JSON.stringify(sess.messages));
    appState.chatState.showHistory = false;
    renderScreen();
    scrollChatToBottom();
  }
}

function scrollChatToBottom() {
  setTimeout(() => {
    const el = document.getElementById("chat-transcript-area");
    if (el) {
      el.scrollTop = el.scrollHeight;
    }
  }, 10);
  appState.chatState.userScrolledUp = false;
}

function onChatScroll(el) {
  if (!el) return;
  const isUp = el.scrollTop < el.scrollHeight - el.clientHeight - 60;
  if (appState.chatState.userScrolledUp !== isUp) {
    appState.chatState.userScrolledUp = isUp;
    const fab = document.querySelector(".scroll-bottom-fab");
    if (fab) fab.style.display = isUp ? "flex" : "none";
  }
}

// Settings Handlers
function toggleListenerService(active) {
  appState.bankSettings.listenerActive = active;
  alert(active ? "Đã bật dịch vụ lắng nghe thông báo!" : "Đã tắt dịch vụ lắng nghe thông báo.");
}

function checkSystemPermission() {
  alert("Quyền lắng nghe thông báo: ĐÃ CẤP QUYỀN (Notification Access Granted).");
}

function simulateTestIntake() {
  alert("Đã gửi tin nhắn giả lập từ Vietcombank vào Review Queue!");
  navigateTo("screen-review");
}

function changeAiPreset(preset) {
  appState.aiSettings.selectedPreset = preset;
  if (preset === "openai") {
    appState.aiSettings.modelName = "gpt-4o-mini";
    appState.aiSettings.baseUrl = "https://api.openai.com/v1";
  } else if (preset === "gemini") {
    appState.aiSettings.modelName = "gemini-1.5-flash";
    appState.aiSettings.baseUrl = "https://generativelanguage.googleapis.com";
  } else if (preset === "ollama") {
    appState.aiSettings.modelName = "llama3.2";
    appState.aiSettings.baseUrl = "http://localhost:11434";
  }
  renderScreen();
}

function testAiConnection() {
  const statusEl = document.getElementById("ai-test-status");
  if (statusEl) statusEl.innerText = "Đang kiểm tra kết nối...";
  setTimeout(() => {
    appState.aiSettings.lastTested = "Vừa xong (HTTP 200 OK - 180ms)";
    if (statusEl) statusEl.innerText = "Kết nối thành công: HTTP 200 OK (180ms)";
    alert("Kiểm tra kết nối mô hình AI thành công!");
  }, 300);
}

function clearAiCache() {
  alert("Đã làm sạch bộ nhớ đệm AI thành công.");
}


function showToast(message) {
  let toast = document.getElementById("app-toast");
  if (!toast) {
    toast = document.createElement("div");
    toast.id = "app-toast";
    toast.className = "app-toast";
    const frame = document.querySelector(".device-screen-frame") || document.body;
    frame.appendChild(toast);
  }
  toast.textContent = message;
  toast.classList.add("visible");
  clearTimeout(window._toastTimeout);
  window._toastTimeout = setTimeout(() => {
    toast.classList.remove("visible");
  }, 2200);
}
// Initialize on DOM load
window.addEventListener("DOMContentLoaded", () => {
  updateI18nElements();
  renderScreen();
});
