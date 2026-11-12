# S10.6 attempt a1 — verification record

Candidate: the uncommitted working tree at base `6f3459fcbcc6b2827a088c4a9a66f5f46389a139`
(see `source-snapshot.txt`); isolated evidence package `io.github.triet4p.aegisf.s812evidence`.
No commit, staging, push or checkpoint. Owner package and owner data were never installed over,
launched, cleared or instrumented.

## 1. Host build and focused unit tests

```
./gradlew.bat --no-daemon :app:testDebugUnitTest \
  --tests "io.github.triet4p.aegisf.ui.ChatNoteContextTest" \
  --tests "io.github.triet4p.aegisf.ui.ReviewQueueFocusTest" \
  --tests "io.github.triet4p.aegisf.data.BankDraftReviewTest" \
  --tests "io.github.triet4p.aegisf.data.ReviewQueueTest" \
  :app:assembleDebug :app:assembleDebugAndroidTest \
  -PS812_ANDROID_TARGET_PACKAGE=io.github.triet4p.aegisf.s812evidence --console=plain
```

Result: **BUILD SUCCESSFUL**. The focused host tests passed:
`ChatNoteContextTest` (5, including the new draft-context request case),
`ReviewQueueFocusTest` (9), `BankDraftReviewTest` (5), `ReviewQueueTest` (7).

`PendingBankDraftDeduplicationTest` was also run once and its 6 failures were all
`android.database.sqlite.SQLiteCantOpenDatabaseException` / `SQLITE_CANTOPEN` raised inside
Robolectric when it opens a temporary database file. It is an environment/harness failure of an
unchanged backend test, not a review change, and is not counted as a pass here. The remaining
host tests in the same invocation (`BankDraftReviewTest`, `ReviewQueueTest`) passed.

The isolated debug APKs were built from this source with
`-PS812_ANDROID_TARGET_PACKAGE=io.github.triet4p.aegisf.s812evidence`. The installed copies were
hashed from their device paths; see `apk-sha256.txt`.

## 2. Native instrumented run

```
adb shell am instrument -w \
  -e class "io.github.triet4p.aegisf.ui.S106ReviewInstrumentedTest,
            io.github.triet4p.aegisf.ui.S95ReviewRefreshPhoneInstrumentedTest,
            io.github.triet4p.aegisf.ui.S97ReviewRevalidationPhoneInstrumentedTest" \
  io.github.triet4p.aegisf.s812evidence.test/androidx.test.runner.AndroidJUnitRunner
```

Result: **OK (7 tests)** in 39.5 s, device HONOR ALT-LX2 (`ALT_LX2`), API 34, 720 × 1610 px,
320 dpi. Raw output: `logs/instrumentation-final.txt`.

The real `ReviewInboxScreen` and `DraftDetailScreen` are rendered through the production theme
over isolated in-memory Room databases; every assertion below also reads the stored rows and the
confirmed-ledger aggregate, so the screen is never the only witness.

### 2.1 Inbox grouping, order, navigation (`inboxGroupsEveryReasonAndNavigationKeepsTheChosenOrder`)

Fixture: six synthetic drafts — a ready expense with a chosen category, a draft with no category,
an unsupported/unreadable bank field, two equal-evidence drafts (the later one is a possible
duplicate), and one draft with a proposed note link. Observed and asserted:

- the sections **Needs your decision**, **Needs a category** and **Ready to confirm** are all
  rendered, and the count says **6 drafts waiting**;
- the order control switches to **Oldest first**;
- opening the ready draft shows its exact **500,000 ₫** amount; system Back returns to the inbox,
  the same ready row is still composed, and the chosen **Oldest first** order is still shown.

### 2.2 Explicit confirmation and dismissal (`confirmWritesTheExactTotalOnceAndDismissWritesNothing`)

Before: confirmed aggregate **spending 0 ₫, income 0 ₫**, transaction rows **0**.

- Opening the ready expense and pressing **Confirm and add to ledger** shows the preview
  **Confirm this record?**; its Confirm writes the record.
- After: **spending 500,000 ₫, income 0 ₫**, transaction rows **1**, draft status **CONFIRMED**.
  No second record exists.
- Opening the ready income and choosing **Dismiss** → **Dismiss draft** returns to the inbox with
  **spending 500,000 ₫, income 0 ₫**, transaction rows still **1**, draft status **DISMISSED**.
- The same drafts opened again show the truthful terminal copy for confirmed and dismissed
  drafts; neither offers the confirm action.

### 2.3 Category/link edits do not move totals; stale preview refuses
(`categoryAndLinkEditsDoNotMoveTotalsAndAStalePreviewRefuses`)

Before: **0 ₫ / 0 ₫**, zero rows.

- Changing the draft's category to **Fuel** through the picker and saving corrections shows
  **Corrections saved.**; the stored category is Fuel and the draft stays PENDING. Totals remain
  **0 ₫ / 0 ₫**, rows **0**. The Save action is re-enabled after the write.
- Attaching a proposed note excerpt shows **Note excerpt attached. The bank amount and ledger are
  unchanged.**; the stored link state is **ACCEPTED**. Totals remain **0 ₫ / 0 ₫**.
- On a ready expense, the preview is opened and then the draft is changed concurrently through
  `saveDraftReview` (a real revision move). The dialog becomes **Preview is stale**, labels the
  values as **Values from the earlier preview (not saved)**, and offers only **Check current
  values** — no Confirm tag exists. Totals remain **0 ₫ / 0 ₫**, rows **0**.
- Pressing **Check current values** produces a fresh preview; its Confirm writes exactly one
  record: **spending 60,000 ₫, income 0 ₫**, rows **1**.

### 2.4 Vietnamese, 1.5× text and the keyboard
(`vietnameseLargeTextAndKeyboardKeepTheReviewActionReachable`)

- The inbox and detail render in Vietnamese at `fontScale = 1.5`; the inbox title and destination
  labels fit, and the detail title is **Biến động**.
- Focusing the amount field opens the numeric keyboard; after the IME inset settles the focused
  field is displayed above the keyboard and the sticky **Xác nhận ghi vào sổ** action remains
  above the keyboard and displayed. Dismissing the keyboard keeps the action displayed.

### 2.5 Truthful missing and read-error states (`missingAndUnreadableDraftsSayWhatHappened`)

- A detail route for a non-existent id renders **This draft is not here** with the explanation and
  Back to inbox.
- Closing the database before opening a detail renders **This draft couldn't be read** with
  Retry/Back instead of a crash. This covers the Room flow-creation failure found while writing
  the test.

### 2.6 Migrated earlier review regressions

- `S95ReviewRefreshPhoneInstrumentedTest`: the visible draft changes group and category line
  after `selectDraftCategory` without refresh or navigation; stored draft stays PENDING and the
  ledger stays empty.
- `S97ReviewRevalidationPhoneInstrumentedTest`: a hosted category is shown (origin HOSTED), then
  editing the linked note clears it and the card returns to **Category: not chosen**; stored
  category/resolution are cleared and no transaction exists.

## 3. Screenshots

All 16 PNGs in `screens/` were taken from the installed isolated APK on the device. They are
listed with what each proves in `visual-review.md`.

## 4. Environment

HONOR ALT-LX2, Android API 34, 720 × 1610 px, 320 dpi; English and Vietnamese; isolated package
`io.github.triet4p.aegisf.s812evidence`. No network request is made by review; the evidence
fixtures are in-memory Room databases and model no real bank notification.

## 5. Limits and uncompensated points

- The bank evidence is synthetic. It exercises the production parser's stored-field shapes but
  does not replay an actual bank notification listener post; intake itself is Sprint 9 evidence.
- The raw notification body is intentionally not stored, so the screen shows the parsed fields
  and says so; it never fabricates an SMS body.
- The loading state is implemented (`Reading the review queue…` / `Reading this draft…`) but the
  in-memory fixtures resolve before a stable loading frame can be captured, so it is not
  separately photographed. The empty, missing, read-error, terminal and concurrent states are;
  a fully empty inbox is likewise implemented but not separately captured.
- Native **Reject** and **Unlink** taps are not among the final captures. Attach is native
  (ACCEPTED, totals 0/0) and Unlink is visible; Reject/Unlink use the same revision-checked
  command path covered by host refusal/unlink/conflict tests and are not claimed as native taps.
- Offline review is architecture-backed (Room-only; the review path makes no network call) and
  was not exercised in airplane mode; it is not claimed as an offline toggle run.
- The screen-level Back/state restoration is exercised in a host that mirrors the shell's
  `SaveableStateHolder`; the shell's own bottom-bar hiding for child routes is unchanged S10.2
  behavior and is not re-captured here. The detail screen installs its own Back handler and guard.
- The test harness's Compose clock must be pumped after the IME animation; an earlier capture was
  a frozen mid-animation frame. The final capture and the `assertIsDisplayed` checks were made
  after pumping.
- The `PendingBankDraftDeduplicationTest` Robolectric open failure is an environment limitation,
  recorded above and not claimed as a pass.
- The full Android test suite was not rerun; the two migrated review tests and the five new S10.6
  tests were run explicitly, and the focused host tests listed above.
