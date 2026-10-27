# Sprint 8.9 — bounded multi-turn agent executor

**Status:** The bounded executor was first exercised on the host with real providers, the real
masked gateway, typed tools/Room services and local Laya. S8.12 now wires it into Chat with
user-configured hosted providers; this document's earlier host/device evidence remains historical
and does not substitute for S8.12 Android UI verification.

Source: `app/src/main/java/io/github/triet4p/aegisf/agent/BoundedWorkflowExecutor.kt`.

## Contract

One `BoundedWorkflowExecutor` is constructed from one `WorkflowRunHost`; `start` runs one request,
`resume` continues a suspended clarification.

- `WorkflowRunHost` — trusted, host-built state: the `AgentSession` scope, the S8.6
  `MaskedPromptBoundary`, the `MaskedHostedGateway` (the only provider path accepted), the current
  v2 `HostedToolDeclaration` set, the versioned `WorkflowSkillCatalog`, the real
  `WorkflowCommandService`/`WorkflowQueryService`, a trusted operation-key source and a clock.
  Construction refuses a boundary from a different session or a declaration set other than the
  five tools.
- `WorkflowRunRequest` — host-selected `WorkflowIntent`s (which load exactly those runtime
  skills), the trusted user request text and `WorkflowRunLimits`.
- `WorkflowRunLimits` — `maxTurns`, `maxToolCalls`, `maxContextCharacters`, `maxWallClockMillis`,
  `maxRepeatedIdenticalCalls`, `maxCaptureObservations`. The generation budget is not set here:
  it is the host's frozen per-model `HostedGenerationBudget` from S8.5 (the live slice uses 8192
  output tokens). There is no shared output cap and no retry that would hide truncation.
- Outcomes — `Completed` (answer plus only the effects re-read from application state),
  `Clarifying` (the application asked for a user decision, with a one-shot continuation),
  `Refused` (an application/masking boundary the agent cannot cross) and `Incomplete` (a declared
  budget, truncation/filter/provider failure, a loop, or an unverified effect).

## Turn flow and states

`READY → GENERATING → DISPATCHING → GENERATING … → VERIFYING → COMPLETED`, with terminal
`CLARIFYING`, `REFUSED` and `INCOMPLETE`. Every transition is recorded in a bounded run trace that
carries state names, tool names, application status codes, ids and revisions — never prompt or
tool-result text.

Each turn:

1. Budget checks (turns, wall clock, accumulated masked context) happen before a provider request.
   The initial (and resumed) prompt carries the exact masked source length of each observed
   capture, so a model forms a span from real offsets instead of guessing character counts;
   the length is a count, never source content.
2. The masked gateway performs one generation. Provider failures are classified and stop the run
   honestly. `Incomplete`/`ContentFiltered` turns stop the run before any call in them can execute.
3. For a tool-call turn, every pending call is answered exactly once:
   - Only `dispatchable` (complete, schema-valid, known-tool) calls execute. Unknown, malformed,
     schema-invalid, truncated and filtered calls are answered as `not_dispatched` observations
     and never reach the application. Their protocol continuation item is still supplied, so the
     protocol stays valid.
   - A distinct mutation request mints one trusted operation key for the run; an identical repeat
     reuses it, so the application replays the stored operation instead of writing twice. A
     corrected request gets a new key.
   - The actual application outcome is fed back, plus a fresh masked observation for the same
     capture and the current draft review row for a Laya request.
   - If the turn's calls would exceed `maxToolCalls`, the run stops before dispatching any of
     them; a turn is never partially applied.
   - The same call repeated beyond `maxRepeatedIdenticalCalls` stops the run as `LOOP_DETECTED`.
4. `clarification_required` from the application suspends the run after the turn's continuation is
   valid. `resume` re-reads scoped observations and starts the resumed turn from a bounded masked
   summary of the prior outcomes plus the user's masked answer, then continues under the same
   turn/tool/context accounting. Because provider conversations are not persisted (S8.10), the
   resumed turn does not replay protocol-private continuation items; the summary is the seam. The
   wall-clock budget restarts at resume because waiting for a person is not agent work. A
   continuation can be resumed once, by its own executor.
5. `resumeTracked` (S8.12) is the durable variant of `resume`: the continuation is validated
   first (ownership, single use, nonblank answer), then the run reopens from `CLARIFYING` only
   (`reopenClarifying`; a `FAILED`/terminal run returns null). The resumed attempt inherits the
   suspended attempt's per-command operation keys verbatim: the same key plus the same request
   replays from the journal, while a materially different request under a reused key surfaces as
   a conflict — so a pre-clarification effect is never duplicated and distinct resumed commands
   never share one journal identity. This fixed the S8.12 controlled-device `resume-error`: the
   prior code reopened the run and then requested a second `PENDING` claim that always failed on
   the already-`RUNNING` row, reporting the resume as unavailable.
6. `user_decision_required`, `caller_scope` and `privacy_boundary_refused` are terminal: the
   agent may not decide a user's decision, widen scope, or retry around the privacy boundary. A
   mistyped scoped token (`invalid_scoped_value`) is repair-only: typed rehydration refuses it and
   nothing is dispatched, so the model may correct it from a fresh observation.

## Completion verification

`Completed` is produced only when the model's final complete turn arrives **and** every recorded
mutation verifies against freshly read state:

| Effect | Verified by |
| --- | --- |
| Event reading | the exact event id exists in the scoped capture with the recorded revision |
| Provisional link | the exact pair is `PROPOSED` with the recorded revision (unless this run later attached it) |
| Attached link | the exact pair is `ACCEPTED` with the recorded link id and revision |
| Laya classification | a non-stale stored snapshot for the draft with the recorded source/category and a prediction time at or after the dispatch, while the draft's review status and revision are unchanged |
| Operation identity | one durable `workflow_operations` row exists for each applied/no-change mutation key |
| No unexpected effect | no financial/confirmation/deletion tool exists; the draft stays `PENDING` |

Every mutation that was attempted but did not reach `applied`/`no_change` prevents a completion, and
the requested intents must each have their verified effect (`INTERPRET_EVENTS` → a stored reading,
`LINK_EVENT_TO_DRAFT` → an accepted link, `PREPARE_LAYA_REVIEW` → a stored Laya snapshot). A model
text that claims an effect the application did not record therefore ends as `Incomplete`, not as
success. An unavailable/corrupt local model is an honest `Incomplete`; it is never replaced by a
hosted guess.

## Privacy and authority

- Model instructions, trusted observations, history lines and tool results all cross the S8.6
  `MaskedHostedGateway`. The executor accepts no raw adapter. The run's initial prompt is
  pre-masked for exact context accounting.
- Inbound assistant text and tool arguments are scanned by the gateway before dispatch; a raw
  literal or foreign/unknown token turns the turn into `INCOMPLETE(masking_blocked)` with no
  calls. The S8.7 adapter still validates every token, span and scope locally before a command.
- Scope, operation identities and tool declarations are host values. Capture notes, draft memos
  and tool-returned prose are explicitly untrusted data in the system instruction and the bundled
  skills; nothing can select a skill, change scope/revisions/keys, or grant authority.
- Run trace and outcomes contain no raw text, credentials or mappings.

## Local Laya, intake and manual work

`request_laya_classification` reaches the unchanged `WorkflowCommandService.requestClassification`
and the real `LayaClassificationEngine`/`LayaInferenceLifecycle`. Prediction stays local, review-only
and unconfirmed. The executor exposes only suspending calls; it does no blocking work on the
caller's thread, and nothing in notification intake or manual entry depends on it, so both keep
working while a hosted turn waits or fails.

Actual local Laya is an Android ONNX Runtime session and was **not** run on this host. The pinned
public `receptron/laya-onnx` bundle is present, and both official desktop ONNX Runtime Java
artifacts (1.21.1 and 1.22.0) were tried, but each fails before any session exists with
`onnxruntime.dll: A dynamic link library (DLL) initialization routine failed` even outside Gradle.
The same weights run under host Python ONNX Runtime, which is the existing reference path, but that
is not the production `LayaRuntime` and is not used as evidence here. The deterministic executor
test therefore proves only the honest unavailable-Laya stop through the real service, and the live
executor slice deliberately requests no suggestion. Actual local-Laya generation through the
executor remains the phone proof recorded in the S8.9 handoff; no substitute classifier is used.

## Deliberate seams for later tasks

- S8.10: durable run history, bounded classified retry, cancel/restart, and uncertain-write
  reconciliation are implemented in `AgentRunHistory` + `startTracked`/`startWithAttemptKey`
  (see `docs/sprint-8-agent-history.md`); the in-memory trace remains the per-turn record
  while the run/attempt/conversation tables are the durable one. An operation key already
  remains safe to replay through the application journal.
- S8.11: stable versioned instruction/tool/skill prefixes (`AgentContextManager`), bounded task
  history and minimal dynamic observations are implemented in the production executor
  (see `docs/sprint-8-agent-history.md` S8.11 section). Cache keys carry provider/model/
  config/tool-schema/skill-schema and privacy-context (vault scope tag) identities — never
  prompt text, raw values, mappings, keys, or credentials. Mutable query results are
  invalidated by scope/revision and re-read fresh; summaries never authorize state. Cache
  hit/miss/expiry, local compaction and provider switch preserve behavior and isolation;
  cache failure falls back safely. Actual per-turn usage (or explicit unsupported status)
  is reported; no estimated saving is ever computed as fact.
- S8.12: `HostedProviderSettingsStore` encrypts provider profiles with AES-GCM and rotating,
  device-local Android Keystore keys; replacing/deleting profiles destroys the previous key.
  Each Chat run captures one immutable config, creates a fresh executor/gateway/vault, and
  records resume attempts separately so provider changes cannot reroute the active conversation.
  Chat exposes selected intents, cancellation, clarification, and verified effects.

## Verification

- `BoundedWorkflowExecutorTest` — real Room/services, scripted provider at the adapter seam:
  read → update → propose → attach completion with stored effects; false success; refused
  mutation; postcondition re-read after an effect disappears; loop/turn/tool/context/time budgets;
  not-dispatched unknown/malformed calls; truncated turn; inbound raw-literal block; injected
  authority; clarification suspend/resume; durable `resumeTracked` reopen (validated, clarifying-only,
  verbatim per-command keys, corrected effect, pre-clarification replay, consumed-continuation
  refusal, failed-run refusal); idempotent repeat; real unavailable Laya.
- `S89ExecutorLayaInstrumentedTest` (`app/src/androidTest/.../agent/`, device-only) — the
  production executor on the phone: real Room/services, the real masked gateway, the five
  typed tools, the production `LayaClassificationEngine`/`LayaInferenceLifecycle` over the
  provisioned pinned bundle, and scripted provider turns at the adapter seam (this test carried no
  credential and opened no socket; current app networking is enabled for the S8.12 hosted path).
  Full slice: synthetic read → interpretation → bounded search → propose → attach → local
  suggestion → verified `Completed` with an `EventReading`, an `ACCEPTED` link, and a
  `Classification` effect; one persisted snapshot; one journal row per mutation; draft
  `PENDING`; zero ledger rows; no raw amount/date/time at the provider seam. Unavailable
  branch: the same executor against an empty model directory ends honest
  `Incomplete(EFFECT_NOT_VERIFIED)` with no snapshot. The executor runs off the main thread
  while a main-looper heartbeat is observed. Launch follows the S8.7 isolated-suffix
  precedent: stage the three pinned files under `/data/local/tmp/s8-public-laya`, build and
  install the isolated `.s89evidence` APKs, run
  `am instrument -e class io.github.triet4p.aegisf.agent.S89ExecutorLayaInstrumentedTest`,
  then remove the suffix. See the exact reconnect command in
  `artifacts/sprint-8/handoffs/S8.9-replan-a2-device-wait.md`.

Proven on the isolated phone package on 2026-10-26 (`S8.9-replan-a3`, device ALT-LX2 /
Android 14, serial AAAJBB4930914141): `OK (2 tests)` for
`S89ExecutorLayaInstrumentedTest` — the six-turn full slice completed through the production
Laya runtime over the provisioned pinned bundle (`MODEL`, candidates `1`,
probabilities `0.2339`, unknownShare `0.7661`, confidence `0.2153`; one snapshot; one
journal row per mutation; draft `PENDING`; zero ledger rows) and the empty-model branch
stayed honest `Incomplete(EFFECT_NOT_VERIFIED)` with no snapshot. Raw logs:
`artifacts/sprint-8/evidence/S8.9-replan-a3-instrumentation.log`,
`artifacts/sprint-8/evidence/S8.9-replan-a3-laya.logcat`, structured
`artifacts/sprint-8/evidence/S8.9-replan-a3-device-result.json`. The provider turns on this
device path are scripted at the adapter seam (no `INTERNET` permission, no credential);
the actual hosted-exchange slice remains the separate a1 live evidence.

## S8.10 durable history (summary)

Full contract in `docs/sprint-8-agent-history.md`. One `AgentRunHistory` per session owns
three separate stores: the user-visible conversation (`agent_conversation`), the
run/attempt journal (`agent_runs` / `agent_run_attempts` with allowed effect refs and
pending/completed/unknown states), and — untouched — the business captures and the
`workflow_operations` effect journal. `startTracked` admits (or replays) the durable run,
claims it for execution, records the attempt outcome, reconciles an uncertain write
before any bounded retry (max 3 attempts), and never claims rollback on cancel. The
safe export is conversation lines plus run states only: no keys, digests, mappings,
credentials, reasoning, or wire detail.

Proven 2026-10-27 (`S8.10-replan-a1`): 6/6 `S810DurableRunHistoryTest` + 2/2
`AgentRunMigrationTest` on the host; a real Go Chat Completions run
(`deepseek-v4.1-flash`, 6 turns / 5 tool calls, reading + `ACCEPTED` link verified,
reconciliation `Applied`, repeated send replayed, zero raw literals outbound);
`OK (2 tests)` on ALT-LX2 / Android 14 through the production Laya runtime
(`NO_FIT`, 6 turns / 5 tool calls, one journal row per mutation, draft `PENDING`,
zero ledger rows, heartbeat 20/20) plus the cancel/late-reply branch.
