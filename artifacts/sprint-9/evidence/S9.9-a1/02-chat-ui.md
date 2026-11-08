# S9.9 — actual Compose Chat UI outcome (isolated phone)

This is the real `Chat / Quick Capture` screen on the isolated app, driven through Compose
semantics (scripted text injection; the note itself was saved through the real screen and the
device IME). It is not human manual use, and it is not claimed as a completed workflow.

## Run that produced provider traffic

- Run `run-65f50a4520ea46b326fefc23614b3539`, state **CLARIFYING**, `stopReason=clarify`,
  `maxOutputTokens=null`, admitted limits 12 turns / 24 tools / 160000 context / 300000 ms.
- Persistent inbox: 10 pending drafts, 10 captures (not cleared; prior accepted rows kept).
- Active profile during the run: `S98 OpenCode Go · DeepSeek V4.1 Flash`, budget
  `maxOutputTokens=null`.
- Intents: `INTERPRET_EVENTS`, `SEARCH_DRAFTS`, `LINK_EVENT_TO_DRAFT`,
  `DRAFT_CATEGORY_REVIEW`.
- Exact prompt entered through Compose semantics (152 chars): "Read the newest saved note. Attach
  only a unique bank match and choose one existing category for each affected draft. Never confirm
  or dismiss anything."

## Serialized requests (real production adapter, real endpoint)

10 `PROVIDER_REQUEST` rows, every one with **no output-limit field**:

```
index 0..9 bytes 64863 69048 71856 77776 79926 83527 85827 87512 91702 99123  limits=[]
```

The output usage per turn is far below the former fixed 8192 ceiling:

| turn | finish | completion_tokens | reasoning_tokens |
|---|---|---|---|
| 0 | tool_calls | 2162 | 1780 |
| 1 | tool_calls | 1161 | 892 |
| 2 | tool_calls | 2402 | 2003 |
| 3 | tool_calls | 724 | 612 |
| 4 | tool_calls | 790 | 698 |
| 5 | tool_calls | 240 | 81 |
| 6 | tool_calls | 139 | 11 |
| 7 | tool_calls | 173 | 0 |
| 8 | tool_calls | 3131 | 2970 |
| 9 | tool_calls | 2742 | 2543 |

No turn ended `length`; there is no `TRUNCATED_TURN` and no `completion_tokens=8192`. The former
S9.8 UI failure mode — the whole 8192 budget spent on reasoning before a tool — is not present.

## Tool calls and the terminal question

14 calls: `select_source` ×5, `make_time_anchor` ×3, `record_event` ×3, `observe` ×2,
`list_drafts`, `ask_user`. No confirm/dismiss/ledger tool exists or was called.

The run ended with the designed clarification:

> The note's second line (Circle K snack, with a clock time) doesn't state its own day — "Hôm nay"
> only anchors the fuel line. What calendar date was the Circle K snack purchase on? This day also
> decides which pending Circle K bank draft is the real match.

That is the app's correct behaviour for a note whose day word is stated once at note level: it
asks instead of borrowing the day for the second occurrence. It is **not a completed workflow**;
the user must answer in the UI to continue.

## What is proven and what is not

- Proven on the actual UI path: the run starts from the real screen, reaches the configured
  provider, serializes **no output-token cap** on any request, spends far less than 8192 output
  tokens per turn, records events and inspects drafts, and honestly asks an unresolved question.
- Not proven on the actual UI path: a completed run (it stopped at the clarification) and exact
  confirmed dashboard totals (the ledger is untouched by the run; no confirmation occurred).
- The complete workflow with exact totals is proven by the fresh-database production
  instrumentation in both arrival orders (`device-arrival_a.log`, `device-arrival_b.log`).

## USB transport observation

One attempt was interrupted by an actual USB/ADB disconnect (`no devices/emulators found` after
285 s). The device-side test process died with the transport; the owned log stopped at
`sent=true`; no run row had been admitted. The app process and Room state survived. No
force-stop, network toggle or database clear was used. After reconnect, the owned log was read
directly from the app's private files (no DB/WAL export).

## Harness corrections (not product behaviour)

- The first successful-admission attempt stalled before any provider request because the test
  polled the app's SQLite file every 2 s while Room had it open. Removing that polling (waiting on
  the app's own busy state instead) made the run proceed. That stall is test-induced.
- The test's final assertion compared a global "confirmed drafts" count; the isolated app already
  held 2 confirmed fixtures from earlier accepted work, so the assertion failed even though the
  run used no confirm tool. The assertion is now scoped to the owned capture/run.

## Visible artifacts

`ui_01_launch.png` … `ui_16_prompt_uppercase.png`/`.xml` record the real screen up to the capture
save and prompt. The successful run's final screen was not captured (the screenshot step was added
after this run); its outcome is backed by the owned trace above.

## Chronology and fixture correlation (correction)

The three UI attempts are distinct and must not be conflated:

| # | Attempt | Admitted run | Outcome | Note |
|---|---|---|---|---|
| 1 | ADB-driven, USB unplugged | none | transport loss at 285 s; log stops at `sent=true`; the instrumentation was killed; no run row | `device-chat_ui-inflight.log` |
| 2 | Compose semantics, with test DB polling | `run-f9945a30bc547e4856f6c92f355e4e3b` | RUNNING, **zero** provider requests, no terminal; test-induced stall (the test opened the app's SQLite file every 2 s); killed by the test timeout | not product behaviour |
| 3 | Compose semantics, DB polling removed | `run-65f50a4520ea46b326fefc23614b3539` | **CLARIFYING**, 10 requests with no limit, 14 tools | `device-chat_ui.log`, `device-ui_trace.log` |

The CLARIFYING run used **capture 33**, not capture 38:

- capture 33 — id `33`, text `Hôm nay đổ xăng 50.000đ lúc 15:00. Ăn nhẹ ở Circle K 20.000đ lúc 15:15. s98edit`,
  `capturedAt=1793888407196`, zone `Asia/Ho_Chi_Minh` (local **2026-11-05 21:20:07**),
  sha256 `fac4b573f86b778b1d22705aca7ecc3f04c0c4fea4d6ed9b670f4e0adf6e650a`.
  Its "Hôm nay" anchors only the fuel line, which is exactly why the model asked for the
  snack's calendar date.
- capture 38 — id `38`, text `Today fuel 50000d at 15:00 and snack 20000d at 15:15`,
  `capturedAt=1793934285018`, zone `Asia/Ho_Chi_Minh` (local **2026-11-06 10:04:45**),
  sha256 `e175590a8e7f4d80a1ddaba31d11f2f737e3c263e723b6f805935a1f6c6b48b4`.
  It was saved through the real Quick Capture screen; the CLARIFYING run did not use it.

So the visible screenshot transcript (`ui_01`–`ui_16`) shows capture 38 being saved, while the
hosted run selected capture 33 from the observed captures. The clarification is about capture 33
and would need **2026-11-05** (its own capturedAt local date), not a guessed date.

Correction to an earlier statement: the USB unplug killed the instrumentation itself. The app
process and Room rows survived after reconnect, but the run was never admitted in that attempt.
Durability of a *running* instrumentation across USB loss was not proven and is not claimed.
