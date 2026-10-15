# S2.5 transaction correction and deletion proof

- Task: `S2.5` / Attempt: `S2.5-a1` / Agent: `Sprint2EditDelete` / Role: Silver (user-selected), light route.
- Route and gates (as assigned): focused real-behavior host smoke on the real launcher `MainActivity` with the real file-backed Room database, relevant permanent regression suite, worker scoped-change review, cleaned `:app:assembleDebug`, then Main's compact acceptance and the user-only visual gate. No independent reviewer, full-route checkpoint, deep review, or commit is assigned.
- Repository / worktree / Git directory: `F:/ai-ml/aegis-f` / `F:/ai-ml/aegis-f/.git`; published baseline `154d34c6f11836a9951f8afffd885c6b004e4e57` on `main`. **No staging, commit, push, reset, or history mutation was performed**, and no `adb`/emulator/device command was run (user-only surfaces throughout).
- Predecessor evidence acknowledged (pointers only, per assignment): `artifacts/sprint-2/S2.4-a1/creation-proof.md`, `artifacts/sprint-2/S2.4-a3/type-selector-proof.md`, `artifacts/sprint-2/S2.1-a2/storage-proof.md`; active plan `docs/sprint-plans/sprint-2.md`. S2.1–S2.4 acceptance was not re-tested.
- Quota/cost observation: unknown (not exposed to the worker).

## Result

Existing saved transactions — including those written before the app was last closed — are now reachable,
inspectable, correctable, and deletable in the production launcher, and the correction/edit/delete behaviour
was exercised on the host against the real file-backed Room database:

- **Reachable details.** Home → **Saved transactions** lists every stored record (newest first, with amount,
  type, description, category, moment); tapping a record opens its detail: amount, type, when, description,
  category (marked when archived), **Status**, **Source**, **Recorded at**, and — once corrected —
  **Last corrected**.
- **Edit prefilled and same-row.** **Edit** opens the same form the creation flow uses, prefilled from the
  stored row (amount, type, date, time, description, category). Saving writes through
  `LedgerRepository.editTransaction` and returns to the detail, which re-reads the row: observed
  `id=2 amountVnd=120000 → 125000`, `createdAtEpochMillis` unchanged, `updatedAtEpochMillis` stamped,
  `confirmation=CONFIRMED` and `source=MANUAL` preserved, still 4 records, and a freshly opened connection to
  the same database file read the corrected row.
- **Cancel / platform Back change nothing.** Leaving an edit by the **Cancel** control or by the system back
  gesture left the stored row byte-identical to the pre-edit row.
- **Refused edits store nothing.** `0`, `1.5`, and a 20-digit amount each showed their field-local message,
  kept the typed value, stayed in the editor, and left the stored row byte-identical
  (`updatedAtEpochMillis` still equal to `createdAtEpochMillis`).
- **Archived attribution preserved, no resurrection.** A record filed under an archived category showed
  `Snacks (archived)` in the form (with an explicit note) and in the detail; correcting only its description
  kept `categoryId=2`, and the category stayed `archived=true`.
- **Delete is confirmed and isolated.** **Delete** opened a confirmation naming the record
  (`45,000 VND · Expense` + description + moment + category). Dismissing it, and the system back gesture,
  deleted nothing. Confirming removed only that row: `deleted row 1; remaining rows [4, 3, 2]`, the other
  records were untouched, the record's category survived, the list came back without the deleted row, and a
  freshly opened connection agreed.

Not proven on this host and therefore **UNPROVEN / user-only**: on-device pixels, typography, touch targets,
the keyboard, theme/font-scale rendering, and physical interaction. Details under "Host limitations".

## Production contract and entry points

- Launcher: `io.github.triet4p.aegisf/.MainActivity` (unchanged manifest entry, `versionCode = 4`). The home
  **TRANSACTIONS** card now offers **Record a transaction** and the new **Saved transactions**; **CATEGORIES**
  and the S2.4 creation flow are unchanged.
- Navigation: `LaunchDestination` is a `Serializable` sealed interface — `Home`, `Categories`,
  `Transactions`, `NewTransaction`, `Detail(transactionId)`, `Edit(transactionId)`. Destinations carry stored
  ids, never loaded rows, so every screen re-reads what the database holds (also after a process restart) and
  survives a configuration change. No writes happen on composition or navigation.
- `ui/TransactionListScreen.kt` — the minimal existing-records surface: `repository.transactions()` (newest
  first) plus one `repository.categories()` map for the row labels, an empty state, and a Back control. No
  filters, grouping, or totals (S2.6/S2.7 work is not anticipated here).
- `ui/TransactionDetailScreen.kt` — reads `findTransaction(id)` + `findCategory(categoryId)`; shows the stored
  values; **Edit** and **Delete**; the delete `AlertDialog` names the record (amount · type, description,
  moment, category) and is the only write path on the screen. A record that no longer exists shows "This
  transaction no longer exists" instead of an editable shell. `BackHandler` on the screen.
- `ui/TransactionEditorScreen.kt` (renamed from `NewTransactionScreen.kt`, one form for both modes) —
  `TransactionEditorScreen(repository, transactionId, onNavigateUp)`: `transactionId == null` is the creation
  flow (unchanged behaviour: confirmed manual record, read-back confirmation panel, single-flight save guard);
  a stored id loads the row, prefills every editable field, and saves a correction then returns to the detail.
  It keeps the accepted `TransactionTypePicker` implementation (shared intrinsic height + per-segment
  `fillMaxHeight()`) untouched, so the accepted S2.4 geometry fix is not regressed.
- Category rules in the form: `assignableCategories()` is still the only source of new assignments, so an
  archived category is never newly offered; the category the record already carries is shown (marked
  `(archived)`) and listed in the picker so it can be kept; choosing **No category** clears it deliberately.
  A switch to **Internal transfer** still writes no category, as creation already required.
- Data contract (`data/LedgerRepository.kt`): new `TransactionCorrection(type, amountVnd,
  occurredAtEpochMillis, description, categoryId)` and `editTransaction(transactionId, correction)` — the
  correction type deliberately cannot carry `confirmation` or `source`, so an edit can neither promote a
  `DRAFT` into the confirmed records that enter totals nor claim bank provenance; the row keeps its id,
  creation time, confirmation, and provenance, and gets a fresh `updatedAtEpochMillis`. Validation moved to
  `validateFields(type, amountVnd, categoryId, retainedCategoryId)`: an archived category may be *retained*
  by the record that already carries it, while any other archived category is still refused. No schema change
  (Room v1 unchanged, no new `app/schemas` export, no migration, no new dependency).
- `ui/LedgerDisplay.kt` (new) — the shared English labels (`typeLabel`, `statusLabel`, `sourceLabel`,
  `categoryLabel`) and the label/value `DetailRow` used by the list, the detail view, and the editor's
  confirmation panel, so the same stored value cannot be described two ways.

## Changed scope

Added:

- `app/src/main/java/io/github/triet4p/aegisf/ui/TransactionListScreen.kt`
- `app/src/main/java/io/github/triet4p/aegisf/ui/TransactionDetailScreen.kt`
- `app/src/main/java/io/github/triet4p/aegisf/ui/LedgerDisplay.kt`

Renamed / rewritten (no duplicate implementation left behind):

- `ui/NewTransactionScreen.kt` → `ui/TransactionEditorScreen.kt` — creation and correction in one form; the
  old entry point and its labels moved here and to `LedgerDisplay.kt`, and the file was not kept alongside.

Modified:

- `data/LedgerRepository.kt` — `TransactionCorrection`, `editTransaction(transactionId, correction)`,
  `validateFields(..., retainedCategoryId)`, and the rule documentation.
- `MainActivity.kt` — sealed `LaunchDestination` with id-carrying `Detail`/`Edit`, wiring for the three
  screens, and the **Saved transactions** entry on the home card (import block tidied).
- `app/src/test/java/io/github/triet4p/aegisf/data/LedgerDatabasePersistenceTest.kt` — the existing correction
  test now uses `TransactionCorrection`; one new permanent test.
- `app/src/test/java/io/github/triet4p/aegisf/data/LedgerRepositoryRejectionTest.kt` — the existing
  unknown-record correction test now uses `TransactionCorrection`; one new permanent test.
- `docs/developer-setup.md` — S2.5 host-test coverage bullet, the S2.5 correction/deletion bullet, and the
  S2.5 user-run checks in §5.
- `C:/Users/admin/.agents/memory/decisions.md` — two entries (correction contract; id-carrying navigation).
- `C:/Users/admin/.agents/memory/lessons-learned.md` — one entry (Robolectric Compose smoke: spinner idling,
  short viewport, field selection, forced layout pass).

Temporary, added only for the smoke run and **already removed**: `app/src/test/java/io/github/triet4p/aegisf/ui/TransactionEditDeleteSmokeTest.kt`
and the three Compose-test dependency lines in `app/build.gradle.kts` (`compose-bom` test platform,
`ui-test-junit4`, `ui-test-manifest`). Verified afterwards: the smoke file is gone, no `TEMPORARY` /
`ui-test-junit4` / `ui-test-manifest` text remains in `app/build.gradle.kts`, and the dependencies block is
byte-identical to its pre-attempt content.

New permanent tests (only for consumer-visible, uncertain edit/delete invariants):

- `a rejected correction leaves the stored record unchanged` — zero amount, a categorised transfer, and an
  archived category the record is not filed under are all refused, and the whole stored row still compares
  equal to its pre-correction value.
- `a correction keeps the confirmation, provenance, and an archived category` — a `DRAFT` /
  `BANK_NOTIFICATION` record filed under a category that is then archived is corrected; after a real
  close/reopen the row keeps its id, its `createdAtEpochMillis`, its `DRAFT` status, its
  `BANK_NOTIFICATION` provenance, and its archived `categoryId`, with a stamped `updatedAtEpochMillis`.

Not changed: Room schema/`app/schemas`, `VndMoney`, `ManualDateTime`, `CategoryManagerScreen`,
`AegisApplication`/`AppContainer`, build configuration, plan files.

## Verification (observed)

Environment per `docs/developer-setup.md`: `JAVA_HOME=C:/Program Files/Java/jdk-21`,
`ANDROID_HOME=C:/Users/admin/AppData/Local/Android/Sdk`, commands run from `F:/ai-ml/aegis-f` with
`--console=plain`.

1. Compile after the restructure —
   `.\gradlew.bat :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --console=plain` →
   **BUILD SUCCESSFUL** in 21s (21 actionable tasks: 5 executed, 16 up-to-date).
2. Permanent suite (after implementation and again on the cleaned tree) —
   `.\gradlew.bat :app:testDebugUnitTest --console=plain` → **BUILD SUCCESSFUL**; JUnit XML:
   `LedgerDatabasePersistenceTest` 10/0/0, `LedgerRepositoryRejectionTest` 12/0/0, `VndMoneyTest` 6/0/0,
   `ManualDateTimeTest` 5/0/0 → **33 tests, 0 failures, 0 errors, 0 skipped** (was 31 before this task; the
   two added tests are the ones listed above).
3. Throwaway host smoke on the real launcher Activity (removed after the run) —
   `.\gradlew.bat :app:testDebugUnitTest --tests 'io.github.triet4p.aegisf.ui.TransactionEditDeleteSmokeTest' --console=plain`
   → **BUILD SUCCESSFUL**; `tests="5" skipped="0" failures="0" errors="0"` (22.8s). It launched the
   production `MainActivity`, seeded the real database file through the production
   `LedgerDatabase.build` + `LedgerRepository` **before the launcher read it** (the "saved in an earlier
   session" case), and drove the production screens through production Compose semantics. Scenarios:
   reach + correct a record from an earlier session; leave a correction by **Cancel** and by the system back
   gesture; refused corrections (`0`, `1.5`, 20 digits); a correction of a record filed under an archived
   category; dismiss/back out of the delete confirmation and then confirm it. Stored effect asserted through
   the app's own repository and through a second, freshly opened connection to the same file. Observed rows
   (JUnit XML `system-out`):

   ```
   corrected row: TransactionEntity(id=2, type=EXPENSE, amountVnd=125000, occurredAtEpochMillis=1792029900000, description=Coffee beans, categoryId=1, confirmation=CONFIRMED, source=MANUAL, createdAtEpochMillis=1700000000000, updatedAtEpochMillis=1792040840241)
   freshly opened row: TransactionEntity(id=2, type=EXPENSE, amountVnd=125000, occurredAtEpochMillis=1792029900000, description=Coffee beans, categoryId=1, confirmation=CONFIRMED, source=MANUAL, createdAtEpochMillis=1700000000000, updatedAtEpochMillis=1792040840241)
   corrected row: TransactionEntity(id=1, type=EXPENSE, amountVnd=45000, occurredAtEpochMillis=1791954000000, description=Lunch with the team, categoryId=2, confirmation=CONFIRMED, source=MANUAL, createdAtEpochMillis=1700000000000, updatedAtEpochMillis=1792040840902)
   kept category: CategoryEntity(id=2, name=Snacks, archived=true, createdAtEpochMillis=1700000000000, updatedAtEpochMillis=1700000000000)
   row after refusals: TransactionEntity(id=2, type=EXPENSE, amountVnd=120000, occurredAtEpochMillis=1792029900000, description=Coffee beans, categoryId=1, confirmation=CONFIRMED, source=MANUAL, createdAtEpochMillis=1700000000000, updatedAtEpochMillis=1700000000000)
   deleted row 1; remaining rows [4, 3, 2]
   ```

   The smoke also asserted, over the production semantics tree, that the editor is prefilled from the stored
   row (`120000`, `Coffee beans`, `2026-10-15`, `09:05`, the `Expense` segment selected, `Food` as the
   category) and that the detail returns `125,000 VND` after a save.
4. Cleaned tree — `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain` →
   **BUILD SUCCESSFUL** in 36s (47 actionable tasks: 21 executed, 26 up-to-date), with the permanent suite at
   **33/0/0**.
5. APK — `app/build/outputs/apk/debug/app-debug.apk`, **18,782,475 bytes**; `aapt dump badging`:
   `package: name='io.github.triet4p.aegisf' versionCode='4' versionName='1.0'` compile/target SDK 37,
   `launchable-activity: name='io.github.triet4p.aegisf.MainActivity' label='Aegis F'`;
   `apksigner verify --print-certs` reports certificate SHA-256
   `891542fd7a27bdaff367af863d28ac0babeb4dfd2c394e340348534a797ff41b` — the documented, preserved debug
   certificate. A byte scan of the APK's `classes*.dex` found `TransactionDetailScreen` (165) and
   `TransactionListScreen` (125) and **zero** occurrences of the removed smoke class or `ui-test-junit4`, so
   the built APK is the cleaned new build.
6. One known tooling message appeared while compiling the smoke: Compose ui-test's
   `createAndroidComposeRule` v2-deprecation warning; it does not fail a build and left with the smoke.

## Host limitations (UNPROVEN on this host)

- All on-device visual and interaction behaviour is user-only: pixels, typography, touch targets, the software
  keyboard, insets, light/dark theme, font scaling, and gesture handling are not proven here. The host proves
  the reachable screens, the writes, the preserved invariants, and the semantics tree (labels, prefilled
  values, verbs), not how they look on the phone.
- The smoke drove the production Compose semantics (including the production `SetText` action) with a tall
  Robolectric viewport and a forced layout pass after coroutine-driven recomposition; it is real runtime
  behaviour of the production screens and the production database, but it is not a rendering or pixel proof.
- A pending delete or correction interrupted mid-flight (process death between tap and write) was not
  exercised; the repository's write is a single Room call, and the existing read-back discipline is unchanged.
- `app/schemas/**/1.json` remains untracked, as recorded since S2.1.
- No `CHANGELOG.md` exists in this repository, and S2.1–S2.4 kept the same practice, so the user-visible
  change is recorded in `docs/developer-setup.md` plus the shared decision log; creating a changelog file
  would be a new document beyond this task's assigned evidence, so none was created. Main can request one.

## USER-RUN physical checklist (numbered; user-only, never run by a worker)

Run in the user's own PowerShell with the phone connected and USB debugging authorized (`install -r` accepts
this build over the installed `versionCode = 4` package because it is signed by the same preserved debug
certificate):

```powershell
adb -d install -r 'F:\ai-ml\aegis-f\app\build\outputs\apk\debug\app-debug.apk'
adb -d shell am start -n io.github.triet4p.aegisf/.MainActivity
```

1. Home shows **Saved transactions** under **Record a transaction**. Open it: the records saved in earlier
   sessions are listed newest first with amount, type, description, category, and moment (for example the
   four from the S2.4 creation check: expenses **45,000 VND** and **120,000 VND**, income **1,000,000 VND**,
   and a **500,000 VND** internal transfer).
2. Tap the **120,000** expense (or any expense you saved): the detail shows the amount, **Expense**, its
   category, **Confirmed**, **Manual**, and **Recorded at**, with **Edit** and **Delete**.
3. **Edit**: every field is prefilled (amount `120000`, type, date, time, description, category). Change the
   amount to `125000` → **Save changes** → the detail returns showing **125,000 VND** and a **Last corrected**
   line, and the record still reads **Confirmed**/**Manual**.
4. Open the same record again and change the amount to `999` → **Cancel** (and, in a second attempt, the
   system back gesture): the detail still shows **125,000 VND** — nothing was saved.
5. In the editor, try `0`, then `1.5`, then a 20-digit number, pressing **Save changes** each time: each is
   refused with its message under the amount field, the typed value stays, and the stored amount is still
   **125,000 VND**.
6. For a record filed under a category you archived afterwards (save a record under a category, then archive
   that category from **Manage categories**): its detail and its editor show `<category> (archived)`; change
   only the description → **Save changes** → the detail still shows `<category> (archived)`, so the historical
   attribution is kept and the category is not revived (check **Manage categories**: it is still
   **Archived**).
7. Tap **Delete** on that same record: the confirmation names it (amount · type, description, moment,
   category). **Cancel** (and, in a second attempt, the system back gesture) deletes nothing. Then **Delete
   transaction**: the list returns without that record, the other records are still listed, and the category
   still exists under **Manage categories**.
8. Close the app from the recents view and reopen it: the corrected amount is still **125,000 VND**, the
   deleted record is still gone, and the remaining records are unchanged.
9. Repeat steps 3–7 in dark theme and with a large system font: values stay readable, the screens scroll, and
   nothing is cut off by the status/navigation bars.

Report the actual visual/runtime observations to Main. The host build and this checklist do not establish
device acceptance; the S2.5 visual/functional gate stays with the user.

## Decisions

- A correction is a dedicated `TransactionCorrection` because the storage layer, not the screen, must own the
  rule that an edit cannot rewrite how a record came to exist (confirmation/provenance), and because
  confirming a draft later deserves its own explicit operation. Recorded in `~/.agents/memory/decisions.md`.
- An already-attached archived category is *retained* by a correction, while newly assigning any archived
  category still fails: attribution is history, assignment is a new decision.
- Records are reached by stored id and re-read per screen, so a screen shows what storage holds, survives a
  process restart, and writes nothing on navigation; S2.6 can replace the list's one-shot read without
  touching the navigation model. Recorded in `~/.agents/memory/decisions.md`.
- One form serves creation and correction, and the accepted type-selector implementation moved with it
  unchanged, so S2.4's geometry fix and creation behaviour are not duplicated or regressed.

## Unresolved limits

- Device-level visual/functional verification of correction and deletion is UNPROVEN on this host; the
  numbered user checklist above is the gate. No PASS is claimed for it.
- No staging, commit, or push was performed; the S2.5 work is uncommitted on the working tree by assignment.
