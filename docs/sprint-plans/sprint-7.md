# Sprint 7 — Proactive Laya and Uncertainty-First Review

## Sprint Goal

Expose the value already present in Laya: classify bank descriptions and user-enriched draft context automatically, and prioritize the reviews that most need human attention without automatically confirming money.

## Status and Dependencies

- Status: Planned. Dependencies: Sprints 5–6 shared revisions/commands, context provenance and usable review surface.
- Workflow: **Full**. Every **S7.1–S7.8** task requires independent evidence PASS then a separate Bronze-only exact-snapshot checkpoint before the next independent task; assigned final differential review follows. No shared checkpoint batch assumed approved. Obtain explicit scoped role/checkpoint/commit authority before dispatch; no push.
- Worker/reviewer ADB debugging authorized; one consolidated user pass at sprint end. Evidence root: `artifacts/sprint-7/`.

## Acceptance Criteria

- Persisted supported notifications automatically schedule Laya classification using available permitted bank context, without opening a transaction or typing a description. Useful context changes schedule only relevant fresh work.
- Classification never delays notification persistence or blocks the UI. Missing model, cancellation, crash/restart and work coalescing preserve drafts and avoid duplicate/unbounded inference.
- Store real model probabilities and the actual existing Laya confidence meaning, not a fabricated calibration estimate. Category choices remain user-controlled; unknown/no-fit and poor retrieval remain visible.
- Queue orders unresolved linking/data conflicts first, then stale/missing/unknown classification, then comparable fresh predictions by ascending confidence with stable age/ID ties. Focus does not jump during interaction, high-confidence items remain accessible and older work is not silently starved.
- A dominant category may be labelled relatively confident only under an explicit measured policy. It moves later in review; it is never auto-confirmed. Uncertain note matching cannot be hidden by high category confidence.
- User overrides, category changes and stale inference results cannot corrupt queue priority or overwrite current data.

## Ordered Atomic Tasks

- [ ] **S7.1 — Define confidence and queue policy from the real contract.** Reuse S5.1's inspected confidence semantics, candidate-set constraints, correction-rule behavior and unknown handling. Freeze comparability grouping, the confidence-label policy and deterministic ordering/tie/focus rules. Acceptance: representative actual outputs and boundary fixtures demonstrate priority without assuming cross-model/candidate-set calibration or silently inventing thresholds.
- [ ] **S7.2 — Trigger classification from bank intake.** After durable deduplicated draft persistence, enqueue bounded classification work against a context/catalog revision. Honor correction rules, manual selection and existing intake permission/privacy boundaries. Acceptance: a supported bank event creates one draft and one logical work item while intake succeeds even if the model is missing or busy; duplicate notifications do not duplicate inference or ledger records.
- [ ] **S7.3 — Coalesce context-driven classification jobs.** Implement resumable pending/running/result/cancelled/failed work transitions, revision-aware coalescing and resource admission using existing Android lifecycle conventions. Reclassify on accepted relevant context/rule/catalog changes, not every screen redraw. Acceptance: rapid edits, process recreation and notification bursts keep the latest job, preserve all drafts and do not permanently hold inference sessions in the listener.
- [ ] **S7.4 — Apply versioned suggestions safely.** Persist fresh probability/confidence snapshots and unknown/candidate metadata; reject stale completions after context/model/category changes. Retain user selection and existing category archive/delete guarantees. Acceptance: a result arriving after user correction or source unlink cannot overwrite it, rank under old candidates, or select an archived/deleted category.
- [ ] **S7.5 — Implement uncertainty-first queue queries.** Apply policy through bounded/paged queries with stable tie-breaking, visible unresolved/stale/confidence groups and chronological override. Preserve open-item focus; provide explicit refresh/next-work behavior so newly changed scores do not move the item under the user's finger. Acceptance: seeded high/low/missing/unknown/conflicting drafts sort correctly, no record is hidden, and oldest unresolved work stays discoverable.
- [ ] **S7.6 — Surface automatic inference and review controls.** Show queued/busy/stale/error/relatively-confident status with actual options and provenance. Support cancel/retry/manual category choice; adding context can improve suggestions without leaving review. Acceptance: missing model and failed inference remain actionable, confident items can still be reviewed/overridden, and no label implies guaranteed accuracy.
- [ ] **S7.7 — Verify real-model quality and lifecycle boundaries.** Run actual Laya on supported bank memo-only, informative user context, opaque memo/no-fit and larger category sets. Exercise model/resource unavailability, locks/restart, rule/category edits during inference and delayed completions. Record observed accuracy/latency and inherited retrieval limits; do not claim that automation fixes classification quality.
  Acceptance: real-model outputs and cold/warm observations are recorded by scenario; unknown/poor-retrieval cases stay visible, stale results do not apply, and unavailable/cancelled inference leaves intake/manual review usable.
- [ ] **S7.8 — Prove reduced review effort.** On the phone, intake a known draft set, let classification happen automatically, resolve uncertain cases first, inspect high-confidence items, correct one and confirm explicitly. Compare description-entry count/taps/time to the earlier manual-invocation path; reconcile exact dashboard totals. Document queue policy, limitations and manual fallback before final review/user acceptance.
  Acceptance: drafts receive suggestions without manual model invocation, uncertainty ordering is observable and overridable, explicit confirmations reconcile exactly to the dashboard, and any claimed effort reduction is backed by the paired fixture.

## Required Verification

Actual Laya, physical notification intake and a seeded fixture with controlled contexts/confidence edge cases are all required; deterministic queue fixtures prove ordering but do not replace real model inference. Show a high-confidence category on an uncertain context link remains in the unresolved group. Restart during work, accept a user override before a delayed result, and confirm intake/manual operation continue offline independently of model availability.

## Decisions / Boundaries

- No agent is needed to gain this sprint's value. Memo-only categorization may be weak; show unknown/uncertain and let the user enrich context.
- Existing confidence is a ranking input, not a financial authorization token. Confidence labels are relative and cannot claim statistical calibration without evidence.
- Parent: [Project Plan](../PLAN.md). Previous: [Sprint 6](sprint-6.md). Next: [Sprint 8](sprint-8.md).
