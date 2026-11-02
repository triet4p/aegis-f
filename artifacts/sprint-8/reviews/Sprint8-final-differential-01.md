# Final Differential Review — Sprint 8 (Sprint8-final-differential)

- **task_id:** Sprint8-final-differential
- **attempt_id:** Sprint8-final-differential-a102
- **agent_id:** Sprint8FinalReview102
- **Role:** independent final differential reviewer (read-only; no source edits, no runs, no checkpoint)
- **Date:** 2026-11-02

## Status: SUPERSEDED — pending new snapshot (not final Sprint 8 acceptance)

Mid-review an owner scope correction arrived (message from Main): the owner explicitly authorizes local Laya to read RAW text; Main accepts removing the OTP/alphanumeric suggestion-safety pre-inference blocking across the local draft/context path, while hosted masking and financial authority remain unchanged. Per Main's direction this differential is assessed against **old HEAD `413b1e7d3bc54e6fbd1f2afbe211ec9005039104`** and **cannot qualify the upcoming source revision**. Further old-candidate assessment was stopped. Main will implement the authorized raw-local cutover under the existing S8.14 cycle remaining round 2, then checkpoint and run a fresh differential. Review counts are preserved (no reset).

**Verdict against old snapshot: no actionable cross-task findings found within assessed scope; outcome recorded as SUPERSEDED/pending, not a sprint-acceptance PASS.** Nothing here blocks the cutover; the findings below are informational context for round 2.

## Criterion and scope of this (superseded) assessment

- **Criterion:** final Sprint 8 differential review under the owner-revised production-focused S8.14 contract (`docs/sprint-plans/sprint-8.md:91-94`): hunt cross-task risks across source recency/trusted refs, Responses continuation/privacy state, production tool budget, suggestion-safety manual envelope/executor/UI, persistence/replay/offline authority, docs/runtime contract; no rerun of passed checks, no S8.13 numeric-gate reopening, no hosted/API calls, targeted source inspection only for concrete concerns.
- **Base HEAD (old):** `f8dee10eb07e5ff90d7399762c5f3b18ef7a5d5a`; **checkpoint HEAD (old):** `413b1e7d3bc54e6fbd1f2afbe211ec9005039104`, parent `f8dee10…`, message `feat(s8.14): prove integrated privacy, authority, and phone experience`, exactly the 21 reviewed snapshot paths.
- **Evidence base:** worker record/handoff `artifacts/sprint-8/workers/S8.14-production-a99.md`, `artifacts/sprint-8/handoffs/S8.14-production-a99.md`; independent review `artifacts/sprint-8/reviews/S8.14-production-review-01.md` + manifest SHA-256 `27aa21bbbbf11dab4eb5f217f659ab44b53ee763a90c4fcc2d064be3c7801c6b`; checkpoint `artifacts/sprint-8/checkpoints/S8.14-production-checkpoint-01.md`; a97 paused handoff `artifacts/sprint-8/handoffs/S8.14-integration-a97-paused.md` and its inherited evidence roots; a99 evidence `artifacts/sprint-8/evidence/S8.14-production-a99/`. Histories not re-read (not needed).

## Differential probes performed and results (old snapshot, byte-verified)

1. **Snapshot integrity (worktree vs. checkpoint).** Recomputed SHA-256 for all 21 committed paths in the current worktree: all match `S8.14-production-checkpoint-01.md` exactly. The removed diagnostic instrumented test (`S814LayaPersistedDraftDiagnosticInstrumentedTest.kt`) and the a99-only `--local-laya-draft-id` runner mode are absent from the worktree, and no committed doc references either. No drift between reviewed candidate and committed source.
2. **Tool-budget headroom does not weaken repetition safety.** Production default `WorkflowRunLimits` is `maxToolCalls=24`, `maxRepeatedIdenticalCalls=3` (`BoundedWorkflowExecutor.kt:88-100,92`); all production constructions (`QuickCaptureScreen.kt:266-269`) rely on the default. Every explicit limit (16/12/8) lives in its own historical test/instrumentation fixture, not a production path. `LOOP_DETECTED` still binds identical repeats before budget exhaustion, so the 16→24 raise is pure headroom as the owner revision intended.
3. **Suggestion-safety mapping consistency.** The runtime's clarification branch (`AgentToolRuntime.kt:993-1007`) re-checks the same `isBlockedForSuggestionSafety` classifier over the same draft field (`classificationDescription()`) that `WorkflowCommandService` used at `WorkflowCommandService.kt:849-857`; the safety envelope carries no draft content, no operation row, no classification/ledger/link change (retained `safety-refusal-draft23` diagnostic flags all unchanged; committed regression `AgentToolRuntimeTest.kt:841-905` also asserts the stored OTP is absent from the envelope).
4. **Responses continuation / privacy state.** Continuation keeps opaque `encrypted_content`, sends required `summary: []`, drops plaintext `content`/`text` (`OpenAiResponsesAdapter.kt:444-457`); resent `function_call_output` items carry runtime-masked upstream results; the complete issued mask-token inventory is re-rendered into every turn (initial, clarification resume, assisted follow-up, compacted and tool-result continuation: `BoundedWorkflowExecutor.kt:727,812,891,937,1132-1139`) after masking, preserving the stable cache prefix.
5. **Recency-ordering consumer cutover.** `CaptureMessageDao.findByIds` newest-first ordering (`:31-32`) is consumed directly only by `WorkflowQueryService.sourceExcerptsByIdsNewestFirst`; the other `findByIds` callers build id-keyed maps, order-independent. No hidden ordering dependency elsewhere.
6. **Schema/migration obligation.** None of the 21 committed paths touches an entity/schema file; the DAO change is query-only, so no new populated-data migration proof arises at this gate.
7. **Docs/runtime contract coherence.** Executor doc (cap 24, suggestion-safety clarification row, reserved `MODEL_UNAVAILABLE`: `docs/sprint-8-agent-executor.md:26-31,160-162`), provider-adapters doc, smoke README (exactly the shipped `--capture-only`/`--local-only`/`--hosted-primary-replay-slice` modes), and CHANGELOG claims all match committed code and evidence; no ungrounded hosted-measurement claim.
8. **Owner-scope compliance of the completed candidate.** a99 verification is credential-free (`hosted_credential_required=false`, `provider_requests=0` in retained runner outputs); no new hosted/API/benchmark run; S8.13 measurement evidence untouched.

## Findings

- **No actionable cross-task findings** in the assessed old-snapshot scope; nothing unsafe was introduced by the a97→a99 accumulated integration as reviewed.
- **INFO (non-blocking, for round 2 context):** With the safety case fixed, a draft whose stored description is blank still returns `NeedsClarification("description")` (`WorkflowCommandService.kt:840-845`) and maps through the runtime fallback to the `MODEL_UNAVAILABLE` manual envelope with issue "The local Laya model did not produce a suggestion" (`AgentToolRuntime.kt:1005-1007, manualClassification`). Pre-existing behavior narrowed but not removed by the a99 fix; it neither fabricates a suggestion nor violates safety. The owner's raw-local cutover will revisit the classifier path anyway; address or dismiss there.
- Unverified/nonblocking: builds/tests were not rerun (per assignment, passed gates not repeated); retained a99 post-fix outputs were read as recorded evidence. All 21-path byte equality was verified by hash recomputation (observed here).

## Handoff to Main

- Main's authorized raw-local cutover proceeds under S8.14-cycle-1 remaining round 2 (count preserved); then Bronze exact-snapshot checkpoint and a **fresh final differential review** against the new snapshot. This file is the compact placeholder for the superseded old-HEAD assessment.
