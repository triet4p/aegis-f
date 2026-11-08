# S9.8-a2 — pre-run evidence freeze

Captured **before** any new fixture cleanup, code edit, or model call of attempt S9.8-a2.

- Task S9.8, attempt S9.8-a2, runtime agent `S98WorkflowRecovery` (`gold-task`, full route).
- Review cycle `S9.8-cycle-1` round 1 — 0 assessed rounds at the time of writing.
- Repository worktree `F:/ai-ml/aegis-f`, Git dir `F:/ai-ml/aegis-f/.git`, ref `refs/heads/main`,
  base `b453f9580b1b124c30dd41e1352e2f0aa5a36b48` (verified with `git rev-parse HEAD`).
- Isolated package `io.github.triet4p.aegisf.s812evidence` (+ `.test`); owner main app
  `io.github.triet4p.aegisf` not opened, cleared, exported or uninstalled.
- Device `AAAJBB4930914141` (`adb devices` → `device`), installed isolated app
  `versionName=1.0`, `lastUpdateTime=2026-11-05 22:23:34`.

## 1. Source revisions at a2 start (git hash-object, working tree)

| path | blob |
|---|---|
| `app/src/main/java/io/github/triet4p/aegisf/agent/AgentToolRuntime.kt` | `0eb1db285a0ec6f33652eb8f46f741817a242c7c` |
| `app/src/main/java/io/github/triet4p/aegisf/agent/BoundedWorkflowExecutor.kt` | `1f42dde833679e32ab5c1f15f6ca1dc5aaea0540` |
| `app/src/main/java/io/github/triet4p/aegisf/privacy/SensitiveVault.kt` (a1 fix present) | `8d2686de2433b13481c890023a5892132d0c5a0c` |
| `app/src/main/java/io/github/triet4p/aegisf/provider/ProviderJson.kt` | `47d46ca8c9aa9e76f59873d070ddb602a0625840` |
| `app/src/main/java/io/github/triet4p/aegisf/provider/OpenAiChatCompletionsAdapter.kt` | `bbc380fa7f4f56b9921fc82c07d52f9c14ba3ba4` |
| `app/src/main/java/io/github/triet4p/aegisf/provider/GeminiGenerateContentAdapter.kt` | `64f9b4a29fc72608574967acc03b51c610593a23` |
| `app/src/main/java/io/github/triet4p/aegisf/provider/OpenAiResponsesAdapter.kt` | `5ade39cd973ce5c9489c6ab2dd8dd41d7781ab9e` |
| `tools/slm/s8_agent_tools_v8.json` | `804b3603d6352ab1196482acb9a20640ed381f4a` |
| `tools/slm/s8_agent_runtime_skills_v8.json` | `31fd2ac3e9f250bbbe65d92b80ede138edaf6c98` |

## 2. a1 failing run — what the durable log actually preserved

Source: `artifacts/sprint-9/evidence/S9.8-a1/device-s98-evidence-live.log` (durable device log,
copied at a1 close). Preset `s98-deepseek` = offered `OpenCode Go · DeepSeek V4.1 Flash`
(`OPENAI_CHAT_COMPLETIONS`), language EN, **production limits 12 turns / 24 tools**, post-`d`
shorthand fix.

```
S98LIVE profile=s98-deepseek lang=EN ambiguity=false budget=production-12/24 outcome=Completed
        events=0 fuelLink=[] snackLink=[] fuelCategory=null snackCategory=null ledger=0 turns=17
        tokens=1942 rawOnWire=false toolResultMasked=true toolResultRaw=false
        continuationTokens=true followUp=completed:Completed:null followUpTurn=true
        followUpRawOnWire=false providerUnavailable=false
S98TRACE lang=EN kinds={ATTEMPT_OUTCOME=1, ATTEMPT_STARTED=1, FOLLOW_UP_MESSAGE=1,
        PROVIDER_REQUEST=17, PROVIDER_RESPONSE=17, RUN_ADMITTED=1, TOOL_CALL=22, TOOL_RESULT=22}
S98TOOLS lang=EN [select_source, select_source, select_source, make_time_anchor, make_time_anchor,
        make_time_anchor, make_time_anchor, make_time_anchor, make_time_anchor, list_drafts,
        list_drafts, observe, list_drafts, observe, select_source, select_source, make_time_anchor,
        make_time_anchor, observe, observe, observe, observe]
```

Initial capture observation value aliases (issued in that run):

```
capture_values=[{"ref":"r_f4f71aa22b50339e4a2697da","kind":"value","shape":"numeric","display":"⟦V-s98f4eb6d672-0001⟧"},
                {"ref":"r_f39f453be2dbdf75c0abd6f8","kind":"value","shape":"numeric","display":"⟦V-s98f4eb6d672-0002⟧"}]
```

`record_event_amount_refs=[]` — **no `record_event` call was ever made** in that run.

First failing tool result (log line preserved verbatim; the a1 test truncated tool results at 300
characters, so the constraint text is cut):

```
S98TOOLRESULT lang=EN index=3 body={"contract":"aegis-agent-interface\/8","tool":"make_time_anchor",
"outcome":"error","dispatch":"dispatched","effect":"none","verified":false,"data":null,
"operation_ref":null,"observations":[],"error":{"code":"UNKNOWN_REFERENCE","category":"reference",
"issues":[{"path":"$.end_date_ref","constraint":"i
```

Reading that cut correctly: the only `constraint` values in `AgentReferenceRegistry.resolve` are
`nonblank_reference` (`n…`) and `issued_in_this_run` (`i…`). The observed first character `i`
therefore means **`issued_in_this_run`**: the model supplied a nonblank string for `end_date_ref`
that was never issued as a handle in that run — not an empty value and not null.

`source_ref` / value refs issued in that run remain in the log excerpt:
`select_source` #1 `r_8ab397b1f85e2877167ee693` quote `Today fuel ⟦V-s98f4eb6d672-0001⟧ at
⟦V-s98f4eb6d672-0002⟧`; #2 `r_daa496f0e26d7542b44750cf` quote `snack at Circle K
⟦V-s98f4eb6d672-0003⟧ at ⟦V-s98f4eb6d672-0004⟧`; #3 `r_0896934b62ed21e6225939a9` quote `Circle K`.

Model's final text (excerpt): “I hit a hard argument-transport blocker and want to report it
precisely rather than write anything misleading.”

**What is NOT preserved and must not be inferred:** the exact string the model put in
`end_date_ref`. The a1 harness recorded only tool names and 300-character result excerpts, and the
run's synthetic DB (the only place holding `TOOL_CALL.argumentsJson`) was deleted by that test's
own cleanup. Any claim about whether the model passed a display token, a day word or a fabricated
handle would be an unverified inference.

## 3. a1 UI run (Gemini 3.5 Flash Lite, VI) — valid-reference use for contrast

Source: `artifacts/sprint-9/evidence/S9.8-a1/device-s98-evidence-device.log` (production trace
dump; this is the real-UI run, not the DeepSeek EN run).

```
make_time_anchor argumentsJson={"date_ref":"r_5b4c6ab8c6afeac0823b893b","end_date_ref":null,
                              "time_ref":"r_0e1b2734be662b82691168d1","end_time_ref":null}
→ outcome ok, anchor_ref r_98865592805d9afd672f7146
```

This shows the production contract *does* resolve issued handles and JSON nulls for the end
fields on a real hosted call. It does not explain the DeepSeek argument.

## 4. Contract facts relevant to the discriminator (code, not inference)

- Handle shape is `r_[0-9a-f]{24}` (`AgentReferenceRegistry.HANDLE_PATTERN`); the masked display
  token shape is `⟦V-<scope>-NNNN⟧` (`SensitiveVault`). They are different identifiers for the
  same masked value: `select_source.values[]` carries `ref` (handle) and `display` (token).
- `make_time_anchor` resolves `date_ref` with kinds `{VALUE, RELATIVE_DATE}` and the other three
  with kind `VALUE`; `null`/absent → no component; blank string → `UNKNOWN_REFERENCE
  nonblank_reference`; unknown nonblank → `UNKNOWN_REFERENCE issued_in_this_run`; issued but
  non-date value → `TEMPORAL_CONSTRAINT`; wrong kind → `REFERENCE_KIND_MISMATCH`.
- Declaration `tools/slm/s8_agent_tools_v8.json` marks **all four** `make_time_anchor` parameters
  `required`, while `time_ref`/`end_date_ref`/`end_time_ref` admit JSON null. Adapters lower
  `["string","null"]` to `nullable: true` (`ProviderJson.openAiSchema` / `geminiSchema`).
- Serialized generation limits exist on every offered protocol:
  `GeminiGenerateContentAdapter` → `generationConfig.maxOutputTokens`,
  `OpenAiChatCompletionsAdapter` → `max_completion_tokens`,
  `OpenAiResponsesAdapter` → `max_output_tokens`, all from `config.budget.maxOutputTokens`
  (offered presets: 8192 / 8192 / 8192 / 4096).
- Production Chat limits are `WorkflowRunLimits()` = `maxTurns=12`, `maxToolCalls=24`
  (`BoundedWorkflowExecutor.kt:107-123`); a1 did not change them and a2 will not.

## 5. Immediate next actions (recorded before execution)

1. Extend the S9.8 instrumentation so a run preserves, **before** its synthetic DB is deleted:
   full per-call `argumentsJson`, full tool-result envelopes, issued `values`/`relative_dates`/
   `anchors`, the serialized output-limit field, and the applied run limits.
2. Add a no-model discriminator stage against the production `AgentToolRuntime` mapping
   candidate `end_date_ref` inputs to exact code/constraint.
3. Run one bounded DeepSeek EN production 12/24 run and read the captured argument.
