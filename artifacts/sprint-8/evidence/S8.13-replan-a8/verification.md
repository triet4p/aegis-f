# S8.13 replan-a8 — controlled executor repair proof

**Scope:** Complete the bounded local end-to-end proof of the a7 scalar-token correction. This is not S8.13 qualification, a new frozen snapshot, hosted-model evidence, `ready_for_review`, PASS, or a checkpoint. No production source, schema, skill, fixture, score, threshold, plan, or gate was changed in a8.

## Runtime path exercised

The throwaway host test used the production `BoundedWorkflowExecutor.startWithAttemptKey`, `MaskedHostedGateway`, `BoundedWorkflowToolAdapter`, canonical `S8AgentToolDeclarations` and `WorkflowSkillCatalog` assets, actual in-memory Room, `WorkflowCommandService`, `WorkflowQueryService`, and actual `ToolCallAssembly` / schema validator. A local scripted adapter supplied only deterministic turns; it did not fake an application result, bypass the adapter, or perform network/provider work.

1. The canonical event skill loaded at version 3 and the current tool declaration was passed to every generation turn.
2. Turn one contained a schema-invalid call and a schema-valid event update whose amount/date/time scalar arguments contained only the bodies of the observed tokens. The real declaration validator classified the first as `schema_invalid`; it returned top-level safe `not_dispatched` detail (`arguments.expectedCaptureRevision: required`) without dispatch. The real tool adapter received the stripped-scalar update and returned `refused / invalid_scoped_value`, field `interpretation.amountVnd`, and constant guidance requiring the whole same-session token including the literal delimiters.
3. The actual masked tool-result continuation delivered both structured results to the next controlled turn. That turn checked that the refusal itself echoed no opaque token or raw value; its attached `fresh_observation` had the current capture/revision and no events. Before correction, Room contained no event or mutation operation; ledger, link, attachment, and classification effects were absent.
4. The next turn constructed its corrected arguments from that `fresh_observation`, copied the exact full amount/date/time tokens from its current masked source, and confirmed the vault recognized them in this same session. The actual adapter and command service applied one event update. The third generation completed; the production executor returned `Completed` only after its normal fresh postcondition check observed the stored event.
5. Final checks observed one event and one `UPDATE_EVENT` operation, no proposal/attachment/classification operation or ledger row, and no raw-sensitive egress at the provider seam. The run used 3 of 4 allowed turns and 3 of 4 allowed tool calls, so it completed with both budgets remaining. The schema-invalid call is included in the tool-call count.

## Exact verification

Command:

```text
powershell.exe -NoProfile -Command "$env:JAVA_TOOL_OPTIONS='--add-opens=java.base/jdk.internal.access=ALL-UNNAMED'; & '.\gradlew.bat' ':app:testDebugUnitTest' '--tests=io.github.triet4p.aegisf.agent.S813TokenExecutorRoundTripSmokeTest' '--no-daemon' '--console=plain'; exit $LASTEXITCODE"
```

Final observed result: `BUILD SUCCESSFUL`; selected test result XML reports 1 test, 0 skipped, 0 failures, 0 errors. The build emitted the known non-blocking SDK XML v4 compatibility warning. Machine-readable sanitized observations are in `runtime-smoke.json`.

Two earlier executions failed only in throwaway harness expectations, not in the product path: the first expected the executor's `not_dispatched` schema result to be nested under `application_outcome` (it is correctly top-level); the second checked the token guidance in the outer tool description rather than the nested amount-field schema. Those assertions were corrected. The final complete runtime scenario passed. No production correction was indicated or made.

## Privacy, limits, and remaining status

The test emitted no sensitive contents to its result XML or this artifact. Its assertions checked the error object separately from the fresh observation (which intentionally carries masked tokens), and scanned the provider seam for raw literals. The controlled adapter was local and scripted; no endpoint request, hosted call, device/build-install action, paid batch, or protocol-wire behavior was exercised. This host runtime proof satisfies the assigned controlled-provider smoke; it does not establish hosted capability or replace any qualification run.

No permanent regression test was retained because this assignment requested throwaway end-to-end proof and existing a7 focused adapter/provider tests already cover the narrower field-feedback/schema contracts. The temporary source `app/src/test/java/io/github/triet4p/aegisf/agent/S813TokenExecutorRoundTripSmokeTest.kt` was removed after the successful run. No production code or docs/changelog changed; no docs update was needed.

S8.13 remains unqualified and cycle `S8.13-cycle-1` round 1 remains **UNASSESSED**. The prior recovered A1 result and the unmet qualification gates remain unchanged; candidate-b remains unmeasured. No new immutable snapshot or hosted continuation was authorized or created. Existing a4 adapter setup / unrelated SQLite findings were not rerun. A post-a7 Android/APK smoke was not run; this assigned proof used the explicitly allowed controlled host runtime path. No stage, commit, or push occurred.