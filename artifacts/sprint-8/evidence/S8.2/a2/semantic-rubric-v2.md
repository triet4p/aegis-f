# S8.2 attempt a2 — semantic rubric v2 correction evidence

| Field | Value |
| --- | --- |
| Task | S8.2 — Freeze a workflow-relevant evaluation contract |
| Attempt | S8.2-a2 |
| Worker agent | S82SemanticSilver02 |
| Role / route | `silver-task` / full (independent evidence PASS + separate Bronze checkpoint) |
| Cycle / round | S8.2-cycle-1 / round 2 |
| Criterion | `docs/sprint-plans/sprint-8.md:25-26`, sprint acceptance `:14-20` |
| Scope | Local only. No ADB, device probe, transfer, install or inference. No commit, push or child agent. |
| Result | `ready_for_review` — v2 scoring revision frozen, validator/self-test/matrix PASS |

## 1. What changed and what did not

Changed (task-owned):

- `tools/slm/s8_workflow_scoring_v2.json` (new) — frozen v2 scoring contract: declared argument policies, bound and immutable input digests, replay and evidence requirements, reason codes, honest boundaries.
- `tools/slm/s8_contract.py` — v2 scoring path (`score`, default), preserved v1 path (`score --scoring v1`), v2 contract validation, extended self-test.
- `tools/slm/s8_workflow_profiles.json` — descriptive `scoring` block now declares v2; symbols, numeric targets, criteria and denominators untouched.
- `docs/sprint-8-evaluation-contract.md` — v2 rubric, achievability audit, boundaries, provenance binding, S8.3 prerequisites.
- `tools/slm/README.md` — v2 commands, evidence requirements, v1 compatibility.
- `.agents/memory/lessons-learned.md`, `.agents/memory/decisions.md` — recorded lesson and decision.

Unchanged and verified byte-identical to checkpoint `b26148e` (SHA-256 recomputed by `validate`):

| Input | SHA-256 |
| --- | --- |
| `tools/slm/s8_workflow_cases.json` | `eae0a48d05a4a0715aaacaa6a53011bc65df9e3c8e02e0254ac6f706c1a94765` |
| `tools/slm/s8_workflow_heldout.json` | `3a173716ac7059fe3a25f8ad6c77ed3d3261444163f1a4fd2eeead73ef3a0926` |
| `tools/slm/s8_workflow_system_prompt.txt` | `e87dd2c01ca577050f8011e93f4d2fcd8a1d70475e05262fc215d30b6ec55ab7` |
| `tools/slm/s8_lfm_restricted_prompt.txt` | `bdfbda5e6ee108b24119f45c0620d735e6ba4aa32d24e13b79003e4940e82a74` |
| `tools/slm/s8_workflow_fixture.json` | `81e4a5fec34b3222e8378321865d5501af5dfa461f1678bdf0d8f254d86fce38` |
| `tools/slm/s8_workflow_tools.json` | `6e840a2aa33508910bbdd688c8210bd10801f350e1027134897e02e940475071` |

The proposed numeric targets (`80%` frozen, `70%` held-out, `30 s`, `60 s`, `1400 MiB`) and `owner_approved_acceptance: false` are unchanged. v1 results, the round-1 review and checkpoint records are untouched; no v1 failure is re-labelled as a pass.

## 2. Frozen discriminator: failing before, accepted after

The S8.3-a2 contract discriminator is reused read-only. `derive_inputs.py` derives v2 inputs from it without altering the originals: it copies the preserved result documents, adds the current frozen binding, and derives `call_effects` from the preserved real `WorkflowCommandService` trace (`database_state.captures_events[0]`). The calls are host-constructed diagnostics, not model output and not a new device run.

Both variants use the same real stored event (`eventCount=1`, `amountVnd=70000`, `time=2026-10-24T09:30+07:00`, no ledger write). They differ only in `idempotencyKey` and `interpretation.normalizedText`. A third input reuses the accepted variant with a different declared `extractionVersion` (`producer 2026-10`) to show that producer metadata is recorded rather than compared to a literal.

| Input | Scorer | `s8-time-01` | Exact reasons |
| --- | --- | --- | --- |
| `inputs/semantic-variant.json` (`host-minted-7f3a`, `Bought a book yesterday at 09:30`) | v1 | **fail** | `["call_intent_or_arguments_mismatch"]` |
| `inputs/semantic-variant.json` | v2 | **pass** | `[]` |
| `inputs/semantic-variant-alt-producer.json` (declared version `producer 2026-10`) | v1 | **fail** | `["call_intent_or_arguments_mismatch"]` |
| `inputs/semantic-variant-alt-producer.json` | v2 | **pass** | `[]` |
| `inputs/exact-literal-control.json` (`case-time-01`, `Book purchase`) | v1 | **pass** | `[]` |
| `inputs/exact-literal-control.json` | v2 | **pass** | `[]` |

Exact preserved output (`scores/semantic-variant.v1.json`):

```json
{"case_id": "s8-time-01", "suite": "frozen", "passed": false, "reasons": ["call_intent_or_arguments_mismatch"]}
```

Exact v2 output (`scores/semantic-variant.v2.json`):

```json
{"case_id": "s8-time-01", "suite": "frozen", "passed": true, "reasons": [], "semantic_fields": [{"call_index": 0, "path": "idempotencyKey", "policy": "idempotency_key", "observed": "host-minted-7f3a"}, {"call_index": 0, "path": "interpretation.normalizedText", "policy": "source_backed_text", "observed": "Bought a book yesterday at 09:30"}], "replay_exercised": false}
```

Suite totals are unchanged in structure: both scorers enumerate all 16 cases, and the 15 absent ones remain `missing_result`. The v2 run therefore reports `frozen 1/10`, `heldout 0/6` — the accepted case plus the preserved denominator, not a candidate score.

## 3. Mutation matrix through the scoring CLI

`run_matrix.py` invokes `tools/slm/s8_contract.py score` in a subprocess for every input and preserves its exact stdout. Every probe starts from the accepted semantic variant and changes one thing.

| Probe | Expected reason | Observed reasons |
| --- | --- | --- |
| `mut-empty-key` | `idempotency_key_invalid` | `call_schema_invalid`, `idempotency_key_invalid` |
| `mut-whitespace-key` | `idempotency_key_invalid` | `idempotency_key_invalid` |
| `mut-changed-scope` (capture 103) | `call_intent_or_arguments_mismatch` | `normalized_text_not_source_backed`, `call_intent_or_arguments_mismatch`, `stored_reading_mismatch` |
| `mut-changed-revision` | `call_intent_or_arguments_mismatch` | `call_intent_or_arguments_mismatch` |
| `mut-changed-amount` | `call_intent_or_arguments_mismatch` | `call_intent_or_arguments_mismatch`, `stored_reading_mismatch` |
| `mut-changed-date` | `call_intent_or_arguments_mismatch` | `call_intent_or_arguments_mismatch`, `stored_reading_mismatch` |
| `mut-changed-time` | `call_intent_or_arguments_mismatch` | `call_intent_or_arguments_mismatch`, `stored_reading_mismatch` |
| `mut-changed-intent` | `call_intent_or_arguments_mismatch` | `call_intent_or_arguments_mismatch` |
| `mut-extra-call` | `call_intent_or_arguments_mismatch` | `call_intent_or_arguments_mismatch` |
| `mut-missing-effects` | `observed_effect_mismatch` | `observed_effect_mismatch` |
| `mut-uninstrumented` | `application_effect_trace_missing` | `application_effect_trace_missing` |
| `mut-nonsense-text` (`zqxv blorf`) | `normalized_text_not_source_backed` | `normalized_text_not_source_backed` |
| `mut-fabricated-numbers` (`Refund 999,000 VND at 23:59`) | `normalized_text_fabricated_facts` | `normalized_text_not_source_backed`, `normalized_text_fabricated_facts` |
| `mut-text-not-stored` | `normalized_text_effect_mismatch` | `normalized_text_effect_mismatch` |
| `mut-missing-stored-reading` | `stored_reading_evidence_missing` | `stored_reading_evidence_missing` |
| `mut-extra-argument` (`evidence`) | `call_schema_invalid` | `call_schema_invalid`, `call_intent_or_arguments_mismatch` |
| `mut-unbound` | `contract_binding_missing` | `contract_binding_missing` |
| `mut-foreign-binding` | `contract_binding_mismatch` | `contract_binding_mismatch` |
| `mut-contradicted-reason` | `unresolved_reason_decision_mismatch` | `unresolved_reason_decision_mismatch` |
| `mut-long-reason` | `unresolved_reason_label_invalid` | `unresolved_reason_decision_mismatch`, `unresolved_reason_label_invalid` |
| `mut-long-extraction-version` | `extraction_version_invalid` | `extraction_version_invalid` |
| `mut-parse-error` | `parse_error` | `parse_error` |

`inputs/empty-results.json` remains the denominator probe: both scorers report `frozen 10`, `heldout 6` and only `missing_result` reasons.

## 4. Idempotency, replay and retry semantics

The real application contract (`WorkflowCommandService`, `WorkflowOperationDao`, `WorkflowOperationEntity`) refuses a blank key, stores one durable operation per key with a unique index, answers a repeated identical request from that record as `NoChange`, and answers the same key with another caller or payload as a conflict.

v2 therefore requires:

- every key to be a non-empty string of at most 128 characters with no control characters, unique within the case; the application's blank-key rule is the whole format requirement, so a non-blank key with surrounding whitespace is a distinct valid key;
- a replay claim to carry the repeated dispatch outcomes `["Applied", "NoChange"]`, exactly one durable `workflow_operations` row for that key with command `UPDATE_EVENT`/`PROPOSE_LINK`/`ATTACH_LINK`/`REQUEST_CLASSIFICATION` and a terminal outcome, and equal before/after state digests;
- a report-level `replay_probe` (same structure, one accepted call) before a score is complete for qualification. The report states `missing`, `valid` or `invalid`; nothing is inferred.

Synthetic self-test probes cover the machine-checkable part: a consistent replay is accepted; a retry that re-applies its effect, a marker-only claim without the durable row, and a replay whose state digest changed are rejected. These are tooling probes, not device evidence.

## 5. Provenance binding

`verification/validate.json` records:

- `active_scoring_version = aegis-f-s8-workflow-scoring/v2`, `contract_version = s8-workflow-contract-v2`, `parser_version = s8-native-parser-v1`, scoring versions `[v1-exact-arguments, aegis-f-s8-workflow-scoring/v2]`;
- `immutable_inputs_verified` = fixture, frozen suite, held-out suite, LFM prompt, system prompt, tools — all equal to checkpoint `b26148e`;
- `bound_inputs_verified` = the six immutable inputs plus `profiles`;
- `scoring_contract_sha256 = c801dda5762e96228f8327c5ab581e0ce7e04b4117d030232e2a1fd720d4eff8`;
- `expected_value_policy_check` — every frozen expected value satisfies the v2 policy applied to it.

A v2 results document must declare scoring/contract/parser versions and the suite, prompt, tools and fixture hashes. `mut-unbound` and `mut-foreign-binding` show that a missing or foreign binding fails the case instead of qualifying silently.

## 6. Achievability audit summary

| Value | Resolution |
| --- | --- |
| literal `idempotencyKey` (9/11 call-bearing cases) | semantic policy: non-empty, bounded, unique, replay-evidenced |
| free-form `normalizedText` (6 cases) | deterministic source/fact checks plus the app-stored reading |
| `TIME_NOT_STATED` label | unresolved decision exact; label any non-blank bounded text |
| `extractionVersion` | declared bounded producer string, never a literal, one per case, recorded in the score output |
| ids, revisions, spans, amounts, merchants, dates, times, bounds, nulls, caller scope | unchanged and exact |

Every remaining exact value was checked against the model-visible inputs (system prompt, case prompt, trusted fixture context, rendered schema). Residual authoring sensitivity is disclosed in the contract and scoring file: the multi-event `merchant: "fuel"` labelling and `attach_link.expectedRevision: 0` stay exact, and a defensible `null` merchant would fail.

## 7. Honest boundaries

- Paraphrase quality is human judgement; the machine checks non-emptiness, source-token overlap, numeric facts, the stored reading and the observed effect, and does not rank paraphrases.
- No format rule is invented beyond what the application enforces: a blank key is refused, while whitespace-padded keys, arbitrary unresolved-reason wording and arbitrary bounded producer versions are accepted and recorded. An earlier draft required trimmed keys, upper-case reason codes and identifier-shaped versions; that would have recreated the same unknowable-format defect and was removed.
- The unresolved-reason label vocabulary is not fixed because the application accepts any non-blank reason string and no product vocabulary exists.
- The declared extraction version is producer-supplied provenance; verifying which extractor produced a reading is a future adapter responsibility.
- Replay evidence is checked structurally; raw trace review remains required.
- Cross-case key uniqueness is the harness's per-case database isolation responsibility.

## 8. Verification actually run

```text
uv run python tools/slm/s8_contract.py validate     -> PASS (10 frozen / 6 held-out / 5 tools / 4 profiles; 6 immutable + 7 bound inputs verified)
uv run python tools/slm/s8_contract.py selftest     -> PASS (all v1 and v2 synthetic probes)
uv run python artifacts/sprint-8/evidence/S8.2/a2/derive_inputs.py -> PASS (4 base inputs, 22 probes)
uv run python artifacts/sprint-8/evidence/S8.2/a2/run_matrix.py    -> PASS (30 score reports, 0 failures)
```

Preserved outputs: `verification/validate.json`, `verification/selftest.json`, `scores/matrix.json`, `scores/*.v1.json`, `scores/*.v2.json`.

No build, lint, formatter, full test suite, Android build, ADB command, device probe, model download or inference was run. No commit or push was made.

## 9. Limits and S8.3 prerequisites

- This is a contract/scoring correction, not model qualification. No candidate score exists and the accepted case above is a diagnostic with a preserved denominator.
- S8.3 must rerun its trials against the frozen v2 revision with the unmodified prompts; v1 raw prompts/outputs stay immutable.
- Before new device scoring the trial harness must add the results `binding`, one `call_effects.stored_reading` per `update_event_interpretation` call, and one validated `replay_probe` per profile. These are host-side evidence additions, not product changes.
- The phone remains disconnected; nothing here is claimed as phone execution, runtime integration or app qualification.

## 10. Source digests

| Source | SHA-256 |
| --- | --- |
| `artifacts/sprint-8/evidence/S8.3/a2/contract-discriminator/results-semantic-variant.json` | `20de94c6e7af13a7891d8a1977590d28fb2b1188bedaeb4e974a9d71e5aef788` |
| `.../trace-semantic-variant.json` | `79095854987a4d845620a82a3f44963fcf9ec3470ba1b86cd8c829f07c812652` |
| `.../results-exact-literal-control.json` | `19033cb9fb7595129c49070393ee2df0a1a7a801f599337f384e5ff2bfd50638` |
| `.../trace-exact-literal-control.json` | `9738a320f0e9bde2e8a263997c15302176343228ff621b4dc2e707e89384944e` |
| `tools/slm/s8_workflow_scoring_v2.json` | `c801dda5762e96228f8327c5ab581e0ce7e04b4117d030232e2a1fd720d4eff8` |
| `tools/slm/s8_contract.py` | `2f71216ddfd07ddeaf955323858a02b2b0e529a0467b042d76b2f4a81ebc643d` |
| `docs/sprint-8-evaluation-contract.md` | `944f6c5ee5b0c463513e36f917cbd1ad5c942bc95708b6796e0455c1283397f8` |
| `tools/slm/README.md` | `808697658ea84cee800a7e32afe6ae764b64cca690403542497437c9106050cf` |
| `tools/slm/s8_workflow_profiles.json` | `27687e0d4915cee3542ca6bfd774e94d4a87c3d6aee705c28ba7208a9a275c57` |
