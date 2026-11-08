# S9.2-a2 — candidate retrieval: acceptance mapping and observed limits

Task `S9.2`, attempt `S9.2-a2`, runtime agent `S92GoldTakeover`. Base commit
`9d446323820bcb6bbd0dcc1aa1d4d2d55deabd93`. Candidate source hashes are unchanged from the
paused `S9.2-a1` manifest; this attempt added no source edits. Evidence roots:
`artifacts/sprint-9/evidence/S9.2-a2/`.

## 1. Acceptance mapping

| S9.2 requirement | Implementation point | Observed verification |
| --- | --- | --- |
| Bounded, deterministic candidate query against the actual app API | `WorkflowQueryService.matchDraftCandidates` (+ `PendingBankDraftDao.findPendingByIds`), `LinkEvidencePolicy.candidateMatches` | JVM `AgentToolRuntimeTest`; isolated Room phone test; live hosted `list_drafts` returning complete/imcomplete flags; fixed ordering and rationale |
| Supported time intervals (point day or stored interval, cross-midnight) | `assessDraftCandidate` time dimension; interval clock or day comparison | Phone Room interval 23:50→00:20 with a parsed transaction at 00:10 and a delayed receipt at 00:45; JVM boundary fixtures |
| Available amount and named-merchant evidence | amount exact comparison, normalized exact-phrase merchant containment | JVM; live event #2 unique by amount+time+merchant; live event #1 ambiguous because the stored event has no merchant |
| Handle delayed/midnight notifications | receipt `postedAtEpochMillis` is not evidence; parsed `statedDate`/`statedTime` used | Phone test persists transaction time separately from receipt time across Room reopen; live match payload has `receipt_time_used: false` |
| Explicit pending-draft corrections | `reviewAmountText`/`reviewDateText`/`reviewTimeText`/`reviewMemoEdited` take precedence via `effective*` helpers | JVM `event draft retrieval ranks parsed evidence honors corrections and excludes terminal rows` |
| Missing / unique / ambiguous / conflicting / incomplete outcomes | `DraftCandidateMatchOutcome`; incomplete resets any unique/missing claim | JVM missing, conflicting, ambiguous, fresh-out-of-grant, beyond-first-page; live unique and ambiguous |
| Deterministic ranking independent of category/model certainty | Fixed comparator (amount, time, merchant, point distance, creation, id) and fixed rationale strings; category/model absent | Live `ranking_rationale` in tool result; JVM repeated-order assertion |
| Exclude confirmed/dismissed/incompatible drafts | DAO filters `reviewStatus='PENDING' AND confirmedTransactionId IS NULL`; policy also skips non-pending/confirmed | JVM terminal-row fixture; live 6 pending rows only, conflicts counted but not returned |
| No silent attachment or ledger effect | Retrieval tool has no mutation; `AgentNoEffectClaim.DraftScope` re-read | Live before/after DB identical: transactions 0, context_links 0, six PENDING drafts with same ids/revisions, operations unchanged |

## 2. Deterministic fixtures exercised

| Fixture | Where | Observed |
| --- | --- | --- |
| Exact candidate set, equal amounts nearby, missing event merchant | JVM `missing event merchant leaves equal amount nearby purchases ambiguous`; live event #1 | 2 candidates, `ambiguous`, merchant `missing`, stable order |
| Terminal (confirmed/dismissed) rows | JVM main retrieval test | excluded from result and conflict counts |
| Corrections to amount/date/time/memo | JVM main retrieval test | corrected rows evaluated on corrected values; stored direction intact |
| Missing amount/date evidence | JVM `missing draft evidence stays missing instead of becoming unique or conflicting` | `missing`, never `unique` |
| Amount conflict / merchant conflict | JVM; live event #2 (5 conflicting rows) | conflicting rows excluded, `conflicting_count` reported |
| Cross-midnight interval + delayed receipt | Phone `S92CandidateRetrievalPhoneInstrumentedTest`; JVM | interval match on parsed time after midnight; receipt not used |
| Beyond first page / incomplete grant | JVM `incomplete pending scope preserves useful candidates without claiming unique or missing` | useful in-scope rows + `incomplete`; no unique/missing claim |
| Fresh pending row outside an otherwise complete grant | JVM `fresh pending rows outside an otherwise complete grant prevent a unique claim` | `incomplete` |
| Stable pages / cursor bound to the event | JVM `event candidate pagination is stable and cursor-bound to the event` | stable order, `CURSOR_STALE` on changed signature/event |
| Actual hosted model SEARCH_DRAFTS | Device live run (`live-hosted-run.json`) | real Gemini response, `list_drafts(event_ref)` tool calls, unique/ambiguous answers, no financial effect |

## 3. Bounded-scope assessment (concrete gap, not suppressed)

The hosted session is granted only the first page of pending drafts:
`QuickCaptureScreen` builds the scope with `QueryPageRequest(limit = 32, offset = 0)` and sets
`draftScopeComplete = !pendingDraftPage.hasMore`. Retrieval therefore evaluates exactly the granted
pending ids. `matchDraftCandidates` re-reads current pending ids in the same transaction and reports
`pending_database_complete_at_recheck=false` when the grant was truncated, a fresh row appeared, or
another page exists; the outcome then becomes `INCOMPLETE` and no `UNIQUE`/`MISSING` claim is made.

Assessment against the existing bounded-query acceptance: **met within the app's existing session
authority**. The retrieval is bounded and deterministic, and it never widens authority on its own.
Concrete remaining gap, reported plainly: **a pending draft after the first 32 is not retrievable in
one hosted session**, so database-wide `UNIQUE`/`MISSING` cannot be claimed there. This is a session
grant capability boundary, not a matching bug; no 32→500 widening or unbounded scan was introduced.
The live run had 6 pending drafts, so its scope was genuinely complete
(`candidate_set_complete_within_granted_scope=true`,
`pending_database_complete_at_recheck=true`); that observation does not prove >32 behavior.

## 4. Unsupported evidence dimensions

The stored note event has neither an account nor a transaction-type field. Both remain `UNKNOWN`
in every result (`unknown_dimensions: ["account","transaction_type"]`), and bank name, draft
direction, receipt time, category choice and model certainty are never substituted. No wrong-account
or wrong-type exclusion is claimed.

## 5. No-effect, revision and cursor semantics inspected

- `AgentToolRuntime.verifyNoEffect(DraftScope)` re-runs the same bounded filter and requires the same
  sorted ids, the same per-draft review revisions and, when the original scope claimed exhaustion,
  an untruncated current scan; it also rechecks the observed event revision. `verifyLinkAbsence`
  requires every inspected pair to still be ineligible with no stored proposal/attachment.
- `BoundedWorkflowExecutor.verifyNoEffectSettlements` now groups scope claims by review status,
  duplicates flag, event id and event revision, and carries the observed draft revisions into the
  recheck, so an event-bound query cannot be settled against a different filter or a moved draft.
- Cursor signatures include scope completeness, event id/revision, match outcome, conflict count,
  pending-database completeness and per-row review revisions. A stale event revision raises the
  event-capture stale error before retrieval; a changed signature returns `CURSOR_STALE` with a
  fresh cursor instead of silently skipping rows.
- In the live run the model first omitted `cursor` and the application refused the call as
  `SCHEMA_INVALID` with no dispatch/effect; the retried call with `cursor: null` produced the normal
  result. This is the observed refusal path, not a hidden fallback.

## 6. Not part of S9.2

No automatic attachment or proposal policy (S9.3), no arrival-triggered reconciliation (S9.4), no
hosted category write (S9.5), no receipt-time identity, no ledger mutation, and no schema change or
migration. The live run's `verifiedEffects` is empty because no mutation was requested and no
empty-database absence claim applied; the UI reported "Verified application changes: none."
