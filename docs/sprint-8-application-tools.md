# Sprint 8.7–8.13 — bounded workflow tools and skills

S8.7 introduced the Android adapter seam for the closed S8 agent operations; S8.13-pre replaces that
model-facing surface with the approved eleven-tool interface. The runtime still uses only the
existing `WorkflowQueryService` and `WorkflowCommandService`: it adds no persistence, no bypass of
scope, revision, idempotency or link-evidence policy, and no ledger, confirmation or deletion
tool. A host creates one `AgentToolRuntime` with a trusted `AgentToolSession` containing the
`AgentSession`, its S8.6 `MaskedPromptBoundary`, a per-run `AgentReferenceRegistry` and the loaded
declarations. The executor loads only the declarations the selected skills require.

## Closed catalog and selective loading

The one runtime declaration file is `tools/slm/s8_agent_tools_v6.json`
(`aegis-f-s8-agent-tools/v6`). Its contract marker is `aegis-agent-interface/6`. It contains exactly
these eleven tools, each with a flat argument object, `additionalProperties: false`, and every
named field listed in `required`:

| Tool | Arguments | Kind |
| --- | ---: | --- |
| `observe` | 1 | read |
| `select_source` | 3 | pure preparation |
| `list_drafts` | 3 | read |
| `make_time_anchor` | 4 | pure preparation |
| `record_event` | 6 | atomic business mutation |
| `inspect_link` | 2 | read/evidence evaluation |
| `propose_link` | 1 | atomic proposal mutation |
| `attach_link` | 1 | atomic attachment mutation |
| `update_draft_review` | 2 | ordinary draft-review mutation |
| `replay_operation` | 1 | journal-backed replay |
| `ask_user` | 3 | conversation suspension |

A field that permits a missing domain value uses explicit JSON `null`; omitted and null are not two
spellings of the same state. Unknown enums, extra fields, blank nonblank strings and wrong primitive
types are schema errors and are never dispatched. `WorkflowSkillCatalog` selects the host-declared
intents' skills and `WorkflowRunHost.availableDeclarations` narrows the catalog to the union of
their declared tools; the same subset is passed to the runtime, so a tool outside the selected
skills is neither offered to the provider nor executable locally.

Declarations own field meaning, primitives, nullability, enum values, provenance and cross-field
prerequisites. Skills own conditional workflow order and examples. The system instruction owns
authority, evidence and truthful-completion rules. The runtime validator, the application services
and the privacy boundary enforce every invariant; no model text can widen scope or supply a trusted
identity.

## References and observations

Every wire reference is an unguessable host-issued alias (`r_` plus 24 hex characters) for one
validated local observation. Runtime handles are never parsed for meaning, never accepted from a
matching regex alone, and never cross an authorized session boundary. The bounded registry is
scoped to the run, holds at most 512 handles, evicts the oldest, and is never persisted; after a
restart the host re-establishes observations and old aliases are not current. Reference kinds are
`capture`, `source`, neutral `value`, `anchor`, `draft`, `category`, `event`, `pair`,
`proposal`, `operation` and pagination `cursor`; each field states and enforces its expected kind.

A handle binds the observation it came from: scope, capture/event/link/review revisions, exact
source coordinates, the complete masked typed token, or the recorded operation identity. Mutations
compare those bindings at the application boundary and answer `STALE_OBSERVATION` without
overwriting. A value observed on a capture page is not authorized for an occurrence merely because
it shares a session: `record_event` accepts neutral value aliases only from the selected source
span or from a clarification linked to that exact subject; the host parses the value for the
argument's role. Capture-page value aliases are display-only; `make_time_anchor` refuses them,
so a grounded anchor is built from the selected occurrence's value aliases.

`select_source(capture_ref, quote, occurrence)` requires a nonblank exact contiguous copy of the
current masked source, preserves complete masked tokens, verifies the occurrence index and token
boundaries, and translates the masked UTF-16 span through the session vault to original-source
offsets. It returns `source_ref`, the copied quote, any existing `event_ref` at those coordinates,
the occurrence's value handles and safe pre-existing anchors. There is no fuzzy matching, ellipsis
expansion or guessed offset. A span whose source changed is refused as stale. It is also how an
explicitly named counterparty becomes evidence: a second selection on that exact seller-or-place
substring inside the same capture returns a source handle accepted only as `merchant_ref`, so an
explicitly named counterparty is recorded from source instead of retyped, while an occurrence that
names no counterparty, or whose candidate wording is uncertain, stays null. A product, item or
activity description is not a merchant.

`make_time_anchor` builds a grounded temporal selection from observed DATE/TIME handles. A time
requires its date; an interval end date cannot precede the start; an interval end time requires
both a grounded end date and a start time; endpoints are never swapped. A date-only anchor has no
zone, while a timed anchor carries the trusted capture-policy zone.

`list_drafts` returns one bounded host-sized page (10 rows) of the authorized stored drafts, the
filter-bound `next_cursor`, and `scope_exhausted`. `review_status` is a required non-null enum
(`PENDING`, `CONFIRMED`, `DISMISSED`, `ALL`); `ALL` disables only the review-state filter. A cursor
binds the scope, filters and ordered row revisions; reuse with different filters, or after the page
changed, returns `CURSOR_STALE` with a fresh cursor rather than silently skipping or repeating
rows. A possible-duplicate flag is not proof of a duplicate.

## Atomic event recording

`record_event(source_ref, summary, amount_ref, anchor_ref, merchant_ref, clarification)` is one
atomic domain write. The host owns capture/event compare-and-set, source translation, extraction
provenance, command identity, typed rehydration, unresolved status and operation receipts.

- `expectedEventRevision == null` now transactionally asserts that the span still holds **no**
  reading. An event that appeared at the same coordinates is a `Changed` conflict and surfaces as
  `STALE_OBSERVATION`; a non-null expectation requires exactly that revision. A selection whose
  span overlaps an existing reading at *different* boundaries is refused at the mutation boundary
  as `STALE_OBSERVATION` (`selected_span_matches_existing_reading`): a changed quote cannot become
  a second reading of the same occurrence, and rewriting stored source boundaries is unsupported.
  The event write transaction re-checks the same overlap atomically, so a competing writer cannot
  slip a different-boundary reading in between observation and insert; the runtime pre-check only
  supplies the specific repair guidance. Disjoint source-grounded occurrences remain independent,
  and a merchant sub-span handle is still accepted only as `merchant_ref`.
- A correction replaces the selected interpretation, but a null never silently erases a stored
  field. When the selected reading already holds an amount, temporal anchor or merchant, the call
  must pass that field's observed alias to keep or replace it; otherwise the host refuses with no
  effect and names the field (`observed_reading_field_preserved_or_replaced`). Explicit removal of
  a stored value has no wire spelling and becomes a user clarification/manual path. Nulls for
  fields that were already absent remain valid partial readings.
- Missing amount requires `clarification: amount`. Absent optional merchant, date or time alone is
  normal and is not an unresolved event. The host derives the stable `unresolvedReason` template
  from the clarification code; the model writes no arbitrary status prose.
- A grounded partial reading may be stored and is then clarified with `ask_user`. An ungrounded or
  materially ambiguous occurrence is clarified **before** writing, not persisted as an unresolved
  flag.
- Only an exact merchant substring source handle from the selected occurrence, or a clarification
  source linked to it, is accepted as `merchant_ref`.
- The result carries the re-read event observation, the settled effect (`applied` or `unchanged`),
  independent verification, and the authorized `operation_ref`.

Multi-event captures repeat the selection/record path with an independent source handle and
operation identity per occurrence; a failure on the second occurrence never rolls back the first.

## Links, local review and clarification

`inspect_link` uses `WorkflowCommandService.inspectLinkEvidence`, the same trusted stored-row
policy the attachment mutation uses, and returns `pair_ref`, masked evidence and `unique`,
`ambiguous` or `ineligible` with a reason. `propose_link(pair_ref)` stores the observed tentative
pair and can succeed for a pair that is not attachable. `attach_link(proposal_ref)` rechecks the
revision and evidence inside the real mutation and always invokes the non-override agent path; the
model no longer supplies `userOverride`. Ambiguous, ineligible, stale or refused pairs are never
attached, and no tool rewrites amounts/dates to force eligibility.

`update_draft_review(draft_ref, category_ref)` is the ordinary draft-review update restricted to
the category field. `observe(draft)` carries the current active `category_choices`, each an opaque
category reference bound to the draft's review revision and the catalog digest it was observed
against. The tool rechecks the draft revision, the category's live assignability and the catalog
digest, then stores only the category; the amount, moment, memo and type are not rewritten. The
draft stays pending and no ledger record is written. A stale draft, a changed catalog or an
archived/removed category is refused with a fresh observation. There is no local classifier,
translation input, probability or category fabrication anywhere in the path. The result returns
the fresh draft observation and its journaled `operation_ref`, so the exact receipt can be selected
for same-run replay.

A settled category update returns its journaled `operation_ref`, so the exact receipt can be
selected for same-run replay; the call's own result is independently verified against the stored
draft review state. A request another attempt still owns is `OPERATION_PENDING`, and an
unverifiable settled result is `EFFECT_NOT_VERIFIED`, never a fabricated category.

`ask_user(subject_ref, field, question)` validates that the subject supports the requested fact,
masks the question, and returns `outcome: clarifying`, `control: await_user`. It changes no business
state; later calls in the same turn are not dispatched. The user answer re-enters the local masking
boundary, becomes scoped clarification evidence (a typed handle when the answer carries exactly one
token of the requested kind, or a linked merchant source), and the resumed run re-reads fresh
observations before the model may correct the partial reading.

## Journal-backed replay

`replay_operation(operation_ref)` calls the narrow domain entry
`WorkflowCommandService.replayOperation(scope, key, command, digest)`. The receipt must belong to
the caller scope, name the same command family and carry the same recorded request digest; a
pending operation is reported as pending, never as success. A settled receipt returns the recorded
change and the actual domain `no_change` with no new row and no reconstructed payload. The result
reports `replayed: true`, `effect: unchanged`, `journal_outcome: no_change`, and an independent
`current_effect_matches` re-read of the historical effect. A later correction or deletion leaves
the receipt historical: it is reported as not currently matching and is never recreated. The new
replay wire interface intentionally changes the old literal-payload replay contract; frozen
fixtures and their scores are preserved and are not regraded.

## Uniform results and errors

Every result, including a schema-invalid call that was never dispatched, uses the envelope
`{contract, tool, outcome, dispatch, effect, verified, data, operation_ref, observations, error,
control}`.

- `outcome` is `ok`, `error` or `clarifying`; business missingness lives in `data`.
- `dispatch` is `not_dispatched`, `dispatched` or `unknown`; `effect` is `none`, `applied`,
  `unchanged`, `partial` or `unknown` and describes this requested action only. A failure that may
  follow an unreadable or unsettled write (`EFFECT_UNKNOWN`, `EFFECT_NOT_VERIFIED`,
  `OPERATION_PENDING`) is always reported as `effect: unknown` with terminal reconciliation, never
  as `none`; the model cannot read it as a no-write guarantee and no retry minted a new key.
- `verified` reports whether the returned effect claims were re-read from live state; it never
  asserts that the user's whole request is done.
- Errors carry a stable `code`, `category`, field `issues` with `path`/`constraint`/safe `expected`,
  `retryable`, `next_action`, optional `subject_ref` and the narrowed `allowed_tools`.
- `control.action` is `continue`, `await_user`, `reconcile` or `stop`, and is host enforced.

Stable codes cover schema, unknown/foreign/mismatched references, scope refusal, stale state,
exact-quote failure, stale cursors, temporal constraints, missing required meaning, ineligible
links, user-only decisions, local-model unavailability/overbudget, pending or unknown operations,
unverified effects, masking refusal, provider transient failure and provider truncation. A result
that cannot be safely masked after a successful write is never sent to the external model: the host
records/reconciles locally, stops hosted work and shows a sanitized local status.

Repairable errors may be corrected only from fresh authoritative masked observations within the run
budgets. `MASKING_REFUSED`, `USER_DECISION_REQUIRED`, `REFERENCE_SCOPE_REFUSED` and every other
terminal code stop hosted dispatch; `MASKING_REFUSED` after a write triggers local reconciliation of
the journal instead of a retry. Error text never echoes a submitted value, a token, or a
candidate answer.

## Assets, identity and affected consumers

`tools/slm/s8_agent_tools_v5.json` and `tools/slm/s8_agent_runtime_skills_v5.json` are the current
runtime inputs copied into the Android assets by `app/build.gradle.kts`; historical S8.13
qualification fixtures retain their pinned v4 assets. `AgentToolDeclarations` and
`WorkflowSkillCatalog` validate schema markers, closed tool/skill IDs, versions and required
content at load time. The five current skills are `event-reading/7`, `draft-search/1`,
`link-event-draft/3`, `local-review/2` and `explicit-operation-replay/1`; each names its tools,
prerequisites, ordered steps, branches, recovery guidance, synthetic examples and completion
condition.

The stable cache identity (`AgentContextManager.cacheKey`) includes the protocol, endpoint host,
model, generation budget, interface contract, the canonical selected tool-schema digest, the
selected skill catalog, and the privacy-scope tag. Changing a schema, skill, error vocabulary or
contract version changes the identity; reordering dynamic observations does not. The same
declarations are lowered and verified for Gemini GenerateContent, OpenAI Chat Completions and
OpenAI Responses; lowering preserves types, nullability, enums, descriptions and constraints (with
the provider's documented omission of `additionalProperties`, still enforced locally).

Affected consumers are the Android generated assets, the declaration and skill loaders, the tool
runtime, the executor, `AgentContextManager`, `MaskedHostedGateway`, the three provider adapters,
`QuickCaptureScreen` through the executor, the S8.10 durable history and every migrated test and
instrumented harness. The frozen S8.4 evaluation assets (`s8_agent_tools.json`,
`s8_agent_tools_v2.json`, the dev/held-out fixtures and scoring) remain byte-identical historical
inputs; they are not the runtime declaration and are not regraded. Old live harnesses that encoded
the retired five-tool surface were removed, while their consumer-visible behavior is covered by the
rewritten executor/runtime unit suites and the migrated device harnesses.
