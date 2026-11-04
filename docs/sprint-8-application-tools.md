# Sprint 8.7–8.13 — bounded workflow tools and skills

S8.7 introduced the Android adapter seam for the closed S8 agent operations; S8.13-pre replaces that
model-facing surface with the approved eleven-tool interface. The runtime still uses only the
existing `WorkflowQueryService` and `WorkflowCommandService`: it adds no persistence, no bypass of
scope, revision, idempotency or link-evidence policy, and no ledger, confirmation or deletion
tool. A host creates one `AgentToolRuntime` with a trusted `AgentToolSession` containing the
`AgentSession`, its S8.6 `MaskedPromptBoundary`, a per-run `AgentReferenceRegistry` and the loaded
declarations. The executor loads only the declarations the selected skills require.

## Closed catalog and selective loading

The one active runtime declaration file is `tools/slm/s8_agent_tools_v8.json`
(`aegis-f-s8-agent-tools/v8`), contract `aegis-agent-interface/8`. It contains eleven closed
tools with flat argument objects and `additionalProperties: false`. Every required field is
declared in `required`; `list_drafts.event_ref` is its one optional event-bound query field.

| Tool | Arguments | Kind |
| --- | ---: | --- |
| `observe` | 1 | read |
| `select_source` | 3 | pure preparation |
| `list_drafts` | 3 required (+ optional `event_ref`) | read |
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
`capture`, `source`, neutral `value`, `relative_date`, `anchor`, `draft`, `category`, `event`,
`pair`, `proposal`, `operation` and pagination `cursor`; each field states and enforces its expected
kind. A `relative_date` handle is not a value: it names one day word the host found inside the
selected occurrence, carries only that word's bounded day offset, and is accepted only as a
temporal component, which the host resolves from the capture's own recorded local date and zone.

A handle binds the observation it came from: scope, capture/event/link/review revisions, exact
source coordinates, the complete masked typed token, or the recorded operation identity. Mutations
compare those bindings at the application boundary and answer `STALE_OBSERVATION` without
overwriting. A value observed on a capture page is not authorized for an occurrence merely because
it shares a session: `record_event` accepts neutral value aliases only from the selected source
span or from a clarification linked to that exact subject; the host parses the value for the
argument's role. Capture-page value aliases are display-only; `make_time_anchor` refuses them,
so a grounded anchor is built from the selected occurrence's value aliases.

Draft observations include only current accepted context from captures in the session's grant.
When event recording reconciles an accepted link or a link is accepted in the active run, the
affected in-scope draft is observed again with its own masked context and active choices. A category
alias belongs to that exact draft observation. The category command compares draft, linked
event/source/link revisions and the live catalog inside its write transaction; a changed context
or catalog requires a fresh observation. A draft with more than 500 context links cannot use an
incomplete context snapshot for hosted category writes. These review-only writes never confirm a
transaction or settle an unresolved link.

Visible review rows update from their own current queue entries. A bounded page-membership
observation also detects when an off-page draft becomes urgent. A group or membership change
re-reads only the current page to keep conflict-first order and page boundaries correct; the
selected order and open draft are not changed.

`select_source(capture_ref, quote, occurrence)` requires a nonblank exact contiguous copy of the
current masked source, preserves complete masked tokens, verifies the occurrence index and token
boundaries, and translates the masked UTF-16 span through the session vault to original-source
offsets. It returns `source_ref`, the copied quote, any existing `event_ref` at those coordinates,
the occurrence's value handles, the day words inside that selection and safe pre-existing anchors.
There is no fuzzy matching, ellipsis expansion or guessed offset. A span whose source changed is
refused as stale. It is also how an
explicitly named counterparty becomes evidence: a second selection on that exact seller-or-place
substring inside the same capture returns a source handle accepted only as `merchant_ref`, so an
explicitly named counterparty is recorded from source instead of retyped, while an occurrence that
names no counterparty, or whose candidate wording is uncertain, stays null. A product, item or
activity description is not a merchant.

`make_time_anchor` builds a grounded temporal selection from observed DATE/TIME handles. A time
requires its date; an interval end date cannot precede the start; an interval end time requires
both a grounded end date and a start time; endpoints are never swapped. A date-only anchor has no
zone, while a timed anchor carries the trusted capture-policy zone.

`date_ref` also accepts a `relative_date` alias from `select_source`. The selection's own
`relative_dates` list carries one alias per day word it found, with that word's bounded offset:
`today`, `yesterday`, `the day before yesterday`, `tomorrow`, `the day after tomorrow` and the
Vietnamese spellings of those days. The host resolves the calendar date from the capture's own
recorded instant and zone — never from the current clock, never from another zone — so the model
neither computes the date nor asks for it again. A period word (`this morning`, `last night`,
`this week`, a bare `mai`/`mốt`/`qua`) names a range and stays out of the vocabulary, which leaves
it a date clarification instead of a fabricated day. A capture that records no usable instant or
zone resolves nothing and asks for the absolute date.

`list_drafts` returns one bounded host-sized page (10 rows), a filter-bound `next_cursor`, and
`scope_exhausted`. `review_status` remains a required non-null enum (`PENDING`, `CONFIRMED`,
`DISMISSED`, `ALL`); `ALL` disables only the review-state filter. Optional `event_ref` binds the
read to one current event alias and requires `PENDING` plus `duplicates_only=false`. Event-bound
retrieval uses the event's stated day or interval, available amount and normalized merchant
evidence, and applies explicit pending-draft corrections before comparison. Known amount, time or
merchant conflicts exclude a row; missing fields remain missing evidence, not a conflict or proof.
The bank's parsed transaction date/time is distinct from notification receipt time
(`postedAtEpochMillis`), which is not read as purchase time. Ranking is exact amount, event-time
compatibility, exact merchant, point-time distance as ordering only, then creation time and id;
category choice and model certainty are never inputs. Account and event transaction type are
`UNKNOWN` because the event does not store them; neither is inferred from bank name or direction.
The result reports `MISSING`, `UNIQUE`, `AMBIGUOUS`, `CONFLICTING` or `INCOMPLETE`, plus per-row
evidence and rationale. It distinguishes `candidate_set_complete_within_granted_scope` (every
authorized id was evaluated), `candidate_page_complete` (whether the item page needs `next_cursor`),
and `pending_database_complete_at_recheck` (the initial pending page was complete and a bounded
current pending-id recheck found every pending id inside the grant). The app currently grants at
most its first 32 pending draft ids to a hosted session. Fresh pending ids outside that grant or
another bounded current page make `pending_database_complete_at_recheck` false, even if zero or
one compatible granted rows are visible. Those rows remain useful, but the outcome is
`INCOMPLETE`; `UNIQUE` or `MISSING` cannot be claimed database-wide. `scope_exhausted` is false
when candidate pages remain or the pending database recheck is incomplete. Cursors page candidate
results within the grant and never widen it. A possible-duplicate flag is not proof of a duplicate.

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
link revision and trusted pair evidence inside the real mutation and always invokes the
non-override agent path; the current bounded search must also confirm that the complete pending
scope is still within the grant. Candidate retrieval ranking and merchant similarity do not
replace the local attachment-evidence policy. An incomplete search never authorizes automatic
attachment. Ambiguous proposals remain unaccepted for the user to resolve; the user can reject
one candidate, choose another, or unlink an accepted association using its current revision. No
tool rewrites amounts/dates to force eligibility.

`ArrivalReconciliationCoordinator` is a separate local system path, not a hosted-session grant. It reads at most one shared pending-draft page (currently 500 rows) for each note/draft arrival, then rechecks that every pending draft fits that bounded page before it can attach. A larger inbox remains `INCOMPLETE`. The hosted `AgentSession` still receives only the first 32 ids, and reconciliation never widens that grant. Durable operations store expected revisions and paged cursors so process restart resumes the same bounded work; ambiguous, rejected, dismissed, resolved, or stale items are not auto-attached.


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

`tools/slm/s8_agent_tools_v8.json` and `tools/slm/s8_agent_runtime_skills_v8.json` are the current
runtime inputs copied into Android assets by `app/build.gradle.kts`; older versioned assets remain
historical. `AgentToolDeclarations` and `WorkflowSkillCatalog` validate schema markers, closed
tool/skill IDs, versions and required content at load time. The five current skills are
`event-reading/8`, `draft-search/2`, `link-event-draft/4`, `draft-review/1` and
`explicit-operation-replay/1`; each names its tools, prerequisites, ordered steps, branches,
recovery guidance, synthetic examples and completion condition.

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
