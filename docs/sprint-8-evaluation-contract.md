# Sprint 8.2 workflow evaluation contract

**Status:** Frozen candidate contract revision `s8-workflow-contract-v2` for S8.3 trial planning; it is not a model qualification, a shipping profile decision, or an owner-approved numeric release gate. The executable fixtures and scorer under `tools/slm/s8_workflow_*` are the source of case identities, exact arguments, semantic scoring policies, and denominators. The v2 revision changes scoring only: the frozen and held-out suites, prompts, fixture, tool schema and proposed numeric targets are byte-identical to checkpoint `b26148e`, and v1 exact-argument scoring plus every v1 result remain preserved and runnable.

**S8.13-pre runtime interface note.** The production agent interface is now the v3 eleven-tool,
reference-based contract described in
[the application tools contract](sprint-8-application-tools.md). This frozen scoring contract still
describes the historical v2 call shapes and remains authoritative only for the existing recorded
scores; it is not retroactively rewritten. A future qualification run requires separately approved
v3 fixtures and scoring, including proof of the journal-backed `replay_operation` identity, and no
historical case is awarded a retroactive PASS.

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
- `tools/slm/s8_workflow_scoring_v2.json`: the frozen v2 scoring contract — declared argument policies, bound and immutable input digests, replay and evidence requirements, reason codes, and the honest-boundary notes.

The suite covers multiple events, explicit and relative/missing time, bounded candidate reads, propose-then-attach and its authorization boundary, exact Laya request, no-match and ambiguity, unknown tool, and injected untrusted text. Expected calls carry the exact arguments that the application requires (revisions, spans, amounts, dates, times, limits, nulls, and ordered multi-call sequences); the four declared v2 policies below are the only fields judged semantically. Cases distinguish an answerable call, a valid clarification, and a refusal. Unknown tools, partial/malformed calls, extra calls, missing calls, bad arguments, and parser errors fail; none are silently dropped.

Before trials, every fixture must be paired with the actual synthetic app fixture it addresses: stored capture/draft IDs and revisions, exact capture text and Unicode span offsets, and expected application outcome/effects. Cases may not be edited to fit a generated answer. The existing app has no implemented generative tool adapter; S8.2 freezes its interface contract but does not claim a tool-call side effect occurred. S8.3 evidence must contain actual application outcomes/effects; schema-valid output alone does not pass.

Three agentic candidate families have frozen source-backed tool formats: MiniCPM v4.7 and Qwen 3.5 use complete XML tool-call envelopes; Gemma 4 uses its native `<|tool_call>call:name{...}<tool_call|>` format. The LFM2.5 comparison is text-only with a separate restricted prompt and no tool mode; it advertises no autonomous capability and is not scored against the agentic suite.

`tools/slm/s8_contract.py validate` checks each agentic native prompt renderer against its frozen schema and generation prefix, then parses complete-call fixtures with nested bounded-query arguments, strings, and integers for each native tool format. It also renders the restricted LFM text-only prompt without tools. The native token/argument representations are traced to the pinned S8.1 candidate sources; the MiniCPM renderer reuses the XML envelope adapter, with the stock no-function-call instruction replaced by the task prompt's requirement to call application tools for workflow actions. The previous S5 graders and scores are excluded. Any future renderer/parser mismatch, partial/malformed/unknown call, or missing required argument blocks that candidate before device scoring.

The contract validator checks case IDs, required tool arguments, seeded synthetic revisions/IDs, and source-span bounds. The fixture includes exact captures, events, drafts and initial-link state so each expected call is achievable against the inspected S5 types; S8.3 must still instantiate equivalent isolated Room data, validate Kotlin string offsets, and capture actual command/query results. No source fixture, prompt rendering, parser fixture, or synthetic scorer self-test is evidence of a model call or application effect. S8.2 does not tokenize model-specific prompts or prove an installed runtime/app adapter; those are explicit S8.3/S8.4 prerequisites.

## Scoring and denominators

`tools/slm/s8_contract.py score <result.json>` applies the frozen v2 semantic rubric. `score <result.json> --scoring v1` reproduces the preserved exact-argument scoring that was assessed at checkpoint `b26148e`; the two revisions are never mixed in one report. Result files identify each original case ID and include parsed calls, parse-error state, `effect_source: "instrumented_application"`, the actual application effect/outcome trace, per-call stored readings, response kind/text, and semantic adjudication for clarification, no-match, and refusal.

Every check below is applied to every case in both suites:

- **Exact by default.** Tool names, ordered call sequences, scoped capture/event/draft ids, expected revisions, source spans, amounts, dates, times, timezones, page bounds, nullness and the caller-scope boundary stay exact. A changed scope, revision, amount, date, time, or intent fails with `call_intent_or_arguments_mismatch`; extra, partial or unknown calls fail; each observed call must also satisfy the generic tool schema.
- **Idempotency key.** Non-empty, at most 128 characters, no control characters, unique within the case. The only format rule is the application's own: a blank key is refused, while a non-blank key with surrounding whitespace is a distinct valid key exactly as the application stores it. A repeated key must be proven by trace evidence rather than re-labelled.
- **Normalized text.** Non-empty, at most 200 characters, matching the reading the application actually stored, containing at least one non-neutral content token shared with the source span or stated merchant (exact or >= 4-character prefix), and stating no number the source span, amount, or stated date/time does not support.
- **Unresolved reason.** The decision to mark a reading unresolved is exact; the label is only required to be non-blank, bounded and free of control characters, because the application accepts any non-blank reason and no product vocabulary exists.
- **Declared extraction version.** A bounded producer-supplied string that the score output records, never compared to a literal; all readings in one case must declare the same version so a mixed-producer sample stays visible.
- **Stored reading.** Every `update_event_interpretation` call needs the app-stored `note_events` row derived from the real instrumented trace: capture, span, amount, date, time, normalized text, unresolved decision and status. A schema-valid call without that row fails (`stored_reading_evidence_missing`), as does a row that does not match the call.
- **Replay claim.** A claim that a retry was idempotent needs the repeated dispatch outcomes (`Applied` then `NoChange`), exactly one durable `workflow_operations` row for the key with a terminal outcome, and equal before/after state digests. A field marker, a re-applied retry, or a changed state digest fails.
- **Provenance binding.** A results document must declare `scoring_version`, `contract_version`, `parser_version` and the SHA-256 of the frozen suite, held-out suite, system prompt, tool schema and fixture it was produced with. A missing or mismatched binding fails every case, so v1 and v2 scores cannot qualify silently in one report.
- **Clarification / no-match / refusal.** The expected structured intent target is required plus a human semantic adjudication (`semantic_review: pass`) of the actual response. No refusal-substring grader is used, and the self-test records are never candidate evidence.
- **Per-profile replay probe.** A score report is complete for qualification only when it carries a `replay_probe` block validated by the same replay rules; the report always states whether the probe was supplied, valid, or missing rather than inferring it.

The executable policy declarations, bound input digests and reason-code list live in `tools/slm/s8_workflow_scoring_v2.json`. `validate` re-hashes the bound inputs, confirms the suites, prompts, fixture and tool schema are unchanged from checkpoint `b26148e`, and checks that every frozen expected value itself satisfies the policy applied to it.

- Frozen denominator: every case in the frozen suite.
- Held-out denominator: every case in the held-out suite.
- Missing output, malformed output, parse failure, application failure, missing stored reading, wrong semantics, and clarification/refusal errors remain counted failures. No post-result dropping or re-labeling.
- Correctness is reported as `passed / denominator` separately by suite; no aggregate can conceal held-out degradation.
- First-result latency is wall time from submitted input through the first complete, parseable, semantically on-task answer. Report cold and warm distributions (median and p95); define cold-start separately as fresh process/runtime start to first complete app-ready model result.
- Report runtime prefill tokens/duration and decode tokens/duration as separately labeled tokens-per-second plus raw counters. Report process RSS and PSS in MiB as distinct measurements; never substitute them. Record device `MemAvailable` baseline/minimum in KiB separately from process memory, not as a budget. Report cancellation wall time and already-applied effects. Missing measurements are disclosed, never imputed.

The `80%` frozen and `70%` held-out correctness targets, `30 s` first-result, `60 s` cold-start, and `1400 MiB` process RSS in the profile file are **proposed comparison targets only**. They are not approved acceptance gates and do not replace the sprint plan's acceptance criteria. No candidate may be rejected, adopted, or marked qualified solely by these proposed numbers absent explicit owner approval. Mandatory authority/privacy boundaries are non-negotiable implementation constraints, not tradeable numeric targets. Multilingual behavior is optional, separately labeled evidence and is not part of the English denominators.

### Why the revision was needed

The S8.3-a2 contract discriminator (preserved under `artifacts/sprint-8/evidence/S8.3/a2/contract-discriminator/`) dispatched two host-constructed calls that differed only in `idempotencyKey` and `interpretation.normalizedText` through the real `WorkflowCommandService` against an isolated in-memory Room database. Both produced the same stored event and effects. Exact v1 scoring passed the literal control and failed the other variant with exactly `call_intent_or_arguments_mismatch`. The second variant is functionally correct: no supplied prompt, fixture context or schema states a required key or reading text, so v1 demanded unknowable literals in 9 of 11 call-bearing cases and 6 free-form readings.

### Achievability audit

Every expected value was checked against what a model actually receives: the frozen system prompt, the case prompt, the per-case trusted application context (date context, capture text/revision, existing event/draft revisions), and the schema.

| Expected value | Knowable from | v2 treatment |
| --- | --- | --- |
| `idempotencyKey` | Nothing states a value; the application only requires a non-blank unique key | Semantic policy |
| `interpretation.normalizedText` | Free-form reading of the source span | Deterministic source-backed policy |
| `interpretation.unresolvedReason` | Only the decision is stated; no label vocabulary exists | Decision exact, label any non-blank bounded text |
| `extractionVersion` | Producer metadata; no prompt-visible value | Declared bounded string, never a literal, one per case |
| capture/event/draft ids, revisions, spans | Case prompt plus trusted fixture context | Exact |
| amount, merchant, date, time, timezone | Source span plus frozen date context | Exact |
| page bounds, review status, duplicate filter | Case prompt plus tool description | Exact |
| caller scope | Never a model argument; supplied by trusted host code | Exact boundary, not model-visible |

Residual authoring sensitivity is disclosed rather than silently loosened: the multi-event expectations label the source word "fuel" as `merchant`, and `attach_link.expectedRevision` 0 relies on the application's initial link revision. Both stay exact, and a defensible `null` merchant would fail.

### Honest boundaries

- Paraphrase quality is human judgement. The machine checks non-emptiness, source tokens, numeric facts, the stored reading and the observed effect; it does not rank paraphrases or judge whether a reading is the best one.
- The unresolved-reason label vocabulary is not fixed: the application accepts any non-blank reason string, so only the unresolved decision, non-emptiness, bounded size and control characters are graded.
- The declared extraction version is producer-supplied provenance and is never compared to a literal. The score output records it and requires one producer per case; verifying which extractor produced a reading belongs to a future adapter, not this scorer.
- Replay evidence is validated for structure and internal consistency (repeated dispatch, one durable operation row, equal state digests), not as proof that an unobserved device run happened. Raw trace review remains required.

### Version history and provenance binding

- `s8-workflow-contract-v1` (checkpoint `b26148e`): exact ordered call/argument equality. Its scoring is preserved as `score --scoring v1`; its results and review are not re-labelled, and no v1 failure is retroactively passed.
- `s8-workflow-contract-v2` (this revision): semantic rubric declared in `tools/slm/s8_workflow_scoring_v2.json`, with `aegis-f-s8-workflow-scoring/v2` and parser `s8-native-parser-v1`.
- Every v2 results document declares its scoring, contract, parser, suite, prompt, tool-schema and fixture versions/hashes. The score report echoes the exact bound inputs and its own scoring-contract digest, so mixed old/new scores cannot qualify silently.

### S8.3 prerequisites

Before new device scoring, the trial harness must (a) add the contract `binding` block to each results document, (b) attach the app-stored reading for every `update_event_interpretation` call, and (c) run one bounded replay probe per profile with the repeated dispatch outcomes and the durable `workflow_operations` row. These are host-side evidence additions; no product change is implied. The v1 raw prompts/outputs already on disk stay immutable and are not re-scored as v2.

## Trial boundary and carry-forward

This contract validation is tooling validation only. It is not model qualification, a phone benchmark, proof of app/agent delivery, runtime integration, a model download, or profile adoption. Prior S5 raw evidence remains immutable and separate; historical S5 quality scores are excluded. S8.3 must use isolated synthetic data and actual application outcomes, preserve every case and raw result, measure actual runtime/resource behavior, and obtain owner decisions before turning proposed numeric targets into hard gates.
