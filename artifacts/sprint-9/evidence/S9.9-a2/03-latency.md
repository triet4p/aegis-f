# Per-attempt latency breakdown (host-only, from the durable trace)

Source: the run's own trace rows with their `createdAtEpochMillis`, exported read-only through
`export_owned` (`owned-runs/s99-owned-run-1b8b6a623f2142ebbc7f9eb969d6cfd2.trace.json`).
Run `run-1b8b6a623f2142ebbc7f9eb969d6cfd2`, admitted limits `maxTurns=12 maxToolCalls=24
maxWallClockMillis=300000`, `maxOutputTokens=null`.

## Attempts

| Attempt | Start→end | Boundary | Turns | Tools | Outcome |
|---|---|---|---|---|---|
| 1 | 16,052 ms | provider connect timeout at +16,001 ms (15,149 ms request) | 0 | 0 | `PROVIDER_FAILURE` transport |
| 2 | 18,944 ms | provider connect timeout at +18,845 ms (15,050 ms request) | 0 | 0 | `PROVIDER_FAILURE` transport |
| 3 | **410,609 ms** | `TIME_BUDGET` checked between turns | 6 | 12 | `TIME_BUDGET` |

Attempts 1–2 are the bounded retry for a transport failure (allowed). Attempt 3 is the run that did
the work; it was **not** retried after `TIME_BUDGET` — `attempts=[3]` ends there, unlike a1's
three TIME_BUDGET attempts.

## Attempt 3, per turn (offset from attempt start)

| Turn | Provider request → provider end | Latency | Tool work in the turn |
|---|---|---|---|
| 1 | +5,057 → +199,062 ms | **194,005 ms** | `select_source` ×2: 128 + 9 ms |
| 2 | +199,330 → +207,397 ms | 8,067 ms | `make_time_anchor` ×2: 37 + 10 ms |
| 3 | +207,566 → +214,216 ms | 6,650 ms | `record_event` ×2: 176 + 139 ms |
| 4 | +214,685 → +220,012 ms | 5,327 ms | `list_drafts` ×2: 125 + 87 ms |
| 5 | +220,388 → +228,021 ms | 7,633 ms | `inspect_link` ×2: 149 + 109 ms |
| 6 | +228,419 → +410,091 ms | **181,672 ms** | `propose_link` ×2: 227 + 154 ms |
| — | — | — | total tool work ≈ 1,415 ms |

Split for attempt 3: provider ≈ 403,354 ms, tool work ≈ 1,415 ms, host/executor gaps ≈ 5,840 ms.
The 300 000 ms wall-clock gate is evaluated before a turn starts, so a turn already running can
carry the attempt past the bound: turn 6 was admitted at +228 s and returned at +410 s.

## What this explains and what it does not

- The assisted failure was **provider latency**, not tool granularity and not the model declining:
  turn 1 alone cost 194 s, and the last turn 181 s. Two turns consumed 376 s of the 300 s budget.
  Tool execution is negligible (≈1.4 s total).
- `update_draft_review` (category) needs one more turn after `propose_link`; at the observed
  per-turn latency it cannot fit under the unchanged 300 s per-attempt bound in this environment.
- The model's decisions were correct at every step it reached: `inspect_link` eligible with
  `candidate_draft_count=1`, `propose_link` ACCEPTED for both pairs.
- This is a latency observation about the configured direct endpoint at the observed times. It is
  not a claim that the workflow is impossible; it is the measured cost that any further bounded
  attempt would face unchanged.

## Admitted source scope (as the model received it)

From the first `PROVIDER_REQUEST` body of attempt 3, the trusted observation block contained:

- **4 capture entries** (`maxCaptureObservations=4`): the newest is the measurement note
  (`saved_order=newest-in-observed-scope`), the other three `older-in-observed-scope`;
- **10 draft entries**, **2 event entries**;
- `list_drafts` later reported `pending_database_complete_at_recheck=true`,
  `scope_exhausted=true`, `conflicting_count=11` — the scope covered every pending draft in the
  session grant, 12 pending at preflight.

The session scope itself is built by the real Chat screen from the Quick Capture page
(`SourceQuery` active, page size 500) and the pending-draft page (limit 32). At this run that page
held 42 active captures and 12 pending drafts.

## Unavailable boundaries

- The unsafe run's per-turn timestamps are not in this table: its trace was exported before
  `createdAtEpochMillis` was added to the export, and its per-run database is deleted after the
  stage by design. Its aggregate is recorded (`10 requests, 9 tools, 1 attempt, 250,046 ms`) and
  its full request/tool payloads are retained.
- Token counts per turn are not asserted here (the provider usage is recorded in the trace rows,
  not summarised in this table).
