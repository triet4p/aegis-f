# PT2 Evidence Review Report — Round 3

## Identity and Review Context

- **task_id**: `PT2`
- **reviewer attempt_id**: `PT2-review-a3`
- **agent_id**: `MaskedFlowReview115`
- **worker_attempt_id**: `PT2-a1`
- **worker agent_id**: `MaskedFlowStreaming114`
- **review_cycle_id**: `PT2-cycle-1`
- **review_round**: `3` (Main-authorized conditional third round under § 3)
- **worker_artifact_path**: `artifacts/production-trace-mask/workers/PT2-a3.md`
- **report_path**: `artifacts/production-trace-mask/reviews/PT2-review-03.md`
- **manifest_path**: `artifacts/production-trace-mask/reviews/PT2-review-03.manifest.json`
- **base_commit_sha**: `6589c880537ee61a92fb99b91b70df5bc501930c` (PT1 checkpoint)
- **target_ref**: `refs/heads/main`
- **snapshot_sha256**: `7d5fb04ad70aa1dd67efb40fa1bea47bee2ac88f3b85ed38e32dcf8f5bfd9d93`
- **verdict**: **PASS**
- **advance**: `true`
- **owner_decision_required**: `false`

---

## Executive Summary & Progress Delta Across Rounds

In this Main-authorized conditional third round (`PT2-cycle-1`, Round 3), worker `MaskedFlowStreaming114` resolved the single remaining defect identified in Round 2: the conflation of UI "allowed workflow skills" with mandatory "demanded effects" in `BoundedWorkflowExecutor`.

The worker cleanly separated the capability allowlist from demanded outcomes in `WorkflowRunRequest`, implemented a truthful re-read verification for already-interpreted occurrences, preserved all false-success and missing-effect guards, and demonstrated on-device execution of the full multi-intent flow on the actual physical phone with all five workflow skills enabled. The run concluded as **SUCCEEDED** (`run-6fd3a77f...`), verifying the classification effect and existing link identity with zero ledger writes.

All acceptance criteria across rounds 1, 2, and 3 are now fully satisfied by concrete, verified evidence on the physical device and passing offline test suites. No actionable finding remains.

### Progress Delta Summary (Rounds 1 → 2 → 3)

1. **Round 1 Findings (5 findings)**:
   - *Physical live acceptance & Laya model*: Cases 1 and 2 passed; Case 3 (ambiguity/clarify/resume) and Laya model import were unverified on-device.
   - *Privacy scan boundary relaxation*: Blanket alphanumeric boundary relaxation permitted glued leaks (e.g. `10000VND`, `10000k`) to bypass decoded scans.
   - *UI lifecycle & projection*: Tab switch dropped in-memory state; capture card projection showed stale "unresolved" text.
   - *Artifact root cause & architecture*: Inaccurate root-cause mechanics and non-existent helper references (`valueRefByArgument`, legacy delegation shims) in worker artifact.
2. **Round 2 Progress**:
   - Case 3a (ambiguity/clarify/resume) and Case 3b (draft search no-match) verified live on phone.
   - Pinned Laya model imported via SAF picker ("Ready — verified on this device", size+hash, ONNX open probe) and live inference executed (`classify_draft` -> `NO_FIT` snapshot stored).
   - Blanket boundary relaxation deleted; strict substring scan `scanLeak` restored; narrow metadata exemption `isNestedProviderIdPath` added for tool IDs only.
   - Shell-owned `ChatAgentRunState` preserved state across tab switches; `projectionRevision` refreshed capture cards; double-admission defect resolved with `startTracked(admitted, ...)`.
   - Documentation and architecture descriptions corrected in `PT2-a2.md` and `root-cause-correction.md`.
   - *Remaining Defect*: Multi-intent run with all 5 skills checked terminated as `REQUIRED_EFFECT_MISSING` because the executor demanded mutations for unrequested replay and already-interpreted event.
3. **Round 3 Resolution (Current Candidate `PT2-a3`)**:
   - Capability allowlist (`intents`) separated from demanded outcomes (`requiredIntents`) in `WorkflowRunRequest` (default `requiredIntents = intents` keeps existing caller contracts).
   - Interactive Chat passes `requiredIntents = emptySet()` so that enabling allowed skills does not force unrequested mutations.
   - `AgentNoEffectClaim.AlreadyInterpreted(eventId, revision)` attached by `select_source` when an occurrence is already `INTERPRETED`; `verifyNoEffect` re-reads Room DB to confirm revision and status; `verifyNoEffectSettlements` accepts it for `INTERPRET_EVENTS` even in mixed runs.
   - On-device regression executed with all 5 skills enabled: run `run-6fd3a77f...` ended **SUCCEEDED** (attempt 1 COMPLETED), `inspect_link` + `observe` + `classify_draft` executed, verified `Classification` effect reported, link verified as `ACCEPTED` rev 1, `transactions` count = 0.
   - Discriminating negative tests verify that unperformed required effects (such as required replay) still fail closed with `Incomplete(REQUIRED_EFFECT_MISSING)`.
   - 677 unit tests pass offline (0 failures, 3 skipped).

---

## Detailed Round-3 Verification

### 1. Capability Allowlist vs Demanded Outcomes Architecture

- In `BoundedWorkflowExecutor.kt` lines 260–287:
  ```kotlin
  data class WorkflowRunRequest(
      val intents: Set<WorkflowIntent>,
      val userRequest: String,
      val limits: WorkflowRunLimits = WorkflowRunLimits(),
      val settlement: WorkflowSettlement = WorkflowSettlement.REQUIRED_EFFECT,
      val requiredIntents: Set<WorkflowIntent> = intents,
  )
  ```
  `intents` defines the authorized tool/skill catalog available to the model. `requiredIntents` defines the outcomes that the host demands must be settled (via a verified effect or verified no-effect settlement).
- Backward compatibility: The default `requiredIntents = intents` ensures that qualification runs, automated test cases, and existing programmatic callers continue to enforce mandatory effect checklists.
- Interactive Chat: In `QuickCaptureScreen.kt` lines 272–281, the chat composer sets `intents = selectedIntents` and `requiredIntents = emptySet()`. This cleanly models the UI checkboxes ("Choose its allowed workflow skills") as authorization without injecting false mandatory effect requirements.
- Truthful reporting: In `complete()`, `reported = verified + noEffects.values`. Only effects that were actually dispatched and verified (or settlements proven by live re-read) are reported in the UI completion card.

### 2. Live Re-Read Proof for Already-Interpreted Occurrences

- `select_source` in `AgentToolRuntime.kt` lines 331–333 inspects the resolved occurrence. When an existing event is present with status `INTERPRETED`, it attaches `AgentNoEffectClaim.AlreadyInterpreted(it.id, it.revision)`.
- Live database verification in `AgentToolRuntime.kt` lines 1525–1528:
  ```kotlin
  is AgentNoEffectClaim.AlreadyInterpreted -> {
      val event = queries.noteEvent(claim.eventId) ?: return false
      event.revision == claim.revision && event.status == NoteEventStatus.INTERPRETED
  }
  ```
  The runtime directly re-queries Room DB via `WorkflowQueryService`, confirming that the event row exists, is still `INTERPRETED`, and matches the expected revision. It cannot fabricate: stale or missing rows fail verification.
- Mixed-run settlement in `BoundedWorkflowExecutor.kt` lines 1689–1702: If `INTERPRET_EVENTS` is in `requiredIntents`, the verified `AlreadyInterpreted` claim settles as `WorkflowVerifiedEffect.NoEffect.AlreadyInterpreted` prior to the zero-mutation check for draft/link searches, allowing mixed runs to succeed honestly.

### 3. Preserved Guard Verification & Discriminating Tests

- In `BoundedWorkflowExecutorTest.kt`:
  - `a host-required replay without a replay stays incomplete`: `requiredIntents = setOf(REPLAY_OPERATION)`, no replay dispatched -> returns `Incomplete(REQUIRED_EFFECT_MISSING, detail="replay_operation")`.
  - `an unproved absence stays incomplete when the effect is required`: `requiredIntents = setOf(INTERPRET_EVENTS)`, unproved absence -> returns `Incomplete(REQUIRED_EFFECT_MISSING)`.
  - `an enabled but unused capability is not a demanded effect`: `intents = {INTERPRET, REPLAY}`, `requiredIntents = emptySet()` -> returns `Completed`, verified effects empty.
  - `a host-required interpretation settles when the selected event is already interpreted`: `requiredIntents = {INTERPRET_EVENTS}`, event already interpreted -> returns `Completed`, settled as `NoEffect.AlreadyInterpreted`.
- No guards were disabled or weakened; effect verification for dispatched mutation tools remains strictly enforced.

### 4. Actual-Main Physical Device Regression Proof

- **Environment**: Same installed main app `io.github.triet4p.aegisf`, same-signer APK `a86fe7679872bad7d27cc563de07f2859327fdec646e2fbc6bc35bd9d8a286d0`, existing configured OpenCode Go provider/key preserved, existing notes and draft #1 preserved.
- **Scenario**: All five skills enabled in UI checkboxes (`INTERPRET_EVENTS`, `LINK_EVENT_TO_DRAFT`, `PREPARE_LAYA_REVIEW`, `REPLAY_OPERATION`, `SEARCH_DRAFTS`). Prompt entered: read the latest already-interpreted note, find/check the matching pending draft, verify the existing link, use Laya to suggest a category, do not confirm any transaction or replay.
- **Run Outcome**: Run `run-6fd3a77f4388d323196626135ad8e2e5` concluded as **SUCCEEDED** (attempt 1 `COMPLETED`, stopReason `none`).
- **Executed Tools**: `inspect_link`, `observe`, `classify_draft`. Zero calls to `replay_operation`, zero redundant `record_event`, zero duplicate `attach_link`.
- **Verified Effects**: `Classification(draftId=1, source=NO_FIT, suggestedCategoryId=null, confidence=0.2423, reviewStatus=PENDING)`. The UI completion card (`10-completed.png`) displays this verified effect directly.
- **Link & Ledger Invariants**: Event #2 linked to draft #1 remains `ACCEPTED` revision 1 (verified without re-attachment). Ledger `transactions` count = 0 (no financial confirmation, no ledger write).
- **Sanitized Evidence**: `artifacts/production-trace-mask/evidence/PT2-a3/db-summary.json` and screenshots `device/01–10`.

---

## Final Verification Matrix

| Area / Criterion | R1 | R2 | R3 (Final) | Evidence / Source Reference |
|---|---|---|---|---|
| Neutral token identity & shape hints | VERIFIED | VERIFIED | **VERIFIED** | `SensitiveVault.kt`, `IssuedValueShape`, `s8-agent-token-inventory/v2` |
| Local role parsing & no regex gates | VERIFIED | VERIFIED | **VERIFIED** | `AgentToolRuntime.kt` (`parseAmount`, `parseDateValue`, `parseTimeValue`) |
| Contract / Schema / Skill clean cutover | VERIFIED | VERIFIED | **VERIFIED** | `aegis-agent-interface/4`, tools/skills v4 assets, zero shims |
| Real SSE streaming & chunk tracing | VERIFIED | VERIFIED | **VERIFIED** | `HostedStreaming.kt`, `TracingHttpTransport.kt`, tests passing |
| Strict leak scanning & metadata exemption | UNVERIFIED | VERIFIED | **VERIFIED** | Strict substring `scanLeak` on content; narrow `isNestedProviderIdPath` for tool IDs |
| UI lifecycle & tab switch retention | UNVERIFIED | VERIFIED | **VERIFIED** | `ChatAgentRunState.kt` owned by `AppShell`; state survives tab navigation |
| Capture list projection refresh | UNVERIFIED | VERIFIED | **VERIFIED** | `projectionRevision` reloads list; `Ca phe` shows "Interpreted · not linked" |
| Single run tracking (no double-admission) | UNVERIFIED | VERIFIED | **VERIFIED** | `BoundedWorkflowExecutor.startTracked(admitted, ...)`; single run ID |
| Case 1: Plain amount & interval | VERIFIED | VERIFIED | **VERIFIED** | Event #2 (10000 VND, 10h–11h), `run-55294f2f...` SUCCEEDED |
| Case 2: Unit money & interval | VERIFIED | VERIFIED | **VERIFIED** | Event #3 (25000 VND, 9h–10h), `run-8a4882e7...` SUCCEEDED |
| Case 3a: Ambiguity / clarify / resume | UNVERIFIED | VERIFIED | **VERIFIED** | Event #4 (20000 VND, `run-67ac...` SUCCEEDED after clarification answer) |
| Case 3b: Draft search no-match | UNVERIFIED | VERIFIED | **VERIFIED** | `list_drafts` -> "No match found" (`run-0760...` SUCCEEDED) |
| Local Laya model readiness & inference | UNVERIFIED | VERIFIED | **VERIFIED** | SAF picker import ("Ready — verified on this device"); `classify_draft` -> `NO_FIT` |
| Link persistence (zero ledger writes) | UNVERIFIED | VERIFIED | **VERIFIED** | Event #2 ↔ Draft #1 `ACCEPTED` rev 1; `transactions` count = 0 |
| Multi-intent full owner flow completion | UNVERIFIED | UNVERIFIED | **VERIFIED** | All 5 skills enabled; prompt executed; `run-6fd3a77f...` ended **SUCCEEDED** |
| Missing-effect / unproved absence guards | VERIFIED | VERIFIED | **VERIFIED** | Discriminating tests in `BoundedWorkflowExecutorTest.kt` pass offline |
| Offline test suite green | VERIFIED | VERIFIED | **VERIFIED** | 677 unit tests pass offline (0 failures, 3 skipped) |

---

## Actionable Findings

**None.** All findings from rounds 1 and 2 have been fully resolved with verified source implementations, passing offline test suites, and concrete on-device execution proof.

---

## Review Gate Conclusion

The PT2 candidate meets all owner acceptance criteria, technical contracts, privacy boundaries, and on-device execution directives. The candidate snapshot is frozen and bound by manifest `artifacts/production-trace-mask/reviews/PT2-review-03.manifest.json` (`7d5fb04ad70aa1dd67efb40fa1bea47bee2ac88f3b85ed38e32dcf8f5bfd9d93`).

The review verdict is **PASS** (`advance: true`). The candidate is ready for Main's full-route exact-snapshot checkpoint authorization by an explicit Bronze agent.
