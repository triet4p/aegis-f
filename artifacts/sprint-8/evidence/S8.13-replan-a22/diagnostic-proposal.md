# S8.13 a22 proposed diagnostic — approval required

- `task_id`: `S8.13`
- `attempt_id`: `S8.13-replan-a22`
- `batch_id`: `S8.13-v3-interface-qualification-batch-21`
- `snapshot_id`: `S8.13-v3-interface-a22-freeze-29`
- freeze: `artifacts/sprint-8/evidence/S8.13-replan-a22/snapshot-29/freeze.json`
- freeze SHA-256: `c297db755a833608abc19c4f93d45973873283293d5fbb177573d105b418d63f`

## One proposed run

Run exactly once after Main approves this exact freeze and proposal. Use the frozen isolated candidate, route, budgets, order, input digests, and run name `diagnostic-repair-nomatch-clarify`; do not switch provider, protocol, or model. The case list is exactly:

1. `a8-nomatch-01`
2. `a8h-nomatch-01`
3. `a8-link-02`

Do not rerun `a8-adv-03`. Its a21 retained record is earlier evidence of one successful bounded repair only; it is not part of this a22 run, must not be spliced into a qualification result, and does not establish the three cases below.

## Required observed behavior

- Both no-match cases must complete only with host-verified `no_effect` / `link_no_match`, complete scoped observations, zero link/event/operation mutations, unchanged source revision, and no stored proposal or attachment.
- `a8-link-02` must show exactly one `PROPOSED` link, zero `ACCEPTED` links/attachments, and exactly one final suspending clarification ask with no later dispatch.
- All three records must preserve current guard/masking evidence, exact run identity, HTTP-200 exchanges, configured identity, and zero unsafe/financial/authority effects. Any stop, missing evidence, unexpected mutation, or contract mismatch remains a failure; do not retry or replace a failed case.

This is a bounded diagnostic, not a qualification pass and scores no qualification gate. Full qualification remains pending: 16 dev + 8 held-out cases for each of three repetitions; thresholds remain 39/48, 17/24, and 7/9 for repair/replay, 100% masking integrity, and zero unauthorized or financially incorrect effects. No hosted call has been made for a22. Await Main's explicit approval before executing the single run.
