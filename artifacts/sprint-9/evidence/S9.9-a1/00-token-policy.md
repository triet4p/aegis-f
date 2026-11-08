# S9.9 — output-token policy: before, change, and observed wire behavior

Scope: the hosted provider contract and the path the real Chat screen uses to start a run
(`QuickCaptureScreen` → `SavedHostedProvider.validatedConfig()` → adapter request). Turn/tool
limits (12 turns / 24 tools), context bound, and worker settings are unchanged and are not part
of this change.

## 1. Observed before state (material risk)

`HostedProviderPresets` seeded every profile with a fixed `maxOutputTokens` (8192 for Gemini,
Gemma and DeepSeek; 4096 for the Responses preset), `HostedGenerationBudget.maxOutputTokens`
was required, and each adapter always serialized it:

- Gemini: `generationConfig.maxOutputTokens`
- Chat Completions: `max_completion_tokens`
- Responses: `max_output_tokens`

S9.8's real Compose Chat attempt on the enlarged inbox (`artifacts/sprint-9/workers/S9.8-a2.md`)
ended `TRUNCATED_TURN` twice with `completion_tokens=8192`, `reasoning_tokens=8192`, **0 tool
calls**: the fixed cap was consumed by reasoning before the first tool call. That instrumentation
success did not prove the UI path.

## 2. Change on the final candidate surface

- `HostedGenerationBudget.maxOutputTokens: Int? = null`. `null` omits the protocol's limit field
  entirely; the provider applies the selected model's own default. A non-null value is still sent
  verbatim and is bounded only by a declared model limit.
- All three adapters emit the field only when it is set. Gemini also omits an empty
  `generationConfig`.
- Presets now default to `null` (model/provider default) and never seed a cap.
- `SavedHostedProvider.maxOutputTokens` is nullable and persisted as JSON null. A stored value
  below `MIN_EXPLICIT_OUTPUT_TOKENS` (32768) is read as the model default: values below the
  explicit minimum predate this policy (the former fixed 8192/4096 preset caps) and are corrected
  on read; the stored document is rewritten only on the next save.
- The settings screen treats a blank field as the model default and refuses an explicit value
  below 32768 with a message; it no longer displays a fixed generation limit.
- Cache identity renders an unset budget as `maxOutputTokens=provider-default`, so a default and
  an explicit budget are different identities.

Compatibility: the three protocols document these limit fields as optional, so omission is
supported rather than substituted. No lower value is used for a model that declares a larger
capability; a configured value above a declared model limit is still a configuration refusal.

## 3. Observed after state on the isolated phone (real production config/adapters)

Device `AAAJBB4930914141`; package `io.github.triet4p.aegisf.s812evidence`; app APK
sha256 `48e607026b7ea6835f87aa4d586eef4675515bacec336b1d8e79d421f199687f`.

`S99WorkflowPhoneInstrumentedTest` stage `wire` serialized the actual first request of the
configured production profile through the production adapter with a capturing transport
(`device-wire.log`):

```
S99WIRE preset=google-gemini-flash-lite profileBudget=null
  canonical=maxOutputTokens=provider-default;reasoningEffort=-;temperature=-;parallelToolCalls=-
  serialized=[]
S99WIRE explicit=maxOutputTokens=32768;reasoningEffort=-;temperature=-;parallelToolCalls=-
S99WIRE explicitFloorRefused=An explicit output limit must be at least 32768 tokens. Clear the field to use the model's own default.
```

The profile stored by the earlier build carried 8192; it now reads and sends as the model
default. A live hosted run (`device-arrival_a.log`) serialized **no limit field on any request**
and recorded the run without a forced cap:

```
S99REQUEST ... index=0..10 serializedLimits=[]
S99ADMITTED payload=…"maxOutputTokens":null…
S99RUNMETA providerMaxOutputTokens=null canonical=maxOutputTokens=provider-default;… turns=11 tools=21
S99RUN Completed(turns=11, tools=21)
```

## 4. Not conflated with other bounds

- The 12-turn / 24-tool safety policy is unchanged (`limitTurns=12 limitTools=24` in
  `S99RUNMETA`).
- The context-character bound (160000) and capture-observation bound are unchanged.
- No worker execution or agent-definition setting was touched.
- Truncation is still reported honestly as `Incomplete(TRUNCATED_TURN …)`; no completion is
  fabricated.

## 5. Limits of this evidence

- The wire proof covers the configured Gemini and DeepSeek production profiles on this device.
  The Gemma and Responses presets are covered by the provider unit contract tests (the same
  optional-field behavior) but were not configured with a credential on this device.
- The explicit 32768 value is asserted through the production profile validation and cache
  identity; a live request with an explicit 32768 was not needed to satisfy the owner policy and
  was not sent.
