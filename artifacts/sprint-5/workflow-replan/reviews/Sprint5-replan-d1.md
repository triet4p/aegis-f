# Sprint 5 end-of-sprint differential review — Sprint5-replan-d1

- **Task / review attempt / agent:** `Sprint 5 differential` / `Sprint5-replan-d1` / `S5DeepReviewD1` (deep-reviewer role; read-only)
- **Sprint:** Sprint 5 — Provenance, draft lifecycle and shared application APIs (`docs/sprint-plans/sprint-5.md`, workflow-replan root `artifacts/sprint-5/workflow-replan/`)
- **Repository / git dir / worktree / target ref:** `F:/ai-ml/aegis-f` / `F:/ai-ml/aegis-f/.git` / `F:/ai-ml/aegis-f` / `refs/heads/main` at `45893e578f55e7f8e53d3cee6022ea49d8ddcc9c` (assigned; matches the S5.9 checkpoint record)
- **Verdict:** **PASS** — zero actionable findings, no material open question
- **Confidence:** 0.87 (numeric)
- **Advance:** true

## 1. Materials read (differential basis)

All nine per-task evidence reviews (`S5.1-replan-r2`, `S5.2-replan-r2`, `S5.3-replan-r1`, `S5.4-replan-r3`, `S5.5-replan-r2`, `S5.6-replan-r6`, `S5.7-replan-r2`, `S5.8-replan-r1`, `S5.9-replan-r1`), all eleven checkpoint records (`S5.1-replan-c1` … `S5.9-replan-c1`, plus the authoritative `S5.6-replan-c2` byte-proof addendum), the S5.9 worker artifact (`workers/S5.9-replan-a1.md`), the sprint plan, and `docs/sprint-5-workflow-contract.md`. All `~/.agents/rules/*` files (subagent-routing, omp-shell, python, markdown, git, changelog, codex-shell) were enumerated and read before work. Referenced worker records (`S5.6 a6/a4/a3`, `S5.7 a2`, `S5.8 a2/a1`) were consulted through the reviews/checkpoints that cite them, as assigned.

- **Commit chain verified from recorded evidence:** `c6893ab` (S5.1) → `103ca5d` → `67ac7ec` → `668317a` → `9dae689` → `2b8a0fc` → `c514163` → `e72272f` → `45893e5` (S5.9, HEAD). Every checkpoint record binds a matching parent base, `refs/heads/main`, byte-level content proof, and preserved foreign working-tree paths. All checkpoint executors are Bronze-only; no Silver/Gold checkpoint appears anywhere in the chain.
- **No Git operation, test, build, or passed-check re-run was performed by this review.** Source access below was read-only grep/targeted reads.

## 2. Differential probes (risks hunted and dismissed)

New cross-task interactions probed this review, none observed in the recorded evidence, each dismissed with a concrete hook:

1. **Classification request against a terminal (confirmed/dismissed) draft** — `WorkflowCommandService.requestClassification` loads the draft (`NotFound` release), requires caller scope before any protected read, requires optional expected revision match, and the recording path (`ClassificationSnapshotRepository.kt:194-200`) refuses to write a snapshot where `draft.reviewCategoryResolved` and records `USER_CATEGORY_CHOSEN`; a classification can never mutate the ledger (command writes ledger rows only in the financial commands). Dismissed — typed refusals plus no ledger write path; no contract criterion violated.
2. **User category choice vs stale prediction, and the S5.9 "corrected driver assertion"** — the production path marks `USER_CATEGORY_CHOSEN` both at record refusal (`ClassificationSnapshotRepository.kt:194-199`) and at dependency recheck (`:286`), with `markStaleForDraft ... WHERE staleReason IS NULL` preserving the first invalidating cause (`ClassificationSnapshotDao.kt:89-117`). The S5.9 driver change (`after == before` stale reason) was a diagnostic-assertion fix, not a suppression of a product defect. Dismissed — the category-choice protection and the first-cause stale semantics are grounded in the cited production source and recorded device evidence.
3. **Multi-event link cardinality (many active events per draft)** — the unique index is on `activeEventId` (per event, not per draft), so several events may be actively linked to one draft. This was explicitly verified as intended in `S5.3-replan-r1 §2.1/§2.6`, the sprint acceptance only requires independent per-event attach, and per-draft event-id sets are consumed as `eventIds` by the effective-context composite. Dismissed — design boundary documented in the passing task-level review; not a contract violation and no defect observed.
4. **Category/rule changes racing in-flight classification and a stale confirmation preview** — snapshot dependencies bind context, category catalog, rules, model and candidate-set versions with transactional refuse-on-stale recording (S5.4 evidence, `ClassificationSnapshotRepository.kt:305-357,181-244`); financial preview/confirmation binds the exact draft revision and category state, and category deletion bumps draft revisions and clears presets (S5.1 contract §2.4; S5.7/S5.8 tests). Dismissed — both directions covered by recorded evidence and tests.
5. **Operation idempotency ownership across callers (pre-insert identity)** — closed by `S5.6-replan-r6`: the reservation carries the exact caller scope and request digest used by durable-row comparison, with generic conflict for different requests and busy for same-request (`WorkflowClaimGuard.kt:33-39,118-139`; `WorkflowCommandService.kt:599-618,1034-1073`). Same key across process restarts replays to `NoChange` (S5.9 device evidence, PIDs 2584/8901). Dismissed — evidence review and correction verified; no re-check needed.
6. **Source edit/delete vs resolved events and confirmed ledger** — capture revise/delete clears derived readings and active links, keeps REJECTED links for audit, marks `SOURCE_EDITED`/`SOURCE_DELETED`, and leaves confirmed ledger rows untouched (S5.3 evidence §2.9); manual record with a note-event source resolves the event and refuses later links/bank consumption, and transaction deletion releases the event (S5.7 evidence). Dismissed — recorded evidence covers both directions; free of data loss or ledger corruption.
7. **Bounded paging/aggregate exactness and history** — keyset chunked aggregates with revision-time + row-count signal (S5.5 evidence); known nondefect limitation (offset paging may shift under a concurrent insert) recorded in `S5.5-replan-r2` and the worker artifact; aggregate runs in one transaction so totals are internally consistent. Dismissed — limitation acknowledged, not an acceptance violation.
8. **Full-migration legacy provenance/privacy/recovery** — S5.8's exact historical v6 export migrated through v11 keeping legacy text with null authoring instants/timezones (no invented provenance), clean FK, injected final-step failure stayed at v6 then retried to v11; the same-file variant re-exercised in S5.9 (PIDs 27537/30751, totals exactly preserved). Dismissed — chain + same-file failure/recovery both grounded.
9. **Forbidden notification content retention vs permitted bank memo** — permitted captures store parsed memo-only `BANK_EVIDENCE_REF` rows; title/raw body/code/OTP excluded from capture text; rejections carry reason only, never body; digests cover permitted fields only. Dismissed — frozen privacy order re-verified by S5.2/S5.9 device evidence.

## 3. Observations (INFO, nonblocking)

- **S5.2 snapshot digest binding (provenance note):** the Bronze executor recorded in `checkpoints/S5.2-replan-c1.md` that the review's declared `snapshot_sha256` could not be reproduced from the manifest file bytes, so that task's binding rests on independently verified per-file hashes (25/25 worktree and base blob matches). File-level binding is sufficient to rule out scope drift; noted only for method consistency with later tasks, whose digests were reproduced exactly (per c1 records and the S5.6-c2 addendum).
- **CRLF checkin normalization:** established and consistently documented across S5.4–S5.9 checkpoints (`core.autocrlf=true`, committed bytes == Git-clean normalized reviewed bytes); no content deviation.
- **S5.9 "classify-choice" NoChange with a fresh key:** explained by the durable-pending/owner state checked before model work and the corrected driver flow recorded in the worker artifact and review; no product defect implicated.

## 4. Unverified limits (nonblocking)

- OS-bound `NotificationListenerService` callback was never exercised with a real grant — known, documented, consent-held-by-user's-package limit common to all tasks; the app-owned `IntakeRepository.storePermitted` path plus host Robolectric service coverage is the standing evidence. Per assignment, not a new finding and not re-opened.
- Worker-reported host/device runs (355-test suite, 315-test suite, device Laya/on-device screens) were recorded evidence, not independently rerun by any reviewer, per protocol.
- Offset-paging shift under concurrent inserts and high-throughput concurrency stress remain untouched, as recorded by S5.5/S5.9.
- **User acceptance of Sprint 5 remains pending.** This review claims no user approval and no acceptance sign-off.

## 5. Gate

No actionable finding raises a task back to fresh review; no material open question remains; no task reopened. Main may proceed to the consolidated user acceptance pass on the current `main` at `45893e578f55e7f8e53d3cee6022ea49d8ddcc9c`.
