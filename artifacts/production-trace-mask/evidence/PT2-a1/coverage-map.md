# PT2 neutral-token coverage map (inspected paths)

Contract version: `aegis-agent-interface/4`; tools `aegis-f-s8-agent-tools/v4`;
skills `aegis-f-s8-agent-runtime-skills/v4`; context block `s8-agent-context/v9`;
token inventory `s8-agent-token-inventory/v2`.

## Tool declarations (`tools/slm/s8_agent_tools_v4.json`)

- Every `*_ref` argument is declared as the neutral "value alias" with a role hint scoped to
  the argument itself (`Value alias whose value parses as a calendar date, bound to… The alias
  is neutral; the host parses it for this argument and refuses a value that is not a date.`).
- No parameter description names an AMT/DATE/TIME/ACCOUNT/BANK token or a regex-derived
  semantic type.
- `record_event.clarification` keeps "a missing amount requires amount" only as the
  *missing-evidence* rule; the prompt/skills add that a masked value is present evidence.

## Bundled skills (`tools/slm/s8_agent_runtime_skills_v4.json`)

- No token/kind syntax remains; prerequisites and error recovery reference host-issued aliases
  and value aliases only.
- `event-reading`, `draft-search`, `link-event-draft` example argument names are role-specific
  (`date_ref`, `amount_ref`, `merchant_ref`) but the copied value is always the displayed alias.

## System/runtime prompt (`AgentContextManager`, context block v9)

- `token_syntax_rule`: a masked value is copied only as one complete listed token.
- `value_identity_rule`: a masked value is one opaque identity with a lexical shape hint; the
  shape never authorizes a field.
- `masked_evidence_rule` (v8): a value the boundary merely masked is present evidence; a
  clarification is only for a genuinely unresolved or ambiguous fact.
- Inventory renders token + lexical shape + display; never a mapping or a raw value.

## Runtime consumers

- `SensitiveVault`: `Shapes.detect` (canonicalization per role), neutral token emit
  (`V-…` / `T-…`), `parseAmount/parseDate/parseTime/parseTimeInstant/parseClockTime/parseYear/
  parseReference` role validators, `scanLeakShapes`, late-mask fold-in.
- `AgentToolRuntime`: `valueRefByArgument` picks by argument role; date args receive only a
  date-shaped alias, time args only a time-shaped alias; the same alias may appear in several
  arguments' candidate lists.
- `HostedGeneration`: legacy typed parse entry points now delegate to the neutral parsers
  (`parseDate(raw, year)`), so the executor/tool runtime keep one convention.
- `HostedResponsePrivacyRefusal`: `sensitiveShape`; `SafeInboundResponseDiagnostics.shapes`.
- `PublicStreamGate` + `MaskedHostedGateway.generateStreaming`: incremental public text gate
  (token-atomic cuts, mapped-surface prefix hold-back, unclosed-token refusal).
- `HostedSseDecoder`, `HostedStreamEvent`, `HostedHttpTransport.postStream`,
  `TracingHttpTransport.postStream` (now forwards the head frame),
  `OpenAiChatCompletionsAdapter/OpenAiResponsesAdapter/GeminiGenerateContentAdapter.streamGuarded`,
  `AgentTraceRecorder.providerStreamChunk/End`, `BoundedWorkflowExecutor` progress sink,
  `QuickCaptureScreen.runProgressSink`.
- `ProviderJson.textOrNull`: explicit JSON `null` is absent, never the text `"null"` (streamed
  tool names/ids, model id, finish reason, streamed text and metadata).

## Explicitly unchanged (frozen historical evidence)

- `tools/slm/s8_agent_tools.json`, `s8_agent_tools_v2.json`, `s8_agent_runtime_skills.json`,
  `s8_agent_runtime_skills_v2.json`, `s8_agent_runtime_fixture_v3.json`,
  `tools/slm/s8_agent_v3_qualification.py` results and `artifacts/**` historical reports.
- `docs/sprint-8-agent-interface-redesign.md` is the historical v3 design record; current
  behavior lives in `sprint-8-agent-contract.md`, `sprint-8-agent-executor.md` and
  `sprint-8-provider-adapters.md`.

## Residual search

See `residual-search.txt` in this directory: the only matches are the frozen v1/v2 eval inputs
and the historical redesign doc. No runtime consumer, bundled asset or current-behavior doc
uses field-semantic token types. The one `sensitiveKind` hit is a historical comment in
`HostedGeneration.kt` explaining the v3→v4 shape rename.
