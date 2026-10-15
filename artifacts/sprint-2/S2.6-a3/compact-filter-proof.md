# S2.6 compact collapsible-filter proof

- Task: `S2.6` (collapsible-filter UI correction) / Attempt: `S2.6-a3` / Agent: `Sprint2FilterToggleSilver` / Role: Silver (user-selected for this correction), light route.
- Route and gates (as assigned): bounded implementation, one focused runtime smoke on the real production launcher UI on a phone-sized viewport, scoped self-review and cleanup, cleaned `:app:assembleDebug`, then Main's compact acceptance and the user-only physical UI check. No independent reviewer, full-route checkpoint, or deep review is assigned.
- Repository / worktree / Git directory: `F:/ai-ml/aegis-f` / `F:/ai-ml/aegis-f/.git`; published baseline `154d34c6f11836a9951f8afffd885c6b004e4e57` on `main`. **No staging, commit, push, reset, or history mutation was performed**, and no `adb`/emulator/device command was run. Physical acceptance is not claimed.
- Quota/cost observation: unknown (not exposed to the worker).

## Predecessor references (all directly available; none blocking)

- Prior completed attempt `S2.6-a1` / agent `Sprint2BrowseFilters` (Gold) — artifact `artifacts/sprint-2/S2.6-a1/browse-filter-proof.md`, history `history://Sprint2BrowseFilters`. Read directly: the browsing contract, the type/category/date semantics, the user-run checklist, and the recorded host limits (second Room connection, forced layout, tall Robolectric viewport) were all consumed as pointers; nothing of that contract was re-litigated.
- Cancelled attempt `S2.6-a2` / agent `Sprint2CompactFilters` (Gold) — history `history://Sprint2CompactFilters`. **Verified before editing: it made zero product changes.** `grep -c "→ write\|→ edit" history://Sprint2CompactFilters` → `0`, and its last recorded tool call is a read. This matches the user's report and the scoped `git status`/source review of the two target files.
- Requested handoff `artifacts/sprint-2/handoffs/S2.6-a2.md` **does not exist** (`ls artifacts/sprint-2/handoffs/` → `S2.1-a1.md`, `S2.4-a2.md`). The user's immediate-kill override and the confirmed `proc://Sprint2CompactFilters/kill` cancellation supersede that wait; absence was not a blocker.

## Result

The filters are now a collapsed-by-default section of the Saved transactions screen: one compact line carrying a **Show filters** / **Hide filters** toggle and the active-dimension count, with no title row, no second header line, and no Apply button. The controls keep their accepted S2.6 semantics unchanged (type chips, category selector over archived categories, inclusive From/Through calendar days, per-field and reversed-range refusals, **Clear all**, no-match recovery); the toggle only shows and hides them.

Measured on the smoke's phone-sized viewport (411x891dp at density 2.0): the results list occupies 778dp of the 891dp screen (share **0.873**, list top 113dp) while collapsed, against 389dp with the panel open. There is no fixed-height cap: the collapsed list simply takes all the height the one-line toggle leaves.

## Changed paths and contract

- `app/src/main/java/io/github/triet4p/aegisf/ui/TransactionListScreen.kt`
  - New private `FilterToggleRow(expanded, activeDimensionCount, onExpandedChange)`: a single `Row` with a `TextButton` labelled `Show filters` / `Hide filters` and a trailing `N active filter(s)` label that is absent at zero. The row is the only always-visible filter chrome; its height is the button's own accessible touch target.
  - New private `activeFilterCountLabel(count)`: `1 active filter` / `N active filters` (nothing below one is rendered).
  - The screen body now renders `FilterToggleRow` unconditionally, and `FilterPanel` only while `filtersExpanded`; the panel receives `Modifier.weight(1f, fill = false)` and the results keep `Modifier.weight(1f)`, so an open panel takes what it needs up to half the leftover height and can never squeeze the results to zero. The `HorizontalDivider`, `EmptyLedger`, `NoMatchingTransactions`, and `TransactionList` branches are unchanged.
  - `FilterPanel` is now the scrollable body only (`verticalScroll(rememberScrollState())`), so every control stays reachable on a short screen; **Clear all** moved into its first row (right-aligned, still `enabled = filters.isActive`) because a collapsed panel has nothing to clear. The old `Filters` title row is gone. All existing controls, messages, and the category dialog are byte-for-byte the same otherwise.
  - New `filtersExpanded: Boolean` / `onFiltersExpandedChange: (Boolean) -> Unit` parameters, documented in the screen KDoc together with the collapse behaviour.
- `app/src/main/java/io/github/triet4p/aegisf/MainActivity.kt`
  - `var transactionFiltersExpanded by rememberSaveable { mutableStateOf(false) }` hoisted beside `transactionFilters` (the existing pattern), passed to `TransactionListScreen`. It is the only added state; navigation, repository wiring, and every other destination are untouched, and nothing is written to disk.
- `app/src/main/java/io/github/triet4p/aegisf/ui/TransactionFilter.kt`
  - New derived `TransactionFilterState.activeDimensionCount` (type + category + From + Through, each 0/1) with `isActive` now expressed as `activeDimensionCount > 0`; one source of truth, no new filter semantics. A typed-but-refused date still counts as set, so the count never hides input the user can still see and correct.
- `docs/developer-setup.md` — §5 browsing bullet rewritten for the collapsed default (toggle, count, reachable controls, state survival) and a short §3 note recording the correction, the throwaway smoke, and the evidence link. No other doc/plan edit.
- `C:/Users/admin/.agents/memory/lessons-learned.md` (outside the repository) — two appended entries: the chip-label-versus-row-text matcher trap, and the measured `weight(1f, fill = false)` collapsible-panel split.

Untouched, by explicit scope: no `onFiltersChange` call, reset, or database/repository write is added by the toggle; no Room schema/entity/DAO/VERSION change (so the exported schema is unchanged); no date logic, editor/detail/category screen, build configuration, dependency, or S2.7 work.

## Verification (observed; exact commands and results)

Environment per `docs/developer-setup.md`: `JAVA_HOME=C:/Program Files/Java/jdk-21`, `ANDROID_HOME=C:/Users/admin/AppData/Local/Android/Sdk`, commands run from `F:/ai-ml/aegis-f` with `--console=plain`.

1. Production compile after the edit — `.\gradlew.bat :app:compileDebugKotlin --console=plain` → **BUILD SUCCESSFUL in 15s** (2 tasks executed).
2. Temporary host runtime smoke on the real launcher (test file and its dependencies removed after the run) — `.\gradlew.bat :app:testDebugUnitTest --tests "io.github.triet4p.aegisf.ui.TransactionFilterToggleSmokeTest" --console=plain` → **BUILD SUCCESSFUL in 1m 2s**; JUnit XML `tests="1" skipped="0" failures="0" errors="0" time="51.331"`. It launched the production `MainActivity` under Robolectric at `@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")` (411x891dp, density 2.0) over the real file-backed database, seeded through the production `AegisApplication.container.ledgerRepository` (so the Activity's own single connection is the one that reads), and drove production Compose semantics. Observed stdout:
   - `SMOKE seeded moments today=1792040400000 tomorrow=1792116000000 yesterday=1791939600000`
   - `SMOKE screen=Rect.fromLTRB(0.0, 0.0, 822.0, 1782.0) density=2.0`
   - `SMOKE collapsed: listTop=113.0dp listHeight=778.0dp screen=891.0dp share=0.8731762`
   - `SMOKE expanded: panelHeight=389.0dp listHeight=389.0dp listTop=502.0dp`
   Asserted, in order: (1) on entry only `Show filters` exists — `Hide filters`, `Clear all`, `Type`, `Category`, `From`, `Through` and any `active filter` label all report *does not exist*, and the rows are exactly `[Lunch, Salary, Market, Move]`; (2) collapsed list height / screen height `0.873 > 0.8` (the results own nearly the whole phone height); (3) after tapping `Show filters`, `Hide filters` is displayed and **each** of `Clear all`, `Type`, `Category`, `From`, `Through` can be scrolled into view and is displayed inside the panel, while panel height and list height are both `> 0` and the list is shorter than collapsed; (4) tapping the `Expense` chip and typing the refused date `2026-02-30` shows `Enter the date as YYYY-MM-DD, for example 2026-10-15.` and `2 active filters`, with rows narrowed to `[Lunch, Market]`; (5) `Hide filters` removes the controls while the count and the same rows stay; (6) `Show filters` restores the typed text `2026-02-30`, its message, the selected `Expense` chip, and the same rows; (7) opening `Lunch`, returning with `Back` keeps the expanded panel, the typed date and the rows; (8) `activityRule.scenario.recreate()` still lands on the list, still expanded, with the typed date and the same rows. The first smoke invocation failed before these steps on an ambiguous `onNodeWithText("Expense")` (the row type text matches too); the selector was fixed to the chip's `Role = Checkbox` semantics and the run then passed end to end — no production defect was involved.
3. Focused permanent suite for the file whose contract changed (deliberately not the full 47-test suite) — `.\gradlew.bat :app:testDebugUnitTest --tests "io.github.triet4p.aegisf.ui.TransactionFilterTest" :app:assembleDebug --console=plain` → **BUILD SUCCESSFUL in 45s**; `TransactionFilterTest` `tests="13" skipped="0" failures="0" errors="0" time="12.686". No permanent test was added for the new UI wiring, by Main's explicit instruction.
4. Cleanup verified — the smoke file is gone (`find app/src/test -name "*.kt"` → the five permanent test files only); `app/build.gradle.kts` sha256 `1f4c46d9b63fcaa4dcf5b9cfa9ee47725541e026023f39fbe1ffcd868b054179` equals its value before this attempt, and `grep "TEMPORARY\|ui-test"` finds nothing (the two temporary Compose test dependencies were removed).
5. APK (built after the cleanup) — `app/build/outputs/apk/debug/app-debug.apk`, **18,782,475 bytes**, mtime `2026-10-15 15:20:41 +0700`, sha256 `38a80c0fc9dffd8ffce4f8b48feecfe6ff6c0c0be80d98311eb5ad2f08d437de`. `output-metadata.json`: `versionCode 4`, `versionName 1.0`. `apksigner verify --print-certs` → `C=US, O=Android, CN=Android Debug`, the documented preserved debug certificate SHA-256 `891542fd7a27bdaff367af863d28ac0babeb4dfd2c394e340348534a797ff41b` (same signer as recorded since S2.1).
6. The APK is this build, not a stale artifact — its `classes6.dex` contains exactly the new strings (`Show filters` ×1, `Hide filters` ×1, `active filter` ×2 — the two label variants, `Clear all` ×1), the archive holds no `ui-test` entries (121 entries total), and `zipalign`-independent size equality with the S2.6-a1 record is coincidental, not a reused file.

## Honest limits (UNPROVEN here; user-only or not exercised)

- Robolectric proves layout geometry and semantics, **not pixels**: on-device typography, touch-target feel, insets, dark theme, and font-scale rendering are not proven here, exactly as recorded for S2.1–S2.5. The visual gate stays with the user.
- Step 8's `scenario.recreate()` proves the saveable path in-process; a real process death (app closed from recents) and the way back from the detail/editor screens on a physical device are user-run.
- One 411x891dp viewport was measured. Very short devices, tablets, and extreme font scales were not exercised; the split is a proportional layout guarantee, not a per-device pixel guarantee.
- The expansion flag and the filter values are deliberately not persisted to disk; nothing here proves behaviour across a cold app start beyond the saveable-state restore above.
- No independent reviewer, evidence gate, or full-route checkpoint applies to this light-route attempt; nothing is committed.

## USER-RUN UI checks (user-only; 4 checks, no worker device access)

Install the cleaned build exactly as documented for the preserved signer (`adb -d install -r 'F:\ai-ml\aegis-f\app\build\outputs\apk\debug\app-debug.apk'`, then open the app), then:

1. **Collapsed by default and more room.** Open **Saved transactions**: no filter control and no `Filters` title are visible; a single **Show filters** line sits above the list with no count, and substantially more records are visible at once than before (previously the panel left only about one card of list height).
2. **Show, filter, and count.** Tap **Show filters**: the panel opens with the same controls as before (Type chips, Category, From/Through, **Clear all**) and both the panel and the list still scroll. Tap **Expense** and pick a category, then type an invalid From date such as `2026-02-30`: its field message shows, the other dimensions still narrow, and the count on the toggle line reads `3 active filters` (type + category + From).
3. **Hide and hide/re-show without loss.** Tap **Hide filters**: the controls disappear, the count stays, and the visible rows are exactly the ones from the previous step. Tap **Show filters** again: the chip is still selected, the typed `2026-02-30` text and its message are back, and the same rows show.
4. **State survives a round trip and a config change.** With the panel open and those values set, open a record and press **Back**, then rotate the phone (or toggle a system setting that recreates the activity): the list comes back with the panel still open and the values intact. Then repeat with dark theme and a large system font: nothing is cramped or truncated, the panel's controls and the list both scroll, and nothing is cut off by the status/navigation bars.

Report the actual visual observations to Main. Neither the host build nor this checklist is device acceptance.

## Decisions

- The open state is hoisted beside the filter values as a saveable primitive rather than kept inside the screen: the list screen leaves composition when a record's detail or editor opens, so only a hoisted (or otherwise saved) flag can survive that round trip and a configuration change — the same reason `filters` is hoisted.
- Collapsing is pure view state: the toggle never calls `onFiltersChange`, never resets a dimension, and never writes; the selections and the typed date texts (including a refused one) live in `filters`, so the requirement that a re-expanded panel show exactly what was left is structural rather than replicated.
- `Clear all` moved inside the open panel instead of staying on the always-visible line: it is meaningless while the controls are collapsed, and keeping it out of the toggle row keeps that row to one short line at any font scale.
- The open panel is capped at half the leftover height with `Modifier.weight(1f, fill = false)` and its body scrolls, so "expanded controls/results still scroll/reachable" holds even on a short screen — verified at 389dp (panel) / 389dp (list). This is a share of available space, not a new fixed-height cap on the collapsed results.
- The active count is derived on `TransactionFilterState` next to the existing `isActive` rather than recomputed in the composable, keeping one definition of "a dimension is set".

## Unresolved limits

- Device-level visual/functional verification of the collapsed panel is UNPROVEN on this host; the four numbered user checks above are the gate, and no PASS is claimed for them.
- No staging, commit, or push was performed; the S2.6 work remains uncommitted on the working tree by assignment. No S2.7 work was started.
