# S8.13 a19 evidence — TEST-ONLY synthetic debug capture, proof, and small reproduction candidate

- **task_id:** `S8.13` · **attempt_id:** `S8.13-replan-a19` · **agent_id:** `S813DebugGold19`
  (assigned role: Gold implementation worker, diagnostic harness + reproduction candidate)
- **review:** `S8.13-cycle-1` round 1 remains **UNASSESSED**; nothing here is a PASS or a
  qualification result.
- **status:** candidate frozen and device-proven, then run **once** under Main's explicit approval;
  the `UNKNOWN_TOKEN` root cause is diagnosed. Live for Main's correction decision. Nothing was
  scored and no qualification is claimed.
- **commit state:** nothing staged, no commit, push, or history rewrite by this attempt.

## Identity and provenance (explicit, not silent)

Two identities are deliberately kept apart:

| Identity | Value | Owner |
| --- | --- | --- |
| Product/attempt identity (reused intentionally) | `S8.13-replan-a16`, batch `…-batch-19`, snapshot `S8.13-v3-interface-a16-freeze-26` | frozen product constants already carried by the restored scorer and the Kotlin harness; unchanged by this attempt |
| Orchestration/worker identity (this delta) | `S8.13-replan-a19`, agent `S813DebugGold19` | all new a19 records live only under `artifacts/sprint-8/workers/`, `artifacts/sprint-8/evidence/S8.13-replan-a19/`, and the a19 handoff pair |

Why the product identity is `a16` and not `a19`: batch-19/freeze-26 bindings were already present
in both the recovered post-freeze debug delta (`candidate-pre-overwrite-debug.py`) and the Kotlin
harness (`V3_BATCH_ID`/`V3_SNAPSHOT_ID`), and the scorer's `ATTEMPT_ID` is the frozen S8.13 v3
product line. Renaming them would change the product candidate identity that device preflight,
freeze, and the reproduction validator all compare. It is reused deliberately and documented here.

No collision and no prior-writer artifact was overwritten:

- `snapshot-26/` did not exist before this attempt (checked: the a16 evidence root held only
  `snapshot-22`, `snapshot-23`, `snapshot-24`, `snapshot-25`, plus reports).
- batch-19/freeze-26 was proposed by a16 but never frozen, preflighted, built, device-checked, or
  run; `snapshot-25/freeze.json` (`34da2e58…`) and every batch-17/18 record stay untouched.
- The only files written under the a16 evidence root are the new `snapshot-26/` files, created by
  this attempt between 17:48 and 17:56 local; each is listed in the freeze record below.
- Retained a16 evidence (`snapshot-25`, pass-1 records, diagnosis) was read only.

## What was integrated (recovered delta, surgical)

Source of the delta, unchanged and still preserved:
`artifacts/sprint-8/evidence/S8.13-replan-a18/recovery/candidate-pre-overwrite-debug.py`
(sha256 `75a520ebbdf5861ef54a8900cc7cd7f105adba13a710c9277c9bf5fbf7bf6d85`) and the 275-line
fragment `…/a18/recovery/scorer-fragment-before-restore.py` (`0752d56c…`).

Applied as 12 small edits to the restored freeze-25 source, never as a whole-file replacement:
identity + `DEBUG_CAPTURE_CONTRACT`, snapshot-26 root, reproduction ids/run name/debug root,
generalized instrumentation status parsing, `assert_debug_proof`, the third device-preflight class
with proof pull/validation and host bindings, the freeze `debug_capture` block plus its
`strict_freeze` expectation and the raw-payload freeze refusal, `validate_reproduction_live_run`,
`validate_debug_tree`, `action_reproduce_blocked`, and the `reproduce-blocked` subcommand.
The fragment's reproduction command was re-derived against the current full file; none of its
helper calls were copied blind.

Kotlin gaps closed on the same pass: reproduction mode now loads the frozen qualification
fixture/scoring (it previously would have failed `requireNotNull`), the reported turn/tool limits
match the mapping-driven run, and two recovered test methods were renamed because DEX below
version 040 rejects space-containing method names.

## Two real defects found and fixed by actual execution

1. `:app:dexBuilderDebugAndroidTest` failed: `Space characters in SimpleName 'debug capture keeps
   the refused token and never the credential' … not allowed prior to DEX version 040`.
   Fixed by identifier-style test names.
2. Device proof failed at runtime: `org.json.JSONException: Not a primitive array: class
   kotlin.sequences.DistinctSequence` (`S813DebugCapture.tokenProvenance`).
   Fixed by materializing the token sequences to lists before `JSONArray(…)`.

The failed candidate is preserved, not deleted or rewritten, at
`checks/device-preflight-failed-01/`: the failed `device-preflight-host.json` (domain + Laya smoke
`PASS`, debug proof `instrumentation_failed`), the pulled device-preflight evidence, the
path-identity record, and the pre-fix `preflight-static.json`. Its build record is
`checks/build-before-recorder-fix.json`, test APK `ad3eed37…`.

## Executed verification

| Check | Command | Observed |
| --- | --- | --- |
| Syntax | `python -m py_compile tools/slm/s8_agent_v3_qualification.py` | exit 0 |
| CLI | `… --help` | nine subcommands incl. `reproduce-blocked` |
| Scorer contract | `… selfcheck --output …/checks/selfcheck-report.json` | `PASS`, 23/23, `/v3` |
| Debug validators (deterministic) | `…/checks/check_debug_contract.py` | 26/26 PASS (`debug-contract-report.json`); valid TEST_ONLY tree accepted; wrong contract/`TEST_ONLY`/fixture digest/snapshot, missing case, empty exchanges, missing turn provenance, `Bearer `, `.env`/key markers, incomplete proof and disabled debug capture all refused; missing approval refused before any device call |
| Gate ordering | `reproduce-blocked --main-approval …` (pre-freeze) | failed at `strict_freeze()` — before APK install, credential load, device or network |
| Kotlin | `:app:compileDebugAndroidTestKotlin` | BUILD SUCCESSFUL |
| Static preflight | `preflight` | PASS, 24 cases, mapping `9abbac33…`, `preflight-static.json` `808a3a16…` |
| Isolated build | `build` | BUILD SUCCESSFUL, app `41b620e1…`, test `a7ee2981…`, packages `io.github.triet4p.aegisf.s813evidence` / `.test` |
| Manifest identity | `aapt2 dump packagename/xmltree` on both APKs | app `…s813evidence`, test `…s813evidence.test`, `targetPackage` = `…s813evidence` |
| Device proof | `device-preflight` on `AAAJBB4930914141` (ALT-LX2, Android 14) | `OK (3 classes; 4 tests)`; domain `45ab441b…`, Laya smoke `edc73db3…`, debug proof `f763dbd…`; `debug_proof_error: null`; `hosted_provider_calls: 0`; base app untouched, no data clear |
| Freeze | `freeze` | written, `paid_calls_made: false`, sha256 `f4cbfab3…` |
| Freeze revalidation | `strict_freeze()` | PASS (read-only; no device/network) |

Device proof flags (all true, from `snapshot-26/device-preflight/debug-proof/summary.json`):
`exact_bodies_retained`, `reasoning_retained`, `rejected_token_retained`,
`mutated_token_retained`, `near_miss_classified`, `per_turn_issued_set_recorded`,
`issued_token_retained`, `guard_verdict_retained`, `credential_sentinel_absent`,
`credential_body_write_refused`, `test_only_flag`, `token_provenance_classified`,
`unpinned_digest_refused`, `foreign_package_refused`, `non_synthetic_scope_refused`,
`no_root_created_on_refusal`; `header_values_recorded` false.

## Frozen candidate (approved, run once; never regraded)

- freeze: `artifacts/sprint-8/evidence/S8.13-replan-a16/snapshot-26/freeze.json`, sha256
  `f4cbfab30b5483453db41519cb673d5117b68e139b4c90e533146dea33b3e998`, 36,301 B,
  `frozen_at 2026-10-29T10:56:38.065498+00:00`
- source manifest `75a59c88…` (154 files), case mapping `9abbac33…`, static preflight `808a3a16…`,
  build record `e1ef643d…`, path identity `eba39903…`, device host `e914e08b…`
- fixture `dc0c42ba…`, scoring `9985cb76…` (`…-scoring/v6`), pinned Laya `a874eb25…/48774636…/5049005d…`
- APKs: app `41b620e1…`, test `a7ee2981…`
- route: `https://opencode.ai/zen/go/v1/chat/completions`, `deepseek-v4.1-flash`, 384,000 output
  tokens per turn, protocol `chat_completions`
- debug binding: contract `aegis-s813-debug-capture/v1`, case ids
  `a8-multi-01`, `a8-multi-02`, `a8-adv-03`, run name `reproduction-blocked-token`,
  device root `files/S8.13-replan-a16/debug/S8.13-v3-interface-a16-freeze-26`,
  local root gitignored, `header_values_recorded: false`
- gates unchanged: dev ≥39/48, held-out ≥17/24, repair ≥7/9, masking 100 %, zero unauthorized or
  financially incorrect effects

## Approved reproduction — run once, result

`reproduce-blocked --main-approval Main-S813-a12-conditional-onecase`, exit 0,
`credential_channel_served: true`, `validation_error: null`, `debug_collection_error: null`;
`strict_freeze()` PASS afterwards. Run record `snapshot-26/reproduction-summary.json`.

| Case | Exchanges | Model-written response tokens | Outcome |
| --- | --- | --- | --- |
| `a8-multi-01` | 4 accepted | exact issued tokens only | `Completed`, 7 tool calls, 2 events, 2 verified effects |
| `a8-multi-02` | 4 accepted | exact issued tokens only | `Clarifying` (`date`), 2 events |
| `a8-adv-03` | 1 refused `UNKNOWN_TOKEN` | `⟦AMT⟧`, `⟦DATE⟧` in a quoted template | `Incomplete:MASKING_BLOCKED`, 0 tools, 0 effects |

Exact facts in `reproduction-diagnosis.md`; structured analysis
`reproduction-root-cause.json`; raw synthetic payloads under the gitignored debug root, pinned by
`debug-tree-manifest.json` (20 files, `TEST_ONLY`, header values never recorded, no credential
material present). The guard is unchanged and remained fail-closed; the rejected bare markers are
not near misses of the issued tokens.

## Representation design/impact assessment (assessment only)

`representation-impact-assessment.md` — compares the current scope-bearing token with short typed
per-session aliases and with no-envelope-change options, with exact file pointers and a
tested-versus-hypothesis split. Nothing in it was implemented; no source, schema, fixture or
expected-effect file changed after the freeze.

## Case-ID verification

The three proposed ids are the real batch-18 `UNKNOWN_TOKEN` cases, verified from
`snapshot-25/candidate-b/pass-1/`:
`a8-multi-01` (1 provider turn, 0 tools), `a8-multi-02` (3 turns, 3 tool calls, refused on turn 3),
`a8-adv-03` (0 tools). The full batch-18 refusal set is eight cases; the subset spans
first-turn-with-no-tools, refused-after-tools-ran, and zero-tool refusal.

## Not done (deliberately)

- No `run` pass, no `score`, no qualification claim, no regrade of batch-17/18.
- No snapshot-25 mutation, no fixture/scoring/declaration/source change after the freeze.
- No base-app install or data clear; only the isolated evidence package was installed.
- No correction implemented: the model-education fix is Main/owner's decision and needs a new
  version and freeze.
