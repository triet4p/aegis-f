# S9.9-a9 action chronology (device-local, Asia/Ho_Chi_Minh)

Phone: ALT-LX2 `AAAJBB4930914141`; isolated app `io.github.triet4p.aegisf.s812evidence`
(app APK sha256 `3e8e4b6a…`, unchanged this attempt) and test APK
`db842cdd5262909a9dcf24a84feb64f8011e82159d608cfdcef5f550d1d5a4cb`
(device hash equal). Owner app `io.github.triet4p.aegisf` never installed/touched.

All observations are `adb exec-out screencap` + `uiautomator dump` pairs stored beside this file;
timestamps are device epoch-ms captured around each action. Screenshots were viewed, not only
stored; XML is the supplementary node/bounds source.

## 0. Baseline before any action

- 17:41 launch → Dashboard: CONFIRMED SPENDING 40,000 VND / 2 confirmed expenses (period
  11/01–30), INCOME 0, TRANSFERS 0, REVIEW 10 waiting. `00-observe.png/.xml`, `01-launch.png/.xml`.
- Chat (`02-chat`): HOSTED AGENT · NOT CONFIGURED (no profile yet). Categories (`06-categories`):
  user-visible "4 categories": Food, Fuel, Fuel, Transport (two pre-existing Fuel rows).
- Read-only `fixture_audit` (existing helper, `S99WorkflowPhoneInstrumentedTest#workflowEvidence
  -e s99_stage fixture_audit`): pending 10, categories `4:Food,1:Fuel,2:Fuel,3:Transport`,
  active captures 55. Pending drafts used by this attempt are pre-existing synthetic rows:
  `15` Petrolimex 50,000 2026-11-05 15:00 and `16` Circle K 20,000 2026-11-05 15:15.

## 1. Manual comparator (visible UI, same data as the assisted scenario)

`t0` = 1794048526824 (17:48:46). Manual tab → Record a transaction; two NEW TRANSACTION forms.

Form 1: amount 50000, Expense, date 2026-11-05, time 15:00, description "Petrolimex",
category Fuel (category dialog `34`/`35`), Save (`36`, `37`).
- `t1` 1794049052702 → `t2` 1794049055948 (Save processing 3,246 ms). Observed transaction:
  50,000 VND · Expense · 2026-11-05 15:00 · Petrolimex · Fuel · Confirmed · Manual (`37`).

Form 2: amount 20000, date 2026-11-05, time 15:15, description "Circle K", category Food,
Save (`46`, `47`). `t3` 1794049187385 → `t4` 1794049190630 (Save processing 3,245 ms).
Observed transaction: 20,000 VND · 2026-11-05 15:15 · Circle K · Food · Confirmed · Manual.

Dashboard (`48`): 110,000 VND / 4 confirmed expenses = baseline 40,000/2 + 70,000/2.
Records list (`49`): Circle K 20,000 Food, S97B coffee 20,000 (foreign id 2), S97B coffee
20,000 (foreign id 1), Petrolimex 50,000 Fuel. Day and period are both 2026-11-05 based:
all four records sit on 2026-11-05.

Exact owned cleanup through the same visible UI: Circle K detail → Delete → confirm (`51`,`52`);
Petrolimex detail → Delete → confirm (`53`,`54`). List returns 40,000 / 2 (`55`), Dashboard
40,000 / 2 with Review 10 waiting (`56`). Only these two owned rows were deleted; the two
foreign 20,000 rows were never targeted.

### Measured manual cost (honest, includes agent UI friction)

- `firstSavedMillis` = t2−t0 = **529,124 ms**; `captureToSecondSave` = t4−t0 = **663,806 ms**.
  These are dominated by the worker's own initial scroll/focus/IME learning (see below); they are
  **not** a human-user baseline and must not be compared to a8's 7,073 / 12,688 ms harness timings.
- Effective actions for a competent user paying the same fields: 2 × (open form, amount, date,
  time, description, category pick, save) + Manual nav = 18 actions; description characters
  **18** ("Petrolimex" 10 + "Circle K" 8); no prompt/answer needed.
- Extra agent-only actions not counted above (disclosed): repeated swipes/taps while discovering
  that a focused text field swallows scroll gestures, and two accidental
  "2026-15:15Circle K"/"Pẻtolimex" entries corrected by clearing the field.

## 2. Guard support for the interactive (screen-driven) session

Approved bounded test-only support: `S99MeasurementInstrumentedTest#interactiveHold`
(`-e s99_measure=hold`), reusing the unchanged `installPinnedGuard()`. It seeds no fixture, taps
no screen and answers no question; it installs the same post/postStream model+endpoint pin and
keeps the app process (and Activity) alive, advancing the Compose test clock each second so the
real UI stays responsive, until the operator creates `files/s99_interactive_hold.stop`.

- First start 18:02:48 showed the guard installed but the app UI frozen at "Reading the stored
  records…" (adb taps were delivered but no recomposition ran). Root cause: the Compose test rule
  owns the frame clock. Added `compose.mainClock.advanceTimeBy(1_000)` inside the wait loop; the
  UI then rendered and accepted input. Hold released normally (18:04:44, guardChecks=0).
- Restart 18:05:28: `S99M guard installed=true expectedModel=deepseek-v4.1-flash
  expectedEndpoint=https://opencode.ai/zen/go/v1/chat/completions`, `S99M hold installed=true
  guardChecks=0`. UI observed responsive (`65`): "HOSTED AGENT · S99 Direct DeepSeek ·
  deepseek-v4.1-flash".
- Profile staged with the existing helper only: `add_custom_profile` for `s99-opencode-go-flash`
  (deepseek-v4.1-flash, OPENAI_CHAT_COMPLETIONS, https://opencode.ai/zen/go/v1/chat/completions),
  credential 72 B secret file sha256 `51d7e7e6…` host/device equal, `secretFileDeleted=true`,
  then `activate_profile`. No provider validation/probe was invoked.

## 3. Assisted run (one authorized workflow)

- Note typed in Quick Capture and saved 18:07:xx: `On 5/11: fuel 50,000d at 15:00 and snack
  20,000d at 15:15.` (58 chars). UI confirmed `Saved. No ledger record or automatic match was
  created.` (`69`).
- Intents checked (`72`): INTERPRET EVENTS, SEARCH DRAFTS, LINK EVENT TO DRAFT, DRAFT CATEGORY
  REVIEW; REPLAY OPERATION off.
- Prompt typed 18:07:56 (141 chars): `Read my saved note, record each purchase, match the bank
  drafts, and save the right existing category on each draft. Do not confirm anything.`
- `Send` tapped once at t10 = 1794049700505 (18:08:20). Screen shows
  `Generating with S99 Direct DeepSeek · deepseek-v4.1-flash…` with Cancel (`74`).
- Hold status: `guardChecks=1` from 18:09:28 onward — the first UI-initiated provider request
  crossed the pinned post/stream guard.

(Workflow continuation, terminal, authoritative reads, Confirm ×2 and dashboard appended below
after they are observed.)

### Actual run outcome (authoritative trace, exported before any cleanup)

Run `run-7e831d58e503f82a54e3e8f03bfa8846` (run row 27), scope `chat-…`, created at Send
1794049701315 (18:08:21). Trace rows: **1,371** → `RUN_ADMITTED` 1, `ATTEMPT_STARTED` 1,
`PROVIDER_REQUEST` 1, `PROVIDER_STREAM_CHUNK` **1,366**, `RUN_CANCELLED` 1,
`PROVIDER_STREAM_END` 1. **Zero** `TOOL_CALL`/`TOOL_RESULT`/`PROVIDER_RESPONSE`/outcome rows.

- `ATTEMPT_STARTED` 1794049703426 (18:08:23); `PROVIDER_REQUEST` 1794049737165 (18:08:57),
  body 66,932 chars, url `https://opencode.ai/zen/go/v1/chat/completions`, no raw
  `50000/20000/89000/54000` in the body, mask tokens present.
- First stream chunk 1794049741192 (18:09:01), last chunk 1794051057911 (18:30:57.9):
  **1,366 chunks in 1,316.7 s (~1.04 chunks/s)** — a very slow provider stream, not a dead
  connection; the OS socket buffer was full (`rx_queue` 353,126 B at 18:24:16/18:24:28) and
  drained later.
- Visible normal UI **Cancel** tapped once at 1794051057570-ish (18:30:57). Durable effect:
  `RUN_CANCELLED {reason: USER_CANCELLED, attempts:[1], state: CANCELLED}` at 1794051057910;
  `PROVIDER_STREAM_END {status:200, chunks:1366, error:null}` at 1794051059044.
  The running UI did **not** visibly repaint after the tap (Send stayed disabled and the
  "Generating…" line stayed on screen through the final `96-after-cancel` capture), but the
  durable run state and the trace prove the cancellation was recorded and the stream closed
  two seconds later.
- Consequence: **no events, no links, no categories (0 HOSTED), no confirmations, no ledger
  rows, no dashboard change** from the assisted path. The run never reached a tool turn.
- Export: `device/attempt1/run-7e831d58e503f82a54e3e8f03bfa8846.trace.json` (800,034 B,
  sha256 `9bda5eea4e62d0d9e96d3d61badd3135ceb9228332c75cb29fa641719f1ce584`), produced by
  `S99WorkflowPhoneInstrumentedTest#workflowEvidence -e s99_stage run_export
  -e s99_run_id run-7e831d58e503f82a54e3e8f03bfa8846` **after** the hold released and **before**
  profile removal/fixture cleanup. Read-only audit
  (`run_audit`) output: `device/97-run-audit.log`.

### Guard / hold lifecycle for this run

- `interactiveHold` installed 18:05:28 (`S99M guard installed=true` model
  `deepseek-v4.1-flash`, endpoint `https://opencode.ai/zen/go/v1/chat/completions`,
  `guardChecks=0`), held through the whole run, released at 18:31:33 after the cancel and the
  post-cancel screenshot: `S99M hold released=true stop=true guardChecks=1 elapsedMs=1565009`.
  One UI-initiated request crossed the guard; no second request, no provider probe, no
  fallback, no Pro.
- The Compose test clock had to be advanced inside the hold loop; without it the app UI froze
  (tap delivered, no recomposition). After the fix the UI rendered and accepted input, but the
  run's own progress stayed extremely slow (above).


## 4. IME / device-state notes

- Vietnamese Telex composition corrupted adb-typed ASCII (`Petrolimex` → `Pẻtolimex`; `Circle`
  survived but `Circle K` lost its space). Mitigation: Gboard IME disabled for literal typing;
  the voice IME can appear on field focus and was hidden with one Back press while visible.
  **Gboard is re-enabled and `default_input_method` re-verified during cleanup.**
- No force-stop, no app reset, no network/airplane toggle, no DB pull/polling, no provider probe.
