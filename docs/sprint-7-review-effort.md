# Measured review effort: automatic intake vs per-open invocation

**Scope:** S7.8 acceptance record. This note documents what the phone measurement actually showed, its
limits, and the manual fallback that stays in place. It changes no behavior and sets no quality target.

## What was compared

Two isolated builds received the same seven VietinBank-shaped notification deliveries and the same six
categories on one phone (Android API 34):

- **Earlier path (`b1c98fd`)** — the last product state before automatic intake classification. Opening a
  draft ran the local model for that draft, and the review form offered an optional suggestion note that
  triggered another model request whenever it changed.
- **Current path (`12d0b55`)** — intake stores the draft and queues one durable classification job, so
  review opens with a stored suggestion.

The same decisions were executed on both builds through the real review screens: six explicit
confirmations (E, B, C, D, A, G) and one duplicate dismissal (F), with G corrected from the suggested
Entertainment to Food.

## Observed result

| Measure | Earlier path | Current path |
| --- | --- | --- |
| Suggestions stored before review opened | 0 of 7 | 7 of 7 (automatic; 12.7 s for all seven in the prep run) |
| Description entries typed to obtain a suggestion | 4 | 0 (the field no longer exists) |
| Model requests during review | 10 | 0 |
| Waiting on a model answer during review | 42.5 s of explicit polling (3 notes answered in 12–14 s; 1 never answered) | 0 s |
| Deliberate taps | 39 | 35 in one uninterrupted pass |
| Ledger | 6 expenses, 9,847,000 VND; 0 income; 0 transfers; 0 pending | identical |
| Dashboard | 9,847,000 VND, 6 confirmed expenses, 0 waiting | identical |

- The uncertainty-first order was observed exactly as specified: duplicate family first, then the no-fit
  drafts, then the comparable suggestions from the flattest to the sharpest. **Oldest first** showed all
  seven drafts in arrival order, so a confident suggestion is never hidden.
- The high-confidence suggestions were shown with their own scope and numbers and stayed overridable; G's
  correction is reflected in the stored transaction (suggested Entertainment, confirmed Food).
- The dashboards on both builds reconcile exactly to the transactions table and to the fixture sum.

## What this does and does not show

- **Shown:** automatic intake removes the manual model run, removes the four typed descriptions this
  fixture needed for opaque/no-fit memos, and removes review-time waiting for the model.
- **Not shown:** fewer confirmation taps. Type, category and the explicit confirmation dialog remain user
  actions on both builds; the measured tap difference is exactly the note-field taps the current form
  does not have. Wall-clock seconds are dominated by the ADB/uiautomator harness and are not human timing,
  so no time-improvement claim is made from them.
- One earlier-path note request ended in "A suggestion is unavailable right now" and stored nothing. That
  is recorded as an observed fragility of the per-edit request, not as a success.

## Limitations

- One seven-draft fixture, one pinned model, one phone, one session — not a statistical result and not a
  multi-device claim.
- The fixture memos are informative English text, the model's documented first language; real opaque bank
  memos are expected to produce more no-fit outcomes (S7.7 measured 1/6 recall with 206 categories).
- The four typed notes were a deliberate walkthrough choice; they are optional and never required for
  confirmation.
- The measurement was driven through ADB, so taps and entry counts are directly observed while seconds are
  harness-influenced. The current pass was split into two invocations after a harness stop; its tap total
  is corrected for the duplicated navigation tap.

## Manual fallback

Nothing in this measurement changes the fallback:

- Confirming, dismissing and recording never require a suggestion; type and category stay explicit user
  choices.
- A missing, failed, cancelled or stale suggestion stays visible and actionable, and a no-fit outcome
  groups the draft for manual choice instead of guessing.
- The current build's only removed option is the optional per-draft suggestion note; an opaque memo ends in
  manual category selection, not a dead end.

## Evidence

The full record, screenshots, database reconciliation and the exact walkthrough action lists are in
[`artifacts/sprint-7/evidence/s78/device-observations.md`](../artifacts/sprint-7/evidence/s78/device-observations.md).
The queue rules themselves are frozen in
[`sprint-7-confidence-queue-policy.md`](sprint-7-confidence-queue-policy.md).
