# Sprint 7 Final Differential Review — 02

**Task:** S7-final — cross-task differential review of Sprint 7 (S7.1–S7.8), scoped to the completed S7.3 correction cycle and the established S7.1–S7.8 ground.
**Review attempt:** S7-final-review-02
**Reviewer agent:** S7FinalDifferential02
**Sprint:** 7 — Proactive Laya and Uncertainty-First Review
**Scope:** differential assessment of the S7.3 retarget/`markExecuting` correction (attempt S7.3-fix-02, evidence S7.3-review-02, checkpoint S7.3-checkpoint-02) against the ground established in `S7-final-review-01.md`; no per-task gates re-run, no source edits, no new criteria.
**Verdict:** **PASS**
**Confidence:** 0.85
**Advance:** true
**Owner decision needed:** no

---

## 1. Correction Under Review (established, not re-run)

- Worker: `artifacts/sprint-7/workers/S7.3-fix-02.md` (silver-task; round 3 of S7.3-cycle-1, Main-authorized; no commit/push by the worker).
- Evidence review: `artifacts/sprint-7/reviews/S7.3-review-02.md` (PASS, advance true; snapshot manifest `S7.3-review-02.manifest.json`, snapshot SHA-256 `e6e44577014898e647bf41b5895b3bd78229e8a02c0d23c42c9a0690972424f7`).
- Checkpoint (bronze executor): `artifacts/sprint-7/checkpoints/S7.3-checkpoint-02.md`, commit `b841406277b7053fe12e97a5e57ee6bbc04acbac`, parent/base `e291db64dcb42056278aff0586e3c774a43c234a`, exactly the 5 manifest paths.
- Prior ground: `artifacts/sprint-7/reviews/S7-final-review-01.md` (FAIL on one LOW → this correction) and all S7.1–S7.8 evidence reports/checkpoints listed there.

## 2. Differential Verification (read-only; nothing re-run)

- **Chain/staleness:** worktree `HEAD` = `refs/heads/main` = `b841406…`; parent is `e291db6…` as recorded. `git diff e291db6..b841406` touches exactly the 5 checkpoint-manifest files (103 insertions, 9 deletions) — no scope drift.
- **Build-config restoration:** `app/build.gradle.kts` is absent from the commit diff and absent from `git status` modifications — byte-identical to base (targetSdk = 37); the round-2 finding is closed. Worktree product source is clean apart from the two Main-owned plan/status edits (`docs/PLAN.md`, `docs/sprint-plans/sprint-7.md`), consistent with the checkpoint's remnant record.
- **S7.8 paired-study validity (brief's app-delta guard):** `git diff 12d0b55..b841406` changes exactly three `app/` files — the queue (1 line), the DAO (19 lines), and the test (81 lines) — i.e. the delta since the paired study's "current path" build is precisely the concurrency guard. The change is scheduling-only: absent a retarget landing inside the claim→`markExecuting` window, every user-visible flow the study measured is identical, and the study runs contained no such interleave. `docs/sprint-7-review-effort.md` stands for the deliverable; no repeated phone study is needed.

## 3. Seam Inspection (criterion-referenced, code-cited)

### 3.1 Conditional alignment preserves concurrent retarget (S7.3 / queue contract)
`ClassificationWorkDao.kt:150-171` — `markExecuting` sets `executedQuestion = :question` unconditionally but assigns `requestedQuestion` only via `CASE WHEN requestedQuestion = :requestedQuestionBeforeAttempt THEN :question ELSE requestedQuestion END`, still gated on `state = RUNNING AND ownerToken = :token`. `BankDraftClassificationQueue.kt:511` passes `job.requestedQuestion` from the row read that is the **first statement** of `classify()` (post-claim re-fetch — the pre-claim `job` in `runAttempt` is not used). A single `retarget` inside the window always writes a digest **different** from the row's value at the read (`admitQuestion` retargets only when `job.requestedQuestion != question`, `BankDraftClassificationQueue.kt:381-382`), so the CASE can never collide with a one-retarget race; the newer request survives and `settleOutcome` re-reads the row inside the transaction (`:549`) and requeues with `Requeue(0, CONTEXT_CHANGED)` (fresh attempt budget; `requeue` SQL leaves `requestedQuestion` untouched). Matches the retarget contract "the newer question runs next" (DAO `retarget` doc, `ClassificationWorkDao.kt:118-133`) and the round-3 queue-level evidence (real device Room run: newer request retained → requeued → production queue delivered exactly the newest memo, S7.3-review-02 §5.6).

### 3.2 Legacy/stale normalization retained
The pre-fix rationale (carried-over rows would requeue forever without alignment) is preserved: the CASE aligns whenever the persisted request still equals the observed value, and no queue path other than `retarget` can diverge `requestedQuestion` between `classify()`'s read and `markExecuting` (`arm` cannot — row is RUNNING; `settle`/`requeue`/`cancel` never write `requestedQuestion`). The lesson entry records both halves of the trade-off (`.agents/memory/lessons-learned.md`, 2026-10-25 entry).

### 3.3 Token / state / cancel / claim / retry interactions
All transition guards unchanged (`claim`, `settle`, `requeue`, `retarget`, `cancelForDraft`). `executedQuestion` is still written unconditionally and strictly before the request key is registered, so `resumeInterruptedWork`'s "null `executedQuestion` ⇒ nothing reserved" invariant (`BankDraftClassificationQueue.kt:727-740`) is intact. `markExecuting` returning 0 (cancel or resume mid-flight) short-circuits the attempt before any model call; late settle writes 0 rows. Explicit `retry()` (`respectUserCancel = false`) still re-opens a user-cancelled row, and a duplicate re-delivery re-arms `CANCELLED`/`FAILED` rows whose recorded request matches (`RETRYABLE_STATES`, `:798`).

### 3.4 Failing-before citation spot-check
`history://S73RetargetSilverFix` shows the correct sequence: the discriminating test was added first, the single-test `:app:testDebugUnitTest --tests …marking an…` run executed, **then** the DAO and queue-call edits landed — i.e. the failing-before run did execute against base behavior. The recorded `ComparisonFailure` detail is asserted identically by the worker record and the evidence review (§5.1); the transcript stores output status only, so the failure text itself rests on those two agreeing records. Consistent; no discrepancy.

## 4. Actionable Findings

**None.** No criterion violation, no evidence-backed gate concern, no owner decision required. The four review-round allowances stay as recorded (S7.3 r3 final stands; S7.7 r3 final untouched).

## 5. Verified — risks probed and dismissed (one line each)

- **Unconditional-settle branches with a live newer request** (`NeedsClarification` → CANCELLED `DESCRIPTION_MISSING`, `NotFound` → CANCELLED, `Applied` non-Classified / `snapshotId <= 0` → FAILED `NOT_RECORDED` lack a `newerQuestion` requeue branch): pre-existing at checkpoint `abd9142` — the correction diff touches only the DAO and the one call line — and reachable `NeedsClarification` from the queue is essentially privacy-blocked text, where no classification is ever appropriate; post-fix the row still records the newer `requestedQuestion`, so re-delivery/`reconcile`/`retry` recovers with the newer digest, making the corner strictly less harmful than pre-fix. Not introduced or worsened by the correction; flagging it would demand a fourth S7.3 round and a new criterion.
- **Two-retarget round-trip corner** (edit away → edit back inside the sub-second preparation window makes the persisted value equal the before-attempt value again, so the CASE aligns to the intermediate digest): inherent property of the value-compare design Main approved in round-1's suggested fix; requires two distinct accepted changes inside a sub-second window (humanly unreachable; no evidence path produces it) and yields only a stale-but-visible suggestion flagged stale by the S7.4 dependency check — same one-tap-recovery harm class as the fixed finding, strictly narrower.
- **Retarget writing the before-attempt value:** impossible for a single retarget, which fires only when the computed question differs from the persisted request (`admitQuestion:381`).
- **Worker/backoff after `Requeue(0,…)`:** the requeued row is re-claimed and runs the newest question, proven end-to-end by the S7.3 round-3 connected smoke on real Android Room (not re-run).
- **`markExecuting` callers:** exactly one production call site (`BankDraftClassificationQueue.kt:511`) plus the test; no second convention drifted.
- **CHANGELOG / lesson placement:** entry sits under `[Unreleased] → Fixed` (`CHANGELOG.md:135-137`) matching the changelog rule; lesson dated 2026-10-25.

## 6. Unverified (nonblocking limits)

- The `ComparisonFailure` failure text of the failing-before run is attested by two agreeing records; the transcript's stored output line was not independently re-displayed (and re-running is out of scope).
- Minor report-quote drift in `S7.3-review-02.md` §5.1: it names the test "marking an older attempt does not overwrite a concurrently retargeted question", while the committed test is "marking an older attempt preserves and runs a concurrently retargeted question" (`ClassificationWorkLifecycleTest.kt:220-222`). Cosmetic; the manifest-bound snapshot is what was reviewed and committed.
- Consolidated normal-phone user acceptance remains a separate pending gate; nothing here claims user acceptance, and the S7.8 descriptive one-phone limits stay as disclosed in the paired study.

## 7. Sprint-Gate Handoff Summary

- All S7.1–S7.8 gates and checkpoints stand; the S7.3 correction closes `S7-final-review-01` Finding 1 with the approved minimal fix shape, verified scope, failing-before repro, 418-test suite/build (recorded), and a real-device queue run (recorded).
- Round accounting preserved: S7.1 r1, S7.2 r1, S7.3 r3 (final, authorized), S7.4 r1, S7.5 r1, S7.6 r2, S7.7 r3 (final), S7.8 r1.
- No checkpoint created or tasks marked by this review. Remaining open item for Main: consolidated normal-phone acceptance.
