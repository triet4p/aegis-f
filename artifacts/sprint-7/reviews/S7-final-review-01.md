# Sprint 7 Final Differential Review

**Task:** S7-final — cross-task differential review of Sprint 7 (S7.1–S7.8)
**Review attempt:** S7-final-review-01
**Reviewer agent:** S7FinalDifferential
**Sprint:** 7 — Proactive Laya and Uncertainty-First Review
**Verdict:** **FAIL** (one actionable LOW finding; no criterion waived, no unsafe workaround proposed)
**Confidence:** 0.82
**Advance:** false
**Owner decision needed:** yes — on whether the single LOW finding gets a bounded S7.3 correction round or is accepted as a recorded limit.

---

## 1. Review Basis (established ground, not re-run)

All eight per-task evidence gates and all eight separate Bronze exact-snapshot checkpoints were read
and treated as established ground:

| Task | Evidence report | Checkpoint | Checkpoint commit |
| --- | --- | --- | --- |
| S7.1 | `reviews/S7.1-review-01.md` (PASS r1) | `checkpoints/S7.1-checkpoint-01.md` | `b1c98fd` |
| S7.2 | `reviews/S7.2-review-01.md` (PASS r1) | `checkpoints/S7.2-checkpoint-01.md` | `1896d2d` |
| S7.3 | `reviews/S7.3-review-01.md` (PASS r1) | `checkpoints/S7.3-checkpoint-01.md` | `abd9142` |
| S7.4 | `reviews/S7.4-review-01.md` (PASS r1) | `checkpoints/S7.4-checkpoint-01.md` | `bfc8f5c` |
| S7.5 | `reviews/S7.5-review-01.md` (PASS r1) | `checkpoints/S7.5-checkpoint-01.md` | `9206cb9` |
| S7.6 | `reviews/S7.6-review-01.md` (PASS r2) | `checkpoints/S7.6-checkpoint-01.md` | `eecb922` |
| S7.7 | `reviews/S7.7-review-01.md` (PASS r3 final) | `checkpoints/S7.7-checkpoint-01.md` | `12d0b55` |
| S7.8 | `reviews/S7.8-review-01.md` (PASS r1, impl-02) | `checkpoints/S7.8-checkpoint-01.md` | `e291db6` |

Independently verified without re-running anything:

- **HEAD/chain:** worktree `HEAD` = `refs/heads/main` = `e291db64dcb42056278aff0586e3c774a43c234a`, matching
  the brief and the S7.8 checkpoint record. The eight checkpoint commits chain exactly
  (`fd3eceb` → `b1c98fd` → … → `e291db6`); each checkpoint's recorded base/post-commit SHAs agree with
  `git log`; committed file counts match each review's manifest (1, 7, 15, 10, 11, 17, 14, 9).
- **No stale measurement:** `git diff 12d0b55..HEAD` touches zero `app/` files — the S7.8 paired study's
  "current path" build (`12d0b55`) runs byte-identical product code to HEAD. The paired result stands
  for the deliverable.
- **Current worktree:** 108 untracked audit/evidence entries plus exactly two modified Main-owned files
  (`docs/PLAN.md`, `docs/sprint-plans/sprint-7.md`). The diff is status/tracking text only (task
  checkmarks, gate links, authorized-scope paragraph). These are the post-checkpoint Main plan/status
  edits the brief permits; no extra checkpoint demanded. Product source is clean.
- **Frozen contract:** `docs/sprint-7-confidence-queue-policy.md` (committed `b1c98fd`, 459 lines) and the
  paired study `docs/sprint-7-review-effort.md` read in full; honest S7.7/S7.8 limits in the brief
  (descriptive one-phone evidence, `MODEL_CHANGED` host-only, serial-not-contention native execution,
  screen-lock lifecycle, same-7 fixture, 4→0 entries / 10→0 review requests, no human-time or
  tap-reduction claim) are all present in the records as disclosed limits — none is an invented
  missing prerequisite, and none was re-litigated.

## 2. Actionable Findings

### Finding 1 — LOW: `markExecuting` can clobber a concurrently retargeted newer question (task mapping: S7.3)

**Claim.** Between the worker attempt's state read in `classify()` and its `markExecuting()` write, a
user edit (or link change) can retarget the running job to a newer question; `markExecuting` then
unconditionally rewrites `requestedQuestion` back to the attempt's own (older) question. The attempt
subsequently settles `RESULT` with `newerQuestion == false`, so the newer question is never asked and
the job is never requeued — it stays settled until the next relevant accepted change, a re-read
delivery, or an explicit user Retry. The draft is not silently wrong: its snapshot is refused/ marked
stale by the S7.4 dependency check (`CONTEXT_CHANGED`), so it sits visibly in Rank 1b with a one-tap
recovery. But this contradicts the queue's own documented contract — `retarget` is documented as "the
newer question runs next" (`BankDraftClassificationQueue.kt:180-186` region) and the S7.3 acceptance
criterion says rapid edits keep the latest job.

**Evidence.**

- `app/src/main/java/io/github/triet4p/aegisf/data/ClassificationWorkDao.kt` — `markExecuting` SQL sets
  `executedQuestion = :question, requestedQuestion = :question` gated only on
  `state = RUNNING AND ownerToken = :token`; it has no guard against a newer `requestedQuestion` that a
  concurrent `retarget` already wrote.
- `app/src/main/java/io/github/triet4p/aegisf/data/BankDraftClassificationQueue.kt:463-527` —
  `classify()` claims, re-reads draft/context, computes the digest, then calls
  `work.markExecuting(jobId, token, question)`; the draft/context reads are suspension points between
  claim and markExecuting where the edit's `DraftReviewSaved` admission can land
  (`onAcceptedChange` → `admitCurrentQuestion(rearmSameQuestion=false)` → `admitQuestion` sees
  `state == RUNNING` → `work.retarget(newQuestion)`).
- `BankDraftClassificationQueue.kt:550-566` — `settleOutcome` derives
  `newerQuestion = job.requestedQuestion != question`; after the clobber both hold the old question, so
  an `Applied`/`NoChange` outcome settles `RESULT` instead of requeueing the newer question
  (`CONTEXT_CHANGED` requeue paths at 569/578/588 are unreachable in this state).
- Coverage gap: `ClassificationWorkLifecycleTest.rapid edits keep the newest question…`
  (`ClassificationWorkLifecycleTest.kt:220-248`) gates at `engine.classify`, which runs **after**
  `markExecuting`, so edits always land post-markExecuting — the test structurally cannot fail on this
  interleave. No artifact (review, worker record, checkpoint) mentions `markExecuting` (grep over
  `artifacts/sprint-7` returns no matches).
- Likelihood is genuinely narrow: the window is only the sub-second span between `claim()` and
  `markExecuting()` (before the model is asked); the device rapid-edits run landed after it. Harm is
  bounded: visible stale suggestion, no wrong application, manual Retry/next relevant change recovers.

**Why it matters.** It is a cross-task seam (S7.3 coalescing × S7.6 save-while-busy surface) that no
per-task gate traced, and it makes the S7.3 criterion's "keep the latest job" claim timing-dependent
with the only coverage being a test that cannot fail on this bug class. It does not invalidate any
passed gate: all recorded device/host runs exercised orderings where the race did not occur.

**Minimal discriminating check (suggested, not run).** A host DAO/queue interleave test:
gate the attempt between `classify()`'s context read and `markExecuting` (or drive the DAO directly):
`claim(id, token)` → `retarget(id, RUNNING, newerQuestion)` → `markExecuting(id, token, olderQuestion)`
→ assert `requestedQuestion` still holds `newerQuestion` and that the attempt settles into a
`Requeue(0, CONTEXT_CHANGED)` rather than `RESULT`. A fix would make `markExecuting` preserve a newer
`requestedQuestion` (compare-and-set) instead of overwriting it. Implementation choice stays with Main;
no redesign is proposed.

**Severity rationale.** LOW: minor, clearly-harmless-in-practice (visible stale state, one-tap
recovery, millisecond window), but evidenced and untraced; flagged rather than suppressed.
**Confidence:** 0.75 (mechanism code-cited; no runtime reproduction was attempted, per no-rerun rule).

## 3. Verified — risks probed and dismissed (one line each)

- **S7.2→S7.3 queue cutover:** the S7.2-era `Channel<Long>(32)` work-holding queue was replaced by the
  durable `classification_work` queue with a `Channel<Unit>(CONFLATED)` wake signal only
  (`BankDraftClassificationQueue.kt:73-74`); no dual-admission path, and `AppContainer.kt:130` wires
  `::admit` — clean cutover, the differing "32 vs 64" figures in task records describe successive
  states, not a live conflict.
- **Queue row duplication:** `classification_snapshots` has a unique index on `draftId`
  (`ClassificationSnapshotEntity.kt:103`), so the `ReviewQueueDao` LEFT JOIN yields exactly one row per
  pending draft — "every pending draft appears exactly once" holds structurally.
- **Cancel vs refresh dead-end:** `refreshSuggestion()` only re-reads the stored snapshot
  (`BankDraftReviewScreen.kt:1203-1208`); after a user cancel, the *same* question is correctly never
  re-armed by re-delivery (`admitQuestion:397-402` + `RETRYABLE_STATES` include CANCELLED gated by
  `respectUserCancel`), a *changed* question (new note) re-arms via reconcile exactly as the cancelled
  panel text advertises, and explicit `retry()` (`:209-215`, `respectUserCancel=false`) re-opens it.
  INFO-level wording nit only: the re-ask fires on the accepted link command, not on the Refresh tap.
- **`cancelForDraft` on terminal rows:** it overwrites `statusReason` on any state but keeps
  `snapshotId` (DAO SQL), so evidence survives; confirmed/dismissed drafts leave the PENDING queue, so
  no ranking distortion.
- **Recovery skip of tokenless rows:** `resumeInterruptedWork` skips `RUNNING` rows with null
  `executedQuestion` for claim-abandonment, but `markExecuting` runs strictly before the request key is
  registered (`classify`: markExecuting → key → `requestClassification`), so a null `executedQuestion`
  implies no reserved operation key exists — nothing can stay reserved; `resumeInterrupted` still
  resets all RUNNING rows unconditionally.
- **User-cancelled vs FAILED re-delivery semantics:** re-delivery re-arms FAILED (`RETRYABLE_STATES`)
  but not USER_CANCELLED — exactly the S7.2/S7.6 disclosed behavior; reconciled with both evidence
  reports.
- **Both-true Rank-1 cases:** user-resolved+stale → subkind 5, direct+resolved → 5, resolved checked
  before stale in the SQL (`ReviewQueueDao` subkind CASE) — implements the S7.5 reviewer's disclosed
  decisions and their rationale consistently beyond the two covered fixtures.
- **Migration/freshness chain:** one schema step (v12, `classification_work`) in the whole sprint,
  `MigrationChainTest` rollback injection recorded; S7.2/S7.4/S7.5/S7.6 correctly claim no further
  schema change (`git diff fd3eceb..HEAD -- app/schemas/` shows only `12.json`).
- **Financial/privacy invariant across tasks:** `transactions = 0` asserted in every task's device
  databases and unit tests; privacy screening precedes content reads; log lines carry ids/digests only.

## 4. Unverified (nonblocking limits, consistent with disclosed task limits)

- No runtime reproduction of Finding 1 was attempted (per no-rerun/expensive-run rule); the
  mechanism is established by code inspection and the structural test gap.
- `PLAN.md` sprint table still shows "S7.8 awaits physical phone unlock" while `sprint-7.md` records
  S7.8 delivered — a stale Main-owned status line in an uncommitted edit; cleanup is Main's, not a
  gate defect.
- Single-entry review reads (`reviewQueueEntry` → `findOrderRow`) reuse the same SQL; equivalence was
  test-verified in S7.5/S7.6 and was not independently re-derived beyond the SQL sample above.
- The `isFull()` bound refuses even a zero-cost fresh-snapshot reuse insert when 64 jobs are pending;
  unreachable in practice and self-healing, noted for completeness only.

## 5. Sprint-Gate Handoff Summary

- All S7.1–S7.8 evidence gates and Bronze checkpoints stand; this review adds no reopened gate and
  re-ran nothing (no 417-test suite rerun, no phone actions, no real-model benchmarks).
- Round allowances consumed: S7.1 r1, S7.2 r1, S7.3 r1, S7.4 r1, S7.5 r1, S7.6 r2, S7.7 r3 (final),
  S7.8 r1. Finding 1 maps to S7.3, which has one remaining default round; reopening preserves counts
  (`skill://omp-subagent-flows/references/evidence-review-cycle.md`). A third round or limit-acceptance
  is Main's/owner's call.
- Normal-phone consolidated acceptance remains a separate pending gate; nothing here claims user
  acceptance.
