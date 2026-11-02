# S8.13-replan-a22 evidence

- `task_id`: `S8.13`
- `attempt_id`: `S8.13-replan-a22`
- `agent_id`: `S813PlumbingSilver22`
- `batch_id`: `S8.13-v3-interface-qualification-batch-21`
- current candidate snapshot: `S8.13-v3-interface-a22-freeze-29`
- review: `S8.13-cycle-1`, round 1 UNASSESSED

This attempt changes only test-harness/scorer plumbing and records. It does not change product token, guard, no-effect, F8 fixture, approved gate, schema/skill, or business semantics. The a21 freeze and retained partial run remain untouched. Main approved exactly `Main-S813-a22-diagnostic-3case` for the single proposal below.

## Deterministic checks

- `checks/plumbing-contract.json`: snapshot-28 identity/fixture/gate consistency check, PASS. Snapshot-28 was later superseded after its Android-test build exposed an unresolved local binding; see `snapshot-28/disposition.json` and `checks/build-failure-snapshot-28.json`.
- `checks/plumbing-contract-freeze-29.json`: freeze-29 identity/path/version contract and preserved fixture, skill, schema, and gate pins, PASS.
- `checks/selfcheck-report.json`: Python scorer behavior selfcheck, PASS (50 checks).
- `snapshot-29/preflight-static.json` and `snapshot-29/case-mapping.json`: static mapping preflight, PASS (16 dev, 8 held-out; 24 total).
- `snapshot-29/build.json`: BUILD SUCCESSFUL with app package `io.github.triet4p.aegisf.s813evidence` and test package `io.github.triet4p.aegisf.s813evidence.test`; the recorded APK hashes are bound to the current source manifest.
- `snapshot-29/device-preflight-host.json`, `path-identity-preflight.json`, and `device-preflight/**`: actual isolated-phone preflight PASS (`OK (3 classes; 4 tests)`), including the Kotlin stop-rule behavior cases, run-name round-trip, path-collision/traversal rejection, and debug-capture proof; hosted calls 0, base app unchanged, no device data clear.
- `snapshot-29/freeze.json`: Stage 1 freeze PASS; `paid_calls_made` is false. Freeze SHA-256: `c297db755a833608abc19c4f93d45973873283293d5fbb177573d105b418d63f`.
## Approved diagnostic results

- `diagnostic-proposal.md` records the exact scope; Main approved it as `Main-S813-a22-diagnostic-3case`, and that single run completed once against freeze-29. It was not retried or replaced.
- `diagnostic-results.md` records actual outcomes: `a8-nomatch-01` was `Incomplete:REQUIRED_EFFECT_MISSING` after one accepted masking repair (no verified effect); `a8h-nomatch-01` produced host-verified `no_effect/link_no_match` with no mutation but its final answer claims an unobserved duplicates-only query; `a8-link-02` suspended after one proposal but targeted draft 421 rather than the frozen mapping's 422, causing a run safety stop.
- The retained run has all 3 case records, 9/9 HTTP-200 exchanges, no transport failures, no debug-collection or run-validation error, and test-only synthetic capture with no header values or credential recording. The stop and case mismatch remain failures; no run was regraded, and qualification remains pending.
- Freeze-29 and the one diagnostic are not a qualification result. The a21 freeze and partial run remain untouched. Lifecycle is `paused_by_owner`; no further source, phone or provider work until explicit owner resume.
