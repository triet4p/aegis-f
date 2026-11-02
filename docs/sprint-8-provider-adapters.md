# Sprint 8.5 — hosted provider adapters

**Status:** The provider-neutral generation contract and three native protocol adapters are in
production use through the S8.12 settings and Chat surfaces. This document's adapter protocol
evidence is not itself model qualification: it records earlier bounded synthetic provider probes.
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
- `HostedGenerationAdapter` — `begin`, `generate`, `continueWithToolResults`,
  `appendUserNotice`. Adapters never
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
- Responses chaining is **stateless**: retained items are resent instead of naming a
  `previous_response_id`, so no continuation is stored on the gateway. Reasoning items retain their
  required identity and opaque `encrypted_content`; the adapter sends the required `summary: []`
  field and drops plaintext `content` and `text` from each continuation.

The Chat adapter also deliberately does not retain the gateway's `reasoning_content`: the live
exchange continues correctly without it, and hidden reasoning must not enter stored conversation
state.

### Response channels and the host tail

The decoded-body privacy guard is channel-aware. Provider reasoning/thinking — Gemini `thought`
parts and `thoughtSignature`, Chat Completions `reasoning_content`/`reasoning`, Responses items with
`type: "reasoning"` (including their `summary` and `encrypted_content`) — is classified explicitly
and is **not** guard input: it is never displayed, rehydrated or treated as authority, so invented
notation inside it cannot refuse a valid response. A Gemini part with `thought: true` contributes
no `functionCall` to the parsed turn even when its call bytes are schema-valid; only ordinary
parts dispatch. Answer text and tool-call `arguments`/`args` stay guarded, and the parsed answer
is checked again before publication and the parsed argument text again before dispatch. Retained
continuation items retain protocol pairing (`thoughtSignature`, Responses item `id`/`type`/`status`/
`call_id`) and opaque `encrypted_content`; the whole item is not byte-identical when a Responses
reasoning item is reserialized: `summary` is empty and plaintext `content`/`text` are removed.
Plaintext reasoning therefore never rides the next request, while required pairing remains valid.
Reasoning is never promoted into answer text.

`continueWithToolResults` also carries the host's issued-token inventory tail and lowers it
natively after the tool outputs: an extra `role: "user"` message for Chat Completions, an extra
`input_text` user item for Responses, and an extra `text` part inside the same `user`
function-response turn for Gemini. The next model turn therefore always sees the run's current
inventory without creating an unmatched call or an extra turn alternation.

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
`InvalidResponse` (a 2xx payload that does not satisfy the protocol schema, including a malformed
usage counter). Failure details are bounded and censored of the configured credential; the
pre-parser privacy guard reports invalid counters with a safe protocol-error reason, not as a
decoded-sensitive refusal. Model problems (malformed arguments, schema violations, unknown tools)
are per-call dispositions, and application tool outcomes remain S8.7's vocabulary.

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

- Streaming: all three protocol adapters implement `streamGuarded` over the
  `HttpStreamTransport` seam (`HostedSseDecoder` for SSE frame boundaries, incremental tool-call
  JSON assembled but dispatched only as complete validated calls, provider complete/error/cancel
  classified like a complete-body turn). Streaming is opt-in per validated route
  (`streamingEnabled`); a route without an honest stream reports
  `StreamingUnavailable(reason)` and still runs the complete-body exchange, never simulated
  typing. The masked gateway re-gates every incremental public text fragment through
  `PublicStreamGate` (a masked value, foreign/unknown token or unclosed token delimiter is never
  shown while still incomplete) and still scans the assembled response body and parsed turn
  before publication or dispatch. The PT1 trace stores each `PROVIDER_STREAM_CHUNK` event and the
  assembled canonical body, with `INTERRUPTED`/`UNAVAILABLE` terminal states for cancel/failure;
  no body is invented for a stream that produced no turn. `TracingHttpTransport` forwards the
  response head frame so the adapter can classify the exchange.
- No retry/backoff and no prompt caching: those belong to S8.9/S8.11. A rate-limited
  or transport-failed turn is returned classified and is not retried here.
- Masking boundary (S8.6) is the only supported production caller: `MaskedHostedGateway` masks
  system/user instructions and tool-result JSON before `begin`/`continueWithToolResults`, keeps the
  protocol's required continuation items byte-identical, and scans decoded keys, strings and
  numeric leaves across the complete strict provider JSON before adapter status parsing or protocol
  decoding. Three things can be treated as technical metadata, and each only after schema
  validation against the adapter's own validated configuration (`HostedResponseMetadataPolicy`):
  the protocol-declared root fields (opaque provider-assigned `id`/`responseId` as bounded nonempty
  printable ASCII, the configured `model`/`modelVersion` echo, the fixed `object` discriminator,
  and the nonnegative integer `created`/`created_at` timestamp), the exact protocol usage-counter
  paths, and — only when the configured endpoint is exactly the OpenCode Go Zen route — the two
  documented `usage.opencode.allowance`/`list_cost` USD amounts as finite nonnegative JSON numbers.
  Nothing else is exempt: usage containers, unknown fields, sensitive keys, arbitrary nested
  `id`/`model`/`usage` lookalikes, model content, reasoning and tool arguments stay fully scanned,
  and the same values under any of those paths remain blocked. Wrong-type, negative, non-finite,
  fractional-timestamp, malformed-id or off-route metadata values are safe protocol errors;
  duplicate keys, malformed/trailing JSON and unsupported roots fail closed as
  `Incomplete("masking_blocked")` with no dispatchable calls or continuation. Privacy refusals carry
  a fixed reason, optional sensitive-kind label, node type and allowlisted structural path, while a
  schema-validated exemption reports only its kind, path and node type. Masking-refusal evidence
  records neither response values nor reasoning; serialized-body matches are diagnostic only. The
  separate local production trace (PT1, `agent_trace_events`) retains the complete provider
  request/response bodies — including returned reasoning and continuation items — locally only; it
  is never exported or uploaded and never stores authentication headers or credentials. Known
  session-scoped tokens remain allowed; raw adapter calls remain for tests; production must not
  bypass the gateway.
- Production Chat supplies the adapters through `MaskedHostedGateway`, the typed application
  tools and `BoundedWorkflowExecutor`. S8.12 settings and credential handling live in
  `HostedProviderSettingsStore` and `HostedProviderSettingsScreen`; the provider profile is
  validated before a synthetic connection probe can save it.
- The live smoke recorded above proves earlier protocol behavior with synthetic text. It is not
  model qualification, a reliability sample, or S8.12 Android UI verification.
- The frozen S8.4 contract's Gemini `tool` role wording is contradicted by the live API; it is
  documented here rather than edited in the checkpointed contract.
