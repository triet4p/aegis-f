# S8.13-replan-a21 evidence

Owner-approved correction loop for `S8.13-cycle-1` round 1. This attempt implements the bounded
masking repair, the scorer false-negative fixes, the no-effect completion contract and the
declaration/skill wording corrections against a **new** candidate identity. No retained batch-17/18
record, no frozen snapshot and no earlier evidence directory was modified.

| Item | Value |
| --- | --- |
| `task_id` | `S8.13` |
| `attempt_id` | `S8.13-replan-a21` |
| batch | `S8.13-v3-interface-qualification-batch-20` |
| snapshot | `S8.13-v3-interface-a21-freeze-27` |
| adjudication | `s8.13-quality-vs-safety-adjudication/v4` |
| scoring contract | `aegis-f-s8-agent-v3-qualification-scoring/v7` |
| masking repair | `s8-agent-masking-repair/v1` |
| instruction block | `s8-agent-context/v5` |

## What this attempt changed

1. **Bounded masking repair (runtime).** `MaskingRepairPolicy` (fixed, versioned, bracket-free
   notice), `HostedGenerationAdapter.appendUserNotice` with native lowering in the Chat
   Completions, Responses and Gemini adapters, `MaskedHostedGateway.withRepairNotice`, and the
   executor's single repair path. The refused response and its calls are discarded whole, the
   attempt is charged to the turn budget, and every non-eligible refusal stays terminal.
2. **No-effect completion (runtime).** `WorkflowVerifiedEffect.NoEffect` (empty search, link
   no-match, policy refusal), host re-reads in `AgentToolRuntime.verifyNoEffect` /
   `verifyLinkAbsence`, `AgentNoEffectClaim` facts carried from `list_drafts` / `inspect_link`, and
   `WorkflowSettlement.NO_EFFECT_AUTHORIZED` for a host-classified out-of-catalog request. Durable
   history records a distinct `no_effect` attempt kind and `masking_repairs` chronology; the UI
   separates "no application change" from mutation effects.
3. **Scorer (prospective, v4/v7).** Outcome kind compared before `:<REASON>`; exactly one terminal
   suspending `ask_user` without a tool result accepted only for an authoritative `Clarifying` run
   with the mapped field and no later dispatch; only declared search filter keys compared; only
   successful clarification suspensions counted; no-effect evidence required for
   `no_match_required` and for a completed policy refusal.
4. **Declarations and skill.** `ask_user.subject_ref` states the runtime per-field subject
   constraint; `inspect_link` / `propose_link` say eligibility governs attachment only; the
   `link-event-draft` skill advances to v2 (tentative proposal, then ask; never attach/accept) and
   the loader's expected-version map matches.

## Layout

- `checks/selfcheck-report.json` — scorer contract selfcheck (deterministic, no device).
- `checks/check_a21_contract.py` + `checks/a21-contract-report.json` — pre-build cross-artifact
  coherence gate: notice shape, adapter lowering presence, version identities, declaration
  wording, unchanged fixture digest and approved gate values.
- `snapshot-27/` — static preflight mapping and (after build/device/freeze) the APK, path, device
  and freeze records.

## Approved diagnostic (executed once)

`reproduce-blocked --main-approval Main-S813-a21-diagnostic-4case` ran once against freeze-27.
`a8-adv-03` completed after exactly one host repair of a naturally occurring pre-dispatch
`UNKNOWN_TOKEN` reasoning refusal (3 turns, 1 tool call, 3 accepted-after-repair exchanges, verified
`no_effect: policy_refusal`, zero business rows). The runner then stopped with `safety_stop:
provider_error` and skipped `a8-nomatch-01`, `a8h-nomatch-01` and `a8-link-02`.

Two plumbing defects were exposed (both outside the repair runtime, and neither changed for this
frozen run): the harness still writes its run directory as `reproduction-blocked-token` while the
scorer expects the new run name, and `diagnosticStopReason` treats the retained repaired-refusal
exchange as a provider error. The records were pulled read-only from the phone and retained under
`run-retained-01/` with the exact reading in `run-retained-01/NOTES.md`. No retry was made.

## Proven vs not proven

**Proven (locally, no hosted call):** the source compiles for `debug` and `debugAndroidTest`; the
full unit suite passes (605 tests, 0 failures); the scorer selfcheck passes 40/40; the a21 contract
gate passes 48/48; the new unit tests pass (three-protocol notice lowering, one-repair/terminal/
foreign/decoded-sensitive/budget/cancellation behavior, link no-match and policy-refusal no-effect
settlements, and the negative cases that must stay `Incomplete`). The isolated package was built,
its manifest inspected, installed and exercised on `AAAJBB4930914141` (`OK (3 classes; 4 tests)`:
domain preflight, actual Laya smoke, debug-capture proof), and the candidate was frozen as
`S8.13-v3-interface-a21-freeze-27`
(`47ad1cf2877dec97de15fe75d1439d7ea65ef3691a570b2b720937e2fb6afe70`, `paid_calls_made: false`).

**Not proven here:** live no-match, ambiguous-proposal and clarification behaviour (the three cases
were skipped by the conservative stop above), any qualification claim, and any aggregate. A live
model was measured exactly once, under the owner's single approved diagnostic.
