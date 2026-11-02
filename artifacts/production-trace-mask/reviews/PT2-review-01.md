# PT2 Evidence Review Report — Round 1

## Identity and Review Context

- **task_id**: `PT2`
- **reviewer attempt_id**: `PT2-review-a1`
- **agent_id**: `MaskedFlowReview115`
- **worker_attempt_id**: `PT2-a1`
- **worker agent_id**: `MaskedFlowStreaming114`
- **review_cycle_id**: `PT2-cycle-1`
- **review_round**: `1`
- **worker_artifact_path**: `artifacts/production-trace-mask/workers/PT2-a1.md`
- **report_path**: `artifacts/production-trace-mask/reviews/PT2-review-01.md`
- **manifest_path**: `artifacts/production-trace-mask/reviews/PT2-review-01.manifest.json`
- **base_commit_sha**: `6589c880537ee61a92fb99b91b70df5bc501930c` (PT1 checkpoint)
- **target_ref**: `refs/heads/main`
- **snapshot_sha256**: `fe189a43c9125a2551ab31519371275886f0f95a84b700150bfd3e67bb718b32`
- **verdict**: **FAIL** (`changes_requested`, `advance: false`)
- **owner_decision_required**: `false` (Round 2 authorized by default protocol within `PT2-cycle-1`)

---

## Executive Summary

The PT2 candidate submitted by worker `MaskedFlowStreaming114` successfully implements the neutral token architecture (`aegis-agent-interface/4`, `aegis-f-s8-agent-tools/v4`, `aegis-f-s8-agent-runtime-skills/v4`, `s8-agent-context/v9`, `s8-agent-token-inventory/v2`), removes obsolete regex-derived semantic token types, deletes legacy typed rehydration methods without leaving shims, and implements genuine SSE streaming across all three provider protocols (`OpenAiChatCompletionsAdapter`, `OpenAiResponsesAdapter`, and `GeminiGenerateContentAdapter`) with incremental public output gating (`PublicStreamGate`), null-safe parsing, tool argument completion guards, and cancellation tracing. The unit test suite passes offline (673 tests, 0 failures), and the updated main app APK was deployed to the reconnected physical phone over the existing package without data loss or credential exposure.

However, the candidate cannot be approved for PASS on Round 1 due to three substantive issues:

1. **Material Acceptance Verification Gaps on Physical Device**: While the physical device was reconnected and Case 1 (owner note with plain integer `10000` and interval `10h-11h`) and Case 2 (`Ca phe 25.000 VND`) were executed live to successful interpretation, Case 3 (ambiguity / no-match / clarification-resume) was omitted live on the grounds that the capture composer form became unreachable after a long conversation. Furthermore, the complete original owner workflow involving draft search (`list_drafts`), link proposal/attachment (`propose_link`/`attach_link`), and Laya review preparation was not exercised live. The pinned local model bundle (`receptron/Laya@68f27dfe`) was staged in `/sdcard/Download/Laya-model/` and size-verified, but was not imported into the application or verified for runtime inference readiness.
2. **Decoded Leak Scan Boundary Relaxation**: To eliminate false-positive refusals caused by short mapped values appearing inside opaque provider-minted call IDs (e.g. `call_…sk11h6ax0` embedding mapped `11h`), `containsMappedSurface` in `SensitiveVault.kt` was modified to require that neither neighbor be an ASCII letter or digit. This blanket relaxation causes true sensitive values adjoining ASCII letters or digits (e.g. `10000VND`, `10000k`, `10000usd`, `acc12345678`, `ref987654`) to escape detection during response-level JSON scanning in `scanInboundResponseJson`.
3. **End-to-End Main UI Usability Limitations**: In `QuickCaptureScreen.kt`, stream text, status notes, job references, and workflow continuations are held in composable-scoped `remember { mutableStateOf(...) }` variables. As disclosed by the worker, switching tabs immediately drops this in-memory state. In addition, the capture card UI exhibits a stale projection defect where `Ca phe` displayed "unresolved · no interpretation recorded" despite the durable database having correctly stored the event.

Consequently, `advance` is **false** and `changes_requested` is issued for Round 2 in `PT2-cycle-1`.

---

## Actionable Findings

### Finding 1 [HIGH]: Material Acceptance Verification Gaps on Physical Device
- **Claim**: The actual-main on-device verification does not satisfy the owner's acceptance criteria for independent case coverage, full flow execution, or local model readiness.
- **Evidence**:
  - `artifacts/production-trace-mask/workers/PT2-a1.md` lines 89–90 admits: "Case 3: Ambiguity / no-match / clarification-resume ... NOT RUN live. The chat's capture form was no longer reachable after the long conversation without further UI surgery; reported, not claimed."
  - The live runs in `artifacts/production-trace-mask/evidence/PT2-a1/db-summary.json` contain only `INTERPRET_EVENTS` intents (`run-55294f2f83409f2ed0fcd6874ae73b24` and `run-8a4882e73dda1430292abb880f834940`). Tools `list_drafts`, `propose_link`, `attach_link`, and `prepare_laya_review` from the owner's original workflow were not exercised.
  - `PT2-a1.md` lines 124–125 states: "Pinned `receptron/Laya@68f27dfe` bundle staged on the phone at `/sdcard/Download/Laya-model/` ... App-level local inference readiness is NOT claimed: it was not exercised in this attempt."
- **Why It Matters**: The owner directive explicitly mandated: "use the actual installed main app `io.github.triet4p.aegisf`, interact through its UI, and exercise the existing configured provider credential plus owner-created note/review draft examples. Actual-main UI execution and its own persisted DB trace are required acceptance evidence, not an isolated test APK or synthetic-only substitute." Inability to reach the composer form in a long chat session is an actionable UI flow defect, not an external barrier.
- **Confidence**: 1.0 (Directly confirmed by worker artifact, DB summary, and IRC handoff).

### Finding 2 [MEDIUM]: Decoded Leak Scan Boundary Relaxation Permits True Leaks Adjoining Letters/Digits
- **Claim**: The `containsMappedSurface` boundary check in `SensitiveVault.kt` ignores sensitive occurrences when adjacent to ASCII letters or digits, creating an unmonitored egress channel during response-level scanning.
- **Evidence**:
  - `SensitiveVault.kt` lines 291–305:
    ```kotlin
    val before = text.getOrNull(index - 1)
    val after = text.getOrNull(index + surface.length)
    if ((before == null || !before.isAsciiLetterOrDigit()) &&
        (after == null || !after.isAsciiLetterOrDigit())
    ) {
        return true
    }
    ```
  - In `MaskedPromptBoundary.kt` line 522, `scanResponseJsonText` calls `scanInbound(text, valueBoundaries = true)`.
  - When a sensitive mapped surface (e.g. `10000`) is output without spacing before a unit or after a reference prefix (e.g. `10000VND`, `10000k`, `10000usd`, `acc12345678`), `(!after.isAsciiLetterOrDigit())` or `(!before.isAsciiLetterOrDigit())` evaluates to `false`, causing the scan to treat the true sensitive value as an uninspected identifier.
- **Why It Matters**: The assignment specifically instructed: "In particular inspect decoded scan change from substring to value-boundary: whether it permits true sensitive-value leaks adjoining letters/digits, don't accept blanket regex relaxation as 'falsepositive fix'. Preserve raw egress/tool/final invariants." The proper remediation is to recognize and exempt opaque provider call ID fields, rather than relaxing leak boundaries across all decoded string and number values.
- **Confidence**: 0.95 (Code inspection and regex analysis).

### Finding 3 [MEDIUM]: Ephemeral State Loss on Tab Switch and Stale Capture Projection in Main UI
- **Claim**: Navigating between bottom tabs drops active clarification and streaming state; newly interpreted captures fail to update their display cards immediately.
- **Evidence**:
  - `QuickCaptureScreen.kt` lines 135–141 stores `agentExecutor`, `agentContinuation`, `agentJob`, `agentStreamText`, and `agentStreamNote` in `remember { mutableStateOf(...) }`. When the user switches to Review or Dashboard and returns, the Composable is recomposed with fresh null state.
  - `artifacts/production-trace-mask/evidence/PT2-a1/device/20-tabswitch-after.png` captures the lost state.
  - `PT2-a1.md` line 135 documents: "The `Ca phe` capture card in the UI still displayed 'unresolved · no interpretation recorded' after its event was stored... stale capture projection."
- **Why It Matters**: Although durable Room DB persistence ensures data is not lost, the interactive UI contract requires that clarification prompts remain answerable and that recorded interpretations project accurately into the visible capture list.
- **Confidence**: 0.95 (Verified in source code and documented by worker screenshots).

### Finding 4 [LOW]: Root Cause Description in Worker Artifact Contradicted Pre-Fix Mechanics
- **Claim**: The frozen worker artifact `PT2-a1.md` stated that the amount `10000` became an amount-typed token that could not be used as date/time, whereas pre-fix source actually typed it as `ACCOUNT`.
- **Evidence**:
  - `PT2-a1.md` lines 15–17: "The owner's note states `10000`, `02/11`, `10h-11h`; the amount became an amount-typed token, so the model could not use it as a date/time argument..."
  - Pre-fix source at `6589c880` (`HEAD`): `SensitiveVault.kt` mapped bare digits `\b\d{4,}\b` to `SensitiveKind.ACCOUNT`. `AgentToolRuntime.valueRefsIn` filtered out `ACCOUNT` tokens (`if (kind != AMOUNT && kind != DATE && kind != TIME) return@forEach`). Informal date `02/11` and interval `10h-11h` did not match `YYYY-MM-DD` or `HH:mm` and were never tokenized.
  - The worker recognized this discrepancy and provided a standalone correction in `artifacts/production-trace-mask/evidence/PT2-a1/root-cause-correction.md`.
- **Why It Matters**: Historical ground truth and audit accuracy require that the documented root cause accurately reflect the software defects that produced the observed failure.
- **Confidence**: 1.0 (Verified against base commit `6589c880` source code).

### Finding 5 [LOW]: Inaccurate Descriptive Wording in Worker Artifact Concerning Runtime Architecture
- **Claim**: The worker artifact describes non-existent helper functions and delegates that mischaracterize the clean cutover.
- **Evidence**:
  - `PT2-a1.md` line 46 cites: "`AgentToolRuntime.valueRefByArgument`: the host chooses a value's meaning by the argument it is passed to; only date-shaped tokens are offered for date arguments..."
  - `PT2-a1.md` line 49 states: "`HostedGeneration` legacy typed entry points now delegate to the neutral parsers."
  - In actual source code: `valueRefByArgument` does not exist; `valueRefsIn` neutrally issues all tokens as `AgentReferenceKind.VALUE` without semantic pre-filtering; and `HostedGeneration.kt` contains zero legacy delegation shims (all legacy typed functions were completely deleted).
- **Why It Matters**: While the actual code is cleaner and more compliant with the no-shim rule than the artifact claimed, documentation discrepancies cause review confusion and false alarms.
- **Confidence**: 1.0 (Code inspection confirms no shims or filtering in source).

### Finding 6 [INFO]: Real Protocol Streaming Implementation Confirmed Sound
- **Claim**: The multi-protocol streaming implementation meets technical criteria for incremental display, boundary protection, and trace persistence.
- **Evidence**:
  - `HostedSseDecoder` properly parses SSE streams, CRLF line endings, multi-line payloads, and `[DONE]` sentinels.
  - `TracingHttpTransport.postStream` forwards the `HostedStreamFrame.Start` response head and records every chunk (`PROVIDER_STREAM_CHUNK`) and termination (`PROVIDER_STREAM_END`).
  - `PublicStreamGate` holds back incomplete token spans (`⟦...⟧`) and trailing characters equal to `maxMappedSurfaceLength() - 1` to prevent leaking split sensitive literals.
  - Private reasoning is excluded from public stream progress in all adapters (`thought` flag in Gemini, reasoning items in Responses, content-only in Chat Completions).
- **Confidence**: 0.95.

---

## Verification Matrix

| Area / Criterion | Status | Evidence / Source Reference |
|---|---|---|
| Neutral token identity (`⟦V-...⟧`) & shape hints | **VERIFIED** | `SensitiveVault.kt`, `IssuedValueShape`, `AgentContextManager.kt` (`s8-agent-token-inventory/v2`) |
| No regex semantic gates on reference eligibility | **VERIFIED** | `AgentToolRuntime.kt` lines 2008–2037 (`valueRefsIn` issues `VALUE` for all tokens) |
| Local argument-role parsing & validation | **VERIFIED** | `AgentToolRuntime.kt` (`parseAmount`, `parseDateValue`, `parseTimeValue`) |
| Contract / Schema / Skill / Prompt cutover | **VERIFIED** | `AgentInterface.CONTRACT = "aegis-agent-interface/4"`, `s8_agent_tools_v4.json`, `s8_agent_runtime_skills_v4.json`, `s8-agent-context/v9` |
| Obsolete shims & legacy entry points removed | **VERIFIED** | Residual search clean; zero typed rehydration shims in `SensitiveVault.kt` or `HostedGeneration.kt` |
| Real SSE streaming & chunk tracing | **VERIFIED** | `HostedStreaming.kt`, `TracingHttpTransport.kt`, `HostedStreamingAdapterTest.kt` |
| Incremental public gate & privacy boundary | **VERIFIED** | `PublicStreamGate.kt`, `PublicStreamGateTest.kt` |
| Unit test suite green | **VERIFIED** | 673 unit tests pass (`:app:testDebugUnitTest --offline`) |
| Same-signer update preserving database & keys | **VERIFIED** | `db-summary.json`, existing provider and captures preserved |
| Physical app live execution: Cases 1 & 2 | **VERIFIED** | `run-55294f2f83409f2ed0fcd6874ae73b24`, `run-8a4882e73dda1430292abb880f834940`, screenshots `01`–`25` |
| Physical app live execution: Case 3 (ambiguity/clarify) | **UNVERIFIED** | Omitted by worker (`PT2-a1.md` line 89) |
| Physical app live execution: full search/link/Laya flow | **UNVERIFIED** | Omitted; only `INTERPRET_EVENTS` exercised live |
| Local Laya model import & inference readiness | **UNVERIFIED** | Files staged in `/sdcard/Download/Laya-model/` but not imported/tested in app |
| Safe leak boundary without ASCII letter/digit bypass | **UNVERIFIED** | `SensitiveVault.containsMappedSurface` allows bypass if adjacent to ASCII alphanumeric |

---

## Batched Actionable Remediation for Round 2

Worker `MaskedFlowStreaming114` is authorized to proceed with Round 2 corrections under cycle `PT2-cycle-1`. The following batched items must be addressed and verified:

1. **Complete Live Acceptance on Physical Main App**:
   - Drive the actual installed app `io.github.triet4p.aegisf` through Case 3 (ambiguity / no-match / clarification-resume). Reset or navigate the chat composer UI as needed; do not drop the case due to chat length.
   - Exercise the full owner workflow including draft search (`list_drafts`), link inspection/proposal (`propose_link`/`attach_link`), and Laya review preparation (`prepare_laya_review`).
   - Import and verify the staged local Laya model (`/sdcard/Download/Laya-model/`) for runtime inference readiness.
2. **Correct Decoded Leak Scan Boundary Logic**:
   - Refine `containsMappedSurface` or the response scanner so that true sensitive-value leaks adjacent to ASCII letters or digits (e.g. `10000VND`, `10000k`, `10000usd`, `acc12345678`) are not ignored.
   - Restrict identifier-boundary allowances to known provider metadata fields (such as `tool_calls[].id` matching `isOpaqueProviderId`), rather than applying a blanket alphanumeric boundary relaxation across all decoded string and number values.
3. **Address Bounded UI Lifecycle Defects**:
   - Ensure the chat composer retains its active clarification and stream projection during tab navigation (e.g. via `rememberSaveable` or ViewModel state preservation).
   - Ensure the capture card UI properly refreshes its projection when an event is recorded so that interpreted captures do not display stale "unresolved" status.
4. **Reconcile Worker Documentation**:
   - Update `PT2-a2.md` with the accurate pre-fix root cause (referencing `root-cause-correction.md`), and remove inaccurate references to `valueRefByArgument` or legacy delegation shims.
