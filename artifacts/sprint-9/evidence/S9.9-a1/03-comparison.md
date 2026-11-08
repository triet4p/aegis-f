# S9.9 — assisted vs manual, measured boundaries

This file separates what was measured from what was not. No speedup is claimed; the assisted
end-to-end tap/time boundary was not instrumented, so the two paths are not compared on a
comparable clock.

## Assisted path (actual Compose Chat UI, clean owned fixture)

- Fixture: capture 41, note `Today fuel 61000d at 15:00 and today snack 37000d at 15:15.`;
  own drafts 23 (61,000, 15:00) and 24 (37,000, 15:15); unique amounts → unique matches.
- Run `run-2254f93397ed329911bc760145ba0789`: **SUCCEEDED**, 12 provider requests, 22 tool calls.
  Tools: `select_source`×5, `make_time_anchor`×3, `record_event`×2, `list_drafts`×2,
  `inspect_link`×2, `propose_link`×2, `attach_link`×2, `observe`×2, `update_draft_review`×2.
- Effects: 2 events INTERPRETED (61,000 / 37,000), 2 accepted links, 2 hosted category writes.
- Explicit user confirmation through the real Review screen: type `Expense` chosen by the user;
  draft 24's **hosted category value (Food) was preserved**; draft 23's category was
  **user-overridden** (Transport).
- Ledger: tx 3 = 61,000 EXPENSE Transport; tx 4 = 37,000 EXPENSE Food. Day aggregate 98,000 /
  2 records; Dashboard UI 138,000 VND / 4 confirmed expenses (40,000 prior + 98,000); drill-down
  UI "138,000 VND · 4 records" (61,000 + 37,000 + 2 × 20,000), matching the dashboard exactly.

## Manual review baseline (scripted Compose on the real Review screen)

- 2 real drafts, non-mutating: open → choose type/category → open the confirmation preview →
  dismiss with "Back to review" → back to inbox.
- Measured: **11 taps, 0 characters typed, 4,691 ms, 2 category choices, 0 confirmations,
  0 writes**. Denominators: 2 drafts; 0/2 wrong links (no links involved); 0/2 overrides.
- This is a scripted device baseline, not human performance.

## Not measured

- **Manual capture end-to-end** (two full Manual forms): the real-editor Compose harness failed
  before a stable editor state (and the date/time fields resist scripted text entry), so there is
  no honest tap/character/time number. It is reported unavailable, not estimated.
- **Assisted end-to-end tap/time** (capture entry → intents → Send → two confirmations): the
  Compose driver did not instrument per-action taps or timestamps; only the run's provider/tool
  data (12 requests / 22 tools) and the completion state are recorded. The test-class duration is
  not a task duration and is not used.
- Therefore no speedup ratio is computed. The observable product benefit evidenced here is
  narrower and factual: one note plus a hosted run produced two source-backed events, two unique
  accepted links and two existing category choices; the user then made two confirmations. The
  manual path would require two full forms, which was not measured.

## Provenance denominators

| Item | Assisted | Manual |
|---|---|---|
| Category choices made by the user in review | 2 (both confirmed; 1 value preserved from hosted, 1 user-overridden) | 2 (all user) |
| Hosted category preserved through confirmation | 1 of 1 available pre-confirmation (draft 24) | n/a |
| Wrong accepted links | 0 of 2 | n/a |
| Confirmations | 2, via the real Review screen | 0 (preview dismissed; no write) |

## Cleanup and remaining state

- After the evidence was captured, the two assisted records were deleted through the production
  user command (`deleteTransaction`); drafts 23/24 remain CONFIRMED with `confirmedTransactionId`
  null. The dashboard is back to the prior baseline **40,000 VND / 2 confirmed expenses**.
- No foreign row, database or provider profile was changed. The added owned capture 41 and drafts
  23/24 remain as synthetic fixture rows.

## Matched-endpoint measurement (throwaway harness, real UI)

Fixture for both paths: **71,000 VND @ 15:00 Transport** and **43,000 VND @ 15:15 Food**, same
device-local day and zone; endpoint = two confirmed EXPENSE ledger records.

- **Manual path measured** (`device-measure-manual.log`, `S99M-manual-done.png`): two complete
  Manual forms, saved through the real editor.
  - actions **18** (semantics clicks + typing calls), characters **103**,
    `captureToSavedMillis` **13,222**, first saved **7,777 ms**, finalize **145 ms**.
  - records `6:43000:4`, `5:71000:3`; fields per record 4, category actions 2, save 1.
- **Assisted path**: pending the in-flight run; it measures the same fixture through Quick
  Capture note entry → 4 skills → Send → hosted wait → two Review confirmations, with the same
  action/character counters and milestones (`t0`, `captureSaved`, `sent`, `idle`, `confirmed`).

Counting rule: an "action" is one counted semantics interaction (click or typing call); not a
physical finger tap. Times are monotonic milliseconds. No speedup is computed; the numbers are
reported with their boundaries.

## Assisted measurement attempts (raw, and the blocker)

- Harness path proved: capture through the real Quick Capture field (actions=3, chars=59,
  screenshot `S99M-assisted-capture.png`), then prompt/skills/send (actions=8 total, chars=199),
  then the hosted wait. The prompt reached the run exactly (admitted `maskedRequest` verified).
- Attempt A (`run-904d8988007bc6ed892d2b3d0bf7c044`): SUCCEEDED but with **12 tools and no
  `attach_link`, `propose_link` or `update_draft_review`**; it recorded events and listed drafts
  only. `hostedWaitMillis=201,085`, `totalToIdleMillis=211,364`. One provider failure occurred
  in the trace. The test then confirmed draft 37 with a manual category (tx 7) and its process
  crashed; draft 38 stayed pending.
- Attempt B (fresh 73,000/47,000 draft pair): same capture path, run ended with **no accepted
  link** on either draft (`acceptedLink=false links=[]`), `hostedWaitMillis=18,280`; the gated
  harness correctly skipped confirmation and the endpoint assertion failed 0 vs 2.
- Cause: the model stopped before linking in both measured runs, on the now-bloated persistent
  inbox created by repeated fixtures. This is a model/environment condition, not a harness
  navigation failure: the same prompt/flow completed with links, categories and two confirmations
  in `run-2254f933…` before the inbox grew.
- Therefore the assisted **end-to-end measured number is NOT available**; the earlier complete
  UI run proves the product path but has no per-action timing. No speedup is computed, and B10
  remains PARTIAL with this exact gap.

Next discriminating action (not performed here): dismiss the attempt's own pending drafts and
delete its own captures (owned IDs, real commands) to restore the pre-measurement inbox, then
re-run this same harness once. That is a concrete prerequisite, not a harness retry.

### Attempt C after owned-fixture cleanup

- The attempt's own pending drafts (25–40) were dismissed and its own 71k/73k note captures
  deleted through the real commands; the inbox returned to 10 pending.
- Fresh 73,000/47,000 drafts 41/42 and a new note: capture saved (actions=3, chars=59),
  prompt/skills/send (actions=8, chars=199), `hostedWaitMillis=20,422`,
  `totalToIdleMillis=27,039`. The model again produced **no accepted link** on either draft; the
  gated harness skipped confirmation.
- Working hypothesis (not yet discriminatingly tested): cumulative synthetic events for the same
  amounts/notes in the persistent database lead the model to treat the note as already handled;
  the earlier complete run predates that history. A never-used fixture pair or a fresh isolated
  data set would test it. This is the concrete prerequisite for the assisted end-to-end number.

## Never-used pair (authorized single contrast) — terminal FAILURE

Fixture: **89,000 VND pharmacy @09:10** and **54,000 VND books @09:35**, draft ids 43/44,
note `Today pharmacy 89000d at 09:10 and today books 54000d at 09:35.` saved through the real
Quick Capture field. Pre-run scoped probe: `events89=0, events54=0, captures=0, drafts89=0,
drafts54=0` — uniqueness and no prior owned history for the pair were proven before the run.

- Assisted outcome: `run-8b0c665838324617c7263ea8cbca0ce1`, state **CLARIFYING**
  (`stopReason=clarify`), **3 × `ask_user`**, no `list_drafts`, no `propose_link`/`attach_link`,
  no `update_draft_review`, links **0/2**, confirmations **0/2**.
- Timing: capture 3 actions/63 chars; endpoint actions **13**, chars **223**;
  hosted wait **261,540 ms**; **269,047 ms is time-to-clarification FAILURE, not
  capture-to-confirmed**.
- Required human corrections at that endpoint: `draft43: link + category`,
  `draft44: link + category`.
- Manual path, same values/times/fixed fixture categories: **18 actions, 107 chars,
  captureToSaved 13,052 ms, firstSaved 7,390 ms, finalize 144 ms**; records 89,000 / 54,000.
- Captured but not semantically implied: pharmacy was filed under **Transport** and books under
  **Food** only because those are the fixed fixture categories used for the comparison; this is
  not a claim that those categories are semantically appropriate, and the assisted run produced
  no category to compare.

### Information gaps (host evidence)

- Exact `ask_user` questions/field values (all 3), the chosen source/scope, and the final model
  message were **not emitted** by the harness. The trace rows exist in the owned device database,
  but reading them now would require a new device read; that was not performed under the
  no-new-device-action instruction. Model final text is unavailable (state CLARIFYING, no answer).
- The harness's clarification loop answered up to two turns (evidence: +5 actions/+24 chars and
  two extra busy→idle cycles) with its data-derived date/generic text; the run still ended
  CLARIFYING after a third `ask_user`. The questions did not match the harness's answer policy.
- Continuation: the instrumentation ended and Android force-stopped the app, so the in-process
  clarification handle is **not alive**; no resume was attempted.
- Fixture `capturedAt`/timezone was not emitted; the capture was saved in the device zone
  (`Asia/Ho_Chi_Minh`) during the run.
- Provider/429 correlation for this run was not emitted; `run-904d…` (A) is the run with one
  recorded `PROVIDER_FAILURE`.

## Direct-provider unsafe + fixed multi-note run (post-diagnosis)

- Direct profile `s99-direct-deepseek` (Chat Completions, `https://api.deepseek.com/chat/completions`,
  `deepseek-v4-pro`, `max_tokens` omitted); key injected privately, profile stores a nonblank/match
  credential, secret file deleted.
- Unsafe run `6aab50fdf46f4f44b97d7312dd052442`: 3 attempts (claim/retry/retry), per attempt
  7/15, 2/6, 3/6 turns/tools — each within 12/24; aggregate 12/27 and 1,074,584 ms; each attempt
  ended `TIME_BUDGET`. No effects (ledger 0, pending 2, categories unchanged); all 12 requests
  `serializedLimits=[]`; no 429. Unsafe validation is NOT completed success.
- Fixed multi-note run `run-6e75d2bc…`: SUCCEEDED, no clarification, but chose the fuel note;
  request body shows `captured_at_epoch_millis` masked to `⟦V-chatef245b14-0016⟧`, so the epoch was
  unusable for ordering. Correction: scope-relative `saved_order` (`newest-in-observed-scope` only
  when unique, `tied-newest-in-observed-scope` on equal latest instants,
  `older-in-observed-scope`), with the header stating it is not a global latest claim; numeric
  masking unchanged; 4-case unit test passes.
