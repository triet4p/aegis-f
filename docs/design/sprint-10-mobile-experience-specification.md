# Aegis Mobile Experience Redesign Specification (Sprint 10)

**Document Reference:** `docs/design/sprint-10-mobile-experience-specification.md`  
**Target Milestone:** Sprint 10 (S10.1 – S10.10)  
**Author / Designer:** S101Designer (Vision & UI/UX Specialist)  
**Status:** **Approved Architecture Candidate (Pending Owner Direction Approval)**  
**Associated Artifacts:**  
- Research Notes: `artifacts/sprint-10/research/mobile-app-benchmarks.md`  
- Capability Audit & Screen Map: `artifacts/sprint-10/design/current-to-proposed-audit.md`  
- Design System & Tokens: `artifacts/sprint-10/design/mobile-design-system.md`  
- Screen Wireframes & State Matrix: `artifacts/sprint-10/design/screen-specifications.md`  
- Backend Invariants & Codebase Mapping: `artifacts/sprint-10/design/backend-and-file-mapping.md`  
- Runnable Interactive Prototype: `artifacts/sprint-10/prototype/`  
- Visual Renders & Contact Sheet: `artifacts/sprint-10/renders/` (`gallery.html`)  

---

## 1. Problem Statement & Motivation

The original Aegis frontend architecture presented financial information as an endless vertical column of stacked functional cards on a single scrollable dashboard (`DashboardScreen.kt`), supplemented by an intermediate 3-button launcher screen (`ManualRoot`) and an overloaded multi-purpose screen (`QuickCaptureScreen.kt`). 

The product owner explicitly rejected this layout as unattractive, cluttered, and cognitively fatiguing, requiring a **complete UI/UX redesign**:
> *"Stacking functional cards is not an acceptable information architecture. Use clearly labelled feature icons that open focused screens, understandable navigation and task-specific flows."*
> 
> *Owner refinement & anti-stacking clarification:*
> *"Hạn chế việc stack các khối component trong cùng 1 màn hình. Cố gắng mỗi màn nên chỉ đảm nhiệm 1 nhiệm vụ nào đó xác định. Chỗ AI nên có màn hình để streaming chat."*

### 1.1 Anti-Stacking & Single-Task Ergonomic Principle
1. **One Explicit Purpose, One Primary Action per Screen:** Each screen is dedicated to a single clearly defined user goal with one prominent primary action (CTA). Secondary workflows, deep edits, or destructive operations are delegated to dedicated child screens or contextual sheets rather than stacked on the same page.
2. **Task Launcher Home vs. Monolithic Piled Feeds:** `HomeScreen` is primarily a quick task launcher and glanceable financial overview. It features an 8-item feature squircle grid (including a prominent *Trợ lý AI* launcher), eliminating stacked multi-item recent transaction feeds and intrusive warning blocks that compete for thumb attention.
3. **Decoupling Local Note Capture from AI Assistant:** Local note composition (`CaptureMessageEntity`) is decoupled from conversational AI execution (`AgentConversationEntity`, `AgentRunEntity`). Quick Capture is a local-only note journal (0 tokens, instant SQLite persistence). AI Chat is a dedicated screen with streaming token responses, tool-working feedback, in-context link resolution, and conversation history.
4. **Information Density within Same Task:** Cards are allowed strictly when presenting information serving the *same* task (e.g. digital receipt breakdown, KPI analytics comparison), never as an endless vertical stack of heterogeneous functional launchers.

The redesign benchmarks market-leading fintech applications (**VietinBank iPay Mobile 6.x**, **MoMo Super-App**, **Revolut**, **Monzo**) to establish a modern, humane, and task-focused mobile user experience while preserving 100% of the underlying backend capabilities, Room database invariants, and exact integer VND math.

---

## 2. Core Information Architecture & Navigation

The redesigned mobile experience is anchored by a **5-Tab Persistent Bottom Navigation Bar** and a **Hero Financial Overview with an 8-Item Labelled Feature Icon Grid**:

```
[ Root Navigation Shell ]
 ├── Tab 1: Trang chủ (Home) ───────────► Lightweight Single-Purpose Task Launcher
 │     ├── Top Bar: User Greeting ("Chào buổi sáng, Triết Phạm"), Avatar, Alert Bell with Badge
 │     ├── Lightweight Financial Glance Bar (48px): Period Spend with Privacy Eye Toggle and Reports Drill-Down
 │     └── Primary Feature Icon Grid (8 Labelled Squircles Sitting Directly on Surface):
 │           ├── [1] Sổ quỹ (Ledger) ───────► Opens Dedicated LedgerScreen
 │           ├── [2] Báo cáo (Analytics) ───► Opens Dedicated AnalyticsScreen
 │           ├── [3] Ghi chép (Notes) ──────► Opens Dedicated QuickCaptureScreen
 │           ├── [4] Trợ lý AI (AI Chat) ───► Opens Dedicated AiChatScreen (Featured Gradient Squircle)
 │           ├── [5] Duyệt tin (Review) ────► Opens Dedicated ReviewInboxScreen (with squircle badge)
 │           ├── [6] Nhập tay (Record) ─────► Opens Dedicated ManualRecordScreen
 │           ├── [7] Danh mục (Rules) ──────► Opens Dedicated CategoryRulesScreen
 │           └── [8] Cài đặt (Settings) ────► Opens Dedicated SettingsScreen
 │     (All stacked 543px cards, alert banners, and duplicate shortcuts eliminated)
 │
 ├── Tab 2: Sổ quỹ (Ledger) ───────────► Search, Filter Chips, Grouped Transactions Feed
 ├── Tab 3: Ghi chép (Quick Notes) ────► Focused Note Composer (SQLite, 0 tokens), Saved Journal, AI Handoff
 ├── Tab 4: Trợ lý AI (AI Chat) ───────► Conversational Streaming Assistant, Tool Feedback, Link Evidence, History
 └── Tab 5: Duyệt tin (Review Queue) ──► Conflict-First Bank Draft Inbox (Needs Decision vs Ready)

[ Secondary Deep Form & Receipt Screens (Bottom Nav Hidden, Sticky Bottom CTAs) ]
 ├── TransactionDetailScreen ──────────► Digital Receipt Breakdown, Edit/Delete Actions
 ├── ManualRecordScreen ───────────────► Segmented Type, Hero Keypad, Category Swatches, Sticky Save CTA
 ├── DraftDetailScreen ────────────────► Bank SMS Audit, Category Provenance, Link Context, Sticky Confirm CTA
 ├── CategoryRulesScreen ──────────────► User Category Catalog & Automation Rules
 └── SettingsScreen ───────────────────► Bank Listener Lifecycle, AI Multi-Profile Config, Keystore Vault
```

### 2.1 Screen Purpose, Primary Action, and Secondary Route Matrix

| Screen Name | Destination ID | Explicit Purpose | Primary Action (CTA) | Secondary Route / Sheet |
| :--- | :--- | :--- | :--- | :--- |
| **`HomeScreen`** | `screen-home` | True single-purpose task launcher & compact financial glance | Select feature squircle to launch task | Notification bell with unread badge |
| **`AnalyticsScreen`** | `screen-analytics` | Inspect monthly spending velocity and category proportions | Tap category row to filter ledger | Month switcher `< Tháng MM/YYYY >` |
| **`LedgerScreen`** | `screen-ledger` | Search and browse confirmed transaction records | Tap transaction row to inspect receipt | Filter chips (Chi tiêu, Thu nhập, Chuyển khoản) |
| **`TransactionDetailScreen`**| `screen-detail` | Inspect confirmed digital receipt & financial provenance | View verified transaction details | Edit transaction form, Delete confirmation sheet |
| **`ManualRecordScreen`** | `screen-record` | Manually record an un-notified cash/bank transaction | "Lưu vào sổ giao dịch" (Sticky CTA) | Note event handoff picker |
| **`QuickCaptureScreen`** | `screen-capture` | Write and store quick text notes locally on device | "Lưu ghi chú (Cục bộ)" (Lưu trên thiết bị, không cần mạng) | "Thảo luận với Trợ lý AI ›" handoff |
| **`AiChatScreen`** | `screen-chat` | Conversational streaming AI assistant for expense analysis | Send prompt / Stop streaming response | Proposed link Attach/Reject, History drawer |
| **`ReviewInboxScreen`** | `screen-review` | Triage unconfirmed incoming bank notifications | Tap draft card to inspect details | Segmented tabs (Cần xử lý vs Sẵn sàng) |
| **`DraftDetailScreen`** | `screen-draft-detail`| Reconcile bank SMS evidence, category and linked note | "Xác nhận ghi vào sổ" (Sticky CTA) | Change category, Unlink note, Dismiss draft |
| **`CategoryRulesScreen`** | `screen-categories` | Manage user-owned categories and automation rules | Add custom category / rule | Rename category, Archive/Delete category |
| **`SettingsScreen`** | `screen-settings` | Configure background services, AI models and security | Select setting section to configure | Test AI connection, Test intake, Language toggle |

## 3. High-Fidelity Screen Specifications

### 3.1 Screen 1: Home Dashboard (`HomeScreen` - Lightweight Task Launcher)
- **Lightweight Glance Bar (48px):** A sleek single-line glance bar replacing the former heavy 179px 3-column card. Displays the active period spend (`Chi tiêu tháng này: 14.850.000 ₫`) with one-tap **Privacy Eye Toggle** masking all figures to `*** *** ₫`, and a direct link to `AnalyticsScreen` / `Reports`. Detailed income and net cashflow breakdowns are placed strictly on `AnalyticsScreen`.
- **8-Item Feature Launchers on Surface:** High-contrast squircle containers ($54\times 54\text{dp}$, $16\text{dp}$ radius) sitting directly on the clean screen surface without a heavy enclosing card box:
  1. `Sổ quỹ` (Ledger)
  2. `Báo cáo` (Analytics)
  3. `Ghi chép` (Notes)
  4. `Trợ lý AI` (AI Chat) — Prominent featured launcher with vibrant gradient squircle and sparkles icon
  5. `Duyệt tin` (Review, with unread badge counter directly on squircle)
  6. `Nhập tay` (Record)
  7. `Danh mục` (Categories)
  8. `Cài đặt` (Settings)
- **Elimination of Stacked Cards & Duplication:** All 4 stacked component blocks (543px) from Round 1 are dismantled. The separate full-width alert warning card is removed; pending review counts are badged cleanly on the "Duyệt tin" launcher squircle and top bell. The redundant "Xem toàn bộ sổ giao dịch ›" card is removed. The duplicate Settings gear is purged from the top bar. Home completely embodies **one defined task: choose destination**.
### 3.2 Screen 2: Spending Analytics & Trends (`AnalyticsScreen`)
- **Time Scope:** Month switcher with chevrons `< Tháng 11/2024 >` and date range selector.
- **Side-by-Side Comparison:** Chi tiêu (Expense) vs Thu nhập (Income) KPI cards, followed by net cashflow indicator.
- **Proportional Category Bar:** Multi-segmented distribution bar visualizing category percentages, with clickable drill-down rows opening filtered ledger views.
- **Daily Spending Velocity:** Bar chart of daily spending trends across the active week.

### 3.3 Screen 3: All Transactions / Ledger (`LedgerScreen`)
- **Search & Filter:** Search field + filter chips (Tất cả, Chi tiêu, Thu nhập, Chuyển khoản).
- **Grouped List:** Paginated feed of transactions with category icons, merchant titles, timestamps, and formatted amounts.
- **Navigation:** Tapping any row opens the digital receipt `TransactionDetailScreen`.

### 3.4 Screen 4: Transaction Detail & Receipt (`TransactionDetailScreen`)
- **Digital Receipt:** Merchant avatar, large amount, confirmation status badge (`✓ Đã xác nhận`).
- **Provenance Rows:** Category, timestamp, funding source account, notes, and transaction ID.
- **Sticky Bottom Bar:** Anchored primary "Chỉnh sửa giao dịch" and destructive "Xoá giao dịch" actions. Bottom navigation bar is hidden on this deep screen.

### 3.5 Screen 5: Manual Record Form (`ManualRecordScreen`)
- **Segmented Type Selector:** Chi tiêu (Expense), Thu nhập (Income), Chuyển khoản (Transfer).
- **Hero Amount Input:** Centered large number input formatted with VND thousand separators and written-out words subtitle (e.g. *"Một trăm năm mươi nghìn đồng"*).
- **Category Swatch Grid:** $4\times 2$ quick picker grid of category icons.
- **Ergonomics & Sticky CTA:** Adapts to software keyboard IME with `imePadding()`; sticky "Lưu vào sổ giao dịch" CTA floats above the Android gesture bar. Bottom navigation is hidden.

### 3.6 Screen 6: Quick Note Journal (`QuickCaptureScreen` - Focused Local Note Capture)
- **Decoupled Single Purpose:** Dedicated strictly to quick text note capture and saved note history (`CaptureMessageEntity`). Zero AI streaming, model running, or draft linking on this screen.
- **Local Note Composer:** Clean text area with placeholder (*"Trưa nay ăn phở Thìn 65k..."*).
- **Humane Copy (Zero Developer Jargon):** User-facing text reads `"Lưu trên thiết bị · Không cần mạng · Tức thì"` / `"Saved to device · Offline · Instant"`. Zero internal database names ("SQLite") or token counters ("0 token") leak to the user.
- **Primary CTA & Feedback:** "Lưu ghi chú (Cục bộ)" — instant local write triggering a non-blocking in-page toast notification.
- **Truthful Prototype Notice:** Explicit footer notice clarifies that the web prototype uses synthetic in-memory state simulating the target Android Room architecture (`CaptureMessageEntity`), distinguishing prototype fixtures from production native Room persistence.
- **Explicit Handoff to AI Assistant:** Clear gradient action banner (*"✨ Thảo luận với Trợ lý AI — Chuyển nội dung sang màn hình AI Chat để đối chiếu với biến động ngân hàng ›"*). Tapping pre-attaches the note text as an explicit context chip on `AiChatScreen`.
- **Saved Notes List:** Chronological feed of locally saved notes with timestamps and delete actions.
### 3.7 Screen 7: Dedicated Streaming AI Chat (`AiChatScreen` - S10.1 Refinement)
- **Dedicated Destination:** Full-featured conversational AI assistant screen for expense analysis, multi-span note parsing, and draft linking (`AgentConversationEntity`, `AgentRunEntity`, `ChatAgentRunState`).
- **Top App Bar:** Back button, localized title (*"Trợ lý AI"* / *"AI Assistant"*), model preset indicator (*"Mô hình: gpt-4o-mini"*), signature synthetic demo badge (`🔶 SYNTHETIC STREAM`), conversation history shortcut, and clear chat action.
- **Explicit Context Submission:** When navigated from Quick Capture or Draft Detail, an explicit dismissible context chip is anchored above the transcript:
  `[📝 Ngữ cảnh đính kèm: "Trưa nay ăn phở Thìn 65k..."  ✕ Bỏ]`
  Any context egressed to the model is 100% visible and user-controllable.
- **Message Transcript & Incremental Response Streaming:**
  * **User Message Bubbles:** Right-aligned bubble with brand primary accent and user avatar. Displays attached context snippet when present.
  * **Assistant Message Bubbles:** Left-aligned card bubble with AI sparkle avatar. Supports formatted markdown (bolding, lists, amounts).
  * **Incremental Visible Streaming:** While generating, text is appended chunk-by-chunk via local timer (simulating provider SSE / chunk decoders). Features a pulsing/blinking typing cursor `▍`.
  * **Explicit State Indicators:**
    1. *Waiting (Pending):* `"⏳ Đang kết nối mô hình…"` (initial provider connection).
    2. *Streaming (Running):* `"⚡ Đang tạo câu trả lời…"` (token accumulation in progress).
    3. *Tool-Working:* Distinct, compact labelled status row / step indicator (`.tool-step-indicator`) with active pulse dot, search icon, and clear task description (`"CÔNG CỤ HỆ THỐNG: Đang đối chiếu biến động ngân hàng chưa khớp..."`). Avoids adding an unnecessary stacked card, in accordance with owner single-task ergonomics; no raw tool args, tokens, or hidden reasoning exposed.
    4. *Completed (Succeeded):* `"✓ Hoàn thành"` (generation complete).
    5. *Stopped (Cancelled):* `"⏹ Đã dừng phản hồi bởi người dùng (giữ nguyên nội dung)"`.
    6. *Error (Failed):* `"⚠️ Không thể hoàn thành phản hồi"` with explicit `[Thử lại]` retry CTA.
- **Composer / Send / Stop Behavior:**
  * While idle, the composer features an expanding text input and primary blue `[Gửi / Send]` button.
  * While waiting, streaming, or tool-working, the button transforms into a prominent red square **`[Dừng / Stop]`** button.
  * **Stop Invariant:** Tapping Stop halts the chunk timer immediately. Displays the text received so far with `[Đã dừng phản hồi]`. Zero late chunks leak after stop. Already verified effects remain intact. The composer immediately restores to Send mode for the next message.
- **Scrolling Ergonomics, Insets & Geometry:**
  * Auto-scroll follows stream when the user is scrolled to the bottom.
  * If the user scrolls up to inspect previous turns, auto-scroll is paused to prevent disorienting jumps.
  * Floating `[↓ Cuộn xuống mới nhất]` FAB is anchored at `bottom: 125px`, providing a verified **21.7px safe clearance** above the sticky composer bar (`height: 98px-110px`), completely eliminating touch target collisions.
  * Transcript bottom padding is set to `130px`, ensuring that the bottom message, active stream text, and blinking typing caret `▍` are never obscured or clipped behind the sticky composer.
  * Anchored above the software keyboard (`WindowInsets.imePadding`).
- **In-Context Action Cards & Financial Boundary:**
  * When the AI proposes a link, a `LinkEvidenceCard` renders directly within the conversation stream:
    - Bank draft counterparty, formatted VND amount, timestamp.
    - Match rationale (*"Khớp số tiền 150.000 ₫ và mốc thời gian 14:15 với ghi chú"*).
    - Explicit actions: `[✓ Gắn kết (Attach)]` and `[✕ Từ chối (Reject)]`.
    - Truthful boundary notice: *"Xác nhận tài chính vào sổ quỹ chỉ diễn ra khi bạn nhấn Xác nhận tại màn hình Duyệt biến động"*. The AI assistant cannot autonomously execute financial transactions or alter ledger balances.
- **Conversation History Design (Human-Readable & Functional):**
  * Top bar history button opens a bottom sheet listing past conversation turns with human-readable session titles (e.g. *"Ăn trưa phở Thìn & cafe"*, *"Tìm giao dịch siêu thị WinMart"*), relative timestamps (*"Hôm nay, 12:35"*, *"Hôm qua, 18:40"*), and localized status badges (*"Hoàn thành"*, *"Đã dừng"*). Zero raw database run IDs (`Turn #run-...`) or unformatted backend enums (`SUCCEEDED`/`CANCELLED`).
  * Tapping a past session actually loads its complete message transcript into the active screen and closes the drawer, eliminating browser alert modals.
  * Aligns durable memory (`AgentConversationEntity`, `AgentRunEntity`) with transient state (`ChatAgentRunState`).
- **Offline & No-Key Handling:**
  * In offline mode: persistent warning banner (`"📡 Ngoại tuyến — Trợ lý AI cần mạng. Hãy dùng Ghi chép cục bộ"`), and Send CTA is safely disabled.
  * When API key is unconfigured: banner guides user to Settings Hub (`"🔑 Chưa cấu hình khóa API — Vào Cài đặt AI để thiết lập khóa"`).

### 3.8 Screen 8: Bank Draft Review Inbox (`ReviewInboxScreen`)
- **Conflict-First Ordering:** Segmented tabs for "Cần xử lý" (conflicts, missing categories, possible duplicates) vs "Sẵn sàng duyệt" (ready for confirm).
- **Draft Cards:** Bank logo, parsed amount, relative timestamp, merchant counterparty, and prominent warning badge for conflicts.

### 3.9 Screen 9: Draft Detail & Link Resolution (`DraftDetailScreen`)
- **Receipt Breakdown:** Raw bank SMS audit box side-by-side with parsed fields. Clean, user-friendly copy (no internal enum strings or "(AUDIT)" labels).
- **Category Selector:** Dropdown/sheet to assign or correct category, displaying suggestion provenance (`ReviewCategoryOrigin`: User rule vs Hosted classifier).
- **Linked Context:** Displays linked note event with "Huỷ gắn (Unlink)" action. Contextual button to ask AI Assistant about this draft.
- **Sticky Bottom CTA:** Anchored "Xác nhận ghi vào sổ giao dịch" button. Bottom navigation is hidden.

### 3.10 Screen 10: Categories & Automation Rules (`CategoryRulesScreen`)
- **Catalog Tabs:** Danh mục (Categories) vs Quy tắc tự động (Rules).
- **Full User Authority:** Every category in the catalog is user-owned and can be renamed, archived, or deleted (subject to referential integrity). Default categories are pre-seeded convenience, never locked.
- **Auto-Rules:** Pattern matching list (e.g., `HIGHLANDS*` -> `Ăn uống`).

### 3.11 Screen 11: Settings Hub (`SettingsScreen`)
- **Bank Listener Lifecycle:** Toggle listener service, view permission grant status, check system intent, send simulated test intake, and manage allowed bank application package whitelist.
- **Multi-Profile AI Configuration:** Provider preset selector (OpenAI, Gemini, Ollama, Custom), model name, endpoint URL, masked secret API key input, reasoning effort, parallel tools toggle, and test connection button.
- **Privacy & Vault:** Local encryption status, clear AI cache, and data retention guidelines.
- **Language Support:** Bilingual parity (English-first baseline with full Vietnamese localization).

## 4. State Matrix: 8 State Families Across All Screens

Every screen explicitly accommodates the 8 core state families:
1. **Normal:** Full data loaded, rich interactive elements.
2. **Empty:** Contextual illustration, clear explanation, and primary action to create data.
3. **Loading:** Animated shimmer skeletons matching component dimensions.
4. **Error:** Inline error banner with descriptive cause and explicit [Thử lại] retry button.
5. **Offline:** Persistent status banner indicating local database functionality remains active.
6. **Ambiguous:** Visual warning badge for duplicate transactions or competing link candidates.
7. **Stale:** Reload sentinel when background reconciliation modifies visible data.
8. **Cancelled:** Clean exit returning to previous screen with zero financial changes.

---

## 5. Architectural & Sprint Cutover Plan

| Sprint Task | Target Scope | Key Source Code Changes |
| :--- | :--- | :--- |
| **S10.2** | Navigation shell & Design System primitives | Refactor `AppShell.kt` into 5-tab navigation, build `AegisFeatureIcon`, theme tokens in Compose. |
| **S10.3** | Home dashboard, analytics & ledger browsing | Implement `HomeScreen`, `AnalyticsScreen`, and `LedgerScreen`. Retire monolithic `DashboardScreen.kt`. |
| **S10.4** | Quick capture & note management | Implement focused `QuickCaptureScreen.kt` for local note journaling (SQLite, 0 tokens), saved notes list, and explicit AI handoff. |
| **S10.5** | Hosted Chat & streaming interaction | Implement dedicated `HostedChatScreen.kt` (`AiChatScreen`) with incremental response streaming, visible state indicators, Stop button, in-context `LinkEvidenceCard`, and conversation history. |
| **S10.6** | Bank draft review & confirmation | Implement `ReviewInboxScreen` and `DraftDetailScreen` with digital receipt layout. |
| **S10.7** | Manual entry & record correction | Implement `ManualRecordScreen` with hero amount input and category swatch grid. |
| **S10.8** | Category, rule & settings destinations | Implement unified `CategoryRulesScreen` and `SettingsScreen`. |
| **S10.9** | Phone verification & refinement | Run on physical Android device with synthetic data, verify insets, keyboard, and Dynamic Type. |
| **S10.10** | Acceptance package & docs | Differential review, user instructions, and final sprint acceptance handoff. |
