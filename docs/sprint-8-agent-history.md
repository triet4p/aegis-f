# Sprint 8.10 — durable run history and safe retry/cancel/restart

**Status:** Implemented and exercised on the host (real provider + real Room) and on the
isolated phone package (real local Laya). No worker commits; full-route review and the
separate Bronze checkpoint follow the evidence PASS.

Sources:

- `app/src/main/java/io/github/triet4p/aegisf/data/AgentRunEntity.kt` (+ `AgentRunDao.kt`),
  `AgentRunAttemptEntity.kt` (+ `AgentRunAttemptDao.kt`), `AgentConversationEntity.kt`
  (+ `AgentConversationDao.kt`) — the three durable stores.
- `app/src/main/java/io/github/triet4p/aegisf/data/CanonicalAgentRetryPolicy.kt` — the one
  bounded retry contract (attempts, delays, retention, fingerprint, attempt keys).
- `app/src/main/java/io/github/triet4p/aegisf/agent/AgentRunHistory.kt` — admit/replay,
  claim, record, reconcile, reopen, retention, cancel, safe export.
- `app/src/main/java/io/github/triet4p/aegisf/agent/AgentRunFailure.kt` — the classified
  failure vocabulary (retryable vs terminal).
- `BoundedWorkflowExecutor.startTracked` / `startWithAttemptKey` / `cancelTracked` and
  the per-command fixed-key path in `BoundedWorkflowToolAdapter` — the production wiring.
- `LedgerDatabase` v13 + `MIGRATION_12_13` — populated-data migration proof below.

## Contract

Three stores stay separate, exactly as the frozen contract requires:

1. **User-visible conversation** (`agent_conversation`): request, status, and answer lines
   only, in display order. Agent-role text is the masked-safe line the user actually saw;
   no raw text, no mapping, no credential, no reasoning is a column, so none can leave
   through the conversation read.
2. **Run/attempt journal** (`agent_runs` / `agent_run_attempts`): run identity (one-way
   request fingerprint), retry attempts, allowed effect refs (event id + revision, link
   id + state + revision, draft id + decision source), pending/completed/unknown states,
   and privacy-safe observations. Vault mappings, protocol continuation items, transient
   reasoning, and provider wire detail are not columns.
3. **Business captures and the effect journal**: owned by the existing command/query
   services (`workflow_operations` et al). The history never writes them and never
   exports them; cancelling or deleting the conversation never touches them.

Effect references are the only cross-store pointers, and they are re-read — never
trusted — before any replay or completion claim.

## Retry, reconciliation, cancel, restart

- `CanonicalAgentRetryPolicy` names every bound: at most 3 attempts per run; the
  rate-limit wait honors the provider's `Retry-After` within a 30 s cap, otherwise a
  1 s × attempt backoff within 10 s; at most 50 runs per session (oldest prune first,
  attempts cascade); one-way fingerprints recognize a repeated request.
- `AgentRunFailure` classifies every end: transport, timeout, rate-limit, server failure,
  stale revision, schema repair, and uncertain write are retryable; truncation, content
  filter, masking block, scope refusal, user decision, loop, malformed turn, and exhausted
  budgets are terminal and never retried.
- Before any replay the run reconciles: the durable `workflow_operations` row plus the
  live rows behind the claimed effects are re-read. Journal `APPLIED` + live rows still
  carrying the effect → `Applied` (replay is a safe idempotent no-change). No journal
  row or a moved-on row → `NotApplied` (open a corrected request under a fresh key with
  revalidated revisions). A still-`PENDING` claim or an unreadable effect → `Unknown`
  (do not replay). A stale revision therefore ends as a conflict for a corrected next
  attempt, never as a silent overwrite and never as a duplicate.
- Cancelling settles the durable attempt and marks the run `CANCELLED`; a stored effect
  stays stored and stays verified — cancellation never claims rollback, and there is no
  rollback operation on the agent path. A late provider reply or a competing reopen under
  an old owner token cannot settle the run's current attempt (conditional transitions).
- Restart (reopen/retry) revalidates revisions from fresh observations, resumes with the
  masked summary seam (provider continuation items stay protected, task-scoped, and
  retention-bounded), and retains protected local mappings only as long as required:
  mappings live in the session vault in memory and are never persisted.

## Retention, deletion, export

- Retention is bounded and explicit: 50 newest runs per session; the safe export pages
  conversation lines (run id, role, text, time) and never exposes operation keys,
  digests, attempt tokens, observations, mappings, continuation items, reasoning, or
  wire detail. `allowBackup="false"` stays set in the manifest, so history never leaves
  through auto-backup; the operation journal (`workflow_operations`) is never exported
  from this path at all.
- Deleting the visible conversation (whole session, or retention pruning) never deletes
  business captures, effect journal rows, or ledger records, and never claims to roll an
  effect back.

## Verification

- `S810DurableRunHistoryTest` (host, real Room/services/history, scripted provider seam):
  repeated send replays one run (1 journal row per mutation family); interrupted update
  reconciles to `Applied`; stale revision reconciles to `NotApplied` and stays conflicted;
  cancel leaves the stored effect intact and never claims rollback; retention prunes while
  the export excludes keys/digests/mappings/raw values; a late reply cannot settle a
  cancelled attempt. 6/6 PASS.
- `AgentRunMigrationTest`: a populated v12 database (categories, ledger records, drafts,
  captures, an event, a link, a snapshot, command rows, a classification job) migrates
  to v13 with every row/identity/total intact and the three new tables usable; an
  injected failure in the last step rolls the whole upgrade back with the v12 rows and
  totals intact. 2/2 PASS.
- Live hosted slice (`S810DurableLiveSmokeTest`, gated by `AEGIS_S810_LIVE_SMOKE=1`,
  synthetic fixtures only, masked evidence in
  `artifacts/sprint-8/evidence/S8.10-replan-a1-live-attempt-01.json`): Go Chat Completions
  `deepseek-v4.1-flash` echoed on all six 200 turns, `Completed(turns=6,toolCalls=5,
  effects=2)` with an `EventReading` + `ACCEPTED` link (rev 1), journal rows 1/1/1, draft
  `PENDING`, zero ledger rows, reconciliation `Applied`, a repeated identical send
  replayed the same durable run, and zero raw amount/date/time literals in outbound
  requests. One earlier attempt ended in an honest terminal `Refused
  (user_decision_required)` (the model sent `userOverride` as the string `"null"`);
  it is preserved in the worker record, not presented as success.
- Device slice (`S810DurableDeviceInstrumentedTest`, isolated `.s810evidence` package,
  device ALT-LX2 / Android 14): `OK (2 tests)` — the durable tracked run through the
  production Laya runtime (`NO_FIT`, 6 turns / 5 tool calls, one snapshot, one journal
  row per mutation, draft `PENDING`, zero ledger rows, heartbeat 20/20) and the
  cancel/late-reply branch. Scripted provider seam (no `INTERNET` permission, no
  credential, no socket); the hosted-exchange leg is the separate live evidence above.
  Raw logs: `artifacts/sprint-8/evidence/S8.10-replan-a1-instrumentation.log`,
  `artifacts/sprint-8/evidence/S8.10-replan-a1-laya.logcat` (+ device-filtered
  `S8.10-replan-a1-device.logcat`), structured
  `artifacts/sprint-8/evidence/S8.10-replan-a1-device-result.json`.
  Pinned bundle hashes verified by `run-as sha256sum`: `laya.onnx a874eb25…`,
  `laya.onnx.data 48774636…`, `laya_config.json 5049005d…`,
  `tokenizer/laya_tokenizer.bin 09b371ce…`.
