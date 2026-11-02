# S8.13 A1 repair failure: token-shape root cause

**Scope:** read-only diagnosis of the genuine recovered `candidate-a/pass-1`; no hosted calls, source edits, fixture/scorer/gate changes, or retry/reset. The current Android invocation is preserved under `candidate-a/pass-1-recovered-current/`. Original stale `candidate-a/pass-1/` and its host record remain untouched.

## Finding

All three frozen repair tasks failed before any event write. Their schema-valid `update_event_interpretation` calls supply the body of each sensitive token in typed scalar fields (for example, `amountVnd: "AMT-<scope>-0001"`, `timeAnchor.date: "DATE-<scope>-0002"`, `timeAnchor.time: "TIME-<scope>-0003"`), while `normalizedText` and `maskedSpanText` retain the complete displayed tokens including literal `⟦` and `⟧`. The typed fields therefore do not satisfy the application’s exact-token parser. The same form repeats after refusal; no effect was applied, no retry achieved `no_change`, and no tool-call result was silently discarded.

This is not a source/APK/runtime-fixture mismatch. The trusted observation contains full tokens, and the identical frozen runtime succeeds when the model sends the full bracketed form (see `a8-multi-02`). The model-facing contract requires exact copying, but does not state in scalar-field descriptions that the delimiters are part of the value or show an illustrative token shape. The application response is also generic, so it fails to explain the field and expected shape.

## Discriminating evidence

- `candidate-a/pass-1-recovered-current/a8-multi-02.json`: successful event updates send full `⟦AMT-…⟧`/`⟦DATE-…⟧`/`⟦TIME-…⟧` values in typed fields and persist the expected readings.
- `candidate-a/pass-1-recovered-current/a8-retry-01.json` and `.provider-attempts.json`: after the first call is rejected by the declaration validator as `schema_invalid`, the next schema-valid call sends bare typed values. Five subsequent calls retain that form; every dispatched call returns `invalid_scoped_value`; no event/operation is stored.
- `candidate-a/pass-1-recovered-current/a8-recover-01.json` and `.provider-attempts.json`: after a first schema-invalid call, a schema-valid bare-token call returns `invalid_scoped_value` and fresh context at capture revision 1. Subsequent attempts use revision 1 but retain bare scalar tokens and fail before the command service sees the revision. The race injection changed revision 0→1 without changing source text; the model therefore never receives an actual command-service stale-revision conflict.
- `candidate-a/pass-1-recovered-current/a8h-retry-01.json` and `.provider-attempts.json`: the first schema-valid call sends bare scalar token values, returns `invalid_scoped_value` plus fresh context, and the subsequent attempts retain the same shape. No event/operation is stored.
- `candidate-a/pass-1-recovered-current/a8-privacy-01.json`: the same bare typed-token form occurs across its attempts; it is a cross-case reproduction, not an additional repair case.
- First adapter refusal sent to the next provider request is `application_outcome: {status: refused, reason: invalid_scoped_value}` plus a current masked observation. It names neither the failing field nor the required token shape. Schema-invalid feedback is only `{status: not_dispatched, reason: schema_invalid, tool: update_event_interpretation}`.

## Contract and validator path

- Frozen wire schema: `update_event_interpretation.interpretation.amountVnd` and `timeAnchor.date/time` are nullable strings. Descriptions say “same-session … token copied exactly” / “opaque tokens”; there is no `pattern` or `example`, and no literal statement that `⟦` and `⟧` must be included. The selected `s8-event-extract` version 2 skill likewise says to use/copy same-session typed tokens, without a scalar-format example. The shared system text says to copy tokens exactly and never abbreviate them. Thus bare token bodies violate exact-copy guidance, but the scalar representation boundary is under-specified and the observed error gives no useful correction.
- Actual app path in `BoundedWorkflowToolAdapter.execute`: inbound scan → `ProviderJson.validateArguments` → `updateEvent` → `checkedEventInterpretation` → `MaskedApplicationEffect.rehydrateInterpretation`. The schema uses unconstrained strings, so bare values can be schema-valid. `rehydrateAmount`, `rehydrateDate`, and `rehydrateTime` call `SensitiveVault.validatedEntry`, whose full-token expression requires the complete `⟦TYPE-scope-sequence⟧` form. Its `TokenValidationException` is caught at the adapter boundary and collapsed to generic `invalid_scoped_value`. No raw input or exception detail is returned to the model.
- The bracketed full token form is visible in the current trusted `sourceText`; the successful `a8-multi-02` writes demonstrate that the same adapter accepts the full form. Existing adapter test helper also extracts the complete bracketed form from observations. No change to token trust, accepted inputs, or fail-closed behavior is proposed.

## Frozen source/runtime binding

- Snapshot: `S8.13-runtime-fixture-v3-batch-03-freeze-02`; the recovered live run is bound to that snapshot’s batch, projection, candidate-a/pass-1, and APK hashes.
- `BoundedWorkflowToolAdapter.kt` current SHA-256 `9098ad958c65a051449610ea6ad5b3f37076c6050dacee4a2a6699842f0850cd` matches the frozen source hash. `BoundedWorkflowExecutor.kt`, `tools/slm/s8_agent_tools_v2.json`, and `tools/slm/s8_agent_runtime_skills.json` also match their frozen hashes. Recovered live-run runtime-tools/runtime-skills hashes match those files.
- Actual app/test APK hashes match freeze: app `c01a77f00ca95d551fb10e2ffa2cab12a7a2afcbd30109ca10323c49056d1b8c`; test `319aa060de3d897e799991e2d11234f16e35b580c9ba48a27716a78f07cb04a2`. Isolated app/test/target package IDs and `AndroidJUnitRunner` match the snapshot and recovered host record. No relevant frozen source/APK skew was observed.

## Approved bounded correction

Main approved: (1) explicitly document that every sensitive scalar string must copy the entire opaque token, including literal `⟦`/`⟧`, in the model-facing tool descriptions and selected extraction skill/system guidance; use clearly illustrative placeholder examples only; (2) preserve strict scope/type/session checks and no-auto-wrap behavior, but return safe field-specific feedback for malformed amount/date/time tokens, without echoing submitted tokens or raw values; improve schema-invalid detail only if the existing validator can provide a safe field/constraint without raw payload; (3) add a no-network actual-adapter/service regression proving full-token success, bare-token refusal with unchanged state, actionable repair guidance followed by a correct full-token operation, and continued rejection of privacy/scope-invalid tokens. Include local tool-wire semantics smoke across supported schema lowering; do not use source-text assertions. No fixture, scoring, gate, provider-route, or hosted measurement changes are authorized by this correction.

At the time this diagnosis was first saved, no product edits had yet been made. The later approved correction and local proof are recorded below. Any corrected source/APK requires a new immutable snapshot and further owner approval before any hosted continuation.

## Correction and local evidence

Main approved the bounded correction. The runtime tool declarations, selected extraction
skill, and stable system instruction now explicitly state that scalar amount/date/time fields
must receive the entire displayed token including literal `⟦` and `⟧`; placeholder shapes are
illustrative only. The adapter still rejects bare, wrong-type, unknown, and foreign tokens.
For typed amount/date/time rehydration failures it now returns a constant field path and
expected full-delimiter guidance without echoing the submitted token or raw value. The existing
validator's safe field/constraint detail is passed through for schema-invalid calls; raw
arguments are not included.

Local proof used the actual in-memory Room/service/adapter path and actual provider request
builders with fake transports only (no provider/API calls):

- `BoundedWorkflowToolAdapterTest` passed 3/3 with the documented
  `JAVA_TOOL_OPTIONS=--add-opens=java.base/jdk.internal.access=ALL-UNNAMED` needed by
  Robolectric on this JDK. The new test rejects bare scalar token bodies with
  `interpretation.amountVnd` plus complete-delimiter guidance, confirms no event/operation was
  created, then applies a corrected full-token call and verifies the stored amount/date/time.
  Foreign-scope and unknown same-scope token calls remain refused with no additional operation.
- The Gemini tool-wire schema smoke passed and confirmed the complete-token instructions and
  nullable string fields survive the actual Gemini schema lowering.
- The OpenAI-compatible tool-wire schema smoke and schema-invalid detail test passed; both
  confirmed the complete-token instructions survive the actual request serialization and
  malformed declaration feedback carries only the safe path/constraint.
- The executor schema-invalid continuation test passed; the safe validator path/constraint is
  present in the `not_dispatched` result and the submitted arguments are absent.
- The first targeted run compiled successfully; the adapter test failed before setup because
  Robolectric could not access `jdk.internal.access`. The provider-wire and executor tests
  passed. Re-running the adapter test class with the documented module-open flag passed 3/3;
  this is a harness JVM-access issue, not an application test failure.

- The selected extraction skill was bumped to v3 because the stable prefix-cache identity
  contains skill ID/version. `WorkflowSkillCatalogTest` passed 3/3 and confirmed the selected
  extraction skill and canonical skill identity both carry `/3`.

The current frozen candidate-a invocation remains exactly one A1 and is scored once:
runtime business 6/16 dev, 3/8 heldout, 0/3 repair/replay; the unchanged raw-v1 exact-contract
scorer is independently preserved at 1/16 dev and 1/8 heldout. Candidate A's best possible
three-repeat totals are dev 38/48 and repair 6/9, both below unchanged gates; candidate B is
unmeasured. No qualification PASS is claimed. The product correction changed source, tool
schema, bumped selected `s8-event-extract` to v3 so its prefix-cache identity tracks instruction
content, tests, and docs after freeze-02; no new snapshot or hosted continuation was created or
authorized.
## Runner binding correction proof

The corrected readback runner source hash `205b4f8caf2af11fa5722184c659bae2eafc1cec293a2202271baef5dba473a3` differs from the frozen pre-correction source hash `649cff3f0512a550fcaa617b5fedd6530f5fb4389085e226b63e2e8ed6020a88`. The existing recovery manifest records PASS for the genuine A7 candidate-a/pass-1: 24 case records and 24 sidecars, bound to the current projection, APKs, and freeze-02 snapshot. The stale destination was preserved.

A read-only call to `validate_device_run_evidence` paired the current A7 host record with the stale local pass directory. That directory identifies A3/batch-v2 while the host record identifies A7/batch-v3; the validator rejected it with `device run identity, pass, or complete-case counts do not match the requested run`. This check made no hosted/API call, device action, package install, or evidence overwrite. Full binding evidence is in `runner-binding-correction-proof.json`.