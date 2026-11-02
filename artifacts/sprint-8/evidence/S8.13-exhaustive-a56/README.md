# S8.13 exhaustive remaining-slot evaluation — attempt a56

TEST-ONLY evaluation evidence for attempt `S8.13-exhaustive-a56`. This attempt implements and
proves the explicit exhaustive-continuation mode; it contains **no hosted measurement**. All
provider interaction remains blocked until independent review, a fresh Bronze checkpoint and
Main's explicit Stage2 approval.

## Why this attempt exists

Attempt `S8.13-qualification-a55` measured six pass-1 cases and stopped on the frozen rubric
(`a8-link-02` proposed the tentative link to draft 421 instead of the mapped 422). The other 66 of
the 72 protocol slots were never attempted, and the raw debug tree it produced was rejected by the
host because per-case records lacked the `pass` field. This attempt fixes the recorder field and
adds one explicit frozen batch-continuation mode so every remaining slot gets a real attempt.

## Frozen identities (snapshot-61)

| Input | Value |
| --- | --- |
| Attempt | `S8.13-exhaustive-a56` |
| Snapshot | `S8.13-exhaustive-a56-freeze-61` |
| Freeze | `snapshot-61/freeze.json` — `513bee38db58e06bdedaa61ccb2546a9bd8a50fda40877275c4fd179460c9841` |
| Build record | `snapshot-61/build.json` — `fc73452c29b24e6d59918d9bcc3cb75a96e038cb824d4cd86a1da90336828c00` |
| Static mapping | `snapshot-61/case-mapping.json` — `45e19c92c60b860347d68dc51859f4eaf8dce2bba9d4117df36047f9e6fd6c27` |
| Static preflight | `snapshot-61/preflight-static.json` — `44a4f76b7e52a74385fcd530047d956740e36998b67509bf5305d4a705d8ce95` |
| Production app APK | `2982e0c5d72c2889b3eedd5caa65eb0184d6652a635846368390f1d08c0a3a35` (byte-equal to the accepted production APK) |
| New test APK | `f561a69372a4c2bb0feb7b9911235e87b515e7afd576835eb7c721b1ec4723be` |
| Qualification fixture | `dc0c42ba8ebc9742ada922ccba6831dcc4baa047efdeb098af23ccdf7dc9f868` (unchanged) |
| Qualification scorer | `1e023975ff9d03f0f6d51b834fad23bcde17cb5368f18db730f1b30b3fa676e7` (unchanged) |
| Device preflight host | `snapshot-61/device-preflight-host.json` — `044f8060fe1080ad897815d83114e3343ca223347e6b01aaefaf206733e0dd3b` |

Device `AAAJBB4930914141` / ALT-LX2 / Android 14.

## Exhaustive continuation contract

`aegis-s813-exhaustive-continuation/v1` (`mode: exhaustive_batch_continuation`), frozen inside
`freeze.json.exhaustive_continuation`:

- pass 1 = the 18-case suffix after the retained six; passes 2 and 3 = the full 24-case order;
- retained prefix `a8-multi-01, a8-multi-02, a8-search-01, a8-search-02, a8-link-01, a8-link-02`
  is read in place from `artifacts/sprint-8/evidence/S8.13-qualification-a55/snapshot-59/candidate-b/pass-1`
  and never rerun, copied or regraded;
- retained provenance digest `c0a7f4ace60c1fbd697eb5df576f16bb0c6de1dd2e12b737fbe86ac65ffa4cd2`
  binds the a55 freeze `c81ebf58…`, live-run `355d9d54…`, host record `9844a548…`, path identity,
  and each retained case/provider-sidecar digest;
- totals: 72 slots = 66 measured this attempt + 6 retained.

Only the **batch reaction** changes. Case-level rubric, semantic, refusal/expected-negative,
provider-protocol/tool, provider/network and masking failures are retained on their own case record
and the next isolated case still runs. The batch stops only for `credential_secrecy_breach`,
`isolation_failure`, `unreachable_infrastructure` or `capture_integrity_failure`. Fixtures,
expected rows, rubric verdicts, per-case turn/tool/wall-clock/output budgets and numeric gates are
unchanged.

Case isolation is structural: each case builds its own in-memory Room database, its own vault scope
and its own seeded fixture rows before its tools run.

## Capture-contract fix and actual device proof

`S813DebugCapture.identity()` now writes `pass` on every per-case `case.json`, exchange record,
turn record and `effects.json` (previously only `session.json` carried it). No validator was
loosened and no retained raw file was repaired.

The actual device preflight produced a complete two-case tree through the real recorder and the
host validator accepted it (`snapshot-61/device-preflight/capture-proof/`, session
`e2fc319e77574f7df9f9f36873fba1f406f5ecc80f2890e1e006ba2dd1f949fb`):

- every record carries `pass: "proof"`, `TEST_ONLY: true`, the capture contract, the isolated
  package and the frozen qualification fixture digest;
- `a8-link-02` retains the intended failing link `(313, 421, PROPOSED)` while `a8-laya-01` stays
  isolated with no links;
- `continued_after_failure: true`, `identity_pass_present_on_every_record: true`, zero hosted calls.

This is identity/structure proof, not model-behaviour evidence. The legacy debug proof summary is
`snapshot-61/device-preflight/debug-proof/summary.json` (`e82d8c5e…`), and the TEST-ONLY privacy
smoke report is
`artifacts/sprint-8/test-only/S8.13-exhaustive-a56/S8.13-exhaustive-a56-freeze-61/preflight-privacy-smoke/safe-response-location-smoke.json`
(`00d8b402…`).

## Layout

- `logs/stage1-preflight.log`, `logs/stage1-build.log`, `logs/stage1-device-preflight.log`,
  `logs/stage1-freeze.log`, `logs/strict-freeze-read.log` — console captures.
- `snapshot-60/` — intermediate build superseded before device work (retained-slot accounting defect
  found in self-review); not referenced by any freeze, no hosted call.
- `snapshot-61/` — the final Stage1 record above and `device-preflight/` evidence.
- `README.md` — this file.

Run, combined report and per-pass raw capture folders are intentionally absent until Main approves
Stage2.

## Remaining execution (after review, checkpoint and Main approval)

```powershell
$invocation = @(
  "--attempt-id", "S8.13-exhaustive-a56",
  "--snapshot-id", "S8.13-exhaustive-a56-freeze-61",
  "--evidence-root", "artifacts/sprint-8/evidence/S8.13-exhaustive-a56",
  "--exhaustive-from", "S8.13-qualification-a55"
)
uv run python tools/slm/s8_agent_v3_qualification.py run --pass-number 1 --main-approval <reference> @invocation
uv run python tools/slm/s8_agent_v3_qualification.py run --pass-number 2 --main-approval <reference> @invocation
uv run python tools/slm/s8_agent_v3_qualification.py run --pass-number 3 --main-approval <reference> @invocation
uv run python tools/slm/s8_agent_v3_qualification.py score @invocation
```

Pass 1 expects 18 case records and 18 debug case folders; passes 2 and 3 expect 24 of each. Each
pass keeps all provider sidecars and the raw TEST-ONLY tree under
`debug/S8.13-exhaustive-a56-freeze-61/pass-<N>/`. `score` writes `combined-72.json` (or
`combined-72-reviewed.json` after independent semantic review) with 72 per-slot provenance rows,
retained-origin digests and the unchanged gates.
