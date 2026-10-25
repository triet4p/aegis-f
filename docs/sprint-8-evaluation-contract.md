# Sprint 8.2 workflow evaluation contract

**Status:** Frozen candidate contract for S8.3 trial planning; it is not a model qualification, a shipping profile decision, or an owner-approved numeric release gate. The executable fixtures and scorer under `tools/slm/s8_workflow_*` are the source of case identities, exact arguments, and denominators.

## Contract provenance and scope

The missing product-contract pointer in the S8.2 dispatch was established by Main as a new deliverable. This document maps trial inputs to the current implementation, not to planned names from `docs/PLAN.md` or the former Sprint 5 Q&A benchmark.

Implemented contracts inspected:

- `WorkflowCommandService` and `WorkflowCommandTypes.kt`: `UpdateEventRequest` / `NoteEventInterpretation`, `ProposeLinkRequest`, `AttachLinkRequest`, `ClassificationRequest`, and `WorkflowCommandOutcome`; the trusted `WorkflowCallerScope` is an argument supplied by host code, never by model text.
- `WorkflowQueryService.draftCandidates(DraftCandidateQuery)` and `WorkflowQueryTypes.kt`: explicit pending-state / possible-duplicate filters and bounded `QueryPageRequest` (`limit` 1–500, `offset` >= 0). This is not arbitrary merchant/amount/time search or SQL.
- `NoteEventEntity.kt` and `EventContextRepository.kt`: one span per event; optional values remain null; incomplete reads carry an unresolved reason; source and event revisions gate writes. Active links are distinct from interpretation state. `NO_MATCH` is produced by stored rejected-link state, not an agent-set status field.
- `docs/sprint-5-workflow-contract.md` §2.5 and `docs/sprint-5-event-context-persistence.md`: Laya's existing category suggestion is review-only; link evidence is app-computed; agent link attachment remains subject to current evidence policy. Confirmation, dismissal, ledger edits/deletion, and category changes are user-only or not exposed through this fixture schema.
- `docs/sprint-8-model-runtime-research.md` and `artifacts/sprint-8/evidence/S8.1/source-verification.md`: candidate identities, limits, and native call-format sources. S8.1 is provenance, not capability evidence.

The sole executable tool set is `tools/slm/s8_workflow_tools.json`. It excludes write/confirm/delete/SQL tools. Tool argument names and bounds map to the existing API types and application methods; no hypothetical S8 adapter symbol is claimed to exist. `callerScope` is deliberately absent from model arguments. App outcomes remain the implemented sealed outcomes: applied, no-change, needs-clarification, conflict, not-found, denied, cancelled, or failed.

## Frozen inputs and coverage

- `tools/slm/s8_workflow_cases.json`: frozen scored set.
- `tools/slm/s8_workflow_heldout.json`: independently phrased held-out cases, not used to author the frozen prompt. Both files have separate IDs and separate denominators.
- `tools/slm/s8_workflow_system_prompt.txt`: candidate shared task instructions; rendered with each profile's native format.
- `tools/slm/s8_workflow_profiles.json`: candidate-by-candidate capability labels, numeric proposed targets, score rules, and denominator pointers.

The suite covers multiple events, explicit and relative/missing time, bounded candidate reads, propose-then-attach and its authorization boundary, exact Laya request, no-match and ambiguity, unknown tool, and injected untrusted text. Expected calls carry exact required arguments (including revisions, spans, idempotency keys, limits, nulls, and ordered multi-call sequences). Cases distinguish an answerable call, a valid clarification, and a refusal. Unknown tools, partial/malformed calls, extra calls, missing calls, bad arguments, and parser errors fail; none are silently dropped.

Before trials, every fixture must be paired with the actual synthetic app fixture it addresses: stored capture/draft IDs and revisions, exact capture text and Unicode span offsets, and expected application outcome/effects. Cases may not be edited to fit a generated answer. The existing app has no implemented generative tool adapter; S8.2 freezes its interface contract but does not claim a tool-call side effect occurred. S8.3 evidence must contain actual application outcomes/effects; schema-valid output alone does not pass.

Three agentic candidate families have frozen source-backed tool formats: MiniCPM v4.7 and Qwen 3.5 use complete XML tool-call envelopes; Gemma 4 uses its native `<|tool_call>call:name{...}<tool_call|>` format. The LFM2.5 comparison is text-only with a separate restricted prompt and no tool mode; it advertises no autonomous capability and is not scored against the agentic suite.

`tools/slm/s8_contract.py validate` checks each agentic native prompt renderer against its frozen schema and generation prefix, then parses complete-call fixtures with nested bounded-query arguments, strings, and integers for each native tool format. It also renders the restricted LFM text-only prompt without tools. The native token/argument representations are traced to the pinned S8.1 candidate sources; the MiniCPM renderer reuses the XML envelope adapter, with the stock no-function-call instruction replaced by the task prompt's requirement to call application tools for workflow actions. The previous S5 graders and scores are excluded. Any future renderer/parser mismatch, partial/malformed/unknown call, or missing required argument blocks that candidate before device scoring.

The contract validator checks case IDs, required tool arguments, seeded synthetic revisions/IDs, and source-span bounds. The fixture includes exact captures, events, drafts and initial-link state so each expected call is achievable against the inspected S5 types; S8.3 must still instantiate equivalent isolated Room data, validate Kotlin string offsets, and capture actual command/query results. No source fixture, prompt rendering, parser fixture, or synthetic scorer self-test is evidence of a model call or application effect. S8.2 does not tokenize model-specific prompts or prove an installed runtime/app adapter; those are explicit S8.3/S8.4 prerequisites.

## Scoring and denominators

`tools/slm/s8_contract.py score <result.json>` scores recorded candidate observations. Result files identify each original case ID and include parsed calls, parse-error state, `effect_source: "instrumented_application"`, the actual application effect/outcome trace, response kind/text, and semantic adjudication for clarification, no-match, and refusal. It requires exact ordered call name/argument equality plus expected effect semantics; extra/partial/unknown calls, uninstrumented results, absent calls, and failed parses are failures. Clarification/no-match/refusal cases require the expected structured intent target and a human semantic adjudication (`semantic_review: pass`) of the actual response. No refusal-substring or schema-only grader is used. The grader never interprets a string match as proof of user intent or successful application behavior; the self-test deliberately uses an explicit non-application mode and is never candidate evidence.

- Frozen denominator: every case in the frozen suite.
- Held-out denominator: every case in the held-out suite.
- Missing output, malformed output, parse failure, application failure, wrong semantics, and clarification/refusal errors remain counted failures. No post-result dropping or re-labeling.
- Correctness is reported as `passed / denominator` separately by suite; no aggregate can conceal held-out degradation.
- First-result latency is wall time from submitted input through the first complete, parseable, semantically on-task answer. Report cold and warm distributions (median and p95); define cold-start separately as fresh process/runtime start to first complete app-ready model result.
- Report runtime prefill tokens/duration and decode tokens/duration as separately labeled tokens-per-second plus raw counters. Report process RSS and PSS in MiB as distinct measurements; never substitute them. Record device `MemAvailable` baseline/minimum in KiB separately from process memory, not as a budget. Report cancellation wall time and already-applied effects. Missing measurements are disclosed, never imputed.

The `80%` frozen and `70%` held-out correctness targets, `30 s` first-result, `60 s` cold-start, and `1400 MiB` process RSS in the profile file are **proposed comparison targets only**. They are not approved acceptance gates and do not replace the sprint plan's acceptance criteria. No candidate may be rejected, adopted, or marked qualified solely by these proposed numbers absent explicit owner approval. Mandatory authority/privacy boundaries are non-negotiable implementation constraints, not tradeable numeric targets. Multilingual behavior is optional, separately labeled evidence and is not part of the English denominators.

## Trial boundary and carry-forward

This contract validation is tooling validation only. It is not model qualification, a phone benchmark, proof of app/agent delivery, runtime integration, a model download, or profile adoption. Prior S5 raw evidence remains immutable and separate; historical S5 quality scores are excluded. S8.3 must use isolated synthetic data and actual application outcomes, preserve every case and raw result, measure actual runtime/resource behavior, and obtain owner decisions before turning proposed numeric targets into hard gates.
