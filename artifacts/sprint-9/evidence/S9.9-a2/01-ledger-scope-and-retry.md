# Ledger scope of the measurement baseline, and the run-budget retry change

## 1. `baseline=0/0` is a day range, not the whole ledger

`S99MeasurementInstrumentedTest.aggregate(day)` calls the production
`WorkflowQueryService.confirmedLedgerAggregate(LedgerDateRange(from, to, "Asia/Ho_Chi_Minh"))`
with

- `from` = the fixture day at 00:00 device-zone (`2026-11-06T00:00+07:00`),
- `to` = the next day at 00:00.

So `S99M pair baseline spending=0 records=0` means: **no confirmed records dated on
2026-11-06** before this attempt wrote anything. It is not a read of the whole ledger and it does
not claim the whole ledger is empty.

`S99M path=manual dashboard spending=143000 records=2` is the same day range after the manual path
wrote its own 89 000 + 54 000 expenses (ids 12/13), i.e. `0 + 143 000` over `0 + 2` records.
`afterCleanup spending=0 records=0` is the same range after those exact ids were deleted.

The persistent **dashboard total 40 000 / 2** that S9.8/S9.9-a1 recorded
(`S99CONF` in `evidence/S9.9-a1/device-confirm_state.log`, and a1's worker record) is a different
aggregate over a different period: those two records are dated before the fixture day, so a
2026-11-06 day range does not include them. The UI screenshot shows the dashboard's own period
total; the emitted `dashboard=` line is the day-scoped production aggregate used for the endpoint
assertion. Both are recorded side by side, never substituted for one another.

`S99CONF transactions=[3|61000|…|1793952000000, 4|37000|…|1793952900000]` from a1 are records 3/4,
already deleted through `deleteTransaction` in a1; a1's `device-clean_owned.log` shows the
remaining foreign candidates it removed were its own 43 000/71 000 fixtures. Nothing in this
attempt touches a row outside the ids it reads back from its own confirmations.

Post-terminal check (read-only, no export): the day-range aggregate for 2026-11-06 must return to
0/0, the whole-ledger confirmed total must still read 40 000 / 2, and the ids of ledger rows outside
the fixture day must be the same set as before the attempt. Only ids, counts and totals are read;
no row is copied out.

## 2. `TIME_BUDGET` retry: callsite and before/after classification

Callsite (the only consumer of the classification for the attempt loop):

- `app/src/main/java/io/github/triet4p/aegisf/agent/BoundedWorkflowExecutor.kt:622-623` —
  the `while` in `runAdmittedTracked` continues only when
  `AgentRunFailure.ofStop(current.reason, current.detail)?.retryable == true` and
  `settledAttempt < CanonicalAgentRetryPolicy.MAX_ATTEMPTS`.

Classification before this attempt:

- `AgentRunFailure.ofStop(TIME_BUDGET) -> Timeout(detail)`, and `Timeout.retryable = true`.
  Observed consequence: unsafe run `6aab50fdf46f4f44b97d7312dd052442` ran 3 attempts
  (7/15, 2/6, 3/6 turns/tools; 12 requests, 1 074 584 ms aggregate), each `TIME_BUDGET`, no effects.

Classification after this attempt (`AgentRunFailure.kt:126-128`):

- `AgentRunFailure.ofStop(TIME_BUDGET) -> Terminal("time_budget", detail)`,
  `Terminal.retryable = false`, so the loop's first condition is false and the attempt is not
  replayed.

Not broadened:

- `AgentRunFailure.Timeout` and the provider mapping
  `HostedProviderFailure.ProviderState` whose code contains `timeout` -> `Timeout` are unchanged,
  so a genuine provider request timeout is still retried after write reconciliation.
- `TURN_BUDGET`, `TOOL_CALL_BUDGET`, `CONTEXT_BUDGET`, truncation, filtering, masking, loops and
  user decisions keep their existing terminal classification.

Focused guard: `app/src/test/java/io/github/triet4p/aegisf/agent/AgentRunFailureTest.kt`
(`runWallClockBudgetIsTerminalNotRetryable`, `otherBudgetsStayTerminal`,
`providerRequestTimeoutRemainsRetryable`).

Live proof: the unsafe stage now prints `attempts=` from the run's own trace; a spent budget must
show a single attempt, where a1's identical classification printed three.
