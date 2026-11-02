# S8.13-replan-a23 evidence

- `task_id`: `S8.13`
- `attempt_id`: `S8.13-replan-a23`
- `agent_id`: `S813BoundaryGold23`
- candidate snapshot: `S8.13-v3-interface-a23-freeze-30`
- review: `S8.13-cycle-1`, round 1 UNASSESSED

Owner-approved policy revision implemented as one boundary/context concern: provider
reasoning/thinking is no longer masking-guard input, the model answer and tool-call arguments stay
enforced, and every model turn carries the run's complete issued-token inventory. Qualification is
**not** claimed; no hosted/provider call was made.

## Records

- `checks/emit_freeze.py`: deterministic local manifest emitter (no network).
- `snapshot-a23/freeze.json`: candidate source/contracts/schemas/skills/scoring/APK freeze.
- `checks/unit-tests.json`: consolidated JVM unit-suite result.
- `checks/scorer-selfcheck.json`: Python scorer behavior selfcheck for the bumped scoring contract.
- `snapshot-a23/build.json`: isolated evidence APK build record and hashes.
- `snapshot-a23/device-smoke.json`: local-only isolated-phone instrumentation result.

## What was verified (and how)

See the worker artifact for the full command list and observed output. The verification ran the
real parser, gateway, adapters and executor paths: reasoning content with invented/raw notation is
accepted on all three protocols, the same content in the answer or tool arguments is refused before
publication/dispatch, malformed envelopes still fail, and the inventory is exact, complete and
value-free on the initial turn, after new observations, after compaction and across a provider
switch/new run.

## Not verified here

- No hosted API call, no credential read, no qualification suite run and no regrade.
- Inherited unresolved findings from earlier attempts are unchanged and are listed in the handoff;
  they are not fixed or reclassified by this attempt.
