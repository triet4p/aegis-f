# Sprint 8.4 agent contract — hosted workflow agent, privacy boundary and harness

**Status:** Draft contract revision `s8-agent-contract-v1` for S8.4 evidence review
(S8.4-cycle-1 round 1). It freezes the agent's application API, provider conversation
events, run states, local/remote data boundary, skill shape, authority ownership,
turn/tool-result flow, postconditions, failure vocabulary and durable state, plus a new
task-level evaluation alongside immutable v2. It is not a model qualification, a shipping
profile decision, or an owner-approved numeric release gate. The executable fixtures and
scorer under `tools/slm/s8_agent_*` are the source of case identities, exact arguments,
masking-boundary checks, and denominators. Frozen v2 (`docs/sprint-8-evaluation-contract.md`,
`tools/slm/s8_workflow_*`) stays byte-identical and is never rescored by this revision.

## 1. Scope and non-goals

This contract covers the hosted workflow agent only: user-configured Gemini API, OpenAI
Chat Completions and OpenAI Responses transports through one shared runtime; the local
pre-egress masking boundary; the bounded application-tool adapter over the existing
`WorkflowCommandService` / `WorkflowQueryService`; versioned bundled skills; the bounded
multi-turn executor with verification; durable run state with safe retry/cancel/restart;
and the task-level evaluation in this revision.

It does **not** deliver production adapters, the executor, masking, or settings
implementations — those are S8.5–S8.12 tasks. Nothing here changes local Laya behavior,
offline manual/intake/ledger guarantees, or the frozen v2 contract.

Inspected application surface (behavioral, not aspirational):

- `WorkflowCommandService` (preparation commands plus user-only financial commands):
  `saveCapture`, `reviseCapture`, `deleteCapture`, `updateEventInterpretation`,
  `proposeLink`, `attachLink`, `rejectLink`, `unlinkLink`, `requestClassification`, and the
  user-only save/correct/delete transaction, draft review/confirm/dismiss, and
  category add/rename/archive/delete plus rule remember/remove paths. Command names are the
  `COMMAND_*` constants (`SAVE_CAPTURE`, `UPDATE_EVENT`, `PROPOSE_LINK`, `ATTACH_LINK`,
  `REJECT_LINK`, `UNLINK_LINK`, `REQUEST_CLASSIFICATION`, …); the claim lease default is
  `DEFAULT_CLAIM_LEASE_MILLIS` (5 minutes); a still-running key answers
  `IN_PROGRESS_REASON`; a lost claim answers `CLAIM_NOT_KEPT_REASON`.
- `WorkflowQueryService` (read-only, every read bounded by `QueryPageRequest` limit
  1–500, offset ≥ 0): `categories`, `categoryCatalog`, `ledgerRecords`,
  `confirmedLedgerAggregate` (closed ranges only), `spendingDrillDown`, `sourceExcerpts`,
  `captureContextSummaries`, `unrecordedNoteEvents`, `draftCandidates`, `reviewQueue`,
  `linkOperations`, `draftContextLinks`, `draftOperations`, `captureOperations`,
  `eventOperations`, `captureEvents`, `classificationOperations`, `workflowOperations`.
- Storage semantics owned by `EventContextRepository` (span/revision/amount/merchant/time
  validation, one reading per span, accepted-link protection), `LinkEvidencePolicy`
  (app-computed `TrustedLinkEvidence`, `UNIQUE` vs `AMBIGUOUS`, marker
  `AEGIS-LINK-EVIDENCE/1`), `WorkflowClaimGuard` + `workflow_operations` rows (claim,
  takeover, replay), `NoteEventEntity` (whole-dong `amountVnd`, `YYYY-MM-DD` /
  `HH:mm` anchors, statuses `INTERPRETED` / `UNRESOLVED` / `NO_MATCH`), `ContextLinkEntity`
  (states `PROPOSED` / `ACCEPTED` / `REJECTED`, one active draft per event),
  `ClassificationSnapshotEntity` (`PredictionSource`, `PredictionStaleReason`), `VndMoney`
  (whole-dong parsing, `checkedAddToTotal`), `ManualDateTime` (`YYYY-MM-DD`, `HH:mm`),
  `SuggestionSafety` (only OTP/security-like text and bank reference codes are blocked),
  `LayaRuntimeContract` (`MAX_LEN` 512, `HEAD_MAX_LEN` 192, `suggest` review-only,
  `PRIMARY_EXCEEDS_ROOM` refusal), and `LayaEffectiveContextComposer`
  (`guaranteedRoomTokens = maxLen − headMaxLen − 4`).
- Evaluation-only instrumented bridge
  (`app/src/androidTest/…/S8WorkflowTrialInstrumentedTest.kt`): isolated in-memory Room,
  trusted `AgentSession("s8.3-isolated-trial", …)` scope, exact dispatch of the five v2
  tools, unknown/partial/malformed calls reported as not dispatched, replay digests, and
  the seeded S8.2 fixture. It is trial evidence, not a product adapter.

## 2. Provider conversation events (native, per protocol)

Three separate protocol adapters share one runtime. Tool names, descriptions, argument
names, bounds and required fields are identical across transports; only the wire encoding
differs. Provider, endpoint, model ID and credential are explicitly configured; no hidden
provider/model substitution and no silent model/protocol fallback.

### 2.1 Gemini API (`generateContent`)

- Endpoint `POST …/v1beta/models/{model}:generateContent`; request carries `contents`,
  `tools[].functionDeclarations`, `toolConfig.functionCallingConfig` (`mode: AUTO`, optional
  `allowedFunctionNames`), and `generationConfig` (sampling, `maxOutputTokens`,
  `thinkingConfig.thinkingLevel`).
- Model output is a `functionCall` part (`candidates[].content.parts[].functionCall`)
  with `name` and structured `args` (plus an optional tracing `id`).
- Tool results return as a `functionResponse` part (`name` matching the call, `response`
  object, echoed `id`) under the `tool` role in the next `contents` turn.
- Continuation appends the model turn (with its `functionCall` parts) and the tool turn
  (with the matching `functionResponse` parts) to `contents`. Every required continuation
  item is retained; a follow-up turn without the pending `functionResponse` is malformed.
- Finish states: `STOP` (complete text), `MAX_TOKENS` (truncated — explicit
  incomplete-budget outcome, never silent success), safety/other finish reasons (provider
  error, never model content). A truncated `functionCall` (unparseable `args`) is never
  executed.

Primary sources: `generateContent` structure and `toolConfig`/`generationConfig` fields
per [generateContent API](https://ai.google.dev/api/generate-content);
`functionCallingConfig` modes per
[function calling](https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/tools/function-calling);
`thinkingLevel` semantics per [thinking levels](https://docs.cloud.google.com/gemini-enterprise-agent-platform/reference/rest/Shared.Types/ThinkingLevel).

### 2.2 OpenAI Chat Completions (`/v1/chat/completions`)

- Request carries `messages`, `tools[]` (`type: function`, JSON-Schema `parameters`) and
  `tool_choice: auto`. Response carries `choices[].message.tool_calls[]` (`id`, `type:
  function`, `function: {name, arguments}` as a JSON string) with `finish_reason:
  tool_calls`.
- Tool results return as `role: tool` messages (`tool_call_id` matching the call,
  stringified `content`). Continuation appends the assistant message carrying
  `tool_calls` **and** one `tool` message per `tool_call_id` to `messages`. Parallel calls
  are all answered before the next turn; a follow-up turn missing any pending `tool`
  message is malformed.
- Finish states: `stop` (complete), `tool_calls` (awaiting results), `length` (truncated —
  explicit incomplete-budget outcome; unparseable `arguments` are never executed),
  `content_filter` (provider-side refusal, never model content).

Primary source: `tools` / `tool_calls` / `finish_reason` semantics per
[chat completions](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create).

### 2.3 OpenAI Responses (`/v1/responses`)

- Request carries `input` items and `tools[]`; model output is typed `function_call`
  items (`id` typically `fc_*`, `call_id`, `name`, `arguments` JSON string, item `status`)
  inside `output`. Tool results return as `function_call_output` items (`call_id`
  matching, stringified `output`) in the next turn's `input`.
- Stateful chaining uses `previous_response_id` with delta-only `input`; stateless
  chaining resends the retained items. Either way every required `function_call_output`
  for a pending `call_id` is retained; a turn that drops one is malformed.
- Run states: `queued` / `in_progress` / `completed` / `failed` (`error` object) /
  `cancelled` / `incomplete` (`incomplete_details.reason`: `max_output_tokens` —
  explicit incomplete-budget outcome, truncated `function_call` items have item status
  `incomplete` and are never executed; `content_filter`; `steered`).

Primary sources: typed items and `function_call` / `function_call_output` shapes per
[function calling](https://developers.openai.com/api/docs/guides/function-calling);
chaining and lifecycle per
[migrate to Responses](https://developers.openai.com/api/docs/guides/migrate-to-responses).

## 3. Workflow run states and turn/tool-result flow

A run moves through: `READY` (configured, scoped, masked input) → `GENERATING` (one
provider turn in flight) → `DISPATCHING` (executing complete validated calls) →
`VERIFYING` (postconditions, §6) → terminal `COMPLETED` (verified), `CLARIFYING`
or INCOMPLETE (budget/truncation, provider error, masking failure, unresolved conflict,
or unverified effect — always labelled, never silent success). A non-terminal provider
turn with pending calls returns to `GENERATING` with the required continuation items (§2);
a terminal answer is reported only after `VERIFYING` passes.

Turn discipline, in order, every turn:

1. Compose the provider turn from versioned instruction/tool/skill prefixes, bounded task
   history, and minimal masked dynamic observations only (§4). Mutable query results are
   re-read by scope/revision, never trusted from a summary or a cached copy.
2. Parse the provider turn: accept only complete calls (schema-valid, fully assembled
   arguments, non-truncated per §2). Partial/unknown/truncated calls are recorded as
   `malformed` and never dispatched; a turn with no complete call and no complete text is
   `INCOMPLETE`, not completion.
3. Validate each complete call against the loaded declarations and trusted state (§5) before
   dispatch: closed tool name, flat schema, reference kind/scope/observed revision,
   idempotency ownership, span bindings, typed value provenance and cross-field rules.
4. Dispatch through `AgentToolRuntime` (backed by `WorkflowCommandService` /
   `WorkflowQueryService`) exactly once per validated call; answer every pending call with
   the shared result envelope and feed the actual structured observations back as the next
   turn's tool results. Never claim an effect the envelope did not confirm. An envelope whose
   `effect` is `unknown` (a pending claim, an unreadable receipt or an unverified effect) is
   host-reconciled, never treated as no-write and never retried under a new operation key.
5. On an explicitly repairable `STALE_OBSERVATION`, `SOURCE_QUOTE_NOT_FOUND`, `CURSOR_STALE`,
   `TEMPORAL_CONSTRAINT`, `MISSING_REQUIRED_MEANING`, `UNKNOWN_REFERENCE`,
   `REFERENCE_KIND_MISMATCH` or `SUBJECT_UNAVAILABLE`, re-read the current authoritative
   masked observation and repair only from its state/constraints, ask the scoped
   clarification question, or stop. Scope, privacy, user-decision, masking, pending/unknown
   operation and over-budget outcomes are terminal; no retry or skill text can bypass them.
6. Verify postconditions (§6) before reporting `COMPLETED`.

## 4. Local/remote boundary and masking contract

Masking is a mandatory **local pre-egress boundary** on every outbound content path:
user input, selected context, conversation history, and tool results. Privacy never
depends on an LLM remembering to call a masking tool. Unknown/ambiguous mappings or a
masking failure block external inference while preserving manual work.

The boundary MUST:

- Detect and minimize sensitive amounts, dates, accounts, identities and bank text;
  keep reversible mappings local and scoped (per run/session), with cross-session
  isolation and collision handling.
- Retain the exact semantics the workflow reasons about: **integer VND** (whole dong,
  `VndMoney` parsing/addition, no rounding or currency conversion), **dates/times**
  (`YYYY-MM-DD` / `HH:mm`, trusted host timezone only when a start time is stated,
  relative-date resolution only from an explicit user/trusted reference with an available
  same-session DATE token; otherwise clarify, and no timezone on a date-only anchor),
  **source spans** (exact offsets into stored text),
  and the relations the workflow needs (equality, ordering, relative-time ordering).
  Tokenization that destroys those relationships is not an acceptable implementation.
- Preserve necessary relations in masked form (equal values mask equally within scope;
  ordering/relative-time relationships stay decidable where the workflow needs them)
  without leaking the underlying values.
- Rehydrate validated values **locally** before invoking typed application commands;
  never accept arbitrary model text as a mapping key or authority. Inbound token
  references are validated against the scoped mapping; unknown tokens are refused, not
  guessed.
- Exclude credentials, mappings, transient reasoning and sensitive continuation data
  from logs/history/export by default. Sensitive continuation data, where a protocol
  requires it, stays protected, task-scoped and retention-bounded.
- Disclose the selected endpoint and residual contextual exposure before real-data
  cloud use. Masking is data minimization, not anonymity and not a no-remote-processing
  claim. No silent inference upload; no bundled developer keys.

The executable masking probe (`tools/slm/s8_agent_masking_probe.py`, §8) pins the
boundary behavior: masked outbound text carries no raw sensitive value; mapping-scoped
equality/ordering/relative-time relations survive; unknown tokens and mapping failures
are refused; rehydrated commands carry exact integer VND, dates/times and spans.

Allowed tools for the agent (closed set): the eleven task-relevant operations declared by
`tools/slm/s8_agent_tools_v3.json` (`aegis-f-s8-agent-tools/v3`, contract
`aegis-agent-interface/3`) — `observe`, `select_source`, `list_drafts`, `make_time_anchor`,
`record_event`, `inspect_link`, `propose_link`, `attach_link`, `classify_draft`,
`replay_operation`, and `ask_user`. Only the subset the host-selected skills require is
loaded for a run; an unloaded tool is neither offered nor executable. Model arguments are
flat primitives and host-issued opaque references, never raw sensitive scalars, revisions,
operation keys, scope or override flags. The frozen `tools/slm/s8_agent_tools.json` v1 and
`s8_agent_tools_v2.json` v2 evaluation schemas remain unchanged historical inputs and are
not loaded by the production Android runtime. There is explicitly **no** financial
confirmation, deletion, arbitrary SQL, shell, arbitrary filesystem access, general plugin
system, or device automation tool. Unknown tools, `execute_sql`, `confirm`/`delete` shapes,
and caller-supplied evidence fields never reach the API.

## 5. Skill shape, trusted ownership, and authority

### 5.1 Skill shape (versioned, bundled)

Each skill is a versioned document with: stable `skill_id` + `skill_version`; `triggers`
(when it loads); `prerequisites` (scope, input, prior state); `tool_order` (allowed
sequence and dependencies); `branches` (normal, clarification, no-match, error,
retry); `error_recovery` (which outcome maps to which branch); and `completion` (the
observable condition that ends it). The runtime loads relevant instructions per turn,
never every workflow on every turn. Stable deterministic transitions belong in code;
judgment and ambiguity stay with the model/user. Bundled skills for this contract:

- `event-reading/2` — grounded occurrence interpretation, atomic recording and clarification;
- `draft-search/1` — bounded candidate reads;
- `link-event-draft/1` — inspect, propose and policy-gated attach;
- `local-review/1` — Laya-to-review preparation (review-only);
- `explicit-operation-replay/1` — exact journal-backed replay of a recorded operation.

### 5.2 Trusted scope, revision and idempotency ownership

- **Scope** comes from trusted runtime/application state: `WorkflowCallerScope.User`
  (user screens) or `WorkflowCallerScope.AgentSession(scopeId, captureIds, draftIds)`
  (the granted captures/drafts of this run). Scope is supplied by host code, never read
  from model arguments, request bodies, notes, memos, or stored category names. Targets
  outside scope are refused (`CALLER_SCOPE`) before anything is read or written, and the
  refusal is identical whether or not the named row exists.
- **References and revisions** come from fresh scoped observations. Every model-facing
  reference is a host-issued opaque alias bound to the observation's scope and
  capture/event/draft/link/review revisions; the model cannot supply or construct one.
  `expectedEventRevision == null` is a transactional assertion that the selected span still
  holds no reading, while a non-null value requires exactly that revision. The agent Laya
  path binds the observed review revision through the draft reference. Stale bindings are
  `STALE_OBSERVATION`, never silent overwrite. A changed span that overlaps an existing
  reading is refused as `STALE_OBSERVATION` rather than stored as a second reading — the event
  write transaction re-checks the overlap atomically, so a competing writer cannot create it
  between observation and insert — and a correction may not null a stored amount, temporal anchor
  or merchant without passing that field's observed alias; source-boundary rewriting and explicit
  field removal are unsupported and become clarification/manual review. Initial link revision is 0.
- **Idempotency keys** are minted by the trusted runtime (non-blank, ≤ 128 chars, no
  control characters); exact tool-plus-argument calls reuse their mapped identity, while
  corrected arguments receive a distinct host identity. Durable attempts retain their
  stable per-tool seed for the first call and derive reproducible identities for later
  distinct calls. The same key + caller + request replays from the durable
  `workflow_operations` row (`Applied` → `NoChange`, one row, equal state digests); the
  same key with a different caller/request is `Conflict`. A started claim is held before
  model work and released only when that work really ends.
- **Link evidence** is computed by the app (`TrustedLinkEvidence`, `UNIQUE` vs
  `AMBIGUOUS`); callers state no evidence. Only the user may attach an ambiguous link;
  an agent attach needs still-current `UNIQUE` evidence bound to the exact
  source/event/draft/revisions. Rejections are durable and user-only
  (`USER_DECISION_REQUIRED`); refused pairs stay refused.
- **Laya** is review-only: remembered rules answer before inference; blank, sparse,
  blocked-safety, or over-budget inputs stay manual; predictions never write ledger
  fields. Confirmation, dismissal, ledger edits/deletion and category changes are
  user-only and have no agent tool.

## 6. Verification postconditions (before COMPLETED)

Every run claiming completion MUST verify, against freshly read application state:

1. Each expected effect holds: stored readings match the dispatched calls (capture,
   span, amount, date, time, normalized text, unresolved decision, status); links are in
   the expected state/revision; queries returned the bounded page asked for.
2. No unexpected effect exists: ledger row counts unchanged unless the task explicitly
   allows a user-confirmed financial write (no agent task in this evaluation does —
   `noLedgerWrites` holds everywhere); no extra operation rows, links, or events.
3. Exact financial/authority correctness: integer VND equal, dates/times/zones equal,
   scopes/revisions/keys match, denials held where required. Exact correctness is
   mandatory and non-negotiable; it is not traded against quality scores.
4. Retry safety: one durable `workflow_operations` row per key; a repeated dispatch
   answers `NoChange` with equal state digests.

## 7. Error vocabulary and durable state

Provider errors (transport failure, 429/rate-limit, 402/account, 5xx, timeout,
`content_filter`/safety finish, `MAX_TOKENS`/`length`/`incomplete`) are distinct from
model failures (parse error, schema-invalid, unknown tool, fabricated arguments),
tool failures (`Conflict`, `NeedsClarification`, `Denied`, `NotFound`, `Failed`,
`Cancelled`), and masking failures (unknown mapping, ambiguous mapping, boundary
error — all block egress). Each is recorded with its own reason code; truncation is
`INCOMPLETE` (budget), never success and never intrinsic-model evidence.

Durable state separates three stores: (a) user-visible conversation (explicit retention,
bounded task history); (b) business captures and effect journal (the ledger rows the
commands wrote); (c) operation journal (`workflow_operations` rows: key, scope, command,
outcome, change; payload digests only, never raw text). Pending/completed/unknown
effect states are recorded with privacy-safe observations. Cancelled work leaves the key
free; interrupted claims expire by lease and may be taken over; uncertain writes are
reconciled before replay. Retention/deletion bounds and backup rules exclude
credentials, mappings and transient reasoning from export by default.

## 8. New task-level evaluation (separately versioned, dev + heldout)

Version `s8-agent-eval/v1`, contract `s8-agent-contract-v1`, scorer
`aegis-f-s8-agent-scoring/v1`. Frozen v2 inputs are untouched; this suite is new files
only. It measures the **agent task loop** (multi-turn with real tool results, masking
boundary, sequencing, clarification, recovery, privacy), not the v2 single-turn
baseline — single-turn baseline and multi-turn agent metrics are never mixed.

- `tools/slm/s8_agent_tools.json` — the five allowed typed tools (schema `aegis-f-s8-agent-tools/v1`).
- `tools/slm/s8_agent_fixture.json` — isolated synthetic snapshot (schema
  `aegis-f-s8-agent-fixture/v1`): captures 111–119 + 211–213, drafts 411/412/421/422/431,
  pre-seeded events 311/312/313; every expected ID/revision is seeded and reachable;
  ASCII spans so Python offsets equal Kotlin String indices.
- `tools/slm/s8_agent_dev.json` — 16 development cases (`a8-*`): multi-event extraction
  (2-event and 3-event with clarification), bounded search (first page, duplicates-only
  page 2), link sequencing (unique attach, ambiguous propose-only, stale-revision
  recovery), Laya (happy path, over-budget refusal), clarification (missing time),
  no-match, adversarial (scope-injection, unknown SQL tool, confirm/delete tool),
  retry (idempotent replay), recovery (conflict then corrected retry), privacy (account
  number masking with exact rehydration).
- `tools/slm/s8_agent_heldout.json` — 8 held-out cases (`a8h-*`): independently phrased
  multi-event, bounded search, ambiguous link, Laya, clarification/no-match,
  adversarial authority, retry/recovery, privacy variants. Separate IDs and denominators.
- `tools/slm/s8_agent_skills.json` — the four bundled skill definitions
  (`s8-agent-skills/v1`).
- `tools/slm/s8_agent_scoring.json` — the frozen scoring contract: exact-by-default
  with declared semantic policies (idempotency key, source-backed text, unresolved
  label, declared version), stored-reading, replay-probe, masking-probe, provenance
  binding, clarification/no-match/refusal adjudication, and the multi-turn/turn-count
  rules.
- `tools/slm/s8_agent_contract.py` — `validate` (schemas, suites, fixture achievability,
  skill shape, masking-probe self-check, input digests), `selftest` (synthetic
  tooling-only probes), `score` (v1 rubric over saved observations with real
  `instrumented_application` traces). No expectation requires an inaccessible
  ID/revision or a model-guessed literal: keys/versions/labels are declared, everything
  else is seeded.
- `tools/slm/s8_agent_masking_probe.py` — executable masking-boundary probe: raw-value
  absence, relation preservation (equality, ordering, relative-time), unknown-token and
  failure refusal, exact rehydration. Run by `validate`; not model evidence.

Grounded in real services: every expected call maps to an existing
`WorkflowCommandService`/`WorkflowQueryService` method with the exact request types
(`UpdateEventRequest`, `ProposeLinkRequest`, `AttachLinkRequest`,
`ClassificationRequest`, `DraftCandidateQuery`, `QueryPageRequest`); validation rules
mirror `EventContextRepository` (span/amount/merchant/time), `VndMoney`
(positive whole dong), `ManualDateTime` grammars, `LinkEvidencePolicy` (unique vs
ambiguous), and the command boundary outcomes; the Laya over-budget case mirrors
`LayaEffectiveContextComposer` refusal semantics (composed against the real 316-token
room with the pinned tokenizer where available, otherwise a stated length bound).

Multi-turn records carry per-turn masked provider turns, tool results, and the
continuation items each protocol requires; the scorer checks turn count bounds,
continuation retention, repair sequencing, and final verified effects. Missing output,
parse failure, dispatch failure, missing stored reading, masking leak, wrong semantics,
and clarification/refusal errors stay counted failures; no post-result dropping.

Provenance binding: every results document declares scoring/contract/tools/fixture/skills
versions plus SHA-256 of the dev suite, held-out suite, tool schema, fixture and skills
it was produced with. A missing/mismatched binding fails every case.

## 9. Budgets, repetition, and accounting (declared before new generation)

Per-model output/reasoning budgets MUST be frozen per model from actual API semantics
before any new generation — adequate for reasoning plus complete multi-call arguments,
never the old shared 2048-token cap, and never silent model/protocol fallback on
truncation. Total run bounds (turns, calls, time, context, user-visible cost policy) are
declared alongside. Every new run records repetition (independent complete passes),
per-case usage where the provider exposes it (input/output/reasoning tokens, cost or
allowance), and operational failures (provider errors, truncations, masking blocks)
separately from completed-run capability. Truncation is an explicit incomplete-budget
outcome. Concrete budget numbers are set by the S8.5/S8.13 briefs against live API
semantics; this contract only requires that they exist, are adequate, and are frozen
before generation.

## 10. Qualification criteria and numerical targets

Consumer-visible qualification (for S8.13/S8.14, not this contract): task success with
exact stored effects, correct abstention (clarification/no-match/refusal with human
adjudication), tool/argument correctness, exact authority/financial correctness, bounded
turns/repairs, masking-boundary integrity (zero raw-value leaks, exact rehydration),
retry/recovery safety (no duplicate effects), and operational reliability accounted
separately. Single-turn v2 baseline and multi-turn agent metrics are reported
separately and never mixed.

Numerical quality targets in `tools/slm/s8_agent_scoring.json` are **clearly proposed
and unapproved** until explicit owner approval; they are not gates and no candidate may
be adopted, rejected, or qualified by them alone. Exact authority/financial correctness
and the masking boundary are mandatory constraints, not tradeable numbers. The
approval blocker is exactly one: **owner approval of numeric quality targets** before
S8.13 qualification. All other contract work in §§1–9 is reachable without it and is
not blocked.
