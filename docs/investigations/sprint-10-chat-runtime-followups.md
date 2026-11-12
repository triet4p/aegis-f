# Sprint 10 Chat runtime follow-ups: retry dead end and ungrounded date questions

> **Privacy redaction — 2026-11-12:** This Git copy replaces verbatim owner conversation phrases and the note's spelled date with semantic descriptions; the future acceptance example is explicitly synthetic. Observed sequence, counts, code references, hypotheses, and repair criteria are retained. The byte-identical original is preserved at `artifacts/sprint-10/private-originals/S10-CLOSE-a1/docs/investigations/sprint-10-chat-runtime-followups.md` (original SHA-256 `8edadc3959063ce7a0addbf605d111919a1fc3975d2ace13b98ee0e96dfcd046`).

**Scope:** investigation only for the owner-reported Chat issues: the failure messages
*The reply couldn't be completed...* / *This attempt can't be retried automatically...* and the
clarification asking for an exact year/time before the note date was resolved. No source, test,
plan, or changelog change is made by this document. The UI progress/streaming correction is a
separate task and is not investigated here.

**Sources inspected:** the owner report and the two existing screenshots (personally opened); the
current `main` source paths cited below; and a read-only copy of the real main app database taken
while the app was idle. Only run/attempt state, trace event kinds, tool names, and non-content
metadata were queried. No chat was resent, no provider request was made, no app data or setting
was changed, and no raw database copy or screenshot crop is retained.

**Status:** findings are separated into observed facts, code-supported causes, and hypotheses.
The exact durable run facts and query commands are in
`artifacts/sprint-10/investigations/S10.11-DIAG-a1.md`.

Line references are from the working tree at investigation time. The separate S10.11 UI task is
editing several of these files concurrently, so symbol names and quoted code are authoritative;
allow for line drift in later revisions.

## 1. Observable sequence

The owner's screenshots show two identical short year-only answers; the durable run shows only
one admitted run, so both visible bubbles belong to the same run's live transcript. The second
bubble is produced by the Retry action (see §3.1–§3.2), not by a second send.

| Device time | Durable fact | Trace/row evidence |
| --- | --- | --- |
| 09:53:48 | Run admitted (attempt 1); one durable user line (the original request with the attached note). | `agent_runs`, `agent_conversation` |
| 09:53:49–09:56:02 | Attempt 1: 7 provider turns, 7 complete stream ends, 7 tool calls; an event was recorded as `UNRESOLVED`; three candidate-query attempts (two errors, then `ambiguous`, 4 candidates); `ask_user` field `time` on run turn 7. Attempt outcome `clarify`. | `agent_trace_events` |
| 10:02:22 | Attempt 2 started from a natural-language time-range answer; 5 provider turns counted as run turns 8–12; `make_time_anchor` refused with `REFERENCE_KIND_MISMATCH`; `record_event` stored `UNRESOLVED` with date and time null; `ask_user` field `date` on run turn 12. Attempt outcome `clarify`. | `agent_trace_events` |
| 10:07:27 | Attempt 3 started from a natural year-only answer; no provider request was made. | `agent_trace_events` |
| 10:07:28 | Attempt 3 ended `TURN_BUDGET`; run state `FAILED`, stop reason `TURN_BUDGET`, attempt 3. | `agent_run_attempts`, `agent_runs` |
| 10:07 | UI showed *The reply couldn't be completed. Your words and any received text are kept.*; then Retry; then a duplicate year-only answer bubble and *This attempt can't be retried automatically. Start a new message with the text above.* | screenshots, code path |

There is no `PROVIDER_FAILURE` trace row in this run and every streamed exchange ended
`COMPLETE`. The terminal stop is the local `TURN_BUDGET`; the only tool-level refusals were
`UNKNOWN_REFERENCE` and `CURSOR_STALE` during candidate retrieval and
`REFERENCE_KIND_MISMATCH` during anchor assembly. A timeout, transport loss, or model-side
failure is therefore not supported by the retained evidence and is not claimed.

## 2. Failure message 1: *The reply couldn't be completed*

### 2.1 Observed

The generic sentence is the Chat screen's fallback for any incomplete outcome that is not a
provider failure: `incompleteMessage` returns `strings.errorGeneric` for
`TURN_BUDGET` (`app/src/main/java/io/github/triet4p/aegisf/ui/chat/AiChatScreen.kt:859-872`).
The durable reason for the owner's run is `TURN_BUDGET`, so the visible sentence is truthful only
in the broad sense that the reply was not completed; it hides the actual stop.

### 2.2 Root cause: a clarification raised on the final allowed turn cannot be resumed

`resume()` reuses the suspended run object and resets the wall-clock start, but it does **not**
start a new turn phase:

- `BoundedWorkflowExecutor.kt:794-899` (`resume`) sets `run.startedAtMillis` and calls
  `drive(run)` without calling `run.beginPhase(...)`.
- `followUp()` does call `run.beginPhase(...)` (`BoundedWorkflowExecutor.kt:974`), and
  `beginPhase` resets `turns` to zero while accumulating the previous phase
  (`BoundedWorkflowExecutor.kt:2229-2237`).
- `drive()` checks `run.turns >= limits.maxTurns` before any provider request
  (`BoundedWorkflowExecutor.kt:984`); the Chat screen uses the default
  `maxTurns = 12` (`BoundedWorkflowExecutor.kt:116`).

Attempt 1 ended on run turn 7 and attempt 2 added five provider turns, ending on run turn 12
with the date clarification. The user's answer therefore started a resume whose first budget
check failed immediately. The screenshot's immediate error after the answer, the zero provider
requests in attempt 3, and the `TURN_BUDGET` row at one second all match this path.

This is an invariant defect, not a model or network fault: the host asked a question and then
could not accept the answer because the question was raised on the last turn it had.

### 2.3 The user is not offered the clarification path after the failure

`continueClarification` calls `agentRunState.beginTurn(answer)` and, on a failed resume, calls
`failTurn` (`AiChatScreen.kt:542-600`). `beginTurn` clears `outcome` and `failTurn` does not set
it, while the clarification card is rendered only when `outcome is WorkflowRunOutcome.Clarifying`
(`AiChatScreen.kt:754-760`). The retained continuation therefore becomes unreachable in the UI:
the only visible action is Retry, which follows a different path.

## 3. Failure message 2: *This attempt can't be retried automatically*

### 3.1 Observed path

`retryUnavailable` is produced only by `retryLast` when `reopenForRetry` returns null
(`AiChatScreen.kt:485-510`). The observed two-bubble transcript can only be produced by that
path, because `retryLast` calls `beginTurn(lastUserText)` and `beginTurn` appends a new user
bubble (`ChatAgentRunState.kt:265-293`).

The run was `FAILED` with attempt 3. `reopenFrom` refuses when `next > MAX_ATTEMPTS`, and
`MAX_ATTEMPTS = 3` (`AgentRunHistory.kt:406-430`, `data/CanonicalAgentRetryPolicy.kt:18`).
With attempt 3 stored, `next` is 4, so reopening is refused. This is the direct cause of the
second message.

### 3.2 The retry gate decides after adding the user bubble

`retryLast` performs `beginTurn(promptText)` before reconciliation and before
`reopenForRetry` (`AiChatScreen.kt:495-507`). A refused retry therefore leaves a duplicate user
message in the transcript for an attempt that was never started. The screenshot shows exactly
that duplicate. The Retry button is rendered for every `ERROR` assistant entry whenever an
executor and a request exist, with no check of the retry policy, the attempt cap, or the terminal
reason (`AiChatScreen.kt:780-793`); after `retryUnavailable` the button is shown again, so the
same dead end can be repeated.

### 3.3 Reconciliation is looked up under the wrong key

`reconcileUncertainWrite` reads the operation journal under the attempt's `idempotencyKey`
(`AgentRunHistory.kt:286-315`), which is `run-<id>:attempt-<n>`
(`CanonicalAgentRetryPolicy.kt:103`). Mutations are journaled under per-tool keys derived from
that attempt key: `...:record`, `...:propose`, `...:attach`, `...:draft-review`
(`BoundedWorkflowExecutor.kt:654-667`). The read-only database confirms a `...:attempt-1:record`
journal row and no row under the bare attempt key.

For this run the UI passed the stale first attempt (`lastAttempt` is set only at the initial
admission and after a successful reopen, `AiChatScreen.kt:391,510`; a clarification resume does
not update it). The attempt row's effect kind had also been overwritten with the run outcome
(`clarification`/`unknown`), which reconciles to "cannot re-read"
(`AgentRunHistory.kt:518-519`). The observed `retryUnavailable` message proves the verdict was
`NotApplied`, i.e. the journal lookup found nothing. Even when the journal row exists, this
lookup cannot see it; a retry after a possibly-applied write is therefore less protected than
the code comments claim.

### 3.4 Retrying a clarification failure would replay the wrong request

If the cap had allowed it, `retryLast` would call `startWithAttemptKey(request = lastRequest)`
(`AiChatScreen.kt:505-515`) with the original request, not with the clarification answer. The
last attempt number is also stale. The `ClarificationCard`/`continueClarification` path is the
only correct continuation, but it is hidden after the failure (§2.3).

### 3.5 Tested behaviour versus the owner's path

An existing scripted test expects `Retry` and then the retry-limit message for a provider
transport failure (`app/src/androidTest/.../S105ChatInstrumentedTest.kt:263-297`). The dead end
after three exhausted attempts is therefore intentionally covered for a provider failure. It is
not covered for a terminal `TURN_BUDGET` raised by a clarification resume, which is the owner's
path; the test also does not assert that the Retry affordance disappears or that no duplicate
user bubble is added.

## 4. Unnecessary date/year question

### 4.1 Observed

The second clarification asked for the purchase's full calendar date because the source note
contained a spelled day/month phrase without an explicit year. The owner supplied a natural
year-only answer. The session then failed on the turn budget before that answer could be used.

Before that question, the run had already queried pending drafts: the successful `list_drafts`
call returned `ambiguous` with 4 candidates and 8 conflicting rows, all inside the granted
pending scope. So the literal claim "without querying candidates" is not true for the first
(time) question. It is true for the second (date) question: attempt 2 did not re-query
candidates before asking for the date. The candidate rows that were returned carried several
`MISSING` evidence dimensions, and no pending draft carried a transaction date on the note's
stated day, so a grounded date would have produced a much smaller, likely empty, candidate set.

### 4.2 The model has no trusted current date, and the note's save date is masked

The stable system instruction and the dynamic observations contain no current local date or
time (`systemInstruction`/`initialUserInstruction`, `BoundedWorkflowExecutor.kt:1809-1900`;
`AgentContextManager.kt:98-130`). The capture
observation does carry `captured_at_epoch_millis` and `capture_timezone`
(`AgentToolRuntime.kt:1749-1800`), but the numeric epoch is masked on egress by the privacy
boundary, as the source comment states. A structural check of the actual first provider request
in the retained trace confirms:

- `captured_at_epoch_millis` arrived as a masked token, not a comparable number;
- the prompt contained no four-digit year and no ISO or slash date token;
- the prompt contained no current-date string.

The note's spelled day/month phrase remained plain text. The model therefore had neither "today"
nor a usable note timestamp and could not establish whether the occurrence fell in the capture
year.

### 4.3 The date text cannot become an anchor value

The host already has the intended year rule: a day/month value that states no year resolves
against the capture's trusted calendar year (`captureReferenceYear`,
`AgentToolRuntime.kt:2656-2662`; used by `parseDateValue`, `AgentToolRuntime.kt:583-590`; vault
resolution `SensitiveVault.kt:190-191,393-400`). That path is reachable only for a masked value
token.

A plain spelled date is never a token and never receives an alias:

- `select_source` issues value aliases only for masked tokens
  (`valueRefsIn`, `AgentToolRuntime.kt:2352-2387`), and relative-day aliases only for the
  closed vocabulary of relative day words (`RelativeDayExpressions.kt:43-54`). In the observed
  run the date selection returned an empty `relative_dates` list and no date value alias.
- `make_time_anchor` accepts only a `VALUE` or `RELATIVE_DATE` reference for `date_ref`
  (`AgentToolRuntime.kt:476-480`). The model passed a source reference and received
  `REFERENCE_KIND_MISMATCH`; the time reference it passed was the clarification value alias.
- The vault's date patterns cover ISO, slash, and yearless numeric dates
  (`SensitiveVault.kt:455-460`), not spelled Vietnamese month phrases.

So the host could resolve a *tokenized* yearless date from the capture year, but the actual note
wording does not produce such a token. The question is a consequence of that gap, not evidence
that the app has no chronological information at all.

### 4.4 The user's natural answer cannot ground the anchor

`registerClarification` issues a value reference for a date/time answer only when the masked
answer contains exactly one vault token (`AgentToolRuntime.kt:1466-1479`). A natural year-only
answer contains no token, so it yields `value_ref: null`; the observation only carries the
answer text. A year-only answer would not by itself satisfy `parseDate` either. The contract
therefore asks for a full date but can consume only a format the user is never told about. The
observed time answer did produce one numeric value reference (the masked clock value), but the
missing date reference still blocked the anchor.

### 4.5 No note-search or range capability

The model-facing tool catalog contains `observe`, `select_source`, `list_drafts`,
`make_time_anchor`, `record_event`, `inspect_link`, `propose_link`, `attach_link`,
`update_draft_review`, `replay_operation`, and `ask_user`
(`AgentInterfaceContract.kt:50-63`; `tools/slm/s8_agent_tools_v8.json`). There is no tool to
list or search saved notes by date or month. Initial observations are bounded to the newest four
captures (`WorkflowRunLimits.maxCaptureObservations`, `BoundedWorkflowExecutor.kt:121,1871-1886`),
and `list_drafts` is event-bound. "Search a reasonable date range, inspect candidates, then ask
a targeted question" is therefore not implementable from the current surface when the note date
cannot be grounded.

### 4.6 Policy distinction

Asking for an absolute date is consistent with the current rules for a period or an ungrounded
temporal component, and the stable block explicitly tells the model never to compute or ask for
the calendar date of a *relative day word*
(`AgentContextManager.kt:114-117`). The defect is that an absolute spelled date with a missing
year is treated as ungrounded even though the host already has the capture year and a resolution
rule for that case. The system currently cannot tell the user why the year is missing or accept
the only natural answer.

## 5. Adjacent defects observed in the same path

- A clarification resume is journaled and displayed as "The run was retried (attempt N)."
  (`AgentRunHistory.kt:445`), although it is a continuation, not a retry.
- The live failure text is generic while the durable line records the exact reason
  (`The attempt ended: turn_budget.`). The two surfaces disagree.
- Clarification questions and answers are not durable conversation lines; the run's
  `agent_conversation` holds the original user request and status rows only. A restored
  transcript would omit the question and both answers. This is code- and row-verified, but not
  exercised by a process death in this investigation.
- `lastAttempt` and `lastRequest` keep the initial admission across clarification resumes, so
  retry continues to target the first attempt and the original request (§3.3, §3.4).

## 6. Supported, hypothesized, unknown

**Supported by code and retained runtime data**

- The first error is `TURN_BUDGET` from a clarification resume with zero provider requests; it is
  not a timeout or provider failure.
- The clarification was raised on run turn 12 of 12; `resume` does not reset the phase while
  `followUp` does.
- The second error is the retry gate refusing `attempt 4 > MAX_ATTEMPTS = 3`; the duplicate user
  bubble is added before that refusal.
- The prompt has no current local date and no usable capture timestamp; the spelled date gets no
  alias; the year-only answer can produce no date reference.
- Candidate retrieval ran in attempt 1 and returned an ambiguous set; attempt 2 asked for the
  date without re-querying.

**Hypotheses (consistent with the evidence, not directly observed)**

- The model asked for the date because it could not construct an anchor for the plain date; the
  failed `make_time_anchor`, the `UNRESOLVED` event, and the immediate date question support this,
  but the model's intent is not observable and its reasoning was not read.
- If the selected span had produced a yearless date alias, the capture year rule would have
  resolved the date without asking and a candidate query would have had a grounded range. This
  is code-supported but was not exercised at runtime.

**Unknown or missing evidence**

- Why the model chose a source reference as `date_ref`; only the resulting kind mismatch is
  retained.
- Whether the user actually tapped Retry; the UI action is not traced. The only code path that
  can produce the observed second bubble and message is `retryLast`.
- Whether a candidate-first question would have resolved the ambiguity; the returned candidates
  had missing amount/date/time evidence.
- No crash log or uncaught exception exists; none is needed, because the run settled normally
  with a terminal stop reason.

## 7. Post-Sprint 10 bounded repairs and acceptance checks

These are proposed follow-on tasks only; nothing here is implemented in Sprint 10.

**T1 — Clarification resume must have a usable turn budget.** Treat a resume as a new phase
(call `beginPhase` as `followUp` already does, or reserve a turn when a clarification is
raised). Acceptance: a clarification raised on a non-final turn resumes and issues at least one
provider request; a run that cannot afford a resume does not offer an answerable clarification;
a regression test covers a clarification asked on turn `maxTurns` and its resume outcome; the
cumulative turn count is still reported.

**T2 — Ground an absolute spelled date from the selected occurrence.** Extend the date
recognition used for alias issuance (or offer a host-issued date component for the selected span)
so a spelled day/month phrase resolves with the capture's recorded year and zone. Acceptance:
selecting that occurrence returns a date reference; `make_time_anchor` resolves it to the
capture's year without asking; existing ISO/slash/yearless-token behavior is unchanged; no raw
value is exposed to the model.

**T3 — Consume natural clarification answers or reject them without consuming the attempt.**
A date/time answer should be structured (year, day/month, range) before the resume, and a
year-only or relative-year answer should either ground a candidate or be rejected with a
specific, answerable question. Acceptance: a synthetic year-only answer after a yearless
date produces a grounded candidate or a clear answerable refusal; the attempt is not silently
converted to `UNRESOLVED`; no duplicate clarification is asked for the same component.

**T4 — Provide trusted local temporal context.** Pass the run's local date and timezone (and a
usable capture chronology) as a bounded observation, not as an opaque epoch. Acceptance: the
prompt can state the current local date; the interpretation uses it to select a reasonable
range; no personal value is unmasked.

**T5 — Candidate-first verification.** When a candidate query returns an ambiguous set, ask
about those candidates (by their application labels and evidence) before asking for an exact
year/time; when a grounded date range yields no compatible candidate, report no match and the
searched range. Acceptance: ambiguous set → question names the candidates; empty set → no-match
answer, no fabricated guess; no financial write is created by the investigation path.

**T6 — Retry gate and UI truthfulness.** Decide retryability before adding any transcript entry;
disable or remove Retry when the run has no attempt left or the stop reason is terminal;
reconcile the current attempt and the per-tool journal keys; replay a clarification continuation
rather than the original request. Acceptance: a terminal last attempt shows no Retry; pressing
Retry on a retryable failure adds no duplicate user bubble; a possibly-applied write is detected
from the journal; the message distinguishes "no attempts left" from "still being processed".

**T7 — History fidelity and wording.** Persist clarification turns (or reconstruct them from the
retained trace) and label a resume as a continuation. Acceptance: a restored transcript shows
the question and both answers in order; a resume is not labeled "retried"; the live and durable
failure text agree.
