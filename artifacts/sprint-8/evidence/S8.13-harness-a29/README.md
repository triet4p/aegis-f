# S8.13 harness a29 — reasoning-expectation alignment

## Candidate and scope

- **Task / attempt / worker:** `S8.13-harness` / `S8.13-harness-a29` / `S813AlignSilver29` (`silver-task`).
- **Review cycle:** `S8.13-harness-cycle-1`, round 1; independent review PASS; exact snapshot39 freeze later authorized for one Stage2 diagnostic.
- **Frozen candidate:** `S8.13-v3-interface-harness-a29-freeze-39`; `freeze.json` SHA-256 `bbcc4f571a1bc4da2caece40d358066f72af40cc3ef5475a8912f50b462afe06`.
- **Ordered diagnostic selection:** `a8-recover-01`, `a8-retry-01`, `a8-nomatch-01`, `a8h-nomatch-01`.
- Scope is test-harness expectation alignment and safe local evidence collection. No `app/src/main`, fixture, schema, runtime skill, scoring definition, or scoring/gate policy was changed. The accepted a25 reasoning-exempt policy and historical a27/a28 records were not rewritten.
- This frozen candidate is **not** a product qualification or checkpoint. Main approved one hosted diagnostic after the exact independent review and freeze checks; it safety-stopped on a financial mismatch. No staging, commit, or push occurred.

## Changed files

- `app/src/androidTest/java/io/github/triet4p/aegisf/agent/S813V3QualificationPreflightInstrumentedTest.kt` — renamed the three reasoning-only cases to match the accepted exemption and changed their exact expected result variants. The counter-bearing reasoning case still asserts the recognized numeric `usage.total_tokens` path.
- `tools/slm/s8_agent_v3_qualification.py` — updated the shared required-case and accepted-outcome contract, validates the observed `function` path segment without weakening tool-call refusals, and exercises these contracts in runner selfcheck. The phone's 39-case raw report is collected to an external gitignored TEST_ONLY root, validated by identity/hash and case count, and referenced by path/hash only in the freeze. Its bytes remain outside source and freeze contents; live debug-root payloads are still rejected by freeze.
- `tools/slm/README.md`, `docs/sprint-8-agent-qualification.md`, `CHANGELOG.md`, `.agents/memory/lessons-learned.md`, `.gitignore` — document the corrected expectations, retained negative controls, external raw-report exclusion, and the observed host-path/freeze lessons.

## Verification

| Check | Observed result | Evidence |
|---|---|---|
| Runner selfcheck | **PASS, 57 checks, 0 failed** | `uv run python tools/slm/s8_agent_v3_qualification.py selfcheck` |
| Static mapping preflight | **PASS, 24 mappings (16 dev + 8 held-out), 0 hosted calls** | `snapshot-39/preflight-static.json` |
| Consolidated isolated APK build | **BUILD SUCCESSFUL**; packages verified as `io.github.triet4p.aegisf.s813evidence` and `.test` | `snapshot-39/build.json` |
| Production APK identity | `0860c6b5b807ea96c9e09e57820b7a9aa623838828014a83e4683634c06e4e41`, byte-identical to the required APK | `snapshot-39/build.json`, `snapshot-39/device-preflight-host.json` |
| Android test APK | `b4457690c16328ac45ea55c5616a0f6b449ae5e780618cb3ea8098dcbfb031c0` | `snapshot-39/build.json` |
| Exact isolated phone preflight | **OK (4 classes; 5 tests)** on `AAAJBB4930914141`, ALT-LX2 / Android 14; selection, Laya/domain, and TEST_ONLY debug capture all passed | `snapshot-39/device-preflight-host.json` |
| Isolation and privacy | Isolated app/test packages installed; base application fingerprint unchanged; `device_data_cleared=false`, `credential_loaded=false`, hosted calls `0`; raw report flags header values, credential material and real-user content all false | `snapshot-39/device-preflight-host.json`, `selection.json`, TEST_ONLY report |
| Final 39-case report | **39/39 completed, aggregate PASS, 0 failed IDs, 0 per-case exceptions, 57 synthetic inputs** | External TEST_ONLY report below; host receipt validates its SHA-256 |
| Fresh freeze | **PASS; subsequently authorized once for Stage2** | `snapshot-39/freeze.json`; exact approval and run receipt below |

The exact runner commands used for the final candidate were:

```powershell
uv run python tools/slm/s8_agent_v3_qualification.py selfcheck
uv run python tools/slm/s8_agent_v3_qualification.py preflight --attempt-id S8.13-harness-a29 --snapshot-id S8.13-v3-interface-harness-a29-freeze-39 --run-name S8.13-live-a29 --case-ids a8-recover-01 a8-retry-01 a8-nomatch-01 a8h-nomatch-01 --evidence-root artifacts/sprint-8/evidence/S8.13-harness-a29
uv run python tools/slm/s8_agent_v3_qualification.py build --attempt-id S8.13-harness-a29 --snapshot-id S8.13-v3-interface-harness-a29-freeze-39 --run-name S8.13-live-a29 --case-ids a8-recover-01 a8-retry-01 a8-nomatch-01 a8h-nomatch-01 --evidence-root artifacts/sprint-8/evidence/S8.13-harness-a29
uv run python tools/slm/s8_agent_v3_qualification.py device-preflight --attempt-id S8.13-harness-a29 --snapshot-id S8.13-v3-interface-harness-a29-freeze-39 --run-name S8.13-live-a29 --case-ids a8-recover-01 a8-retry-01 a8-nomatch-01 a8h-nomatch-01 --evidence-root artifacts/sprint-8/evidence/S8.13-harness-a29
uv run python tools/slm/s8_agent_v3_qualification.py freeze --attempt-id S8.13-harness-a29 --snapshot-id S8.13-v3-interface-harness-a29-freeze-39 --run-name S8.13-live-a29 --case-ids a8-recover-01 a8-retry-01 a8-nomatch-01 a8h-nomatch-01 --evidence-root artifacts/sprint-8/evidence/S8.13-harness-a29
```

## TEST_ONLY raw report and source boundary

- Raw report: `artifacts/sprint-8/test-only/S8.13-harness-a29/S8.13-v3-interface-harness-a29-freeze-39/preflight-privacy-smoke/safe-response-location-smoke.json`.
- Raw report SHA-256: `ba738f3e33f50f899f834638da99aefa8304596d88095963d0bb3ca452e69556`.
- The report binds attempt `S8.13-harness-a29`, snapshot `S8.13-v3-interface-harness-a29-freeze-39`, run `S8.13-live-a29`, and the ordered four-case selection. It is `TEST_ONLY`, contains synthetic bodies only, has `header_values_recorded=false`, `credential_material_recorded=false`, `real_user_content_recorded=false`, `hosted_provider_calls=0`, and records all 39 named cases / 57 inputs with no exceptions.
- `.gitignore` excludes the external `artifacts/sprint-8/test-only/` root and the retained a29 debug sidecars. `freeze.json` records the external report's path/hash/count and explicitly sets `raw_payload_in_source_manifest=false` and `raw_payload_in_freeze=false`; the raw bodies are not copied into source, freeze, or export contents. The device host receipt also binds the report path/hash.
- Current source manifest: 232 files; SHA-256 `ac2d16202f3514634ba1a4587141668877cfceb8c2c7d78c91c2586acc82599d`.
- APK and host-source hashes: app APK `0860c6b5b807ea96c9e09e57820b7a9aa623838828014a83e4683634c06e4e41`; androidTest APK `b4457690c16328ac45ea55c5616a0f6b449ae5e780618cb3ea8098dcbfb031c0`; updated Android test source `fa1ebf0cb94850d6bbf79bd4fa8362bd01598e3bc4a04833ba90d9e6234128c9`; updated runner `45380ab1622fd14323ebe10a01f9f98a9286df973f8fe337ad2d8ca361276867`.

Explicit unchanged baseline/current SHA-256 pairs (baseline `S8.13-replan-a25` freeze31 / current freeze39):

| Path | Baseline SHA-256 | Current SHA-256 |
|---|---|---|
| `app/src/main/java/io/github/triet4p/aegisf/privacy/MaskedPromptBoundary.kt` | `835ff46fa7cccb3aea519b3a22c8bb4478d504ce5589156d8b2b141b1660c819` | same |
| `app/src/main/java/io/github/triet4p/aegisf/agent/BoundedWorkflowExecutor.kt` | `d2a258c43b3367dc87f043f6a35aa8466a9bf45651408ac01d506556369e8fcb` | same |
| `app/src/main/java/io/github/triet4p/aegisf/agent/AgentToolRuntime.kt` | `c01b6d2e4b5445edca73adea04a0af11dbfba5cd435971a00790a8c24bc65c28` | same |
| `tools/slm/s8_agent_v3_qualification_fixture.json` | `dc0c42ba8ebc9742ada922ccba6831dcc4baa047efdeb098af23ccdf7dc9f868` | same |
| `tools/slm/s8_agent_v3_qualification_scoring.json` | `1e023975ff9d03f0f6d51b834fad23bcde17cb5368f18db730f1b30b3fa676e7` | same |
| `tools/slm/s8_agent_runtime_skills_v3.json` | `f38d6afd4fb7c38ad772aaf495c6a282f98208cd71345500e6689f711c861ca7` | same |
| `tools/slm/s8_agent_tools_v3.json` | `929074aadbb29df10994cdcf25bb24ca4e84aba25a27b8d6c463c18d99b9cd33` | same |

## Final named-case results

The table reports expected and observed guard variants from the final raw report. Every row passed; exact synthetic bodies, per-input diagnostics and safe-channel traces remain only in the external TEST_ONLY report.

| Named case | Expected = actual | Observed invariant / safe diagnostic | Result |
|---|---|---|---|
| `unicode_escape_is_not_decoded_match` | `ACCEPTED / Accepted` | Unclassified JSON remains non-matching | PASS |
| `decoded_content_refused` | `REFUSED / Refused` | `DECODED_SENSITIVE_MATCH` at `choices[].message.content` | PASS |
| `decoded_reasoning_accepted` | `ACCEPTED / Accepted` | Reasoning-only value at `choices[].message.reasoning_content` is exempt | PASS |
| `root_metadata_schema_validated_accepted` | `ACCEPTED / AcceptedWithValidatedMetadata` | Validated root `id`, `object`, `created`, and `model` | PASS |
| `route_accounting_amounts_accepted` | `ACCEPTED / AcceptedWithValidatedMetadata` | Validated `usage.opencode.allowance` and `list_cost` | PASS |
| `malformed_root_metadata_protocol_error` | `PROTOCOL_ERROR / ProtocolError` | `INVALID_PROTOCOL_METADATA` across malformed root metadata | PASS |
| `malformed_route_accounting_protocol_error` | `PROTOCOL_ERROR / ProtocolError` | `INVALID_PROTOCOL_METADATA` on malformed route amounts | PASS |
| `known_numeric_usage_counter_accepted` | `ACCEPTED / AcceptedWithValidatedCounters` | Recognized numeric `usage.total_tokens` | PASS |
| `provider_specific_usage_paths_recognized` | `ACCEPTED / AcceptedWithValidatedCounters` | Recognized counters across Chat, Responses and Gemini protocol paths | PASS |
| `unknown_usage_counter_refused` | `REFUSED / Refused` | `DECODED_SENSITIVE_MATCH` at `usage.unlisted` | PASS |
| `safe_usage_identifier_path_exposed` | `REFUSED / Refused` | `DECODED_SENSITIVE_MATCH` at `usage.vendor_metrics.model_usage` | PASS |
| `unsafe_usage_identifier_collapsed` | `REFUSED / Refused` | `DECODED_SENSITIVE_MATCH` at `usage.other_field.model_usage` | PASS |
| `model_unknown_identifier_stays_collapsed` | `REFUSED / Refused` | `DECODED_SENSITIVE_MATCH` at `choices[].message.other_field` | PASS |
| `known_other_responses_usage_path_identified` | `REFUSED / Refused` | Other-protocol path `usage.input_tokens` refused | PASS |
| `known_other_gemini_usage_path_identified` | `REFUSED / Refused` | Other-protocol path `usageMetadata.promptTokenCount` refused | PASS |
| `sensitive_usage_key_name_not_disclosed` | `REFUSED / Refused` | Sensitive usage key name collapsed to `usage.field_name` | PASS |
| `string_sensitive_usage_counter_refused` | `REFUSED / Refused` | String `usage.total_tokens` refused | PASS |
| `malformed_usage_counter_protocol_error` | `PROTOCOL_ERROR / ProtocolError` | `INVALID_USAGE_COUNTER` for malformed counter schema | PASS |
| `known_counter_model_content_still_refused` | `REFUSED / Refused` | `DECODED_SENSITIVE_MATCH` at model content despite recognized counter | PASS |
| `known_counter_reasoning_accepted_with_counters` | `ACCEPTED / AcceptedWithValidatedCounters` | Reasoning exempt; assertion still requires recognized numeric `usage.total_tokens` | PASS |
| `known_counter_tool_arguments_still_refused` | `REFUSED / Refused` | Tool arguments remain refused with a recognized counter present | PASS |
| `known_counter_sensitive_key_still_refused` | `REFUSED / Refused` | Sensitive key remains refused with a recognized counter present | PASS |
| `metadata_value_in_model_content_refused` | `REFUSED / Refused` | `DECODED_SENSITIVE_MATCH` at model content | PASS |
| `metadata_value_in_model_reasoning_accepted` | `ACCEPTED / Accepted` | Reasoning-only value at `choices[].message.reasoning_content` is exempt | PASS |
| `metadata_value_in_tool_arguments_refused` | `REFUSED / Refused` | Tool arguments remain guarded | PASS |
| `metadata_value_in_sensitive_key_refused` | `REFUSED / Refused` | Sensitive key remains guarded | PASS |
| `nested_metadata_lookalike_refused` | `REFUSED / Refused` | Nested `choices[].message.id` lookalike refused | PASS |
| `metadata_model_echo_mismatch_refused` | `REFUSED / Refused` | Mismatched root `model` value refused | PASS |
| `non_go_route_accounting_refused` | `REFUSED / Refused` | Go-only `usage.opencode.allowance` refused on non-Go route | PASS |
| `unknown_field_value_refused` | `REFUSED / Refused` | Unknown nested value collapsed to `other_field` | PASS |
| `unknown_field_name_refused` | `REFUSED / Refused` | Sensitive key name collapsed to `field_name` | PASS |
| `duplicate_key_refused` | `REFUSED / Refused` | `DUPLICATE_KEY` at `id.duplicate_key` | PASS |
| `decoded_duplicate_key_refused` | `REFUSED / Refused` | `DUPLICATE_KEY` at `id.duplicate_key` | PASS |
| `invalid_json_refused` | `REFUSED / Refused` | `INVALID_JSON` at `id` | PASS |
| `unsupported_root_refused` | `REFUSED / Refused` | `UNSUPPORTED_ROOT` at `response_body` | PASS |
| `masked_token_only_accepted` | `ACCEPTED / Accepted` | Masked-token-only response accepted | PASS |
| `booleans_and_null_accepted` | `ACCEPTED / Accepted` | Boolean and null JSON values accepted | PASS |
| `foreign_token_refused` | `REFUSED / Refused` | `FOREIGN_TOKEN` at `other_field` | PASS |
| `unknown_token_refused` | `REFUSED / Refused` | `UNKNOWN_TOKEN` at `other_field` | PASS |

## Preserved intermediate diagnostics

No previous evidence was overwritten or deleted.

| Snapshot | Preserved result | Exact finding and retained evidence |
|---|---|---|
| `freeze-36` | Raw 39-case report complete; every semantic predicate passed | Host domain validation rejected two tool-argument safe paths because the runner allowlist omitted the observed `function` segment. The exact externalized raw case records and host failure receipt remain under this attempt's ignored debug/evidence paths. |
| `freeze-37` | Device preflight **OK (4 classes; 5 tests)** | Freeze refused because the required synthetic report shared `DEBUG_ROOT`; report and host receipt remain preserved. |
| `freeze-38` | Device preflight **OK (4 classes; 5 tests)** | Freeze did not write after a host validator `NameError` (`host` receipt not loaded); the one-line host read was restored. The successful raw report stays in the separate TEST_ONLY root. |
| `freeze-39` | Final device preflight and freeze **PASS** | Current identity-bound evidence and hash references are listed above; only this snapshot is the review candidate. |

## Authorized Stage2 diagnostic — completed once; safety-stopped

- **Independent review:** `S813HarnessReview29`, `S8.13-harness-review-01`, cycle 1 / round 1 — **PASS**; review manifest SHA-256 `1c9c4d54baf83c60bbe9e58b3b3cf7ac69aa3024f902178861484790221e9368`.
- **Main approval:** `Main-S813-a29-live4` authorized exactly snapshot `S8.13-v3-interface-harness-a29-freeze-39`, freeze SHA-256 `bbcc4f571a1bc4da2caece40d358066f72af40cc3ef5475a8912f50b462afe06`, source manifest SHA-256 `ac2d16202f3514634ba1a4587141668877cfceb8c2c7d78c91c2586acc82599d`, run `S8.13-live-a29`, and ordered cases `a8-recover-01`, `a8-retry-01`, `a8-nomatch-01`, `a8h-nomatch-01`.
- The runner revalidated the exact freeze and diagnostic identity before hosted execution. The only invocation was:

  ```powershell
  uv run python tools/slm/s8_agent_v3_qualification.py reproduce-blocked --attempt-id S8.13-harness-a29 --snapshot-id S8.13-v3-interface-harness-a29-freeze-39 --run-name S8.13-live-a29 --case-ids a8-recover-01 a8-retry-01 a8-nomatch-01 a8h-nomatch-01 --evidence-root artifacts/sprint-8/evidence/S8.13-harness-a29 --main-approval Main-S813-a29-live4
  ```

- **Host result:** `reproduction_recorded`; instrumentation `OK (1 test)`; identity/evidence validation and debug collection errors are null; `hosted_provider_calls=9`; `hard_stop=runtime_safety_stop:unexpected_or_financially_incorrect_event`. The run returned nonzero for this safety stop. The isolated phone base-application fingerprint remained unchanged. The reproduction path installs the isolated packages in place and does not issue an app-data clear.
- **Credential/privacy boundary:** process-memory credential channel served the authorized run (`credential_channel_served=true`); `credential_recorded=false`. The full raw capture is marked `test_only=true`, `full_bodies_retained=true`, `header_values_recorded=false`, and the host receipt says the debug root is untracked and excluded from reports. No credential/header values are present in the capture.

| Ordered case | Recorded status | Result |
|---|---|---|
| `a8-recover-01` | `hosted_run_recorded` | `Completed`; 9 turns, 10 tool calls, 9 hosted exchanges. One event was applied and re-read; `financial_safety_match=false` because the actual event included merchant `Book` while the expected mapping had no merchant. No ledger row, out-of-scope effect, or unexpected mutation operation occurred. |
| `a8-retry-01` | `not_run_safety_stop` | 0 exchanges and no effect capture; stopped after the preceding financial mismatch. |
| `a8-nomatch-01` | `not_run_safety_stop` | 0 exchanges and no effect capture; stopped after the preceding financial mismatch. |
| `a8h-nomatch-01` | `not_run_safety_stop` | 0 exchanges and no effect capture; stopped after the preceding financial mismatch. |

The first case's complete inventory is preserved below; full requests, reasoning, tool results, final answer, and effects are retained only in its TEST_ONLY debug capture. There were no further case runs.

| Turn | Observed calls / result |
|---|---|
| 1 | Two `select_source` calls; both succeeded. |
| 2 | `make_time_anchor`; succeeded. |
| 3 | `record_event`; rejected with `STALE_OBSERVATION`, no write. |
| 4 | `observe`; succeeded and returned the fresh capture revision. |
| 5 | Two `select_source` calls; both succeeded against the fresh observation. |
| 6 | `make_time_anchor`; succeeded. |
| 7 | `record_event`; applied one event, including the unexpected present merchant `Book`. |
| 8 | `observe`; re-read the applied event and confirmed its stored fields. |
| 9 | No tool call; the final answer said the reading was “stored and verified” and reported merchant `Book`, but did not surface the expected-versus-actual merchant mismatch. The host outcome is therefore not a safety match despite the completion wording. |

- The run trace records one verified `event_reading`, zero ledger rows, zero out-of-scope effects, and zero unexpected mutation operations. The only semantic mismatch was the unexpected merchant field; the safety stop was honored immediately, and the other three cases were not attempted.
- **TEST_ONLY run capture:** `artifacts/sprint-8/evidence/S8.13-harness-a29/debug/S8.13-v3-interface-harness-a29-freeze-39/S8.13-live-a29/debug/a8-recover-01/` contains `case.json`, `effects.json`, `exchanges.jsonl`, and `turns.jsonl`; the shared `session.json` is at the parent `.../S8.13-live-a29/debug/session.json`. The receipt records 9 exchanges for the completed case and 0 for each stopped case. `snapshot-39/reproduction-summary.json` records the approved identity and host outcome; the collected case-level run record is retained under the same ignored, report-excluded debug root.
- No source/freeze identity changed for Stage2, and no case-level rerun or outcome selection occurred. The prescribed stale-observation recovery was part of the single `a8-recover-01` execution. The authorized diagnostic is complete; it does **not** qualify the candidate or close the full-route gate. No cumulative checkpoint, stage, commit, or push is authorized for this worker.
- **routing_refs:** `S8.13-harness-cycle-1`; `history://S813EvidenceSilver28`; `history://S813HarnessSilver27`; `artifacts/sprint-8/reviews/S8.13-a25-review-02.md`.

