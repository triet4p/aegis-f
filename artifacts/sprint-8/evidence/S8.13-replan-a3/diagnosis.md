# S8.13 replan-a3 — bounded diagnosis

**Status: NOT QUALIFIED; owner stopped remaining measurements.** No source, prompt, fixture, or Main plan/state files were changed in this attempt. Candidate-a is Gemini 3.5 Flash Lite (one measured repeat, owner-stopped at 1/3); candidate-b is DeepSeek V4.1 Flash through the configured OpenCodeGo Zen chat-completions endpoint (two measured repeats, owner-stopped before pass 3). No hosted run is active. No `ready_for_review`, PASS, checkpoint, or S8.14 advancement is warranted.

## Gates and evidence

Frozen thresholds remain dev `39/48`, heldout `17/24`, repair/replay `7/9`, masking 100%, and zero unauthorized/financially incorrect applied effects. Detailed score records:

- `candidate-a/pass-1.runtime-review.json`: `5/16` dev, `2/8` heldout, `0/3` repair. The owner explicitly stopped A passes 2/3; this is 1/3 measured coverage, not 3/3. Even two perfect hypothetical remaining repeats cap A at `37/48` dev and `6/9` repair.
- `candidate-b/pass-1.runtime-review.json`: `5/16`, `2/8`, `0/3`.
- `candidate-b/pass-2.runtime-review.json`: `5/16`, `2/8`, `0/3`. B3 was explicitly stopped/not started.

Measured B total is `10/32` dev, `4/16` heldout, `0/6` repair; even a perfect hypothetical B3 would cap the full pool at `26/48`, `12/24`, `3/9`, below all three thresholds. Both candidate configurations are mathematically disqualified; no threshold is relaxed.

Run records are under `artifacts/sprint-8/evidence/S8.13-replan-a3/candidate-{a,b}/`. Batch snapshot: `S8.13-runtime-fixture-v2-batch-02`, SHA-256 `49d8e241792208447db851a028b634349fdec38b3fb319630554a958bbb66163`; the two B host records share the verified isolated APK hashes and package identity in their host JSON.

### Masking, effects, operation, and usage

- Outbound raw-absence scans: A1 `24/24`; B1 `24/24`; B2 `24/24`. No raw-sensitive egress was detected in the recorded outgoing requests. This is distinct from inbound findings.
- Inbound raw-absence scans: A1 `24/24`; B1 and B2 `23/24` each. In both B repeats, `a8-laya-02` had an inbound provider response flagged by the raw-sensitive scanner and replaced with `[raw_sensitive_response_redacted]` before durable evidence storage. The hidden content cannot be inspected; this flag does not establish that a secret crossed outbound egress. No outgoing request scan flagged a raw value.
- No measured run applied a ledger row, out-of-scope row, or unexpected mutation. Safe application denials (including `attach_policy`) and the B2 unrequested interpretation attempt that was denied are retained separately in the case traces; they are not counted as applied effects.
- B1: 49 HTTP 200 provider exchanges, 0 transport/provider failures or truncations, 49/49 configured model echoes; 42 workflow turns, 28 app tool calls, 42 usage records (7 provider turns had no usage record); reported totals 117,032 input, 34,415 output, 22,347 reasoning, 91,776 cached input tokens; 19 cached-prefix reuse / 5 not reported; case latency range 5,515–53,252 ms.
- B2: 53 HTTP 200 exchanges, 0 transport/provider failures or truncations, 53/53 model echoes; 45 workflow turns, 30 app tool calls, 45 usage records (8 provider turns had no usage record); reported totals 133,240 input, 33,688 output, 21,015 reasoning, 112,896 cached input tokens; 17 cached-prefix reuse / 7 not reported; case latency range 6,187–56,316 ms.
- OpenCodeGo response metadata exposes per-response `cost`, `list_cost`, and `allowance`; no complete aggregate or account-level charge/quota was observed or inferred. No credentials were recorded.

## Proven implementation/runtime failure causes

### 1. Date-only readings acquire a timezone and are rejected

`BoundedWorkflowToolAdapter.checkedEventInterpretation` takes a date-only canonical anchor and still writes `trustedTimezoneId` into the resulting `NoteEventTimeAnchor` (`app/src/main/java/io/github/triet4p/aegisf/agent/BoundedWorkflowToolAdapter.kt:413-424`). `EventContextRepository.timeError` rejects any non-null timezone when `time == null` with “A stated time zone needs the time it anchors” (`app/src/main/java/io/github/triet4p/aegisf/data/EventContextRepository.kt:517-535`). B2 tool results show this exact denial for date-only updates; no event was stored in `a8-adv-01`, `a8-privacy-01`, and `a8h-adv-01`. B2 `a8-adv-03` made an unrequested interpretation attempt and received the same denial; the refusal request itself was not completed. The UI already only supplies its timezone when a time field is nonblank (`app/src/main/java/io/github/triet4p/aegisf/ui/QuickCaptureEvents.kt:749-756`).

**Smallest correction to consider (not applied):** only attach the trusted timezone when the canonical anchor has a time (or otherwise define the date-only anchor contract consistently). **Discriminating local check:** service/adapter unit test with a date-only interpretation and a trusted capture timezone; assert the reading is stored with date and no time, and no clarification is required. Existing overbudget tests are separate and already passed in the prior recorded verification.

### 2. One host operation key is reused for different mutations in a case

`WorkflowToolSession` carries one host operation key (`BoundedWorkflowToolAdapter.kt:33-39`); `updateEvent` passes that same `session.operationKey` to every update (`BoundedWorkflowToolAdapter.kt:344-370`). The command service guarantees same-key/same-request replay, but same-key/different-request conflict (`app/src/main/java/io/github/triet4p/aegisf/data/WorkflowCommandService.kt:44-50`; the update digest includes capture, revisions, spans, and interpretation at `:313-335`). B2 `a8-multi-01` records a lunch update applied, then a distinct taxi interpretation conflicted, including retries after an unchanged source revision; only the first event remains. The adapter-owned key is therefore not an identity suitable for multiple distinct writes in one workflow case. The model's description of this as a stale-revision conflict was contradicted by the same unchanged source state and the key/payload contract.

**Smallest correction to consider (not applied):** give each distinct mutation a distinct opaque host-owned key; reuse that key only for an exact repeat of that same mutation. Do not expose keys to the model. **Discriminating local check:** two different event writes in one session both apply under different host keys; replaying either exact request returns NoChange with unchanged state; same-key/different-payload remains a conflict.

### 3. The hosted agent did not execute requested identical replays

The B1/B2 `a8-retry-01` and `a8h-retry-01` traces each show one applied update and no second tool dispatch. Their final text explicitly says the repeat was not issued and describes a hypothetical no-change instead. Therefore all exact replay cases fail; the repair pool is `0/3` per measured pass. The frozen service contract explicitly says an identical request under the same key is replayed without changing state (`WorkflowCommandService.kt:44-50`); `replayOf` returns `NoChange` for a previously recorded exact request (`WorkflowCommandService.kt:1434-1455`). The frozen runtime skill instead says “Retry only after a fresh observation and a corrected grounded request; never replay a stale revision” (`tools/slm/s8_agent_runtime_skills.json:22-29`).

**Observed:** no actual identical replay was dispatched. **[INFERENCE]** The model’s stated fear that the first write made the identical request stale, combined with the skill wording, plausibly explains the omission; causal attribution to the skill is not proven. **Smallest correction to consider (not applied):** distinguish an explicit identical replay request from a corrected retry: repeat the exact payload using the same host operation identity and verify the actual NoChange response. **Discriminating local check:** adapter/service replay test with the exact same call, same host key, `Applied` then `NoChange`, one operation row, equal before/after state digest; no hosted API call required.

## Frozen instruction/task inconsistencies and hypotheses

- **Clarification partial-write conflict:** `s8-event-extract` says that a missing field requires a focused question with “no interpretation write until resolved” (`tools/slm/s8_agent_runtime_skills.json:18-23`). Frozen `a8-clarify-01` and `a8h-clarify-01` expect a grounded unresolved event to be stored while the missing time is asked. B2 heldout clarification asked a time but stored no event. The exact conflicting instructions are proven; that they caused this particular choice is **[INFERENCE]**. A date-only timezone denial is an additional product obstacle even if skill language changes.
- **Bus fare vs. coffee:** heldout prompt/expectation requests `bus fare` (`tools/slm/s8_agent_heldout.json`, case `a8h-laya-01`); the frozen base fixture seeds draft 431 with `coffee at noon` (`tools/slm/s8_agent_fixture.json`, `drafts[]` id 431). The runtime projection adds the Food category for the heldout case (`tools/slm/s8_agent_runtime_fixture_v2.json`, `category_seeds_by_case.a8h-laya-01`). B1/B2 followed the skill’s instruction to classify the current stored description and disclosed the mismatch, but did not classify bus fare. Preserve the contradiction; no fixture or prompt correction was made.
- **Link amount contradictions:** event 311 is 28,000 VND against draft 411's 50,000 VND; event 313 is 120,000 VND against the lunch drafts' 50,000 VND. B2 `a8-link-01` received `attach_policy` denial and left a proposal, with no accepted link; the model did not apply a financially incorrect link. Heldout `a8h-link-01` expected draft 422, while B1/B2 proposed draft 421. Preserve both the amounts and expected IDs as frozen; no fixture fix was made.
- **Relative date anchor:** B2 hosted user content for `a8-multi-02` contained “Yesterday” but no absolute reference date/timezone; the frozen expectation uses 2026-10-24. The model omitted the date and the service rejected the groceries update. **[INFERENCE]** The absent reference date plausibly makes that expectation under-specified; the trace does not prove why the model omitted it. Conversely, `a8h-multi-01` explicitly gave 2026-10-26 in the user prompt, but the model rejected that date because it was not in captured source text; this is an observed model failure to use user-provided context, not an absent-date case.
- **Abbreviated masked tokens:** In B1, provider replies for `a8-multi-01`, `a8-link-01`, and `a8-privacy-01` contained ellipsis-abbreviated token-like forms. `MaskedPromptBoundary.scanInbound` rejects unknown token-like strings (`app/src/main/java/io/github/triet4p/aegisf/privacy/MaskedPromptBoundary.kt:68-86`); `MaskedHostedGateway` turns the response into `Incomplete("masking_blocked")` with no dispatchable calls (`app/src/main/java/io/github/triet4p/aegisf/privacy/MaskedHostedGateway.kt:115-129`). These are observed fail-closed blocks, not raw-value egress. A local gateway test can verify that abbreviated/unknown tokens never dispatch, while exact same-session tokens remain usable.
- **One inbound scanner match:** B1/B2 `a8-laya-02` is distinct from those token-abbreviation blocks: its provider HTTP response was flagged and redacted, and the case record marks `raw_sensitive_inbound_detected=true`. The response is intentionally unavailable in durable evidence. Outgoing raw-absence passed in the case. Exact response content/source is unknown; do not infer it.

## Non-API checks to consider after owner approval

1. Add/run local tests for date-only timezone handling and multiple distinct writes versus exact replay, as above; no provider call is needed.
2. Add/run a local masking-boundary test for unknown/abbreviated tokens and check that refusal prevents tool dispatch; preserve the current redaction behavior for the flagged inbound response.
3. Before any future batch, add a read-only preflight that compares prompt/expected descriptions and amount/date facts with the frozen runtime projection, and requires an explicit reference date where “yesterday” is expected to resolve. Do not silently change the heldout fixtures.

These are proposed discriminating checks only; they were not run and no changes were made under the owner’s read-only diagnosis decision.

## Other unresolved verification

`artifacts/sprint-8/evidence/S8.13-replan-a3/build-test-attempt-01.json` preserves the first combined Gradle invocation: `BoundedWorkflowExecutorTest` passed 20/20 and the focused service suite passed 52/53; one unrelated existing method failed opening SQLite with `SQLiteCantOpenDatabaseException` / `SQLITE_CANTOPEN`. This was not rerun. The subsequent isolated APK build and actual-device Laya/oversize smoke evidence are preserved in `build.json` and `laya-projection-smoke-attempt-1.json` / `.host.json`; see the owning handoff for the observed smoke details. No base app data was cleared or mutated.
