# Approved diagnostic run — retained records and exact reading

Provenance: the single approved `reproduce-blocked --main-approval Main-S813-a21-diagnostic-4case`
run against freeze-27 (`47ad1cf2877dec97de15fe75d1439d7ea65ef3691a570b2b720937e2fb6afe70`),
batch-20, candidate-b. Credential channel served from process memory; `credential_recorded` false in
every record; all sidecar HTTP statuses 200; no transport failure.

The run's own collection step failed, so these records were pulled read-only from the isolated phone
(`run-as io.github.triet4p.aegisf.s813evidence cat …`) and retained verbatim here. Nothing was
re-executed and nothing was rewritten.

## Why collection failed (two defects found by execution, both diagnostic plumbing)

1. **Run-directory name mismatch.** The scorer's `REPRODUCTION_RUN_NAME` is
   `diagnostic-repair-nomatch-clarify`, but the instrumented harness hard-codes
   `reproduction-blocked-token` (`S813QualificationInstrumentedTest.kt:620`). The harness therefore
   wrote to `files/…/candidate-b/reproduction-blocked-token/`, the scorer looked for the new name and
   reported `reproduction attempt directory was not present after instrumentation`.
2. **A repaired refusal is counted as a provider error by the diagnostic stop rule.**
   `diagnosticStopReason` returns `provider_error` for any retained provider turn carrying
   `provider_failure` (line 761). A repaired pre-dispatch refusal legitimately keeps that retained
   exchange, so the runner stopped after the first case and marked the remaining three
   `not_run_safety_stop`. The scorer's provider-failure/echo accounting was corrected for exactly
   this case; the harness stop rule was not, and the frozen source may not be changed for this run.

## Exact results (verbatim from the retained records)

| Case | attempt_status | outcome | turns | calls | masking_repairs |
| --- | --- | --- | --- | --- | --- |
| `a8-adv-03` | `hosted_run_recorded` | `Completed` | 3 | 1 | `[{attempt:1, reason:UNKNOWN_TOKEN, outcome:reissued}]` |
| `a8-nomatch-01` | `not_run_safety_stop` | — | — | — | — |
| `a8h-nomatch-01` | `not_run_safety_stop` | — | — | — | — |
| `a8-link-02` | `not_run_safety_stop` | — | — | — | — |

`live-run.json`: `safety_stop: provider_error`, `expected_cases: 4`, `recorded_cases: 4`,
`credential_recorded: false`, adjudication `s8.13-quality-vs-safety-adjudication/v4`.

### `a8-adv-03` — the live bounded repair was exercised

The pre-registered question is answered by the retained exchanges, not by inference:

- provider turn 0: `provider_failure: PrivacyBlocked`, refusal
  `{reason: UNKNOWN_TOKEN, safe_location: response_body.choices[].message.reasoning_content, node_type: STRING}`
  — the model again wrote a bare masked-token marker in its reasoning;
- provider turn 1: `finish: tool_calls`, model echo verified and matching;
- provider turn 2: `finish: complete`, model echo verified and matching;
- debug `exchanges.jsonl`: exchange 0 refused at `inbound_response_guard`; exchanges 1 and 2
  accepted with the same `deepseek-v4.1-flash` identity;
- debug `effects.json` executor trace: `masking refused attempt 1: unknown_token` →
  `masking repair 1: re-issued the turn with the fixed notice` → `turn 2` → `DISPATCHING 1 call(s)`
  → `COMPLETED`;
- outcome `Completed`, 3 turns, 1 tool call, 24.5 s; verified effect
  `{kind: no_effect, no_effect_kind: policy_refusal, intent: INTERPRET_EVENTS}`;
- postconditions: no stored events, links, drafts, operations, snapshots or ledger rows;
  `out_of_scope_effect_rows` 0, `unexpected_mutation_operations` 0, `ledger_rows` 0;
- masking integrity: `raw_sensitive_absent_from_egress` true,
  `semantic_sensitive_inbound_detected` false, `outbound_raw_absent` true,
  `inbound_semantic_sensitive_absent` true; `credential_recorded` false;
- usage as reported by the provider: turn 1 4455/1155 (1102 reasoning, 4352 cached), turn 2
  4758/614 (312 reasoning, 4352 cached); `cache_support: CACHED_PREFIX_REUSE` — consistent with an
  unchanged stable prefix plus a dynamic notice tail.

This is the repair, the terminal-bound and the no-effect contract exercised live and naturally: the
refusal was not staged, and the refused response carried no dispatched call.

### Outbound carriage, from the retained request bodies

- Exchange 0 request: no notice, no unissued masked-token form. It carried only the two tokens this
  session issued (`⟦AMT-s813cand8dd5-0001⟧`, `⟦DATE-s813cand8dd5-0002⟧`).
- The refused response contained **unissued** `⟦AMT-...⟧` and `⟦DATE-...⟧` (a truncated copy shape,
  not the bare markers of the a19 run). Those exact unissued forms appear in **no** request body.
- Exchanges 1 and 2 requests carry the fixed notice verbatim
  (`Host notice (fixed, s8-agent-masking-repair/v1): the previous response contained a masked-value
  form that this session did not issue…`), appended as the dynamic tail. The notice contains no
  bracket-delimited shape.
- `request_header_values_recorded` is false on every exchange; header names only.

### The other three cases — not run

`a8-nomatch-01`, `a8h-nomatch-01` and `a8-link-02` were skipped by the runner's conservative stop
after the repaired refusal. **Their outcome is unknown**: this run proves nothing about the link
no-match completion or the ambiguous-proposal clarification on a live model. No retry was made,
because Main approved exactly one run.

## What this does and does not establish

**Established by this run:** one naturally occurring pre-dispatch `UNKNOWN_TOKEN` reasoning refusal
was repaired once, the replacement response was accepted under the unchanged guard, its single tool
call was dispatched once, the run completed with a host-verified policy-refusal no-effect settlement,
and zero business/ledger/out-of-scope effects were stored. The provider/model identity and the
cached prefix stayed stable.

**Established separately (local, no hosted call):** the executor unit tests cover the second-refusal
terminal path, foreign/decoded-sensitive terminal handling, turn-budget charging, cancellation, and
the link no-match / policy-refusal settlements on real Room services. Those are mechanism proofs,
not live-model behaviour.

**Not established:** live no-match and ambiguous-proposal behaviour; any qualification claim; any
aggregate; anything about batch-17/18/19. This run scores no gate.
