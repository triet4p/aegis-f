# S9.7-a1 criterion-to-evidence map

Criterion source: `docs/sprint-plans/sprint-9.md:32` (full task text) and the assignment.
Implementation paths are owned by this worker; base `820eac95`.

| # | Criterion | Implementation | Evidence |
|---|---|---|---|
| 1 | Amendment/deletion of saved captures and event/link changes with provenance/retention semantics | Existing `reviseCapture`/`deleteCapture` keep the capture identity/time/zone and a text-free tombstone; `updateEventInterpretation` keeps span/identity and compare-and-set revisions; link decisions stay stored. Now links to **confirmed** drafts are retained by `ContextLinkDao.deleteActiveForCapture` while unconfirmed derived links are removed. | JVM `editing a linked capture clears derived context and retains its provenance`, `deleting a linked capture is idempotent and leaves its text free tombstone`, new `a confirmed record keeps its note reference and ledger row when the note is deleted`; device `S97CorrectionBoundaryPhoneInstrumentedTest` (2 tests) incl. reopen after DB reopen; phone Round A steps 5–10 and Round B steps 13–16. |
| 2 | Affected-review revalidation on capture/event/link changes | `pending_bank_drafts.reviewCategoryOrigin` (v18), `BankDraftReviewDao.reopenHostedReviewCategory` (id-scoped, pending-only, revision bump); called by `CaptureMessageRepository` invalidation, `EventContextRepository` reading revision, and propose/accept/reject/unlink. | JVM new tests: `editing a linked note reopens the hosted category and refuses the old context write`, `deleting a note clears a hosted category and keeps the user's own choice`, `revising a proposed event reopens the hosted category`; device review UI test showed the live card change from `Category chosen — confirm or dismiss` to `Category not chosen yet`; phone steps 2→7 and 12→14. |
| 3 | Old-context hosted write cannot mark the draft ready | The S9.5 digest/revision check already rejects a stale `selectDraftCategory`; this task adds the missing stored-state revalidation so an already-saved hosted choice cannot stay `READY`. | JVM `editing a linked note...`, `undo reports a conflict...` (pre-undo hosted write → `Conflict`); device command test stale-write conflict; phone step 7 + verify stage asserts `NEEDS_CATEGORY`/unresolved. |
| 4 | Newer user category selections remain intact | Revalidation only touches `reviewCategoryOrigin = HOSTED`; user review writes record `USER`; a legacy row migrates as `NONE` and is preserved. | JVM `deleting a note clears a hosted category and keeps the user's own choice`, `undo reports a conflict and never overwrites a newer user category`; device command test 2 (user category after note delete + stale undo); migration assertion in `MigrationChainTest`. |
| 5 | Undo reports conflicts instead of overwriting newer work | Unlink/reject/attach keep compare-and-set link revisions; revalidation advances the draft review revision. | JVM `undo reports a conflict...` (stale unlink → `Conflict`, current unlink → `Applied`, user category retained); device command test 2; phone confirmed-record card hides Unlink and stores the reference. |
| 6 | Finalized financial records unchanged until an explicit user correction flow | Capture revision/deletion no longer removes a confirmed draft's link; all four link mutations are refused for confirmed drafts with `CONFIRMED_RECORD`; no ledger write exists on the capture/link paths. | JVM `a confirmed record keeps its note reference and ledger row when the note is deleted` (whole `TransactionEntity` equality before/after), `link decisions on a confirmed record are refused and change nothing`; device command test (ledger row equality + retained evidence); phone step 10/16 dashboard totals unchanged. |
| 7 | Deleting/revising a linked note cannot silently erase ledger description/history or break references | The confirmed link and its stored evidence survive; the event row keeps span/identity/source revision and reports `SOURCE_EDITED`/`SOURCE_DELETED`; `draftContextLinks` reports `sourceCurrent=false`, no excerpt. | JVM confirmed-retention test asserts link state/revision/evidence equality, `SOURCE_DELETED`, `sourceCurrent=false`; device command test reopens the DB and re-asserts; phone step 9/15 shows the retained link and the explicit message. |
| 8 | UI reports the boundary honestly | Capture edit/delete dialog copy states retained confirmed references + reopened hosted category; Chat and QuickCapture cards hide Unlink for a confirmed draft and state the reference is kept; `CONFIRMED_RECORD` has explicit user-facing text. | Phone screenshots `s974`, `s982`, `s990`; UI code paths in `QuickCaptureScreen`, `QuickCaptureEvents`, `ChatOutcomeCards`. |

## Explicitly out of scope / not newly implemented

- No new ledger correction or deletion flow: the existing manual transaction editor remains the
  only explicit correction path; the confirmed row is never touched by note/link actions.
- No hosted provider call and no classifier; the hosted write path is exercised through the same
  `WorkflowCommandService.selectDraftCategory` boundary with an `AgentSession` scope, on device.
- No S9.8 language/provider capability work.

## Proof limits

- Phone actions are scripted ADB guided by screenshot reads, not human/manual use.
- The category reopen on the phone was driven by the real Quick Capture `Save changes` button; the
  stored-state assertions come from the verify stage and instrumented tests, not the screenshots.
- The isolated app database contains both Round A and corrected Round B fixture rows; see
  `INCIDENT-DB-CLEAR.md`. No claim of restoration.
