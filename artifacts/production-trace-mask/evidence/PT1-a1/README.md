# PT1-a1 verification evidence (non-phone)

Owner directive at the time of this attempt: the phone is disconnected, so no ADB/device or
live-provider/credential access was performed and actual installed-main UI/DB acceptance is
explicitly pending. Everything below is host/JVM verification of the production code path.

## Provenance

- Repository `F:/ai-ml/aegis-f`, git dir `.git`, worktree same, base HEAD
  `12f683205593b548556bd4ed6e533466bc3583c7`, no commit/staging/push.
- JDK `C:/Program Files/Java/jdk-21`; commands were run via
  `powershell -NoProfile -Command "$env:JAVA_HOME='C:\Program Files\Java\jdk-21'; .\gradlew.bat … --offline"`.
- Frozen candidate manifests are in `artifacts/production-trace-mask/workers/PT1-a1.md`
  (SHA-256 per owned path).

## Final verification (frozen candidate)

Command:

```
gradlew.bat :app:testDebugUnitTest --offline
```

Observed: `BUILD SUCCESSFUL in 2m 55s`; Gradle reported the task as the only executed task of the
run. JUnit XML for all 68 test classes is preserved verbatim in `junit-final/`.

- 68 test classes, **633 tests, 0 failures, 0 errors, 3 skipped**.
- The 3 skips are the env-gated live provider smokes, unrun because
  `AEGIS_S85_LIVE_PROTOCOL_SMOKE` was not set (no credential was read or used):
  `HostedProtocolLiveSmokeTest` — Gemini generateContent, OpenAI Chat Completions, OpenAI Responses
  two-turn tool-result exchanges.
- Includes every migration-chain test: `MigrationChainTest` (v6→v14), `AgentRunMigrationTest`
  (v12→v14), `AgentTraceMigrationTest` (v13→v14 populated upgrade, gap markers, cascade, rollback).
- Includes the PT1 tests: `ProductionTraceTest` 4/4, `AgentTraceStoreTest` 3/3,
  `S810DurableRunHistoryTest` 6/6, `S811ContextCacheTest` (all).

Compile-only check on the same candidate:

```
gradlew.bat :app:compileDebugUnitTestKotlin --offline
```

Observed: `BUILD SUCCESSFUL` (main + unit-test Kotlin sources; the only warning is the pre-existing
`MigrationChainTest.kt:900` nullable-string warning).

## Intermediate runs and how each discrepancy was resolved (provenance, not final evidence)

1. Focused migration/trace/history run — 18 tests, 2 failed, both in the then-new
   `ProductionTraceTest`: the read-only tool envelope reports `effect = "none"` (not `no_effect`),
   and the continuation request's last message is the host token-inventory user turn, not the tool
   result. Both were test-expectation bugs, fixed in the test only; the production code was not
   changed for them.
2. First full suite — 630 tests, 2 failed, 3 skipped. Both failures were `S811ContextCacheTest`
   asserting `context_block_version: s8-agent-context/v6` while the accepted HEAD source already
   declared `s8-agent-context/v7` (`git show HEAD:` on both files; neither is a PT1 file). Per Main's
   direction the two obsolete exact-version assertions were deleted (no version constant re-pinned);
   all behavioral assertions in those tests remain.
3. `AgentTraceStoreTest` first run — plain-JVM `org.json` "not mocked"; the class now uses the
   Robolectric runner like every other JSON test. Production code unchanged.

No test result was discarded or rerun to manufacture a pass: the final suite is the complete run of
the frozen candidate, and the intermediate outcomes are recorded above for audit.

## Not run (explicit)

- Actual installed main app (`io.github.triet4p.aegisf`) UI/DB acceptance: **pending** — phone
  disconnected, no ADB attempt, no credential export, no financial confirmation.
- Any live hosted-provider call: not run (no credential use while disconnected).
- No device/instrumentation smoke was invoked for this attempt.

## Seed / safety notes

- No credential, auth header, personal DB or wire dump is present in this directory. Only JUnit XML
  from synthetic-fixture tests (test names, durations, outcomes) and this summary.
