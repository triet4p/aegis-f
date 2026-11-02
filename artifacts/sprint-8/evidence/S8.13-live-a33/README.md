# S8.13 merchant retest — a33 measurement

## Assignment and run identity

- **Task:** `S8.13-merchant-retest`
- **Measurement attempt:** `S8.13-live-a33` (`silver-task`, runtime agent `S813MerchantLive33`)
- **Frozen execution attempt/run name:** `S8.13-fewshot-a32`
- **Batch:** `S8.13-v3-interface-qualification-batch-21`
- **Snapshot:** `S8.13-v3-interface-fewshot-a32-freeze-43`
- **Frozen order:** `a8-recover-01` → `a8-multi-01` → `a8-retry-01`
- **Main approval:** `Main-S813-merchant-retest-43`
- **Freeze:** `artifacts/sprint-8/evidence/S8.13-fewshot-a32/snapshot-43/freeze.json`, SHA-256 `05166a5ddccefa43d132cf74388441d8429d4a28f8febee07afbf37c2d5dfb9c`
- **Reviewed snapshot:** `artifacts/sprint-8/reviews/S8.13-fewshot-review-02.manifest.json`, snapshot SHA-256 `f3823ecd89189aeb662893da3f59f268bc9e19dc55df56244ead6671d33671d4`; review report verdict PASS: `artifacts/sprint-8/reviews/S8.13-fewshot-review-02.md`.

`S8.13-live-a33` is only the measurement-record label. The invocation truthfully used the frozen `S8.13-fewshot-a32` attempt/run identity and its frozen evidence root.

## Freeze recheck and invocation

Before the hosted invocation, the freeze SHA-256 was recomputed and matched the approved value. The production APK SHA-256 matched `2982e0c5d72c2889b3eedd5caa65eb0184d6652a635846368390f1d08c0a3a35`; the isolated test APK matched `c41412835498aa41d4efdf30c36b312b490323e1efac5bfe452ed293a371d98d`. The runner's `strict_freeze()` check validated the frozen 232-file source manifest (`89f023105024680c5bd8d26c44b72c676eae1fae81a387c7aa45fb67268e6158`), source/APK hashes, and identity before its in-memory credential loader was reached; the run completed that check without drift.

One invocation, with no added options, case reruns, or extra calls:

```powershell
uv run python tools/slm/s8_agent_v3_qualification.py reproduce-blocked --attempt-id S8.13-fewshot-a32 --snapshot-id S8.13-v3-interface-fewshot-a32-freeze-43 --run-name S8.13-fewshot-a32 --case-ids a8-recover-01 a8-multi-01 a8-retry-01 --evidence-root artifacts/sprint-8/evidence/S8.13-fewshot-a32 --main-approval Main-S813-merchant-retest-43
```

The runner recorded `reproduction_recorded`; instrumentation `OK (1 test)`; validation and debug-collection errors `null`; `hard_stop: null`. All three selected cases ran once in order. No base-app clear was performed; the base-application fingerprint was unchanged.

## Observed case results

| Case | Expected merchant / behavior | Actual stored effect and trace | Result |
|---|---|---|---|
| `a8-recover-01` | Merchant `null`; one stale-CAS recovery and one applied write | Both `record_event` attempts passed `merchant_ref: null`. The first returned `STALE_OBSERVATION` with no effect; the fresh observation and same grounded occurrence were selected, then one write stored 70,000 VND for 2026-10-21 09:00 with merchant `null`. Exactly one `UPDATE_EVENT`; no ledger/out-of-scope/unexpected mutation. | PASS |
| `a8-multi-01` | First event merchant `Highlands`; second event merchant `null` | The first `record_event` used the separately selected `Highlands` source reference and stored 28,000 VND at 12:30 with merchant `Highlands`. The taxi event passed `merchant_ref: null` and stored 65,000 VND at 13:05 with merchant `null`. Exactly two events and two `UPDATE_EVENT`s; no third event or other mutation. | PASS |
| `a8-retry-01` | Merchant `null`; exact operation replay returns `no_change` and leaves effect unchanged | The Fuel event passed `merchant_ref: null` and stored 50,000 VND for 2026-10-20 15:00. `replay_operation` used the exact operation reference returned by the successful write; replay reported `no_change` and `current_effect_matches: true`. A fresh observation confirmed the same event; exactly one `UPDATE_EVENT`. | PASS |

For all three cases, final narration agreed with the actual tool trace and stored result. The stale-CAS branch in `a8-recover-01` was recovered as prescribed and was not a merchant mismatch. No source/provenance, validator, transport, harness, or provider failure was observed in this sample. The previously observed `Book`-as-merchant semantic error did not recur: the current source has only the item word `Book`, both write attempts use `merchant_ref: null`, and the stored merchant is `null`.

## Recorded measurements and safeguards

| Case | Turns / tool calls / provider exchanges | End-to-end latency | Input / output / reasoning / cached-input tokens |
|---|---:|---:|---:|
| `a8-recover-01` | 8 / 7 / 8 | 26,191 ms | 55,953 / 1,553 / 358 / 46,336 |
| `a8-multi-01` | 4 / 7 / 4 | 15,858 ms | 28,128 / 1,749 / 537 / 23,424 |
| `a8-retry-01` | 6 / 5 / 6 | 19,464 ms | 42,842 / 1,709 / 589 / 36,608 |
| **Total recorded** | **18 / 19 / 18** | **61,513 ms summed case latency** | **126,923 / 5,011 / 1,484 / 106,368** |

All 18 recorded provider exchanges had HTTP 200, no transport failure, and an accepted response guard; no refusal or protocol error was recorded. Each case had no privacy refusal, no semantic sensitive inbound detection, and no serialized-body sensitive match; raw-sensitive data was absent from egress. The capture session is marked `TEST_ONLY`, `synthetic_only_full_bodies`, fixture-only, with `header_values_recorded: false`; credential scope was process memory only and `credential_recorded: false`. The run receipt records the credential channel was served, as required by the approved invocation. Base application remained unchanged.

## Captured evidence pointers

Runner summary and identity-bound evidence:

- `artifacts/sprint-8/evidence/S8.13-fewshot-a32/snapshot-43/reproduction-summary.json`
- `artifacts/sprint-8/evidence/S8.13-fewshot-a32/debug/S8.13-v3-interface-fewshot-a32-freeze-43/S8.13-fewshot-a32/evidence/live-run.json`
- Same `evidence/` directory: `a8-recover-01.json`, `a8-multi-01.json`, `a8-retry-01.json` and their three `.provider-attempts.json` sidecars.

Retrieved full TEST_ONLY capture (no raw payloads copied into this summary):

- `artifacts/sprint-8/evidence/S8.13-fewshot-a32/debug/S8.13-v3-interface-fewshot-a32-freeze-43/S8.13-fewshot-a32/debug/session.json`
- Same `debug/` directory, under each case directory `a8-recover-01/`, `a8-multi-01/`, and `a8-retry-01/`: `case.json`, `turns.jsonl`, `exchanges.jsonl`, and `effects.json`.

The runner reported all 3 case records and all 3 provider sidecars collected, 18 provider exchanges validated, `debug_collection_error: null`, and `validation_error: null`.

## Scope limitation

This is a one-shot, three-case diagnostic subset, not qualification evidence and not a full-suite result. No case was rerun after the sample; no source, build, fixture, scoring, or skill changes were made; no additional tests/builds, commit, or push were performed.

**routing_refs:** `S8.13-merchant-fewshot-cycle-1`; `history://S813FinalizeBronze32`; `history://S813FewshotGold30`.
