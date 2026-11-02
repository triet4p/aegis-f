# S8.13 a18 recovery evidence — exact restoration of the freeze-25 scorer

- **task_id:** `S8.13` · **attempt_id:** `S8.13-replan-a18` · **agent_id:** `S813RestoreGold18`
  (assigned role: Gold implementation worker; source recovery only)
- **review:** `S8.13-cycle-1`, round 1 remains **UNASSESSED**; nothing was submitted for assessment.
- **outcome:** exact byte recovery **verified**; live source restored; isolated syntax/`--help`/`selfcheck`
  checks pass. No hosted call, no device action, no freeze, no regrade, no commit/stage/push.

## Result

| Object | Bytes | Lines | sha256 | xxh32 tag |
| --- | --- | --- | --- | --- |
| `tools/slm/s8_agent_v3_qualification.py` (restored, live) | 209,304 | 3,943 | `1cb6bff5e1a502f489d7db950219d910febf65312a1fc008c3281a0d5dfa6584` | `2B8D` |
| `candidate-freeze25.py` (staging copy of the same bytes) | 209,304 | 3,943 | `1cb6bff5e1a502f489d7db950219d910febf65312a1fc008c3281a0d5dfa6584` | `2B8D` |
| `candidate-pre-overwrite-debug.py` (separate, later delta) | 214,853 | 4,036 | `75a520ebbdf5861ef54a8900cc7cd7f105adba13a710c9277c9bf5fbf7bf6d85` | `7023` |

The restored sha256 equals the freeze-25 record's `source_sha256`
(`artifacts/sprint-8/evidence/S8.13-replan-a16/snapshot-25/freeze.json`,
`frozen_at = 2026-10-29T09:30:18.057081+00:00`, file sha256
`34da2e58a9f2ae3b49c2fa96ee9b1cd4bb62e42ca11130d9d3b892abde4dd14e`).

## Mandatory set acknowledged before work

Read in full: `artifacts/sprint-8/handoffs/S8.13-replan-a17.md`,
`artifacts/sprint-8/detailedhandoffs/S8.13-replan-a17.md`; the a16 handoff pair was used on demand.
Read every regular file under `C:/Users/admin/.agents/rules/`
(`changelog.md`, `codex-shell.md`, `git.md`, `markdown.md`, `omp-shell.md`, `python.md`,
`subagent-routing.md`) and `skill://implement-atomic-task` (+ its task-summary template).

State inherited and re-checked, not assumed:

- a17 correctly reported that its own freeze manifest check found the scorer + three Kotlin files
  mismatched; it **incorrectly** described freeze-25 as "frozen and unrun". Observed here: batch-18
  pass-1 records exist for snapshot-25 —
  `.../snapshot-25/candidate-b/pass-1/live-run.json` carries
  `batch_id = S8.13-v3-interface-qualification-batch-18`,
  `snapshot_id = S8.13-v3-interface-a16-freeze-25`,
  `started_at = 2026-10-29T09:40:52.956201Z`, `finished_at = 2026-10-29T09:48:05.338671Z`.
  No regrading, rerun or new measurement was performed here.
- Qualification batches 17/18 remain **NOT QUALIFIED**; their pass-1 evidence was not touched.

## Preservation performed before any write

Read-only hashes at start (unchanged at end):

| Object | Bytes | sha256 |
| --- | --- | --- |
| `artifacts/sprint-8/evidence/S8.13-replan-a17/recovery/scorer-pre-edit.cpython-313.pyc` | 245,106 | `25103a22ffa918a16ef192a88e188665ec2ad8f944d99f359f78495d140a20ee` |
| `artifacts/sprint-8/evidence/S8.13-replan-a17/recovery/scorer-current-fragment-on-disk.py` | 14,307 | `0752d56c65adf8458469ca2af9650e2db8087a42e8592c46a5a03719330d3237` |
| `artifacts/sprint-8/evidence/S8.13-replan-a18/recovery/scorer-fragment-before-restore.py` | 14,307 | `0752d56c65adf8458469ca2af9650e2db8087a42e8592c46a5a03719330d3237` |
| `artifacts/sprint-8/evidence/S8.13-replan-a16/snapshot-25/freeze.json` | 35,322 | `34da2e58a9f2ae3b49c2fa96ee9b1cd4bb62e42ca11130d9d3b892abde4dd14e` |
| `tools/slm/__pycache__/s8_agent_v3_qualification.cpython-313.pyc` (live cache, untouched) | 245,106 | `25103a22…` |

The fragment was copied out again immediately before the restoration so the pre-restore live bytes
remain available even if the a17/a16 copies are ever moved.

## Sources (all owner-authorized, structured extraction only)

Session root:
`C:/Users/admin/.omp/agent/sessions/--F--ai-ml-aegis-f--/2026-10-25T07-28-29-207Z_01a171f5-fe17-7331-b95b-b3bff65c7927/`

| Session | Target-file mutation calls | First line | Last line | Failed (skipped) |
| --- | --- | --- | --- | --- |
| `S813V3QualificationSilver10.jsonl` | 135 | 629 | 3400 | 15 |
| `S813PrivacyFallbackSilver12.jsonl` | 63 | 627 | 3012 | 11 |
| `S813MetadataQualificationGold13.jsonl` | 21 | 353 | 556 | 0 |
| `S813ResumeGold16.jsonl` | 57 | 403 | 1980 | 1 |

Only tool calls whose path/section resolves to `tools/slm/s8_agent_v3_qualification.py` were read as
payloads; results were joined by `toolCallId`. No credential, header, config or `.env` content was
read. Per-session counts are also in `session-index.json`.

Key anchors:

- Initial complete write: `S813V3QualificationSilver10.jsonl:629`, entry `ce955be2`,
  `2026-10-28T21:09:28.039Z`, call `call_ca4a50c06a914776abe056ba44620afd|fc_0c911cd4243ff001016ae263fd7c70819181570f1821baac3a`,
  91,215 characters, result tag `D626`.
- Freeze-25 producing pair: `S813ResumeGold16.jsonl:1210`
  (`call_01_7bDsnMajQqFcfJVgOpyU4137|1ca3dcd1-…` and `call_02_euMjYxXEuEMuBBer32R62053|9db0bacf-…`,
  `2026-10-29T09:25:22.201Z`, batch-17→18 and snapshot-24→25 identifiers, post-state sha `1cb6bff…`).
  An a16 add at `09:30:33Z` (`inventory: adjudication_version`) and its revert at
  `S813ResumeGold16.jsonl:1496` (`2026-10-29T09:37:46.993Z`, call
  `call_00_3XjB6Ae9g7VElhNIsPvM1668|7deaa31d-…`) return to the identical bytes, giving a second
  independent sequence point with the same snapshot hash.
- Destructive write: `S813ResumeGold16.jsonl:1980`,
  `call_00_ET_4QRb3D9RPwpUtzqhMb936205|780c29f4-…`, 14,307 chars → fragment sha `0752d5…`. Recorded
  as provenance; **not** replayed over the recovered source.

## Method

`replay_scorer.py` (kept in this directory) extracts the calls and replays only successful ones in
transcript order, verifying every resulting file state with the four-hex revision tag the tool
returned (`tag == xxh32(file bytes) & 0xFFFF`, uppercase) and the section tag the next call anchored
to.

Rules learned from the recorded results themselves (documented in `omp://tools/edit.md` and confirmed
by the tool's own warnings):

1. Line numbers address the original tagged snapshot, not earlier hunks of the same call; insert
   (`PUT >N`/`PUT <N`/`PUT >$`) and replace (`PUT N.=M`) entries compose positionally.
2. `replacement boundary echo` auto-repair drops leading body rows that duplicate the rows directly
   before the range and trailing body rows that duplicate the rows directly after it. Both variants
   were observed in real results (warning at `...Silver10.jsonl:649` for trailing rows; at `:854`
   for a leading row).
3. `CUT N*` block cuts either resolved or failed; every occurrence in these sessions failed and was
   skipped, never replayed.
4. Result headers of multi-file calls name the first section (a Kotlin file here), so three target
   post-tags (`seq 161/163/165`) are header artifacts; the target sections' states are corroborated
   by the next call's exact pre-tag (`54DB`, `835B`, `E80A`) instead.
5. Two replace-mode edits carried `replace_all: true` (`S813ResumeGold16.jsonl:460`, `:1172`); both
   were applied as replace-all, consistent with their results.

Verification totals: 276 selected mutation calls → 249 successful applications
(171 hashline patches, 76 replace-mode edits, 2 whole-file writes) and 27 failed calls skipped.
168 applied operations have both an expected pre-tag and post-tag; all matched. Together with the
final sha256 equality to the frozen record, the chain proves no unrecorded scorer mutation occurred
across the replayed span.

## Independent corroboration from the preserved `.pyc` (not executed)

`scorer-pre-edit.cpython-313.pyc` header: magic `f30d0d0a`, flags `0`, source mtime
`2026-10-29T09:37:47Z`, source size **209,304** — exactly the restored candidate's size and the
timestamp of the 09:37:46.993Z edit. Loading the payload with `marshal.loads` (data only, never
executing it) and comparing against a fresh in-memory `compile()` of `candidate-freeze25.py`:

- 183/183 nested code objects have identical `co_code`, `co_consts`, `co_linetable`, argument and
  position streams;
- the raw marshal payload is 245,090 B recorded vs 245,120 B freshly compiled, with the first byte
  difference inside a constant-reference encoding while every code object compares equal — a compiler
  build/reference-layout difference, not a semantic difference. Byte-level pyc equality is therefore
  **not** claimed; the sha256 equality to the freeze record is the authoritative byte proof.

## Checks actually executed after restoration

Cache was redirected with `PYTHONPYCACHEPREFIX=artifacts/sprint-8/evidence/S8.13-replan-a18/recovery/pycache-check`
so `tools/slm/__pycache__` (and the preserved pyc artifact) stayed untouched.

| Command | Observed |
| --- | --- |
| `python -m py_compile tools/slm/s8_agent_v3_qualification.py` | exit 0, no output |
| `python tools/slm/s8_agent_v3_qualification.py --help` | exit 0, all eight subcommands listed (`preflight`, `build`, `device-preflight`, `freeze`, `run`, `diagnostic-onecase`, `score`, `selfcheck`) |
| `python tools/slm/s8_agent_v3_qualification.py selfcheck --output …/recovery/selfcheck-report.json` | `result PASS`, 23 checks, 0 failed, `adjudication_version s8.13-quality-vs-safety-adjudication/v3` (`selfcheck-report.json`) |

Not run, deliberately:

- `preflight` — it writes a new case mapping under the retained `snapshot-25` evidence root, which
  would mutate retained freeze evidence. The assignment authorizes documenting an intentional
  mismatch instead; the three Kotlin sources are still the post-freeze debug versions, so a preflight
  manifest comparison would report those by design.
- Any `run`/`score`/hosted call, device or phone operation, and any new freeze.

Post-restore per-entry check of `freeze.json["source_sha256"]`: **149/152 match**; the only remaining
mismatches are the three post-freeze debug Kotlin files
(`S813LayaProjectionSmokeInstrumentedTest.kt`, `S813QualificationInstrumentedTest.kt`,
`S813V3QualificationPreflightInstrumentedTest.kt`). The scorer is back to its frozen bytes.

## Secondary candidate (kept separate, not the restoration target)

`candidate-pre-overwrite-debug.py` is the state immediately before the destructive write
(seq 274, `S813ResumeGold16.jsonl:1968`, `2026-10-29T10:02:18.264Z`), derived deterministically from
the verified freeze-25 state plus the recorded successful a16 debug edits:
214,853 B / 4,036 lines, sha256 `75a520ebbdf5861ef54a8900cc7cd7f105adba13a710c9277c9bf5fbf7bf6d85`,
tag `7023`. It is labelled with its own hash and must never be presented as the freeze-25 source.

## Files in this directory

- `replay_scorer.py` — extraction + replay engine (deterministic; reruns produce the same hashes).
- `replay-log.json` — per-operation state log: tags, sha256, sizes, repairs, failure list.
- `session-index.json` — per-session call counts, line ranges, key call IDs.
- `summary.json` — machine-readable result, provenance and checks.
- `candidate-freeze25.py` — the recovered freeze-25 source (exact).
- `candidate-pre-overwrite-debug.py` — separate later delta (see above).
- `scorer-fragment-before-restore.py`, `replayed-final.py` — the pre-restore live fragment (both
  `0752d5…`), preserved for provenance.
- `selfcheck-report.json` — selfcheck output.
- `pycache-check/`, `__pycache__/` — redirected byte-code caches created by the checks; the live
  `tools/slm/__pycache__` was not written.

Reproduce: `python artifacts/sprint-8/evidence/S8.13-replan-a18/recovery/replay_scorer.py`.
