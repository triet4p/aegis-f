# Backend Invariants, File Mapping & Accessibility Specification

**Task ID:** S10.1  
**Attempt:** S10.1-design-a1  
**Date:** 2026-11-09  
**Designer:** S101Designer  

---

## 1. Preserved Backend Invariants & Architectural Contracts

The complete UI/UX redesign strictly preserves all underlying backend contracts, domain invariants, and query/command services. The redesign changes presentation and task navigation; it **never modifies financial rules, data schemas, or mathematical guarantees**.

### 1.1 Exact VND Math & Currency Semantics
- **Rule:** All financial computations use exact integer arithmetic in Vietnamese Đồng (`VndMoney`, stored as `Long` cents/VND, non-negative).
- **Formatting:** Thousands separated by standard dot or comma (e.g. `150.000 ₫`), strictly suffixed by `₫`. Floating-point currency calculations are forbidden.
- **Classification:** Three mutually exclusive transaction types:
  * `EXPENSE`: Confirmed spending. Deducts from net balance.
  * `INCOME`: Confirmed inflow. Adds to net balance.
  * `INTERNAL_TRANSFER`: Confirmed movement between accounts. Net cashflow impact is zero; tracked separately and never conflated with income or expenses.

### 1.2 The Ledger Confirmation Boundary
- **Rule:** Unconfirmed bank drafts (`PendingBankDraftEntity`), unrecorded note messages (`CaptureMessageEntity`), and parsed note events (`NoteEventEntity`) **never enter ledger totals or confirmed spending aggregates**.
- Only an explicit confirmation by the user (`WorkflowCommandService.confirmBankDraft`, `WorkflowCommandService.recordManualTransaction`) writes an authoritative `TransactionEntity` with `ConfirmationStatus.CONFIRMED`.
- Dismissed drafts (`DISMISSED`) and deleted notes (`DELETED` tombstone) leave permanent audit traces without altering confirmed history.

### 1.3 Category Governance & Provenance Semantics
- **All Categories Are User-Mutable:** In the Aegis backend (`CategoryEntity`, `WorkflowCommandService`), every category in the catalog is user-owned. Users have full authority to rename, archive, or delete any category (subject to referential safety checks). Default categories are merely pre-seeded for initial convenience; they are **never locked or immutable**.
- **Suggestion Origin vs. Catalog Mutability:** The distinction `ReviewCategoryOrigin` (`USER` vs `HOSTED`) applies strictly to **draft suggestion provenance** on incoming bank notifications:
  * `USER`: The category was assigned or suggested based on the user's remembered automation rules (`RememberedCategoryRuleEntity`).
  * `HOSTED`: The category was suggested by the hosted AI classifier model (`ClassificationSnapshotEntity`).
- **Archival Invariant:** Archiving a category removes it from new manual selection pickers, while preserving historical referential integrity; confirmed records filed under an archived category remain discoverable, editable, and counted in aggregates.
- **Deletion Safety:** A category can be deleted only when zero stored transactions and zero active rules reference it.
### 1.4 Reversible Context Linking & Audit Provenance
- Links between note events and bank drafts (`ContextLinkEntity`) support explicit lifecycle states:
  * `PROPOSED`: AI agent or heuristic suggested a match; requires user review.
  * `ATTACHED`: User confirmed the association.
  * `REJECTED`: User refused the match.
  * `UNLINKED`: User detached a previously attached link.
- Every link operation is reversible and carries complete revision tracking (`revision`, `modifiedAtMillis`).

### 1.5 Notification Privacy & Vault Boundary
- Bank notifications are intercepted locally by `BankNotificationListener` on-device only.
- Raw notification text is processed locally and stored in encrypted app storage.
- When sending prompts to hosted AI models (via `MaskedHostedGateway`), personal sensitive information (account numbers, names, phone numbers) is stripped or masked according to `SensitiveVault` policies.

### 1.6 Conversational History & Streaming Lifecycle Contracts
- **Durable vs. Transient Boundaries:**
  * **Durable Memory (`AgentConversationEntity`, `AgentRunEntity`):** Persisted in SQLite via Room. Stores the sequence of user-visible turns: the user's request words (`USER`), lifecycle status lines (`STATUS`), and masked-safe terminal or clarification answers (`AGENT`). Durable rows carry no credentials, vault mappings, transient reasoning, or provider wire tokens.
  * **Transient State (`ChatAgentRunState`):** Owned by the UI/shell during the active process lifecycle. Manages the active coroutine `Job`, live streaming text buffer (`streamText`), in-progress tool progress string (`progress`), and temporary continuation token. On process recreation or tab re-entry, the UI gracefully re-reads the durable conversation rows without attempting ungrounded token auto-resume.
- **Streaming Token Invariant & Stop Behavior:**
  * Provider Server-Sent Events (`HostedSseDecoder`) feed arbitrary body chunks to the streaming adapter.
  * When the user triggers "Stop", the coroutine job is cancelled immediately (`job?.cancel()`).
  * **Stop Content Preservation:** Displayed partial content received prior to cancellation is committed to the conversation transcript as a stopped turn; already verified link effects or state mutations remain intact. Zero late chunks are allowed to append after stop.
- **Financial Confirmation Boundary:**
  * AI agent responses and tool executions (`list_drafts`, `propose_link`) can propose links or extract suggestions, but **never execute authoritative ledger confirmation**.
  * Only the user's explicit confirmation CTA on `DraftDetailScreen` or `ReviewInboxScreen` can advance a draft to `TransactionEntity.ConfirmationStatus.CONFIRMED`.

### 1.7 Language Contract (English-First Baseline & Bilingual Parity)
- **Codebase & Architecture:** All Kotlin domain entities, DAO interfaces, query/command types, and architectural specifications are authored in English first per repository conventions (`README.md`, `PLAN.md`).
- **UI Presentation:** The frontend supports complete bilingual parity (English baseline with full Vietnamese localization). Design tokens, semantics, and labels maintain 1:1 parity between English and Vietnamese.

## 2. Implementation File & Shared Component Mapping (S10.2 – S10.8)

To ensure a seamless transition for upcoming implementation sprints, the current codebase files are mapped to the proposed components and target tasks:

| Target Sprint & Task | Current Source Files | Proposed Refactored / New Composable Files | Responsibilities & Scope |
| :--- | :--- | :--- | :--- |
| **S10.2: Navigation Shell & Design System** | `AppShell.kt`, `MainActivity.kt#AegisTheme`, `CategoryPalette.kt` | `ui/navigation/AegisNavShell.kt`<br>`ui/theme/AegisTheme.kt`<br>`ui/theme/Color.kt`<br>`ui/theme/Type.kt`<br>`ui/components/AegisFeatureIcon.kt`<br>`ui/components/AegisBottomBar.kt` | Implement 5-tab bottom navigation, feature icon grid primitive, theme tokens (Light/Dark), system insets, back navigation handler. |
| **S10.3: Home, Dashboard & Ledger Browsing** | `DashboardScreen.kt`, `TransactionListScreen.kt`, `PagedBrowsing.kt` | `ui/home/HomeScreen.kt`<br>`ui/home/HomeGlanceBar.kt`<br>`ui/analytics/AnalyticsScreen.kt`<br>`ui/ledger/LedgerScreen.kt`<br>`ui/ledger/TransactionRow.kt` | Replace 543px stacked cards with lightweight single-line glance bar, 8-item launcher grid sitting directly on surface (no enclosing card box, review badge on squircle), dedicated Analytics screen (charts, month selector), and paginated Ledger screen. |
| **S10.4: Quick Capture & Note Journal** | `QuickCaptureScreen.kt`, `CaptureMessageDao.kt` | `ui/capture/QuickCaptureScreen.kt`<br>`ui/capture/NoteComposer.kt`<br>`ui/capture/SavedNotesList.kt` | Focused local note journaling (Android Room `CaptureMessageEntity`, offline device write, zero token jargon, toast feedback), saved notes list, and explicit AI handoff. |
| **S10.5: Dedicated Streaming Chat & Link Interaction** | `ChatOutcomeCards.kt`, `ChatAgentRunState.kt`, `HostedStreaming.kt` | `ui/chat/HostedChatScreen.kt`<br>`ui/chat/ChatStreamingArea.kt`<br>`ui/chat/ChatComposerBar.kt`<br>`ui/chat/LinkEvidenceCard.kt`<br>`ui/chat/ChatHistorySheet.kt` | Dedicated conversational streaming assistant screen (`AgentConversationEntity`, `AgentRunEntity`), waiting/streaming/tool-step/completed/stopped states, stop behavior, in-context `LinkEvidenceCard`, safe non-overlapping FAB geometry (21.7px clearance), dynamic English bottom nav, and human-readable conversation history drawer. |
| **S10.6: Bank Draft Inbox & Review** | `BankDraftReviewScreen.kt`, `ReviewQueueFocus.kt` | `ui/review/ReviewInboxScreen.kt`<br>`ui/review/DraftDetailScreen.kt`<br>`ui/review/DuplicateWarningCard.kt`<br>`ui/review/DraftReceiptCard.kt` | Conflict-first review queue (Needs Decision vs Ready), digital receipt view, category assignment, explicit confirm/dismiss. |
| **S10.7: Manual Entry & Correction** | `TransactionEditorScreen.kt`, `ManualDateTime.kt` | `ui/record/ManualRecordScreen.kt`<br>`ui/record/TransactionEditorForm.kt`<br>`ui/record/CategorySwatchPicker.kt` | Dedicated transaction creation screen, amount input with keypad, category picker grid, source event handoff. |
| **S10.8: Categories, Rules & Settings** | `CategoryManagerScreen.kt`, `BankNotificationSettingsScreen.kt`, `HostedProviderSettingsScreen.kt` | `ui/categories/CategoryRulesScreen.kt`<br>`ui/settings/SettingsScreen.kt`<br>`ui/settings/BankNotificationSection.kt`<br>`ui/settings/HostedProviderSection.kt` | Unified category & rule manager (Tabs: Categories, Rules), centralized settings hub with masked keys and allowed bank app list. |

---

## 3. Device Sizing, Keyboard / Insets & Dynamic Type

### 3.1 Target Device Viewports
The design is verified against standard mobile phone viewports:
- **Reference Compact:** $360\times 800\text{dp}$ (Android standard phone)
- **Reference Standard:** $390\times 844\text{dp}$ (iPhone 14/15/16)
- **Reference Large:** $412\times 915\text{dp}$ (Pixel 8 / Galaxy S24)

### 3.2 System Insets & Keyboard Ergonomics
- **Status Bar & Dynamic Island:** Applied via `WindowInsets.statusBars` or `WindowInsets.safeDrawing`. Top App Bar maintains minimum $56\text{dp}$ height below the status bar.
- **Software Keyboard (IME):**
  * Forms use `WindowInsets.imePadding()` to ensure inputs scroll into view when the keyboard opens.
  * Primary action buttons (e.g., "Lưu vào sổ", "Xác nhận") float above the keyboard or anchor to the bottom of the scrollable column.
  * Tapping outside an input dismisses the software keyboard (`FocusManager.clearFocus()`).
- **Bottom Navigation Insets:** Navigation bar adds `WindowInsets.navigationBars.asPaddingValues()` to prevent overlap with Android 3-button or gesture bar.

### 3.3 Dynamic Text Scaling (1.0x – 1.5x)
- Every textual container uses flexible sizing (`heightIn(min = 48.dp)` instead of hardcoded `height(48.dp)`).
- Critical financial amounts wrap cleanly or truncate with trailing currency symbol (`... ₫`), never dropping significant digits silently.
- Category pills and badges use `maxLines = 1` with `overflow = TextOverflow.Ellipsis`.

---

## 4. Accessibility & Non-Color Cues

- **Minimum Touch Targets:** Every clickable button, icon squircle, tab bar item, and list row has a minimum touch target size of $48\times 48\text{dp}$ (using `Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)`).
- **Semantics & Screen Readers:**
  * Every icon and button defines explicit `contentDescription` in Vietnamese and English (e.g. `contentDescription = "Chuyển tiền, 3 biến động đang chờ"`).
  * Currency numbers include spoken format (e.g., "Mười bốn triệu tám trăm năm mươi nghìn đồng").
- **Non-Color Status Indicators:**
  * `CONFIRMED`: Green color + Checkmark icon (`✓`) + "Đã xác nhận".
  * `CONFLICT`: Amber color + Warning triangle icon (`⚠`) + "Cần xử lý".
  * `DISMISSED`: Slate color + Cross mark icon (`✕`) + "Đã bỏ qua".
  * `PROPOSED_LINK`: Blue color + Link chain icon (`🔗`) + "Đề xuất liên kết".
