# S8.13 harness a27 evidence

## Status

Implementation and local selection/identity checks are complete, but the acceptance gate is blocked. No fresh `freeze.json` was emitted because snapshot34's actual phone preflight failed in an existing privacy smoke assertion. Main directed stopping at this finding: no more device runs, no skipping/altering the preflight, no product correction, no hosted calls, and no review-candidate submission. This evidence index is not freeze approval.

Invocation used throughout snapshot34:

- attempt: `S8.13-harness-a27`
- snapshot: `S8.13-v3-interface-harness-a27-freeze-34`
- run: `S8.13-live-a27`
- ordered cases: `a8-recover-01`, `a8-retry-01`, `a8-nomatch-01`, `a8h-nomatch-01`

## Consolidated checks and exact outcomes

- Runner `selfcheck`: PASS, 54 checks, no failures, adjudication v5.
- Static preflight: PASS, 16 dev + 8 held-out = 24 mappings, hosted provider calls 0.
- Android app and instrumentation APK build: BUILD SUCCESSFUL; app package `io.github.triet4p.aegisf.s813evidence`, test package `io.github.triet4p.aegisf.s813evidence.test`, runner `androidx.test.runner.AndroidJUnitRunner`.
- Snapshot34 actual phone run: `preflight_failed`; device ALT-LX2 / Android 14; base app unchanged; hosted provider calls 0; credential loaded false; selection, Laya smoke and TEST_ONLY debug proof were collected. Domain proof is absent because the existing `S813V3QualificationPreflightInstrumentedTest` failed at `runSafeResponseLocationSmoke` line 771 on `assertTrue(semanticCases.values.all { it })`. The instrumentation output does not identify which individual `semanticCases` value failed; it is unknown from retained output. No further instrumentation or rerun was authorized.
- No live diagnostic, `reproduce-blocked`, provider request, or hosted scoring run occurred. Freeze/hosted approval was not inferred from the four-case intent.

Selection proof (`snapshot-34/device-preflight/preflight/S8.13-live-a27/selection.json`) records the requested four IDs in exactly that order as both `case_ids` and `selected_executor_case_ids`; it also records `selection_only=true`, zero provider calls, no credential load, and rejection of missing, duplicate, unknown, whitespace, malformed IDs and unsafe run names. The collector retained this file, the same-invocation Laya output and debug proof. Laya and debug proof both carry the same attempt/snapshot/run/case identity. Debug proof reports exact synthetic bodies retained, TEST_ONLY set, credential sentinel absent, credential-bearing body refused, and header values not recorded.

## Exact hashes

Baseline reviewed freeze31:

- freeze file SHA-256: `3eaaccf2640fdfae90e03534e12a7aca0224836e89bd9ec979fa7f063808b446`
- source manifest SHA-256: `edf8b8f8dff96e8c3bf80da568416715d9118fc9a2c84e23fd223fef3854999c` (211 paths)

Snapshot34 build/source record (not a freeze):

- `build.json`: `25ae05669fc3a36e409e41fec2ec9e368c440bdfc96b8bf70eaa0b8adc622ee5`
- current source manifest: `1ffbcbd4daa1fddf4617c86b9dbb9c22462607f3b74faea18f8657fcca839dd8` (232 paths; coverage-expanded versus freeze31)
- app APK: `0860c6b5b807ea96c9e09e57820b7a9aa623838828014a83e4683634c06e4e41`
- androidTest APK: `0cf211f800f57c1b784abafcac2457870721cff1ce0bf695d605223f0bef5215`
- case mapping: `f88c4a7bc8c1c44cfcb0aba91dfa3f196819e27cb305d9ff4553bc9e076378d0`
- static preflight: `6b092da84a6611cd8c2ad43d2827483ffa7c3b946370a652121a4a815ae5d34f`
- path identity proof: `867451d100f9303c2ff718dc7269777694c9874d88fa523d607f42777c3418ab`
- device host record: `c4ef3ba6a1495984b810426cfc3ec1bdb2dc4eec5fc71109d44ad5fa0229e1dc`
- collected selection: `cf8093f2f793eec859f5f8d0f14f6ccd7c1f8c838cc106d7c6706d1a50891ecd`
- collected Laya smoke: `a88752cb34cdec7897cee57742ac973b39a583ff5c0f940aed056574eb868a62`
- collected TEST_ONLY debug proof: `eb4b93b782e58db9965a82af3c80a991e8c2f3887804ef84f63de29ac9119b68`

Earlier a27 local failures are preserved, not overwritten:

- snapshot32 host record: `31f95d6189c73287dfac1d50f4fa3f42310afd7d8218ecb192e2f120b9d41a6f` (fixture key mismatch; no selection/domain/Laya proof).
- snapshot33 host record: `37e9f1a70d86e1cfe2b4b3c384faf3bf3106ce88ae788bac7391ea9aadee2843` (selection and Laya succeeded; V3 instrumentation lost adb TCP 5037 connectivity; debug proof exposed missing exchange append).
- Pre-correction APKs are retained in each snapshot's `build-before-.../` directory with hashes recorded by `sha256sum` at creation.

## Diff scope against freeze31

The expanded source manifest has 232 entries versus 211 in freeze31, with no removals. It adds coverage for previously omitted inputs (including the runner and its credential-loader dependency, `app/src/test`, and the Laya tokenizer asset); additions are not claims of edits to those files. The six changed files overlapping freeze31 are all `app/src/androidTest` plumbing:

| File | freeze31 SHA-256 | snapshot34 SHA-256 |
|---|---|---|
| `S813DebugCapture.kt` | `e16b2e3a54052702fb729228b8c8c7ed8eab7a4aae3a0373da2cf515fdad6b2b` | `106b1f4acedbc2b288fe11c1b30ac127c5d7cc996b984c3fb3dbbfb06f8046db` |
| `S813DebugCaptureInstrumentedTest.kt` | `c48553762808f6dbeb0a6e9d950a2f2807e5a085f10053abc8dc9ac2097132fd` | `1e685f856555597867899330a654476d7c943bbeef4a6d385a7d0bfa11a05f70` |
| `S813DiagnosticPlumbing.kt` | `464abd7d7fe69a688c43dcf790d32cca1761b088dd1d874c4094b66bb820c0d8` | `f5bbff5eda7fe32f2f27d1ecb5064c58a991c2c00533497f116bc346dfd7eb62` |
| `S813LayaProjectionSmokeInstrumentedTest.kt` | `60361c80a7d9a4c175dfea8784e4e086f415005809b60f0e493f25db4c2dc21f` | `8fc3c7a3e710c040a07ad67ae7a6301325995e3c92e25028669f6fe41b30305d` |
| `S813QualificationInstrumentedTest.kt` | `1d80ab6fa4954e398e561af7ea8b5be2e77d3d4c8ec1a2270266bea8ce4ce8d9` | `12faec90c314042546092b73235b33237085352d1f37508bba5ca7f37c43eb4f` |
| `S813V3QualificationPreflightInstrumentedTest.kt` | `b6f354ec28a900e74e834b98a45e99e40b3055c1a47d06ecd07b6c96d47dcaad` | `50144f252bdf0ea56b30537fd29b46fab0db272293702ff42c9847fc9019ff47` |

No `app/src/main` file changed. Representative unchanged production hashes: `BoundedWorkflowExecutor.kt` `d2a258c43b3367dc87f043f6a35aa8466a9bf45651408ac01d506556369e8fcb`; `AgentToolRuntime.kt` `c01b6d2e4b5445edca73adea04a0af11dbfba5cd435971a00790a8c24bc65c28`. Inputs remain byte-identical by freeze/build source hashes: qualification fixture `dc0c42ba8ebc9742ada922ccba6831dcc4baa047efdeb098af23ccdf7dc9f868`; scoring definition `1e023975ff9d03f0f6d51b834fad23bcde17cb5368f18db730f1b30b3fa676e7`; runtime skills `f38d6afd4fb7c38ad772aaf495c6a282f98208cd71345500e6689f711c861ca7`; v3 interface schema `929074aadbb29df10994cdcf25bb24ca4e84aba25a27b8d6c463c18d99b9cd33`.

The reviewed freeze31 file and a26 zero-call records were read-only and remain untouched. Snapshot34 is not frozen, not approved for hosted use, and not submitted for review.