# Owner-authorized Flash measurement — result, exact cause, latency

Owner decision: **one** complete measurement pair on the direct DeepSeek endpoint using the current
documented Flash model. No further generation after this run, whatever the outcome.

Primary-source model check (`https://api-docs.deepseek.com/`, read 2026-11-06):

- model id **`deepseek-flash`**, served by `DeepSeek-V4.1-Flash-1017`;
- Chat Completions: `POST https://api.deepseek.com/chat/completions`.
- Temporary isolated profile `s99-direct-deepseek-flash` (Chat Completions, `deepseek-flash`,
  credential streamed to the app over `adb shell` stdin and deleted by the staging stage).
  Removed afterwards; the stored profiles are back to
  `[google-gemini-flash-lite…, s98-gemma, s98-luna]`, active `google-gemini-flash-lite…`.

Harness: `S99MeasurementInstrumentedTest#measurementPair` (manual path first, then assisted),
unchanged product policy (`maxTurns=12 maxToolCalls=24 maxWallClockMillis=300000`,
`maxOutputTokens=null`). Evidence `device/device-measure-pair-flash.log`,
`owned-runs/s99-measure-run-8b4e35e6444fcc2f36789b9542612212.trace.json` (timestamps included,
exported before the fixture cleanup).

## Manual path — COMPLETE (same fixture)

| Metric | Value |
|---|---|
| actions / characters | **18 / 91** |
| capture → second save | **12,833 ms** (first save 7,300 ms, finalize 134 ms) |
| records | `18:89000:Transport`, `19:54000:Food` |
| day aggregate + drill-down | `143000 / 2`; `19:54000:4 | 18:89000:3` |
| stored ledger at the endpoint | 4 records / **183,000** = ids `19:54000,18:89000,2:20000,1:20000` (the 40,000 baseline is visibly included) |
| after exact-id cleanup | day `0/0`; stored ledger back to ids `1,2` = 40,000 |

## Assisted path — FAILED before any write (0/2 links, 0/2 categories, 0/2 confirmations)

| Step | Value |
|---|---|
| preflight before Send | OK — note capture 83 holds the exact text, one active capture with it, pending plausible drafts = exactly {54,55}, categories present, ledger 0/0 |
| attempts | **1** (no transport retry; no TIME_BUDGET replay) |
| outcome | `SUCCEEDED`, `turns=11`, `tools=12`, `hostedWaitMillis=235,400` |
| actions / characters | 8 to idle, 9 to the endpoint; 203 chars (capture 63 + prompt 140) |
| clarification rounds | 0 |
| links | **0/2** |
| hosted category writes | **0 calls, 0 applied** (`origin=NONE`, `resolved=false` for both drafts) |
| confirmations | **0/2** |
| cleanup | drafts 54/55 dismissed, note 83 deleted, day `0/0`; stored ledger ids `1,2` |

### Exact cause (not a product defect)

The model never obtained a valid alias for the newest note. The observation block issued
`r_a9bab2f5bb5d67b55b4b339b` for the capture (`saved_order=newest-in-observed-scope`). The model
sent `r_a9bab2f5bb5d67b55b4b3393` — same prefix, last two characters wrong — in three
`select_source` calls and three `observe` calls. The app refused each with
`UNKNOWN_REFERENCE`/`issued_in_this_run`, which is the correct guard: the alias was never issued.

Cross-check over the whole run: 74 refs were issued; the only used-and-not-issued ref is that one
string. Every other tool call used a real alias and succeeded.

The terminal answer states it plainly ("Every attempt to work with the newest note's capture failed
… `UNKNOWN_REFERENCE` / `issued_in_this_run` on `$.capture_ref`") and stops instead of probing
variants or writing from an unverified reference. No event, link, category or ledger row was
created.

## Latency (host-only, from the run's own timestamps)

| Turn | Provider latency | Turn | Provider latency |
|---|---|---|---|
| 1 | 10,355 ms | 7 | 17,419 ms |
| 2 | 1,747 ms | 8 | 62,105 ms |
| 3 | 10,927 ms | 9 | 23,305 ms |
| 4 | 9,756 ms | 10 | 39,858 ms |
| 5 | 6,622 ms | 11 | 35,769 ms |
| 6 | 14,816 ms | — | — |

Attempt total **234,959 ms**; tool work **395 ms** over 12 calls; no transport failure, no retry.
For comparison, the same fixture on `deepseek-v4-pro` took 410,609 ms with two provider turns of
194,005 ms and 181,672 ms (`03-latency.md`).

## Interpretation

- Flash is materially faster on this workload and fits the 300 s per-attempt bound.
- This run does **not** improve B10: the endpoint is 0/2 links and 0/2 confirmations. The limiting
  factor here is a single mis-copied 24-hex alias, not latency and not a product decision.
- The failure is recorded as observed. No further generation was performed and none is implied by
  this evidence.
