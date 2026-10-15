# S2.6 browsing and filters proof

- Task: `S2.6` / Attempt: `S2.6-a1` / Agent: `Sprint2BrowseFilters` / Role: Gold (user-selected), light route.
- Route and gates (as assigned): focused real-behavior host runtime smoke on the real production launcher UI plus real Room filtering/observation, relevant permanent regression suite, scoped worker review, cleaned `:app:assembleDebug`, then Main's compact acceptance and the user-only physical UI gate. No independent reviewer, full-route checkpoint, deep review, or commit is assigned.
- Repository / worktree / Git directory: `F:/ai-ml/aegis-f` / `F:/ai-ml/aegis-f/.git`; published baseline `154d34c6f11836a9951f8afffd885c6b004e4e57` on `main`. **No staging, commit, push, reset, or history mutation was performed**, and no `adb`/emulator/device command was run (user-only surfaces throughout).
- Predecessor evidence acknowledged (pointers only): `artifacts/sprint-2/S2.5-a1/edit-delete-proof.md`, `artifacts/sprint-2/S2.2-a1/vnd-proof.md`; active plan `docs/sprint-plans/sprint-2.md`. S2.1–S2.5 acceptance was not re-tested.
- Quota/cost observation: unknown (not exposed to the worker).

## Result

The existing Saved transactions surface is now the single browsing surface: the same
newest-first list (newest occurred-at first, stored id tie-breaker, exact whole-VND and
device-local moments via the current helpers, no product cap) narrowed by combined
type/category/date filters, with row tap still opening the S2.5 detail view where edit
and delete keep working. Category, type, and date dimensions combine as AND; the default
state returns the full list, and clearing every dimension returns it too.

## Changed paths and contract

Added:

- `app/src/main/java/io/github/triet4p/aegisf/ui/TransactionFilter.kt` — `TransactionFilterState(type, category, fromText, throughText)`, `CategoryFilter` (`All` / `Uncategorized` / `Specific(categoryId)` by stable database id, never by name), `EpochRange` (half-open), `resolveDateFilter` (strict per-field `ManualDateTime.dateError`, `From <= Through` text compare, empty ends open, refused dimension yields a null range plus its message and is never applied), `applyTransactionFilters` (AND across constrained dimensions, input order preserved, no re-sort/cap/fabrication).
- `app/src/test/java/io/github/triet4p/aegisf/ui/TransactionFilterTest.kt` — 13 permanent tests (below).

Modified:

- `ui/ManualDateTime.kt` — `dayStartEpochMillis` (local midnight at the start of the named device-local day) and `dayAfterStartEpochMillis` (one `Calendar.add(DATE, 1)` day later in the same zone, not a fixed 24h), plus the filter date-convention KDoc. minSdk 24 `java.util` facilities only; no `java.time`, no desugaring, no new dependency.
- `data/TransactionDao.kt` — `observeAll()` (same newest-first ordering as `findAll`, as a live Room stream).
- `data/LedgerRepository.kt` — `observeTransactions()` (live stream; filters narrow, never write). One-shot `transactions()` kept for tests and the final verification read.
- `ui/TransactionListScreen.kt` — rewritten on the hoisted live transaction + category flows (stable identities, collected once per screen per the S2.3 decision): filter panel (type chips as wrapping `FilterChip`s, category dialog over all stored categories including archived ones marked archived with separate All/No-category choices, From/Through fields with per-field and reversed-range messages), distinct `No transactions yet` vs `No matching transactions` (+ `Clear filters` recovery), loading state, unchanged row layout/navigation. No composition writes: date resolution and row narrowing are pure `remember` derivations.
- `MainActivity.kt` — the filter state is hoisted beside the navigation state (`rememberSaveable` primitives), so detail/editor round-trips retain the filters; list signature gains `filters`/`onFiltersChange`. Navigation model, edit/delete contracts, and selector geometry untouched.
- `app/src/test/java/io/github/triet4p/aegisf/data/LedgerDatabasePersistenceTest.kt` — one new permanent test: `transactions are observed newest first without re-querying` (tie order, correction re-emit in place, deletion re-emit, close/reopen).
- `docs/developer-setup.md` — filter/date semantics in §3 and the S2.6 user-run checks in §5.

Not changed: Room schema/`app/schemas`, `VndMoney`, editor/detail/category screens, `AegisApplication`/`AppContainer`, build configuration (byte-identical after throwaway cleanup), plan files. No `CHANGELOG.md` exists in this repository, and S2.1–S2.5 kept the same practice, so none was created.

## Date convention (documented in code and in developer-setup.md)

- From/Through are inclusive device-local calendar days (`YYYY-MM-DD`), either end optionally empty (open end).
- Converted to the half-open epoch range `[startOf(From), startOf(dayAfter(Through)))`, where `startOf` is local midnight in the device's current default zone — the same zone `ManualDateTime` writes records in — and the day after is one calendar day later in that zone. Never guessed UTC midnight, never a fixed 24h addition: a 23-hour daylight-saving start day and a 25-hour end day contribute their actual length.
- A malformed end or a From-after-Through pair is refused with its message; the refused date dimension is treated as open while the other dimensions still narrow. Nothing crashes, nothing silently broadens.

## Verification (observed)

Environment per `docs/developer-setup.md`: `JAVA_HOME=C:/Program Files/Java/jdk-21`, `ANDROID_HOME=C:/Users/admin/AppData/Local/Android/Sdk`, commands from `F:/ai-ml/aegis-f` with `--console=plain`.

1. Permanent suite on the cleaned tree — `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain` → **BUILD SUCCESSFUL** (47 actionable tasks: 10 executed, 37 up-to-date). JUnit XML: `LedgerDatabasePersistenceTest` 11/0/0, `LedgerRepositoryRejectionTest` 12/0/0, `VndMoneyTest` 6/0/0, `ManualDateTimeTest` 5/0/0, `TransactionFilterTest` 13/0/0 → **47 tests, 0 failures, 0 errors, 0 skipped** (was 33 before this task; 14 added: 13 filter + 1 observation).
2. New permanent coverage worth naming: combined type+category+date AND with one-dimension widening; All vs Uncategorized vs Specific(archived id) vs unknown id (matches nothing, never everything); clear-all returns stored order; equal occurred-at keeps stored-id tie-breaker under filters; single-Through-day first-minute/last-minute boundaries; inclusive From/Through pair with adjacent-day exclusion; open From / open Through / both-empty-is-full-list; malformed (`2026-02-30`) and reversed (incl. adjacent-day) refusals with null range; New York DST start day = 23h and end day = 25h; late-local UTC+7 record on its own day; live transaction stream ordering/correction/deletion/close-reopen.
3. Throwaway host runtime smoke on the real launcher Activity (removed after the run) — `.\gradlew.bat :app:testDebugUnitTest --tests 'io.github.triet4p.aegisf.ui.TransactionBrowseFilterSmokeTest' --console=plain` → **BUILD SUCCESSFUL**; `tests="1" skipped="0" failures="0" errors="0"` (21.4s). It launched the production `MainActivity`, seeded the real database file through production `LedgerDatabase.build` + `LedgerRepository`, and drove the production filter UI through production Compose semantics (tall Robolectric viewport). Observed rows (JUnit XML `system-out`):
   - Baseline list shows all four seeded rows: Market (16th), the 15th tie (Salary id 2, Lunch id 1), Move (14th).
   - Type Expense keeps Lunch + Market, drops Salary/Move; + category Food keeps only Lunch; + Through `2026-10-15` keeps Lunch (Through boundary inclusive).
   - Only-From `2026-10-16` drops Lunch and keeps Market (From boundary inclusive).
   - `Clear all` / `Clear filters` each return the full list (Lunch + Salary visible).
   - Reversed From `2026-10-16`/Through `2026-10-15` shows `The From date is after the Through date.` while Expense still narrows (Market stays, Lunch stays out) — refusal, not silent broadening and not a no-match.
   - Malformed From `2026-02-30` shows its field message with the Food pair still narrowed to Lunch + Salary.
   - Production edit proven by repository read: `SMOKE edited amount=125000 moment=1792126800000`; production delete proven by repository read: `SMOKE market present after delete=false`; final fresh connection: `SMOKE rows: [1:125000, 2:1000000, 4:500000]`, `SMOKE row count: 3`, `SMOKE lunch amount: 125000`, `SMOKE market present: false`, `SMOKE food archived: true`, `SMOKE travel name: Journeys`.
4. Cleaned tree — `app/build.gradle.kts` verified byte-identical to its pre-attempt content (no `TEMPORARY` / `ui-test-junit4` / `ui-test-manifest` text remains); the smoke file is gone (`app/src/test/.../ui/` holds only `TransactionFilterTest.kt` and `ManualDateTimeTest.kt`).
5. APK — `app/build/outputs/apk/debug/app-debug.apk`, **18,782,475 bytes**; output metadata: `versionCode='4' versionName='1.0'`; `apksigner verify --print-certs` reports the documented, preserved debug certificate SHA-256 `891542fd7a27bdaff367af863d28ac0babeb4dfd2c394e340348534a797ff41b` (v2 scheme verified, 1 signer, `C=US, O=Android, CN=Android Debug`).
6. One known tooling message while compiling the smoke: Compose ui-test's `createAndroidComposeRule` v2-deprecation warning; it does not fail a build and left with the smoke.

## Honest host limits (UNPROVEN on this host, user-only)

- On-device pixels, typography, touch targets, keyboard, insets, themes, and font-scale rendering are not proven here — same standing limitation as S2.1–S2.5.
- The smoke drove production Compose semantics with a tall viewport and real-time waits for Room's live streams, but it is not a rendering or pixel proof.
- A second Room connection writing while the Activity holds the database open does **not** re-emit into the Activity's live collectors on this host: the production edit (`125000`) and delete (`market gone`) above are proven by repository reads plus the permanent live-stream test, but the list's automatic UI refresh after such an *external* write was not observed on the host. In real use the writes happen on the app's own screens through the same collectors (and the permanent observation test proves the streams re-emit on create/correction/deletion), yet the exact visible refresh after an edit/delete/category rename/archive must be confirmed on the phone — checklist steps 8–9 below.
- Likewise the category dialog's `(archived)` marker after an *external* archive on this host: the dialog showed plain `Food` (1 node) rather than `Food (archived)` in the smoke's timing window, while the repository read in the same run proved `food archived: true` and the permanent tests prove archived rows flow through the same `observeCategories` stream. The marker rendering after an archive performed on the phone's own category screen is a user-run check — step 6 below.
- A pending filter edit interrupted mid-flight (process death between keystroke and collection) was not exercised; filters are unsaved browsing state and cannot corrupt ledger data by construction.
- `app/schemas/**/1.json` remains untracked, as recorded since S2.1.

## USER-RUN physical checklist (numbered; user-only, never run by a worker)

Run in the user's own PowerShell with the phone connected and USB debugging authorized (`install -r` accepts this build over the installed `versionCode = 4` package because it is signed by the same preserved debug certificate):

```powershell
adb -d install -r 'F:\ai-ml\aegis-f\app\build\outputs\apk\debug\app-debug.apk'
adb -d shell am start -n io.github.triet4p.aegisf/.MainActivity
```

Seed data (use the S2.4/S2.5 records already on the phone if present, or save fresh ones): an expense `Lunch` 45,000 under a category `Food` dated today 12:00, income `Salary` 1,000,000 under `Food` dated today 12:00 (same moment — the tie case), an uncategorized expense `Market` 120,000 dated tomorrow 09:00, and a transfer `Move` 500,000 dated yesterday 08:00.

1. Home → **Saved transactions**: the **Filters** panel shows above the list (**Type**, **Category** showing **All categories**, empty **From**/**Through**). All four records are listed newest first: Market, then Salary, then Lunch (tie: larger id first), then Move — each with amount, type, description, category, and moment.
2. Tap **Expense**: Lunch + Market stay, Salary + Move vanish. Tap **All types** again: all four return.
3. Tap **All categories** → choose **Food**: only Lunch + Salary show. Choose **No category**: only Market + Move show. Choose **All categories**: all four return.
4. With type **Expense** and category **Food** still chosen, type Through `<today>`: only Lunch shows (Through boundary inclusive). Clear **Through**, type From `<tomorrow>`: Lunch vanishes, Market stays (From boundary inclusive). **Clear all**: all four return.
5. Type From `<tomorrow>` and Through `<today>`: the message `The From date is after the Through date.` shows and the type/category narrowing still applies (never a silent full list, never a crash). Type From `2026-02-30`: its field message shows and the other dimensions still narrow. **Clear all** recovers both times. Extra no-match check: with category **Food** chosen, set From to a far-future day (e.g. `2099-01-01`) with Through empty — the list shows **No matching transactions** (not **No transactions yet**) — then tap **Clear filters** and confirm all records return. (The valid-range no-match screen is the one surface the host proved only through its permanent unit cases and the reversed-range path above; confirm it renders on the phone.)
6. From **Manage categories**, archive **Food** and rename another category; back in **Saved transactions**, open the category selector: **Food (archived)** is listed — choose it: Lunch + Salary show with the `(archived)` marker. (This is the exact surface the host timing could not prove: confirm the marker appears after the on-phone archive.)
7. With filters cleared, open Lunch → **Edit** → change only the description → save → **Back** to the list: the filters are exactly as left and the edited row shows without restarting the app. Delete Market from its detail → confirm: the list returns without Market and the category still exists under **Manage categories**.
8. Still in the list, correct a visible record's amount and delete another record from their detail views: both changes appear in the list on return with no restart. (This is the exact live-refresh surface the host's second-connection limit could not prove: confirm no stale rows.)
9. Close the app from recents and reopen it: the ledger rows are unchanged (correction and deletion persisted; filters are a browsing preference and need not persist).
10. Repeat steps 2–7 in dark theme and with a large system font: the chips wrap (no cramped truncation), the panel and list scroll, and nothing is cut off by the status/navigation bars.

Report the actual visual/runtime observations to Main. The host build and this checklist do not establish device acceptance; the S2.6 visual/functional gate stays with the user.

## Decisions

- Filters are pure derivation over two hoisted live Room streams: no new DAO query per dimension, no paging framework, no schema change, no new dependency. A refused date dimension is treated as open with its message shown, so the failure mode is honest narrowing rather than a crash or a silent broadening.
- The category filter keys on the stable database id (rename-safe) and deliberately includes archived categories (history stays discoverable), while `Uncategorized` is a separate selection from `All`. The editor's `assignableCategories` contract is unchanged: filtering by an archived category never reassigns it.
- The filter state lives with the navigation state as saveable primitives (survives configuration change and detail/editor round-trips) and is deliberately not persisted to disk: it is a browsing preference, not ledger data.
- The type dimension uses wrapping chips rather than the editor's segmented control, because `Internal transfer` already fills two lines at narrow widths and the acceptance forbids new cramped segmented labels.

## Unresolved limits

- Device-level visual/functional verification of browsing and filters is UNPROVEN on this host; the numbered user checklist above is the gate. No PASS is claimed for it. In particular steps 6 and 8 cover the two surfaces the host timing could not prove (archived-marker rendering after an on-phone archive; list refresh after on-phone edit/delete/rename/archive).
- No staging, commit, or push was performed; the S2.6 work is uncommitted on the working tree by assignment. No S2.7 work was started.
