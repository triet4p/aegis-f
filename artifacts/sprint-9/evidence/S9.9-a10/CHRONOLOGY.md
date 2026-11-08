# S9.9-a10 action chronology (device-local, Asia/Ho_Chi_Minh)

Attempt S9.9-a10, owner-resumed after the S9.9 pause. Isolated app
`io.github.triet4p.aegisf.s812evidence` only; owner app `io.github.triet4p.aegisf` never launched or
modified. Phone ALT-LX2 `AAAJBB4930914141`, USB present from 20:34 until it disconnected mid-attempt
at 21:24 (see §7).

Installed before this attempt began: app APK sha256
`3e8e4b6a3fdf158db3edd7aaa2efdb083e2428e9749415ef81211b589df55c8e` (owner-stable, unchanged) and
test APK sha256 `db842cdd5262909a9dcf24a84feb64f8011e82159d608cfdcef5f550d1d5a4cb` (the a9 build).
This attempt's final test APK is `2d5d5dbcb867fba765addb524815e7cd43bde379872b488d20bb6711d2387538`
(device hash equal at install). No app APK was rebuilt or reinstalled.

## 0. Read-only preflight and exact inherited cleanup

- `ord` `01-fixture-audit-pre.log` — existing read-only `fixture_audit` stage. Pending drafts 10,
  active captures 56, categories `4:Food,1:Fuel,2:Fuel,3:Transport`. Reused synthetic drafts: `15`
  Petrolimex 50,000 2026-11-05 15:00, `16` Circle K 20,000 2026-11-05 15:15; the misleading nearby
  candidate `17` Highlands Coffee 25,000 2026-11-05 15:10 also pending. Duplicate pending drafts
  from earlier sprints exist (18/21 = 50,000 2026-11-06, 19/22 = 20,000 2026-11-06, 11/13 = 51,000).
- The a9 handoff left one owned `USER_NOTE` capture unverified ("likely 102"). The audit names it
  exactly: `capture=102 kind=USER_NOTE revision=0 capturedAt=1794049620281` (2026-11-08 18:07:00)
  text `On 5/11: fuel 50,000d at 15:00 and snack 20,000d at 15:15.` — identical timestamp and text
  to a9's note.
- `02-cleanup-a9-note.log` — exact-id cleanup: `S99CLEANUP capture=102 outcome=Applied`,
  `remainingPending=10`. Nothing else selected; foreign ledger ids 1/2 (20,000 each) and immutable
  bank-evidence captures untouched.
- `04-provider-prefs-baseline.xml` — 65 B, sha256
  `3325d2a819fdd8062c2cdc48a09b995c9b012915bcdf88b1cf9742a7f057c793`; `files/s98_secret.env`
  absent.
- `07-dashboard-baseline.png/.xml` — 20:41 Dashboard: CONFIRMED SPENDING 40,000 VND / 2 confirmed
  expenses, INCOME 0, TRANSFERS 0, REVIEW 10 waiting, period 11/01–30.

## 1. Temporary provider profile (private key transfer only)

- Key read from the repository `.env` into a host temp file, pushed to `/data/local/tmp`, copied to
  `files/s98_secret.env` by `run-as`, then the device temp and the host temp were deleted. The key
  was never printed, logged, placed in argv, XML or a screenshot.
- `05-profile-staging.log` — existing S98 helper only: `add_custom_profile` `s99-opencode-go-flash`
  → `deepseek-v4.1-flash`, `OPENAI_CHAT_COMPLETIONS`,
  `https://opencode.ai/zen/go/v1/chat/completions`, `credentialNonBlank=true credentialMatch=true`,
  `secretFileDeleted=true`; then `activate_profile` → active. Output cap stays the preset default
  (omitted on the wire); no provider probe or validation call.
- `06-provider-prefs-staged.xml` — encrypted profile store (894 B).

## 2. Causal clock comparison (local deterministic SSE, no provider request)

Both runs use the same `S99ReplayStreamTransport` (60 data events produced every 150 ms, no
network) inside the same fail-closed pin; only the hold's clock environment differs.

- `attempt1/run-eb44a66d6ec99bb6a320ec557d3f5e1e.trace.json` (74015 B, sha256
  `ca2a66f61fa7860e6899dbc5d1509293b193e86712da4ca858933c265f741d2b`) — the a9-style hold:
  `compose.mainClock.advanceTimeBy(1000)` + `delay(1000)`. 61 chunk rows (60 deltas + `[DONE]`),
  first 20:48:31.502, last 20:49:33.254; **span 61.8 s; gaps min 1006, median 1028, p95 1042,
  max 1059 ms; 0 gaps > 5 s**. UI text only advanced once per pumped frame.
- `attempt2/run-5b7254b141a05382f11dc9764c277abb.trace.json` (73833 B, sha256
  `a3517053fa80acf7bfb620f8f9995dfafa6ae4754e27f2f031985243536fa22a`) — the fixed rule-free hold
  (no Compose rule, application frame clock): **span 9.86 s; gaps min 16 (first), median 167,
  p95 171, max 174 ms** — the producer's own 150 ms + overhead. `ATTEMPT_OUTCOME`
  COMPLETED/SUCCEEDED. Streamed text visibly advanced while streaming (local0..local7 at +2.1 s).
- Conclusion: the ~1 s/chunk cadence in a9 was produced by the test clock pump, not by the
  provider. The observed A/B is the evidence; no deeper internal mechanism is claimed.

## 3. Cancel smoke on the fixed path (local SSE)

- `attempt3/run-e66efe107da22b29ee1ac1406c3d2c37.trace.json` (56188 B, sha256
  `7d18862e4922e24a540f2d4e2b28d02dd9a60ff13edaf69717c3f6a0b42dd92a`).
- Send 20:55:52.089, visible **Cancel** tapped 20:55:57.8 (≈4.7 s into the stream): 29 chunk rows,
  gaps min 163 / median 167 / p95 171 / max 202 ms; `RUN_CANCELLED`
  `{reason: USER_CANCELLED, state: CANCELLED}` at 20:55:57.780, `PROVIDER_STREAM_END
  {status:200, chunks:29}` 31 ms later. The UI repainted within ~1 s (Cancel gone, Send enabled).
- Disclosed pre-existing UI wording defect: after a cancel the progress line reads "The hosted
  request could not start…" because the job's `CancellationException` is caught by the generic
  catch. Durable cancel is proven; wording is out of this attempt's scope.

## 4. Live trial 1 — real provider, real UI

- Run `run-faf6b8f65a8f1ff62f87689daecc7848` (row 31), FAILED / `TURN_BUDGET`.
- Trace `live1/run-faf6b8f65a8f1ff62f87689daecc7848.trace.json` (11,314,572 B, sha256
  `3a2d1150587a11d3c9b0159567edfa97d7ab227857731323a562de142fb7054a`): 17,293 rows —
  `PROVIDER_REQUEST` 12, `PROVIDER_RESPONSE` 12, `PROVIDER_STREAM_CHUNK` **17,222**,
  `PROVIDER_STREAM_END` 12, `TOOL_CALL` 14, `TOOL_RESULT` 14, `CLARIFICATION_REQUEST` 1,
  `CLARIFICATION_REPLY` 1, `ATTEMPT_OUTCOME` 2.
- Note saved through the visible UI at 20:58:56 (`Saved. No ledger record or automatic match was
  created.`), 58 chars exact; four intents checked (INTERPRET, SEARCH DRAFTS, LINK, DRAFT CATEGORY);
  prompt 141 chars typed and verified; one Send at 21:00:08.295.
- Genuine clarification at 21:04:48.6: *"The newest saved note gives one day at the start ("On …")
  but the snack purchase clause itself doesn't restate it, so the app couldn't ground a date for the
  snack (event #49) — and that's also why three same-amount drafts still tie. What calendar date was
  the snack purchase made?"* Answered through the visible UI with `5/11/2026` (the note's own day)
  at 21:06:06.9.
- Tool sequence: select_source ×3, make_time_anchor ×3 (one ok anchor from a date ref that was
  never issued by a tool result), record_event ×3 (event #48 fuel **INTERPRETED**; event #49 snack
  initially **UNRESOLVED** with `clarification: date`; the resumed record_event returned
  `REFERENCE_KIND_MISMATCH` at `$.anchor_ref`, constraint `anchor_from_selected_occurrence`,
  message "Use an anchor built from this occurrence's observed date/time references."),
  list_drafts ×2, ask_user, observe ×2.
- Final outcome `FAILED / TURN_BUDGET` at turns 12 / toolCalls 14. UI: "Run incomplete: TURN_BUDGET…
  Run state: failed (run aecc7848)".
- Effects: 2 events; **0 attach_link, 0 update_draft_review**; the capture card showed
  `Linked · 1 bank draft(s)` (unique-evidence auto-link of event #48 to draft #15).

## 5. Live trial 2 — real provider, real UI, grounded prompt

- Run `run-7ea9a3e986f85bbc114297c2888bffe3` (row 32), FAILED / `TURN_BUDGET`, 3 attempts.
- Trace `live2/run-7ea9a3e986f85bbc114297c2888bffe3.trace.json` (4,731,377 B, sha256
  `2aaec8bc6bf327893ff216ef7ae916e43a547d224f0bd62ea756632ebca924a7`): 6,585 rows —
  `PROVIDER_REQUEST` 12, `PROVIDER_RESPONSE` 12, `PROVIDER_STREAM_CHUNK` 6,512,
  `TOOL_CALL` 13, `TOOL_RESULT` 13, `CLARIFICATION_REQUEST` 2, `CLARIFICATION_REPLY` 2,
  `ATTEMPT_OUTCOME` 3.
- Prompt (243 chars, typed and verified, four intents checked, REPLAY OPERATION off, no replay
  transport installed): *"Note dated 2026-11-05. Fuel 50,000 at 15:00, snack 20,000 at 15:15.
  Events 48 and 49 already exist, do not record new ones. Attach each event to its unique
  2026-11-05 pending draft and save Fuel for fuel and Food for the snack. Do not confirm."*
- **Hosted category persisted:** `update_draft_review` on draft #15 → `outcome: ok`,
  `effect: applied`, `verified: true`, `operation_ref r_e2de649cb73fd298440673b9`,
  `review_status: PENDING`, `review_only: true`, `category_resolved: true`,
  `selected_category {id: 1, name: "Fuel"}`, `review_revision: 2`, and `linked_context` showing
  event #48 → **1 hosted category write + 1 accepted link**.
- Clarification 1 (turn 6, field `candidate`): *"Event #49 (snack purchase) has three compatible
  pending drafts and no unique match: draft #22 (VIETINBANK, Circle K), draft #19 (Synthetic bank,
  Circle K) and draft #16 (Synthetic bank, Circle K) all have the same amount… Which one of these
  drafts should be attached to event #49?"* Answered `Draft 16, the Circle K 20,000 bank
  notification dated 2026-11-05. The snack purchase is dated 2026-11-05.` (grounded in the fixture
  audit: only draft #16 is dated 2026-11-05).
- Clarification 2 (turn 12, field `date`): *"Event #49 (the snack purchase) is still stored without
  a date, and the draft you chose (draft #16) cannot be attached until the event has one. What is
  the calendar date of the snack purchase?"* Answered `2026-11-05`.
- Attempt 3 then ended `FAILED / TURN_BUDGET` 425 ms after it started: the run-level 12-turn budget
  had already been consumed by attempts 1+2 (6 + 6), so the third attempt had no turns left.
- Final UI: "Run incomplete: TURN_BUDGET… Run state: failed (run 888bffe3)"; Review badge still
  **10** (drafts remain pending, as required).

## 6. B10 status at the end of this attempt

- Links: **1 of 2** (event #48 ↔ draft #15, unique-evidence link; draft `linked_context_count=1`).
- Categories: **1 of 2** hosted writes (draft #15 → Fuel id 1, `category_resolved=true`,
  `review_only=true`, PENDING, origin HOSTED by construction of `update_draft_review`).
- Explicit user Confirm: **0 of 2** (not attempted; the two-category precondition was never met, and
  confirming one draft would not be an honest B10 proof).
- Dashboard: unchanged 40,000 VND / 2 confirmed expenses (assisted path wrote no ledger row).
- Not met: second link, second hosted category, both explicit confirms, assisted dashboard endpoint.

## 7. Device disconnect and state left behind

- At 21:24 the USB device disappeared (`adb devices` empty; `adb reconnect` and an adb server
  restart did not restore it). Everything below was last observed before that.
- Hold instrumentations both ended normally (`released=true`, `guardChecks=12/12`), and the hold
  marker file `files/s99_interactive_hold.stop` was written by the operator to release them; it was
  not removed afterwards.
- Cleanup that could **not** be performed because of the disconnect:
  - the temporary provider profile `s99-opencode-go-flash` is still stored and **active** (encrypted
    store; the plaintext secret file was deleted by the staging helper);
  - Gboard (`com.google.android.inputmethod.latin/...LatinIME`) is still **disabled** and the
    default IME is the voice IME — it must be re-enabled;
  - post-run prefs byte/hash and `files/s98_secret.env` absence could not be re-verified.
- Verified before the disconnect: `files/s98_secret.env` absent right after staging
  (`secretFileDeleted=true`); foreign ledger rows 1/2 (40,000 total) never targeted; owner app never
  installed/launched by this attempt; no force-stop of live work; one `am force-stop` was issued on
  the isolated app between experiments with no run in flight (disclosed in the worker record).

## 8. Missteps disclosed

- One `run_audit` instrumentation was started while a hold instrumentation was still alive; Android
  reported `Process crashed` for the hold. The run trace is persistent and was exported intact; after
  that exactly one instrumentation was kept alive at a time.
- One `am force-stop` of the isolated app between experiments when no run was in flight.
- Diagnostic prompts were typed with `adb input text`; two shell-metacharacter and one `%s` typo
  were corrected before sending, and Gboard was disabled so the final prompt text was literal.
- The diagnostic prompt of trial 1 was typed while Gboard was enabled and showed Telex corruption
  (`test` → `tét`); the prompt content does not affect pacing and this is disclosed rather than
  hidden.

## 9. Post-reconnect phase — cleanup and offline verification (owner authority)

- Phone reconnected at 21:36 (device clock). Read-only check first: no `aegisf` process, no active
  instrumentation.
- Cleanup only through the previously approved helper, no other instrumentation live: `remove_profile
  s99-opencode-go-flash` → `remaining=[] active=null`; prefs 65 B sha256
  `3325d2a819fdd8062c2cdc48a09b995c9b012915bcdf88b1cf9742a7f057c793`; `files/s98_secret.env` absent;
  hold marker removed and verified absent. Foreign ledger 40,000 VND / 2 untouched.
- Gboard only: no IME enable/disable, no default-IME change, no developer/USB/settings command.
  Visible Gboard verified (`device/136-gboard-visible.png`), the visible globe switched it to English
  (`device/138-gboard-typed.png`), and a literal ASCII string was typed, verified and cleared.
- Provenance fix (offline, no device/provider): red regression test reproduced exactly
  `REFERENCE_KIND_MISMATCH` / `$.anchor_ref` / `anchor_from_selected_occurrence`; after the fix the
  test passes, the full `AgentToolRuntimeTest` class is 42/0/0, and the disposable production-runtime
  smoke read back `eventId=2 status=INTERPRETED amount=20000 date=2026-10-21 time=15:15
  unresolved=null` (`smoke-production-runtime.log`). The smoke entrypoint was deleted afterwards.
- No third live trial. The installed app APK (`3e8e4b6a…`) predates the fix, so the phone path
  remains unverified until an authorized rebuild/reinstall plus one live run.

