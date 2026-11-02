# S8.13-pre-impl-a4 evidence

Task `S8.13-pre`, attempt `S8.13-pre-impl-a4`, agent `S813PreInterfaceGold04` (Gold, full route).
Cycle 2 round-1 correction (linked from cycle 1, whose rounds 1-3 were `changes_requested`) of the
loaded `event-reading` example provenance defect for `docs/sprint-8-agent-interface-redesign.md` and
the current `docs/sprint-plans/sprint-8.md` S8.13-pre acceptance. Main explicitly authorized
`S8.13-pre-cycle-2` with two default assessment rounds and unchanged criteria. No commit, stage,
push, base-app data clear or hosted qualification run was performed. Frozen fixtures, scores and
earlier evidence are unchanged.

## Candidate snapshot

- Repository/worktree: `F:/ai-ml/aegis-f`; Git directory `F:/ai-ml/aegis-f/.git`; target
  `refs/heads/main`; base `14f313eab95213339b88500e3a98b0a17d7373c8`.
- `candidate-manifest.txt`: 61 candidate source paths under `app/`, `docs/` (excluding the foreign
  `docs/PLAN.md` and `docs/sprint-plans/*`), `tools/slm/` and `CHANGELOG.md`, one line per path
  (`MODIFIED`/`ADDED`/`DELETED`/`RESTORED`) with the worktree SHA-256.
  - manifest SHA-256 `449c9efb5cc4643b4ad3b8c70cf157dccb4f9659657b46c03dd6f550d9c3ddc6`
- `source-snapshot.txt`: `git status --porcelain` for the same paths with `git hash-object` for
  untracked entries (the a3 format).
  - snapshot SHA-256 `62821b83cdac954bf14811e3ba6772bc0985468f824c0c81b91e0526acf44f97`
- Both files are newline-normalised (LF) and sorted. The digests are plain SHA-256 of the file
  bytes (`sha256sum`), so the reviewer can re-capture the same paths and compare directly.

## Round-1 findings and corrections

1. **Temporal interval sign (HIGH).** `AgentToolRuntime.makeTimeAnchor` now rejects a same-day end
   at or before the start (`compareTimes(start, end) >= 0`) and accepts an increasing end; the
   date-order check is unchanged. Behavioral coverage: increasing, reversed, equal-end and
   reversed-date intervals.
2. **Truthful effects and classification causes (HIGH).** `AgentToolException` carries its own
   `effect`; the envelope forces `unknown` for `EFFECT_UNKNOWN`, `EFFECT_NOT_VERIFIED` and
   `OPERATION_PENDING`, and keeps `none` only for errors that prove no write. `classify_draft` now
   maps each denial reason instead of collapsing to model-unavailable, and consults the operation
   journal: a claim another attempt owns is `OPERATION_PENDING` (effect unknown), a settled journal
   entry this attempt cannot read is `EFFECT_NOT_VERIFIED` (effect unknown), and only a released
   claim/model failure is the manual `MODEL_UNAVAILABLE` path (effect none). The error can carry
   several field issues at once.
3. **Changed source boundaries (HIGH).** An expected-absence selection that overlaps an existing
   reading at different boundaries is refused with `STALE_OBSERVATION`
   (`selected_span_matches_existing_reading`), effect none, and a fresh capture observation; no
   second reading is stored. The runtime pre-check only provides that specific guidance: the atomic
   guard is `NoteEventDao.findOverlapping` called inside `EventContextRepository.recordEvent`'s
   `database.withTransaction` block, before the insert. Because Room serialises write transactions,
   a competing writer's different-boundary reading is visible to the second transaction, which
   returns `Changed`; the two cannot both commit. Disjoint source-grounded occurrences remain two
   independent events, and merchant sub-span handles remain valid only as `merchant_ref`.
   Behavioral proof: `NoteEventContextTest` writes directly through the repository (bypassing the
   runtime) and asserts the overlap is `Changed` with one row while a disjoint span stores a second
   row.
4. **Destructive null correction (HIGH).** A correction of an existing reading refuses a null for any
   amount, anchor component or merchant the reading already holds (`MISSING_REQUIRED_MEANING`,
   `observed_reading_field_preserved_or_replaced`, one issue per erased field), effect none. Nulls
   for fields that were already absent still drive partial-reading resume; explicit removal has no
   wire spelling and becomes clarification/manual review.
5. **Review-status schema mismatch (MEDIUM).** `tools/slm/s8_agent_tools_v3.json` now declares
   `review_status` as the non-null enum `PENDING|CONFIRMED|DISMISSED|ALL`; the runtime requires that
   enum (`ALL` maps to no stored filter), rejects null/missing, and the description no longer
   offers null. Provider lowering derives from the same declaration.
6. **Immutable historical inputs (HIGH).** `tools/slm/s8_agent_tools_v2.json` and
   `tools/slm/s8_agent_runtime_skills.json` are back in the worktree with the pre-deletion
   S8.13-replan bytes: schema `aegis-f-s8-agent-tools/v2` and
   `aegis-f-s8-agent-runtime-skills/v2` (event skill version 3, corrected absolute-date/token
   rules). Provenance: the deleted worktree copies survived as the build-generated asset copies
   from 2026-10-28 15:28, before `app/build.gradle.kts` was changed (20:26) and before a1 removed the
   files; both generated copies (`s8-agent-runtime`, `s813-qualification`) were byte-identical.
   These bytes are *not* HEAD's older `skills/v1` + earlier tools revision, so the files are
   restored from the surviving pre-deletion copies, not from `HEAD`. Historical consumers were
   exercised: `tools/slm/s8_agent_runtime_v3_preflight.py` now exits `PASS` with
   `hosted_calls: 0` (it validates the v2 declarations/skills, the frozen fixtures, docs and the v3
   projection; see `v2-preflight.json`). No historical script, fixture, score or gate was edited.
   The Git index still carries a staged deletion of these two paths (not unstaged: index changes
   are outside this worker's authorization); Main/checkpoint must stage the restored worktree
   content.
7. **Classification replay receipt (MEDIUM).** A settled `classify_draft` now returns its journaled
   `operation_ref`, so `replay_operation` can select the exact recorded command; a behavioral test
   replays it (`replayed: true`, `effect: unchanged`).

### Cycle-2 correction (loaded example provenance)

The cycle-1 round-3 review found that the loaded `event-reading` example in
`tools/slm/s8_agent_runtime_skills_v3.json` said the capture observation issued `amt_a`/`anchor_a`
and then used them with a `select_source` `src_a`. There is no such path: capture-page aliases are
bound to `capture:<id>`, `select_source` issues occurrence-bound `source:<cap>:<start>:<end>`
aliases, `record_event`/`make_time_anchor` enforce that binding (`REFERENCE_KIND_MISMATCH`), and a
no-event capture issues no anchors. The enforcement is correct (design §4.1/§6.5) and unchanged.
The skill content was corrected instead: the prerequisite, tool-order, normal branch and recovery
guidance now say capture-page value aliases are display-only and the occurrence's aliases come from
`select_source` (with the anchor built by `make_time_anchor` from those grounded aliases); both
examples use that flow. `event-reading` is version 2, `WorkflowSkillCatalog.EXPECTED_VERSIONS` and
its cache identity/tests are updated, and the changed skill content changes the provider cache
identity. No runtime, registry or tool behavior changed.

Executable proof added to `AgentToolRuntimeTest`: the corrected flow (observe → select_source →
source-issued amount/date/time aliases → make_time_anchor → record_event) records and verifies one
reading; a capture-only amount alias and a capture-only date alias are refused with
`REFERENCE_KIND_MISMATCH` (effect none); a fabricated handle is `UNKNOWN_REFERENCE`; nothing is
stored until the valid source-issued call.

Also updated: `docs/sprint-8-application-tools.md`, `docs/sprint-8-agent-contract.md`, `CHANGELOG.md`,
and the project lesson/decision memory. `docs/sprint-8-agent-interface-redesign.md` (the approved
criterion) was not edited.

## Exact verification (this attempt)

```powershell
cmd /c "gradlew.bat --no-daemon --max-workers=2 --console=plain :app:compileDebugKotlin :app:compileDebugUnitTestKotlin"
# BUILD SUCCESSFUL in 1m 9s
cmd /c "gradlew.bat --no-daemon --max-workers=2 --console=plain :app:compileDebugAndroidTestKotlin"
# BUILD SUCCESSFUL in 34s
cmd /c "gradlew.bat --no-daemon --max-workers=2 --console=plain :app:compileDebugKotlin :app:testDebugUnitTest --tests io.github.triet4p.aegisf.agent.AgentToolRuntimeTest --tests io.github.triet4p.aegisf.data.NoteEventContextTest --tests io.github.triet4p.aegisf.data.WorkflowCommandServiceTest --tests io.github.triet4p.aegisf.agent.BoundedWorkflowExecutorTest --tests io.github.triet4p.aegisf.agent.S811ContextCacheTest --tests io.github.triet4p.aegisf.agent.S810DurableRunHistoryTest --tests io.github.triet4p.aegisf.agent.WorkflowSkillCatalogTest --tests io.github.triet4p.aegisf.provider.GeminiGenerateContentAdapterTest --tests io.github.triet4p.aegisf.provider.OpenAiChatCompletionsAdapterTest --tests io.github.triet4p.aegisf.provider.OpenAiResponsesAdapterTest"
# BUILD SUCCESSFUL in 1m 28s
python tools/slm/s8_agent_runtime_v3_preflight.py --output artifacts/sprint-8/evidence/S8.13-pre-impl-a4/v2-preflight.json
# {"result":"PASS","hosted_calls":0,"case_counts":{"dev":16,"heldout":8,"total":24}, ...}
# after the cycle-2 skill/doc change, re-run to a fresh path: v2-preflight-cycle2.json -> PASS, hosted_calls:0
```

Observed test totals from `unit-results/TEST-*.xml`:

| Class | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| `AgentToolRuntimeTest` | 23 | 0 | 0 | 0 |
| `NoteEventContextTest` | 8 | 0 | 0 | 0 |
| `WorkflowCommandServiceTest` | 53 | 0 | 0 | 0 |
| `BoundedWorkflowExecutorTest` | 13 | 0 | 0 | 0 |
| `S810DurableRunHistoryTest` | 8 | 0 | 0 | 0 |
| `S811ContextCacheTest` | 11 | 0 | 0 | 0 |
| `WorkflowSkillCatalogTest` | 3 | 0 | 0 | 0 |
| `GeminiGenerateContentAdapterTest` | 15 | 0 | 0 | 0 |
| `OpenAiChatCompletionsAdapterTest` | 12 | 0 | 0 | 0 |
| `OpenAiResponsesAdapterTest` | 13 | 0 | 0 | 0 |
| **Total** | **159** | **0** | **0** | **0** |

`AgentToolRuntimeTest` and `NoteEventContextTest` are real Room/service behavior (Robolectric
in-memory `LedgerDatabase`), not source/wiring tests. The new/changed cases are: same-day interval
ordering; overlapping-boundary refusal; stored-field erasure refusal; applied-then-unreadable receipt
(`EFFECT_NOT_VERIFIED`, effect unknown, no duplicate row); pending replay (`OPERATION_PENDING`,
effect unknown, no new row); rejected null `review_status` plus `ALL`; settled classification receipt
+ replay + pending claim; the repository-level transactional overlap refusal (direct
`EventContextRepository` call, one row, disjoint span still stores); and the corrected documented
event-reading flow, where source-issued aliases record and verify while capture-only and fabricated
aliases are refused with no write.

## Limits and non-claims

- The a3 full unit suite (564/0/3) and its isolated-device and hosted-phone evidence are **not**
  re-run or superseded here; they remain the prior candidate's evidence. This attempt ran the
  targeted 159-test set above plus the historical non-LLM preflight.
- No hosted/paid provider call was made. The changed paths do not require a paid rerun; the a3
  bounded phone smoke exercised extraction, replay and clarification resume, which the new guards
  do not alter for grounded corrections (observed aliases are passed) and which the unit boundary
  tests now cover directly. No device install/instrumentation was performed this attempt, so no new
  on-device observation is claimed.
- No commit/stage/push; the base app and local assets were untouched. Foreign worktree changes
  (`.agents/memory` additions made before this attempt, plans, Sprint 7 evidence) were preserved;
  this attempt appended its own lesson/decision entries rather than rewriting foreign content.
- `S813QualificationInstrumentedTest` still uses historical v2 fixture shapes and was not run as a
  qualification; no numeric qualification gate is measured or claimed.
- The staged deletions of the two restored historical inputs remain in the index by reviewer
  instruction; the worktree files are present with the verified bytes.
