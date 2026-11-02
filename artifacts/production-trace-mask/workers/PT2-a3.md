# PT2-a3 worker artifact — round-3 capability/required split

- task_id: `PT2`
- attempt_id: `PT2-a1` (round 3, same context)
- agent_id: `MaskedFlowStreaming114`
- cycle: `PT2-cycle-1` round 3, ready_for_review candidate (Main-authorized conditional round)
- input: `artifacts/production-trace-mask/reviews/PT2-review-02.md` (single finding), Main's round-3 authorization
- base commit: `6589c880537ee61a92fb99b91b70df5bc501930c`
- repository `F:/ai-ml/aegis-f`, target `refs/heads/main`; no commit/push.

## Fix (only the adjudicated finding and its callers/tests/docs)

**Capability allowlist vs demanded outcomes.**
- `WorkflowRunRequest` now carries `intents` (capability allowlist: which tools/skills the model
  may use) and `requiredIntents` (outcomes the trusted host explicitly demands). The default is
  `requiredIntents = intents`, so qualification, scripted tests and any existing caller keep the
  previous required-effect contract unchanged.
- Completion checks only `requiredIntents` for a verified effect or a verified settlement. An
  enabled but unused capability is authorized, not demanded; the model still decides from the
  user request what to apply, and only verified effects are reported.
- The interactive Chat passes its checked skills as the allowlist and `requiredIntents = emptySet()`
  (`QuickCaptureScreen`), matching the label "Choose its allowed workflow skills" and removing the
  forced replay/re-interpret failure. No prompt keyword parsing, no skill deselection, no
  removal of effect verification.

**Already-satisfied state, verified without a fake mutation.**
- New `AgentNoEffectClaim.AlreadyInterpreted(eventId, revision)`: `select_source` attaches it when
  the selected occurrence already resolves to an `INTERPRETED` event.
- `verifyNoEffect` re-reads the event and requires the same revision and `INTERPRETED` status.
- `verifyNoEffectSettlements` accepts that settlement for `INTERPRET_EVENTS` when the host
  requires it and no reading was produced, **independently of other mutations in the same run**
  (the existing draft-scope/link no-match settlements keep their zero-mutation guard). It is
  reported as `NoEffect.AlreadyInterpreted`, never as a new reading.
- Claim verification is otherwise unchanged: a dispatched mutation that fails to verify still
  refuses; a host-required intent with neither a verified effect nor a verified settlement still
  ends `Incomplete(REQUIRED_EFFECT_MISSING)`.

## Actual-main regression (same installed app, key/data preserved; no Confirm/ledger)

All five skills enabled in Chat; prompt (IME text visible in the screenshot, unedited): read the
already-interpreted latest note, find/check the matching draft, verify the existing link, use Laya
for a category, do not confirm or replay.

- Run `run-6fd3a77f4388d323196626135ad8e2e5`: **SUCCEEDED**, stopReason `none`, attempt 1
  `COMPLETED`; allowed intents = INTERPRET_EVENTS, LINK_EVENT_TO_DRAFT, PREPARE_LAYA_REVIEW,
  REPLAY_OPERATION, SEARCH_DRAFTS.
- Tool calls: `inspect_link`, `observe`, `classify_draft` — **no `replay_operation`**, no redundant
  `record_event`, no duplicate `attach_link`.
- Verified effect: `Classification(draftId=1, source=NO_FIT, suggestedCategoryId=null,
  confidence=0.2423, reviewStatus=PENDING)`; the UI completion card lists exactly that.
- Link state verified and reported truthfully: event #2 ↔ draft #1 `ACCEPTED` revision 1 (the run
  verified the current identity instead of re-attaching); local Laya snapshot still
  `NO_FIT` at model revision `68f27dfe…/512/192`; draft remains PENDING.
- Ledger unaffected: `transactions` count 0; nothing confirmed; no replay.
- Evidence: `artifacts/production-trace-mask/evidence/PT2-a3/device/` (01–10) and
  `evidence/PT2-a3/db-summary.json` (sanitized; no DB copy).

## Guard verification (not globally disabled)

- `a host-required replay without a replay stays incomplete` — intents {REPLAY_OPERATION},
  required {REPLAY_OPERATION}, answer only → `Incomplete(REQUIRED_EFFECT_MISSING)`, detail
  `replay_operation`.
- Existing `an unproved absence stays incomplete when the effect is required` (default required =
  intents) still passes, as do the link/no-match and mutation-verification suites.
- `an enabled but unused capability is not a demanded effect` — capability-only request with
  interpret+replay allowed completes with no effects.
- `a host-required interpretation settles when the selected event is already interpreted` —
  already-interpreted settlement reported as `NoEffect.AlreadyInterpreted` after a live re-read.

## Checks

- `:app:testDebugUnitTest --offline` — **677 tests, 0 failures, 3 skipped**.
- `:app:compileDebugAndroidTestKotlin --offline` — PASS (new exhaustive branch mapped in the
  qualification harness; no shims).
- `:app:assembleDebug --offline` — PASS; APK sha256
  `a86fe7679872bad7d27cc563de07f2859327fdec646e2fbc6bc35bd9d8a286d0`, installed in place with
  `adb install -r` (same package/data/key) and used for the regression above.

## Docs

`docs/sprint-8-agent-executor.md` (capability vs demanded outcomes; already-interpreted
settlement; Chat semantics), `docs/sprint-8-agent-history.md` (round-3 decision),
`CHANGELOG.md`. No historical evidence rewritten; no version-word tests pinned.

## Disclosed residue / limits

- The pre-fix orphan run rows from the earlier double-admission attempt remain untouched in the
  owner DB as historical evidence (disclosed in round 2).
- The typed prompt is IME-distorted in the screenshot/trace; it is unedited and the requested
  actions are unambiguous.
- Gemini/Responses streaming remain unit-tested only; the live route is Chat Completions.
- Laya proof is readiness + real inference (NO_FIT), not a suggestion-quality claim.

## Freeze

Source frozen at ready_for_review. Manifest:
`artifacts/production-trace-mask/evidence/PT2-a3/manifest.sha256`.
