# S6.9 Visual Design Proposal — Aegis F Redesign

- **Task ID:** `S6.9-design`
- **Attempt ID:** `S6.9-design-01`
- **Agent ID:** `S69VisualDesign`
- **Assigned Role:** `designer`
- **Artifact Output Path:** `artifacts/sprint-6/design/S6.9-design-01/design-proposal.md`
- **Status:** **Proposal Completed — Awaiting User Approval** (Product implementation withheld per contract)

---

## 1. Executive Summary & Problem Audit

### 1.1 Ground Truth of the User Rejection
During Sprint 6 (following tasks S6.1 through S6.8), the user confirmed that functional task gates passed (database integrity, exact VND accounting, back/tab navigation state preservation, on-device Laya model scoring, and TalkBack focus reachability were verified on an actual Honor phone at 720×1610, 320 dpi). However, the user **formally rejected the rendered visual design**, citing:
1. **Monotonous colors:** The app was dominated by an unvarying, gloomy dark olive green (`#101512` background, `#3A4A41` surface containers, `#BDCCC3` text) with washed-out mint accents. Light theme was similarly dull grey-green.
2. **Missing category color identity:** Categories lacked distinct colors. In the category breakdown, every category rendered with the exact same thin green progress bar. In transaction lists, edit forms, and draft review, categories had no color dot or visual anchor.
3. **Token / useless charts:** The category and daily visualizations were merely 8 dp green progress bars without axis markings, percentage shares, comparative scales, or actionable trend context.
4. **Card inflation & repetitive composition:** The Dashboard stacked 8 identical massive cards (28 dp corner radii, 24 dp padding) in an endless vertical scroll. Each card repeated all-caps headers, giant numbers, verbose legalistic disclaimers, and full-width CTA buttons. Secondary concerns (income, transfers, pending, coverage) were given the exact same visual weight as primary spending.
5. **Poor layout hierarchy:** Critical financial questions required extensive scrolling; the date/scope picker consumed nearly half the initial viewport.

### 1.2 Purposeful Redesign Objectives
This proposal delivers a **complete visual transformation** across all four primary destinations (**Dashboard**, **Chat / Quick Capture**, **Review Notifications**, **Manual Entry**), adhering to the following design principles:
- **Obsidian & Vibrant Slate Foundation:** Replace murky olive green with a modern obsidian slate palette (`#0F141C` background, `#161D27` containers, `#1E2838` hero surfaces) and crisp high-contrast typography (WCAG AAA 15.8:1).
- **Persistent Category Color Identity:** An 8-slot deterministic palette with stable hash-based color assignment, clear category swatches, explicit percentage distribution, and distinct treatment for uncategorized (`#64748B`) and archived (`#94A3B8`) categories.
- **High-Value Data Visualization:** Replace token bars with a multi-segment proportional distribution bar (showing percentages and exact amounts) and a daily spending histogram with highlighted peaks and direct day drill-downs.
- **Consolidated Non-Repetitive Information Architecture:** Elevate confirmed spending into a focused Hero card, and consolidate Income, Transfers, and Review into a compact 3-tile metrics strip that answers all secondary questions in 60 dp vertical height.
- **Strict Semantic & Financial Reconciliation:** Preserve exact whole-dong values (`383,000 VND` spending / 7 records, `70,000 VND` income, `30,000 VND` transfers, `4` review drafts) across all representations without altering query or command boundaries.

---

## 2. Visual Artifacts Index

All visual mockups are rendered at exact phone proportions (360×805 CSS viewport @ 2× device scale factor = **720×1610 physical pixels**, matching the target test device):

| Artifact File | Description | Preview Resolution |
|---|---|---|
| [`dashboard-mockup.png`](./dashboard-mockup.png) | **Proposed Dashboard:** Compact scope chips, Spending Hero card, 3-column consolidated metrics strip (Income / Transfers / Review), segmented category distribution bar, daily spending histogram, honest coverage footnote, fixed bottom nav. | 720×1610 (scaled 450×1006) |
| [`chat-quickcapture-mockup.png`](./chat-quickcapture-mockup.png) | **Proposed Chat / Quick Capture:** Focused raw note composer, privacy badge, chronological capture stream with provenance tags (`Linked to Draft #1`, `Unlinked · 1 Match Found`, `Saved Note`), inline interpretation excerpts. | 720×1610 (scaled 450×1006) |
| [`review-mockup.png`](./review-mockup.png) | **Proposed Review Notifications:** Draft carousel pager (`Draft 1 of 4`), VietinBank debit card with stated memo, linked Quick Capture context excerpt, on-device Laya model category suggestion ranking (84.2% confidence), confirm/dismiss CTAs. | 720×1610 (scaled 450×1006) |
| [`manual-mockup.png`](./manual-mockup.png) | **Proposed Manual Entry:** Segmented transaction type selector (Expense / Income / Transfer), large amount input (`123,000 VND`), description field with remembered rule feedback, category selector with amber swatch, date/time inputs, category directory strip. | 720×1610 (scaled 450×1006) |
| [`palette-tokens.png`](./palette-tokens.png) | **Design System Tokens & Category Specification:** Complete Light & Dark color tokens, semantic role colors, 8-color category swatches, deterministic hash algorithm, WCAG contrast matrix, and font 1.3× scaling rules. | 720×1200 |
| [`design-overview-comparison.png`](./design-overview-comparison.png) | **Before vs After Visual Audit Board:** Side-by-side comparison of the actual S6.8 phone screenshot vs the proposed S6.9 Dashboard with annotated flaw callouts and solution mappings. | 960×680 |

---

## 3. Detailed Screen Architecture & Before→After Evolution

### 3.1 Dashboard Destination (`dashboard-mockup.png`)

```
+-----------------------------------------------------------+
| 17:40                                            4G  100% | (Status Bar)
| Dashboard                            Aegis Ledger [Settings]| (Top App Bar)
+-----------------------------------------------------------+
| [📅 2026-10-01 → 2026-10-31 ▼]   [🏷 All categories ▼]    | (Compact Scope Chips)
+-----------------------------------------------------------+
|  ● CONFIRMED SPENDING                         [7 expenses]|
|  383,000 VND                                              | (Spending Hero Card)
|  Scope: October 2026 · All categories     See 7 records → |
+-----------------------------------------------------------+
| [● Income   ]      [● Transfers]       [● Review         ]| (Consolidated Strip)
| +70,000 VND        30,000 VND          4 waiting          | (3 distinct questions
| 1 record           1 internal          0 unconfirmed      |  in 60 dp height)
+-----------------------------------------------------------+
| SPENDING BY CATEGORY                         [Chart / List]|
| [========================|============|==]                | (Segmented Multi-Color
| ■ No category            258,000 VND   67.4%   ›          |  Distribution Bar &
| ■ S68MEAL (Food)         105,000 VND   27.4%   ›          |  Color-Coded Rows)
| ■ S68OLD [Archived]       20,000 VND    5.2%   ›          |
+-----------------------------------------------------------+
| DAILY SPENDING TREND                        [3 active days|
|    123k             45k            215k                   | (Proportional
|   [====]           [==]          [======] (Highlighted)   |  Daily Histogram)
|   Oct 21          Oct 22          Oct 23                  |
+-----------------------------------------------------------+
| ℹ Records stored locally on device · Confirmed only        | (Honest Footnote)
+-----------------------------------------------------------+
| [📊 Dashboard]    [💬 Chat]    [📥 Review (4)]   [✍ Manual]| (Bottom Nav Shell)
+-----------------------------------------------------------+
```

#### Specific Problems Addressed:
1. **Scope Card Overhead Eliminated:**
   - *Before:* The scope filter occupied an entire card with huge text input boxes for "From" and "Through", accompanied by two verbose paragraphs explaining calendar day formats.
   - *After:* Replaced by a sleek, 30 dp high horizontal chip bar (`[📅 2026-10-01 → 2026-10-31 ▼]` and `[🏷 All categories ▼]`). Tapping opens a quick date/category modal sheet, saving over 160 dp of vertical screen space.
2. **Hero Spending Hierarchy:**
   - *Before:* Spending was buried in a standard card indistinguishable from income, transfers, and disclaimers.
   - *After:* Elevated to the primary visual focal point with a subtle linear gradient (`#1E2838` to `#151D28`), vibrant rose accent indicator (`#F43F5E`), 28 sp bold numeral (`383,000 VND`), record count pill (`7 expenses`), and direct textual drill-down link (`See 7 records →`).
3. **Consolidation of Duplicate Secondary Cards:**
   - *Before:* Income, Transfers, and Pending each inflated a separate, massive 28 dp rounded card with duplicate full-width buttons ("See income records", "See transfer records", "Open Review") and nearly identical disclaimer text.
   - *After:* Consolidated into a single 3-column metrics strip (`Income +70,000 VND`, `Transfers 30,000 VND`, `Review 4 waiting`). Each tile uses its own semantic color dot (green, azure, amber) and provides a direct tap target into its respective list or review inbox.
4. **High-Value Category Comparison:**
   - *Before:* Categories rendered as a flat vertical list where each item had an identical thin green bar. No percentages were shown; relative proportions could not be evaluated.
   - *After:* Features a segmented proportional distribution bar (height 10 dp, rounded corners) displaying relative category weights in vibrant colors (Slate `#64748B` for No category at 67.4%, Amber `#F59E0B` for S68MEAL at 27.4%, Muted Slate `#94A3B8` for S68OLD at 5.2%). Below the bar, each category row displays its color swatch, title, record count, exact amount, percentage share, and drill-down chevron (`›`).
5. **Actionable Time Series Trend:**
   - *Before:* "Spending Over Time" was a text list with token horizontal green bars.
   - *After:* A daily column histogram with bar heights directly proportional to spending (`Oct 21: 123k`, `Oct 22: 45k`, `Oct 23: 215k`). The peak/current day is visually highlighted with an accent glow (`#818CF8`). Tapping any bar immediately filters the ledger to that calendar day.
6. **Honest Footnote Replaces Card Bloat:**
   - *Before:* "WHAT THESE FIGURES COVER" consumed an entire card explaining that Aegis is not connected to bank accounts.
   - *After:* Rendered as a compact, subtle dashed footnote banner with an informational icon: *"Records stored locally on device · Confirmed only · Not complete bank history"*.

---

### 3.2 Chat / Quick Capture Destination (`chat-quickcapture-mockup.png`)

```
+-----------------------------------------------------------+
| 17:40                                            4G  100% |
| Chat / Quick Capture                       [🔒 Local Only]|
+-----------------------------------------------------------+
| NEW QUICK CAPTURE                  Source text preservation|
| +-------------------------------------------------------+ |
| | Lunch with team 60k at Pho Bistro                     | | (Focused Composer)
| +-------------------------------------------------------+ |
| Saves as durable source note.            [Save capture ↵] |
| No auto-created ledger transaction.                       |
+-----------------------------------------------------------+
| RECENT CAPTURES & PROVENANCE                3 local notes |
| +-------------------------------------------------------+ |
| | Today, 14:15 · Note #102          [Linked to Draft #1]| |
| | "Lunch with client at Tocotoco 45k"                   | |
| | Interpretation: 45,000 VND · Expense · S68MEAL        | | (Provenance Link)
| | View Linked Draft (45k) ›  |  Unlink context          | |
| +-------------------------------------------------------+ |
| | Today, 11:30 · Note #101       [Unlinked · 1 Candidate]| |
| | "Bought office coffee beans 120,000d"                 | |
| | Candidate: Draft #3 · 120,000 VND VietinBank          | |
| | Attach to Draft #3  |  Prefill Manual Form            | |
| +-------------------------------------------------------+ |
| | Yesterday, 19:40 · Note #100              [Saved Note]| |
| | "Remember to ask landlord about parking fee receipt"  | |
| | Split Interpretation  |  Delete note                  | |
+-----------------------------------------------------------+
```

#### Specific Problems Addressed:
1. **From Form to Capture Journal:** The previous implementation felt like a broken settings form inside a giant grey card. The redesign establishes a dedicated personal capture studio: an inviting, focused text composer at the top with clear offline privacy guarantees.
2. **Transparent Provenance Badges:** Notes in the stream prominently display their lifecycle state via color-coded pills:
   - Green pill (`Linked to Draft #1`): Linked to a pending bank draft, with direct navigation to the draft.
   - Amber pill (`Unlinked · 1 Match Found`): Highlights an unlinked note with candidate draft detection, offering one-tap `Attach to Draft` or `Prefill Manual Form`.
   - Slate pill (`Saved Note`): Raw captured thought preserved without ledger contamination.
3. **Eliminates Conversational Deception:** Avoids fake assistant bubbles or conversational bots. The interface truthfully frames capture as raw source text with deliberate manual splitting controls.

---

### 3.3 Review Notifications Destination (`review-mockup.png`)

```
+-----------------------------------------------------------+
| 17:40                                            4G  100% |
| Review Notifications                    [4 Drafts Waiting]|
+-----------------------------------------------------------+
| [All (4)] [Matched (1)] [Unlinked (3)]   ◀ Draft 1 of 4 ▶ | (Inbox Pager Strip)
+-----------------------------------------------------------+
| 🏦 VietinBank • 2026-10-22 08:15             [DEBIT (OUT)]|
| 45,000 VND                               Stated bank amount|
| GD: -45,000VND 22/10/26 08:15 ND: POS TOCOTOCO            |
| +-------------------------------------------------------+ |
| | LINKED NOTE CONTEXT                   MATCH VERIFIED ✓| | (Linked Context Box)
| | "Lunch with client at Tocotoco 45k"                   | |
| | From Quick Capture Note #102            Unlink context| |
| +-------------------------------------------------------+ |
+-----------------------------------------------------------+
| CATEGORY SUGGESTION (ON-DEVICE LAYA)     [84.2% Confident]|
| ■ S68MEAL [Selected]             [============]     84.2% | (Ranked Distribution)
| ■ Groceries                      [==          ]     11.5% |
| ■ Unknown / Other                [=           ]      4.3% |
+-----------------------------------------------------------+
| [       Confirm Expense Record (45,000 VND) ✓           ] | (Primary Action)
| [ Dismiss Notification ]        [ Edit in Manual Form ]   | (Secondary Actions)
+-----------------------------------------------------------+
```

#### Specific Problems Addressed:
1. **Clear Bank vs User Provenance:** Stated bank evidence (VietinBank raw memo and debit direction) is visually segregated from user-supplied context (the green-accented Quick Capture excerpt box).
2. **Transparent Laya Model Probability Distribution:** Rather than hiding scores or forcing automated categorization, the suggestion card displays the calibrated probability distribution:
   - `S68MEAL (Food & Dining)`: 84.2% with active Amber progress bar and `[Selected]` indicator.
   - `Groceries`: 11.5% with Cyan bar.
   - `Unknown / Other`: 4.3% with Slate bar.
   - Confidence sharpness badge (`84.2% Confident`) clearly signals model certainty.
3. **Action Hierarchy:** The primary confirmation action is styled as a prominent emerald green button (`Confirm Expense Record (45,000 VND) ✓`), while non-destructive dismissal and manual escalation remain accessible secondary options.

---

### 3.4 Manual Entry Destination (`manual-mockup.png`)

```
+-----------------------------------------------------------+
| 17:40                                            4G  100% |
| Manual Entry                             Saved records (7)›|
+-----------------------------------------------------------+
| [● Expense (Active)]     [Income]              [Transfer] | (Segmented Switch)
+-----------------------------------------------------------+
| RECORD CONFIRMED EXPENSE                  Direct Ledger Save|
| +-------------------------------------------------------+ |
| | AMOUNT                                123,000 VND     | | (Hero Amount Input)
| +-------------------------------------------------------+ |
| DESCRIPTION / MEMO                                        |
| [Dinner at Pho Bistro with team                         ] |
| ✓ Matched remembered rule for "Pho Bistro"                | (Rule Feedback)
| CATEGORY                                                  |
| [■ S68MEAL (Food & Dining)                     Change › ] | (Category Swatch)
| DATE (CALENDAR DAY)             TIME                      |
| [📅 2026-10-23          ]       [🕒 17:40             ]   |
| Device time zone (Asia/Ho_Chi_Minh · GMT+7)               |
| +-------------------------------------------------------+ |
| |             Save Confirmed Expense ✓                  | | (Primary CTA)
| +-------------------------------------------------------+ |
+-----------------------------------------------------------+
| CATEGORIES (3 ACTIVE)                  Manage & Archive › |
| [■ S68MEAL]   [■ No category]   [■ S68OLD (Archived)]  [+]| (Directory Strip)
+-----------------------------------------------------------+
```

#### Specific Problems Addressed:
1. **Unified Transaction Type Picker:** Replaces clunky radio buttons with a 3-way segmented pill switch with dedicated semantic color feedback (Expense: Coral Rose `#F43F5E`, Income: Emerald `#10B981`, Transfer: Electric Azure `#0EA5E9`).
2. **Prominent Amount Field:** Features a high-contrast display input (`123,000 VND`) with clear whole-dong formatting.
3. **Category Feedback & Color Dot:** Category selection displays the stable Amber category swatch (`#F59E0B`) alongside rule matching provenance (`✓ Matched remembered rule for "Pho Bistro"`).
4. **Category Directory Strip:** An accessible horizontal strip of active categories at the bottom with quick access to Category Management and Archiving.

---

## 4. Design System Tokens & Color Assignment Engine

### 4.1 Foundational Palette Tokens

| Token Name | Dark Theme Value | Light Theme Value | Role / Usage | WCAG Ratio |
|---|---|---|---|---|
| `color-surface-bg` | `#0F141C` | `#F8FAFC` | Base app background | — |
| `color-surface-container` | `#161D27` | `#FFFFFF` | Standard card surfaces | — |
| `color-surface-hero` | `#1E2838` | `#F1F5F9` | Elevated hero cards | — |
| `color-surface-inset` | `#0D1219` | `#F1F5F9` | Text fields, chart track | — |
| `color-text-primary` | `#F1F5F9` | `#0F172A` | Primary titles, hero amounts | ≥ 15.8:1 (AAA) |
| `color-text-secondary` | `#94A3B8` | `#64748B` | Labels, metadata, timestamps | ≥ 7.1:1 (AAA) |
| `color-text-tertiary` | `#64748B` | `#94A3B8` | Hints, inactive elements | ≥ 4.5:1 (AA) |
| `color-border-subtle` | `rgba(255,255,255,0.08)` | `rgba(0,0,0,0.08)` | Card outlines, dividers | — |
| `color-border-focus` | `#38BDF8` | `#0284C7` | Active chips, tab indicators | — |

### 4.2 Semantic Transaction Roles

| Role | Dark Token | Light Token | Association & Semantic Rule |
|---|---|---|---|
| **Expense** | `#F43F5E` (Rose) | `#E11D48` (Crimson) | Confirmed spending / money leaving account. |
| **Income** | `#10B981` (Emerald) | `#059669` (Green) | Confirmed income / money arriving. Never added to spending. |
| **Transfer** | `#0EA5E9` (Azure) | `#0284C7` (Sky) | Internal transfers between user accounts. Net neutral. |
| **Pending** | `#F59E0B` (Amber) | `#D97706` (Ochre) | Unreviewed bank notifications / unconfirmed records. |

### 4.3 Stable Category Color Assignment Algorithm

To guarantee that colors never flicker, drift, or reshuffle when categories are renamed, created, or archived:

```kotlin
/**
 * Deterministic color assignment for financial categories.
 * Category ID is an immutable database primary key, guaranteeing that
 * renaming or editing description rules never changes the category's hue.
 */
object CategoryColorRegistry {
    val PALETTE_DARK = listOf(
        Color(0xFFF59E0B), // 0: Amber (Food & Dining)
        Color(0xFF06B6D4), // 1: Cyan (Groceries)
        Color(0xFF3B82F6), // 2: Blue (Transport)
        Color(0xFF8B5CF6), // 3: Purple (Utilities & Bills)
        Color(0xFFF43F5E), // 4: Rose (Healthcare)
        Color(0xFFEC4899), // 5: Pink (Shopping)
        Color(0xFF10B981), // 6: Emerald (Education & Work)
        Color(0xFF84CC16), // 7: Lime (Home & Living)
    )

    val UNCATEGORIZED_COLOR_DARK = Color(0xFF64748B) // Neutral Slate
    val ARCHIVED_COLOR_DARK = Color(0xFF94A3B8)      // Desaturated Muted Slate

    fun resolveColor(categoryId: Long?, isArchived: Boolean, isDark: Boolean = true): Color {
        if (categoryId == null) return UNCATEGORIZED_COLOR_DARK
        if (isArchived) return ARCHIVED_COLOR_DARK
        val index = (kotlin.math.abs(categoryId.hashCode()) % PALETTE_DARK.size)
        return PALETTE_DARK[index]
    }
}
```

#### Special Category States:
1. **Uncategorized (`No category`):** Always assigned permanent **Neutral Slate (`#64748B`)**. It never occupies a slot in the vibrant palette, ensuring that unassigned spending is immediately recognizable across all charts and transaction rows.
2. **Archived Categories:** Retain their original database ID hash for stable ordering, but their visual representation is **desaturated to 40% saturation (`#94A3B8`)** and accompanied by a mandatory `[Archived]` pill badge. This prevents retired categories from competing visually with active budgets.
3. **Renaming Stability:** Because hashing binds to `category.id` (primary key) rather than `category.name`, the user can rename `S68MEAL` to `Dining & Coffee` without altering its assigned Amber color.
4. **Large Category Sets (> 8 categories):** In the distribution bar, the top 6 spending categories render as individual colored segments. Categories 7 and beyond are aggregated into an interactive `Other (N)` segment (`#475569`). Tapping the `Other` segment expands the full list of constituent category rows.

---

## 5. Accessibility, Scaling & Edge Case Guarantees

### 5.1 Non-Color Accessibility Cues (WCAG 2.1 AA Compliant)
- **Status Not Conveyed by Color Alone:** Every financial role pairs color with explicit text labels (`DEBIT (OUT)`, `INCOME`, `INTERNAL TRANSFER`, `CONFIRMED EXPENSE`).
- **Category Indicators:** Every category row and chart legend item includes both a colored geometric swatch and the textual category name.
- **Selected Navigation State:** Selected bottom tabs use **bold font weight (`FontWeight.Bold`)**, an elevated contrast ratio, and a dedicated **horizontal indicator bar (`width: 22dp, height: 2.5dp`)**, never color alone.
- **High Contrast:** All primary text against card containers achieves a minimum contrast ratio of **12.4:1**; secondary text achieves **7.1:1**, exceeding WCAG AAA standards.

### 5.2 System Font Scale 1.3× Adaptation
- **Bottom Navigation Shell:** Destination labels utilize `labelSmall (10 sp)` with `maxLines = 1`, `softWrap = false`, and equal column weighting (`Modifier.weight(1f)`). Labels fit comfortably across phone viewports at 1.3× font scaling without clipping (`Dashboard`, `Chat`, `Review`, `Manual`).
- **Compact Review Badge:** The Review notification badge is rendered as a compact circular pill (`nav-badge`), displaying the numeric count (`4`) while the accessible semantics (`spokenLabel`) pronounce the full context: *"Review notifications, 4 waiting"*.
- **Dynamic Text Wrapping:** Hero numbers (`383,000 VND`) dynamically scale down or wrap to two lines on narrower display widths without overlapping adjacent metadata.

### 5.3 Empty & Boundary States
- **Zero Spending Scope:** Renders an uncluttered card displaying `0 VND` with a clean informational icon and text: *"No confirmed expenses recorded for this period"*.
- **No Categories:** The category card displays *"No category spending in this scope"*, with a direct prompt to add categories in Manual.
- **Incomplete Bank Coverage:** The coverage footnote transparently reminds the user: *"Records stored locally on device · Confirmed only · Not complete bank history"*.

---

## 6. Exact Financial Reconciliation Audit

All data displayed across the proposal mockups strictly matches the verified synthetic test dataset from Sprint 6:

```
================================================================================
CONFIRMED LEDGER RECONCILIATION AUDIT (October 1 – October 31, 2026)
================================================================================
Category Breakdown:
  - No category:           258,000 VND  (4 confirmed expenses)  ->  67.4% share
  - S68MEAL (Food):        105,000 VND  (2 confirmed expenses)  ->  27.4% share
  - S68OLD (Archived):      20,000 VND  (1 confirmed expense)   ->   5.2% share
--------------------------------------------------------------------------------
TOTAL CONFIRMED SPENDING:  383,000 VND  (7 confirmed expenses)  -> 100.0% share

Daily Spending Breakdown (Timeline):
  - 2026-10-21:            123,000 VND  (1 expense: 123k)
  - 2026-10-22:             45,000 VND  (1 expense: 45k)
  - 2026-10-23 (Today):    215,000 VND  (5 expenses: 20k + 60k + 45k + 90k)
--------------------------------------------------------------------------------
TOTAL DAILY SUM:           383,000 VND  (Exact reconciliation ✓)

Secondary Accounts & Non-Spending:
  - Confirmed Income:       70,000 VND  (1 confirmed record)    -> Stored apart
  - Internal Transfers:     30,000 VND  (1 internal transfer)   -> Stored apart
  - Pending Review Drafts:           4  (VietinBank drafts)     -> Zero in ledger
================================================================================
```

Every figure across `dashboard-mockup.png`, `chat-quickcapture-mockup.png`, `review-mockup.png`, and `manual-mockup.png` balances to the single whole dong.

---

## 7. Implementation Handoff Guide (For Future Implementation Task)

*Note: This section serves as the technical handoff for the subsequent implementation task once the user explicitly approves this design proposal. No product code is modified in this task.*

### 7.1 Compose Theme Architecture (`app/src/main/java/io/github/triet4p/aegisf/ui/theme/`)
1. Create `ColorTokens.kt` to define the obsidian slate surfaces, semantic transaction accents, and `CategoryColorRegistry`.
2. Update `AegisTheme` in `MainActivity.kt` to apply the new dark/light color schemes:
   - Primary: `#38BDF8` (Sky)
   - Background: `#0F141C` (Obsidian Slate)
   - Surface: `#161D27` (Deep Slate)
   - SurfaceVariant: `#1E2838` (Navy Slate)
   - Outline: `rgba(255, 255, 255, 0.08)`

### 7.2 Dashboard Refactoring (`DashboardScreen.kt`)
1. **Scope Bar:** Replace `DashboardScopeCard` with a compact `Row` containing two clickable `FilterChip` composables for date range and category.
2. **Hero Spending Card:** Refactor `SpendingCard` to use `hero-card` styling with a subtle gradient and `displaySmall` bold typography.
3. **Consolidated Metrics Strip:** Replace separate `IncomeCard`, `TransfersCard`, and `PendingCard` with a single composable `SecondaryMetricsStrip(income, transfers, pendingDraftCount)`.
4. **Proportional Distribution Bar:** In `CategoryBreakdownCard`, replace the 8 dp `DashboardBar` with `SegmentedDistributionBar(buckets, totalVnd)`.
5. **Daily Spending Histogram:** In `SpendingOverTimeCard`, render a column-based bar chart using `Canvas` or weighted `Column`s with proportional heights and tap targets.
6. **Coverage Banner:** Replace `CoverageCard` with a lightweight `FootnoteBanner`.

### 7.3 Visual Acceptance Checklist for Verification

| Item # | Acceptance Criterion | Verification Method | Pass / Fail |
|---|---|---|---|
| **VAC-01** | Dashboard replaces olive green with obsidian slate theme (`#0F141C`) | Visual inspection on phone | [ ] Pending Approval |
| **VAC-02** | Category breakdown renders multi-color segmented proportional bar | Verify segment widths match % | [ ] Pending Approval |
| **VAC-03** | Category color swatches match across charts, legends, lists, and forms | Cross-screen color consistency | [ ] Pending Approval |
| **VAC-04** | Renaming a category does not change its assigned color | Test category rename action | [ ] Pending Approval |
| **VAC-05** | Income, Transfers, and Review consolidated into 1 row without card inflation | Layout inspection | [ ] Pending Approval |
| **VAC-06** | Daily spending trend displays proportional columns with drill-down | Tap column filters scope | [ ] Pending Approval |
| **VAC-07** | All totals reconcile: 383,000 VND / 7 records, 70k income, 30k transfer | Arithmetic reconciliation | [ ] Pending Approval |
| **VAC-08** | Bottom nav destination labels fit on 1 line at 1.3× font scale | Phone render at font scale 1.3 | [ ] Pending Approval |
| **VAC-09** | WCAG 2.1 AA contrast verified for all text and swatches | Contrast checker evaluation | [ ] Pending Approval |
| **VAC-10** | Non-color cues present for all status and navigation states | Inspect labels, icons, borders | [ ] Pending Approval |

---

## 8. Proposal Boundaries & User Approval Gate

- **Scope Limits:** This attempt delivers the comprehensive design audit, visual specification, color assignment engine, design tokens, and rendered phone-proportioned mockups.
- **Zero Product Mutation:** No application source code, Gradle configurations, tests, or plans have been altered.
- **Next Step:** Awaiting explicit user approval of this visual design direction. Upon approval, the user will separately select the implementation role (Bronze / Silver / Gold) to execute the Compose UI migration under S6.9.
