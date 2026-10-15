# S2.4 Manual transaction creation proof

- Task: `S2.4` / Attempt: `S2.4-a1` / Agent: `Sprint2ManualCreation` (Gold, light route)
- Route: Light. Gates: actual host behavior smoke and creation invariants on the real launcher Activity, existing regression suite, debug APK build, worker scope self-review, Main acceptance. Physical visual/interaction proof is user-run only; no independent reviewer, full-route checkpoint, deep review, or commit is assigned.
- Repository/worktree/Git dir: `F:/ai-ml/aegis-f` / `F:/ai-ml/aegis-f/.git`; baseline `main`. No staging, commit, push, reset, or signing action was performed.
- Quota observation: unknown (not exposed to the worker).

## Result

Manual transaction creation is implemented and host-verified at the data/creation level on the real
launcher `MainActivity` with the real file-backed Room database: the English form (amount,
expense/income/internal-transfer type, date AND time, description, category where applicable) writes
exactly one confirmed `MANUAL` record per accepted submission, with the typed device-local moment
stored exactly, field-local refusals that write nothing, cancel that writes nothing, and a saved-record
confirmation backed by the row read back from storage. Host suite and debug build are green on the
cleaned tree (31 permanent tests, 0 failures; APK built).

Not proven on this host and therefore **UNPROVEN / user-only**: the confirmation panel's rendering
after a save, any pending-write interleaving, and all physical visual/interaction behavior. Details in
"Host limitations" and the manual checklist below. No PASS is claimed for those.

## Production contract and entrypoints

- Entry point: launcher `io.github.triet4p.aegisf/.MainActivity` (manifest-registered, exported) →
  home screen **Aegis F / Vietnamese dong / VND / ₫** → the new **TRANSACTIONS** card with
  **Record a transaction** → `NewTransactionScreen`. The **CATEGORIES** card and
  `CategoryManagerScreen` are unchanged and still reachable from the same home screen; home-screen
  identity, safe insets, scrolling, light/dark theme, and font scaling behaviour are preserved.
- `ui/NewTransactionScreen.kt` — the form: `Amount (VND)` (digits-only field, parsed by
  `VndMoney.parseManualAmount`), a type selector (Expense / Income / Internal transfer), `Date`
  (`YYYY-MM-DD`) and `Time` (`HH:mm`, 24-hour) fields prefilled with the current device-local values,
  `Description (optional)`, and a category button that opens a single-choice picker listing
  `No category` plus `LedgerRepository.assignableCategories()`. An internal transfer shows the
  explanatory text and no category control. Errors render under their own field with the typed values
  intact. Saving is a single-flight write (`AtomicBoolean` guard plus the disabled save control).
- `ui/ManualDateTime.kt` — the only mapping between typed date/time text and stored epoch millis:
  `yyyy-MM-dd HH:mm` parsed in `TimeZone.getDefault()` (the device's current zone), strict grammars
  (trailing text, `2026-02-30`, `25:61`, and `18:30:45` are refused with field-local messages), plus
  `dateFieldText` / `timeFieldText` / `formatMoment` for display of stored moments.
- Writes go through the production `LedgerRepository.addTransaction(TransactionInput)` — same
  repository instance the launcher hands the screen (`(application as AegisApplication).container`).
  `TransactionInput` defaults (`confirmation = CONFIRMED`, `source = MANUAL`) are what makes a
  saved record a confirmed manual record; the repository still enforces positive amounts, unknown
  and archived categories, and category-less transfers.
- Success: the form is replaced by a confirmation panel that reads the persisted row back
  (`repository.findTransaction(id)`, keyed by a saveable record id) and shows the stored amount
  (`VndMoney.formatAmount`), type, `When <moment> device time`, description, category, `Status`
  (`Confirmed`), `Source` (`Manual`), and the stored creation time, with **Record another** and
  **Done** actions. It is deliberately not a ledger list; browsing is S2.6.
- No database schema change: `Room` schema v1 is unchanged, so no new `app/schemas` export was needed.

## Changed scope

Added:

- `app/src/main/java/io/github/triet4p/aegisf/ui/NewTransactionScreen.kt` — manual creation form,
  category picker, field-local refusals, single-flight save, saved-record confirmation panel.
- `app/src/main/java/io/github/triet4p/aegisf/ui/ManualDateTime.kt` — device-local date/time
  conversion, strict grammars, and moment display (pure `java.util`; no new dependency).
- `app/src/test/java/io/github/triet4p/aegisf/ui/ManualDateTimeTest.kt` — permanent tests for the
  device-local round-trip (a typed moment stores that exact instant in a fixed UTC+7 zone and is
  never reinterpreted as UTC; field text round-trips; midnight-local-day membership; grammar
  refusals).

Modified:

- `app/src/main/java/io/github/triet4p/aegisf/MainActivity.kt` — the home screen gains a
  **TRANSACTIONS** card (**Record a transaction**) and the screen switch becomes a three-destination
  `when` (home / categories / new transaction) over a saveable enum; the categories entry point and
  home-screen appearance are unchanged.
- `docs/developer-setup.md` — host-test coverage bullet, verified-state note, and the S2.4 user-run
  manual checks in §5.
- `C:/Users/admin/.agents/memory/decisions.md` — the date/time and save-confirmation decisions.

Not changed: Room schema, repository, `VndMoney`, `CategoryManagerScreen`, build dependencies, plans.

## Verification (observed)

Environment per `docs/developer-setup.md`: JDK 21 (`JAVA_HOME`), Android SDK at
`%LOCALAPPDATA%\Android\Sdk`; commands run from `F:/ai-ml/aegis-f` with `--console=plain`.

### Cleaned tree

1. `./gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain` — `BUILD SUCCESSFUL` in
   36s (final run on the cleaned tree, after the last production correction: the **Record another**
   reset prefills the date/time fields with the current moment rather than the screen's original
   opening time). JUnit XML: `LedgerRepositoryRejectionTest` 11/0/0, `VndMoneyTest` 6/0/0,
   `LedgerDatabasePersistenceTest` 9/0/0, `ManualDateTimeTest` 5/0/0 → **31 tests, 0 failures, 0
   errors, 0 skipped**.
2. `./gradlew.bat :app:assembleDebug --console=plain` — `BUILD SUCCESSFUL` in 36s (same invocation as
   above; 47 actionable tasks: 10 executed, 37 up-to-date). APK:
   `app/build/outputs/apk/debug/app-debug.apk` (18,782,475 bytes). Only the documented non-blocking
   SDK XML warning.

### Throwaway host smoke on the real launcher Activity (removed after the run)

A temporary Robolectric test launched the production `MainActivity`, drove the production creation
screen through the production Compose semantics (text entry through the production `SetText`
semantics action, taps through the window, and the production scroll), and asserted against the real
file-backed Room database read through a separate instance of the same file. Final run: **5 tests, 5
green, 0 failures** (`tests="5" failures="0" errors="0"` in the JUnit XML). Observed:

- **The four scenarios**: expense 45,000 with category, expense 120,000 with category, income
  1,000,000 without a category, and internal transfer 500,000 (no category control shown) each wrote
  exactly one record. Record read back from the database after the first save:
  `TransactionEntity(id=1, type=EXPENSE, amountVnd=45000, occurredAtEpochMillis=1792063800000,
  description="Lunch", categoryId=1, confirmation=CONFIRMED, source=MANUAL, createdAtEpochMillis=
  1792029382317, updatedAtEpochMillis=1792029382317)` — `1792063800000` is exactly the device-zone
  instant of the typed `2026-10-15` `18:30`. The second save stored
  `occurredAtEpochMillis=1792116300000` = the typed `2026-10-16` `09:05`. After all four saves:
  exactly 4 records with 4 distinct ids, amounts {45000, 120000, 1000000, 500000}, the transfer
  carrying a null category, every record `CONFIRMED`/`MANUAL`, and a freshly opened instance of the
  same database file reading back the same four records.
- **Invalid amounts** (`0`, `-1`, `1.5`, `99999999999999999999`): each showed its field-local
  message (`Amount must be at least 1 whole dong; got 0.`; the digits-only message;
  `Amount exceeds the maximum whole-dong value.`) under the amount field, kept the typed value, and
  left the database with **0** records.
- **Archived category**: with the chosen category archived while the form was open, the save was
  refused with `Category "Snacks" is archived and cannot be attached to a new record.` under the
  category field and the database stayed at **0** records; a form opened afterwards offered no
  archived category.
- **Cancel before saving**: **0** records and the home screen returned.
- **Submit once**: exactly **1** stored record; after success the save control no longer exists in
  the semantics tree; an extra tap on the confirmation panel added nothing; a deliberate
  **Record another** followed by a new submission wrote the second record (2 records: 45000 and
  120000).

Harness-only facts recorded while making assertions possible on this host (no production change was
made for any of them): taps aimed below the 470 px Robolectric viewport land on nothing, so the
harness scrolled with the production `performScrollTo` before tapping; a recomposition arriving from
a coroutine continuation needed a forced layout traversal (frame advance + `decorView.requestLayout()`
+ looper idle + `waitForIdle`) before its nodes carried bounds — the same limitation class recorded in
the S2.3 proof; and the Compose test framework drains the main looper between actions.

## Host limitations (UNPROVEN on this host)

- **The confirmation panel's rendering after a save is UNPROVEN.** After a successful save the panel's
  semantics nodes exist (title `Transaction saved`, the formatted amount, `When … device time`,
  `Confirmed`, `Manual`), but the host runs no layout traversal after a coroutine-originated
  recomposition, so the nodes reported zero bounds and `assertIsDisplayed` failed. One isolated run
  captured the panel fully laid out after a forced traversal (real bounds from `t=88` down through the
  action buttons, with `45,000 VND` and `2026-10-15 18:30 device time`). Whether the panel renders
  correctly on the device is therefore **UNPROVEN/user-only**, not a waived pass and not a claimed
  production defect.
- **Pending-write interleaving is UNPROVEN.** Any second tap's delivery pumps the coroutine
  dispatcher on this host, so the first write always completes before a second click can be emitted;
  the "write still pending when a second submission arrives" state is unreachable through taps here.
  What is observed is separate evidence: the success state machine leaves no live save target after a
  save, and the rapid double-tap case behaves correctly where it could be delivered. The in-flight
  `AtomicBoolean` guard itself is not host-proven; its physical behaviour is part of the user-run gate.
- All physical visual/interaction behaviour (portrait/landscape, font scale, themes, touch targets,
  insets, scrolling, keyboard, immediate appearance of refusals and confirmations) is user-only.
- `app/schemas/**/1.json` remains untracked, as recorded since S2.1.

## USER-only manual creation checks (for the consolidated S2.8 run)

Run in the user's own PowerShell with the phone connected and USB debugging authorized (never run by a
worker):

```powershell
adb -d install -r 'F:\ai-ml\aegis-f\app\build\outputs\apk\debug\app-debug.apk'
adb -d shell am start -n io.github.triet4p.aegisf/.MainActivity
```

1. Home shows **Aegis F**, **Vietnamese dong**, **VND**, `₫`, and both **Record a transaction** and
   **Manage categories**.
2. **Record a transaction** → the form shows **Amount (VND)**, **Type** (Expense / Income / Internal
   transfer), **Date**, **Time**, **Description (optional)**, and the category button; the date and
   time fields are prefilled with the device's current date and time.
3. Enter `45000`, description `Lunch`, pick a category (or **No category**) → **Save transaction** →
   the confirmation shows **45,000 VND**, the stored moment as `<date> <time> device time`,
   **Status: Confirmed**, **Source: Manual**, and **Recorded at**; the form is gone.
4. **Record another** → enter `120000` → save. Then income: type **Income**, `1000000`, description
   `Salary` → save. Then **Internal transfer**, `500000`, description `Move to savings` → the screen
   states that an internal transfer carries no category and offers no category button → save.
5. Refusals store nothing and keep the typed value: `0` → `Amount must be at least 1 whole dong; got
   0.`; `-1` and `1.5` → the digits-only message; a 20-digit number → the maximum-whole-dong message.
6. Archive a category, then try to attach it to a new record: it is not offered and cannot be saved.
7. **Cancel** (or system back) before saving stores nothing. Tap **Save transaction** twice quickly:
   exactly one record appears.
8. Close the app from recents and reopen it from the launcher: the saved records are still there
   (browsing them is the S2.6 work; this feature's contract is creation).
9. Repeat steps 3–7 in dark theme and with a large system font: text stays readable, the form
   scrolls, content stays clear of the status/navigation bars.

Expected persisted confirmation: the four records above — expenses 45,000 VND and 120,000 VND, income
1,000,000 VND, and an internal transfer 500,000 VND — each confirmed and manual, with the typed
device-local moment, which is the same data the sprint's device plan reuses in S2.7/S2.8.

## Decisions

- Dates and times are typed text with fixed grammars converted in the device's own zone; the stored
  instant is never a UTC reinterpretation, and impossible values are refused rather than rolled over
  (recorded in the decision log).
- A save is confirmed by reading the persisted record back, behind a single-flight write guard; the
  confirmation is intentionally not a ledger list.
- No schema, dependency, or repository change was required, so none was made.

## Unresolved limits

- Confirmation-panel rendering, pending-write interleaving, and all physical interaction are
  UNPROVEN on this host; the user-run checks above are the gate.
- No staging, commit, or push was performed.
