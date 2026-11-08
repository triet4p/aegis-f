# S9.7-a1 verification

- Base: `820eac95ccfe229463ab1c53cba90f8b0719e57a` (worktree `F:/ai-ml/aegis-f`, ref `refs/heads/main`).
- Isolated package: `io.github.triet4p.aegisf.s812evidence` (+ `.test`). Owner main app
  `io.github.triet4p.aegisf` was never opened, cleared, exported, or uninstalled.
- Host environment: Windows, `TEMP=F:/s95tmp TMP=F:/s95tmp`, `--no-daemon`.

## Final host verification (bg_117, candidate)

```
TEMP=F:/s95tmp TMP=F:/s95tmp ./gradlew.bat \
  -PS812_ANDROID_TARGET_PACKAGE=io.github.triet4p.aegisf.s812evidence \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
```

- `:app:testDebugUnitTest` — **573 tests, 0 failures, 0 errors, 3 skipped**. Counted from
  `app/build/test-results/testDebugUnitTest/*.xml` after that run.
- `:app:lintDebug` — **BUILD SUCCESSFUL** (`app/build/reports/lint-results-debug.html`).
- `:app:assembleDebug` / `:app:assembleDebugAndroidTest` — **BUILD SUCCESSFUL**.

Final APK hashes (candidate after the corrected staged fixture was rebuilt):

- `app/build/outputs/apk/debug/app-debug.apk`
  `8403a007ce2df2c05bc52d226855e8ac2161750d5772316985cbf7152157b039`
- `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
  `b62539ee9f8a053201a94156e549bd0c21596dfffb4cd7e6cc0f8729f4010e50`

A later edit changed only the staged androidTest fixture (no product source), so the app APK hash
above is the product candidate exercised on the phone.

## Focused host run

```
TEMP=F:/s95tmp TMP=F:/s95tmp ./gradlew.bat \
  :app:testDebugUnitTest --tests "io.github.triet4p.aegisf.data.WorkflowCommandServiceTest" --no-daemon
```

**BUILD SUCCESSFUL** (bg_115). The class now includes the six S9.7 boundary tests.

## Isolated phone (AAAJBB4930914141, ALT-LX2, 720x1610, LatinIME)

Installed with `adb install -r` for both APKs; no uninstall and no data clear.

```
adb shell am instrument -w -e class \
  io.github.triet4p.aegisf.data.S97CorrectionBoundaryPhoneInstrumentedTest \
  io.github.triet4p.aegisf.s812evidence.test/androidx.test.runner.AndroidJUnitRunner
```
**OK (2 tests)** — note edit reopens hosted category, stale hosted write conflicts, confirmed
record keeps ledger row + link evidence + cleared SOURCE_DELETED reading across a database reopen;
user category survives a note delete; stale undo conflicts; current undo keeps the newer user
category; a pre-undo hosted write conflicts.

```
adb shell am instrument -w -e class \
  io.github.triet4p.aegisf.ui.S97ReviewRevalidationPhoneInstrumentedTest \
  io.github.triet4p.aegisf.s812evidence.test/androidx.test.runner.AndroidJUnitRunner
```
**OK (1 test)** — the actual review surface rendered from a synthetic named DB changed the visible
card from `Category chosen — confirm or dismiss` to `Category not chosen yet` without manual
refresh after `reviseCapture`; draft remained `PENDING`, category `NONE`, ledger empty.

Both commands were rerun on the final rebuilt androidTest APK: **OK (2 tests)** and **OK (1 test)**.

Staged device surface:

```
adb shell am instrument -w -e s97_stage seed -e class \
  io.github.triet4p.aegisf.data.S97DeviceSurfaceInstrumentedTest \
  io.github.triet4p.aegisf.s812evidence.test/androidx.test.runner.AndroidJUnitRunner
# ... real Quick Capture edits in PHONE-TRANSCRIPT.md ...
adb shell am instrument -w -e class \
  io.github.triet4p.aegisf.data.S97DeviceSurfaceInstrumentedTest \
  io.github.triet4p.aegisf.s812evidence.test/androidx.test.runner.AndroidJUnitRunner
```
Seed stage **OK (1 test)**; verify stage **OK (1 test)**. The seed and verify stages were run
twice: once with the pre-correction fixture and once with the corrected fixture that seeds unique
run-scoped rows and never clears a database. See `INCIDENT-DB-CLEAR.md` for the disclosed reset
performed by the first seed run.

## Failed attempts preserved

See `FAILED-ATTEMPTS.md`. No host failure was silently discarded; the final candidate is the one
that produced the results above.

## Limits

- The phone proof used scripted ADB actions guided by screenshots/UI dumps, not human/manual use.
- Text typing used one ASCII character per edit; observed text was verified in the dialog before
  saving. The character landed mid-token in the long run id, which is cosmetic and recorded as
  observed.
- No live hosted-provider call was made for this task; the hosted category write path was exercised
  through the same production command boundary with an `AgentSession` scope, and the actual review
  UI revalidation was exercised on device.
- The isolated app database now contains rows from both the pre-correction and corrected seed runs
  because the clear was removed; the first run's rows were not reconstructed or deleted.
