# PT2 Evidence Review Report — Round 2

## Identity and Review Context

- **task_id**: `PT2`
- **reviewer attempt_id**: `PT2-review-a2`
- **agent_id**: `MaskedFlowReview115`
- **worker_attempt_id**: `PT2-a1`
- **worker agent_id**: `MaskedFlowStreaming114`
- **review_cycle_id**: `PT2-cycle-1`
- **review_round**: `2`
- **worker_artifact_path**: `artifacts/production-trace-mask/workers/PT2-a2.md`
- **report_path**: `artifacts/production-trace-mask/reviews/PT2-review-02.md`
- **manifest_path**: `artifacts/production-trace-mask/reviews/PT2-review-02.manifest.json`
- **base_commit_sha**: `6589c880537ee61a92fb99b91b70df5bc501930c` (PT1 checkpoint)
- **target_ref**: `refs/heads/main`
- **snapshot_sha256**: `5c65c12a093963b10b9ef71e914585ca775ba9404a90efedd94bffcc98a2dd09`
- **verdict**: **FAIL** (`changes_requested`, `advance: false`)
- **owner_decision_required**: `false` (Awaiting Main's conditional third-round decision or owner directive; round count 2 consumed)

---

## Executive Summary & Progress Delta Across Rounds

In Round 2 of cycle `PT2-cycle-1`, worker `MaskedFlowStreaming114` resolved four out of the five Round-1 findings, made substantial verified progress across on-device actual-main acceptance, and discovered and resolved a subtle production run-tracking defect:

1. **Finding 1 Progress (Live Acceptance & Local Laya Model)**:
   - **Ambiguity / Clarification / Resume**: Successfully executed live on the physical device (`run-67ac...`). The model detected two competing amounts (`20000` and `30000`) in capture #8, asked a genuine clarification question via `ask_user`, and resumed on the user's answer (`20000`) to create event #4 (`INTERPRETED`, revision 2, zero ledger writes).
   - **Draft Search / No Match**: Successfully executed live (`run-0760...`). `list_drafts` was called across pending and all scopes; the model concluded with "No match found — in an exhausted, fully-scoped read" with draft amounts masked.
   - **Local Laya Model Import & Real Inference**: The pinned bundle at `/sdcard/Download/Laya-model/` was imported via the SAF system folder picker. The UI verified size, SHA-256, opened the ONNX runtime, and reported "Ready — verified on this device" (`03-model-ready.png`). Real local inference was executed via `classify_draft` on draft #1 (`run-38d0...`, SUCCEEDED), storing a valid classification snapshot (`decisionSource=NO_FIT`, confidence 0.2423, modelRevision `68f27dfe.../512/192`).
2. **Finding 2 Progress (Decoded Leak Scan Strictness)**:
   - The blanket alphanumeric boundary relaxation (`containsMappedSurface`, `scanValueLeak`, `scanValueLeakShapes`) was completely deleted.
   - Strict substring scanning (`scanLeak`) was restored across all decoded text, ensuring values like `10000VND`, `10000k`, `10000usd`, and `acc12345678` are refused.
   - A narrow, schema-bound exemption (`HostedUsageContract.isNestedProviderIdPath`) was introduced strictly for documented provider call ID locations (`choices[].message.tool_calls[].id` in Chat Completions and `output[].id`/`output[].call_id` in Responses). It requires `isOpaqueProviderId` and explicitly refuses any ID equal to a mapped value (`!vault.isMappedValueExact`).
3. **Finding 3 Progress (UI Lifecycle & Capture Projection)**:
   - `ChatAgentRunState` is now owned by `AppShell` and executed on the shell's coroutine scope. In-flight runs, stream text, notes, and continuation state survive tab switching (`35-tab-midrun.png`, `36-tab-return-midrun.png`).
   - `projectionRevision` increments on every settle, properly refreshing the capture list so that `Ca phe` displays "Interpreted · not linked" instead of "unresolved".
   - An additional defect was discovered and fixed: the UI previously created an admission that was duplicated by `startTracked`, creating orphan `RUNNING` rows. `BoundedWorkflowExecutor.startTracked(admitted, ...)` now claims the caller's admission, ensuring clarification runs remain under a single run ID.
4. **Findings 4 & 5 Progress (Documentation & Architectural Accuracy)**:
   - Root-cause mechanics and architectural descriptions were fully reconciled in `PT2-a2.md` and `root-cause-correction.md`.

### The Remaining Block / Defect

Despite these substantial achievements, the candidate cannot receive PASS on Round 2 because the **full owner multi-intent workflow still ends in `REQUIRED_EFFECT_MISSING`**:
- When the user enables the allowed skills in the UI (all 5 checkboxes) and asks the agent to link the note and categorize it with Laya (`05-owner-prompt.png`), the model correctly executes `select_source`, `inspect_link` (eligible), `propose_link` (proposed), `attach_link` (accepted / verified), and `classify_draft` (applied).
- The business effects are successfully stored in Room DB (link between event #2 and draft #1 accepted; classification snapshot recorded).
- However, `BoundedWorkflowExecutor.kt:1581-1605` treats every intent in `run.request.intents` as a mandatory demanded effect. Because the note was already interpreted and the prompt did not ask to replay an operation, `INTERPRET_EVENTS` and `REPLAY_OPERATION` produced no mutations.
- The executor terminated the run with `WorkflowRunStopReason.REQUIRED_EFFECT_MISSING: interpret_events,replay_operation` (`run-9f68...`).
- A benign enabled skill must not force an unrelated replay or redundant interpretation when the user did not ask for it. This production defect prevents the real multi-intent owner experience from completing with `SUCCEEDED`.

Per `skill://omp-subagent-flows/references/evidence-review-cycle.md`, Round 2 is closed as **FAIL** (`changes_requested`), and the progress delta and bounded remaining finding are submitted to Main for the conditional third-round decision.

---

## Remaining Actionable Finding

### Finding 1 [HIGH / MEDIUM]: Conflation of UI Allowed Skills with Mandatory Demanded Effects Causes `REQUIRED_EFFECT_MISSING` Failure
- **Claim**: The application conflates user-selected "allowed workflow skills" with mandatory "demanded effects" for that run, causing runs where unrequested or already-completed skills were benignly enabled to terminate as failed with `REQUIRED_EFFECT_MISSING`.
- **Evidence**:
  - `QuickCaptureScreen.kt` lines 272–275 constructs `WorkflowRunRequest(intents = selectedIntents, userRequest = prompt)` directly from the UI checkboxes labeled "Choose its allowed workflow skills" (`08-laya-intent.png`).
  - In `artifacts/production-trace-mask/evidence/PT2-a2/device/05-owner-prompt.png`, the user asked: "Read the latest note I wrote, interpret it, find the matching pending draft, check before linking and ask me if it is not clear. After linking correctly, use Laya to suggest a category. Do not confirm any transaction." The user did not request an operation replay.
  - In `BoundedWorkflowExecutor.kt` lines 1581–1605:
    ```kotlin
    val missing = run.request.intents.filter { intent ->
        when (intent) {
            WorkflowIntent.INTERPRET_EVENTS ->
                verified.none { it is WorkflowVerifiedEffect.EventReading } &&
                    noEffects[intent] == null
            WorkflowIntent.LINK_EVENT_TO_DRAFT ->
                verified.none { it is WorkflowVerifiedEffect.LinkState } &&
                    noEffects[intent] == null
            WorkflowIntent.PREPARE_LAYA_REVIEW -> verified.none {
                it is WorkflowVerifiedEffect.Classification || it is WorkflowVerifiedEffect.ClassificationUnavailable
            } && noEffects[intent] == null
            WorkflowIntent.SEARCH_DRAFTS -> false
            WorkflowIntent.REPLAY_OPERATION ->
                verified.none { it is WorkflowVerifiedEffect.ReplayIdentity } &&
                    noEffects[intent] == null
        }
    }
    if (missing.isNotEmpty()) {
        transition(run.trace, WorkflowRunState.INCOMPLETE, "requested effects missing")
        return incomplete(
            run,
            WorkflowRunStopReason.REQUIRED_EFFECT_MISSING,
            missing.joinToString(",") { it.name.lowercase() },
        )
    }
    ```
  - In `artifacts/production-trace-mask/evidence/PT2-a2/db-summary.json`, run `run-9f68...` (the full owner workflow) terminated with state `FAILED` and `stopReason: REQUIRED_EFFECT_MISSING`.
  - The business actions (`attach_link` accepted, `classify_draft` applied) were fully executed and persisted in Room DB, but the UI shows the run as incomplete/failed.
- **Why It Matters**: A user enabling skills in the UI expects the agent to be authorized to use those tools if needed. An enabled skill must not turn into a forced requirement that fails the run when the user prompt did not ask for it or when the target entity (event #2) was already interpreted.
- **Proposed Concrete Correction for Round 3**:
  1. In `BoundedWorkflowExecutor.kt`, an enabled intent whose tool was not called should not be demanded as a required effect if other mutation tools were called and satisfied the user's requested actions; OR recognize that an already-interpreted event satisfies `INTERPRET_EVENTS` (or settles as no-effect needed), and do not demand `REPLAY_OPERATION` unless a replay was requested/dispatched.
  2. Alternatively, in `QuickCaptureScreen.kt`, decouple "allowed skills" from demanded effects so that enabling tools does not inject mandatory effect requirements into `WorkflowRunRequest`.
  3. Re-run the full owner flow on the physical phone with all skills enabled; verify that the run terminates with `WorkflowRunOutcome.Completed` (`SUCCEEDED`) rather than `REQUIRED_EFFECT_MISSING`.
- **Confidence**: 1.0.

---

## Verification Matrix

| Area / Criterion | Round 1 Status | Round 2 Status | Evidence / Source Reference |
|---|---|---|---|
| Neutral token identity & shape hints | VERIFIED | **VERIFIED** | `SensitiveVault.kt`, `IssuedValueShape`, `s8-agent-token-inventory/v2` |
| Local role parsing & no semantic regex gates | VERIFIED | **VERIFIED** | `AgentToolRuntime.kt` (`parseAmount`, `parseDateValue`, `parseTimeValue`) |
| Contract / Schema / Skill clean cutover | VERIFIED | **VERIFIED** | `aegis-agent-interface/4`, tools/skills v4 assets, no shims |
| Real SSE streaming & chunk tracing | VERIFIED | **VERIFIED** | `HostedStreaming.kt`, `TracingHttpTransport.kt`, tests passing |
| Decoded leak scan strictness | UNVERIFIED | **VERIFIED** | Blanket relaxation deleted; strict `scanLeak` restored; narrow metadata exemption via `HostedUsageContract.isNestedProviderIdPath` |
| UI lifecycle & tab switch retention | UNVERIFIED | **VERIFIED** | `ChatAgentRunState.kt` owned by `AppShell`; job and state survive tab navigation |
| Capture list projection refresh | UNVERIFIED | **VERIFIED** | `projectionRevision` reloads list; `Ca phe` shows "Interpreted · not linked" |
| Double-admit defect resolved | UNVERIFIED | **VERIFIED** | `BoundedWorkflowExecutor.startTracked(admitted, ...)` claims caller admission; clarify run is single run ID |
| Physical app Case 1 (plain amount & interval) | VERIFIED | **VERIFIED** | Event #2 (10000 VND, 10h–11h), `run-55294f2f...` SUCCEEDED |
| Physical app Case 2 (unit money & interval) | VERIFIED | **VERIFIED** | Event #3 (25000 VND, 9h–10h), `run-8a4882e7...` SUCCEEDED |
| Physical app Case 3a (ambiguity/clarify/resume) | UNVERIFIED | **VERIFIED** | Capture #8 (20k vs 30k) -> clarification asked -> resumed -> Event #4 (20000 VND, `run-67ac...` SUCCEEDED) |
| Physical app Case 3b (draft search / no match) | UNVERIFIED | **VERIFIED** | `list_drafts` -> "No match found" (`run-0760...` SUCCEEDED) |
| Physical app local Laya model readiness | UNVERIFIED | **VERIFIED** | SAF picker import -> "Ready — verified on this device", size+sha256+ONNX open |
| Physical app local Laya inference execution | UNVERIFIED | **VERIFIED** | `classify_draft` on draft #1 -> `NO_FIT` snapshot stored (`run-38d0...` SUCCEEDED) |
| Physical app link persistence (no ledger write) | UNVERIFIED | **VERIFIED** | Event #2 ↔ Draft #1 `ACCEPTED` rev 1; `transactions` count = 0 |
| Physical app full owner workflow completion | UNVERIFIED | **UNVERIFIED** | Actions executed and persisted, but run ended `REQUIRED_EFFECT_MISSING` due to skill/effect conflation |

---

## Conclusion & Recommendation for Main

All concrete implementation and evidence gaps from Round 1 have been resolved with high technical fidelity. The single remaining issue is the architectural conflation between UI "allowed skills" and executor "mandatory demanded effects", which prevents the multi-intent owner run from concluding as `COMPLETED`.

Because:
1. Earlier findings were materially resolved with demonstrable on-device proof,
2. The remaining defect is bounded, with a clear mechanical cause and a concrete fix in executor/UI semantics,
3. A single discriminating verification step on the physical phone will confirm full completion,

this review report recommends that Main authorize **Conditional Round 3** under `skill://omp-subagent-flows/references/evidence-review-cycle.md` § 3 to correct this bounded defect and achieve full PASS.
