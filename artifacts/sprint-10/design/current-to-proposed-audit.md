# Current-to-Proposed Frontend Capability & Screen Audit

**Task ID:** S10.1  
**Attempt:** S10.1-design-a1  
**Date:** 2026-11-09  
**Designer:** S101Designer  

---

## 1. Usability Baseline Audit (Current Frontend)

The current Aegis frontend was implemented as a 4-destination application shell (`AppShell.kt`) with four primary tabs: **Dashboard**, **Chat**, **Review**, and **Manual**. While functionally robust and backed by exact query/command services, the visual and navigational architecture suffers from severe structural usability defects:

| Current Surface | Current Architecture & Implementation | Usability Problems & Deficiencies |
| :--- | :--- | :--- |
| **Dashboard** (`DashboardScreen.kt`) | Monolithic vertical column containing `DashboardScopeCard`, `SpendingCard`, `CategoryBreakdownCard`, `SpendingOverTimeCard`, and `CoverageCard`. | **Card Stacking Fatigue:** Over 1,300 lines of vertically stacked rectangular panels with minimal visual breathing room. Users must scroll through scope controls, multiple KPI cards, category breakdown progress bars, and daily charts just to see basic financial state. Lack of immediate task shortcuts. |
| **Manual Hub** (`AppShell.kt#ManualRoot`) | Root tab displaying three large outlined buttons: "RECORD A TRANSACTION", "BROWSE ALL TRANSACTIONS", "MANAGE CATEGORIES". | **Intermediate Navigation Trap:** Wastes an entire primary tab bar slot on an intermediate launchpad menu. Tapping "Manual" does not allow quick entry; it requires an extra tap to pick between entering, browsing, or managing categories. |
| **Chat / Quick Capture** (`QuickCaptureScreen.kt`, `ChatOutcomeCards.kt`) | A single scrollable screen mixing note composition text field, "Save note" button, "Run hosted workflow" button, full note history list, event breakdown cards, link candidate popups, and conversational agent traces. | **Cognitive Overload & Mixed Paradigms:** Conflates local quick note taking with cloud/hosted agent execution. Users cannot easily tell whether typing a note simply stores text or triggers an external AI API call. Link reconciliation cards are buried inside note event cards. |
| **Review Inbox** (`BankDraftReviewScreen.kt`) | Grouped list of bank drafts with status headers, raw bank SMS text, and candidate match cards. | **Technical Clutter:** Bank drafts look like raw database entries rather than clean transaction receipts. Duplicate warnings and conflict states are difficult to parse quickly on mobile. |
| **Navigation & Secondary Stacks** (`AppShell.kt`) | Internal `List<DashboardRoute>`, `List<ReviewRoute>`, `List<ManualRoute>` stacks managed inside composables. | **Inconsistent Back Behavior:** Navigating deeper into secondary routes (e.g. from Dashboard to ScopedList to RecordDetail to RecordEdit) relies on custom back handlers and lacks unified breadcrumb / header context. |
| **Aesthetics & Design Tokens** (`MainActivity.kt#AegisShapes`) | 2dp–4dp corner radius across all surfaces, flat hairline outlines (`0xFF232D3F` / `0xFFD1D9E0`), monotone styling. | **Industrial / Debug Tool Appearance:** Feels like an internal engineering console rather than a friendly, polished personal finance application. Lacks vibrant status accents, cohesive iconography, and modern touch ergonomics. |

---

## 2. Complete Capability Mapping (Current to Proposed)

Every user-facing capability supported in the Aegis backend and existing screens is mapped below to its new proposed destination. **No capability is dropped, hidden, or compromised.**

| Capability / Flow | Current Location & Composable | Proposed Destination & Screen | Entry Point / Navigation Path |
| :--- | :--- | :--- | :--- |
| **Overall Balance & Scope Overview** | `DashboardScreen.kt#SpendingCard` | `HomeScreen` (Hero Financial Card) | Root tab: **Trang chủ (Home)** |
| **Financial Privacy Masking** | None (amounts always visible) | `HomeScreen` (Eye Toggle icon) | Tap eye icon on Hero Financial Card |
| **High-Frequency Quick Actions** | Stacked inside various screens | `HomeScreen` (Quick Actions Bar) | 4 primary buttons: Ghi chép, Nhập tay, Duyệt tin, Báo cáo |
| **Detailed Spending Analytics & Trends** | `DashboardScreen.kt#SpendingOverTimeCard`, `CategoryBreakdownCard` | `AnalyticsScreen` (Dedicated screen) | Feature icon on Home: **Báo cáo chi tiêu** |
| **Date Range & Month Filtering** | `DashboardScreen.kt#DashboardScopeCard` (Dialogs) | Integrated into `AnalyticsScreen` & `LedgerScreen` | Month switcher `< Tháng MM/YYYY >` + custom range chip |
| **Category Spending Breakdown** | `DashboardScreen.kt#CategoryBreakdownCard` | `AnalyticsScreen` (Donut Chart & Category List) | Tap category row in `AnalyticsScreen` to drill down |
| **Browse All Transactions (Ledger)** | `TransactionListScreen.kt` (via `ManualRoot`) | `LedgerScreen` (Dedicated Root Tab) | Root tab: **Sổ giao dịch (Ledger)** & Home feature icon |
| **Filter Ledger by Type & Category** | `TransactionListScreen.kt#FilterPanel` | `LedgerScreen` (Horizontal Filter Chips & Filter Drawer) | Sticky filter bar on `LedgerScreen` |
| **Transaction Record Detail** | `TransactionDetailScreen.kt` | `TransactionDetailScreen` (Receipt Modal / Screen) | Tap any row in `LedgerScreen` or `AnalyticsScreen` |
| **Edit Confirmed Transaction** | `TransactionEditorScreen.kt#TransactionFormScreen` | `TransactionFormScreen` (Focused Editor Screen) | Tap "Chỉnh sửa" on `TransactionDetailScreen` |
| **Delete Confirmed Transaction** | `TransactionDetailScreen.kt#DeleteConfirmationDialog` | `TransactionDetailScreen` (Destructive action sheet) | Tap "Xoá giao dịch" on `TransactionDetailScreen` |
| **Manual Transaction Entry** | `TransactionEditorScreen.kt` (via `ManualRoot`) | `ManualRecordScreen` (Dedicated quick entry) | Quick action bar on Home & Home feature icon **Nhập giao dịch** |
| **Note Event to Manual Record Handoff** | `TransactionEditorScreen.kt#NoteEventPickerDialog` | `ManualRecordScreen` (Source Event Banner / Picker) | "Nhập từ sự kiện ghi chú" in `ManualRecordScreen` or tap event card |
| **Quick Note Capture (Local Device Journal)** | `QuickCaptureScreen.kt#CapturePanel` | `QuickCaptureScreen` (Focused Note Journal) | Root tab: **Ghi chép (Notes)** & Home feature icon #3 |
| **Hosted AI Agent Execution & Streaming** | `QuickCaptureScreen.kt#RunHostedWorkflow` | `AiChatScreen` (Dedicated Streaming Chat) | Root tab: **Trợ lý AI (AI Chat)** & Home feature icon #4 |
| **Note Event Multi-Span Extraction** | `QuickCaptureEvents.kt#QuickCaptureEventCard` | `AiChatScreen` & `QuickCaptureScreen` | Conversational stream in `AiChatScreen` |
| **Edit Note Event Interpretation** | `QuickCaptureEvents.kt#InterpretationEditorDialog` | `InterpretationEditDialog` / Sheet | Tap "Sửa diễn giải" on event card |
| **Link Candidate Browser & Attachment** | `QuickCaptureEvents.kt#CandidateDraftBrowserDialog` | `LinkResolutionSheet` (Dedicated linking sheet) | Tap "Liên kết với biến động ngân hàng" |
| **Reversible Link Actions (Attach/Reject/Unlink)** | `ChatOutcomeCards.kt#ChatLinkCard` | `LinkEvidenceCard` (Inline in `AiChatScreen` & `DraftDetailScreen`) | Action buttons: Gắn kết (Attach), Từ chối (Reject), Huỷ gắn (Unlink) |
| **Agent Clarification & Scope Follow-up** | `ChatOutcomeCards.kt#ChatFollowUpRow` | `AiChatScreen` (Clarification Action Banner) | Conversational stream in `AiChatScreen` |
| **Bank Draft Review Queue** | `BankDraftReviewScreen.kt#InboxContent` | `ReviewInboxScreen` (Dedicated Root Tab) | Root tab: **Duyệt tin (Review)** with live badge counter |
| **Conflict-First Draft Ordering** | `BankDraftReviewScreen.kt#QueueSectionHeader` | `ReviewInboxScreen` (Segmented Tabs / Grouping) | Tabs: Cần xử lý (Conflict/Missing), Sẵn sàng (Ready) |
| **Draft Detail & Receipt Preview** | `BankDraftReviewScreen.kt#DraftDetailContent` | `DraftDetailScreen` (Dedicated receipt view) | Tap draft card in `ReviewInboxScreen` |
| **Draft Category Assignment** | `BankDraftReviewScreen.kt#CategoryChoice` | `DraftDetailScreen` (Interactive Category Swatch Picker) | Tap category badge on `DraftDetailScreen` |
| **Explicit Confirm / Dismiss Draft** | `BankDraftReviewScreen.kt#ConfirmDismiss` | `DraftDetailScreen` (Anchored Bottom Action Bar) | Primary: "Xác nhận vào sổ", Secondary: "Bỏ qua" |
| **Duplicate Warning & Resolution** | `BankDraftReviewScreen.kt#DuplicateWarning` | `DraftDetailScreen` (Prominent Amber Warning Card) | Tap "Xem bản ghi trùng lặp" |
| **Category Management (User vs Hosted)** | `CategoryManagerScreen.kt` | `CategoryRulesScreen` (Tab 1: Danh mục) | Feature icon on Home: **Danh mục & Quy tắc** |
| **Add / Rename / Archive / Delete Category** | `CategoryManagerScreen.kt#CategoryNameDialog` etc. | `CategoryEditDialog` & Action Sheets | Floating action button & item menu in `CategoryRulesScreen` |
| **Remembered Category Rules** | Backend DAO only (no dedicated UI) | `CategoryRulesScreen` (Tab 2: Quy tắc tự động) | Tab 2 in `CategoryRulesScreen` |
| **Bank Notification Listener Setup** | `BankNotificationSettingsScreen.kt` | `BankNotificationSettingsScreen` | Feature icon on Home: **Thông báo ngân hàng** |
| **Allowed Bank Packages Management** | `BankNotificationSettingsScreen.kt#AllowedPackageRow` | `BankNotificationSettingsScreen` (App Whitelist) | Add/remove bank apps in notification settings |
| **Hosted AI Provider Presets & Models** | `HostedProviderSettingsScreen.kt` | `HostedProviderSettingsScreen` | Settings Hub -> **Cấu hình mô hình AI** |
| **API Key Storage & Endpoint Masking** | `HostedProviderSettingsScreen.kt` | `HostedProviderSettingsScreen` (Masked input) | Settings Hub -> **Cấu hình mô hình AI** |
| **Privacy, Vault & Retention Disclosures** | Scattered in disclaimers | `PrivacyDisclosureScreen` | Settings Hub -> **Bảo mật & Quyền riêng tư** |
| **Settings Hub (Central Configuration)** | `AppShell.kt#SettingsRoot` | `SettingsScreen` (Dedicated Root Tab) | Root tab: **Cài đặt (Settings)** & Top Bar gear icon |

---

## 3. High-Level Navigation Hierarchy

The redesigned application is structured around a **5-Tab Bottom Navigation Bar** providing anchor points for everyday tasks, complemented by a **Grid of Labelled Feature Icons** on the Home screen for immediate access to dedicated workflows:

```
[ Application Root Shell ]
   ├── [ Bottom Tab 1: Trang chủ (Home) ]
   │      ├── Top Bar: User Avatar, Greeting, Single Notification Alert Bell with Badge
   │      ├── Lightweight Financial Glance Bar (48px): Period Spend with Privacy Mask Toggle & Reports Drill-down
   │      └── Task Launcher Icon Grid (8 Primary Squircles Directly on Screen Surface):
   │             ├── [1] Sổ quỹ (Ledger) ──────────────► Opens Dedicated LedgerScreen
   │             ├── [2] Báo cáo (Analytics) ──────────► Opens Dedicated AnalyticsScreen
   │             ├── [3] Ghi chép (Notes) ─────────────► Opens Dedicated QuickCaptureScreen
   │             ├── [4] Trợ lý AI (AI Chat) ──────────► Opens Dedicated AiChatScreen (Featured Gradient Squircle)
   │             ├── [5] Duyệt tin (Review) ───────────► Opens Dedicated ReviewInboxScreen (with squircle badge)
   │             ├── [6] Nhập tay (Record) ────────────► Opens Dedicated ManualRecordScreen
   │             ├── [7] Danh mục (Rules) ─────────────► Opens Dedicated CategoryRulesScreen
   │             └── [8] Cài đặt (Settings) ───────────► Opens Dedicated SettingsScreen
   │      (All 4-card 543px stacked boxes, alert banners, and duplicate shortcuts eliminated)
   ├── [ Bottom Tab 2: Sổ giao dịch (Ledger) ]
   │      ├── Top Bar: Search, Date Filter, Category Filter, Export
   │      ├── Transaction List with Infinite/Paginated Scroll
   │      └── Tap Transaction Row ─────────────────────► Opens TransactionDetailScreen
   │                                                         └── Tap "Sửa" ──► TransactionFormScreen
   │
   ├── [ Bottom Tab 3: Ghi chép nhanh (Quick Notes) ]
   │      ├── Top Bar: Trash / Clear Notes Action
   │      ├── Note Composer (Local-only instant save on device, zero token jargon, toast feedback)
   │      ├── Truthful Prototype Disclaimer Notice (Web in-memory fixture vs Android Room persistence)
   │      ├── Explicit AI Assistant Handoff Banner ────► Opens AiChatScreen with Note Context
   │      └── Chronological Saved Local Notes List ────► Delete / Send to AI Actions
   │
   ├── [ Bottom Tab 4: Trợ lý AI (Dedicated AI Chat) ]
   │      ├── Top Bar: Model Preset Indicator, SYNTHETIC STREAM Badge, History Drawer, Clear
   │      ├── Explicit Attached Context Chip (if opened from Note or Draft)
   │      ├── Message Transcript with Incremental Token Streaming & Caret ▍
   │      ├── Live State Indicators (Waiting -> Streaming -> Tool-Working Step Indicator -> Completed / Stopped / Error)
   │      ├── In-Context LinkEvidenceCard with Reversible Attach/Reject Actions
   │      ├── Floating Scroll-to-Bottom FAB (21.7px safe vertical clearance above composer)
   │      ├── Human-Readable History Drawer (session titles, relative times, friendly status, direct transcript loading)
   │      └── Persistent Composer with Send / Stop Toggle Button
   │
   └── [ Bottom Tab 5: Duyệt tin (Review) ] (With Unread Badge Counter)
          ├── Top Bar: Order Toggle (Cần xử lý trước vs Thời gian)
          ├── Filter Tabs: [ Cần quyết định ] [ Sẵn sàng ]
          └── Draft Receipt Cards ─────────────────────► Opens DraftDetailScreen
                                                           ├── Confirm Record
                                                           ├── Dismiss Draft
                                                           └── Link / Unlink Note Events
```
