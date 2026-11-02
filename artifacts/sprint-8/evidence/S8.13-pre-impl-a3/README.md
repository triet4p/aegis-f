# S8.13-pre-impl-a3 evidence

Task `S8.13-pre`, attempt `S8.13-pre-impl-a3`, agent `S813PreInterfaceGold03` (Gold, full route).
Owner-approved design: `docs/sprint-8-agent-interface-redesign.md`. No commit, stage, push, base-app
data clear or qualification batch was performed. Frozen S8.4 fixtures and scores are unchanged.

## Files

| File | Meaning |
| --- | --- |
| `unit-results/TEST-*.xml` | Full `:app:testDebugUnitTest` result set: 564 tests, 0 failures, 3 skipped. |
| `laya-projection-instrumentation.log` | Isolated phone run of `S813LayaProjectionSmokeInstrumentedTest`: `OK (1 test)`. |
| `laya-smoke.json` | Device evidence pulled from the isolated app: actual local Laya distribution/snapshot, deterministic oversize refusal, and the unique/duplicate/financial link preflight. |
| `durable-device-instrumentation.log` | Isolated phone run of `S810DurableDeviceInstrumentedTest`: `OK (2 tests)`. |
| `s89-executor-laya-instrumentation.log` | Isolated phone run of `S89ExecutorLayaInstrumentedTest`: `OK (2 tests)`. |
| `s87-laya-adapter-instrumentation.log` | Isolated phone run of `S87LayaAdapterInstrumentedTest`: `OK (1 test)` after updating the historical raw-span expectation to the v3 grounded summary. |
| `hosted-phone-smoke-instrumentation.log` | Isolated phone run of `S813HostedPhoneSmokeInstrumentedTest`: `OK (1 test)`, 60 s; repeated twice on the final APK with identical outcomes. |
| `hosted-phone-smoke.json` | Bounded real-phone DeepSeek evidence: outcomes, verified effects, postconditions and masked wire attempts. |

## Exact verification

```powershell
cmd /c gradlew.bat --no-daemon --max-workers=2 --console=plain :app:compileDebugKotlin :app:compileDebugUnitTestKotlin
cmd /c gradlew.bat --no-daemon --max-workers=2 --console=plain :app:testDebugUnitTest
cmd /c gradlew.bat --no-daemon --max-workers=2 --console=plain :app:compileDebugAndroidTestKotlin
cmd /c gradlew.bat --no-daemon --max-workers=2 --console=plain -PS813_ANDROID_TARGET_PACKAGE=io.github.triet4p.aegisf.s813evidence :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class io.github.triet4p.aegisf.agent.S813LayaProjectionSmokeInstrumentedTest io.github.triet4p.aegisf.s813evidence.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -r -e class io.github.triet4p.aegisf.agent.S810DurableDeviceInstrumentedTest io.github.triet4p.aegisf.s813evidence.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -r -e class io.github.triet4p.aegisf.agent.S89ExecutorLayaInstrumentedTest io.github.triet4p.aegisf.s813evidence.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -r -e class io.github.triet4p.aegisf.agent.S87LayaAdapterInstrumentedTest io.github.triet4p.aegisf.s813evidence.test/androidx.test.runner.AndroidJUnitRunner
```

Observed APK identity: `io.github.triet4p.aegisf.s813evidence` (versionCode 4) and
`io.github.triet4p.aegisf.s813evidence.test`. Device `AAAJBB4930914141` (ALT-LX2, Android 14).
Install used `-r`, so the isolated package's data was preserved and the base package was untouched.

Pinned Laya bundle staged from
`F:/llms/hf/models/hub/models--receptron--laya-onnx/snapshots/68f27dfe5a27a54fb2b1fefc432f43f972e90868`
(`laya.onnx` 3,807,291 B; `laya.onnx.data` 1,685,258,240 B; `laya_config.json` 369 B), provisioned by
the production `LayaModelProvisioner`; the staging directory and `/data/local/tmp` copies were removed
after the runs.

## Bounded real-phone hosted smoke

`S813HostedPhoneSmokeInstrumentedTest` ran sequentially with one credential read over a one-shot
`adb reverse` loopback channel into the test process memory. The credential was never an
instrumentation argument, file, log line, or persisted value; `RecordingTransport` removes the
Authorization header before recording, and a programmatic check confirmed that the credential and
the string `Bearer ` are absent from every evidence/log file (`credential_recorded=false`). The
`authorization_recorded=true` marker means the header was **seen** on the request and stripped; the
recorded headers contain no Authorization entry.

Provider binding: product Go/Zen `https://opencode.ai/zen/go/v1/chat/completions`, model
`deepseek-v4.1-flash`, OpenAI Chat Completions, output cap 8192, temperature 0.1, sequential calls.
Result: 13 HTTP 200 attempts per run, with two consecutive identical runs on the final APK.

1. `normal_no_merchant_then_exact_replay` → `Completed`, 6 turns / 5 calls; stored amount 28,000,
   merchant null, unresolved null, date `2026-10-21`, time `12:30`; one `UPDATE_EVENT` row; zero
   ledger rows; verified `EventReading` plus `ReplayIdentity(current_effect_matches=true)`.
2. `partial_then_clarification_resume` → first `Clarifying(field=amount)` with a stored
   `UNRESOLVED` partial; resume with `28,000` → `Completed`; verified corrected reading revision 1,
   amount 28,000, unresolved null, status `INTERPRETED`; zero ledger rows.

This smoke is not a qualification batch, does not score frozen cases, and does not change any S8.4
result. `S813QualificationInstrumentedTest` was compiled but deliberately not run: its fixtures are
historical v2 shapes and a future qualification needs a separately approved v3 fixture/scoring
mapping (including journal-backed replay).

## Runtime checks covered by the green suites

- Eleven-tool closed catalog, flat strict schema, selective loading, unknown/unloaded refusal.
- Opaque references: wrong kind, foreign session, stale capture/event/draft/link, cursor filters.
- Exact masked quote selection, occurrence, token-boundary and UTF-16 translation; expected absence
  vs an event appearing under the same span (transactional CAS).
- Normal no-merchant reading; multi-event capture; partial amount + clarification minting and
  resume; typed amount/date/time clarification evidence.
- Shared trusted link eligibility: eligible propose/attach, ineligible refusal, stale attach,
  no override, duplicate/mismatch preflight on device.
- Local Laya: stored description only, actual model distribution on device, unavailable/manual,
  deterministic oversize refusal with no snapshot/operation/ledger effect.
- Journal replay: exact scope/command/digest, `no_change`, one row, current-effect true and false.
- Postcondition re-read, superseded proposal/event/classification claims, loop/turn/tool/context
  budgets, truncated/filtered turns, provider failure classification.
- Post-write masking terminal stop with local reconciliation (raw literal never crosses the
  boundary and no retry creates a duplicate).
- Durable history: repeated send replays one run, interrupted write reconciles, cancel leaves the
  stored effect, late reply cannot settle, retention/export excludes keys and mappings.
- Cache identity includes contract/tools/skills/privacy and changes when semantics change; Gemini,
  Chat Completions and Responses declaration lowering covered by provider tests (Gemini was not
  called live in this attempt).

## Snapshot and ownership

- Repository/worktree: `F:/ai-ml/aegis-f`; Git dir `F:/ai-ml/aegis-f/.git`; target `refs/heads/main`;
  base commit `14f313e` plus the pre-existing uncommitted S8.13 work and this attempt's edits.
- No `git add`, commit, push, reset, stash, checkout or base-app data clear.
- Foreign/unrelated working-tree changes (`.agents/memory/*`, `docs/PLAN.md`, sprint plans, Sprint 7
  evidence) were preserved.


## Candidate snapshot

- Base commit `14f313eab95213339b88500e3a98b0a17d7373c8`; no commit/stage/push.
- Source snapshot digest `98e85ec94f8eeb00faf3d244cf1b2367def711953ff425e27787758609f39916` over the sorted status plus untracked source object hashes for
  `app/`, `docs/`, `tools/`, `CHANGELOG.md`; manifest `source-snapshot.txt` in this directory.
