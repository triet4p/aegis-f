# Sprint 8.5 — hosted provider adapters

**Status:** Implemented provider-neutral generation plus the three native protocol adapters. This
is protocol-slice evidence, not a model qualification: no executor (S8.9), masking boundary
(S8.6), tool adapter (S8.7) or user settings surface (S8.12) is delivered here. The adapters are
unwired from the UI and are exercised by unit tests and a bounded synthetic live smoke.

Source: `app/src/main/java/io/github/triet4p/aegisf/provider/`.

## Contract

`HostedGeneration.kt` defines the surface one agent runtime will use:

- `HostedProviderConfig` — protocol (`GEMINI_GENERATE_CONTENT`, `OPENAI_CHAT_COMPLETIONS`,
  `OPENAI_RESPONSES`), one absolute HTTPS endpoint, one model id, one credential, the model's
  declared capabilities, a required generation budget, and optional documented client headers.
  Construction is the validation point: plaintext endpoints, a model id that would change the
  request path, the wrong credential kind for the protocol, a budget above a discovered limit, a
  requested feature the capabilities do not declare, and credential-carrying extra headers are
  all refused. Nothing is defaulted, and there is no fallback to another protocol or model.
- `HostedAuthentication` — bearer or Google API key. Its `toString` and `redact` exist so neither a
  configuration dump nor a provider error detail can retain the credential.
- `HostedProviderCapabilities` — tool calls, parallel tool calls, reasoning budget, temperature,
  and the provider-published output limit. These are declarations about a discovered model, not
  inference from its name.
- `HostedGenerationBudget` — `maxOutputTokens` is required; reasoning effort, temperature and the
  parallel-call setting are sent only when the capabilities declare them. There is no shared
  2048-token cap. Truncation is an incomplete outcome, never a completed answer.
- `HostedToolDeclaration` — one portable name, description and JSON-Schema argument object.
- `HostedGenerationAdapter` — `begin`, `generate`, `continueWithToolResults`. Adapters never
  execute a call.
- `HostedTurn` / `HostedToolCall` / `HostedGenerationResult` / `HostedProviderFailure` — the parsed
  turn, per-call dispatch eligibility, and the classified failure vocabulary.

## Wire encodings

| Protocol | Tools | Model call | Tool result | Budget fields |
| --- | --- | --- | --- | --- |
| Gemini `generateContent` | `tools[].functionDeclarations[]`; the provider's OpenAPI subset (local `$defs`/`$ref` materialized, `additionalProperties` and `$defs` removed, types upper-cased, `["x","null"]` lowered to `x` + `nullable`) | `functionCall` part with `name`, structured `args`, optional `id`; `thoughtSignature` retained verbatim | `functionResponse` part with `name`, object `response`, echoed `id` — in a **user** role turn | `generationConfig.maxOutputTokens`, `temperature`, `thinkingConfig.thinkingLevel` |
| Chat Completions | `tools[]` as `{type: "function", function: {…}}`, `tool_choice: "auto"` | `choices[].message.tool_calls[]` with `id`, `function.name`, JSON-string `arguments` | `role: "tool"` message with matching `tool_call_id` | `max_completion_tokens`, `temperature`, `reasoning_effort`, `parallel_tool_calls` |
| Responses | flat `tools[]` with `type: "function"`, `name`, `parameters`, `tool_choice: "auto"` | typed `output[]` `function_call` items with `call_id`, `name`, JSON-string `arguments`, item `status` | `function_call_output` item with matching `call_id` and string `output` | `max_output_tokens`, `temperature`, `reasoning.effort`, `parallel_tool_calls` |

Two protocol facts were **corrected from live observation** rather than inherited from the frozen
S8.4 draft:

- Gemini `generateContent` rejects a `tool` role (`Role 'tool' is not supported`). The accepted
  encoding is the `functionResponse` part in a `user` role turn, and that is what the adapter
  sends. The frozen contract text in `docs/sprint-8-agent-contract.md` §2.1 is intentionally left
  unchanged because it is checkpointed evidence; this document records the correction.
- Responses chaining is **stateless**: the retained items are resent instead of naming a
  `previous_response_id`, so no continuation is stored on the gateway. The full `output` list,
  including reasoning items, is preserved because those items belong to the continuation.

The Chat adapter also deliberately does not retain the gateway's `reasoning_content`: the live
exchange continues correctly without it, and hidden reasoning must not enter stored conversation
state.

## Argument assembly and dispatch eligibility

Every call in a turn is parsed, then assessed against the declarations offered for that turn:

- `READY` — the arguments are a complete JSON object that satisfies the declared schema. Only this
  disposition is `dispatchable`.
- `MALFORMED_ARGUMENTS` — arguments are absent, cut off, or not a complete JSON object. The strict
  parser rejects trailing bytes, so a truncated `{"value":` never parses as a smaller object.
- `SCHEMA_INVALID` — complete JSON that violates the declaration: missing required member, extra
  member where `additionalProperties: false`, wrong primitive type, out-of-bounds number, short
  string, or failed declared pattern.
- `UNKNOWN_TOOL` — the name was not declared for this turn.
- `TRUNCATED_TURN` / `FILTERED_TURN` — the turn itself stopped on a budget or a provider refusal,
  so no call from it may execute. A Responses `function_call` item whose status is `incomplete` is
  marked per call even when the enclosing response is `completed`.

Every pending call, including a malformed one, is retained in `pendingCallIds`, because the
protocols require one result item per call. `continueWithToolResults` refuses a missing, extra or
repeated answer, so a required continuation item cannot be dropped silently.

## Failure vocabulary

`HostedProviderFailure` separates `Transport`, `Authentication` (401/403), `Account` (402),
`RateLimited` (429, with `Retry-After`), `Rejected` (400/404/409/422), `Unavailable` (5xx),
`Redirected` (3xx — never followed, because the credential would be replayed against another
host), `ProviderState` (a 2xx payload reporting failed/cancelled/non-terminal, or a provider
finish code), `ModelMismatch` (the echoed model differs from the configured one), and
`InvalidResponse` (a 2xx payload that does not satisfy the protocol schema). Failure details are
bounded and censored of the configured credential. Model problems (malformed arguments, schema
violations, unknown tools) are per-call dispositions, and application tool outcomes remain S8.7's
vocabulary.

## Live protocol evidence

The bounded smoke (`HostedProtocolLiveSmokeTest`) runs only with
`AEGIS_S85_LIVE_PROTOCOL_SMOKE=1`, reads the developer trial credentials from the repository-local
`.env` in-process, and sends only synthetic probe text. It ran once for this attempt and completed
a two-turn tool-result exchange on each protocol:

| Protocol | Endpoint | Configured model | Budget | Turn 1 | Turn 2 |
| --- | --- | --- | --- | --- | --- |
| Gemini `generateContent` | `https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent` | `gemini-3.5-flash-lite` | 8192 output, `thinkingLevel: minimal` | `tool_calls`; `probe_echo{"value":"ALPHA-17"}` READY; echoed model matches | `complete`; “ALPHA-17 DONE”; echoed model matches |
| OpenCode Go chat completions | `https://opencode.ai/zen/go/v1/chat/completions` | `deepseek-v4.1-flash` | 8192 `max_completion_tokens`, parallel calls on | `tool_calls`; same call READY; echoed model matches | `complete`; “ALPHA-17 DONE”; second turn reported 256 cached prompt tokens |
| OpenCode Go Responses | `https://opencode.ai/zen/go/v1/responses` | `gpt-6-luna` | 4096 `max_output_tokens`, `reasoning.effort: medium` | `tool_calls`; same call READY; echoed model matches | `complete`; “ALPHA-17 DONE”; stateless item chaining |

Massaged wire semantics for each run are recorded in
`artifacts/sprint-8/evidence/S8.5/a1/live/`. Every record carries the URL, the header **names**
(never values), the exact synthetic request and response bodies, the classified outcomes and the
reported/configured model identity. A credential scan of those files found none.

### Responses endpoint prerequisite

The assignment required the Responses route and its authorization to be established, not assumed.
The primary OpenCode documentation lists the Go Responses route
`https://opencode.ai/inference/go/openai/v1/responses` (legacy alias `/zen/go/v1/responses`) for
its Responses-family models, and the same Go API key is the authorized credential for the Go
billing product. Discovery confirmed both routes answer with `billing_source: go` and the
`gpt-6-luna` model echo. No Console `/inference` route, vendor-direct endpoint or invented
authorization was used, and no protocol or model substitution occurred. There is therefore no
missing Responses prerequisite for this task.

## Verification

- `gradlew :app:testDebugUnitTest --tests io.github.triet4p.aegisf.provider.*` — 67 provider tests
  pass (`artifacts/sprint-8/evidence/S8.5/a1/unit-provider-tests.log`).
- `AEGIS_S85_LIVE_PROTOCOL_SMOKE=1 gradlew :app:testDebugUnitTest --tests …HostedProtocolLiveSmokeTest`
  — 3/3 protocol exchanges pass (`…/live/smoke-run.log`).
- Full unit suite result is recorded in the worker summary for this attempt.

## Limits and follow-ups

- No streaming, no retry/backoff and no prompt caching: those belong to S8.9/S8.11. A rate-limited
  or transport-failed turn is returned classified and is not retried here.
- Masking boundary (S8.6) is the only supported production caller: `MaskedHostedGateway` masks
  system/user instructions and tool-result JSON before `begin`/`continueWithToolResults`, keeps the
  protocol's required continuation items byte-identical, and returns an
  `Incomplete("masking_blocked")` turn with no dispatchable calls when inbound model text carries
  raw literals or foreign tokens. Raw adapter calls remain for tests; production must not bypass
  the gateway.
- No application tool adapter (S8.7), no executor policy (S8.9) and no settings
  persistence/validation UI (S8.12). Those callers do not exist yet; nothing else may supply
  raw content to the adapters on the production path.
- The live smoke proves protocol behavior with synthetic text. It is not model qualification, not
  a reliability sample and not Android-runtime evidence.
- The frozen S8.4 contract's Gemini `tool` role wording is contradicted by the live API; it is
  documented here rather than edited in the checkpointed contract.
