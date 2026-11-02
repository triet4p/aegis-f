# S8.13 smoke-evidence a28

## Identity and scope

- Task: `S8.13-smoke-evidence`
- Attempt: `S8.13-evidence-a28`
- Worker: `S813EvidenceSilver28` — silver-task, local smoke failure observability
- Snapshot/run: `S8.13-v3-interface-smoke-evidence-a28-freeze-35` / `S8.13-smoke-evidence-a28`
- Ordered selection: `a8-recover-01`, `a8-retry-01`, `a8-nomatch-01`, `a8h-nomatch-01`
- Scope was test-side reporting and retrieval only. No runtime code, test expectations, fixtures, scoring, or policy were changed.

The earlier a27 snapshot32/33/34 artifacts and the accepted a25 review were left untouched. This attempt produced no freeze, hosted approval, or qualification claim.

## Changed files

- `app/src/androidTest/java/io/github/triet4p/aegisf/agent/S813V3QualificationPreflightInstrumentedTest.kt` — evaluates all named response subcases independently, persists each full synthetic input and expected/actual guard result into an identity-bound `TEST_ONLY` report, and asserts the named aggregate only after persisting the complete report. Errors and stack traces are retained; independent cases continue.
- `tools/slm/s8_agent_v3_qualification.py` — pulls and validates the separate privacy-smoke raw root even when the controlled instrumentation fails, records its path/hash, and validates detailed per-case summary records instead of boolean-only values.
- `docs/sprint-8-agent-qualification.md`, `tools/slm/README.md`, `CHANGELOG.md`, `.agents/memory/lessons-learned.md` — document the failure-artifact contract and lesson.

No `app/src/main` file was changed.

## Verification and emitted artifacts

| Check | Observed result | Evidence |
|---|---|---|
| Static mapping preflight | PASS, 16 dev + 8 held-out = 24 mappings; hosted calls 0 | `snapshot-35/preflight-static.json` |
| Consolidated build | BUILD SUCCESSFUL; app SHA-256 `0860c6b5b807ea96c9e09e57820b7a9aa623838828014a83e4683634c06e4e41`; androidTest SHA-256 `0e5f36daa0a66e1ac38aee57bfc2f6673edfaf89f19d2942fa500e9fb8809472` | `snapshot-35/build.json` |
| Production APK identity | App SHA-256 is byte-identical to a27/snapshot34 (`0860c6b5…e4e41`) | `snapshot-35/build.json`; a27 `snapshot-34/build.json` |
| Exact isolated device preflight | `preflight_failed`; CLI exit 1; ALT-LX2 / Android 14; base application unchanged; hosted calls 0; credentials not loaded; device data not cleared | `snapshot-35/device-preflight-host.json` |
| Selection-only test | `OK (1 test)`; `selection.json` has the exact four requested IDs in order and `credential_loaded=false` | `snapshot-35/device-preflight/preflight/S8.13-smoke-evidence-a28/selection.json` |
| Laya smoke | Instrumentation `OK (1 test)`; emitted and collected | `snapshot-35/device-preflight/laya-smoke.json` |
| TEST_ONLY debug proof | `OK (2 tests)`; summary retained | `snapshot-35/device-preflight/debug-proof/S8.13-smoke-evidence-a28/summary.json` |
| Controlled V3 preflight | JUnit failure in the named response smoke; `domain-preflight.json` was not written | Instrumentation record in `snapshot-35/device-preflight-host.json` |

**Raw failed-device artifact:** `artifacts/sprint-8/evidence/S8.13-evidence-a28/debug/S8.13-v3-interface-smoke-evidence-a28-freeze-35/preflight-privacy-smoke/safe-response-location-smoke.json` (SHA-256 `57a22867f205146e4bb33de2ddabef5d8b28821e19f1842633b9d88ad3122c62`). It is marked `TEST_ONLY`, binds the attempt/snapshot/run/case identity, records all 39 named cases and all 57 synthetic inputs, has `aggregate_result: FAIL`, and retains zero per-case exceptions plus the full aggregate `AssertionError` stack trace. Every case records expected and actual protocol/channel, guard decision/outcome, safe locations and call/effect trace; the full synthetic response bodies are retained in `input_responses`.

**Retrieval despite failure:** the `device-preflight` command returned exit 1, while the Android instrumentation wrapper's `exit_code` field was 0 and its JUnit status was `instrumentation_failed` (`FAILURES!!!`). The host record separately shows `collection_error: null`, `test_only_privacy_smoke_collection_error: null`, `test_only_privacy_smoke_validation_error: null`, the raw report path/hash above, and the collected file under `collected_files`. The raw report was pulled from the separate device `files/<attempt>/debug/<snapshot>/preflight-privacy-smoke` root, not inferred from stdout.

## Named response-case results

The raw JSON above is authoritative for exact input bodies and complete per-input diagnostics. The table below summarizes every named predicate; 36 passed, three failed, and every row was executed without per-case exceptions.

| Named case | Expected | Actual guard result / observed channel and safe location | Predicate |
|---|---|---|---|
| `unicode_escape_is_not_decoded_match` | ACCEPTED / Accepted | ACCEPTED / Accepted; `unclassified_json`, `response_body.unclassified_json` | PASS |
| `decoded_content_refused` | REFUSED / Refused | REFUSED, `DECODED_SENSITIVE_MATCH` at `response_body.choices[].message.content` (`model_content`) | PASS |
| `decoded_reasoning_refused` | REFUSED / Refused | ACCEPTED / Accepted; sensitive synthetic input observed at `response_body.choices[].message.reasoning_content` (`model_reasoning`) | **FAIL** |
| `root_metadata_schema_validated_accepted` | ACCEPTED / AcceptedWithValidatedMetadata | ACCEPTED / AcceptedWithValidatedMetadata; validated root `id`, `object`, `created`, `model` | PASS |
| `route_accounting_amounts_accepted` | ACCEPTED / AcceptedWithValidatedMetadata | ACCEPTED / AcceptedWithValidatedMetadata; `usage.opencode.allowance`, `usage.opencode.list_cost` | PASS |
| `malformed_root_metadata_protocol_error` | PROTOCOL_ERROR / ProtocolError | 8/8 ProtocolError, `INVALID_PROTOCOL_METADATA`; safe paths include `id`, `created`, `object`, `model` | PASS |
| `malformed_route_accounting_protocol_error` | PROTOCOL_ERROR / ProtocolError | 4/4 ProtocolError, `INVALID_PROTOCOL_METADATA`; paths `usage.opencode.allowance` / `list_cost` | PASS |
| `known_numeric_usage_counter_accepted` | ACCEPTED / AcceptedWithValidatedCounters | ACCEPTED / AcceptedWithValidatedCounters; recognized `usage.total_tokens` | PASS |
| `provider_specific_usage_paths_recognized` | ACCEPTED / AcceptedWithValidatedCounters | 3/3 ACCEPTED / AcceptedWithValidatedCounters across the three declared protocols; exact path sets in raw JSON | PASS |
| `unknown_usage_counter_refused` | REFUSED / Refused | REFUSED, `DECODED_SENSITIVE_MATCH` at `response_body.usage.unlisted` | PASS |
| `safe_usage_identifier_path_exposed` | REFUSED / Refused | REFUSED, `DECODED_SENSITIVE_MATCH` at `response_body.usage.vendor_metrics.model_usage` | PASS |
| `unsafe_usage_identifier_collapsed` | REFUSED / Refused | REFUSED, `DECODED_SENSITIVE_MATCH` at `response_body.usage.other_field.model_usage` | PASS |
| `model_unknown_identifier_stays_collapsed` | REFUSED / Refused | REFUSED, `DECODED_SENSITIVE_MATCH` at `response_body.choices[].message.other_field` | PASS |
| `known_other_responses_usage_path_identified` | REFUSED / Refused | REFUSED, `DECODED_SENSITIVE_MATCH` at `response_body.usage.input_tokens` | PASS |
| `known_other_gemini_usage_path_identified` | REFUSED / Refused | REFUSED, `DECODED_SENSITIVE_MATCH` at `response_body.usageMetadata.promptTokenCount` | PASS |
| `sensitive_usage_key_name_not_disclosed` | REFUSED / Refused | REFUSED, `DECODED_SENSITIVE_MATCH` at `response_body.usage.field_name` | PASS |
| `string_sensitive_usage_counter_refused` | REFUSED / Refused | REFUSED, `DECODED_SENSITIVE_MATCH` at `response_body.usage.total_tokens` (`STRING`) | PASS |
| `malformed_usage_counter_protocol_error` | PROTOCOL_ERROR / ProtocolError | 7/7 ProtocolError, `INVALID_USAGE_COUNTER`; locations include `usage.total_tokens`, `usage`, `usage.completion_tokens_details` | PASS |
| `known_counter_model_content_still_refused` | REFUSED / Refused | REFUSED, `DECODED_SENSITIVE_MATCH` at `response_body.choices[].message.content` | PASS |
| `known_counter_reasoning_still_refused` | REFUSED / Refused | ACCEPTED / AcceptedWithValidatedCounters; recognized `usage.total_tokens`; reasoning observed at `choices[].message.reasoning_content` | **FAIL** |
| `known_counter_tool_arguments_still_refused` | REFUSED / Refused | REFUSED, `DECODED_SENSITIVE_MATCH` at `choices[].message.tool_calls[].function.arguments` | PASS |
| `known_counter_sensitive_key_still_refused` | REFUSED / Refused | REFUSED, `DECODED_SENSITIVE_MATCH` at `response_body.field_name` | PASS |
| `metadata_value_in_model_content_refused` | REFUSED / Refused | REFUSED, `DECODED_SENSITIVE_MATCH` at `response_body.choices[].message.content` | PASS |
| `metadata_value_in_model_reasoning_refused` | REFUSED / Refused | ACCEPTED / Accepted; synthetic input observed at `response_body.choices[].message.reasoning_content` (`model_reasoning`) | **FAIL** |
| `metadata_value_in_tool_arguments_refused` | REFUSED / Refused | REFUSED, `DECODED_SENSITIVE_MATCH` at `choices[].message.tool_calls[].function.arguments` | PASS |
| `metadata_value_in_sensitive_key_refused` | REFUSED / Refused | REFUSED, `DECODED_SENSITIVE_MATCH` at `response_body.field_name` | PASS |
| `nested_metadata_lookalike_refused` | REFUSED / Refused | REFUSED, `DECODED_SENSITIVE_MATCH` at `response_body.choices[].message.id` | PASS |
| `metadata_model_echo_mismatch_refused` | REFUSED / Refused | REFUSED, `DECODED_SENSITIVE_MATCH` at `response_body.model` | PASS |
| `non_go_route_accounting_refused` | REFUSED / Refused | REFUSED, `DECODED_SENSITIVE_MATCH` at `response_body.usage.opencode.allowance` | PASS |
| `unknown_field_value_refused` | REFUSED / Refused | REFUSED, `DECODED_SENSITIVE_MATCH` at `response_body.other_field.other_field` | PASS |
| `unknown_field_name_refused` | REFUSED / Refused | REFUSED, `DECODED_SENSITIVE_MATCH` at `response_body.field_name` | PASS |
| `duplicate_key_refused` | REFUSED / Refused | REFUSED, `DUPLICATE_KEY` at `response_body.id.duplicate_key` | PASS |
| `decoded_duplicate_key_refused` | REFUSED / Refused | REFUSED, `DUPLICATE_KEY` at `response_body.id.duplicate_key` | PASS |
| `invalid_json_refused` | REFUSED / Refused | REFUSED, `INVALID_JSON` at `response_body.id` | PASS |
| `unsupported_root_refused` | REFUSED / Refused | REFUSED, `UNSUPPORTED_ROOT` at `response_body` | PASS |
| `masked_token_only_accepted` | ACCEPTED / Accepted | ACCEPTED / Accepted | PASS |
| `booleans_and_null_accepted` | ACCEPTED / Accepted | ACCEPTED / Accepted | PASS |
| `foreign_token_refused` | REFUSED / Refused | REFUSED, `FOREIGN_TOKEN` at `response_body.other_field` | PASS |
| `unknown_token_refused` | REFUSED / Refused | REFUSED, `UNKNOWN_TOKEN` at `response_body.other_field` | PASS |

## Contract comparison and unresolved finding

**Observed:** exactly three named expected-`REFUSED` cases returned `ACCEPTED`; there were no thrown per-case exceptions. The controlled test then failed at `S813V3QualificationPreflightInstrumentedTest.kt:1517` with the three case IDs in its assertion message. All raw inputs and actual outputs are present in the sidecar.

**Relevant runtime contract:** `app/src/main/java/io/github/triet4p/aegisf/privacy/MaskedPromptBoundary.kt:105-125` says provider reasoning/thinking is not privacy-guard input; `:369-374` skips values when the classified channel is not guarded; `:635-643` defines `PROVIDER_REASONING` as unguarded; `:696-701` classifies Chat `reasoning_content`/`reasoning` as that channel.

**Relevant expected contract:** `S813V3QualificationPreflightInstrumentedTest.kt:842-845`, `:983-986`, and `:999-1002` expect refusal for the three reasoning cases. `tools/slm/s8_agent_v3_qualification.py:72-112` lists the named preflight cases. The accepted a25 review explicitly bounds the inbound guard as reasoning-exempt (only tool/final channels guarded): `artifacts/sprint-8/reviews/S8.13-a25-review-02.md:5-6`.

**[INFERENCE]** The three test expectations appear stale relative to the accepted reasoning-exempt policy and current runtime contract. This is a concrete observed expectation/behavior mismatch, not an assertion that the runtime or policy is wrong. No runtime or expected value was changed, and no root-cause fix was attempted.

No hosted API, credential load, data clear, stage, commit, or push occurred. The raw report, build, host record and prior snapshots remain preserved. `routing_refs`: `S8.13-harness-cycle-1`; `history://S813HarnessSilver27`.