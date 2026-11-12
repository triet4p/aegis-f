# S10.7 attempt a1 / candidate a2 — verification record

Candidate: the uncommitted working tree at base `2581b11f0087ae93a1e1e80a7e11808db83c875b`
(see `source-snapshot.txt`); isolated evidence package `io.github.triet4p.aegisf.s812evidence`.
Round 1 closed `changes_requested` (F1–F3); this record describes the round-2 candidate a2 after
those corrections. No commit, staging, push or checkpoint. Owner package
`io.github.triet4p.aegisf`, its data and the owner device settings were never cleared,
instrumented or replaced. Wi-Fi was already off; the temporary airplane-mode runs were restored
to off (mobile data remained on).

## 1. Host build and focused unit tests

```
./gradlew.bat --no-daemon :app:testDebugUnitTest \
  --tests "io.github.triet4p.aegisf.ui.ManualAmountWordsTest" \
  --tests "io.github.triet4p.aegisf.ui.ManualDateTimeTest" \
  :app:assembleDebug :app:assembleDebugAndroidTest \
  -PS812_ANDROID_TARGET_PACKAGE=io.github.triet4p.aegisf.s812evidence --console=plain
```

Result: **BUILD SUCCESSFUL**. `ManualAmountWordsTest` **6/6** passed; `ManualDateTimeTest` **5/5**
passed (JUnit XML: 0 failures, 0 errors, 0 skipped). The isolated debug APKs were built from this
source; the installed copies on the device have the same SHA-256 as the local outputs
(`apk-sha256.txt`).

The amount-words tests cover the design example in both languages, Vietnamese empty-hundreds and
irregular unit forms, English short-scale grouping, and non-positive input. `ManualDateTimeTest`
re-checks the existing date/time grammar and device-zone conversion the form still relies on.

## 2. Native instrumented run

Device HONOR ALT-LX2 (`ALT_LX2`), Android 14 / API 34, 720 × 1610 px, 320 dpi. Isolated package
`io.github.triet4p.aegisf.s812evidence`; the opt-in source-set guard asserts the package name in
every test.

```
adb shell am instrument -w \
  -e class "io.github.triet4p.aegisf.ui.S107ManualRecordInstrumentedTest" \
  io.github.triet4p.aegisf.s812evidence.test/androidx.test.runner.AndroidJUnitRunner
```

Result: **OK (7 tests)** in 43.7 s on candidate a2. Raw output:
`logs/instrumentation-final-round2.txt`. (Round 1's six-test run is retained at
`logs/instrumentation-final.txt`.) The seventh test is the round-2 localization regression
described in §2.7.

The production `TransactionEditorScreen` is rendered through the real `AegisTheme` over isolated
in-memory Room databases. Every assertion below also reads the stored row and the confirmed
aggregate, so the screen is never the only witness.

### 2.1 Create: exact total, exact moment, one record
(`createWritesTheExactAmountOnceAndTheMomentSheetSetsTheStoredInstant`)

Before: confirmed **spending 0 ₫, income 0 ₫**, zero rows.

- The amount field accepts the number keyboard; `150000` shows
  **“One hundred fifty thousand dong”**.
- The moment sheet sets `2026-11-05` / `15:00`; the form shows **05/11/2026 · 15:00**.
- Category **Food**, memo **Synthetic lunch**, one Save.

After: **spending 150,000 ₫, income 0 ₫**, exactly **one** row with amount 150,000, type
`EXPENSE`, category Food, description `Synthetic lunch`, and
`occurredAtEpochMillis == ManualDateTime.toEpochMillis("2026-11-05", "15:00", defaultZone)`.
The success panel reads the stored row back.

### 2.2 Correction: exact new total once, discard writes nothing
(`correctionChangesTheExactTotalOnceAndDiscardWritesNothing`)

Before: seeded expense **100,000 ₫**, spending **100,000 ₫**, one row.

- The correction form opens prefilled at `100000`, title **Edit transaction**.
- Change to `125000`, Save: the screen leaves, aggregate **125,000 ₫**, still one row, amount
  125,000.
- Reopen at `125000`, change to `130000`, press Close → **Discard changes?** → **Discard**:
  the aggregate remains **125,000 ₫** and the stored amount remains 125,000.

### 2.3 Input, archived category, no write
(`invalidAmountAndAnArchivedCategoryAreRefusedWithoutWriting`)

Before/after: **0 ₫ / 0 ₫**, zero rows.

- Empty Save → **Enter an amount before saving.**
- `12.5` → **Use whole digits only…** (nothing stored).
- `150000`, choose **Food**, then archive Food through the real command boundary while the form
  is open. The tile becomes marked **Archived**, the form says
  **“Food” was archived while this form was open…**, and Save reports
  **That category is no longer available.** Still zero rows and zero totals.

### 2.4 Record changed elsewhere
(`aRecordChangedElsewhereIsRefusedWithTheTypedValuesIntact`)

Before: seeded **100,000 ₫**. The form is edited to **130000**, then a real concurrent correction
moves the stored row to **110,000 ₫**.

- Save reports **This record changed elsewhere. Nothing was saved and your entries are still
  here…**.
- The typed amount remains `130000`; aggregate remains **110,000 ₫** with exactly one row.
- A missing id renders **This transaction is not here** and writes nothing.

### 2.5 Already-recorded saved note
(`anAlreadyRecordedSavedNoteCannotBeWrittenTwiceAndKeepsTheTypedValues`)

Fixture: one saved note reading **Taxi ride**, amount 45,000, 2026-11-05 15:00. The form opens
from that note, prefilled `45000` and the reading; the source card names
**Taxi ride · 45,000 ₫ · 05/11/2026 · 15:00**.

- Change the form to `47000`, then record the same note through the real command boundary
  (settling its revision).
- Save reports **The saved note changed or was already recorded…**; the typed `47000` remains.
- Aggregate is **45,000 ₫ / 0 ₫** with exactly **one** row: no duplicate and no silent loss.

### 2.6 Vietnamese, 1.5× text, real IME
(`vietnameseLargeTextAndTheKeyboardKeepTheSingleSaveReachable`)

- Vietnamese title **Thêm giao dịch mới**, primary **Lưu vào sổ giao dịch**.
- At `fontScale = 1.5` the amount field and Save are displayed with the real keyboard open; the
  type choices are laid out in two rows so **Chuyển khoản nội bộ** is never clipped.
- Selecting transfer shows the single explanation
  **Chuyển khoản nội bộ giữa các tài khoản của bạn: không phải chi tiêu hay thu nhập và không có
  danh mục.** and hides the category picker; Save remains displayed.

### 2.7 Round-2 localization regression
(`vietnameseRetainedArchivedCategorySheetUsesLocalizedWords`)

Fixture: a stored expense filed under **Food**, then Food archived so the correction carries a
retained archived category.

- At Vietnamese 1.5×, the correction opens prefilled at `100000` with **Sửa giao dịch**.
- **All categories** opens the full sheet; the retained row reads **Food** with the localized
  badge **Đã lưu trữ**.
- The test asserts that badge is present and that the default English **ARCHIVED** is absent;
  `s107-14-vi-archived-sheet` is the opened capture.

## 3. Actual airplane-mode run

Radio state before: airplane off, Wi-Fi already off, mobile data on. Then:

```
adb shell cmd connectivity airplane-mode enable      # settings get global airplane_mode_on -> 1
adb shell am instrument -w \
  -e class "io.github.triet4p.aegisf.ui.S107ManualRecordInstrumentedTest#createWritesTheExactAmountOnceAndTheMomentSheetSetsTheStoredInstant" \
  io.github.triet4p.aegisf.s812evidence.test/androidx.test.runner.AndroidJUnitRunner
adb shell cmd connectivity airplane-mode disable     # settings get global airplane_mode_on -> 0
```

Result: **OK (1 test)** in 14.1 s with airplane mode on. The create test still wrote exactly
**150,000 ₫ / 0 ₫** and one row. Raw output: `logs/instrumentation-offline.txt`. Airplane mode was
restored to off; mobile data read 1 afterwards, Wi-Fi was off before and after.

The same radio procedure was repeated for the correction test:

```
adb shell cmd connectivity airplane-mode enable
adb shell am instrument -w \
  -e class "io.github.triet4p.aegisf.ui.S107ManualRecordInstrumentedTest#correctionChangesTheExactTotalOnceAndDiscardWritesNothing" \
  io.github.triet4p.aegisf.s812evidence.test/androidx.test.runner.AndroidJUnitRunner
adb shell cmd connectivity airplane-mode disable
```

Result: **OK (1 test)** in 6.3 s with airplane mode on: **100,000 → 125,000 ₫** exactly once,
and discard kept 125,000 ₫. Raw output: `logs/instrumentation-offline-correction.txt`. Airplane
mode was restored to off; mobile data read 1.

After the F1–F3 corrections, a single round-2 airplane run executed both methods together:

```
adb shell cmd connectivity airplane-mode enable
adb shell am instrument -w \
  -e class "io.github.triet4p.aegisf.ui.S107ManualRecordInstrumentedTest#createWritesTheExactAmountOnceAndTheMomentSheetSetsTheStoredInstant,io.github.triet4p.aegisf.ui.S107ManualRecordInstrumentedTest#correctionChangesTheExactTotalOnceAndDiscardWritesNothing" \
  io.github.triet4p.aegisf.s812evidence.test/androidx.test.runner.AndroidJUnitRunner
adb shell cmd connectivity airplane-mode disable
```

Result: **OK (2 tests)** in 18.4 s with airplane mode on; create 150,000 ₫ once, correction
100,000 → 125,000 ₫ once with discard unchanged. Raw output:
`logs/instrumentation-offline-round2.txt`. Airplane mode was restored to off; mobile data read 1.

## 4. Real-shell walkthrough (isolated package, production UI)

`pm clear io.github.triet4p.aegisf.s812evidence` (isolated only), then launched
`io.github.triet4p.aegisf.s812evidence/io.github.triet4p.aegisf.MainActivity` and used the real
tabs/icons with `adb shell input`.

1. Home → **Manual record**: focused form, bottom navigation hidden, top-bar Close, one sticky
   **Save to ledger**. `device-01-form-empty.png`.
2. Tapped the amount field and used the actual numeric keyboard: `123000`, words
   **One hundred twenty-three thousand dong**, Save above the keyboard.
   `device-02-form-amount-ime.png`.
3. Save → success panel reads back **-123,000 ₫**, Confirmed, Type Expense, Category No category,
   Source Manual, one Done and a secondary Record another. `device-03-saved.png`.
4. Home then showed **Spending this month 123,000 ₫**; Ledger showed **-123,000 ₫**; the receipt
   opened. `device-04-correction-prefilled.png` is the prefilled **Edit transaction** form from
   that receipt (amount 123000 and words).
5. Changed the amount through the same form and Saved. The receipt then showed exact
   **-133,000 ₫** with **Corrected** time and the same **Record ID 1**;
   `device-07-corrected-133000.png`, and Home then showed **133,000 ₫**
   (`device-09-home-total.png`).
6. Reopened Edit, typed a change, pressed Close → **Discard changes?**, chose **Discard**; the
   receipt still showed **-133,000 ₫** (`device-08-discard-dialog.png` and the post-discard UI
   dump `window-after-discard.xml`).

The stray intermediate 133,000,000 figure seen while scripting the adb keyboard was corrected
through the same form to the intended 133,000; it was an input-scripting artifact in the isolated
package, not an app write path, and the final stored/detail/Home values are exact.

## 5. Screenshots and visual comparison

All instrumentation PNGs were taken from the installed isolated APK on the phone and personally
opened. The worker also opened the approved `artifacts/sprint-10/renders/06_manual_record.webp` and
compared it concretely; the observations and corrections are in `visual-review.md`.

- `s107-01-create-en` — create form, X/title, hero amount, adaptive type row, category grid,
  details card, sticky Save.
- `s107-02-moment-sheet-en` — focused date/time sheet with exact fields and apply action.
- `s107-03-create-filled-en` — amount words, selected category, memo, Save above keyboard.
- `s107-04-saved-en` — success panel read back from the ledger.
- `s107-05-correction-en` — prefilled correction form with one Save changes.
- `s107-06-validation-en` — invalid amount refusal with typed digits intact.
- `s107-07-archive-warning-en`, `s107-07b-archive-refusal-en` — archived category marked and
  refused with zero writes.
- `s107-08-stale-record-en`, `s107-08b-stale-amount-en` — revision conflict text plus the retained
  typed amount.
- `s107-09-missing-record-en` — missing record state.
- `s107-10-source-note-en` — full saved-note source card with amount/date/text and Change/Remove.
- `s107-11-source-stale-en`, `s107-11b-source-amount-en` — already-recorded note refusal plus the
  retained typed amount.
- `s107-12-vi-large-ime`, `s107-13-vi-transfer` — Vietnamese at 1.5× with IME and transfer.
- `s107-14-vi-archived-sheet` — Vietnamese retained-archived category in the full sheet, localized
  `Đã lưu trữ` badge and no English `ARCHIVED` leak.
- `device-01`…`device-09` — real-shell route, keyboard, save, receipt, correction, discard and
  Home total.
- `device-10-final-form` — the final a2 APK relaunched on the real shell after the F1–F3 changes,
  showing the final create form, selector contrast and single Save. (The earlier real-shell route
  captures were taken on the round-1 APK; the round-2 changes do not alter that create/correct/
  discard route.)

## 6. Limits and uncompensated points

- **Round-2 F1 (source-prefill race)** is fixed in source: the first note load prefill is gated on
  `!editedSinceOpen`, so typed values are not overwritten while the read is in flight; the note
  still loads and its revision is still sent. The race is source-verified but not deterministically
  raced at runtime.
- **Round-2 F2 (retained archived badge)** is fixed and natively exercised: the full sheet now
  receives the localized archived label; `s107-14` shows `Đã lưu trữ` and the regression asserts
  no English `ARCHIVED`.
- **Round-2 F3 (a11y literal)** is fixed in source: the tile no longer appends `", selected"`;
  `selectable(..., role = RadioButton)` carries selection semantics. Code-only, as the reviewer
  noted.
- Saved-note and bank evidence are synthetic; no live bank notification or provider run is claimed.
- A forced storage failure is not separately exercised at the native layer. The save coroutine
  catches an unexpected exception and shows the localized “could not be saved” message with Save
  enabled for retry; validation refusal and stale revision refusal are natively exercised.
- The loading frame is implemented but resolves before a stable capture; it is not separately
  photographed. Missing and read-error states are implemented; missing is captured.
- The system font scale was not changed. 1.5× is app-scoped through `LocalDensity` in the test
  host, and light/dark are the app theme plus the device's system dark theme.
- The moment sheet uses exact typed date/time and quick Today/Now actions rather than a calendar
  widget. This preserves the existing `YYYY-MM-DD`/`HH:mm` capability and validation.
- Category tiles show a category-colour swatch and the user's label rather than invented per-name
  icons; categories have no stored icon. The form does not invent merchant/account fields the
  ledger does not store.
- The source chooser reads the first bounded page of unrecorded note events, as the replaced form
  did; it does not add a new paging feature.
- The owner package and owner data were not used; all writes above are in the isolated package or
  in in-memory test databases.
- The full Android test suite was not rerun. The focused S10.7 suite, the two host tests and the
  compile of all test sources were run.
