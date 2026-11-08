# S9.8-a2 — commands and observed failures

Every command below ran on this workstation against worktree `F:/ai-ml/aegis-f` or device
`AAAJBB4930914141`. Device target: `io.github.triet4p.aegisf.s812evidence` (+ `.test`). The owner
app `io.github.triet4p.aegisf` was never opened, stopped, cleared or uninstalled.

## Host

```
TEMP=F:/s95tmp TMP=F:/s95tmp ./gradlew.bat -PS812_ANDROID_TARGET_PACKAGE=io.github.triet4p.aegisf.s812evidence \
  :app:assembleDebugAndroidTest --no-daemon -q
```
- Used repeatedly while building the S9.8 harness. Final runs: BUILD SUCCESSFUL.

```
TEMP=F:/s95tmp TMP=F:/s95tmp ./gradlew.bat -PS812_ANDROID_TARGET_PACKAGE=io.github.triet4p.aegisf.s812evidence \
  :app:testDebugUnitTest --tests 'io.github.triet4p.aegisf.agent.AgentToolRuntimeTest' --no-daemon -q
```
- 38 tests / 0 failures (while the (now reverted) coercion existed; the final tree has no coercion).

```
TEMP=F:/s95tmp TMP=F:/s95tmp ./gradlew.bat -PS812_ANDROID_TARGET_PACKAGE=io.github.triet4p.aegisf.s812evidence \
  :app:testDebugUnitTest --tests 'io.github.triet4p.aegisf.provider.*' \
  --tests 'io.github.triet4p.aegisf.agent.AgentToolRuntimeTest' \
  --tests 'io.github.triet4p.aegisf.agent.BoundedWorkflowExecutorTest' --no-daemon -q
```
- 174 tests / 0 failures / 0 errors / 3 skipped (after the schema fix).

Final gate (single run over the final sources):

```
TEMP=F:/s95tmp TMP=F:/s95tmp ./gradlew.bat -PS812_ANDROID_TARGET_PACKAGE=io.github.triet4p.aegisf.s812evidence \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
```
- BUILD SUCCESSFUL in 4m 6s; test XML totals 60 classes / 577 tests / 0 failures / 0 errors /
  3 skipped; lint report 0 error-severity, 18 warnings, 2 hints.
- APK sha256: app `fe854a2f3f4784cd60537be8c5a6b45038bbaa72a211b184fbd263960b9f52d1`,
  androidTest `21b49b657cb31fa5dd2e6d669b35e99e569d75180369f3b77071642deae217c3`.

## Device install

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```
- `Success` each time. `install -r` keeps the isolated app's own data.

## Instrumented stages (all via `s812evidence.test/androidx.test.runner.AndroidJUnitRunner`)

```
adb shell am instrument -w -e class 'io.github.triet4p.aegisf.agent.S98CapabilityPhoneInstrumentedTest#providerInventoryAndOfferedMatrix' ...
adb shell am instrument -w -e s98_stage add_profiles_from_secret -e class io.github.triet4p.aegisf.data.S98DeviceFixtureInstrumentedTest ...
adb shell am instrument -w -e s98_stage anchor_contract -e class io.github.triet4p.aegisf.data.S98DeviceFixtureInstrumentedTest ...
adb shell am instrument -w -e s98_live yes -e s98_profile s98-deepseek -e s98_lang en \
  -e class io.github.triet4p.aegisf.agent.S98CapabilityPhoneInstrumentedTest#liveLanguageMaskingAndFollowUpMatrix ...
adb shell am instrument -w -e s98_live yes -e s98_profile s98-deepseek -e s98_lang en_grounded ...   (same class)
adb shell am instrument -w -e s98_live yes -e s98_profile s98-deepseek -e s98_lang vi_grounded ...   (same class)
adb shell am instrument -w -e s98_stage ui_seed_grounded -e class io.github.triet4p.aegisf.data.S98DeviceFixtureInstrumentedTest ...
adb shell am instrument -w -e s98_stage activate_profile -e s98_profile_id s98-deepseek ...
adb shell am instrument -w -e s98_stage ui_verify -e class io.github.triet4p.aegisf.data.S98DeviceFixtureInstrumentedTest ...
adb shell am instrument -w -e s98_stage trace_latest -e class io.github.triet4p.aegisf.data.S98DeviceFixtureInstrumentedTest ...
adb shell am instrument -w -e s98_stage restore_active ...
adb shell am instrument -w -e s98_stage remove_profile -e s98_profile_id s98-gemma|s98-deepseek|s98-luna ...
adb shell am instrument -w -e s98_stage verify_config ...
```

Credential transfer (values never printed, never in an argument):

- Repo `.env` read in memory by an `eval` process; only variable names printed
  (`GOOGLE_AI_STUDIO_KEY`, `OPENCODE_GO_API_KEY`).
- Streamed over `adb shell` stdin to
  `/data/data/io.github.triet4p.aegisf.s812evidence/files/s98_secret.env` (147 bytes); the
  instrumentation read it, saved run-scoped profiles, verified `credentialMatch=true` and deleted
  the file (`secretFileDeleted=true`). Final listing has no `s98_secret.env`.

## UI run (real Compose Chat, isolated app)

```
adb shell am force-stop io.github.triet4p.aegisf.s812evidence
adb shell am start -n io.github.triet4p.aegisf.s812evidence/io.github.triet4p.aegisf.MainActivity
adb shell input tap 273 1485            # Chat tab
adb shell input swipe 360 1200 360 500  # reveal skill list
adb shell input tap 112 <y>             # SEARCH DRAFTS / LINK / DRAFT CATEGORY REVIEW
adb shell input swipe 360 300 360 1300  # INTERPRET EVENTS already checked
adb shell input tap 360 680             # prompt field
adb shell input text "Read%sthe%ssaved%snote,..."   (exact request; composing IMEs disabled)
adb shell input keyevent 4              # hide the voice IME panel
adb shell input tap 146 851             # Send
adb exec-out screencap -p > ui_XX_*.png
adb shell uiautomator dump /sdcard/ui.xml && adb exec-out cat /sdcard/ui.xml > ui_XX_*.xml
```

IME handling (temporary, restored): `ime disable` for LatinIME and SwiftKey, then
`ime enable` + `ime set ...LatinIME`; final `settings get secure default_input_method` returned
`com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME`.

## Observed failures (preserved)

1. Pre-fix VI run: `TURN_BUDGET`, 0 events; model sent `end_date_ref:"null"`/`end_time_ref:"null"`
   text (turns 2/3/4/8), `cursor:"null"` (turn 9), `""` (turn 11), then `masking_blocked
   UNKNOWN_TOKEN`.
2. Note-level-only EN note, production `resumeTracked(2026-11-06)`: snack `record_event` refused
   `REFERENCE_KIND_MISMATCH $.anchor_ref anchor_from_selected_occurrence` at turn 12, then
   `TURN_BUDGET`; snack link absent.
3. UI attempt 1: `TRUNCATED_TURN` (run `9ea5f07b`), 2541 chunks, `completion_tokens=8192`,
   0 tool calls, saved state unchanged.
4. UI attempt 2: `TRUNCATED_TURN` (run `363a81e9`), 2153 chunks, `prompt_tokens=14840`,
   `completion_tokens=8192`, `reasoning_tokens=8192`, 0 tool calls, saved state unchanged.
5. `ui_verify` asserted after the UI attempts (by design) because the UI run saved no event; its
   emitted rows are the evidence (`events=0`, `acceptedLinks=[]`, `ledger=2 baseline=2`).
6. `verify_config` failed while the three temporary profiles still existed (expected); it passed
   after they were removed.
7. Prompt typing through the device IME produced Telex-mangled text; that input was discarded
   (force-stop, field verified empty) and never sent. The exact text was later entered with
   composing IMEs disabled and re-dumped before Send.
8. An intermediate coercion-based VI run completed; that change was reverted per Main's direction
   and is not used as candidate proof.
