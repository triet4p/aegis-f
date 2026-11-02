# S8.13 replan-a6 — terminal masked-result refusal evidence

**Scope:** Direct, local behavioral proof for the changed `masked_result_refused` terminal path. This is not hosted-model qualification, an independent review, a PASS, or a checkpoint. No hosted/API call, new batch, device action, fixture/scoring edit, stage, commit, or push occurred.

## Exact runtime proof

Command run for the throwaway host smoke:

```text
powershell.exe -NoProfile -Command "& '.\gradlew.bat' ':app:testDebugUnitTest' '--tests=io.github.triet4p.aegisf.agent.S813MaskedResultRefusalSmokeTest' '--no-daemon' '--console=plain'; exit $LASTEXITCODE"
```

Observed on the exact-path run: `BUILD SUCCESSFUL`; `TEST-io.github.triet4p.aegisf.agent.S813MaskedResultRefusalSmokeTest.xml` recorded 1 test, 0 skipped, 0 failures, 0 errors. The only build warning was the existing Android SDK XML v4 compatibility warning.

The throwaway test used an actual in-memory Room database, `WorkflowQueryService`, `WorkflowCommandService`, `WorkflowRunHost`, production `BoundedWorkflowExecutor`, `MaskedHostedGateway`, and `BoundedWorkflowToolAdapter`. An in-process scripted provider adapter dispatched one valid read-only `query_draft_candidates` call over the synthetic Room row. A temporary one-shot fault flag in the real `MaskedPromptBoundary.maskToolResultJson` injected a `MaskingBlockedException` at the final structured-result masking boundary, after the adapter had built the ordinary typed result. The real adapter `maskResult` catch generated `status=refused, reason=masked_result_refused`; the result was not stubbed or asserted through enum mapping.

Observed assertions, all passed:

- The executor returned `WorkflowRunOutcome.Refused` with reason `masked_result_refused` and a final `REFUSED` trace state.
- Exactly one provider `generate` ran; `continueWithToolResults` ran zero times. The trace contained one tool dispatch, the read-only query. With limits of 3 turns and 8 tool calls, the refusal was not budget exhaustion; no subsequent provider or tool dispatch occurred.
- The Room-seeded draft remained `PENDING`; no ledger transaction, event-update operation, proposal operation, attachment operation, or classification operation was created. The only pre-run row was the test's synthetic pending-draft seed.
- The injected boundary fault was consumed once. No raw sensitive test values or prompt/tool-result payloads were written to evidence.

The one-shot flag and throwaway test source were removed after this run. No product source, permanent test, fixture, scorer, documentation, changelog, or Main-owned plan/state file remains changed by this attempt. A permanent test was not warranted: reproducing this exact boundary failure requires deliberate fault injection, and the requested throwaway end-to-end proof already exercises the consumer-visible terminal stop through the real executor/adapter/Room route.

## Discriminating natural-failure observation

A preliminary throwaway run used the actual `SensitiveVault` capacity failure with the same real executor/adapter/Room query path. It passed and observed `privacy_boundary_refused`, because the capacity exception occurred while the adapter individually masked a field, before its final `maskResult` catch. This is not counted as proof of `masked_result_refused`; it distinguishes the two catch sites.

The exact-path injection above failed specifically inside the final `maskToolResultJson` call made by `maskResult`, then observed `masked_result_refused` and the executor's terminal behavior. This is the required direct path proof, not the prior over-budget path.

## Scope and remaining gate

No new qualification evidence or hosted/API traffic was produced. `S8.13-cycle-1` round 1 remains **UNASSESSED**. Independent evidence review and the separate Bronze checkpoint remain Main-owned and unchanged; this local host smoke does not establish overall S8.13 qualification.
