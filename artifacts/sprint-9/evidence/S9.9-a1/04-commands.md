# S9.9 — exact commands and observed failures

Worktree `F:/ai-ml/aegis-f`, ref `refs/heads/main`, base `1cd84dd7a14aa4073b8abb74f11f6de180de8ab3`.
Device `AAAJBB4930914141`. Isolated target `io.github.triet4p.aegisf.s812evidence`
(+ `.test`); the owner app was never installed over, cleared, or stopped.

## Host build and focused tests

```
TEMP=F:/s95tmp TMP=F:/s95tmp ./gradlew.bat -PS812_ANDROID_TARGET_PACKAGE=io.github.triet4p.aegisf.s812evidence \
  :app:testDebugUnitTest --tests 'io.github.triet4p.aegisf.provider.*' --no-daemon -q
```
- 108 tests / 0 failures / 0 errors / 3 skipped over the provider classes (the full suite is
  reported separately in the worker record).

```
... :app:compileDebugAndroidTestKotlin
... :app:assembleDebug :app:assembleDebugAndroidTest
```
- BUILD SUCCESSFUL. APK sha256 in the worker record.

## Device install

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```
- `Success` both; `install -r` preserves the isolated app's own data.

## Provider profiles (credential never printed or passed as an argument)

- Repo `.env` was read in memory; only the variable names were printed.
- The two values were streamed over `adb shell` stdin to
  `…/files/s98_secret.env`; the existing S9.8 staging stage read it, saved run-scoped profiles,
  verified the credential, and deleted the file (`no-secret-file` in the listing).
- `s98-deepseek` was activated for the workflow runs; the Gemini profile remains stored.
- No key appears in any artifact, log, screenshot or command argument.

## Instrumented stages

```
adb shell am instrument -w -e s99_stage wire \
  -e class 'io.github.triet4p.aegisf.data.S99WorkflowPhoneInstrumentedTest#workflowEvidence' \
  io.github.triet4p.aegisf.s812evidence.test/androidx.test.runner.AndroidJUnitRunner

adb shell am instrument -w -e s99_stage safety ...            (same class)
adb shell am instrument -w -e s99_stage arrival_a -e s99_live yes ... (same class)
adb shell am instrument -w -e s99_stage arrival_b -e s99_live yes ... (same class)
adb shell am instrument -w -e s99_stage missing_ambiguous -e s99_live yes ... (same class)

adb shell am instrument -w -e s99_stage unsafe -e s99_live yes ...   (see worker record)
adb shell am instrument -w -e class 'io.github.triet4p.aegisf.ui.S99ChatUiInstrumentedTest' ...
```
- Each live stage uses a fresh synthetic Room file that it deletes in `finally`; the persistent
  app database is never cleared.

## Observed harness/test failures (diagnosed, not hidden)

1. Safety stage assertions compared against zero after a transfer confirmation and after a
   cancellation; fixed to compare the pre-action ledger/pending baseline. Product behaviour was
   unchanged.
2. A "retry after cancellation must mint a new run" assumption was wrong: `admitRun` is
   idempotent by request fingerprint. Corrected to assert the documented replay and to use a new
   session for a fresh run.
3. A stale-confirmation probe edited the *capture*; that does not move the draft's review
   revision, so the confirmation was legitimately applied. Corrected to edit the draft review,
   which produces the expected `Conflict("The draft changed under the caller.")`.
4. The synthetic notification text first used a non-bank sender; the production parser did not
   parse it, so arrival reconciliation had nothing to match. Corrected to the bank format the
   existing arrival tests use.
5. Missing/ambiguous: first assertion expected two events; the three-purchase note legitimately
   produces three. The model also chose the designed `ask_user(field=candidate)` path. Assertion
   corrected and the clarification recorded as expected behaviour.
6. Manual baseline harness: a category selection auto-dismisses the picker (no "Done" node), and
   the transaction editor shows a saved-record panel instead of returning to the Manual list.
   Both are visible-state diagnoses; no blind selector retries.

## Real-device input observation (IME)

The device IME (`com.google.android.inputmethod.latin`) autocorrects English words into
Vietnamese when text is injected with `adb shell input text`:

- `Circle` → `Cỉcle`
- `choose` → `chốe`, `existing` → `ẽisting`, `affected` → `afected`,
  `confirm` → `cònirm`, `or` → `ỏ`, `dismiss` → `dimiss`

Every field was dumped and verified before use; corrupted text was never sent. The note was
retyped to an exact ASCII form and saved through the real screen. The prompt is entered through
Compose semantics (no IME) in `S99ChatUiInstrumentedTest`, labelled as a scripted injection.
`cmd clipboard` is not implemented on this device, so no paste path exists. The IME setting was
not changed; `settings get secure default_input_method` remains LatinIME.

## Notes

- No airplane mode, Wi-Fi/mobile-data change, database clear, uninstall or owner-app interaction
  occurred at any point.
