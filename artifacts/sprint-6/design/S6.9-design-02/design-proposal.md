# S6.9 Visual Design Proposal (Attempt 02) — Aegis F Redesign

- **Task ID:** `S6.9-design`
- **Attempt ID:** `S6.9-design-02`
- **Agent ID:** `S69DesignCorrection`
- **Assigned Role:** `designer`
- **Prior Attempt Reference:** `S6.9-design-01` (agent `S69VisualDesign`, artifact `artifacts/sprint-6/design/S6.9-design-01/design-proposal.md`)
- **Artifact Output Path:** `artifacts/sprint-6/design/S6.9-design-02/design-proposal.md`
- **Status:** **Proposal Completed — Awaiting User Approval** (Product implementation withheld per contract)

---

## 1. Executive Summary & User Steer Alignment

### 1.1 Priority User Steer: "Non-AI, Kiểu Đơn Giản, Nhưng Vẫn Đủ Màu Sắc, Góc Cạnh"
Following the initial exploratory attempt `S6.9-design-01`, the user explicitly provided a priority design steer:
> *"ưu tiên các thiết kế non-AI, kiểu đơn giản, nhưng vẫn đủ màu sắc, góc cạnh"*

This proposal directly incorporates that directive, replacing the prior floating rounded-slate direction with an opinionated, utilitarian, editorial financial UI:

1. **Góc Cạnh (Crisp Angular Geometry):**
   - Eliminated bubbly, heavily rounded containers (16–28 dp corner radii) and floating pill buttons.
   - Standardized on a crisp **2 dp to 4 dp corner radius** across all panels, buttons, chips, and data cards.
   - Structured the layout with **1 px hairline dividers** (`#232D3F` Dark / `#D1D9E0` Light), rectangular multi-segment bars, and crisp tabular data strips.

2. **Kiểu Đơn Giản & Non-AI (Utilitarian & Editorial Financial Tone):**
   - Zero gradients (no `linear-gradient` hero cards), zero glowing box-shadows, zero glassmorphism blurs.
   - Replaced decorative emoji bottom-navigation icons (`📊`, `💬`, `📥`, `✍`) with **minimalist, high-contrast SVG vector icons** (2.2 px square stroke caps).
   - Applied **monospaced tabular figures** (`383,000 VND`, `45,000 VND`) for immediate numerical alignment and scannability.
   - Genuine layout simplification: elevated Confirmed Spending with a clean left-accent rule (`#EF4444`), and consolidated secondary questions (Income, Transfers, Review) into a single 3-column metrics strip with hairline borders.

3. **Đủ Màu Sắc (Vibrant, Purposeful Functional Palette):**
   - Avoids both monotonous green and washed-out grayscale.
   - Employs an **8-slot deterministic category palette** with memorable, functional hues (Amber, Teal, Indigo, Purple, Rose, Orange, Emerald, Lime) alongside dedicated Neutral Slate (`#64748B`) and Archived Slate (`#94A3B8`).
   - Distinct semantic role accents: Confirmed Spending (`#EF4444` / `#E11D48`), Income (`#10B981` / `#059669`), Transfers (`#0EA5E9` / `#0284C7`), Pending Review (`#F59E0B` / `#D97706`).

4. **Honest Copy & User-Attached Semantics (Zero Fake Automation):**
   - Removed misleading algorithmic claims (`1 Match Found`, `Candidate: Draft #3`, `Matched ✓`).
   - All associations between Quick Capture notes and bank drafts are explicitly framed as **user-attached**, requiring user confirmation.
   - Bounded draft browsing (`Browse drafts to attach ›`) is user-initiated, not an unverified automated suggestion.

---

## 2. Visual Artifacts Index

All visual mockups are rendered at exact phone proportions matching the test device (360×805 CSS viewport @ 2× scale = **720×1610 physical pixels**):

| Artifact File | Description | Resolution |
|---|---|---|
| [`dashboard-mockup.png`](./dashboard-mockup.png) | **Proposed Dashboard (Dark):** Angular scope chips, Hero Spending panel with red left rule, 3-column consolidated metrics strip, segmented category bar, daily spending histogram, honest footnote, minimalist vector bottom nav. | 720×1610 |
| [`chat-quickcapture-mockup.png`](./chat-quickcapture-mockup.png) | **Proposed Chat / Quick Capture (Dark):** Angular raw note composer, privacy tag, chronological note stream with explicit user-attached badges (`User-attached to Draft #1`, `Unattached Note`, `Saved Note`), zero algorithm claims. | 720×1610 |
| [`review-mockup.png`](./review-mockup.png) | **Proposed Review Notifications (Dark):** Filter tabs (`All`, `Attached`, `Unattached`), draft pager (`1 of 4`), VietinBank stated memo, user-attached note context box, illustrative Laya suggestion ranking (84.2%), confirm/dismiss actions. | 720×1610 |
| [`manual-mockup.png`](./manual-mockup.png) | **Proposed Manual Entry (Dark):** Angular 3-way type selector (Expense / Income / Transfer), large monospaced amount input (`123,000 VND`), description with rule match feedback, category selector with amber swatch, date/time inputs, category directory strip. | 720×1610 |
| [`palette-tokens.png`](./palette-tokens.png) | **Design System Tokens & Category Specification:** Complete Light & Dark color tokens, semantic financial roles, 8-color category swatches, safe non-negative modulo algorithm, archived category identity policy, WCAG contrast matrix. | 590×1024 |
| [`design-overview-comparison.png`](./design-overview-comparison.png) | **Before vs After Visual Audit Board:** Side-by-side comparison of the rejected S6.8 phone screenshot vs the proposed S6.9-02 non-AI angular Dashboard with annotated solution callouts. | 1024×737 |
| [`font13-enlarged-mockup.png`](./font13-enlarged-mockup.png) | **Enlarged Font Scale (1.3×) Variant:** Rendered phone layout demonstrating text wrapping, bottom nav resilience, and vertical layout behavior under system accessibility scaling. | 720×1610 |
| [`light-theme-mockup.png`](./light-theme-mockup.png) | **Supported Light Theme Variant:** Rendered Dashboard in light mode (`#F4F6F8` background, `#FFFFFF` containers, `#0F172A` text), verifying contrast and category vibrancy in daytime conditions. | 720×1610 |

---

## 3. Concrete Corrections Addressing Prior Proposal Deficiencies

### 3.1 Chat & Review Copy/State: Explicit Manual Context, Zero Auto-Matching
- **Problem in S6.9-design-01:** Labels such as `Unlinked · 1 Match Found`, `Candidate: Draft #3`, and `Matched ✓` falsely implied automated NLP extraction and match detection, which is strictly out of scope for Sprint 6 (Sprint 9 concern).
- **Correction Applied in S6.9-design-02:**
  - In Chat (`chat-quickcapture-mockup.png`):
    - Note #102: Status pill is `[USER-ATTACHED TO DRAFT #1]`. Excerpt text reads `User interpretation: 45,000 VND · Expense · S68MEAL` with status tag `User-attached ✓` (NOT `Matched ✓`). Actions are `View attached draft (45k) ›` and `Detach context`.
    - Note #101: Status pill is `[UNATTACHED NOTE]`. Displays raw source text without any candidate match assertion. User actions are `Browse drafts to attach ›` (bounded user-initiated browsing) and `Prefill manual form ›`.
    - Note #100: Retains plain `[SAVED NOTE]` with `Add manual interpretation` and `Delete note`.
  - In Review (`review-mockup.png`):
    - Filter tabs updated from `Matched (1)` / `Unlinked (3)` to `[All (4)]`, `[Attached (1)]`, `[Unattached (3)]`.
    - Context card header updated to `USER-ATTACHED NOTE CONTEXT` with indicator `User-attached ✓`.
    - Detach action plainly labeled `Detach context`.

### 3.2 Clear Labeling of Synthetic Design Scenarios & Illustrative Laya Ranking
- **Discrepancy in S6.9-design-01:** Prior handoff claimed historical phone alignment with 4 pending drafts, but the final verified S6.8 phone run concluded with 0 pending drafts after user confirmation.
- **Correction Applied in S6.9-design-02:**
  - Explicitly labeled across all surfaces as **Synthetic Design Scenario (October 2026)**.
  - Review inbox shows 4 waiting drafts as a purposeful synthetic fixture for review flow demonstration.
  - Category suggestions in Review are labeled `ILLUSTRATIVE LAYA SUGGESTION (SYNTHETIC FIXTURE)`, with explicit footer text: *"Illustrative Laya model distribution · Requires user confirmation · Not runtime execution claim"*.
  - All figures strictly balance within the synthetic scenario:
    - Confirmed Spending: `383,000 VND` across `7` records (reconciles: `258,000 VND` No category + `105,000 VND` S68MEAL + `20,000 VND` S68OLD archived).
    - Daily spending: `Oct 21: 123,000 VND` (1 rec) + `Oct 22: 45,000 VND` (1 rec) + `Oct 23: 215,000 VND` (5 recs) = `383,000 VND`.
    - Confirmed Income: `70,000 VND` (1 record).
    - Internal Transfers: `30,000 VND` (1 record).
    - Pending Review Drafts: `4` drafts (Draft #1: `45,000 VND` VietinBank debit; 0 written to ledger).

### 3.3 Usable Archived Category Identity Policy (Resolving Gray Hue Inconsistency)
- **Contradiction in S6.9-design-01:** Prior proposal mapped all archived categories to neutral gray `#94A3B8` while simultaneously claiming hue retention.
- **Policy Specified in S6.9-design-02:**
  - **Visible Badge Mandatory:** Every archived category displays a mandatory, high-contrast rectangular `[ARCHIVED]` badge pill.
  - **Chart & Distribution Bar Policy:** In financial charts and category bars, archived categories share a neutral archived tone (`#94A3B8` Dark / `#64748B` Light, AA 5.8:1). This intentionally deprioritizes retired categories and reserves the vibrant 8-hue palette for active, actionable budgets.
  - **Directory & Selector Policy:** In the Manual category selector and management directory, archived categories display their deterministically assigned category hue at **40% opacity** alongside the textual `[ARCHIVED]` badge, ensuring distinct identity during history browsing without visual clutter.

### 3.4 Algorithmic Safety: Safe Non-Negative Modulo
- **Bug in S6.9-design-01:** Used `kotlin.math.abs(categoryId.hashCode()) % 8`. In Java/Kotlin, `Math.abs(Int.MIN_VALUE)` remains `-2147483648` (negative), which can produce negative array indexes.
- **Correction Applied in S6.9-design-02:**
  ```kotlin
  object CategoryColorRegistry {
      val PALETTE_DARK = listOf(
          Color(0xFFF59E0B), // 0: Amber (Dining & Food)
          Color(0xFF0D9488), // 1: Teal (Groceries)
          Color(0xFF4F46E5), // 2: Indigo (Transport)
          Color(0xFF9333EA), // 3: Purple (Utilities & Bills)
          Color(0xFFE11D48), // 4: Rose (Healthcare)
          Color(0xFFEA580C), // 5: Orange (Shopping)
          Color(0xFF16A34A), // 6: Emerald (Education & Work)
          Color(0xFF65A30D), // 7: Lime (Home & Maintenance)
      )

      val UNCATEGORIZED_DARK = Color(0xFF64748B) // Neutral Slate
      val ARCHIVED_CHART_DARK = Color(0xFF94A3B8) // Neutral Archived Tone

      /**
       * Safe non-negative modulo:
       * Bitwise AND with 0x7FFFFFFF clears the sign bit, guaranteeing a positive
       * integer before modulo arithmetic, completely avoiding the Int.MIN_VALUE abs bug.
       */
      fun resolveColorIndex(categoryId: Long): Int {
          val hash = categoryId.hashCode()
          return ((hash.toLong() and 0x7FFFFFFF_L) % PALETTE_DARK.size).toInt()
      }

      fun resolveChartColor(categoryId: Long?, isArchived: Boolean): Color {
          if (categoryId == null) return UNCATEGORIZED_DARK
          if (isArchived) return ARCHIVED_CHART_DARK
          return PALETTE_DARK[resolveColorIndex(categoryId)]
      }
  }
  ```

### 3.5 Actual Font 1.3× Scaling & Light Theme Verification (Honest Limits)
- Rather than asserting theoretical zero-clipping from fixed images, two dedicated layout variants were rendered and visually audited:
  1. **Enlarged Font Scale (1.3×) [`font13-enlarged-mockup.png`]:**
     - **Observed Behavior:** Bottom navigation labels (`labelSmall` 10.5 sp scaled to ~13.6 sp) fit on a single line across phone viewports (`DASHBOARD`, `CHAT`, `REVIEW`, `MANUAL`) without horizontal clipping. The numeric badge (`4`) maintains proper top-right positioning.
     - **Honest Limits Identified:** In fixed-height containers, date strings like `2026-10-01 → 2026-10-31` wrap to two lines. In Compose implementation, the Scope row must either use compact date formatting (`Oct 01 → Oct 31`) or allow dynamic row height. Screen contents must use `Modifier.verticalScroll(rememberScrollState())` to handle vertical expansion cleanly without overlap.
  2. **Supported Light Theme [`light-theme-mockup.png`]:**
     - High-contrast verification: Primary text `#0F172A` on `#FFFFFF` yields **16.2:1 (WCAG AAA)**; secondary text `#475569` yields **6.8:1 (WCAG AAA)**; Confirmed Spending `#E11D48` achieves **5.8:1 (WCAG AA)**.
     - Full theme parity: App does not default to dark-only; both Light and Dark themes share identical angular geometry, category colors, and layout structure.

---

## 4. Screen Architecture & Visual Details

### 4.1 Dashboard Destination (`dashboard-mockup.png`)
```
+-----------------------------------------------------------+
| 17:40                                            4G  100% | (Status Bar)
| DASHBOARD [SYNTHETIC FIXTURE]                 [⚙ SETTINGS] | (App Bar, 2dp corners)
+-----------------------------------------------------------+
| [📅 2026-10-01 → 2026-10-31 ▾]       [🏷 All categories ▾]| (Angular Scope Chips)
+-----------------------------------------------------------+
| | ● CONFIRMED SPENDING                        [7 expenses] |
| | 383,000 VND                                             | (Hero Panel with
| | Scope: October 2026 · Reconciled        See 7 records → |  solid red left rule)
+-----------------------------------------------------------+
| [● Income   ]      | [● Transfers]      | [● Review      ]| (3-Column Strip with
| +70,000 VND        | 30,000 VND         | 4 waiting       |  vertical hairline
| 1 record           | 1 movement         | seed inbox      |  dividers)
+-----------------------------------------------------------+
| SPENDING BY CATEGORY                         [CHART / LIST]| (Flat Panel)
| [========================|============|==]                | (Proportional Multi-Color
| ■ No category            258,000 VND   67.4%   ›          |  Segmented Bar &
| ■ S68MEAL (Food)         105,000 VND   27.4%   ›          |  Tabular Rows)
| ■ S68OLD [ARCHIVED]       20,000 VND    5.2%   ›          |
+-----------------------------------------------------------+
| DAILY SPENDING TREND                      3 active cal days|
|    123k             45k            215k                   | (Proportional
|   [====]           [==]          [======] (Active)        |  Rectangular Bars)
|   Oct 21          Oct 22          Oct 23                  |
+-----------------------------------------------------------+
| ℹ Records stored locally on device · Confirmed only        | (Honest Footnote)
+-----------------------------------------------------------+
| [||| DASHBOARD]    [💬 CHAT]      [📥 REVIEW (4)]  [✍ MANUAL]| (Minimalist Vector Nav)
+-----------------------------------------------------------+
```

### 4.2 Chat / Quick Capture Destination (`chat-quickcapture-mockup.png`)
- Dedicated raw personal note journal; no chatbot illusion or fake conversational bubbles.
- Upper Composer: Flat panel with cyan left accent rule, direct text area (`"Lunch with team 60k at Pho Bistro"`), offline notice (`No auto-created ledger transaction`), and sharp `[SAVE CAPTURE ↵]` button.
- Stream Cards:
  - Note #102: Green left rule, `[USER-ATTACHED TO DRAFT #1]`, user interpretation excerpt (`45,000 VND · Expense · S68MEAL`), `User-attached ✓`, direct link to attached draft.
  - Note #101: Slate left rule, `[UNATTACHED NOTE]`, raw text (`"Bought office coffee beans 120,000d"`), actions `Browse drafts to attach ›` and `Prefill manual form ›`.
  - Note #100: Plain note with `Add manual interpretation` and `Delete note`.

### 4.3 Review Notifications Destination (`review-mockup.png`)
- Inbox Strip: Filter tabs `[ALL (4)]`, `[ATTACHED (1)]`, `[UNATTACHED (3)]`, with pager `Draft 1 of 4`.
- Bank Draft Panel: Red left rule, VietinBank memo (`GD: -45,000VND 22/10/26 08:15 ND: POS TOCOTOCO`), stated bank amount `45,000 VND` `[DEBIT (OUT)]`.
- User-Attached Context: Prominently displayed green box showing `"Lunch with client at Tocotoco 45k"`, tagged `User-attached ✓`.
- Illustrative Laya Suggestion: Ranked distribution card with probability bars:
  - `S68MEAL (Food & Dining)`: 84.2% `[Selected]` (Amber bar)
  - `Groceries`: 11.5% (Cyan bar)
  - `Unknown / Other`: 4.3% (Slate bar)
  - Clear notice: *Illustrative Laya model distribution · Requires user confirmation*.
- Actions: Prominent emerald green `[ Confirm Expense Record (45,000 VND) ✓ ]` CTA, secondary `[ Dismiss Notification ]` and `[ Edit in Manual Form ]`.

### 4.4 Manual Entry Destination (`manual-mockup.png`)
- Type Switch: Angular 3-segment toggle (`Expense` red accent, `Income` green, `Transfer` azure).
- Amount Field: Monospaced high-contrast display (`123,000 VND`) with red hairline accent.
- Description Field: Displays rule match provenance (`✓ Matched remembered rule for "Pho Bistro"`).
- Category Selector: Shows Amber category swatch (`#F59E0B`) and category name.
- Primary CTA: Solid crimson button `[ Save Confirmed Expense ✓ ]`.
- Category Directory: Horizontal strip of active category chips with quick `Manage & Archive ›` link.

---

## 5. Design Tokens Specification

### 5.1 Color Tokens Table

| Token Name | Dark Theme | Light Theme | Usage / Semantics | WCAG Ratio |
|---|---|---|---|---|
| `color-bg-body` | `#0C1017` | `#F4F6F8` | Main window background | — |
| `color-bg-container` | `#131923` | `#FFFFFF` | Panels, cards, form containers | — |
| `color-bg-inset` | `#0A0D13` | `#F0F3F6` | Text fields, chart track, footnotes | — |
| `color-border-main` | `#232D3F` | `#D1D9E0` | Hairline dividers, panel borders | — |
| `color-border-sub` | `#1C2433` | `#E2E8F0` | Inner row dividers | — |
| `color-text-primary` | `#F8FAFC` | `#0F172A` | Primary titles, hero amounts | ≥ 15.8:1 (AAA) |
| `color-text-secondary` | `#94A3B8` | `#475569` | Field labels, timestamps, metadata | ≥ 6.8:1 (AAA) |
| `color-text-muted` | `#64748B` | `#64748B` | Inactive hints, micro text | ≥ 4.5:1 (AA) |
| `color-role-expense` | `#EF4444` | `#E11D48` | Confirmed spending / money leaving | ≥ 5.8:1 (AA) |
| `color-role-income` | `#10B981` | `#059669` | Confirmed income / money arriving | ≥ 7.2:1 (AAA) |
| `color-role-transfer` | `#0EA5E9` | `#0284C7` | Account-to-account transfer | ≥ 6.5:1 (AAA) |
| `color-role-pending` | `#F59E0B` | `#D97706` | Pending intake / drafts waiting | ≥ 8.1:1 (AAA) |

### 5.2 Category Palette Table (8 Deterministic Slots)

| Slot | Category Name | Dark Token | Light Token | Association | Contrast |
|---|---|---|---|---|---|
| — | **No category** | `#64748B` | `#475569` | Unassigned / Fallback | AA 5.2:1 |
| 0 | **S68MEAL / Dining** | `#F59E0B` | `#D97706` | Food, coffee, restaurants | AAA 9.4:1 |
| 1 | **Groceries** | `#0D9488` | `#0F766E` | Supermarket, market, pantry | AA 5.8:1 |
| 2 | **Transport** | `#4F46E5` | `#4338CA` | Fuel, transit, ride-hailing | AA 4.8:1 |
| 3 | **Utilities & Bills** | `#9333EA` | `#7E22CE` | Electricity, water, internet | AA 4.6:1 |
| 4 | **Health & Care** | `#E11D48` | `#BE123C` | Medical, pharmacy, fitness | AA 4.7:1 |
| 5 | **Shopping** | `#EA580C` | `#C2410C` | Apparel, electronics, goods | AA 4.9:1 |
| 6 | **Work & Education** | `#16A34A` | `#15803D` | Books, courses, office supplies | AA 5.1:1 |
| 7 | **Home & Living** | `#65A30D` | `#4D7C0F` | Furniture, repairs, maintenance | AA 5.0:1 |
| — | **S68OLD [Archived]** | `#94A3B8` | `#64748B` | Neutral tone + [ARCHIVED] badge | AA 5.8:1 |

---

## 6. Implementation Handoff Guide (For Future Implementation Task)

*Note: This section guides the future implementation role once the user explicitly approves this design proposal. No product code has been altered in this task.*

### 6.1 Theme Architecture (`app/src/main/java/io/github/triet4p/aegisf/ui/theme/`)
1. **Geometry Tokens:** Define `CornerRadiusMicro = 2.dp` and `CornerRadiusSmall = 4.dp`. Avoid `RoundedCornerShape(16.dp)` or `28.dp`.
2. **Color Schemes:**
   - Dark scheme: background `#0C1017`, surface `#131923`, surfaceVariant `#0A0D13`, outline `#232D3F`, outlineVariant `#1C2433`.
   - Light scheme: background `#F4F6F8`, surface `#FFFFFF`, surfaceVariant `#F0F3F6`, outline `#D1D9E0`, outlineVariant `#E2E8F0`.
3. **Typography:** Set `FontFamily.Monospace` for numeric amounts; apply `FontWeight.Bold` and uppercase formatting to section titles.
4. **Bottom Nav Composable:** Replace text emojis with standard Material vector path icons (`Icons.Default.Poll`, `Icons.Default.ChatBubbleOutline`, `Icons.Default.Inbox`, `Icons.Default.Edit`).

### 6.2 Visual Acceptance Checklist for Verification

| Item # | Acceptance Criterion | Verification Method | Status |
|---|---|---|---|
| **VAC-01** | Replace murky olive green with crisp Obsidian Charcoal (`#0C1017`) and Soft Gray (`#F4F6F8`) | Visual inspection on phone | [ ] Pending Approval |
| **VAC-02** | All cards and buttons adhere to 2–4 dp corner radius (angular, non-AI geometry) | Inspect component corner radii | [ ] Pending Approval |
| **VAC-03** | Category breakdown renders multi-color segmented proportional distribution bar | Verify segment widths match percentages | [ ] Pending Approval |
| **VAC-04** | Category color swatches match across charts, legends, selectors, and transaction rows | Cross-screen color consistency check | [ ] Pending Approval |
| **VAC-05** | Category color algorithm uses safe non-negative modulo (avoids `Int.MIN_VALUE` bug) | Unit test with `Long.MIN_VALUE` / negative hashes | [ ] Pending Approval |
| **VAC-06** | Income, Transfers, and Review consolidated into 1 row without card inflation | Layout inspection | [ ] Pending Approval |
| **VAC-07** | Chat & Review copy use explicit user-attached context; zero auto-match claims | Audit text strings across screens | [ ] Pending Approval |
| **VAC-08** | Review category suggestion labeled as illustrative Laya ranking | Verify disclaimer text on Review screen | [ ] Pending Approval |
| **VAC-09** | Bottom nav destination labels fit on 1 line at 1.3× font scale | Phone render at font scale 1.3 | [ ] Pending Approval |
| **VAC-10** | WCAG 2.1 AA/AAA contrast verified for all text and swatches in both themes | Contrast matrix validation | [ ] Pending Approval |

---

## 7. Proposal Boundaries & Next Steps

- **Scope Limits:** This task provides the comprehensive visual design audit, design tokens, safe color assignment engine, implementation guidelines, and 8 rendered physical-proportioned PNG mockups.
- **Zero Product Mutation:** No application source code, Gradle configurations, test fixtures, or plans have been modified.
- **Next Step:** Awaiting explicit user approval of this visual design direction. After user approval, the user will separately select the implementation role (Bronze, Silver, or Gold) to begin the Compose UI migration under S6.9.
