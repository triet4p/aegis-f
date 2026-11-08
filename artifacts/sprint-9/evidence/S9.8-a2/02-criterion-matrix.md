# S9.8-a2 — criterion matrix and observed results

Attempt S9.8-a2, runtime `S98WorkflowRecovery` (`gold-task`, full route). Cycle `S9.8-cycle-1`
round 1, 0 assessed rounds before this submission. Base `b453f9580b1b124c30dd41e1352e2f0aa5a36b48`.

Evidence root `artifacts/sprint-9/evidence/S9.8-a2/`. Isolated package
`io.github.triet4p.aegisf.s812evidence` (+ `.test`); owner app untouched.

## Final candidate code

| path | change |
|---|---|
| `app/src/main/java/io/github/triet4p/aegisf/privacy/SensitiveVault.kt` | a1: bare/grouped `d` dong shorthand masked and read as whole dong |
| `app/src/main/java/io/github/triet4p/aegisf/provider/ProviderJson.kt` | a2: OpenAI lowering keeps `["string","null"]` as a JSON Schema type array (Gemini keeps `nullable`) |
| `app/src/test/java/io/github/triet4p/aegisf/privacy/MaskedValueShapesTest.kt` | a1: 4 dong-shape tests |
| `app/src/test/java/io/github/triet4p/aegisf/provider/OpenAiChatCompletionsAdapterTest.kt` | a2: nullable union assertion updated to the type array |
| `app/src/test/java/io/github/triet4p/aegisf/provider/OpenAiResponsesAdapterTest.kt` | a2: same |
| `app/src/test/java/io/github/triet4p/aegisf/provider/ToolArgumentValidationTest.kt` | a2: type-array union test |
| `CHANGELOG.md` | a1 dong entry + a2 schema entry |
| `app/src/androidTest/java/io/github/triet4p/aegisf/agent/S98CapabilityPhoneInstrumentedTest.kt` | S9.8 harness (full-arg preservation, production resume, language variants) |
| `app/src/androidTest/java/io/github/triet4p/aegisf/data/S98DeviceFixtureInstrumentedTest.kt` | device fixture, config staging, reference-contract discriminator, UI seed/verify |

Final APK hashes: app `fe854a2f3f4784cd60537be8c5a6b45038bbaa72a211b184fbd263960b9f52d1`;
androidTest `21b49b657cb31fa5dd2e6d669b35e99e569d75180369f3b77071642deae217c3`.

Host verification (one run over the final sources):
`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` —
**577 tests / 0 failures / 0 errors / 3 skipped**, lint **0 errors** (18 warnings, 2 hints),
BUILD SUCCESSFUL.

## Criterion matrix

| S9.8 criterion (`docs/sprint-plans/sprint-9.md:33`) | Evidence | Verdict |
|---|---|---|
| Run the completed workflow with offered hosted model configurations | Production run stack (same host, gateway, boundary, skills, tool runtime, executor, 12-turn/24-tool policy) on the isolated phone; final APK. **EN grounded: Completed, 11 turns/18 tools, 2 source-backed events, both drafts linked ACCEPTED, categories Transport/Food, drafts PENDING, ledger unchanged. VI grounded: Completed, 12 turns/21 tools, same saved state.** Traces: `live-en-schema-fix.trace.json`, `live-vi-schema-fix.trace.json` | **Demonstrated (final candidate code)** |
| Claimed language modes | English baseline completed; Vietnamese fuel/Circle K example completed with the source-grounded variant. Note-level-only "Today" variant (a1/a2 original note) stops at a designed date clarification for the second clause, because the app deliberately resolves a day only inside the selected occurrence | **Demonstrated; boundary disclosed** |
| Inherited masking boundary through follow-up turns and tool results | Every run: masked tokens on every provider request, no raw fixture surface on the wire, tool results carry tokens, continuation turns carry tokens, follow-up turn masked. Clarification answer `2026-11-06` appears in 0 of 12 request bodies (run2 trace). Raw local values preserved for local validation | **Demonstrated** |
| Unsupported / missing-key / offline providers leave notes editable with immediate manual fallback | a1 app-scoped loopback provider: honest `PROVIDER_FAILURE`, note edit saved, manual category saved, ledger unchanged, drafts PENDING; secret/profile path refuses a missing credential. This attempt did not change that path; the a2 failure path (UI `TRUNCATED_TURN`) likewise left the note and all records untouched | **Demonstrated (app-scoped; not a device-offline claim)** |
| No hidden provider substitution, no generative SLM fallback | Each run used only its selected profile/endpoint; failures surfaced as `PROVIDER_FAILURE` / `TRUNCATED_TURN` / `MASKING_BLOCKED`; no alternate endpoint and no on-device classifier path exists active | **Demonstrated** |
| No ledger confirmation; category is review state only | Every run ended with ledger rows equal to the baseline; hosted categories stored with origin HOSTED and drafts still PENDING | **Demonstrated** |

## Defect found and fixed in this attempt

`ProviderJson` collapsed the declared `["string","null"]` union to `type:"string"` + `nullable:true`
for OpenAI-compatible protocols. `nullable` is not a JSON Schema keyword, so the model received a
required string and spelled the absent optional reference as the literal text `"null"`, which the
strict host correctly refuses (`UNKNOWN_REFERENCE issued_in_this_run`). Observed on the real
endpoint in the VI run (`end_date_ref:"null"` turns 2/3/4/8, `cursor:"null"` turn 9). Fixed by
preserving the JSON Schema type union; the same model then sent real JSON null and both language
baselines completed. Before/after schema, risk and verification: `01-nullable-contract.md`.

No permissive coercion was kept: a literal `"null"`/blank string is still not a reference.

## UI smoke on the final APK

Real Chat UI on the isolated phone, header `HOSTED AGENT · S98 OpenCode Go · DeepSeek V4.1 Flash`,
four intents enabled, REPLAY off, exact prompt entered without IME composition (composing IMEs
temporarily disabled; exact text re-dumped before sending), Send tapped, progress observed.

- Attempt 1: honest `Run incomplete: TRUNCATED_TURN. You can retry or continue manually.` /
  `Run state: failed (run 9ea5f07b)`. Trace: 2541 streamed chunks, `finish_reason=length`,
  `completion_tokens=8192`, turns 1, tool calls 0. No event/link/category row was written; ledger
  stayed 2.
- Attempt 2 (single retry): same honest terminal outcome (run `363a81e9`), `prompt_tokens=14840`,
  `completion_tokens=8192`, `reasoning_tokens=8192`, 2153 chunks, 0 tool calls, saved state
  unchanged.
- Screenshots/UI dumps: `ui_01_launch.png` … `ui_25_retry_outcome.png/.xml`; DB check
  `S98UIVERIFY events=0 acceptedLinks=[] ledger=2 baseline=2`.

Interpretation: the production Chat prompt over the real inbox (4 captures, 8 pending drafts)
spends the model's entire output budget on reasoning before any tool call. This is a model
output-budget/context-size limit, reported honestly by the UI and never as success; it is **not**
a code defect and no budget was raised.

## Failures observed this attempt (all preserved)

- VI grounded pre-fix: `TURN_BUDGET`, 0 events — reproduced the `"null"` text failure; then a
  `masking_blocked UNKNOWN_TOKEN` stop.
- EN note-level-only note, clarification answered with an absolute date through the production
  `resumeTracked` path: `record_event` refused `REFERENCE_KIND_MISMATCH $.anchor_ref
  anchor_from_selected_occurrence` on the last available turn, then `TURN_BUDGET`; snack link not
  created.
- UI hosted run: two honest `TRUNCATED_TURN` outcomes as above.
- Harness corrections: a1 used `executor.resume` (non-production entry); a2 uses
  `executor.resumeTracked` per `QuickCaptureScreen.kt:402`.
- Typing the Chat prompt through the device IME produced Telex-mangled text; that input was
  discarded (app restarted, field verified empty) and never sent. Exact text was then entered with
  the composing IMEs disabled and re-dumped before Send.

## Restoration / cleanup

- Provider profiles: `[google-gemini-flash-lite-e9911898-e111-4cb6-a5e3-ee13c7be63f2]`, active the
  same, `restored=true`; `s98-gemma`, `s98-deepseek`, `s98-luna`, `s98-unreachable` removed.
- `s98_secret.env` absent from the isolated app's private files.
- IME restored to `com.google.android.inputmethod.latin/...LatinIME`.
- No network/airplane/mobile-data setting touched at any point this attempt.
- Device rows added: capture 35 + drafts 18/19/20 (UI fixture) and the synthetic per-run databases
  are deleted by their own tests. Ledger unchanged at 2.

## Unresolved / disclosed limits

- The real UI path could not complete a hosted run in this attempt because of the model's
  reasoning-token budget over the enlarged production inbox; no budget increase was made.
- The note-level-only "Today" note produces a designed second-clause date clarification; after a
  genuine user answer the production 12-turn bound was reached before the second link.
- A single earlier VI run after the (now reverted) coercion completed, but its success is not used
  as candidate proof; only the schema-fix runs and a1's UI/fallback evidence are cited.
