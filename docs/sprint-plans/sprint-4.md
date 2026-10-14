# Sprint 4 — On-Device Existing-Category Suggestions

## Sprint Goal

Use a verified local Laya runtime to suggest existing user-defined categories for English expense descriptions while preserving unknown handling and manual control.

## Status and Dependencies

- Status: Pending.
- Dependencies: Sprint 2 for category/ledger behavior; Sprint 3 for the notification-review integration task. Standalone model evaluation may begin after Sprint 2.
- Workflow: **Lightweight**. Runtime parity and labeled domain evaluation precede adoption; review and physical-device inference evidence are required before the closing local commit. No automatic push.
- Status legend: `[ ]` pending / `[~]` in progress / `[x]` done.

## Acceptance Criteria

- The pinned English Laya checkpoint/export and tokenizer run through ONNX Runtime Android on the phone, offline, without requiring a desktop inference server.
- Android decisions match the reference preprocessing/decision semantics within documented numerical tolerance; temperature/calibration is applied correctly rather than labeling raw logits as probabilities.
- Suggestions use stable category IDs and current active category metadata, not fabricated/free-form IDs or names.
- The app imposes no category-count cap: candidate selection respects the runtime token budget, preserves an unknown/no-fit outcome, and never silently truncates an oversized request.
- Ambiguous/no-fit inputs remain reviewable and manually categorizable. No model result silently confirms or changes a financial record.
- A labeled English evaluation records suggestion quality, incorrect confident decisions, candidate retrieval misses, cold/warm latency, and peak process memory on the actual phone. Device/model adoption is based on observed evidence, not published GPU numbers.

## Ordered Atomic Tasks

- [ ] **S4.1 — Select and reproduce the Laya artifact.** Verify checkpoint/export provenance, license, graph operators, tokenizer special tokens, markers, head budget, and calibration configuration. Pin revisions/hashes, produce a reference runner and labeled English expense examples, and do not assume a generic text-classification pipeline reproduces the decision head.
- [ ] **S4.2 — Integrate the Android decision runtime.** Load the graph/external weights and implement tokenizer, sequence construction, option markers, outputs, and calibration. Prove reference/Android parity on meaningful choice, unknown, and boundary cases; verify native-library compatibility with the phone's page size/ABI.
- [ ] **S4.3 — Implement local model provisioning.** Transfer or explicitly download a verified artifact into app-private storage without committing weights. Expose missing/corrupt/incompatible model states and preserve manual operation; initial acquisition may use network, inference may not.
- [ ] **S4.4 — Implement scalable category candidates.** Construct choices from active categories and a bounded shortlist for oversized sets, including unknown/no-fit. Use user correction rules and deterministic category metadata matching first; measure shortlist recall on the evaluation set and expose uncertainty when selection loses evidence.
- [ ] **S4.5 — Implement background inference lifecycle.** Run cancellable, bounded decision work off the main thread, serialize unsafe session access, and release sessions when appropriate. Verify typing/navigation and notification persistence do not stall while inference runs; do not retain a model in the notification callback.
- [ ] **S4.6 — Deliver suggestion UI for manual entry.** Show the existing-category suggestion and allow accept/change/ignore before saving. Handle edited descriptions, changed/archived categories, and late results without applying stale suggestions to a different form state.
- [ ] **S4.7 — Persist explicit user correction rules.** Offer a deliberate remember-rule action for repeated merchant/description patterns. Apply stored rules before neural inference, allow removal, and avoid overwriting unrelated transactions or silently generalizing one correction globally.
- [ ] **S4.8 — Integrate suggestions into notification review.** Apply rules/model only where permitted parsed content is meaningful for the English-first model; leave opaque or unsupported-language memos unknown and permit an English note. Review confirmation remains mandatory.
- [ ] **S4.9 — Complete the adoption evaluation.** Measure labeled top-choice/shortlist quality and unknown behavior, cold/warm p50/p95 latency, peak PSS, lifecycle responsiveness, and offline inference on the phone. Test empty categories, overlapping labels, many categories, archived IDs, prompt-like descriptions, and multiple bank drafts. Record observed limits and manual fallback behavior; remove probes and document model use.

## Required Verification

In airplane mode after model provisioning, enter English descriptions such as `Lunch at a cafe, 45000 VND`, `Monthly rent`, and an intentionally ambiguous `Payment to Alex`. Observe valid current category suggestions for supported inputs and safe review for uncertainty. Add categories beyond one request's budget, archive a suggested category during a pending request, and confirm no stale/invalid assignment. Reopen the app and exercise notification-review suggestions without blocking intake.

## Decisions / Blockers

- English Laya 421M is the first candidate; Jev's hosted API is not an offline fallback. The multilingual checkpoint is not required merely because the currency is VND.
- Schema validity and reported confidence are not correctness guarantees. Confidence thresholds require domain/device evidence rather than arbitrary advertised probabilities.
- Model performance remains unverified. If export/operator support or device resources prevent adoption, document the measured failure and evaluate an offline alternative explicitly; do not mark this sprint complete with a rule-only substitute.
- Parent roadmap: [Project Plan](../PLAN.md). Previous: [Sprint 3](sprint-3.md). Next: [Sprint 5](sprint-5.md).
