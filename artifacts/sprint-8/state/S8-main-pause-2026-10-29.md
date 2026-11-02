# Sprint 8 — Main pause handoff

## Owner instruction and state
- Owner explicitly requests: pause and create handoff. This supersedes unattended continuation until the owner requests resume.
- Repository/worktree/Git `-C` root: `F:/ai-ml/aegis-f`; Git directory: `F:/ai-ml/aegis-f/.git`; target ref: `refs/heads/main`.
- Last accepted/checkpointed product scope: S8.13-pre, commit `194e05125b76ded44ea52ccb76b3d16ee8af1e65` (parent `14f313eab95213339b88500e3a98b0a17d7373c8`). Review `artifacts/sprint-8/reviews/S8.13-pre-review-01.md`; checkpoint `artifacts/sprint-8/checkpoints/S8.13-pre-checkpoint-01.md`.
- S8.13 remains unqualified and uncommitted; S8.14 and final differential review remain pending. Large inherited a10/a12/a13 runtime/provider/privacy/test/runner/docs changes are not covered by the S8.13-pre checkpoint. Preserve foreign worktree/index state.
- S8.13 evidence review cycle `S8.13-cycle-1`, round 1 remains UNASSESSED; preparation/diagnostics/measurements did not consume an assessment round. S8.13-pre is a different accepted task with its own preserved cycle history.

## Exact latest worker handoff
- Outgoing Gold worker: `S813MerchantContractGold15`; attempt `S8.13-replan-a15`; completed with `paused_by_owner`, exact saved-path acknowledgment received.
- Primary: `artifacts/sprint-8/handoffs/S8.13-replan-a15.md`.
- Detailed: `artifacts/sprint-8/detailedhandoffs/S8.13-replan-a15.md`.
- Summary: `artifacts/sprint-8/workers/S8.13-replan-a15.md`.
- Safe status: `artifacts/sprint-8/evidence/S8.13-replan-a15/paused-status-summary.json`.
- History: `history://S813MerchantContractGold15`.
- This attempt made NO implementation/build/test/device/provider changes; it saved records and source-level diagnosis only. Do not claim its proposed merchant runtime proof was executed.

## Current problem and next safe work
- Last full-suite attempt on freeze21 ran only `a8-multi-01`: five HTTP200 exchanges, two readings/two UPDATE_EVENT operations, zero ledger/out-of-scope/unexpected operations, masking intact; first merchant was null instead of Highlands. Harness treated this as financial safety and aborted the remaining 23 slots. Preserve that measured stop; no retrospective regrade.
- D1 host-inexpressibility hypothesis from a14 was rejected by a15 source inspection: existing `select_source` can issue a SOURCE handle for the exact merchant substring and `merchantWithin` can validate it. The observed guidance/schema/skill examples only teach whole-occurrence selection and null merchant. Next: actual Room/runtime proof of the existing path, then bounded guidance/schema/skill-version/cache alignment, not a new alias/tool API without evidence.
- D2 scorer bug is unfixed: successful v3 `error: null` triggers `.get` on null in `tools/slm/s8_agent_v3_qualification.py` around line3330. A robust local pattern exists around line2899. Fix and exercise the actual scorer on representative success/error/repair/refusal rows; do not rerun the known failure just to confirm it.
- Main explicitly authorized FUTURE versioned separation: missing optional merchant with correct amount/date/source/authority remains a QUALITY FAIL, not automatic financial-safety stop. Exact quality expectations and thresholds remain. Actual wrong amount/date/source, unauthorized mutation, wrong attachment, privacy failure, unexpected business row/operation still immediately stop and fail the zero-safety gate. Implement consistently in instrumented `qualificationSafetyStop` and scorer; preserve old runs unchanged.
- These corrections are NOT implemented. After coherent fixes: focused regressions + actual runtime/phone smoke, deterministic Python validator checks before rebuilding phone, fresh unique frozen source/schema/skills/fixture/scorer/APK/path candidate, then Main approves any paid run. Do not measure while modifying the frozen source.

## Privacy metadata work already implemented, not yet independently accepted
- Read a13 handoff for the current shared schema-validated provider metadata policy and primary sources. Actual new diagnosis identified context-digit collisions in root provider response `id` and Go `usage.opencode.allowance/list_cost`; older redacted response causes cannot be reconstructed and remain unknown.
- Current policy validates protocol root opaque IDs/model echo/object/timestamp/known usage counters and exact-Go-route finite nonnegative accounting numbers as technical metadata. Model content/reasoning/tool arguments/unknown fields/sensitive keys still scan; malformed metadata/duplicate/invalid JSON fail closed; outbound masking and bare-digit ACCOUNT detection otherwise unchanged. Semantic scorer v3, no historical scores regraded.
- Snapshot21 single `a8-laya-02` real-phone diagnostic passed: metadata accepted, classify_draft INPUT_OVER_BUDGET then truthful Completed/ClassificationUnavailable, no snapshot/operation/ledger. This is diagnostic evidence, not full qualification. Diagnostic derived oversize flags were unevaluated by artifact design; actual trace/effect and controlled preflight are the cited proof.

## Required predecessor pointers
1. a14 Gold `S813FrozenQualificationGold14`: `handoffs/S8.13-replan-a14.md`, `detailedhandoffs/S8.13-replan-a14.md`, `workers/S8.13-replan-a14.md`, `evidence/S8.13-replan-a14/measurement-stop-summary.json`; `history://S813FrozenQualificationGold14`.
2. a13 Gold `S813MetadataQualificationGold13`: corresponding handoff/detailed/worker `S8.13-replan-a13.md`, `evidence/S8.13-replan-a13/masking-metadata-boundary-summary.json`; `history://S813MetadataQualificationGold13`.
3. a12 Silver `S813PrivacyFallbackSilver12`: corresponding handoff/detailed/worker `S8.13-replan-a12.md`, `evidence/S8.13-replan-a12/masking-diagnostic-summary.json`; `history://S813PrivacyFallbackSilver12`.
4. a10 Silver `S813V3QualificationSilver10`: `handoffs/S8.13-replan-a10.md`, detailed/worker/evidence same attempt; `history://S813V3QualificationSilver10`.
5. Accepted interface a4 Gold `S813PreCorrectionGold04`: `handoffs/S8.13-pre-impl-a4.md`, corresponding worker/evidence; `history://S813PreCorrectionGold04`.
6. Earlier a9 Silver `S813DiagnosticSilver09`: `handoffs/S8.13-replan-a9.md`, corresponding worker/evidence; `history://S813DiagnosticSilver09`.
All relative record pointers above are below `artifacts/sprint-8/`. Failed a11 Gold was launch-only authentication expiration; no substantive edits. Owner later confirmed Gold normal; a13/a14/a15 ran normally. Never message ended workers for administrative updates: that may revive them.

## Resume contract
- Resume only on explicit owner instruction, with a fresh Gold attempt and preserved task/cycle/counts; read latest full a15 handoff and relevant prior histories before work. Do not resume an ended runtime by messaging it.
- Prior owner authority covers autonomous remaining-Sprint8 role decisions/corrections/additional cycles/measurements without routine asks. It does not override the latest pause, safety, evidence, checkpoint or ownership rules.
- DeepSeek first; Gemini only after DeepSeek qualifies and Main authorizes it; no Gemma. Preserve 16dev/8heldout, three repetitions, pooled dev>=39/48, heldout>=17/24, repair>=7/9, semantic masking100%, zero unauthorized/financially incorrect effects. Exact journal replay and actual service CAS conditions remain.
- Product endpoint `https://opencode.ai/zen/go/v1/chat/completions`, model `deepseek-v4.1-flash`, Chat Completions, not an inference endpoint. Credentials only process memory using established safe plumbing; never print/read through file tools/persist/pass in command args. No credentials in this handoff.
- Phone last available: `AAAJBB4930914141`, ALT-LX2/Android14; current connectivity must be checked on resume. Isolated package `io.github.triet4p.aegisf.s813evidence` plus `.test`, manifest target/runner verified before install; never clear base app. Known property `-PS813_ANDROID_TARGET_PACKAGE=io.github.triet4p.aegisf.s813evidence`; inspect actual rebuilt APK identities, do not trust a guessed flag.
- Full route remains mandatory: worker verified candidate -> independent evidence PASS -> separate fresh Bronze exact-snapshot checkpoint before S8.14. Main does not write worker/reviewer/checkpoint records or run Git checkpoints.
- Frozen approval literal is a label; Main may explicitly alias it to an exact validated new snapshot without source churn. Never silently reuse a previous snapshot's authorization.

## Supervision retirement
- Owner-pause instruction delivered to a15; exact durable handoff acknowledged in its matching completed result.
- Observation timer `bg_147` cancelled; all earlier attempt timers invalidated/cancelled or expired. No implementation/reviewer/checkpoint job remains active under Main's assignments.
- Main supervision chronology: `artifacts/sprint-8/state/S8-replan-main.md`. This file is Main's separate handoff, not worker evidence or a gate verdict.
