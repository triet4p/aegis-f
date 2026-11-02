# S8.13-replan-a9 — hosted three-case repair diagnostic

## Scope and disposition

This is the owner-approved exact-three-case-per-candidate diagnostic, not a qualification batch or a substitute for full S8.13. The frozen selection was `a8-recover-01`, `a8-retry-01`, and `a8h-retry-01`, in that order, once each for each candidate. Six case executions completed; no replacement cases, retries, or additional suite runs were made. `S8.13-cycle-1` round 1 remains **UNASSESSED**.

The source/input snapshot is `S8.13-repair-diagnostic-batch-01-freeze-01` in `diagnostic-freeze.json`. The snapshot binds the exact three-case input/fixture hashes, v3 projection, preflight checksum, runtime source/schema/skill/scoring hashes, per-case budgets, isolated APK identities and hashes, and the read-only device identity. Its scope is explicitly marked diagnostic-only.

## Preflight, build, and device identity

Commands ran through PowerShell with the project `uv` Python environment:

```text
uv run python tools/slm/s8_agent_repair_diagnostic.py build
uv run python tools/slm/s8_agent_repair_diagnostic.py preflight
uv run python tools/slm/s8_agent_repair_diagnostic.py freeze
```

Observed: the final corrected build succeeded. `aapt2` package/manifest verification bound app package `io.github.triet4p.aegisf.s813evidence`, test package `.test`, instrumentation target `io.github.triet4p.aegisf.s813evidence`, and `androidx.test.runner.AndroidJUnitRunner`. Final APK SHA-256 values are app `4804f8e9064b50437e234f9bf1983280c19af84c736420ffd7134e230031a52c` and test `0fd524f0bfb60deb6de0a8e9669ed0add106793ce7b9e4d545b31bc3ce88688e` (`build-corrected.json`). An earlier successful pre-correction build record is preserved separately as `build.json`; it is not the frozen build.

The offline v3 preflight recorded 16 dev/8 held-out base identities, the unchanged repair inputs, exact selected IDs, immutable fixture/scorer digests, and zero hosted calls. Both process-only credentials were available (booleans only; no key was recorded). Immediately before first device mutation, the assigned phone was read-only verified as `AAAJBB4930914141`, `ALT-LX2`, Android 14; the run runner repeated this identity check before installation. Only the isolated app/test packages were installed. Neither run cleared app data, mutated the base application, nor staged Laya.

## Hosted execution and operational observations

Each run command was invoked once, sequentially, under the frozen snapshot:

```text
uv run python tools/slm/s8_agent_repair_diagnostic.py run --profile candidate-a --timeout-seconds 10800
uv run python tools/slm/s8_agent_repair_diagnostic.py run --profile candidate-b --timeout-seconds 10800
```

Candidate A used Gemini 3.5 Flash Lite over Google AI Studio native GenerateContent. Candidate B used DeepSeek V4.1 Flash over OpenCode Go Zen chat/completions. Both used the frozen 8192 output-token-per-turn budget and the existing six-turn/eight-tool-call per-case limits; no legacy 2048-token cap or budget increase was applied.

Both selected Android instrumented invocations returned `OK (1 test)` and collected exactly three case records plus three provider sidecars each. Candidate A recorded 9 HTTP 200 exchanges; Candidate B recorded 8 HTTP 200 exchanges. All 17 provider responses had matching model echoes; no transport/provider errors, HTTP errors, response truncations, masking failures, or safety-stop conditions occurred. Every case passed the outbound/inbound raw-sensitive masking checks. Across all six cases, persisted ledger rows, out-of-scope rows, and unexpected mutation operations were zero; no unauthorized or financially incorrect applied effect was observed.

Observed case end-to-end latencies were 6,708–14,743 ms for A and 25,579–31,996 ms for B. A reported 53,800 input / 1,960 output / 6,390 reasoning tokens; cached-input tokens totaled 24,316 across turns that reported them, with three first-turn cached-token fields absent. B reported 40,051 input / 9,802 output / 6,719 reasoning / 26,240 cached-input tokens. All case runs reported cached-prefix reuse. B returned `cost: 0` per response plus provider list-cost/allowance metadata; this metadata is not an account charge or quota measurement. A returned no per-response cost. Account-level charge and quota remain unknown.

## Per-case intent/effect adjudication

These are observations against the unchanged frozen case intents/effects, not a qualification score. A `Completed` workflow outcome is not treated as task success by itself.

| Candidate | Case | Actual chain and stored effect | Strict case result |
| --- | --- | --- | --- |
| A | `a8-recover-01` | First update had full amount/date tokens but a time scalar without delimiters; the real adapter returned safe `invalid_scoped_value` feedback and a fresh revision-1 observation with no event. The next call was schema-invalid (`expectedEventRevision` required, not dispatched); a later full-token write using revision 1 applied and stored the expected reading. The injected revision change never reached the CAS: no service conflict was returned. | **Fail** — the required conflict → fresh-read → corrected-write recovery chain was not demonstrated. The tool validation was not weakened. |
| A | `a8-retry-01` | Full tokens first; actual `APPLIED` then actual `NO_CHANGE`, one stored event, no ledger effect. | **Fail for the strict exact-payload criterion** — the repeat changed `expectedEventRevision` from `null` to `0` and omitted two optional null interval fields. Same normalized business values reached `NO_CHANGE`, but the provider payloads were not structurally identical. |
| A | `a8h-retry-01` | Full tokens first; one `APPLIED` update and one stored event. | **Fail** — no identical repeat was dispatched and no `NO_CHANGE` was observed. |
| B | `a8-recover-01` | Full tokens first; the actual application result was `conflict / stale_or_conflicting_state`, followed by a fresh observation at capture revision 1 with no event. The next update used revision 1 and full tokens and applied. The stored event had an unexpected non-null unresolved reason and status `UNRESOLVED`, while the frozen expectation has no unresolved reason. | **Repair mechanics pass; overall intent/effect fail** due the unresolved stored status. The actual nested tool result proves the conflict; see the evidence note below. |
| B | `a8-retry-01` | Full tokens first; dispatched the structurally identical update payload again; actual `APPLIED` then actual `NO_CHANGE`, one interpreted event, no ledger effect. | **Pass** for the frozen replay intent/effect. |
| B | `a8h-retry-01` | Full tokens first; one `APPLIED` update. Stored event status was `UNRESOLVED` due a non-null unresolved reason. | **Fail** — no identical repeat/`NO_CHANGE`, and stored status differs from expectation. |

Strict unchanged-case intent/effect adjudication is 0/3 for A and 1/3 for B. This small diagnostic is not an overall S8.13 score or gate assessment.

## Preserved evidence and interpretation note

- `diagnostic-adjudication.json` contains the concise per-case chains, operational aggregates, and explicit limits.
- `candidate-a/pass-1/` and `candidate-b/pass-1/` preserve each case, each provider-attempt sidecar, and `live-run.json`; the matching `.host.json` files bind each run to the frozen snapshot, device, package identities, and APK hashes.
- The candidate-B recovery case's `revision_race.service_conflict_observed` convenience field is `false`, but the retained actual `provider_turns[].tool_results[].application_outcome.status` is `conflict`, with `fresh_observation.captureRevision = 1` and no events before the second call. The convenience detector checked only the top-level status, not the nested application outcome. Original evidence/source were not edited after freeze; this report uses the actual nested tool result rather than that derived boolean.
- No formal full-suite scorer, full suite, independent review, `ready_for_review`, qualification PASS, checkpoint, plan/state update, commit, or push was performed or claimed.
