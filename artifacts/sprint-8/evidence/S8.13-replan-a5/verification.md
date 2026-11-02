# S8.13 replan-a5 — controlled device correction evidence

**Task/attempt/agent:** `S8.13` / `S8.13-replan-a5` / `S813PhoneSilver05` (Silver). The owner’s “Đã connect lại” authorization lifted the prior device hold. No new hosted API call, batch, fixture/scoring/threshold change, or checkpoint was made.

## Prior history acknowledged

Read all requested handoffs and direct histories before source work:

- `artifacts/sprint-8/handoffs/S8.13-replan-a1.md` and `history://S813QualificationSilver01`: existing isolated qualification runner and one hosted 24-case run were preserved as historical, below-gate evidence; no qualification PASS.
- `artifacts/sprint-8/handoffs/S8.13-replan-a2.md` and `history://S813FixtureSilver02`: the attempt paused before build/device work; its projection and source edits were unverified at that boundary.
- `artifacts/sprint-8/handoffs/S8.13-replan-a3.md` and `history://S813ResumeSilver03`: recorded hosted runs remained below the frozen gates; local Laya/oversize proof and read-only diagnosis were preserved, with no source change or PASS.
- `artifacts/sprint-8/handoffs/S8.13-replan-a4.md`, `artifacts/sprint-8/workers/S8.13-replan-a4.md`, `artifacts/sprint-8/evidence/S8.13-replan-a4/verification.md`, and `history://S813ContractSilver04`: A4’s contract corrections and controlled host 40/40 are prior evidence only. A4 did not run the device smoke and did not directly induce `masked_result_refused`.
- `docs/sprint-plans/sprint-8.md:53-65` and `artifacts/sprint-8/state/S8-replan-main.md:360-369`: the correction smoke is not hosted qualification; cycle-1 round 1 remains unassessed, and the separate Bronze exact-snapshot checkpoint remains pending.

All supplied references were accessible; no required history/handoff was missing. The S8.13 round/qualification state was not changed.

## Source and APK coherence

Reviewed the A4 v2 adapter/executor implementation and canonical runtime declarations/skills, including the tool adapter, date-anchor path, durable operation signature mapping, retry verification path, and terminal refusal set. `app/build.gradle.kts` copies the v2 declarations and runtime skills into the production asset set and uses the isolated package suffix property. The temporary smoke loaded `S8AgentToolDeclarations` and `WorkflowSkillCatalog` from those APK assets.

Final build command:

```text
powershell.exe -NoProfile -Command "$env:JAVA_HOME='C:\Program Files\Java\jdk-21'; $env:ANDROID_HOME='C:\Users\admin\AppData\Local\Android\Sdk'; $env:ANDROID_SDK_ROOT=$env:ANDROID_HOME; & '.\gradlew.bat' --no-daemon --max-workers=1 ':app:assembleDebug' ':app:assembleDebugAndroidTest' '-PS813_ANDROID_TARGET_PACKAGE=io.github.triet4p.aegisf.s813evidence' '--console=plain'; exit $LASTEXITCODE"
```

Observed `BUILD SUCCESSFUL` for `:app:assembleDebug` and `:app:assembleDebugAndroidTest`. `aapt2` verified:

- app APK package: `io.github.triet4p.aegisf.s813evidence`
- test APK package: `io.github.triet4p.aegisf.s813evidence.test`
- test target: `io.github.triet4p.aegisf.s813evidence`
- runner: `androidx.test.runner.AndroidJUnitRunner`

SHA-256: app `8ed68b03ca8f4bb67d91ef243f0fe6e806959448a056d44beb3795fe8539be43`; test `4233b46fb1d24d72e6129a5811364fcf36e3550eccb1b03b32a84951639a203f`. SDK emitted a non-blocking SDK XML v4 compatibility warning (the processor reports support through v3). The first temporary-test build failed on using the wrong query-service signatures; after correcting the harness, all subsequent required APK builds succeeded.

## Device command and result

Before device mutation, `adb devices -l` showed `AAAJBB4930914141` online (`ALT-LX2`, `ALT_LX2`). Repeated read-only checks returned `get-state=device`, serial `AAAJBB4930914141`, model `ALT-LX2`, Android `14`. Read-only `pm path` confirmed the isolated app/test packages and base package were installed. Installed/reinstalled only the isolated APKs with `adb install -r`; each install reported `Success`. The base app was not installed, cleared, or otherwise mutated; no `pm clear` was issued.

Final instrumentation command:

```text
adb.exe -s AAAJBB4930914141 shell am instrument -w -e class io.github.triet4p.aegisf.agent.S813CorrectionDeviceSmokeInstrumentedTest io.github.triet4p.aegisf.s813evidence.test/androidx.test.runner.AndroidJUnitRunner
```

Final output: `OK (1 test)`. The test created evidence under isolated app-private files and the host collected it with `adb shell run-as io.github.triet4p.aegisf.s813evidence cat files/S8.13/S8.13-replan-a5/device-contract-smoke.json`. No screenshot was needed; this was a service/Room contract smoke, not a UI change.

## Assertions observed on the actual Android runtime

The throwaway instrumented test exercised `BoundedWorkflowExecutor.startWithAttemptKey`, a durable `AgentRunHistory` attempt, the production `MaskedHostedGateway`, canonical declarations/skills, the real `BoundedWorkflowToolAdapter`, `WorkflowCommandService`, `WorkflowQueryService`, and in-memory Room. Provider turns came only from an in-process scripted adapter; no network or hosted call was available in this scenario.

Observed sequence and assertions:

1. Two distinct event mutations in one durable workflow both returned `applied`; their durable operation identities were distinct.
2. An exact repeat returned the actual `no_change`. Operation-row count was 3 before and after replay (including one fixture-seeding row), and the captured event state was unchanged.
3. A stale event revision returned `conflict` with a fresh masked capture/event observation. The corrected same-target call took its capture/event revisions from that observation and returned `applied`; final `Completed` required the executor’s normal postcondition read.
4. Stored date-only event had no `anchorTime` or `anchorTimezone`; the timed event had the trusted `Asia/Ho_Chi_Minh` timezone. The corrected repair target’s stored merchant was asserted to be `Meal`.
5. Four stored `UPDATE_EVENT` operation rows remained after the workflow (one seed, two distinct workflow writes, one corrected workflow write); the conflict did not add an operation row. There were zero ledger rows, link operations, classification operations, or raw sensitive provider egress.
6. Durable attempt reconciliation was `APPLIED`; outcome was `COMPLETED`, with four verified event-reading dispatches. The scripted adapter handled 6 provider-turn requests and 5 tool-result continuations.

The exact sanitized record is `device-contract-smoke.json`; command, device, identity, install, and instrumentation details are in `device-smoke-host.json`.

## Harness-only failures retained

- Device smoke attempt 1 created two event readings and then revised the first one in the same run. The executor verifies every prior `applied` dispatch against its exact stored revision (`BoundedWorkflowExecutor.verifyEvent` requires equality); that scenario correctly ended incomplete. The harness was reshaped to seed the stale-revision target before the run, then exercise conflict → fresh observation → corrected success, without weakening product verification.
- Attempt 2 reached `COMPLETED` with the intended `applied, applied, no_change, conflict, applied` statuses, 6 turns, 5 continuations, and 4 operation rows, but the temporary harness incorrectly expected a `Coffee` merchant on the separate date-only row. That unrelated assertion was removed. The sanitized attempt record is `device-smoke-attempt-02.json`.
- Attempt 3 passed the selected instrumented class, `OK (1 test)`, with the final assertions. The temporary test source was removed after proof; it is not a permanent regression test.

## Remaining limitations

- The changed `masked_result_refused` terminal path was inspected but **not directly induced**. In the real typed adapter, string-bearing service fields are masked as they are constructed and the defensive final `maskResult` catches failures; I found no safe valid typed result that reaches that catch without injecting malformed adapter/service output. I did not add a test-only production seam, fabricate an output, or weaken the masking boundary. Thus no direct no-further-dispatch assertion for that specific refusal is claimed. The existing over-budget refusal is not treated as proof of this branch.
- Prior `BoundedWorkflowToolAdapterTest` Robolectric setup failures and the unrelated `SQLiteCantOpenDatabaseException` / `SQLITE_CANTOPEN` remain unresolved; neither was rerun. A4’s 40/40 host result is preserved as prior evidence, not rerun or enlarged by this device smoke.
- Relative targets remain clarification-only without the exact authorized same-session DATE token. Frozen “Yesterday”, busfare-versus-coffee, and link amount/candidate-ID contradictions remain unchanged and disclosed; they did not require unsafe device effects for this smoke.
- Full hosted qualification remains unmet. No hosted request/batch, scoring/threshold/fixture edit, `ready_for_review`, PASS, checkpoint, commit, or S8.14 advancement occurred.
