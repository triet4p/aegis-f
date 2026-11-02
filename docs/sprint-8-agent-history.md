# Sprint 8.10 — durable run history and safe retry/cancel/restart

**CAT1 (2026-11-02):** Laya and its English translation bridge are removed from the active
app. The hosted workflow chooses an existing category from the draft observation's current
`category_choices` and submits it through `update_draft_review` (the ordinary draft-review
update). Laya-specific sections below are historical S4/S7/S8 evidence, not the active
contract.

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
  the per-command fixed-key path in `AgentToolRuntime` — the production wiring.
- `LedgerDatabase` v13 + `MIGRATION_12_13` — populated-data migration proof below.

## Contract

Three stores stay separate, exactly as the frozen contract requires:

1. **User-visible conversation** (`agent_conversation`): request, status, and answer lines
   only, in display order. User requests are locally masked before either the conversation line
   or the run-row request text is persisted; agent-role text is likewise the masked-safe line
   the user actually saw. No unmasked request, mapping, credential, or reasoning is retained
   in these history paths.
2. **Run/attempt journal** (`agent_runs` / `agent_run_attempts`): run identity (one-way
   request fingerprint), retry attempts, allowed effect refs (event id + revision, link
   id + state + revision, draft id + decision source), pending/completed/unknown states,
   and privacy-safe observations. Vault mappings, protocol continuation items, transient
   reasoning, and provider wire detail are not columns of this journal. They are retained
   only by the separate local production trace below, which is never exported.
3. **Business captures and the effect journal**: owned by the existing command/query
   services (`workflow_operations` et al). The history never writes them and never
   exports them; cancelling or deleting the conversation never touches them.
4. **Local production trace** (`agent_trace_events`, schema v14): the ordered, append-only
   diagnostic evidence of what actually crossed the provider and tool boundaries. It is
   owner-approved local-only retention (see "Policy decision" below) and is never part of
   the conversation export, the hosted prompt, or any write path.

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
  budgets are terminal and never retried — with exactly one exception: a *pre-dispatch*
  `UNKNOWN_TOKEN` response may be re-issued once by the host under the bounded masking-repair
  rule (v5). The refused response and every call it carried are discarded whole, the attempt is
  charged to the turn budget, and a second `UNKNOWN_TOKEN` or any foreign-token, raw-literal,
  decoded-sensitive, malformed-JSON or metadata refusal is terminal with no fallback spelling,
  endpoint or model. The chronology (`attempt`, `reason`, `reissued`/`terminal`) is recorded as
  content-free `masking_repairs` in the durable observation and the case record.
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
- The local production trace is retained in the same database but is deliberately outside
  every export path: `safeExport` does not query `agent_trace_events`. Deleting a run
  (retention pruning, a whole-session history delete, or a cascade) deletes its trace rows
  with it; nothing is copied anywhere.
- Deleting the visible conversation (whole session, or retention pruning) never deletes
  business captures, effect journal rows, or ledger records, and never claims to roll an
  effect back.

## PT1 — local production trace (schema v14)

Owner-approved correction: the run/attempt journal's aggregates could not reconstruct a
run, so production now also keeps the complete evidence of what crossed the provider and
tool boundaries.

### Contract

- **Location and ordering.** `agent_trace_events` in the production Room database;
  `id` (autoincrement) is the ordered event identity, and `createdAtEpochMillis` is the
  append time. Rows are append-only and never updated; a reader orders by `id`.
- **Scope.** Every row carries `runId` (foreign key, cascade delete), `scopeId`, and
  `attempt` (`0` for run-scope admission/cancellation). Provider rows also carry `turn`
  (the attempt's exchange number) and tool rows carry the model's `callId`.
- **Content.** `RUN_ADMITTED` (intents, limits, masked request), `ATTEMPT_STARTED`,
  `PROVIDER_REQUEST` (exact URL, credential-free headers, complete body), `PROVIDER_RESPONSE`
  (status, safe headers, complete body — this is where provider-returned reasoning /
  continuation items live), `PROVIDER_FAILURE` (classified value-free code + detail),
  `TOOL_CALL` (name, exact arguments, disposition), `TOOL_RESULT` (complete envelope,
  effect, verification, dispatched state, operation/target identity), `TOOL_ERROR`,
  `CLARIFICATION_REQUEST`, `CLARIFICATION_REPLY` (the user's own answer), `FOLLOW_UP_MESSAGE`,
  `ATTEMPT_OUTCOME` (state, stop reason, answer, counts, usage, verified effects, repairs,
  compaction, refusal), and `RUN_CANCELLED`.
- **No truncation, no summaries.** Provider bodies, tool arguments/results and clarification
  text are stored verbatim. The journal's aggregates remain in the journal; the trace never
  substitutes a summary for observed content.
- **No credentials.** The transport decorator drops credential/session headers by name
  (`Authorization`, `x-goog-api-key`, cookies, …) and records their names only in
  `redactedHeaders`; credential-looking URL query values are replaced with `[redacted]`.
  No auth header, token, cookie or other transport secret is stored.
- **Guard refusal is not a gap.** A provider response the inbound privacy guard refused is
  still recorded exactly as returned. The guard governs publication, rehydration and
  continuation of that body, not local evidence; the row is never displayed as an answer,
  never rehydrated, never continued, never exported — and it is the evidence needed to
  diagnose the refusal.
- **Explicit gaps.** Missing evidence is never invented. Schema v14 writes one
  `EVIDENCE_GAP` (`PRE_TRACE_HISTORY`) per pre-existing run and one (`PRE_TRACE_ATTEMPT`)
  per pre-existing attempt; an exchange whose response never arrived is `PROVIDER_RESPONSE`
  with completion `UNAVAILABLE` (failure) or `INTERRUPTED` (cancellation), and carries no
  body.
- **Trace-write failure is not completeness.** A failed append never fails a run. The store
  records `EVIDENCE_GAP` / `TRACE_WRITE_FAILED` immediately, and if even that write fails it
  keeps a per-run pending marker that the next successful write records as
  `TRACE_WRITE_FAILED_EARLIER` (with the count and kinds of the lost events, still attributed
  to the run that lost them). If the database itself stays unavailable, no marker can be
  written at all: that absence is an unavoidable limit of local storage and must never be
  read as "the trace is complete". The trace is complete only for the events whose rows exist.
- **Production, not debug-only.** `AppContainer.createHostedWorkflowHost` always builds the
  recorder and wraps the production transport with `TracingHttpTransport`; the binding is
  set by `startTracked` / `resumeTracked` after the durable run/attempt exists. Tracing
  failures are swallowed (with a gap marker when possible) and can never fail, duplicate or
  authorize a tool call or business effect.
- **Interrupted/partial exchanges.** Evidence is complete for what the app observed: a
  request row exists before the call, and a cancelled or failed call is explicit. Streamed
  provider output is not implemented in this app; the trace schema already supports it
  (chunk rows closed `COMPLETE`/`INTERRUPTED`) so a later streaming transport cannot silently
  drop a partial answer.

### Policy decision (owner-approved, appended for PT1)

Retain the complete provider request/response bodies, including provider-returned reasoning
and protocol continuation items, **locally only** in the production database so a run can be
reconstructed; never export or upload them, never store authentication headers/credentials,
keep local-RAW and hosted-masking/guard policies unchanged, and mark every unavailable piece
of evidence explicitly instead of reconstructing it. This supersedes the earlier blanket
"never retain transient reasoning or wire detail" statement for the local diagnostic store
only; the conversation, the hosted prompt and the export keep their previous policy.

### Verification

- `AgentTraceMigrationTest` (populated v13 database from the exported schema): every run,
  attempt and conversation row and identity survives; one explicit `PRE_TRACE_HISTORY` gap
  per run and one `PRE_TRACE_ATTEMPT` gap per attempt are recorded with no body; the trace
  is usable and ordered afterwards; deleting the run cascades its trace; an injected
  conflicting trace table aborts the upgrade and rolls back with the v13 rows intact.
- `ProductionTraceTest`: the app container's own host, history and trace store over the real
  chat-completions adapter and production transport decorator record the ordered run
  admission, attempt start, both provider exchanges (including the provider's returned
  reasoning), the tool call/result and the terminal outcome; no credential appears; the
  same trace is re-read after reopening the container; failure and cancellation are recorded
  as explicit gaps; a clarification question plus the user's raw reply are recorded on the
  same run while the provider only receives the masked reply.

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

## S8.11 context, prompt caching and compaction (summary)

Full contract lives in the executor sources (`AgentContextManager`,
`BoundedWorkflowExecutor`, `MaskedHostedGateway.cacheKeyFor`,
`HostedProviderIdentity`); this section records the durable-history interaction only.

- The stable versioned instruction/tool/skill prefix is built per run from trusted
  host state: the provider identity (protocol/host/model/frozen per-model budget from
  the validated adapter config), the canonical tool schema, the selected versioned
  skills, and the vault scope tag as the privacy context. No prompt text, raw value,
  mapping, key, or credential ever enters the cache key; a provider switch therefore
  starts a fresh native chain from the same stable prefix instead of replaying stale
  continuation items.
- Per-turn provider usage is recorded exactly as reported (input/output/reasoning/
  cached tokens; absent fields stay null) on `Completed`/`Incomplete` outcomes, and
  the history's safe observation carries summed totals plus the derived cache behavior
  (`CACHED_PREFIX_REUSE` on any positive cached-input report, `NO_CACHE_REPORTED` on
  reported usage without cached input, `USAGE_UNSUPPORTED` otherwise). No estimated
  savings are computed or reported as fact.
- Local compaction drops only older masked tool-result payloads from the accounted
  prompt (drive loop and dispatch-turn paths; resume path compacts the resume seam the
  same way). Every dispatch record stays in memory, every effect is re-read from live
  application rows before verification, and a compaction record
  (`compactedTurns/droppedToolResults/carriedDispatches`) is carried on the outcome and
  the history observation. Summaries never authorize state; mutable query results are
  always re-read by scope/revision.
- No schema change was required: usage, cache identity, and compaction travel on the
  in-memory outcome and inside the existing attempt `observationJson` detail object
  (counts, derived support, fingerprint prefix, compacted count). The safe export is
  unchanged and still excludes keys, digests, mappings, credentials, reasoning, and
  wire detail.

Proven by `S811ContextCacheTest` (11 focused tests, host, scripted provider seam) and
the gated live smoke `S811ContextLiveSmokeTest` (real Go Chat Completions run with
exact masked wire, per-turn usage, cache identity, and verified effects).

## PT2 masked-value usability and live streaming (summary)

Owner-authorized post-S8.13 correction. Full contract lives in
`SensitiveVault`, `AgentToolRuntime`, `HostedGeneration`, `MaskedHostedGateway`,
`BoundedWorkflowExecutor` and the provider adapters; this section records the policy
decision and the durable-history interaction only.

- **Opaque value identity.** One token per value. Field-role canonicalization is
  unchanged (whole dong for amounts, `YYYY-MM-DD`/`HH:mm` for date/time, integer
  reference text for accounts), but the token carries no role. The host decides a
  value's role from the argument it is passed to and validates against the current
  protocol; a role mismatch is a field error repaired from observations, never a
  missing value that triggers clarification. This is the owner bug: the amount was
  masked with an amount-shaped token, could not satisfy date/time arguments, and the
  model asked the owner to retype a value that was present in the note.
- Only a date-shaped token is offered for a date argument and only a time-shaped token
  for a time argument; the date-anchor helper selects by value validity, so a
  YYYY-MM-DD value is never cut to a bare year. Arrival-time masking folds an already
  masked value into its existing token, so identity and role stay stable across turns.
- Diagnostics/exports describe tokens by lexical shape only (`sensitiveShape`), never
  by field role; a `null` explicit field is absent, never the text "null" (streaming
  providers send explicit nulls for omitted optionals).
- **Streaming.** Streaming is a real transport path, not simulated typing: SSE frames
  are parsed incrementally, tool-call JSON is assembled but only a complete validated
  call is dispatchable, and provider complete/error/cancel are classified like a
  complete-body turn. Public text crosses `PublicStreamGate` before display; private
  reasoning is never progress. The trace keeps every `PROVIDER_STREAM_CHUNK` and the
  assembled canonical body, with `INTERRUPTED`/`UNAVAILABLE` terminal states. A route
  without an honest stream reports `StreamingUnavailable` and runs the complete-body
  exchange.
- Known UI lifecycle limitation (unchanged, out of this correction's scope): the chat
  composer's run/continuation/stream state is composable-scoped, so switching tabs
  while a run is displayed loses the visible clarification/progress while the durable
  DB row keeps its true state (CLARIFYING/RUNNING). It is disclosed, not silently
  fixed by a UI redesign.

## PT2 round-2 addendum (bounded corrections)

- **Chat exchange ownership.** The chat's in-progress exchange (outcome, clarification
  continuation, streamed public text, job handle) is owned by the shell in
  `ChatAgentRunState` and launched on the shell's coroutine scope, not by the Chat destination
  subtree. A bottom-tab switch no longer cancels the run or drops the displayed clarification;
  the destination reads the same state when it returns. The durable Room rows remain the
  authority.
- **Immediate capture projection.** Every settled send/resume bumps a projection revision that
  is a key of the capture-loading effect, so a freshly written event shows as `Interpreted` in
  the capture list instead of the stale `unresolved · no interpretation recorded`.
- **One durable run identity.** The UI admits the run so it can show the identity and offer
  cancellation/resume before the first provider turn; the executor now claims and runs that
  same admission (`startTracked(admitted, …)`) instead of admitting a second, never-claimed run
  row. Clarification resume therefore targets the run the user is interacting with.
- **Nested provider-id metadata.** The decoded response scan exempts only the documented
  provider-assigned tool-call identifier fields, validated by the opaque-id schema and never
  when the identifier is itself a mapped value. Sensitive values glued into ordinary text or
  into any other field still fail the strict substring leak scan.

## PT2 round-3 addendum (capability vs demanded outcomes)

- `WorkflowRunRequest` now separates the capability allowlist (`intents`, selecting tools and
  skills) from the host-demanded outcomes (`requiredIntents`, defaulting to `intents`). Only the
  demanded set gates completion; the interactive Chat passes its checked skills as the allowlist
  and an empty required set, so enabling a skill no longer forces an unrelated or already-done
  mutation. Qualification and scripted callers keep their existing required-effect contract.
- A selected source whose event already carries an `INTERPRETED` reading at the observed revision
  settles as `already_interpreted` after the executor re-reads the event, in a mixed run as well:
  the goal state is proved from live state instead of being claimed or rewritten.
- Claim verification is unchanged: a dispatched mutation that fails to verify still refuses, and
  a host-required outcome with neither a verified effect nor a verified settlement stays
  `Incomplete(REQUIRED_EFFECT_MISSING)`.
