# S9.9 — criterion matrix (final worker assessment)

Task S9.9, attempt S9.9-a1. Evidence root `artifacts/sprint-9/evidence/S9.9-a1/`. Device
`AAAJBB4930914141`, isolated package `io.github.triet4p.aegisf.s812evidence`. Verdicts are
**PASS**, **PARTIAL**, or **NOT MEASURED**, with the exact evidence or gap. No verdict is inferred
from a test name or a `SUCCEEDED` state alone.

## A. Sprint acceptance criteria

| # | Criterion | Verdict | Evidence / gap |
|---|---|---|---|
| A1 | One note yields several source-backed editable event interpretations; no expenses, no invented fields, no event copied into every draft | PASS | Fresh DB arrival a/b: 2 events with source spans/amounts/anchors, ledger 0 before explicit confirm. Actual UI clean fixture: 2 events (34/35) INTERPRETED, 2 links, 2 hosted categories. Missing/ambiguous: 3 events. |
| A2 | Relative times resolved against the capture's own timestamp/timezone; event vs bank vs receipt time distinguished; ambiguity preserved; manual split/link available | PASS | Anchors on the capture day; delayed-receipt fixture matched on stated transaction moment (`delayedReceipt stated=2026-11-05 23:50 posted=1800086400000 linked=ACCEPTED`); missing amount stayed null/UNRESOLVED; actual UI run on capture 33 asked for the missing day instead of guessing. |
| A3 | Bounded deterministic retrieval; missing/multiple/conflict never silently attach | PASS | Missing/ambiguous: unique fuel accepted; equal-amount pair not accepted; model asked `field=candidate`; parking (no draft) unlinked. |
| A4 | Automatic attachment only reversible draft context, unique evidence; model/category not authority | PASS | Note-first arrival auto-linked exactly the two unique pairs; misleading 25,000 never accepted; drafts stayed PENDING until the user confirmed. |
| A5 | Hosted model sees current categories and saves one existing category via `update_draft_review`; local identity/revision/catalog authoritative; conflict→missing→ready order; category never resolves a match | PASS | Complete UI run: 2 `update_draft_review` calls in the trace; pre-confirmation draft 24 = category 4, origin HOSTED. Review queue page order `NEEDS_CATEGORY|READY|READY`; stale category write Conflict. |
| A6 | Correction/rejection/note edit/undo/restart cannot silently duplicate or override confirmed facts | PASS | Fresh: duplicate replay 1 row; transfer excluded; cancellation; stale confirm/category Conflict; prior rejection held after a later arrival. Restart/undo reuse accepted S9.4/S9.7 evidence. |
| A7 | Chat reports actual saved events, links, unresolved questions, completed vs cancelled | PASS | Actual UI: enlarged inbox → CLARIFYING with the real date question; clean fixture → SUCCEEDED with events/links/categories reported; no confirm/dismiss tool exists. |
| A8 | Owner output-token policy: no forced 8192; provider default by omission; explicit ≥32768 | PASS | `00-token-policy.md`; wire `serializedLimits=[]`; admitted `maxOutputTokens=null`; explicit 32768 accepted, 8192 refused. Actual UI: 12 requests on the clean run, all with no limit. |

## B. S9.9-specific requirements

| # | Requirement | Verdict | Evidence / gap |
|---|---|---|---|
| B1 | Seeded multi-event note with matching, ambiguous and missing bank drafts | PASS | Arrival a/b + missing/ambiguous stages. |
| B2 | Both arrival orders reach review | PASS (instrumented), PARTIAL (actual UI) | Fresh DB: drafts-first Completed 11/21; note-first arrival auto-link then Completed 6/13, same totals. The actual UI complete run was drafts-first only; the note-first actual UI was not repeated. |
| B3 | Explicit user confirmation and exact dashboard/drill-down totals | PASS | Fresh DB both orders: 70,000 / 2 / Transport 50,000 + Food 20,000. Actual UI clean fixture: 98,000 / 2; dashboard 138,000 / 4; drill-down "138,000 VND · 4 records" exactly. |
| B4 | Transfers | PASS (instrumented) | 500,000 TRANSFER → spending 0, transfers 500,000. |
| B5 | Duplicate notifications | PASS (instrumented) | Identical replay = one row/same id; duplicate-family queue placement. |
| B6 | Cancellation | PASS (instrumented) | `CANCELLED`, ledger delta 0, replay idempotent, fresh session new run. |
| B7 | Stale changes | PASS (instrumented) | Stale confirm and stale hosted category both Conflict. |
| B8 | Unsafe text | **PARTIAL** | Local structural proof: tool catalog has no confirm/dismiss/delete, 0 effects, categories unchanged. The hosted run returned 429 overloaded three times; the model never saw the text. 429 alone does not prove injection resistance. No blind retry. |
| B9 | Retries create no duplicates | PASS (local) | Duplicate replay, identical resend, stale writes, rejection held. |
| B10 | Raw-backed workflow measurements | **MEASURED, ASSISTED FAILURE** | Never-used pair 89k/54k, pre-probe 0 prior events/captures. Manual: 18 actions / 107 chars / 13,052 ms to two records. Assisted: 13 actions / 223 chars / hosted wait 261,540 ms, ending **CLARIFYING with 0/2 links and 0/2 confirmations** (time-to-clarification failure, not capture-to-confirmed); required corrections link+category for both drafts. Earlier untimed complete UI run remains separate. No speedup/benefit claimed. Exact ask_user questions/source/final text were not captured and need a device read. |


## C. Required verification extras

| Item | Verdict | Evidence / gap |
|---|---|---|
| Note with no notification | PASS | Parking event, no draft, editable. |
| Missing amount | PASS | Fresh: null amount, UNRESOLVED, no link. |
| Delayed receipt after midnight | PASS | Fresh: stated 2026-11-05 23:50, posted next day, matched. |
| Two candidates equal amounts | PASS | Fresh: not auto-attached; model asked; user chose. |
| Prior user rejection | PASS | Fresh: rejected pair remained REJECTED after a later arrival. |
| Category archive/change during inference | PARTIAL (reused) | Accepted S9.5/S9.7 evidence; S9.9 ran stale category and stale confirm, not an archive during a live run. |
| Note edit during inference | PARTIAL (reused) | Accepted S9.7 evidence; S9.9's stale-change scenario is post-preview, not mid-inference. |
| Review queue ordering | PASS | Page order emitted and asserted non-decreasing. |
| Restart boundaries | PARTIAL (reused) | Accepted S9.4 process-recovery evidence; S9.9 did not force-stop mid-run. |
| Manual split/link | PASS | Explicit user choice attached an equal-amount candidate; edits accepted. |

## D. Actual UI fixture and state disclosure

- Enlarged inbox actual UI: `run-65f50a45…` CLARIFYING on capture 33 (Vietnamese note, day stated
  once) — 10 requests, no cap, 14 tools, designed date question. Not a completion.
- Clean owned fixture actual UI: `run-2254f933…` SUCCEEDED on capture 41 (61,000/37,000), 12
  requests, 22 tools, 2 hosted categories, 2 links; two explicit UI confirmations; exact
  dashboard/drill-down; records then removed through the user command. Drafts 23/24 remain
  CONFIRMED with null transaction id. Dashboard restored to 40,000 / 2.
- USB unplug killed the instrumentation once (no run admitted); not a durability proof.
