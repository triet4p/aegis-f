# PT2-a1 worker artifact — masked-value usability + real streaming

- task_id: `PT2`
- attempt_id: `PT2-a1`
- agent_id: `MaskedFlowStreaming114`
- role: full-route implementation worker
- cycle: `PT2-cycle-1` round 1 (ready_for_review candidate)
- base commit: `6589c880537ee61a92fb99b91b70df5bc501930c` (PT1 checkpoint)
- repository: `F:/ai-ml/aegis-f`, target `refs/heads/main`, no commit/push by worker.

## Root cause (diagnosed from code + new discriminating traces)

1. **Role-typed token identity (the owner bug).** A masked value was minted as a token whose
   validator was the field role that detected it (`AMT`/`DATE`/`TIME`/`ACCT`/`BANK`), and the
   local parse entry points accepted only the matching token type. The owner's note states
   `10000`, `02/11`, `10h-11h`; the amount became an amount-typed token, so the model could not
   use it as a date/time argument and the host did not treat "the amount is masked" as present
   evidence. That is exactly the owner's report: the amount *was* in the note, but no supported
   path let the model use it, so it asked the owner to type it again.
2. **Streaming head frame dropped.** `TracingHttpTransport.postStream` recorded the response head
   but did not re-emit `HostedStreamFrame.Start`; every adapter therefore reported
   `Transport("the stream did not deliver a response head")` after a fully successful 200 SSE
   exchange (live run `run-813faa8cdd1a01127932bd535e5651e7`, FAILED/PROVIDER_FAILURE, 1055
   chunks). Fixed by forwarding the frame.
3. **Explicit JSON null stringified by `optString`.** Android's `optString` renders an explicit
   `null` value as the text `"null"`. Streaming providers send explicit nulls for omitted
   optionals, so tool names corrupted (`select_sourcenullnullnullnullnull` in the real UI) and
   model/finish-reason metadata did too. Fixed with `ProviderJson.textOrNull` in all streamed and
   complete-body protocol parsers.
4. **Streamed tool calls without ids.** The live Go route omits `tool_calls[].id`; assembly then
   failed as "tool call carried no id". The adapter now mints a stable unique local id for that
   turn only (it is the pairing handle for the next request, never a sensitive value).
5. **False-positive decoded leak refusal.** After fix (2), the next live run
   (`run-2c248152ee8f71e97569d28ed90537bd`) failed `MASKING_BLOCKED / DECODED_SENSITIVE_MATCH`
   because the naive substring leak scan matched the mapped time surface `11h` inside a
   provider-chosen call id (`call_…sk11h6ax0`) and inside usage numbers. The decoded-JSON leaf
   scan now requires a value boundary (`scanValueLeak`): neither neighbour may be an ASCII letter
   or digit; the conservative raw-wire `scanLeak` used for serialized-body sanity stays naive.

## Neutral identity cutover (owner-approved, `aegis-agent-interface/4`)

- `SensitiveVault`: one opaque token per value; `Shapes.detect` keeps role-specific
  canonicalization but the token carries no role; role validation moved to
  `parseAmount|parseDate|parseTime|parseTimeInstant|parseClockTime|parseYear|parseReference`;
  late-masked values fold into their existing token.
- `AgentToolRuntime.valueRefByArgument`: the host chooses a value's meaning by the argument it is
  passed to; only date-shaped tokens are offered for date arguments and only time-shaped tokens
  for time arguments; a role mismatch is a field error, never a missing value.
- `HostedGeneration` legacy typed entry points now delegate to the neutral parsers.
- Diagnostics: `sensitiveShape`, `SafeInboundResponseDiagnostics.shapes`; no
  ACCOUNT/AMOUNT/DATE/TIME semantic kind in runtime paths, exports or traces.
- Prompt/context block `s8-agent-context/v9`; inventory `s8-agent-token-inventory/v2`; tool
  declarations `aegis-f-s8-agent-tools/v4`; skills `aegis-f-s8-agent-runtime-skills/v4`;
  qualification fixture contract `aegis-agent-interface/4` (v4 asset paths).
- Coverage map: `artifacts/production-trace-mask/evidence/PT2-a1/coverage-map.md`.
- Residual search: `artifacts/production-trace-mask/evidence/PT2-a1/residual-search.txt` (only
  frozen v1/v2 eval inputs and the historical redesign doc match; no runtime consumer).

## Real streaming (implemented, not simulated)

- `HostedSseDecoder` (frame/line/`data:` boundaries incl. multi-line data and `[DONE]`),
  `HostedStreamFrame`, `HttpStreamTransport.postStream` (incremental read, bounded total,
  non-2xx head delivered as a body frame), `TracingHttpTransport.postStream` (records every
  chunk + terminal `INTERRUPTED`/`UNAVAILABLE`, forwards the head, records the assembled body).
- `streamGuarded` for Chat Completions, Responses and Gemini: delta assembly, tool calls executed
  only when the assembled argument object is complete and schema-valid, provider
  complete/error/cancel classified like a complete-body turn, cancel rethrown and marked
  interrupted.
- `MaskedHostedGateway.generateStreaming` + `PublicStreamGate`: incremental public text is gated
  per flush (masked values held back by surface length, token spans atomic, unclosed `⟦` refused),
  private reasoning never becomes progress, and the assembled body/parsed turn are still scanned
  before publication or dispatch. A route without an honest stream reports
  `StreamingUnavailable(reason)` and runs the complete-body exchange.
- Executor progress sink → `WorkflowRunProgress` → chat UI (`agentStreamText += text`, tool name
  notes) in `QuickCaptureScreen`.
- Tests: `HostedSseDecoderTest`, `HostedStreamingAdapterTest` (incl. explicit-null regression and
  truncated-call non-dispatch), `PublicStreamGateTest`.

## Actual-main UI/DB acceptance (installed app `io.github.triet4p.aegisf`, same-signer update)

Only the owner's existing configured provider was used (OpenCode Go · DeepSeek V4.1 Flash);
no credential was read, printed or changed; no financial confirmation and no ledger/transaction
row was created. Existing captures were interpreted (reading only).

| case | input | actual result |
|---|---|---|
| 1. Owner note, plain integer + informal date/time interval | `Ngayd 02/11 lúc khoảng 10h-11h … giá 10000` | **PASS** — run `run-55294f2f83409f2ed0fcd6874ae73b24` SUCCEEDED; event #2: amount 10000, date 2026-11-02, time 10:00, interval end 2026-11-02 11:00, Asia/Ho_Chi_Minh, status INTERPRETED, no unresolved reason. Streamed answer shown incrementally and verified. |
| 2. Unit-formatted money + date + interval | `Ca phe 25.000 VND ngay 22/10 tu 9h den 10h` | **PASS** — run `run-8a4882e73dda1430292abb880f834940` SUCCEEDED; event #3: amount 25000, date 2026-10-22, 09:00–10:00 interval, INTERPRETED (one mid-run provider failure repaired/continued; run still succeeded). |
| 3. Ambiguity / no-match / clarification-resume, live provider | — | **NOT RUN live.** The chat's capture form was no longer reachable after the long conversation without further UI surgery; reported, not claimed. These paths are covered by the passing unit suite (`BoundedWorkflowExecutorTest` clarify/resume, no-match, foreign-token terminal, ambiguity rules; scripted provider). |

Evidence screenshots: `artifacts/production-trace-mask/evidence/PT2-a1/device/`
(`02`, `03` chat/composer; `06`/`07` stream started + live tool progress; `12`–`14` fixed build
live progress; `17`–`19` case-1 streamed answer and verified effect; `20` post-tab-switch state
loss; `21`–`25` case-2). Sanitized DB summary (no DB copy, no raw bodies):
`artifacts/production-trace-mask/evidence/PT2-a1/db-summary.json` — includes the failed interim
attempts (PROVIDER_FAILURE head-frame bug, MASKING_BLOCKED false positive) alongside the two
successful runs, so the fix sequence is traceable.

Trace content of the successful case: `PROVIDER_REQUEST` 4, `PROVIDER_RESPONSE` 4 (assembled
`streamed:true, assembled:true`), `PROVIDER_STREAM_CHUNK` 843, `PROVIDER_STREAM_END` 4,
`TOOL_CALL` 4, `TOOL_RESULT` 4, `RUN_ADMITTED`/`ATTEMPT_STARTED`/`ATTEMPT_OUTCOME`. No auth header
or credential is stored.

## Checks run (final candidate)

- `:app:testDebugUnitTest --offline` — **673 tests, 0 failures** (73 result files).
- `:app:compileDebugAndroidTestKotlin --offline` — **PASS**.
- `:app:assembleDebug --offline` — **PASS**; APK sha256
  `17986d2251c8fc6e21ba83daf245f6748d475a03f49922cd8a4f7c598859395b`.
- Installed with `adb install -r` over the existing package (data preserved); actual-main UI runs
  above used that APK.

## Docs updated

`docs/sprint-8-agent-contract.md` (§4 neutral identity, inventory shapes, v4 closed set),
`docs/sprint-8-provider-adapters.md` (real streaming + limits), `docs/sprint-8-agent-history.md`
(PT2 policy decision + tab-switch disclosure), `CHANGELOG.md`. Frozen historical eval inputs and
the historical redesign doc were left unchanged; no version-wording assertion tests were pinned.

## Local model

Pinned `receptron/Laya@68f27dfe` bundle staged on the phone at
`/sdcard/Download/Laya-model/` (`laya.onnx` 3,807,291 B; `laya.onnx.data` 1,685,258,240 B;
`laya_config.json` 369 B) with size verification. **App-level local inference readiness is NOT
claimed**: it was not exercised in this attempt.

## Remaining limitations (disclosed, not hidden)

- **Tab switch still loses displayed chat state** (confirmed live: `device/20-tabswitch-after.png`).
  The composer's run/continuation/stream state is composable-scoped; the durable DB row keeps the
  true state. This correction did not redesign the Chat lifecycle.
- Gemini and Responses streaming are implemented and unit-tested against scripted SSE transports,
  but only the configured Chat Completions route was exercised live.
- Live ambiguity/no-match/clarification-resume runs are not performed (unit coverage only).
- The `Ca phe` capture card in the UI still displayed "unresolved · no interpretation recorded"
  after its event was stored; the durable event is correct (event #3), so this is a stale capture
  projection, not a data loss — noted for a later UI pass.

## Freeze and manifest

Source frozen at ready_for_review; owned-path hash manifest:
`artifacts/production-trace-mask/evidence/PT2-a1/manifest.sha256` (54 source/docs paths +
header; deleted v3 assets recorded as DELETED).
