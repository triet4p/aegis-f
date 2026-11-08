# Measured pair — raw-backed numbers, failures and boundaries

Same fixture for both paths: **89,000 VND pharmacy @ 09:10** and **54,000 VND books @ 09:35**, on the
device day `2026-11-06`, zone `Asia/Ho_Chi_Minh`; endpoint = two explicitly confirmed EXPENSE
ledger records reconciled against the Dashboard/drill-down. Manual categories Transport/Food are
fixture choices, not a claim that those are the right categories.

Counting rule: one "action" = one counted semantics interaction (click or typing call), not a
physical finger tap. Times are monotonic milliseconds from the harness. No speedup or benefit ratio
is computed.

## Manual path — COMPLETE

`device/device-measure-pair-run2.log` (and the identical run 1 log).

| Metric | Value |
|---|---|
| forms / fields / category choices | 2 / 4 per form / 2 |
| actions | **18** |
| characters | **91** (51 of them the two unique descriptions) |
| capture → second save | **12,475 ms** |
| first save | 7,412 ms (run 1: 7,338 ms) |
| finalize (Done → list) | 151 ms |
| records | `14:89000:Transport`, `15:54000:Food` |
| day aggregate | `143000 / 2`, drill-down `15:54000:4 | 14:89000:3` |
| baseline before / after exact-id cleanup | `0/0` → `143000/2` → **`0/0`** |

Screenshots: `S99M-pair-manual-dashboard.png` is the real Dashboard screen (its own period total);
`S99M-pair-after-manual-cleanup.png` is the same screen after the two records were deleted by id.
The screen figure is not claimed to equal the day-scoped aggregate printed next to it.

## Assisted path — NOT COMPLETE (2/2 links, 1/2 confirmations)

`device/device-measure-pair-run2.log`, trace `owned-runs/s99-measure-run-1b8b6a…trace.json`.

| Segment | Value |
|---|---|
| seed drafts / note capture | 51 (89,000 @09:10) / 52 (54,000 @09:35); note capture 79 |
| preflight before Send | OK — note id holds the exact text, one active capture with it, pending plausible drafts = exactly {51,52}, categories present, ledger 0/0 |
| capture (real Quick Capture) | 3 actions / 63 chars |
| prompt + 4 skills + Send | 5 actions / 140 chars |
| clarification rounds | **0** |
| hosted wait (send → idle) | **446,223 ms** (attempt 1 + 2 transport retries + attempt 3) |
| run outcome | `FAILED / TIME_BUDGET`, 6 turns / 12 tools; `update_draft_review` **not reached** |
| links | **2/2 ACCEPTED** (`propose_link` for both pairs, after `inspect_link` = eligible, count 1) |
| confirmations | **1/2** — draft 51 confirmed through the real Review screen (transaction created) |
| second confirmation | not attempted: the harness waited for the wrong post-confirm screen |
| assisted total actions to that point | 9 (8 pre-send + opening the draft/type/category/confirm sequence counted separately below) |

### Why the second confirmation was not attempted

The first confirmation SUCCEEDED (draft 51 CONFIRMED, `89000` expense created). The harness then
waited for a "Back to inbox" action that the real screen only shows for a draft that was already
terminal; on a successful confirmation `handleOutcome` calls `onNavigateUp()` and the screen
returns to `REVIEW NOTIFICATIONS`. The harness timed out after 30 s. This is a harness defect — the
product behaved as designed — and it is fixed in the current source
(`S99MeasurementInstrumentedTest` waits for `REVIEW NOTIFICATIONS` or `Back to inbox`) and proven
without a model by `confirmReturnsToReviewList`
(`device/device-confirm-flow.log`: `returnedToReviewList=true`, tx 17 deleted by exact id).

Because the abort path ran, the fixture was consumed: draft 52 was dismissed and note capture 79
deleted. The leftover transaction 16 (89,000) was then deleted by exact id; the day aggregate
returned to `0/0` and the stored ledger to ids `1,2` = 40,000.

## Earlier assisted run on the fixed source (same fixture, before the candidate fix) — FAILURE

`run-10e27d09a42a2fe3db9817002a1f08bf`: `SUCCEEDED` at the model level, 8 actions / 203 chars,
hosted wait 588,081 ms, **0/2 links, 0/2 confirmations**. Its own `inspect_link` returned
`eligibility=ambiguous, reason=COMPETING_CANDIDATE, candidate_draft_count=4` while `list_drafts`
returned `unique / candidate_count=1`. That contradiction is the defect documented in
`02-inspect-link-defect.md`; the model then correctly refused to attach. Kept as a retained
failure, not hidden.

## Provenance / denominators

| Item | Manual | Assisted |
|---|---|---|
| records written by this path | 2 / 2 | 1 / 2 |
| wrong accepted links | n/a | 0 / 2 |
| user category choices | 2 (all manual) | 1 (user choice; hosted category not produced in this run) |
| confirmations | 2 via Manual save | 1 via real Review screen |
| hosted model calls | 0 | 3 attempts (2 transport, 1 work) |
| day aggregate before cleanup | 143,000 / 2 | 89,000 / 1 |
| day aggregate after exact-id cleanup | 0 / 0 | 0 / 0 |

## Limits

- The assisted measurement is **not** complete: it has no 2/2 confirmed endpoint, so no
  assisted-vs-manual time/tap comparison is computed.
- The two transport retries (15 s connect timeouts each) are counted in the hosted wait; they are
  not model time.
- The measured run's per-turn latency is in `03-latency.md`: two provider turns (194 s and 181 s)
  consumed the 300 s per-attempt bound; tool work was ~1.4 s total. A category turn could not fit.
- The screenshot is the app's Dashboard period total; the emitted number is the day-scoped
  aggregate. They are recorded together and never substituted for each other.

## Next discriminating action (owner decision, not performed)

The authorized fresh-fixture run was executed on `deepseek-flash` (`06-flash-measurement.md`) and
failed earlier in the chain: the model mis-copied the newest note's capture alias once and then
stopped, so it produced 0/2 links and 0/2 categories. The fixture is spent and no further
generation is authorized.

The single remaining discriminating action is therefore not another blind run: it is to decide
whether the workflow should tolerate a model that repeats an unissued alias once. A candidate
correction would be a **product** improvement (for example, re-issuing or restating the capture
alias in the error observation so the model can recover), which is new scope and needs the owner.
Without such a change, repeated runs on either measured model are a lottery on model behaviour, not
a measurement of the product.
