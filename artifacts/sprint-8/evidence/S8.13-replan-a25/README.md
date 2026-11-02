# S8.13-replan-a25 evidence

- `task_id`: `S8.13`
- `attempt_id`: `S8.13-replan-a25`
- `agent_id`: `S813ResumeGold25`
- candidate snapshot: `S8.13-v3-interface-a25-freeze-31`
- review: `S8.13-cycle-1`, round 2 (UNASSESSED; assigned evidence gate below)
- owner limit: current attempt only — no a26/replacement/retry/third round/hosted batch on failure.

Owner-authorized r2 correction of the three consolidated r1 findings. Qualification is
**not** claimed; no hosted/provider call was made.

## Records

- `checks/emit_freeze.py`: deterministic local manifest emitter (network-free; retargeted from the a23 script to a25 ids).
- `snapshot-a25/freeze.json`: candidate source/contracts/schemas/skills/scoring/APK freeze (`hosted_calls: 0`).
- `checks/unit-tests.json`: consolidated JVM unit-suite result (618/0/0/3 PASS).
- `checks/scorer-selfcheck.json`: Python scorer behavior selfcheck (PASS, 50 checks, scoring v9).
- `snapshot-a25/build.json`: isolated evidence APK build record and hashes.
- `snapshot-a25/device-smoke.json`: compile-success / device-not-executed record (honest not-run; `adb devices` empty).

## What was verified (and how)

Current-attempt execution only: full JVM unit suite green; scorer selfcheck PASS; Android test
compile + isolated APK builds green. New/changed-path device tests compile but did not run
(no device attached); a23's device results are preserved in
`../S8.13-replan-a23/snapshot-a23/device-smoke.json` and are not re-claimed here.
Historical S8.5 live captures (`artifacts/sprint-8/evidence/S8.5/a1/live/`) informed the
opaque-state contract call (Responses stateless resend works with function_call+output items;
Gemini echoes `thoughtSignature` every turn; Chat's second request carries no reasoning) but
involve no new hosted call and are cited as historical, not current-attempt, evidence.

## Not verified here

- No hosted API call, no credential read, no qualification suite run and no regrade.
- Changed-path phone smoke (guard-channel + debug-capture classes incl. the mixed
  thought-call/Responses test) awaits a device; compile is proven, execution is not.
- Inherited unresolved findings from earlier attempts are unchanged and are listed in the
  handoff; they are not fixed or reclassified by this attempt.
