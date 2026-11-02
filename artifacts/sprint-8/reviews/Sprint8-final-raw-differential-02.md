# Sprint 8 Final Differential Review — Raw-Local Cutover (attempt a106)

- **Task:** `Sprint8-final-differential`, attempt `Sprint8-final-raw-differential-a106`, agent `Sprint8RawFinal106` (fresh `deep-reviewer`).
- **HEAD under review:** `7adf65219532b53fecfc82c894c83d7e7c83f142` (refs/heads/main; parent `413b1e7d3bc54e6fbd1f2afbe211ec9005039104`). Worktree `F:/ai-ml/aegis-f`; no source drift since the checkpoint; 5 Main-owned plan/history files remain uncommitted by design.
- **Scope:** S8.14 raw-local cutover only (16 candidate paths). Hosted/API/model-benchmark/broad-UI campaigns excluded per current plan lines 91-96; prior passed S8.13/S8.14 gates established and not rerun.
- **Gates accepted as established ground:**
  - Evidence PASS cycle-1 r2, `artifacts/sprint-8/reviews/S8.14-raw-local-review-02.md`, attempt `S8.14-raw-local-review-a104`, agent `S814RawReview104`, snapshot SHA-256 `30f3f4ed12ac755a39e6aee13c5e8e2dcbe9e4a925304ec28c6d7bfbdaaaf044`.
  - Bronze exact-snapshot checkpoint, `artifacts/sprint-8/checkpoints/S8.14-raw-local-checkpoint-02.md`, attempt `S8.14-raw-local-checkpoint-a105`, agent `S814RawCheckpoint105`, commit `7adf65219532b53fecfc82c894c83d7e7c83f142` (16 paths; EOL proof for one LF-normalized doc).
  - Historical cycle-1 r1 PASS `artifacts/sprint-8/reviews/S8.14-production-review-01.md` + `artifacts/sprint-8/checkpoints/S8.14-production-checkpoint-01.md` at `413b1e7` (closed, not reopened).
  - Superseded prior differential report `artifacts/sprint-8/reviews/Sprint8-final-differential-01.md` — not reused; no old finding carried.

## Verdict: FAIL — one actionable finding (documentation-only)

## Actionable finding F1 — MEDIUM acceptance criterion miss, doc-only: five live-tense claims of the deleted local safety guard survive in the delivered docs

**Criterion basis:** current plan line 96 — "Remove the now-obsolete safety-refusal adapter branch/tests/docs rather than retain a shim." Code, tests, and the adapter branch were cleanly removed (`SuggestionSafety.kt` deleted; no identifier references anywhere in `app/src`, `tools`, or `docs`; `WorkflowDenialReason` (`app/src/main/java/io/github/triet4p/aegisf/data/WorkflowCommandTypes.kt:63-112`) has no safety member). But the four modified doc files still describe the removed guard as current behavior. The r2 report's "documentation coherence" claim (review-02 §8) is overstated for exactly these spots; its hygiene sweep matched only the removed identifiers and missed prose.

Contradictory delivered text vs. the deliberate removal:

1. `docs/sprint-5-effective-context.md:140-143` (Privacy and authority boundary, present tense): "The safety guard that keeps OTP/security-like text and bank reference codes out of the model **now applies to the readings as well**, so a second door to the model cannot be opened by the new context." — Directly asserts the exact behavior this cycle deleted; the companion queue doc now states the opposite (`docs/sprint-7-confidence-queue-policy.md:103` "OTP/security-word and bank-reference patterns are not local Laya input filters").
2. `docs/sprint-8-agent-executor.md:103`: an unqualified effect-verification row "**Suggestion-safety refusal** | the `description` clarification is returned before local inference…" in the live completion-verification table, describing an effect path that no longer exists (no return site: the requestClassification branch was removed at the former `WorkflowCommandService.kt:846-855` call site).
3. `docs/sprint-8-agent-contract.md:292-293` (§5.2 authority ownership): "blank, sparse, **blocked-safety**, or over-budget inputs stay manual" — lists a removed input class as a current manual-review cause, contradicting the same doc's lines 54-56 ("S8.14 local Laya receives raw draft descriptions… without OTP/security-word/bank-reference filters").
4. `docs/sprint-7-confidence-queue-policy.md:127` ("plus **safety** and model-unavailable outcomes…") and `:232-233` ("input refused by the **safety/budget gate**") — the budget gate remains valid, but "safety" as a missing-snapshot cause or outcome family no longer exists in `WorkflowDenialReason`/`LayaModelProvisioner.Status`.
5. `docs/sprint-5-workflow-contract.md:252-253` (frozen input precedence §5): "safety-blocked note yields null (no silent fallback to memo)" — no code path can now block a stored note ("safety-blocked"); intake screening rejects bodies before storage, and `SuggestionSafety` is deleted.

**Why it matters:** these files are the normative behavioral specs this sprint's own reviews and future tasks consume. An executor/evaluator following them expects a safety-refusal clarification that the application can never produce (misdiagnosing behavior or fabricating a failure class), and the contract doc contradicts itself within one file. No runtime behavior is affected; all shipped behavior is correct per evidence.

**qa:** evidence consulted — deleted source file, zero-reference repo sweep, `WorkflowCommandTypes.kt:63-112`, the six contradictory doc locations above, and the r2 report's §8 documentation-coherence claim it overstated. Open question for Main: none — the contradictions are headquartered in r2-candidate-modified files; correction is a small doc assignment, not a code change.

## Verified new-risk probes (dismissed)

- **Raw-local full context vs. hosted egress masking:** dismissed. Removed call sites were all local-ingress classification paths (`WorkflowCommandService.kt:846-855`, `LayaEffectiveContext.kt:187`, `AgentToolRuntime.kt:992-1007`, worker record + review-02 §1). Every hosted observation crossing to a provider stays masked: draft observations used by the hosted path apply `mask(draft.classificationDescription())` before envelope construction (`AgentToolRuntime.kt:1636,1650`), and all provider egress crosses `MaskedHostedGateway` (`AppContainer.kt:188`, executor doc §Privacy, fail-closed on raw literals: `docs/sprint-8-agent-executor.md:122-123`). Raw text reaching the local model is the owner requirement, not a privacy finding.
- **Classifier review-only financial boundary:** dismissed. `classifyDraft` (`AgentToolRuntime.kt:899-992`) returns observations/manual states only; r2 device + JVM evidence shows unchanged ledger rows, links, and no financial confirmation.
- **Ordinary missing/unavailable handling after gate deletion:** dismissed. Empty description → `NeedsClarification` → `MISSING_REQUIRED_MEANING` (`AgentToolRuntime.kt:991`), genuine engine failure → `MODEL_UNAVAILABLE` (`:989`), over-budget denial retained (`WorkflowCommandService.kt:903`); no shim or disguised gate remains.
- **Trusted source/revisions/provenance/replay:** not touched by this candidate (revision conflict check at former `WorkflowCommandService.kt:831-837` and journal replay verified in r2); no repo change since, so r2 evidence stands.
- **Tool-call cap:** `BoundedWorkflowExecutor.kt:89` default `maxToolCalls = 24` matches the recorded operational-limit revision; CHANGELOG lines 45-48 document it.
- **Responses continuation/privacy-UI coherence:** executor doc reasoning-continuation and token-inventory sections coexist with the new raw-local paragraph (`docs/sprint-8-agent-executor.md` §151-160); UI strings carry no local-screening claims and hosted privacy failures still describe only the hosted boundary. CHANGELOG entry (lines 50-54) matches delivered behavior including the blank-description error mapping.
- **Provenance confusion:** committed tree vs. reviewed snapshot verified by the checkpoint record (15/16 exact SHA-256, 1 LF-normalized doc with recorded proof); HEAD equals that commit.

## Unverified / known limits

- Read-only review: no tests rerun, no Git/checkpoint operations, no device or network runs, no product edits.
- Prose sweep covered the five sprint-5/7/8 docs and identifiers repo-wide; non-candidate docs (e.g., historical handoff narratives, sprint plans) intentionally excluded per assignment. Plan pages 91-96 lines quoting the removed mapping describe history/decisions, not delivered behavior, and were treated as Main-owned records.
- F1 is doc-only; the runtime classification path itself passed code-level sanity only as read, not via new execution (exercised verification remains the r2 evidence).
