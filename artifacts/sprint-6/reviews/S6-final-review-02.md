# Sprint 6 End-of-Sprint Differential Review (S6.9 delta)

- **Task ID:** `S6-final`
- **Attempt ID:** `S6-final-review-02`
- **Agent ID:** `S6RedesignDifferential`
- **Sprint:** 6 — `docs/sprint-plans/sprint-6.md`
- **Route:** Light. All assigned gates PASS as established ground: S6.1–S6.8 evidence gates, prior differential `S6-final-review-01`, S6.9 designer visual gate `S6.9-visual-01.md` (0 findings), S6.9 independent evidence gate `S6.9-review-01.md` (0 findings). **No checkpoint assigned or present — none required or consulted.**
- **Scope:** differential review of the S6.9 delta and its cross-task seams only; no passed gate/check was re-run, no build/test/phone operation executed, nothing staged/committed/pushed, product/plan/worker artifacts untouched. Only this report written.
- **Verdict:** **FAIL** (one HIGH actionable finding; no other blockers)
- **Confidence:** 0.83
- **Advance:** `false`
- **Report path:** `artifacts/sprint-6/reviews/S6-final-review-02.md`

## Inputs read

- Sprint plan (full), worker `artifacts/sprint-6/workers/S6.9-impl-02.md` (full), approved design `artifacts/sprint-6/design/S6.9-design-02/design-proposal.md` (full), `S6.9-visual-01.md` (full), `S6.9-review-01.md` (full), prior differential `S6-final-review-01.md` (full).
- Targeted source reads (novel probes only): `ui/DashboardScope.kt` (full), `ui/DashboardScreen.kt` (compact-label, dialog write path, breakdown/daily chart guards, drill labels), `ui/CategoryPalette.kt` (full), `ui/ManualDateTime.kt` (grammar/parse), `MainActivity.kt` (theme), `data/WorkflowCommandService.kt:2169-2213` (seam re-read only for delta confirmation).

## Actionable findings

### AF-1 — HIGH: Dashboard crashes on any single-digit-day date the documented grammar explicitly accepts (S6.9 compact label × S6.2/S6.7 lenient date grammar)

- **Claim:** The new S6.9 compact date-range control label throws `StringIndexOutOfBoundsException` during composition whenever the scope holds a valid, usable range whose From or Through text has a single-digit day (or single-digit month), crashing the primary Dashboard screen from a supported user input.
- **Evidence:**
  - The grammar deliberately accepts non-padded dates: `ui/DashboardScope.kt:100-108` documents "The date grammar accepts a single-digit month or day, so `2026-10-3` … naming an earlier day", the closed S6.2/S6.7 carry-forward behavior. `ManualDateTime.matchesGrammar` (`ui/ManualDateTime.kt:195-199`) parses with non-lenient `SimpleDateFormat("yyyy-MM-dd")`, which accepts `2026-10-3` (9 chars, parse position == length, calendar-valid), so `resolveDashboardScope` returns `range != null` — a usable scope.
  - The dialog stores the raw typed text without canonicalization: `ui/DashboardScreen.kt:386-389` (`onValueChange = { onScopeChange(scope.copy(fromText = it)) }`); same for Through.
  - The new compact label computes fixed offsets from that raw text: `ui/DashboardScreen.kt:269-273` — same-month branch executes `scope.fromText.substring(5, 7)` and `substring(8, 10)`. On `"2026-10-3"` (length 9), `substring(8, 10)` has endIndex > length and throws `StringIndexOutOfBoundsException` at composition time; single-digit month `"2026-1-05"` (length 9) throws identically. The `remember` block re-evaluates on every scope-text keystroke, so typing or autotext-trimming one character in the From/Through field crashes the app; no handler catches it.
  - Reachable in one keystroke from the evidenced default month: From `2026-10-31` → backspace `0` → `2026-10-3` with Through still `2026-10-31` is a valid earlier range → crash. Phone evidence used only padded fixture dates (`10/01–31`), so neither the visual nor the evidence gate could observe this.
- **Why it matters:** crashes the primary screen on an input path the sprint explicitly supports and tests (`DashboardScopeTest` covers the resolver, but the label lives in UI code with no unit coverage); violates the sprint's exactness/robustness contract for the redesigned Dashboard ("readable money", robust scopes, and the whole reason the S6.9 fix for the compact label was made).
- **Correction contract (Main assigns; this review fixes nothing):** the compact label must never throw for any text the grammar accepts — derive the compact label from the resolved canonical day texts (e.g. format the resolved range's device-local days via `ManualDateTime`/`dateFieldText`, or pad/canonicalize before substring), not from fixed offsets into raw user text. Regression test: compact label is computable for `2026-10-3` through `2026-10-31` (and `2026-1-05` variants) without an exception, label still fits at 1.3×, helper still shows the full ISO range. A re-review of the corrected task is then required per plan before the consolidated user pass.
- **Confidence:** 0.92 (full chain read in source; behavior follows from `SimpleDateFormat` numeric parsing, corroborated by the code's own doc comment; not phone-reproduced — read-only review).
- **QA:** consulted `DashboardScope.kt` (full), `ManualDateTime.kt` (grammar), `DashboardScreen.kt` (dialog + label); open question: whether the through-text alone can also hit the throw without from-text (yes — same expression on `throughText.substring(8, 10)`, line 271; noted, same fix). No phone reproduction was performed (read-only constraint; trivially reproducible by Main's worker with `adb` on the isolated package).

## Verified (novel probes dismissed — with reasons)

- **Cross-year compact-range ambiguity:** same-month → `MM/DD–DD`; cross-month/cross-year → full `MM-DD–MM-DD` without year (`DashboardScreen.kt:267-273`). The full-ISO helper `Inclusive device-local days · ${resolved.label}.` is rendered unconditionally under the buttons (`DashboardScreen.kt:352-357`), so no ambiguity is left unread. INFO only; no action.
- **Zero/overflow/many-category bar proportions:** zero buckets → explicit "No category has spending in this scope."; zero total → "Share unavailable" instead of a divide-by-zero (`DashboardScreen.kt:692-757`); overflow → chart hidden with a lower-bound explanation, text list forced (`:713-728`); daily chart forces the exact text list when any day overflows (`:889-920`); `dashboardBarFraction` clamps non-positive inputs (`DashboardScope.kt:196-199`). 10+ categories evidenced in attempt01; 8-slot hue collisions are by-design (name+amount always textual).
- **Deleted/archived-category retained scope and drill:** `Unknown category` null-fallthrough preserved post-rewrite (`DashboardScreen.kt:736-739`, `DashboardScope.kt:177-183`); archived stays selectable with textual `(archived)` label + unconditional `[ARCHIVED]` badge; hue keyed by stored ID via `Math.floorMod` (`CategoryPalette.kt:48`), never name.
- **Hidden-metadata accessibility:** swatches are decorative 12 dp boxes with no semantics (correct — every instance pairs text name/amount/count/share); distribution bar carries full `contentDescription` summary (`DashboardScreen.kt:842-845`); drills use `Role.Button` + explicit `onClickLabel`. No information conveyed by hue alone.
- **Global theme/style spillover:** `AegisTheme` (`MainActivity.kt:113-155`) defines theme-wide roles + 2–4 dp shapes; no hardcoded colors outside `CategoryPalette.kt`/`DashboardScreen.kt` and no leftover S6.8 olive tokens anywhere in `ui/` (grep), so secondary screens (TransactionDetail, LayaProvisioning, Settings) inherit coherent tokens; theme and swatches both use `isSystemInDarkTheme()`, so they cannot disagree.
- **Data-layer/presentation leakage:** grep over `data/` finds no `CategoryPalette`/`Color(0x…`/`isSystemInDarkTheme` reference — presentation stayed in `ui/`; the S6.9 hash manifest lists only UI + README/CHANGELOG/build.gradle files, and `s69impl01`/receiver/suffix scaffolding is gone (per evidence gate, hash-verified).
- **Key seam preserved after S6.9 edits:** `confirmationBinding` still binds draft revision/status, fields, category `id:archived:updatedAt`, and every accepted link's `eventId:revision:resolvedTransactionId` (`WorkflowCommandService.kt:2169-2213`) — stale preview withholding and idempotent confirms stand; Manual prefill (`TransactionEditorScreen.kt:297-299`) and event provenance paths untouched.
- **Fixture separation:** worker artifact + `results.json` keep S6.8 383,000/7 (reference), S6.9 stress 699,000/16, design mockup pending-4 (synthetic-only), and final 834,000/19 + 70,000/1 + 30,000/1 + pending 0 explicitly apart; no equivalence claim found anywhere in the reviewed reports.

## Unverified / limits (nonblocking)

- AF-1 is source-derived, not phone-reproduced (read-only review; reproduction requires the corrected build anyway).
- The prior differential's bounded interpretation-text-only provenance residual (event-text edit between preview and confirm does not change `confirmationBinding`) remains the documented Sprint 7 note — not re-raised here; no S6.9 change touched it.
- Actual-phone user visual acceptance of S6.9 remains pending regardless of this verdict; AF-1 must be fixed and re-gated before that pass.

## Verdict

**FAIL — advance: false.** One HIGH actionable finding (AF-1: composition crash on supported non-padded dates in the new S6.9 compact range label). All other delta-focus probes dismissed with source evidence. Main should assign the AF-1 correction to a fresh worker, obtain a fresh task-level evidence PASS for the corrected S6.9 portion, and then rerun the required end-of-sprint differential review; the consolidated user phone acceptance remains pending afterward.
