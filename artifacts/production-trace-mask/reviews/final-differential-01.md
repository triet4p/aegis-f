# Final Differential Review — ProductionTraceMask-final

- **task_id**: `ProductionTraceMask-final`
- **reviewer attempt_id**: `PT-final-a1`
- **agent_id**: `ProductionFlowFinal117` (deep-reviewer, differential; read-only)
- **sprint / correction scope**: `docs/production-trace-mask-correction.md` (PT1 + PT2, full route)
- **repository**: `F:/ai-ml/aegis-f`; git dir `.git`; ref `refs/heads/main`
- **reviewed HEAD (exact)**: `8518676c3e8f7475c1a5140275224fe286cb4a92` — verified directly from `.git/refs/heads/main` at review time; parent `6589c880537ee61a92fb99b91b70df5bc501930c` (PT1 checkpoint), base `12f683205593b548556bd4ed6e533466bc3583c7`.
- **report_path**: `artifacts/production-trace-mask/reviews/final-differential-01.md`
- **verdict**: **PASS** — no actionable findings.
- **advance**: `true`
- **confidence**: 0.9

## Established ground (not re-checked)

- PT1 cycle PT1-cycle-1 round 1 PASS: `reviews/PT1-review-01.md` (+ manifest), snapshot `9620307d…`, worker `workers/PT1-a1.md`, 633 tests / 0 fail / 3 env skips.
- PT1 exact-snapshot checkpoint SUCCESS: commit `6589c880…`, record `checkpoints/PT1-checkpoint-01.md` (19 paths; whole-file inclusion of `docs/sprint-8-agent-history.md` Main-authorized via tool-returned selection; no push).
- PT2 cycle PT2-cycle-1 rounds 1–2 FAIL, Main-authorized conditional round 3 PASS: `reviews/PT2-review-01.md` / `-02.md` / `-03.md` (+ manifests), all three rounds consumed exactly once, no silent reset. Final reviewed snapshot `7d5fb04a…`; worker `workers/PT2-a3.md` (a1/a2 retained).
- PT2 exact-snapshot checkpoint SUCCESS: commit `8518676c…` with parent `6589c880…`, record `checkpoints/PT2-checkpoint-01.md`, exact 58 paths (47 modified / 9 added / 2 deleted), worktree-vs-manifest SHA match, semantic CRLF normalization only, unrelated index state untouched; verified per-file by the executor. Current main ref equals the checkpoint commit.
- Recorded PT2 outcomes: 677 unit tests / 0 failures / 3 skips; AndroidTest compile/build PASS; actual-main physical UI/DB runs incl. all-5-skill multi-intent run `run-6fd3a77f…` SUCCEEDED, clarify/resume and no-match proofs, current accepted link (`ACCEPTED` rev 1), Laya Ready + real inference `NO_FIT`, ledger transactions = 0.

## Differential scope: probes and dispositions (observed facts, read-only source inspection)

1. **Targeted Main interjection — PT2 `requiredIntents` vs PT1 durable run identity/replay.**
   - Facts: `AgentRunHistory.admitRun` (`AgentRunHistory.kt:73-173`) fingerprints admission from scopeId + `intents` + masked text + limits + vault scope tag only (`CanonicalAgentRetryPolicy.requestFingerprint`, lines 82-100); `requiredIntents` is not part of the fingerprint or the `agent_runs` row or the `RUN_ADMITTED` trace payload.
   - Disposition — no wrong-result replay is reachable: (a) a replayed admission (`replayed=true`, firstInsert −1) does **not** return any stored outcome; it only re-yields the existing identity, which must then be claimed (`claimPending` requires `state='PENDING'`, `AgentRunDao.kt:28-35`) before execution. A RUNNING or terminal (SUCCEEDED/FAILED/CANCELLED) run cannot be claimed, so a repeated identical send returns `Incomplete(TOOL_FAILURE, "another opener holds run …")` (`BoundedWorkflowExecutor.kt:546-554, 585-593`) — it never re-surfaces a prior result. (b) A PENDING orphan admitted earlier is taken over and executed fresh under the **current send's** request object; completion checks read `run.request.requiredIntents` of that current request (`BoundedWorkflowExecutor.kt:1612-1702`), not a stored contract, so no cross-contract result can surface; and a PENDING orphan never produced a result to replay. (c) `resumeTracked` resumes from `CLARIFYING` under the run's own original request held in the continuation (`BoundedWorkflowExecutor.kt:684-719`, `startWithAttemptKey` reuses the same `request`), so an attempt never changes its completion contract mid-run. (d) At HEAD the only production admission caller is the interactive Chat (`QuickCaptureScreen.kt:278-301`), which always passes `requiredIntents = emptySet()` with its user-checked intents; default-contract (`requiredIntents = intents`) requests occur only in tests/qualification, in separate histories/scopes. Cross-contract divergence within one production scope therefore does not occur at HEAD.
   - Conclusion: the firewall "same scope/capabilities/request/limits but different `requiredIntents` must not replay a result admitted under a different completion contract" holds by construction at HEAD because admission replay carries no result at all. Recorded as **verified-dismissed** (INFO-level residual below).
2. **Trace identity/order/losslessness under neutral streams.** `TracingHttpTransport` (`agent/TracingHttpTransport.kt`) is the single wire seam with per-event recording in wire order (`PROVIDER_STREAM_CHUNK`), explicit terminal/interrupted/unavailable stream-end markers, and non-2xx bodies recorded as complete responses; the canonical assembled body is recorded separately from the adapter's assembly. Cancel/retry: CancellationException → interrupted marker, and every retry rebinds the recorder (`host.trace.bind`) to the new attempt; deferred gaps keep original run/attempt attribution. Consistent with PT1 acceptance; no interaction defect found.
3. **Secret separation under streams.** Streaming requests go through the same recorder redaction seam (secret headers/query names) used for complete bodies; no separate streaming redaction path exists to drift. PT1 `ProductionTraceTest` no-secret proof plus PT2's Chat-Completions live runs imply this seam is exercised; not re-executed (per limits).
4. **No plaintext private-reasoning replay/public display.** Reasoning/thinking is scrubbed at the adapters while keeping opaque pairing state (thought flag, thoughtSignature, encrypted_content); it is never guard input, never a public stream fragment, and retained only inside the local trace body (`MaskedPromptBoundary.kt:637-646`, `MaskedHostedGateway.kt:28-31, 227-234`, `GeminiGenerateContentAdapter.scrubbedModelContent`). Stream display is gated by the incremental `PublicText` public gate holding back split token spans and trailing partial sensitive literals; r1's Finding 6 (INFO, streaming sound) plus r2's restored strict `scanLeak` with narrow schema-bound provider-ID exemption (`isNestedProviderIdPath`) and concatenated-display tests close this. Chat Completions reasoning fields (`reasoning_content`, `reasoning`) are channel-classified and excluded. No gap.
5. **Issued inventory vs neutral role references.** PT2 rounds verified the clean cutover (`aegis-agent-interface/4`, tools/skills v4, `s8-agent-context/v9`, `s8-agent-token-inventory/v2`, `IssuedValueShape`, no regex-kind eligibility gates, zero shims, residual-search clean). All issued inventory stays consistent with neutral role references because references issue `VALUE` for every token with shape hints only. Established ground; spot source probes (`AgentToolRuntime.valueRefsIn`) agree; no unreviewed interaction found.
6. **Scope/claim/effect vs interactive allowlist + host-demanded outcomes.** `intents` (allowlist) vs `requiredIntents` (demanded outcomes) are separated with backward-compatible default; `AlreadyInterpreted` settlement is verified by live Room re-read (event id + revision + status) and cannot be fabricated; discriminating negative tests keep `REQUIRED_EFFECT_MISSING` fail-closed for unperformed demanded effects (host-required replay, unproved absence). R3 PASS is material and self-consistent with the prompt/UI labeling; no cross-caller mismatch inferred at HEAD (see item 1).
7. **Durable UI admission/resume and stream trace coupling.** Chat run state is shell-owned and survives tab navigation; single-admission (`startTracked(admitted, …)`) removed the double-admit orphan path; projection refresh is revision-driven. The remaining orphan run rows from the pre-fix double-admit defect are intentionally preserved in the owner DB as historical evidence (disclosed in round 2). Trace coupling (per-attempt rebinds, opinion markers) is consistent. No gap.
8. **Migration/data preservation.** `LedgerDatabase.VERSION` is still 14 at HEAD (`LedgerDatabase.kt:100`); the PT2 58-path snapshot contains no schema/migration paths, so PT2 introduced no new data movement. PT1's v13→v14 migration proof (populated-preserve, explicit pre-trace gaps, ordering, cascade, rollback, older chains) stands unchanged. Same-signer in-place update retained owner rows/config per recorded PT2 evidence. No gap.

## Verified-dismissed new probes

- Cross-contract result replay via durable admission reuse — impossible; claim gate (`state='PENDING'`) blocks terminal/RUNNING runs and PENDING takeover runs under the caller's current contract (see item 1).
- Streaming lossless-order drift between displayed assembly and stored trace — impossible; both derive from the same single wire seam, wire-order chunks plus separately recorded canonical assembly.
- New PT2-caused migration/data risk — none; schema pin remains v14 with no migration scope in the reviewed snapshot.
- Private-reasoning re-emergence through resumed/chat streams — excluded by channel classification at decode and by the `PublicText` stream gate; worker and reviewer evidence match source.

## Residual (nonblocking, `unverified`)

- **INFO — durable admission trace symmetry:** the `RUN_ADMITTED` trace payload and `agent_runs` row carry intents/limits but not `requiredIntents`, and the fingerprint intentionally excludes it. This is not a correctness defect (see item 1) but means a later trace-only diagnostic cannot reconstruct which completion contract a historical run was admitted under when contracts diverge; at HEAD no production caller divergence exists, and PT2 rounds exhausted — recorded for owner awareness only.
- **INFO — repeated-send diagnostic wording:** on a repeated identical send after a run has reached a terminal state, `startTracked` reports `Incomplete(TOOL_FAILURE, "another opener holds run …")` although nothing "holds" it. Pre-existing message semantics outside the PT2 defect surface; behavioral design intent (no second workflow per admitted run) is preserved.
- **INFO — PT2 status markers:** `docs/production-trace-mask-correction.md` still shows `[~]` and `state/` files still record in-progress wording; Main-owned docs/status are explicitly allowed to remain in flight during this gate and carry no product/contract risk.

## Declared verification limits preserved

- Only the configured Chat Completions route was exercised live on-device; **Gemini and Responses streaming remain unit-tested only** (no live run claimed anywhere in the accepted records).
- Laya proof is readiness ("Ready — verified on this device", size+hash+ONNX open) plus one real local inference producing a `NO_FIT` snapshot; this is **not** a category-quality/suggestion-accuracy claim.
- No financial Confirm was exercised: ledger `transactions` count = 0 across all live proofs; no data clearing, credential export, or test-app clutter; provider/key/notes/drafts/grants/allowlist preserved by same-signer in-place update.
- Pre-phone-dismissal scope (PT1 non-phone) was superseded by the owner's reconnect directive and covered by PT2 physical-device acceptance; PT1's code/non-phone gate naming remains accurate.
- No historical S8.13/S8.14 metric was rewritten; old scope owner PASS preserved; pre-existing user files remain uncommitted intentionally.

## Conclusion

Every assigned per-task evidence gate and both Bronze exact-snapshot checkpoints are established ground; the differential hunting over their intersection (trace×stream, admission×contract, UI×trace, migration×checkpoint) surfaced no incorrect-behavior risk and no unreported acceptance gap. The Main-requested `requiredIntents` interaction is dispositioned as structurally safe at HEAD for the stated firewall, with residual diagnostics noted above.

- verdict **PASS**; `advance: true`; actionable findings: **none**.
