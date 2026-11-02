# S8.13 replan-a4 — correction evidence

**Scope:** local contract correction only. This is not an S8.13 hosted qualification PASS and does not advance the independent evidence gate, Bronze checkpoint, or S8.14. No hosted API request or new qualification batch was run. No frozen fixture, expectation, scorer, or numeric gate was changed.

## Source evidence and bounded fixes

| Gap | Prior evidence / current finding | Correction | Local behavior evidence |
| --- | --- | --- | --- |
| Date-only timezone rejection | `artifacts/sprint-8/evidence/S8.13-replan-a3/diagnosis.md` §1 and B2 trace: the adapter attached capture timezone to a date-only anchor; the service rejected `timezoneId` without `time`. | The v2 tool has no model timezone argument. The adapter includes the trusted capture timezone only when the canonical start time is present. Date-only anchors remain timezone-free. | `BoundedWorkflowExecutorTest`: date-only and timed anchors; host runtime smoke stored a date-only reading without a zone and a timed reading with trusted `Asia/Ho_Chi_Minh`. |
| Distinct mutations sharing one durable operation identity | a3 diagnosis §2; B2 `a8-multi-01` stored the first event then received same-key/different-request conflict. | Durable attempts retain the existing per-tool seed for the first call, but use the executor's exact tool-plus-argument signature map: exact repeats reuse the same identity; later distinct/corrected calls derive stable identities. Service same-key/different-payload rejection is unchanged. | Host runtime smoke applied two distinct event writes in one durable attempt, replayed one exact call as `no_change`, then repaired a stale-revision call with a distinct corrected request. Final state had three events and three operation rows. Separate boundary test confirmed same-key/different-payload returns conflict with no extra event/operation. |
| Exact replay was claimed hypothetically rather than dispatched | a3 diagnosis §3; B1/B2 `a8-retry-01` and `a8h-retry-01` issued no second call. | Skills distinguish an exact replay from a corrected retry; exact replay must repeat every argument and requires the actual service `no_change` result. | Host runtime smoke observed `no_change` for the exact repeat and no additional operation row. Durable restart and continuation replay tests also passed. |
| Repairable result feedback was incomplete | Source inspection found that link mutations returned status without a fresh pair; Laya mismatch feedback included revision/status but not the current masked description. | Link proposal/attachment results now include the fresh current-pair state/revision. Laya feedback includes the fresh masked description, status, and revision. Missing/null agent review revisions are rejected before the service call. Terminal `masked_result_refused` is classified terminally. | Real service/Room tests observed stale link conflict with `currentPair=PROPOSED` revision 0, then correction to `ACCEPTED` revision 1; Laya `context_mismatch` returned the actual fresh masked description/revision; null Laya revision produced `schema_invalid` with no classification operation/snapshot. Existing over-budget terminal test remained green. |
| A repairable failure poisoned a later verified correction | Pre-fix `BoundedWorkflowExecutorTest` had the durable stale-revision correction failure; a3 source inspection also showed completion treated every failed mutation as permanently fatal. | Only a later `applied`/`no_change` mutation of the same target may supersede an earlier repairable error; final completion still runs the normal fresh-state postcondition verifier. Unrelated failures and terminal refusals remain blocking. | Final durable stale-revision test and multi-turn runtime smoke completed only after the corrected effect was re-read and verified. The existing verifier regression for a removed effect remained green. |
| Schema/skill conflated source span with date provenance | Frozen `a8h-multi-01` explicitly supplies absolute date 2026-10-26 in the user prompt, while capture 211 contains no date; a3 records that the model refused the date as not present in captured source. The adapter rehydrates only same-session typed DATE tokens and supports a token from the masked user request. | Occurrence and UTF-16 span remain source-grounded. The schema/skill now permit an explicit absolute user-supplied DATE token when it clearly applies; relative targets remain fail-closed unless an exact target DATE token already exists in masked context. No raw/constructed date or new token is allowed. | `BoundedWorkflowExecutorTest` stored a source event whose explicit absolute date came from the same-session masked user request; raw date text was not supplied as a tool value. The date value was stored with the trusted capture timezone because the source also stated a time. |

The runtime schema now describes field meanings, nullability, bounds, opaque token provenance, revisions, exact UTF-16 spans, and date/time/interval cross-field rules. The selected event skill distinguishes an ungrounded occurrence (clarify without writing) from a grounded partial reading (store known fields, then ask), and it never invents a relative-date anchor. The executor context block requires exact replay evidence and repair only from fresh authoritative masked observations. Existing query bounds, masking, scope, financial authority, service checks, retry limits, and budgets were not weakened.

## Host verification

Command:

```text
powershell.exe -NoProfile -Command "& '.\gradlew.bat' ':app:testDebugUnitTest' '--tests=io.github.triet4p.aegisf.agent.BoundedWorkflowExecutorTest' '--tests=io.github.triet4p.aegisf.agent.S813ContractRuntimeSmokeTest' '--tests=io.github.triet4p.aegisf.agent.S811ContextCacheTest' '--no-daemon'; exit $LASTEXITCODE"
```

Final run: **BUILD SUCCESSFUL**, 40 tests, 0 skipped, 0 failures, 0 errors:

- `BoundedWorkflowExecutorTest`: 28/28.
- `S811ContextCacheTest`: 11/11.
- Temporary `S813ContractRuntimeSmokeTest`: 1/1.

The throwaway multi-turn smoke used the production `BoundedWorkflowExecutor`, `MaskedHostedGateway`, canonical tool declarations/runtime skills, `BoundedWorkflowToolAdapter`, actual `WorkflowCommandService`/`WorkflowQueryService`, and real in-memory Room. An in-process scripted adapter supplied controlled turns; it made no network/API request and is not hosted-model capability evidence. The temporary test source was removed after the successful run.

`ProviderJson` schema-lowering paths were inspected: they preserve descriptions and the existing constraints, lower supported integer/nullability shapes, and apply to the three supported wire formats. No hosted endpoint request was made; protocol acceptance by a live service remains unverified.

The specific `masked_result_refused` path was not independently induced; its terminal classification is source-level evidence. The existing over-budget terminal-refusal test exercised the shared stop path but is not a direct masking-failure proof.

One preliminary build attempt failed because the executor lacked an import for the existing `classificationDescription` extension; the import was added and the final run above compiled and passed. A pre-fix combined run had two expected executor behavior failures (durable distinct-call identity and corrected stale-revision completion) and three Robolectric setup failures in `BoundedWorkflowToolAdapterTest`; that adapter test class was not rerun. The existing a3 `WorkflowCommandServiceTest` `SQLiteCantOpenDatabaseException` (`SQLITE_CANTOPEN`) remains unresolved and was not rerun or counted green.

## Device and qualification limits

No device install/test/mutation was run. Before the owner hold, read-only checks observed device `AAAJBB4930914141` online and both isolated `.s813evidence` app/test packages installed. Main then ordered a hold until explicit reconnect confirmation; no subsequent device command was issued. No base-app clear occurred. UI behavior was not touched.

The prior a1/a2/a3 handoffs and histories, a3 diagnosis, previous traces, and all frozen S8.13 inputs remain preserved. This correction is local evidence only; S8.13 remains unqualified and the cycle-1 round-1 assessment remains unassigned/unassessed.

## Owner decisions still needed

1. For a relative phrase with an explicit reference date/timezone but no exact target DATE token in masked context, should a future host implementation derive/mint that target token locally, or should the assistant continue asking for the absolute target date? This correction keeps the latter fail-closed behavior.
2. Frozen `a8-multi-02` says “Yesterday” without an absolute reference, but expects 2026-10-24. An owner must supply/approve the missing reference or explicitly resolve the expectation; this worker did not invent it.
3. Frozen heldout `a8h-laya-01` requests bus fare while the stored draft description is coffee at noon. The runtime classifies the authoritative stored description; owner must resolve prompt/fixture intent without changing frozen evidence in this task.
4. Link expectations conflict with stored amounts and candidate ids: event 311 is 28,000 VND vs draft 411 at 50,000 VND; event 313 is 120,000 VND vs lunch drafts at 50,000 VND; heldout `a8h-link-01` expects draft 422 while the observed model proposed 421. Preserve refusals/zero unauthorized effects; owner must resolve the frozen expectation/data inconsistency before qualification.
