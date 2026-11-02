# Lessons learned

## [2026-10-17] Android NotificationListenerService drops binding after update or background kill without auto-rebind

**Symptom:** Recent bank notifications were not captured into pending drafts; drafts only appeared after manual app interactions or not at all.
**Root cause:** Android's NotificationManagerService unbinds NotificationListenerService upon package replacement (`adb install -r`) or aggressive OEM background process kills. Without automatic rebind and active reconciliation on app/screen resume, background notifications were missed and not reconciled into drafts before the user swiped them away from the status bar.
**Fix / workaround:** Added `requestActiveReconciliation` hooks on `MainActivity.onResume`, `AegisApplication.onCreate`, and `BankDraftReviewScreen` entry. If disconnected, automatic rebind with `setComponentEnabledSetting` toggle fallback is invoked; if connected, off-thread `reconcileNow` processes any active status-bar notifications immediately.
**Watch out for:** Relying on `NotificationListenerService` callbacks without active notification reconciliation on user-visible screen entry, especially on custom OEM Android skins (Honor MagicOS / EMUI / MIUI) that restrict background execution.

## [2026-10-19] Reused device probe directories contaminate memory samples

**Symptom:** S5.1 reports showed 3.4–3.6 GB process RSS/HWM even though fresh runs completed at roughly 0.7 GB; `samples.csv` row counts exceeded the current process duration.
**Root cause:** `device_probe.sh` appended samples to a reused case directory, while `slm_eval.py` summarized the entire file. Full and held-out runs also shared `load`/`cancel` output paths.
**Fix / workaround:** Truncate `samples.csv` at probe start; namespace each run's prompt and probe directories by the report filename; include the probe script hash in the report contract. For existing reports, isolate rows using the run's `status.wall_ms` and monotonic timestamps, or mark memory unverified when raw windows are unavailable.
**Watch out for:** Reusing a report/output stem on-device can overwrite its raw run directory; use a unique report filename and check fresh status/sample counts.

## [2026-10-19] Text matches do not prove refusal or clarification

**Symptom:** S5.1 passed an SQL refusal followed by a truncated tool call, an unknown-category answer that substituted unrelated Groceries, a generic “not enough information” answer for unsupported income totals, a Vietnamese deletion prompt answered with unrelated text containing “không,” and `Team Coffee` when only exact `Coffee` was accepted.
**Root cause:** Phrase/substrings were treated as semantic proof without checking partial native-call activity, the refusal's action/financial scope, exact category output, or whether a frozen tool label was achievable under its schema.
**Fix / workaround:** Count native tool-call start markers as activity even when no complete call parses; require scope-relevant questions for ambiguity and unknown categories and explicit scope-matched refusals for unsupported actions/types; compare normalized category names exactly after removing model control tokens. If a fixture asks for an unavailable ordering or omits schema-required dates, preserve its wording/old score and apply a versioned, candidate-independent clarification expectation.
**Watch out for:** Inspect raw generations, not summary phrases. Keep frozen-label scores separate from corrected-semantic scores and achievable-call denominators; a language mismatch, unsafe promise, impossible call, or unrelated answer is not an acceptable tier limitation.

## [2026-10-19] Keep the native parser's empty-list contract

**Symptom:** The LFM2.5-350M run emitted complete native tool calls, but on-device reports stored `parsed_calls: null` and graded those calls as absent.
**Root cause:** Replacing the Gemma-native parser branch accidentally removed the shared `return calls`; all family parsers must return a list, including when no calls are found.
**Fix / workaround:** Restore the unconditional list return and reparse preserved raw stdout for every candidate instead of rerunning unaffected inference. Mark source grades invalid when `parsed_calls` is null/missing and distinguish them from raw-backed current-rubric scores.
**Watch out for:** A native parser is an evaluator boundary, not a dispatcher. Smoke it against complete raw calls from each family, malformed/partial envelopes, and a no-call response before attributing failures to a model.

## [2026-10-23] ADB screen text can outlive its visible bounds

**Symptom:** A phone smoke wait timed out for an event action that was present after expanding a capture, and a later event-control lookup could not find the event heading even though `Browse drafts` was visibly clickable.
**Root cause:** The Compose screen is vertically scrollable: the action can be outside the current viewport, or the heading can scroll above an action that remains visible. The UI hierarchy exposes button text as a non-clickable child and the actionable bounds on its clickable parent.
**Fix / workaround:** Drive scrollable screens to the relevant viewport; when one event is unambiguous and its heading is above the viewport, use the measured clickable parent bounds. Inspect the captured screenshot and accessibility bounds instead of treating text-node presence as rendered visibility.
**Watch out for:** A text-based hierarchy search does not establish that a node is on-screen or clickable, and a visible button without its event heading requires bounded parent lookup or a scroll before choosing among repeated controls.

## [2026-10-23] A confirmation preview must be confirmed with its own revision and edits

**Symptom:** After a concurrent draft change, pressing "Check current values" produced a current preview, but pressing Confirm always failed with "The draft changed under the caller."
**Root cause:** The dialog displayed a preview's binding while the confirm request sent the form's older saved `expectedRevision` and the current form edits. The binding and the revision therefore described two different states.
**Fix / workaround:** Keep the edits beside each `DraftConfirmationPreview.Ready`, send the preview's `expectedRevision`/`binding`/`edits` in the confirmation, and adopt the preview revision into the form after a successful preview. Prove it on the rendered phone: stale dialog → check current values → Confirm → one ledger row.
**Watch out for:** A preview is a read of one exact state; anything that writes from it must carry that state's identity, not the form's last saved identity. Also keep the stale check visible (draft revision and form edits) so Confirm is withheld before a stale binding can be sent.

## [2026-10-23] Phone harness text entry must survive a Vietnamese Telex IME

**Symptom:** `adb shell input text "Cafe sua review"` produced "Càe sua review" and an English word was auto-capitalized when focus moved, re-triggering a suggestion request; a retake also captured an unrelated personal notification banner.
**Root cause:** The device IME transforms Telex tone letters during `input text` and auto-capitalizes/commits when the focus leaves the field; heads-up notifications can appear above the app on any capture.
**Fix / workaround:** Type only Telex-safe markers/digits (e.g. `S66 5000066`), read the field value back before an action, scroll inside the upper app area while the IME is open instead of pressing Back, and inspect each screenshot for personal content before citing it; delete and retake any exposed capture.
**Watch out for:** Never change the user's keyboard/language to make a harness pass, and never treat a pre-action text echo as proof of what was saved.
 
## [2026-10-23] Keep bottom-navigation labels legible at large font scale

**Symptom:** On the ALT-LX2 at font scale 1.3, the Dashboard tab rendered as `Dashboar`, and the Review badge `4 waiting` wrapped into two lines and enlarged the navigation bar.
**Root cause:** The four equal-width tabs combined horizontal padding with a font-scaled `labelMedium` style, leaving too little width for the longest destination; a worded count badge also wrapped beneath the Review label.
**Fix / workaround:** Use `labelSmall` for the destination labels and a numeric count badge while retaining the complete `N waiting` string in the tab's accessible description. Keep selection marked by both bold weight and an underline. Actual 720×1610 phone screenshots show all four labels and the compact badge at 1.3; measured targets were 83×56 dp.
**Watch out for:** `maxLines = 1` and source semantics do not prove the OEM rendering. Verify the actual phone at 1.3, inspect accessible bounds, and use TalkBack/keyboard focus to activate a critical action; restore the original font and accessibility settings after testing.

## [2026-10-23] Manual keyboard opening pans the form beneath system bars

**Symptom:** On the ALT-LX2, opening the IME in Manual hid the `New transaction` top bar and placed the introductory text behind the status bar; the Save action was initially below the keyboard.
**Root cause:** The activity left `windowSoftInputMode` unspecified, and Android selected `adjust=pan` for the scrollable Compose screen; the Manual form also lacked IME inset padding.
**Fix / workaround:** Set `android:windowSoftInputMode="adjustResize"` for `MainActivity` and apply `imePadding()` to the Manual form's scroll container. The phone recheck kept the top bar visible and allowed Save to scroll above the IME; `dumpsys window windows` reported `adjust=resize`.
**Watch out for:** Edge-to-edge Compose screens with scrollable text fields can pan behind system bars; inspect runtime window attributes and screenshots, and verify that critical actions remain reachable while the keyboard is open.

## [2026-10-24] Dashboard compact labels cannot slice accepted date text

**Symptom:** A grammar-valid Dashboard date such as `2026-10-3` crashed Compose while computing the compact scope label.
**Root cause:** The date parser accepts non-padded months and days, while the label used fixed substring offsets into raw editing text.
**Fix / workaround:** Derive display dates from the resolved device-local range and keep the typed field values unchanged. Test the compact-label consumer with non-padded month/day combinations as well as invalid and reversed ranges.
**Watch out for:** Any UI that formats a parsed date must not assume the original text is fixed-width; use canonical parsed calendar days, and preserve raw text only for editing and refusal feedback.

## [2026-10-24] ADB screenshots capture the current display, not the target package

**Symptom:** A screen capture after an isolated app closed contained the launcher rather than the app screen.
**Root cause:** `adb exec-out screencap` captures whichever surface is foreground, so a crashed app exposes whatever screen is underneath.
**Fix / workaround:** Confirm the isolated package remains foreground before capturing and stop capture immediately if its process or activity exits.
**Watch out for:** Never retain a screenshot merely because the target package was launched earlier; screen contents can change between the launch and capture.

## [2026-10-25] A claim-time question alignment can erase a concurrent retarget

**Symptom:** A running classification attempt settled its older result and left the newer accepted context unclassified.
**Root cause:** `markExecuting` unconditionally aligned `requestedQuestion` to the attempt's recomputed question, even when `retarget` had updated the row after the attempt's initial read.
**Fix / workaround:** Pass the request observed before context recomputation to `markExecuting`; align only if the stored request still matches that observed value, while always recording the executed question under the existing state and owner-token guard.
**Watch out for:** Keep both legacy/stale-row normalization and the compare-before-align behavior; removing alignment causes endless requeue of stale rows, but unconditional alignment loses a concurrent newer request.

## [2026-10-25] Exact argument equality on producer-only values makes an evaluation suite unachievable

**Symptom:** A functionally correct tool call that produced exactly the same real application effect as the frozen expectation failed scoring with only `call_intent_or_arguments_mismatch`, because its idempotency key and normalized reading text differed from the literals written in the case file.
**Root cause:** The scorer compared the whole parsed call by deep equality, but the frozen expectations carried values no supplied input states: a literal idempotency key (9 of 11 call-bearing cases), free-form normalized text (6 readings), an unresolved-reason label, and a producer extraction version. The case file is not the model's input, so "exact expected arguments" silently meant "guess the fixture's private strings".
**Fix / workaround:** Keep intent, scope, revisions, spans, amounts, dates, times, bounds and nullness exact, and score only the unknowable fields semantically from deterministic source-backed constraints plus the real app-stored row: non-empty unique key, source-backed text matching the stored reading, an exact unresolved decision with a well-formed label, and one declared producer version per case. Bind every result document to the scoring/contract/suite/prompt/tools/parser hashes, and keep the old exact scorer runnable so prior results stay comparable.
**Watch out for:** Before freezing any expectation, check it against what the model actually receives (prompt, context block, rendered schema); an expectation a careful model cannot derive is a contract defect, not a capability result. Do not "fix" it by leaking expected literals into the prompt, and do not loosen scope/amount/time constraints along with the unknowable fields.

## [2026-10-27] Durable agent admission stored the unmasked request

**Symptom:** Agent admission computed a locally masked request fingerprint, but persisted the original request text in both the run row and the user-visible conversation.

**Root cause:** The masked value was used only for identity calculation; the two history writes independently used `request.userRequest`.

**Fix / workaround:** Persist the same `maskedRequest` in `AgentRunEntity.requestText` and the `USER` conversation line, with a regression test that checks both stores against a synthetic key-like value.

**Watch out for:** Every new durable request/history write must consume the boundary-masked value, not the original request object; check both run rows and conversation/export surfaces.

## [2026-10-27] Deleting the last hosted profile retained an unused Keystore key

**Symptom:** Removing the only provider profile left an encrypted empty profile list and a new key alias behind.
**Root cause:** `delete` rotated the key by saving an empty list instead of clearing preferences and removing the active profile.
**Fix / workaround:** Route last-profile deletion through `deleteAll`, check active-id removal commits, and verify old/current aliases are gone on-device.
**Watch out for:** Test both deletion with other profiles remaining and deletion of the final profile; the latter must remove the ciphertext, alias reference, active id, and Keystore key.

## [2026-10-27] Duplicate provider preset ids made lookup ambiguous

**Symptom:** The Gemini Flash Lite preset appeared twice under one id, causing `byId` to throw because it requires a single match.
**Root cause:** A duplicate preset declaration was added with different capabilities but the same model/protocol identity.
**Fix / workaround:** Keep one verified declaration per preset identity and assert that all published preset ids are unique.
**Watch out for:** Preset ids are used for deterministic selection; compare both id and model capabilities when editing an existing profile.


## [2026-10-27] Exercise text-only OpenAI probes with provider-specific request metadata

**Symptom:** The real OpenCode Go settings probe was rejected although the same endpoint/model succeeded in multi-turn tool smoke.

**Root cause:** The app's text-only probe had no first-party OpenCode session headers, and Chat Completions serialized `parallel_tool_calls` even when the request had no `tools`.

**Fix / workaround:** Supply a stable per-run `x-opencode-session` plus the app User-Agent only to the exact `opencode.ai` host; omit `parallel_tool_calls` when no tools are offered. The Android UI then verified all three profile save/replace probes.

**Watch out for:** A tool-bearing live exchange does not cover the settings probe's text-only request shape. Compare wire fields and required headers on-device, and keep provider-specific metadata off unrelated origins.

## [2026-10-28] Do not attach a timezone to a date-only reading

**Symptom:** A date-only source-backed event was refused because its timezone had no time value to anchor.

**Root cause:** The adapter copied the trusted capture timezone into every canonical date anchor, including anchors with `time == null`, while the event service correctly rejects that combination.

**Fix / workaround:** Keep `timezoneId` out of the model schema and add the trusted timezone only when the canonical anchor contains a start time. Preserve the date token and leave a date-only anchor's timezone null.

**Watch out for:** Test date-only and timed anchors through the real adapter/service path; do not invent a reference date or weaken the service's date/time validation.

## [2026-10-28] Give each distinct durable mutation its own operation identity

**Symptom:** Two different event readings in one durable attempt used the same service idempotency key, so the second write conflicted; corrected stale-revision calls also lacked a distinct durable identity.

**Root cause:** Durable execution chose one key per tool name, bypassing the executor's existing exact-call signature map.

**Fix / workaround:** Keep the exact-call map in durable mode. Reuse a key only for the same tool and exact argument JSON; preserve the first per-tool attempt seed and derive stable keys for later distinct signatures.

**Watch out for:** Prove distinct writes, exact replay (`no_change`), corrected retry, process restart, and same-key/different-payload rejection. Never weaken the service journal's conflict rule.

## [2026-10-28] Verify a repaired target instead of letting its earlier error poison completion

**Symptom:** A mutation returned a repairable stale-revision error, the agent retried from the fresh state and the service applied it, but the earlier error still forced the run to be incomplete.

**Root cause:** Completion treated every failed mutation dispatch as permanent even when a later successful dispatch repaired the same target.

**Fix / workaround:** Supersede only a repairable failure followed by a successful `applied`/`no_change` mutation for the same target, then run the ordinary final postcondition verifier on the successful dispatch. Unrelated successes and terminal refusals remain non-repairable.

**Watch out for:** The later service status is not itself proof of completion: retain the normal fresh-state verifier, and keep terminal masking/scope/user-decision refusals out of retryable handling.

## [2026-10-28] Keep lower-level nullable revisions out of agent preconditions

**Symptom:** The agent tool schema allowed `expectedReviewRevision: null` even though the selected Laya skill required the revision from a fresh draft observation.

**Root cause:** The schema mirrored the lower-level command request's optional revision, which intentionally permits callers outside the agent boundary to leave review state open.

**Fix / workaround:** Require an integer current revision in the agent schema and reject null at the adapter boundary; retain the lower-level API's existing optionality for its other callers.

**Watch out for:** A permissive service request does not imply that an agent tool should expose the same optional fields. Keep freshness preconditions explicit and enforce them at both schema and adapter boundaries.

## [2026-10-28] Separate explicit user dates from unminted relative targets

**Symptom:** A frozen case gave an explicit absolute date in the user request, but the skill/schema described event dates as source-only; the production adapter can rehydrate a same-session DATE token from the masked request.

**Root cause:** The contract conflated the source-grounded occurrence/span with its temporal anchor and failed to describe same-session user-provided date tokens.

**Fix / workaround:** Allow an explicit absolute user DATE token when it clearly applies to the source-grounded occurrence. Keep scope/session validation, raw-value blocking, and date-only timezone rules. A relative phrase still requires an explicit reference and an already-available exact target DATE token; otherwise ask for an absolute target date.

**Watch out for:** A reference date does not mint a token for a different derived date. Do not construct raw dates or new tokens in model output; local derivation/token minting needs an explicit product decision.

## [2026-10-29] Uncertain agent mutations were reported as `effect: none`

**Symptom:** A post-write `EFFECT_UNKNOWN`/`EFFECT_NOT_VERIFIED` tool result reached the model with `effect: none`, and a classification request owned by another attempt became `MODEL_UNAVAILABLE`, so a possibly applied write read as a no-write guarantee.
**Root cause:** The single tool-exception catch hard-coded `effect = "none"` and the classification branch mapped every `Denied`/`Failed` outcome to the manual/model-unavailable path.
**Fix / workaround:** `AgentToolException` carries its own effect; the envelope forces `unknown` for `EFFECT_UNKNOWN`, `EFFECT_NOT_VERIFIED` and `OPERATION_PENDING`. Classification maps each denial reason and checks the operation journal for a still-pending claim before claiming model unavailability.
**Watch out for:** Any new error path that can run after a domain write must state its effect explicitly; never let a pending or unreadable receipt be rendered as `none` or retried under a fresh operation key.

## [2026-10-29] Agent event corrections could duplicate or silently erase readings

**Symptom:** Selecting a different quote over an existing occurrence created a second reading, and a correction with null optional arguments replaced a stored amount/anchor with null.
**Root cause:** Event identity is exact-span only, and the correction built a full replacement directly from nullable model arguments with no explicit-removal guard.
**Fix / workaround:** `record_event` refuses an expected-absence selection whose span overlaps an existing reading at different boundaries (`STALE_OBSERVATION`, `selected_span_matches_existing_reading`), and refuses null for any field the selected reading already holds (`MISSING_REQUIRED_MEANING`, `observed_reading_field_preserved_or_replaced`). Disjoint occurrences and partial-reading resume still work.
**Watch out for:** Do not implement this guard at the adapter read; the check must sit at the mutation boundary. Do not widen it into one reading per capture, and keep merchant sub-span selection valid as `merchant_ref`.

## [2026-10-29] Same-day interval validation rejected valid ranges

**Symptom:** `make_time_anchor` refused 09:00–17:00 and accepted 17:00–09:00.
**Root cause:** The same-day branch tested `compareTimes(start, end) <= 0`, inverting the intended "end must follow start" rule.
**Fix / workaround:** Reject when `compareTimes(start, end) >= 0`; cover increasing, reversed, equal-end and reversed-date intervals with behavioral tests.
**Watch out for:** Masked comparison helpers return normal `Comparable` signs; check the operator direction, not just the helper name, whenever a boundary is validated on masked values.
 
## [2026-10-29] Parsed output checks miss sensitive provider fields

**Symptom:** A real provider response triggered the qualification recorder's raw-body detector even though parsed assistant text, tool arguments and tool results were clean; another hosted turn followed before the run stopped.

**Root cause:** The local gateway scanned parsed fields only. Protocol bodies may also contain unparsed reasoning, signatures, opaque items, or error fields; the historical recorder kept a redacted body and boolean only, so the exact pass-1 source/member cannot be attributed.

**Fix / workaround:** Scan the complete response body at the adapter boundary before parsing or status-detail extraction, return a terminal privacy-blocked result, and let the executor stop before dispatch or continuation. Record only safe mapped kinds and allowlisted structural paths; preserve redacted historical evidence without claiming the leak source or reason.

**Watch out for:** A raw-body match proves only that a mapped surface occurred in the received envelope, not that it was financial reasoning or that the provider saw a literal prompt value. Do not inspect, replay, or log retained redacted bodies to infer what was not preserved.

## [2026-10-29] Qualification pass paths collided across snapshots

**Symptom:** A Stage2 attempt against an approved snapshot was refused locally because its host-record path already belonged to an earlier preserved snapshot; the prior phone run directory also existed at the legacy root.

**Root cause:** Output folders had moved under a versioned snapshot root, but host/preflight records and phone run paths were still keyed only by attempt/candidate/pass, so preserved earlier evidence occupied the selected paths.

**Fix / workaround:** Bind output, host, preflight, per-pass path-proof, device, pull, and validator paths to the attempt/snapshot/candidate/pass identity. Freeze every exact path and prove new targets are absent while retaining and refusing the old root paths.

**Watch out for:** When versioning a measurement runner, migrate every producer, collector, validator, score reader, and instrumentation argument together. A unique output folder alone does not prevent a stale host record or phone directory from aliasing the new attempt.

## [2026-10-29] Serialized JSON escape digits are not decoded content

**Symptom:** The full serialized response matched a mapped four-digit value inside a Unicode escape even though the decoded JSON leaf contained no matching value; object-building JSON parsing also silently retained only the last duplicate key value.

**Root cause:** Raw-body surface matching treats JSON escape syntax as response content, while the existing last-value-wins parser cannot establish whether a duplicate's discarded value was sensitive.

**Fix / workaround:** Use a strict streaming JSON reader to scan decoded keys, strings and numeric leaves across the complete object; reject duplicate keys, malformed/trailing JSON and unsupported roots before adapter parsing. Continue using the normal inbound scanner so only known session tokens are accepted.

**Watch out for:** Do not skip metadata, reasoning, unknown fields or keys to avoid false positives, and do not infer the historical source from redacted response bodies. The privacy decision is over decoded semantics; serialization-only escape digits are not a match, but digits decoded back into a sensitive value remain blocked.

## [2026-10-29] New snapshot identity moved a retained collision sentinel

**Symptom:** The a12 static preflight refused to run because its preserved freeze04 root-path collision sentinel appeared missing.

**Root cause:** The runner rebound its evidence root to a12 but still resolved the historical a10 sentinel relative to the current attempt, so it inspected an empty new root instead of the preserved a10 path.

**Fix / workaround:** Bind the legacy sentinel’s local evidence root and remote run directory to the explicit preserved a10 attempt while keeping all new outputs under a12/snapshot-11.

**Watch out for:** When versioning a qualification runner, historical collision sentinels are fixed evidence references, not paths derived from the new attempt identity.

## [2026-10-30] Cumulative context accounting must match the resend-all wire shape

**Symptom:** Fresh-chain-only `dispatchTurn` accounting plus per-turn inventory charging made the legacy `initialPrompt + 120` compaction test fail with CONTEXT_BUDGET even though the emitted chain "fit", and a mid-turn stop would abandon answered calls.
**Root cause:** All three adapters resend prior items every turn, so each turn's full inventory is genuinely on the wire; charging only the fresh delta while the drive loop still compacts from the cumulative counter double-counts one way and under-counts the other. The honest fix is cumulative charging in `dispatchTurn` with no mid-turn stop, plus a drive-loop compaction that resets the counter to the stable prefix before charging the rebuilt prompt.
**Fix / workaround:** Charge `maskedResults + fresh inventory` cumulatively per turn; on overflow let the drive loop (pending-empty only) rebuild from prefix + bounded summary + re-read observations, reset `contextCharacters` to `systemInstruction.length` before charging the rebuilt prompt, and stop CONTEXT_BUDGET when even that cannot fit. Size behavioral bounds from a measured one-turn probe, never a magic constant.
**Watch out for:** Any future change to what adapters resend (e.g. delta-only chaining) must update the accounting in lockstep; a test bound that "fits" must be measured from the same fixture's probe, not copied from a previous accounting regime.

## [2026-10-31] A diagnostic allowlist can reject a valid fresh invocation before selection

**Symptom:** The a26 reproduction launch stopped at host-side identity checks; its Android
instrumentation still bound the diagnostic path to the a22 attempt/freeze29 identity and an older
case list, so the requested subset could not reach executor selection.

**Root cause:** Invocation identity and selected cases were repeated as separate runner constants,
instrumentation allowlists and output-path assumptions instead of being supplied as one per-run
contract.

**Fix / workaround:** Require attempt, snapshot, run name and ordered case IDs together; validate
the mapped selection before credential loading; pass those values to local preflight, instrumentation,
debug capture and collection; reject any output whose identity or order differs.

**Watch out for:** A new host run name alone is insufficient. Every instrumented producer, path,
freeze, and collector must consume the same invocation, and an old subset must never be an implicit
fallback when selection arguments are absent.

## [2026-10-31] APK manifest and build-record keys are different contracts

**Symptom:** Inspection found that the pre-install device gate compared `app`/`test` fields
against a build record that stores `app_package`/`test_package`, and the reproduction check expected
both `runner` and `test_runner` from a verifier that returns only `test_runner`. Correct APKs would
therefore be refused before installation.

**Root cause:** The read-only manifest helper and persisted build evidence use different key names,
but callers compared them as if they shared one schema.

**Fix / workaround:** Project build-record fields into the manifest verifier's exact four-key
contract before comparison; use the verifier's actual return shape when rechecking APK manifests.

**Watch out for:** Keep APK package/target/runner validation before install, but explicitly adapt
between helper and evidence schemas rather than inventing duplicate aliases.

## [2026-10-30] Packaged fixture and derived evidence use different case keys

**Symptom:** Qualification, Laya and v3 preflight instrumentation failed with `JSONException: No value for case_id` while reading the packaged qualification mapping.
**Root cause:** The source asset stores each case identifier as `id`; derived runner evidence uses `case_id`, and the tests applied the evidence key to the source asset.
**Fix / workaround:** Use one shared Android test-side lookup for `cases[*].id`; leave fixture bytes unchanged and do not add guessed key fallbacks.
**Watch out for:** Validate test plumbing against the real packaged asset on-device; similarly named identifiers in separate JSON contracts are not interchangeable.

## [2026-10-30] Constructing a debug exchange record does not persist it

**Symptom:** The real TEST_ONLY capture instrumentation failed with `FileNotFoundException` for `exchanges.jsonl` after recording a synthetic exchange.
**Root cause:** `recordExchange()` validated and constructed the identity-bound JSON record but returned without appending it to the case directory.
**Fix / workaround:** Append the guarded record through the existing `append(directory, "exchanges.jsonl", record.toString())` helper.
**Watch out for:** When extending a capture producer, verify that the serialized record is actually written and that rejected credential-bearing input still leaves the file unchanged.

## [2026-10-30] An aggregate preflight assertion can erase named failure evidence

**Symptom:** The device preflight reported only `semanticCases.values.all { it }`, so the failed
response-privacy subcase and its input/guard outcome were unavailable after the instrumented test
failed.

**Root cause:** The test reduced named synthetic cases to booleans, asserted on the aggregate before
writing the enclosing domain report, and the host collector had no separate raw report to retrieve
when the test failed.

**Fix / workaround:** Persist each identity-bound `TEST_ONLY` case record with its synthetic input,
expected and actual guard/channel result, and error stack before aggregate failure; continue
independent cases and collect the separate raw root regardless of the instrumentation result.

**Watch out for:** Any local preflight that serializes diagnostics only after a passing aggregate
can silently lose the evidence needed to distinguish a runtime result from a stale expectation.

## [2026-10-30] Reasoning-exempt response cases retained stale refusal expectations

**Symptom:** Three named response-privacy preflight cases failed because synthetic sensitive-looking
values in Chat provider reasoning were expected to be refused, while the current guard accepted the
reasoning channel.

**Root cause:** Test expectations and the shared expected-case contract no longer matched the
owner-approved reasoning exemption; the runtime behavior was consistent with the accepted boundary.

**Fix / workaround:** Rename the cases to describe accepted reasoning, update their exact accepted
outcome variants, and keep the recognized numeric usage-counter assertion for the counter-bearing
case. Preserve the content, tool-argument, malformed-protocol, metadata and token negative controls.

**Watch out for:** When an accepted policy excludes a channel from guard input, align both
instrumentation and host-side expected-case contracts without changing the runtime or weakening
enforced-channel controls.

## [2026-10-30] Safe-location validation omitted the tool function segment

**Symptom:** The complete response smoke passed, but host validation rejected the two tool-argument
negative controls whose reported path includes `tool_calls[].function.arguments`.

**Root cause:** The safe JSON path segment allowlist included `function_call` and `functionCall` but
not the exact `function` segment used by Chat `tool_calls`.

**Fix / workaround:** Add the observed `function` segment to the test-only safe path allowlist; keep
the exact refusal assertions and full raw report validation unchanged.

**Watch out for:** When retaining diagnostic paths from a provider protocol, compare the allowlist
against the observed schema path rather than a similarly named alternate field.

## [2026-10-30] Freeze rejected the required synthetic privacy report

**Symptom:** The complete local preflight passed, but freeze rejected the `TEST_ONLY` raw report
because it shared the evidence-root debug directory reserved for other raw payloads.

**Root cause:** The collector conflated the required synthetic privacy sidecar with live diagnostic
debug bodies, while freeze correctly refused to distribute arbitrary raw debug trees.

**Fix / workaround:** Collect the single identity-bound 39-case report into a separate gitignored
`artifacts/sprint-8/test-only/<attempt>/<snapshot>/` root, validate its contents and hash, and bind
only its path/hash in freeze metadata. Continue rejecting any raw payload under the live debug root.

**Watch out for:** Preserve failure reports, exclude their bytes from source/freeze/export manifests,
and allow only the exact validated external report rather than opening a blanket raw-debug exception.

## [2026-10-30] Item descriptions promoted into the merchant field evade span validation

**Symptom:** A hosted reading stored the item word as its merchant (`Book` for a book purchase with no stated shop). Span provenance checks passed because the substring was genuinely in the source, yet the stored reading misattributed the event and the scorer safety-stopped the batch.
**Root cause:** The `select_source`/`record_event` guidance and the loaded `event-reading` examples taught only whole-occurrence selection with a null merchant; nothing told the model that only an explicitly named seller-or-place counterparty is a merchant and that a product/item/activity description must stay null.
**Fix / workaround:** Focus the tool descriptions and the skill fewshots on the counterparty distinction: a second `select_source` on the exact explicitly named seller-or-place substring supplies `merchant_ref`; a product/item/activity description is never promoted; an absent or uncertain merchant stays null with clarification `none`. Provenance still only proves the substring, never its semantic role.
**Watch out for:** Span validation passing does not mean the field is semantically correct. Any new fewshot must use novel synthetic wording, never frozen case text, and must cover absent, explicit and uncertain merchant cases without inventing refs.

## [2026-11-02] Suggestion-safety refusal appeared as local model unavailability

**Symptom:** `classify_draft` returned `MODEL_UNAVAILABLE` for a `NeedsClarification(description, ...)` result even though suggestion safety had refused the description before inference.
**Root cause:** The runtime treated every classification clarification as a failed or unavailable model request.
**Fix / workaround:** Reuse `isBlockedForSuggestionSafety` to distinguish the pre-inference refusal, return the existing `clarifying`/`await_user` manual-review envelope, and reserve `MODEL_UNAVAILABLE` for actual inference failures.
**Watch out for:** `NeedsClarification.reason` is human-readable; do not branch on its complete prose or weaken the safety classifier to force a prediction.
