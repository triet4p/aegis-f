# Aegis Detailed Screen Specifications & State Matrix

**Task ID:** S10.1  
**Attempt:** S10.1-design-a1  
**Date:** 2026-11-09  
**Designer:** S101Designer  

---

## 1. Screen 1: Home Dashboard (`HomeScreen` - Lightweight Task Launcher)

### 1.1 Purpose & Primary Actions
The Home Dashboard is a true single-purpose task launcher and compact financial glance. In accordance with the owner's anti-stacking clarification, Home does not pile heterogeneous functional card blocks, multi-row feeds, or intrusive alerts on one screen. It provides a compact overview and serves strictly as the primary task launchpad.
- **Explicit Purpose:** Dedicated task launcher and glanceable financial status.
- **Primary Action:** Tap one of the 8 labelled squircle feature icons sitting directly on the surface to begin a focused workflow.
- **Secondary Routes:**
  * Tap Eye Toggle to conceal all balance numbers (`*** *** ₫`).
  * Tap "Báo cáo ›" on the glance bar to open `AnalyticsScreen` for in-depth income and cashflow breakdowns.
  * Tap Notification Bell (`[Bell 3]`) or "Duyệt tin" squircle to open `ReviewInboxScreen`.
  * Tap User Avatar (`[Avatar]`) or "Cài đặt" squircle to open `SettingsScreen`.

### 1.2 Layout & Wireframe Structure
```
+---------------------------------------------------+
| [Avatar] Chào buổi sáng, Triết!          [Bell 3] | Top App Bar
+---------------------------------------------------+
|  +---------------------------------------------+  | Lightweight Glance Bar
|  | [Anal] Chi tiêu tháng này: 14.850.000 ₫ [Eye]|  | (Single Row, 48px)
|  |                                  Báo cáo >  |  | (Links to Analytics)
|  +---------------------------------------------+  |
|                                                   |
|  Bạn muốn thực hiện tác vụ nào?                   | Task Prompt
|                                                   |
|  +--------+   +--------+   +--------+   +--------+| 8-Item Launchers
|  | [Ledg] |   | [Anal] |   | [Note] |   | [Chat*]|| (Directly on Surface)
|  | Sổ quỹ |   |Báo cáo |   |Ghi chép|   |Trợ lý AI|| (Row 1)
|  +--------+   +--------+   +--------+   +--------+| (*Featured Gradient)
|                                                   |
|  +--------+   +--------+   +--------+   +--------+|
|  | [Rev3] |   | [Plus] |   | [Rule] |   | [Cog]  ||
|  |Duyệt tin|  |Nhập tay|   |Danh mục|   |Cài đặt || (Row 2)
|  +--------+   +--------+   +--------+   +--------+| (Unread Badged #5)
|                                                   |
|  (All 4-card 543px stack blocks completely removed)|
|                                                   |
| [Trang chủ] [Sổ quỹ] [Ghi chép] [Trợ lý AI] [Duyệt tin] | 5-Tab Bottom Nav
+---------------------------------------------------+
```
---

## 2. Screen 2: Spending & Financial Analytics (`AnalyticsScreen`)

### 2.1 Purpose & Primary Actions
A focused screen dedicated to in-depth analysis of confirmed expenses, income, internal transfers, category breakdown, and daily spending velocity.
- **Primary Actions:**
  * Switch time period using `< Tháng MM/YYYY >` or pick custom date range.
  * Toggle category filter to isolate specific category spending.
  * Inspect Donut / Proportional breakdown of expenses by category.
  * View daily spending velocity bar chart.
  * Tap any category row to filter ledger records for that category.

### 2.2 Layout & Wireframe Structure
```
+---------------------------------------------------+
| [<-] Báo cáo chi tiêu                   [Filter]  | Top App Bar
+---------------------------------------------------+
|       <            Tháng 11/2024            >     | Month Switcher
|       (01/11/2024 - 30/11/2024 · 30 ngày)         |
+---------------------------------------------------+
|  +-----------------------+ +--------------------+ |
|  | TỔNG CHI TIÊU         | | TỔNG THU NHẬP      | | KPI Comparison
|  | 14.850.000 ₫          | | 28.500.000 ₫       | | Cards
|  | ↑ 12% so với tháng trước| | ↓ 5% so với tháng tr.| |
|  +-----------------------+ +--------------------+ |
|  | THU - CHI: +13.650.000 ₫ (Dương tiền mặt)     | | Net Cashflow Pill
+---------------------------------------------------+
|  PHÂN BỔ THEO DANH MỤC                            |
|       [ Donut Chart / Category Distribution Bar ]  | Visual Breakdown
|  1. Ăn uống (Food)         42%        6.250.000 ₫ >|
|  2. Mua sắm (Shopping)     24%        3.560.000 ₫ >| Drill-Down Rows
|  3. Nhà cửa & Hoá đơn      18%        2.670.000 ₫ >| (Click opens
|  4. Di chuyển (Transport)  10%        1.485.000 ₫ >|  scoped list)
|  5. Khác (Others)           6%          885.000 ₫ >|
+---------------------------------------------------+
|  CHI TIÊU THEO NGÀY                               |
|       [ Daily Spending Bar Histogram ]            | Velocity Chart
+---------------------------------------------------+
```

---

## 3. Screen 3: All Transactions / Ledger (`LedgerScreen`)

### 3.1 Purpose & Primary Actions
The authoritative record book for browsing, searching, and filtering all confirmed transactions.
- **Primary Actions:**
  * Search transactions by merchant, description, or notes.
  * Quick filter chips: All, Chi tiêu (Expense), Thu nhập (Income), Chuyển khoản (Transfer).
  * Category filter pill and date range selector.
  * Infinite / paginated list of transactions grouped by day.
  * Tap any transaction row to open `TransactionDetailScreen`.
  * FAB / Top action: "Thêm giao dịch" (+).

### 3.2 Layout & Wireframe Structure
```
+---------------------------------------------------+
| Sổ giao dịch                          [Search] [+]| Top App Bar
+---------------------------------------------------+
| [Search Input: Tìm theo tên, ghi chú, số tiền...] | Search Bar
| [ Tất cả ] [ Chi tiêu ] [ Thu nhập ] [ Chuyển khoản ] Filter Chips
| [ Bộ lọc ngày: Tháng 11 v ] [ Danh mục: Tất cả v ]| Secondary Filters
+---------------------------------------------------+
| HÔM NAY (09/11/2024)                              | Date Header
|   [Food Icon]  Phở Thìn Bờ Hồ           -65.000 ₫ |
|                Ăn uống · Tiền mặt · 12:30        | Transaction Row
|   [Shop Icon]  Uniqlo Đồng Khởi        -899.000 ₫ |
|                Mua sắm · Vietcombank · 15:45     |
+---------------------------------------------------+
| HÔM QUA (08/11/2024)                              |
|   [Income]     Lương tháng 10       +28.500.000 ₫ |
|                Thu nhập · Techcombank · 09:15    |
|   [Transfer]   Chuyển sang tiết kiệm -5.000.000 ₫ |
|                Chuyển khoản nội bộ · 18:00       |
+---------------------------------------------------+
| Trang 1 / 4 · Hiển thị 20 giao dịch               | Pagination Info
+---------------------------------------------------+
```

---

## 4. Screen 4: Transaction Detail (`TransactionDetailScreen`)

### 4.1 Purpose & Primary Actions
Digital receipt screen displaying comprehensive provenance, financial data, and edit/delete actions.
- **Primary Actions:**
  * View exact amount, type, category swatch, timestamp, counterparty, source bank account, and notes.
  * View linked note evidence or bank notification origin (if applicable).
  * Tap "Chỉnh sửa" to open `TransactionFormScreen`.
  * Tap "Xoá giao dịch" to trigger confirmation dialog.

### 4.2 Layout & Wireframe Structure
```
+---------------------------------------------------+
| [<-] Chi tiết giao dịch                  [Share]  | Top App Bar
+---------------------------------------------------+
|  +---------------------------------------------+  |
|  |              [ Food Icon Squircle ]         |  | Receipt Header
|  |                 Phở Thìn Bờ Hồ              |  |
|  |                  - 65.000 ₫                 |  | Large Amount
|  |              [ Chi tiêu · Đã xác nhận ]     |  | Status Badge
|  +---------------------------------------------+  |
|                                                   |
|  THÔNG TIN CHI TIẾT                               | Key-Value Rows
|  Danh mục:          Ăn uống (Food & Dining)       |
|  Thời gian:         12:30, Thứ Bảy 09/11/2024     |
|  Nguồn tiền:        Tiền mặt (Cash)               |
|  Ghi chú:           Ăn trưa cùng đồng nghiệp      |
|  Mã giao dịch:      TXN-20241109-0824             |
|  Nguồn gốc:         Nhập thủ công (Manual entry)  |
|                                                   |
|  [ Chỉnh sửa giao dịch ]   [ Xoá giao dịch (Đỏ) ] | Action Buttons
+---------------------------------------------------+
```

---

## 5. Screen 5: Manual Transaction Entry & Edit Form (`ManualRecordScreen` / `TransactionFormScreen`)

### 5.1 Purpose & Primary Actions
A fast, distraction-free transaction creator and editor designed for one-handed thumb entry.
- **Primary Actions:**
  * Enter amount in VND via custom large input or keypad.
  * Select Transaction Type: Chi tiêu (Expense), Thu nhập (Income), Chuyển khoản (Transfer).
  * Select Category from quick swatch grid or full modal picker.
  * Pick Date & Time (defaults to now).
  * Optional Note / Memo text field.
  * Optional Handoff: "Gắn với sự kiện ghi chú chưa lưu" (Unrecorded Note Event).
  * Tap "Lưu giao dịch" (Save) or "Huỷ" (Cancel).

### 5.2 Layout & Wireframe Structure
```
+---------------------------------------------------+
| [X] Thêm giao dịch mới                     [Lưu]  | Top App Bar
+---------------------------------------------------+
| [ Chi tiêu (Expense) ] [ Thu nhập ] [ Chuyển khoản ] Segmented Tabs
+---------------------------------------------------+
| SỐ TIỀN GIAO DỊCH                                 |
|                     150.000 ₫                     | Hero Amount Input
|             (Một trăm năm mươi nghìn đồng)        | Number in words
+---------------------------------------------------+
| CHỌN DANH MỤC                                     |
|  [Ăn uống*] [Mua sắm] [Di chuyển] [Hoá đơn]       | Quick Swatch Grid
|  [Nhà cửa]  [Sức khoẻ][Giải trí]  [+ Thêm mục]    |
+---------------------------------------------------+
| THÔNG TIN BỔ SUNG                                 |
| Ngày & giờ:   [ 09/11/2024 - 14:30              v]| Date/Time Picker
| Ví / Nguồn:   [ Tiền mặt (Cash)                 v]| Account Picker
| Đối tác:      [ Highland Coffee                  ]| Counterparty Field
| Ghi chú:      [ Cafe gặp đối tác sáng thứ 7      ]| Note Field
+---------------------------------------------------+
| [ LƯU VÀO SỔ GIAO DỊCH ]                          | Primary Action
+---------------------------------------------------+
```

---

## 6. Screen 6: Quick Note Journal (`QuickCaptureScreen` - Focused Note Capture)

### 6.1 Purpose & Primary Actions
A focused, single-purpose screen dedicated strictly to fast text note capture and saved note history (`CaptureMessageEntity`). In compliance with the anti-stacking mandate, all AI execution, token streaming, and bank draft reconciliation are decoupled and moved to the dedicated AI Chat screen.
- **Explicit Purpose:** Fast local note journaling on device.
- **Primary Action:** "Lưu ghi chú (Cục bộ)" — instant local write to device (offline, instant, non-blocking toast feedback).
- **Secondary Route:** "Thảo luận với Trợ lý AI ›" — explicit handoff pre-attaching the note to `AiChatScreen`.
- **Additional Actions:** Clear notes history, delete individual notes.

### 6.2 Layout & Wireframe Structure
```
+---------------------------------------------------+
| [<-] Ghi chép nhanh                        [Trash]| Top App Bar
+---------------------------------------------------+
|  +---------------------------------------------+  | Note Composer
|  | GHI CHÉP NHANH (LƯU CỤC BỘ)                 |  | (Focused Card)
|  | "Trưa nay ăn phở Thìn 65k, cafe 35k"        |  |
|  |                                             |  |
|  | Lưu trên thiết bị · Không cần mạng · Tức thì|  | Humane User Copy
|  |                  [✓ Lưu ghi chú (Cục bộ)]   |  | Primary Action (Toast)
|  +---------------------------------------------+  |
|                                                   |
|  +---------------------------------------------+  |
|  | ✨ Thảo luận với Trợ lý AI                > |  | Explicit AI Handoff
|  | Chuyển nội dung sang AI Chat để đối chiếu   |  | (Opens AiChatScreen)
|  +---------------------------------------------+  |
|                                                   |
|  GHI CHÚ ĐÃ LƯU CỤC BỘ                 2 ghi chú  |
|  +---------------------------------------------+  | Saved Local Notes
|  | "Trưa nay ăn phở Thìn 65k, cafe 35k"        |  |
|  | 13:00 · 09/11/2024      [Gửi vào AI >] [Xoá]|  |
|  +---------------------------------------------+  |
|  | "Mua xăng 80k cây xăng Petrolimex"           |  |
|  | 19:45 · 06/11/2024      [Gửi vào AI >] [Xoá]|  |
|  +---------------------------------------------+  |
|  +---------------------------------------------+  | Prototype Fixture Note
|  | ℹ️ Mô phỏng Web: State in-memory (Room entity) |  | (Truthful Boundary)
|  +---------------------------------------------+  |
|                                                   |
| [Trang chủ] [Sổ quỹ] [Ghi chép] [Trợ lý AI] [Duyệt tin] | 5-Tab Bottom Nav
+---------------------------------------------------+
```

---

## 7. Screen 7: Dedicated Streaming AI Chat (`AiChatScreen` - S10.1 Refinement)

### 7.1 Purpose & Primary Actions
The dedicated conversational AI assistant destination for financial analysis, expense interpretation, and workflow linking (`AgentConversationEntity`, `AgentRunEntity`, `ChatAgentRunState`).
- **Explicit Purpose:** Interactive conversational AI session with real-time response streaming and in-context draft linking.
- **Primary Action:** Send prompt / Stop streaming response.
- **Streaming States & Lifecycle:**
  * *Waiting (Pending):* `"⏳ Đang kết nối mô hình…"`
  * *Streaming (Running):* Incrementally appended visible response text with blinking caret `▍`.
  * *Tool-Working:* Distinct compact step indicator (`.tool-step-indicator`) with active pulse dot and search icon: `"CÔNG CỤ HỆ THỐNG: Đang đối chiếu biến động ngân hàng chưa khớp..."` (no raw reasoning or developer schemas exposed; non-stacked representation).
  * *Completed (Succeeded):* Complete response rendered, typing caret removed, in-context `LinkEvidenceCard` rendered.
  * *Stopped (Cancelled):* Immediate halt of chunking timer, partial response text preserved in transcript with `"⏹ Đã dừng phản hồi bởi người dùng"`, no late chunks.
  * *Error (Failed):* Inline retry banner with `"⚠️ Không thể hoàn thành phản hồi"`.
- **Ergonomics & Insets:** Auto-scroll follows stream when at bottom; pauses when user scrolls up with floating `"↓ Cuộn xuống mới nhất"` FAB (anchored at `bottom: 125px`, providing 21.7px safe clearance above composer; transcript bottom padding: 130px); anchored above keyboard (`WindowInsets.imePadding`).
- **Secondary Routes:**
  * Tap proposed link `[✓ Gắn kết (Attach)]` or `[✕ Từ chối (Reject)]`.
  * Tap History icon (`[History]`) to open past conversation turns bottom sheet.
  * Dismiss attached context chip (`[✕ Bỏ ngữ cảnh]`).

### 7.2 Layout & Wireframe Structure
```
+---------------------------------------------------+
| [<-] Trợ lý AI  [SYNTHETIC STREAM]   [Hist] [Trash]| Top App Bar
|      Mô hình: gpt-4o-mini · Trợ lý đối chiếu      |
+---------------------------------------------------+
| [ 📝 Ngữ cảnh: "Trưa nay ăn phở Thìn 65k..."  [x] ]| Context Banner
+---------------------------------------------------+
|                                                   | Scrollable Transcript
|                             +-------------------+ |
|                             | Trưa nay ăn phở   | | User Bubble
|                             | Thìn 65k, cafe 35k| | (Right aligned)
|                             |              12:35| |
|                             +-------------------+ |
|                                                   |
| +-----------------------------------------------+ | Assistant Bubble
| | [AI] Tôi đã tiếp nhận ghi chú của bạn:        | | (Left aligned)
| | • Khoản 1: Phở Thìn Bờ Hồ — 65.000 ₫ (Ăn uống)| |
| | • Khoản 2: Highlands Coffee — 35.000 ₫       | | Incremental Text
| | Đang đối chiếu với biến động ngân hàng... ▍   | | Blinking Caret
| |                                               | |
| | [● 🔍 CÔNG CỤ HỆ THỐNG: Đang đối chiếu...]     | | Compact Tool Step
| |                                               | | (Non-Stacked Row)
| |  +-----------------------------------------+  | |
| |  | [🔗] ĐỀ XUẤT LIÊN KẾT BIẾN ĐỘNG         |  | | LinkEvidenceCard
| |  | Vietcombank · - 150.000 ₫ · HIGHLANDS   |  | | (In-context)
| |  | Lý do: Khớp số tiền và mốc thời gian    |  | |
| |  | [✓ Gắn kết (Attach)]     [ Từ chối ]    |  | | Explicit Actions
| |  | ℹ️ Xác nhận vào sổ diễn ra tại Duyệt tin |  | | Boundary Note
| |  +-----------------------------------------+  | |
| +-----------------------------------------------+ |
|                                                   |
|                 [ Cuộn xuống mới nhất ↓ ]         | Floating Scroll FAB
+---------------------------------------------------+
| +---------------------------------------+ +-----+ |
| | Nhập câu hỏi về chi tiêu, biến động...| | [■] | | Composer (Stop/Send)
| +---------------------------------------+ +-----+ |
| [💡 Phân tích chi tiêu] [☕ Cafe Highlands]        | Quick Suggestions
| [Trang chủ] [Sổ quỹ] [Ghi chép] [Trợ lý AI] [Duyệt tin] | 5-Tab Bottom Nav
+---------------------------------------------------+
```

---

## 8. Screen 8: Bank Draft Review Inbox (`ReviewInboxScreen`)

### 8.1 Purpose & Primary Actions
The centralized review hub for incoming bank notifications captured by the Android notification listener.
- **Primary Actions:**
  * Filter drafts by urgency: "Cần xử lý" (Conflicts, possible duplicates, missing categories) vs "Sẵn sàng" (Ready to confirm).
  * Inspect digital receipt preview: Bank app icon, parsed amount, counterparty, timestamp, and duplicate alerts.
  * Tap draft card to open full `DraftDetailScreen`.
  * One-tap quick confirm or dismiss for ready drafts.

### 8.2 Layout & Wireframe Structure
```
+---------------------------------------------------+
| Duyệt biến động ngân hàng                  [Sắp xếp]| Top App Bar
+---------------------------------------------------+
| [ Cần xử lý (2) ]     [ Sẵn sàng duyệt (1) ]      | Segmented Queue
+---------------------------------------------------+
| CẦN XỬ LÝ XUNG ĐỘT HOẶC THIẾU DANH MỤC            |
|  +---------------------------------------------+  |
|  | [VCB Icon]  Vietcombank · 14:15 Hôm nay      |  |
|  |             - 150.000 ₫                     |  | Draft Card 1
|  | [!] Nghi ngờ trùng lặp với giao dịch #842   |  | Conflict Warning
|  | [?] Chưa chọn danh mục                      |  |
|  | [Xem chi tiết & Xử lý >]                    |  |
|  +---------------------------------------------+  |
|  +---------------------------------------------+  |
|  | [MB Icon]   MB Bank · 11:30 Hôm nay         |  |
|  |             - 45.000 ₫                      |  | Draft Card 2
|  | [🔗] Đang chờ xác nhận liên kết ghi chú      |  | Proposed Link
|  | [Xem chi tiết & Xử lý >]                    |  |
|  +---------------------------------------------+  |
|                                                   |
| SẴN SÀNG DUYỆT (Đã khớp đầy đủ)                   |
|  +---------------------------------------------+  |
|  | [TCB Icon]  Techcombank · 08:00 Hôm nay     |  |
|  |             + 5.000.000 ₫                   |  | Ready Draft Card
|  | Danh mục:   Thu nhập khác                   |  |
|  | [ XÁC NHẬN VÀO SỔ ]        [ BỎ QUA ]       |  | Immediate Actions
|  +---------------------------------------------+  |
+---------------------------------------------------+
```

---

## 9. Screen 9: Bank Draft Detail & Link Resolution (`DraftDetailScreen`)

### 9.1 Purpose & Primary Actions
Full digital receipt breakdown for one pending bank draft, allowing category assignment, duplicate inspection, note linking, and final financial confirmation.
- **Primary Actions:**
  * Inspect raw bank SMS/notification text side-by-side with parsed fields.
  * Select or change category swatch.
  * Inspect and accept/reject linked note events.
  * Confirm into confirmed ledger totals (`Xác nhận giao dịch`).
  * Dismiss without recording (`Bỏ qua biến động`).

### 9.2 Layout & Wireframe Structure
+---------------------------------------------------+
| [<-] Chi tiết biến động                    [Bỏ qua]| Top App Bar
+---------------------------------------------------+
|  +---------------------------------------------+  |
|  |             [ Bank Logo: VCB ]              |  | Digital Receipt
|  |                 VIETCOMBANK                 |  | Card
|  |                - 150.000 ₫                  |  |
|  |           14:15:22 - 09/11/2024             |  |
|  +---------------------------------------------+  |
|                                                   |
|  DANH MỤC ÁP DỤNG                                 |
|  [ Ăn uống v ]  (Chạm để đổi danh mục)            | Category Selector
|                                                   |
|  THÔNG TIN BỐI CẢNH LIÊN KẾT                      |
|  [✓] Đã liên kết với sự kiện: "Ăn trưa Highland"   | Linked Note
|      [ Huỷ gắn kết (Unlink) ]                     | Reversible Action
|                                                   |
|  NỘI DUNG THÔNG BÁO TỪ NGÂN HÀNG                  | Raw Notification
|  "TK 007100... SD: -150,000VND vao 14:15 09/11..."|
|                                                   |
|  +---------------------------------------------+  |
|  | [ XÁC NHẬN GHI VÀO SỔ GIAO DỊCH ] (Sticky)   |  | Primary CTA (Sticky Bar)
|  +---------------------------------------------+  |
```

---

## 10. Screen 10: Category & Automation Rules (`CategoryRulesScreen`)

### 10.1 Purpose & Primary Actions
Complete catalog of spending/income categories and automated categorization rules.
- **Primary Actions:**
  * Switch between Tabs: "Danh mục chi tiêu" (Categories) and "Quy tắc tự động" (Rules).
  * Filter categories by Active vs Archived.
  * Add new custom user category (`+`).
  * Rename, archive, or delete category (with safety checks against active records).
  * View and configure auto-assignment rules (e.g. *If merchant contains "Highlands" -> assign "Ăn uống"*).

### 10.2 Layout & Wireframe Structure
+---------------------------------------------------+
| [<-] Danh mục & Quy tắc                       [+] | Top App Bar
+---------------------------------------------------+
| [ Danh mục (Categories) ]    [ Quy tắc tự động ]  | Segmented Tabs
+---------------------------------------------------+
| DANH SÁCH DANH MỤC (Toàn quyền sửa/lưu trữ/xoá)   |
|  [Icon] Ăn uống (Food & Dining)       [Sửa] [Xoá] | Default Category
|  [Icon] Mua sắm (Shopping)            [Sửa] [Xoá] |
|  [Icon] Di chuyển (Transport)         [Sửa] [Xoá] |
|  [Icon] Thú cưng (Pet Care)           [Sửa] [Xoá] | Custom User Category
|  [Icon] Gym & Thể thao                [Sửa] [Xoá] |
| QUY TẮC NHỚ TỰ ĐỘNG                               |
|  "Grab*"          ──────► Di chuyển               | Automation Rules
|  "Circle K*"      ──────► Ăn uống                 |
|  "Shopee*"        ──────► Mua sắm                 |
+---------------------------------------------------+
```

---

## 11. Screen 11: Settings Hub (`SettingsScreen`)

### 11.1 Purpose & Primary Actions
Configuration of application behavior, external integrations, AI models, and privacy.
- **Primary Actions:**
  * Bank Notification Listener: enable/disable service, manage allowed bank apps.
  * Hosted AI Provider: select provider preset (OpenAI, Gemini, Ollama, Custom), model name, configure masked API key.
  * Privacy & Security: view local encryption vault status, data retention rules, clear cached traces.
  * View app version and backend status.

### 11.2 Layout & Wireframe Structure
+---------------------------------------------------+
| [<-] Cài đặt hệ thống                             | Top App Bar
+---------------------------------------------------+
| KẾT NỐI & THÔNG BÁO NGÂN HÀNG                     | Group 1
|  Dịch vụ lắng nghe thông báo            [Đang bật] |
|  Quyền hệ thống: Đã cấp quyền (Granted)            |
|  [Kiểm tra quyền hệ thống]  [Gửi tin thử nghiệm]  | Lifecycle Controls
|  Ứng dụng cho phép: Vietcombank, MB Bank, TCB     | Package Whitelist
|                                                   |
| TRÍ TUỆ NHÂN TẠO (MÔ HÌNH HOSTED)                 | Group 2
|  Nhà cung cấp: [ OpenAI (Official API)         v] | Provider Presets
|  Mô hình:      [ gpt-4o-mini                    ] | Model Name
|  Base URL:     [ https://api.openai.com/v1      ] | Endpoint URL
|  Khoá API:     [ sk-••••••••••••3a9f (Keystore) ] | Masked Vault Key
|  [ Kiểm tra kết nối AI ] (Status: 200 OK - 180ms)  | Test Ping
|                                                   |
| BẢO MẬT & HỆ THỐNG                                | Group 3
|  [Lock] Kho bảo mật dữ liệu Keystore Vault  [An toàn]
|  [Trash] Xoá bộ nhớ đệm phân tích AI              |
|  Phiên bản ứng dụng: Aegis v1.0.0 (Sprint 10)     |
+---------------------------------------------------+
```

---

## 12. Comprehensive State Matrix Across All 11 Screens

Every screen is designed with explicit, dedicated handling for all 8 required state families:

| Screen / Flow | Normal State | Empty State | Loading State | Error State | Offline State | Ambiguous State | Stale State | Cancelled State |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **1. HomeScreen** | Unstacked launcher, 8 squircles directly on surface, compact glance bar. | "Chưa có giao dịch tháng này. Hãy chọn tính năng bên dưới." | Shimmer skeletons on glance & squircle icons. | "Lỗi đọc dữ liệu số dư. [Thử lại]" with cached fallback. | Banner: "Chế độ ngoại tuyến · Dữ liệu cục bộ khả dụng." | Eye toggle active: `*** *** ₫` across all figures. | Data refresh indicator while reconciling. | Dismissed alert banner safely vanishes. |
| **2. AnalyticsScreen** | Month metrics, donut chart, category list, daily bars. | "Không có chi tiêu nào trong tháng đã chọn." | Circular spinner over chart container. | "Không thể tính toán thống kê. [Tải lại]" | Full functionality using local database records. | Net cashflow indicator neutral ($0\text{ ₫}$). | Stale month badge when date altered without reload. | Filter dismissal reverts to default month. |
| **3. LedgerScreen** | Paginated rows with status badges, dates, amounts. | "Không tìm thấy giao dịch phù hợp với bộ lọc." [Xoá bộ lọc] | Shimmer list rows with animated gradient. | "Lỗi tải sổ quỹ. Kiểm tra quyền truy cập lưu trữ." | Offline indicator; all local confirmed records browsable. | Search query with partial match highlights matching substring. | Page reload sentinel when background draft confirms. | Cancel search clears text and restores list. |
| **4. TransactionDetail** | Receipt card with key-value rows, edit/delete CTAs. | "Giao dịch không tồn tại hoặc đã bị xoá." [Quay lại] | Skeleton receipt card with pulsing boxes. | "Không thể đọc chi tiết giao dịch #ID." | Full detail loaded from local Room DB. | Conflicting source event indicated with amber badge. | Notice: "Bản ghi đã được chỉnh sửa tại phiên khác." | Deletion aborted retains record intact. |
| **5. ManualRecord** | Clean keypad/inputs, active category swatch, Save CTA. | Initial state: `0 ₫`, date defaults to current instant. | "Đang lưu giao dịch..." with disabled button. | Inline validation: "Vui lòng nhập số tiền lớn hơn 0 ₫." | Fully functional offline; saves directly to local SQLite. | Note event handoff prompt: "Chọn sự kiện ghi chú khớp." | Warning if source note event revision changed. | "Huỷ" button restores previous screen with zero financial changes. |
| **6. QuickCapture** | Focused note composer, local save CTA, truthful Room fixture notice, AI handoff. | "Chưa có ghi chú nào. Gõ nội dung chi tiêu bên trên." | Shimmer on note composer area. | "Lỗi lưu ghi chú vào cơ sở dữ liệu." | 100% functional offline (local write, zero jargon). | Long text handled cleanly with internal scroll. | Note revision change indicated before edit. | Discard note draft clears textarea cleanly. |
| **7. AiChatScreen** | Conversational message bubbles, streaming responses, Stop CTA, link cards. | "Hỏi Trợ lý AI về chi tiêu, đối chiếu hoá đơn..." | Waiting status: "Đang kết nối mô hình..." | "Không thể hoàn thành phản hồi. [Thử lại]" | Banner: "Ngoại tuyến — Trợ lý AI cần mạng. Hãy dùng Ghi chép cục bộ." Composer disabled. | Clarification prompt: "Bạn muốn đối chiếu với giao dịch nào?" | Pre-attached note modified: context marked `[Hết hiệu lực]`. | Stop button halts chunk timer immediately; preserves partial text in transcript. |
| **8. ReviewInbox** | Conflict-first list of bank draft receipt cards. | "Tuyệt vời! Không còn biến động nào cần duyệt." | Skeleton draft cards with pulsing headers. | "Lỗi đồng bộ biến động từ listener." | Drafts stored in local DB remain fully actionable. | Amber badge: "Nghi ngờ trùng lặp với giao dịch #ID". | Draft modified in background updates inline safely. | Dismiss draft moves draft to Dismissed list. |
| **9. DraftDetail** | Receipt breakdown, category picker, linked note card. | "Biến động đã được xác nhận hoặc đã huỷ bỏ." | Shimmer receipt modal loading bank text. | "Không thể mở biến động ngân hàng." | Works offline; confirms locally into confirmed ledger. | Ambiguous link: "Ghi chú khớp 2 biến động khác nhau." | Revision check: preview stale refuses confirm safely. | "Bỏ qua" removes from inbox without entering ledger. |
| **10. CategoryRules** | Categories list (user-mutable) and auto-rules list. | "Chưa có danh mục tự tạo nào. Chạm [+] để thêm." | Shimmer category rows. | "Lỗi cập nhật danh mục: Tên đã tồn tại." | Fully operable offline. | Name collision highlighted in red with suggestion. | Concurrent rule edit detected and warned. | Modal dismiss discards unsaved category name. |
| **11. SettingsScreen** | Sectioned settings list with toggles and status pills. | N/A (all settings have deterministic defaults). | Spinner while testing listener connection. | "Khoá API không hợp lệ hoặc hết hạn ngạch." | AI provider settings editable; connectivity test pauses. | Masked API key display: `sk-••••••••••••3a9f`. | Outdated config reloaded on screen focus. | Discard settings edits reverts to stored values. |
