# Sprint 8.3 — hosted workflow comparison (Google AI Studio and OpenCode Go)

**Status:** measurement complete for three owner-requested hosted candidates. Each has three
independent full-suite passes over the frozen S8.2 v2 contract, dispatched through the real
application boundary. **No candidate is adopted, qualified, recommended, or shown to execute
the whole workflow**, and this page is not a cloud-migration decision or a privacy claim. The
owner has since replaced the former qualification requirement with the baseline-closure
criterion at `docs/sprint-plans/sprint-8.md:32`; under that criterion this page is a
reproducible baseline of three **model-plus-harness configurations**, not a capability verdict
about the models themselves.

This page is separate from [sprint-8-device-trials.md](sprint-8-device-trials.md). Those
on-device results remain the shipping evidence; nothing here changes them.

## Why this experiment exists

The experiment was authorized under the pre-revision S8.3 requirement for at least one
demonstrated agent-capable option and one genuinely evaluated alternative (then
`docs/sprint-plans/sprint-8.md:27-29`). The local trials measured 1/10 and 2/10 on the frozen
suite, so the owner authorized a comparative hosted evaluation of larger models before deciding
anything about a cloud-first product, with an explicitly evaluation-only cloud exception. The
owner has since replaced that qualification requirement with the baseline-closure criterion at
`:32`. The old requirement is recorded here as the historical reason for the experiment, not as
the current gate.

## Scope, authority and data boundary

- Owner-authorized models: `gemma-4-26b-a4b-it`, `gemini-3.5-flash-lite` (Google AI Studio) and
  `deepseek-v4.1-flash` (OpenCode Go gateway). No substitute model, provider or vendor API.
- Requests contain **only** frozen synthetic S8.2 case text, the frozen system prompt, the
  frozen trusted fixture context and the frozen tool declarations. No personal phone data,
  repository content or other environment values left the host.
- Credentials (`GOOGLE_AI_STUDIO_KEY`, `OPENCODE_GO_API_KEY`) are read in-process from the
  git-ignored repository `.env` and sent only in headers. They never appear in a URL, argv,
  log, artifact or Git content; an evidence-wide scan found zero occurrences.
- Application effects come only from the real `WorkflowCommandService` /
  `WorkflowQueryService` through the isolated in-memory Room bridge in
  `app/src/androidTest/java/io/github/triet4p/aegisf/data/S8WorkflowTrialInstrumentedTest.kt`.
  No mock or echo tool exists; no product source is changed.
- **Not implemented, not claimed:** production privacy masking. Real captures would need
  redaction/tokenization that preserves exact local amount/date mappings through tool calls
  and results; a regex-only mask does not prove that. Synthetic fixtures were used instead of
  pretending such a layer exists.

## Verified identities and native protocols

| Requested | Live identifier | Verified version / limits | Native protocol used |
| --- | --- | --- | --- |
| Gemma 4 26B | `gemma-4-26b-a4b-it` | `001`, 262,144 in / 32,768 out, thinking | `generateContent` + `functionDeclarations`, structured `functionCall` |
| Gemini 3.5 Flash Lite | `gemini-3.5-flash-lite` | `3.5-flash-lite-07-2026`, 1,048,576 in / 65,536 out, thinking | same Google function-calling protocol |
| DeepSeek V4.1 Flash | `deepseek-v4.1-flash` | listed in the 38-model Go catalog | OpenAI-compatible Chat Completions `tools` / `tool_calls` |

Protocol notes:

- Tool names, descriptions, argument names, bounds and required fields are identical across
  transports; only the wire encoding differs.
- **No native text envelope is parsed for any hosted profile.** The adapter is the provider's
  structured decode (`google-function-call-v1`, `openai-chat-tool-calls-v1`), recorded as
  `run.transport.native_envelope_parsed: false`. On-device profiles used the frozen
  `s8-native-parser-v1` text-envelope parser; that is a transport difference, not a model result.
- OpenCode documents DeepSeek on Chat Completions (Responses is documented for GPT models), so
  the Go evaluation uses Chat Completions. The Console `/inference` route returned HTTP 402
  “Insufficient account funds”; that is retained only as a **wrong-route diagnostic**, not as
  evidence about Go funds. The Go route `https://opencode.ai/zen/go/v1` returned 200 with a
  native tool call and `billing_source: go`.
- Single-turn for every profile: one user message, no tool results, no follow-up. A future
  tool-result multi-turn measurement is a separate protocol, not this baseline.

## Frozen request configuration

Frozen in [`tools/slm/s8_hosted_request_config.json`](../../tools/slm/s8_hosted_request_config.json);
its SHA-256 is recorded with every pass.

| Setting | Google profiles | Go profile |
| --- | --- | --- |
| Temperature / top-p / seed | 0.1 / 0.95 / 42 | 0.1 / 0.95 / 42 |
| top-k | 50 | not exposed by Chat Completions |
| Output cap | 2048 | 2048 |
| Reasoning | `thinkingLevel: minimal` | model default; reasoning tokens reported separately |
| Tool choice | `AUTO` | `auto` |
| Retry policy | none on any 4xx; one retry on transport failure or 5xx | same |

Comparability disclosures:

- **All three Gemma passes used one config digest (`710a0f8d…`) and byte-identical per-case
  request bodies.** The 429 in pass 3 therefore occurred under identical conditions and is a
  measured reliability failure, not a configuration difference.
- Gemini and DeepSeek ran on config revision v2 (per-provider blocks plus launch pacing). v2
  changed only JSON key order in Google `generationConfig` and launch spacing: all 16 Google
  request bodies are **semantically identical** across revisions and models (verified by
  parsed-JSON equality). Pacing affects launch timing only, never request content, the adapter
  or per-request latency.

## Method

- Same frozen inputs as the device trials: 10 frozen + 6 held-out cases, frozen system prompt,
  fixture, tool schema and trusted per-case context. Cases, prompts, expectations and
  denominators were not edited.
- Every parsed call is dispatched through the instrumentation bridge; each call, application
  outcome, stored `note_events` row and database snapshot is persisted per case. The adapter
  never filters a call.
- One independent pass = a fresh 16-case run in its own evidence root. Passes are serialised.
  Every attempt is retained, including provider errors and invalid runs.
- One host-constructed replay probe per pass exercises the application idempotency contract
  (`Applied` → `NoChange`, one durable `workflow_operations` row, equal state digests). It is
  never shown to a model and never scored as model behaviour.
- Human adjudication of the six clarification / no-match / refusal cases was written from the
  actual response text before scoring.
- No hosted cancellation, RSS/PSS or cold-start measurement exists: a single bounded HTTP
  request has no local model process to signal or sample. The absence is recorded, not imputed.
- Launch pacing (Google 8 s, Go 2 s) respects published limits. A limited case is recorded as
  a provider error and is never retried inside a pass.

## Results per model (three complete passes each)

| Model | Frozen (per pass) | Held-out (per pass) | Passing cases |
| --- | --- | --- | --- |
| Gemma 4 26B | **3, 3, 3** / 10 | **3, 3, 2** / 6 | `s8-laya-01`, `s8-match-02`, `s8-unknown-01`, `s8h-search-01`, `s8h-laya-01`, `s8h-injection-01` (pass 3 loses `s8h-injection-01` to a 429) |
| Gemini 3.5 Flash Lite | **4, 4, 4** / 10 | **2, 2, 2** / 6 | `s8-search-01`, `s8-laya-01`, `s8-match-01`, `s8-unknown-01`, `s8h-search-01`, `s8h-laya-01` — identical in all three passes |
| DeepSeek V4.1 Flash | **3, 3, 3** / 10 | **4, 4, 4** / 6 | `s8-search-01`, `s8-laya-01`, `s8-unknown-01`, `s8h-search-01`, `s8h-ambiguity-01`, `s8h-laya-01`, `s8h-injection-01` — identical in all three passes |

Stability: Gemini and DeepSeek were case-for-case identical in all three passes. Gemma's only
flip is `s8h-injection-01`, the 429 case. Machine-readable: `evidence/S8.3/a5/stability-*.json`.

## Capability breakdown with exact denominators

The suite has **11 call-bearing cases** and **5 no-call cases** — two clarifications, one
no-match and two refusals; `s8-time-02` requires both a bounded read *and* a clarification and
is counted as call-bearing. Per model there are **48 case generations** (3 passes × 16): **33
call-bearing and 15 no-call** generations.

Column definitions, because the labels are easy to over-read:

- **Adapted** — the adapter could represent the response at all (no `parse_error` and no
  provider error). It measures transport plumbing, not correctness.
- **No-call cases with zero calls** — of the 5 expected no-tool cases, how many emitted no
  call. This is abstention only; it does **not** check that the clarification, no-match or
  refusal was semantically correct. The Gemma pass-3 429 response is absent and counts as not
  adapted, so Gemma is 14/15 rather than 15/15.
- **Call-bearing tool sequence** — the observed tool-name sequence equals the expected one.
  This is a mixture of positive tool selection and abstention, **not** positive tool-call
  precision.
- **Call-bearing tool + args** — the sequence matches *and* the v2 scorer accepts every
  argument; denominator 33 across three passes.
- **Effects matched** — the case has no `observed_effect_mismatch` reason.
- **Workflow passed** — the v2 scorer reports a PASS for the whole case.
- **Stored readings** — cases in which a model call stored at least one `note_events` reading.
  DeepSeek stored 11 rows across 9 cases (both events in passes 2–3).

| Model | Adapted | No-call zero-call | Call-bearing tool sequence | Call-bearing tool + args | All-16 tool + args | Effects matched | Workflow passed | Stored readings |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Gemma 4 26B, pass 1 | 16/16 | 5/5 | 10/11 | 3/11 | 8/16 | 10/16 | 6/16 | 1/16 |
| Gemma 4 26B, pass 2 | 16/16 | 5/5 | 10/11 | 3/11 | 8/16 | 10/16 | 6/16 | 1/16 |
| Gemma 4 26B, pass 3 | 15/16 | 4/5 | 10/11 | 3/11 | 7/16 | 10/16 | 5/16 | 1/16 |
| Gemini, pass 1–3 (identical) | 16/16 | 2/5 | 8/11 | 4/11 | 6/16 | 10/16 | 6/16 | 4/16 |
| DeepSeek, pass 1 | 15/16 | 3/5 | 7/11 | 4/11 | 7/16 | 11/16 | 7/16 | 3/16 |
| DeepSeek, pass 2 | 16/16 | 4/5 | 9/11 | 4/11 | 8/16 | 12/16 | 7/16 | 3/16 |
| DeepSeek, pass 3 | 15/16 | 4/5 | 7/11 | 4/11 | 8/16 | 12/16 | 7/16 | 3/16 |

Three-pass totals (48 case generations per model; 15 no-call and 33 call-bearing):

| Model | Adapted | No-call zero-call | Call-bearing tool | Call-bearing tool+args | Effects | Workflow | Readings |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Gemma 4 26B | 47/48 | 14/15 | 30/33 | 9/33 | 30/48 | 17/48 | 3/48 |
| Gemini 3.5 Flash Lite | 48/48 | 6/15 | 24/33 | 12/33 | 30/48 | 18/48 | 12/48 |
| DeepSeek V4.1 Flash | 46/48 | 11/15 | 23/33 | 12/33 | 35/48 | 21/48 | 9/48 |

Reading this honestly: under this one-turn, 2048-token-output configuration no model exceeds
21 of 48 whole-workflow passes, and only DeepSeek matches the tool sequence on most
call-bearing cases. The high “adapted” counts say the transports work; they say nothing about
workflow correctness. A failure here is a property of the measured configuration and does **not**
establish that any model is intrinsically incapable — the confound register below states what
was and was not varied.

Recurring failure modes:

- **Gemma:** `unresolvedReason` placed at the top level instead of inside `interpretation`
  (schema-invalid), `expectedEventRevision` 0 instead of `null` for a new reading (the
  application really returned `Conflict`), missing-time clarification never asked, no-match
  answered with a bare acknowledgement, ambiguity answered with a promise to ask later.
- **Gemini:** only 2 of 5 no-call cases stayed call-free; it emitted bounded reads for the
  ambiguity and injected-authority cases, and for the two-draft clarification. It is the only
  candidate that produced the required no-match report.
- **DeepSeek:** added extra bounded reads on write cases; asked no missing-time clarification;
  confirmed no action in the no-match case without stating that no candidate exists. Multi-event
  extraction hit the frozen 2048-token cap in **all three passes** and every occurrence remains
  a primary failure: pass 1 `s8-multi-01` (1814 reasoning tokens, parse error at a 389-character
  arguments string), pass 2 `s8h-multi-01` (2048 reasoning tokens, no calls emitted at all), and
  pass 3 `s8h-multi-01` (1580 reasoning tokens, parse error). The cap was not raised afterwards,
  because that would have made the passes non-comparable.

## Operational reliability (all launches, including failures)

| Event | Model | Count | Handling |
| --- | --- | --- | --- |
| Complete primary passes | Gemma / Gemini / DeepSeek | 3 / 3 / 3 | scored |
| Case-level provider error | Gemma pass 3, `s8h-injection-01` | 1 | HTTP 429, free-tier 16,000 input tokens/min; preserved as a failure in that pass, not retried |
| Pass invalidated by device loss | Gemini pass 3 (first attempt) | 1 | phone disconnected; 8/16 cases lacked an application trace; preserved with `aborted.json`, excluded from all denominators, rerun only after the phone returned |
| Supplemental pass aborted by config change | Gemma (supplemental) | 1 | ten real generations preserved unscored, excluded; no replacement pass per owner direction |
| Wrong-route generation attempts | DeepSeek via Console `/inference` | 2 | HTTP 402 `Insufficient account funds`; recorded as a route diagnostic, then the Go route was verified and used |
| Cap-truncated generation at the frozen output cap | DeepSeek `s8-multi-01` (p1), `s8h-multi-01` (p2, p3) | 3 | primary failures, counted in their passes; cap not tuned afterwards |

The device loss is a real operational observation about this measurement setup: a 16-case pass
takes minutes and the USB link dropped once, invalidating a pass that had to be rerun. It did
not alter a model result, but it is part of the reliability record, not a footnote.

## Baseline diagnosis under the revised S8.3 criterion

The current criterion (`docs/sprint-plans/sprint-8.md:32`) asks for a reviewed, reproducible
baseline plus a diagnosis of model-plus-harness limitations, with exact denominators,
raw-backed tool/argument/effect outcomes, cap/protocol/one-turn confounds and operational
failures explicit. It is **not** an agent qualification, and this page does not qualify any
profile. The measured object is one operating point: frozen v2 cases, one frozen prompt, one
user turn and a 2048-token output cap. This reassessment is attempt `S8.3-replan-a6`; it adds
no new provider request. The round-1 FAIL under the previous criterion remains historical and
is not converted into a PASS.

### Frozen operating point (raw-backed values)

| Factor | Frozen value | Where it is observable |
| --- | --- | --- |
| Output cap | `maxOutputTokens: 2048` for all three profiles | `transport.generation_config` in every `summary.json` |
| Provider-native output limit | Gemma 32,768; Gemini 65,536 | `assets.model_identity.outputTokenLimit` |
| Reasoning | Gemma/Gemini `thinkingLevel: minimal`; DeepSeek model default, reported separately | `generation_config`; `thought_tokens` per case |
| Conversation | one user turn, no tool results, no follow-up turn | `transport.conversation` in all nine summaries |
| Decode | provider-native structured calls; `native_envelope_parsed: false` | `transport.output_adapter` |
| Tool schema | same names, descriptions, arguments and bounds as the device profiles | frozen `s8_workflow_tools.json`; both adapters render it |
| Sampling | temp 0.1, top-p 0.95, seed 42; top-k 50 (Google only) | `generation_config` |
| Retry | none on 4xx (a 429 is retained); at most one on transport/5xx | `retry_policy` |
| Context | full frozen prompt + fixture; no compaction and no history | `input_tokens` medians ≈ 1520 (Google), 1920 (DeepSeek) |
| Scoring | v2 semantic rubric; parse, schema, argument, effect and reading failures stay in the denominator | `contract_binding.status: verified` in all nine passes |

### The output cap actually bound one profile, and was not varied

- **DeepSeek hit the cap on exactly three case generations:** `s8-multi-01` pass 1 and
  `s8h-multi-01` passes 2–3, all `finish_reason: length`, `output_tokens: 2048`, with 1814 /
  2048 / 1580 `thought_tokens`. Two produced unparseable `tool_calls` arguments and one produced
  no calls at all. These remain primary failures in their passes; the cap was frozen before the
  passes and was deliberately not raised afterwards because that would have made the set
  non-comparable. They are **incomplete-budget outcomes**, not observations of the model with an
  adequate budget.
- **Gemma and Gemini never approached the cap:** maxima 333 and 171 output tokens against 2048.
  Their failures are therefore not cap artifacts, though they are still one-turn,
  no-tool-result observations.
- No ablation at a larger cap, no per-model native output budget and no reasoning-budget sweep
  was run, so this baseline cannot say how much of the DeepSeek multi-event failure is budget
  and how much is argument construction.

### Protocol confound: one turn, no observed tool results

Every case is a single request. The model never sees the application's tool result, an error
code, a revision conflict or a clarification answer, and cannot repair a partially wrong call in
a following turn — the executor, continuation and postcondition loop belongs to S8.9 and was not
built yet. Multi-step link sequences, `expectedEventRevision` handling and clarification
behaviour are therefore measured *without* the feedback loop the eventual agent will have. This
does not excuse a wrong one-shot call; it bounds what the number means. No multi-turn comparison
was run, so no claim is made that a multi-turn harness would or would not change the result.

### What the baseline supports, and what it does not

- It supports: the three providers are reachable through their documented routes with the frozen
  tool schema; their native structured decodes are usable; application effects are real and
  traceable; exact per-case tool, argument, effect and reading outcomes are reproducible; the
  tested configurations complete 17/48, 18/48 and 21/48 whole workflows respectively.
- It does not support: any qualification, adoption, recommendation or ranking of the models; any
  claim that a model alone is incapable; any claim that a larger cap or a multi-turn loop would
  pass; any production privacy or cloud-migration decision; any numeric target as a gate.
- Operational events are not capability evidence and are not used to explain a successful case:
  the 429 is a real free-tier limit, the device-loss pass is excluded from every denominator, the
  aborted supplemental pass is excluded and unscored, the Console 402 is a wrong-route
  diagnostic, and cap truncations stay inside their pass denominators.

### Known measurement limits

- No cancellation, RSS/PSS or cold-start value exists for a single bounded HTTP request; none is
  imputed.
- Provider prices and quotas are variable; only provider-reported usage and the Go allowance are
  recorded.
- Reasoning token counts exist only where the provider reports them; Google reports no cost.
- Production privacy masking was deliberately not implemented, so no real-data claim is made.

## Hosted versus on-device, same contract

| Profile | Frozen | Held-out | Transport | Peak RSS |
| --- | --- | --- | --- | --- |
| MiniCPM-V-4.7-1B (phone) | 1/10 | 1/6 | in-process llama.cpp | 840 MiB |
| Qwen3.5-0.8B (phone) | 2/10 | 1/6 | in-process llama.cpp | 707 MiB |
| Gemma 4 26B (hosted) | 3/10 (×3) | 3, 3, 2 / 6 | Google `generateContent` | n/a |
| Gemini 3.5 Flash Lite (hosted) | 4/10 (×3) | 2/6 (×3) | Google `generateContent` | n/a |
| DeepSeek V4.1 Flash (hosted) | 3/10 (×3) | 4/6 (×3) | OpenCode Go Chat Completions | n/a |

The hosted models beat the local ones on this suite, but none approaches the proposed
(unapproved) 80 % frozen target; only DeepSeek's held-out result (4/6 per pass) is near the
70 % one. That is a comparison of measured configurations under the frozen contract — not a
qualification, not a model-capability ranking, and not a gate on the proposed targets.

## Latency, tokens and provider-reported cost

| Measure | Gemma 4 26B (pass 1/2/3) | Gemini 3.5 Flash Lite (pass 1/2/3) | DeepSeek V4.1 Flash (pass 1/2/3) |
| --- | --- | --- | --- |
| First-result median | 2.62 / 2.49 / 2.75 s | 1.30 / 1.33 / 1.24 s | 4.49 / 5.51 / 4.31 s |
| First-result warm p95 | 4.24 / 4.73 / 4.51 s | 2.85 / 3.17 / 3.94 s | 7.83 / 12.26 / 9.53 s |
| Input tokens (median) | 1520.5 / 1520.5 / 1523 | 1520.5 | 1920.0 |
| Output tokens (median) | 53.5 / 53.5 / 54.0 | 56.0 | 489.5 / 500.0 / 390.5 |
| Reasoning tokens (median) | not reported by the API | not reported by the API | 349.0 / 286.5 / 307.0 |
| Provider-reported cost | not reported by the API | not reported by the API | 0.0 USD (Go plan) |
| Go allowance counted | — | — | 0.00610 / 0.00550 / 0.00504 USD |
| Cases missing a first-result latency | pass 3: 1 (`s8h-injection-01`, 429) | 0 | pass 1: 1 (`s8-multi-01`); pass 3: 1 (`s8h-multi-01`) — both cap-truncated |

First-result latency is client-measured wall time from sending the request to a complete,
adaptable structured answer; provider errors and unrepresentable responses are named in
`first_result_missing_cases` rather than contributing a latency. A zero cost on the Go route
means Go-plan usage, not a missing value.

## What is not measured, and is not claimed

- No candidate is adopted, qualified, recommended or called production-ready; none executes the
  whole workflow under this configuration. The former agent-capable qualification requirement
  was replaced by the owner with the baseline-closure criterion at
  `docs/sprint-plans/sprint-8.md:32`; it is neither retroactively satisfied nor retroactively
  failed here.
- No claim is made that a model alone is incapable. The output cap, the single-turn protocol,
  the missing tool-result feedback and the absence of a continuation loop were not varied; the
  diagnosis above states exactly what this baseline can separate.
- No production privacy masking, no cloud product wiring, no runtime migration, no
  multilingual evaluation.
- No hosted cancellation, RSS/PSS or cold-start figure exists, and none is imputed.
- The reasoning channel exists only in the hosted transports; where the API reports it, its
  token count is separate.
- The single-turn protocol does not exercise tool results or multi-step follow-up.
- 80 %/70 % correctness and 30 s/60 s/1400 MiB remain proposed comparison targets, not gates.

## Evidence and reproduction

- `artifacts/sprint-8/evidence/S8.3/a5/index.json` — every evidence file with size and SHA-256,
  per-pass scores, stability and provider verification.
- `…/a5/pass-<n>/<profile>/` — `request.json`, provider `response.json`, `case.json`,
  `application_trace.json`, `results.json`, `score.json`, `adjudication.json`,
  `model-verification.json`, `replay-probe/`.
- `…/a5/opencode-go/provider-verification.json` — Go gateway route, catalog, preflight.
- Preserved invalid attempts: `…/a5/pass-4-aborted-config-change/`,
  `…/a5/pass-3/gemini-3.5-flash-lite-ai-studio-device-loss/`.

```text
python tools/slm/s8_trials.py verify --profile <profile>
python tools/slm/s8_trials.py run --profile <profile> --evidence artifacts/sprint-8/evidence/S8.3/a5/pass-<n>
python tools/slm/s8_trials.py build-results --profile <profile> --evidence artifacts/sprint-8/evidence/S8.3/a5/pass-<n>
python tools/slm/s8_trials.py stability --profile <profile> --passes 3
```
