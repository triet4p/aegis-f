# Sprint 10 — Recovery, Privacy and Personal Daily-Use Release

## Sprint Goal

Deliver a privately signed Android release whose four-screen capture/review/agent workflow remains correct through backup, restore, upgrades, offline use and model/resource failures. Preserve all recovery obligations of the former Sprint 6 release plan.

## Status and Dependencies

- Status: Planned. Dependencies: Sprints 5–9 acceptance and technical gates, plus accepted Sprints 1–4 behavior.
- Workflow: **Full**. Each **S10.1–S10.10** task requires independent evidence PASS and a separate Bronze-only exact-snapshot checkpoint before the next independent task; then assigned final cross-feature differential review and one consolidated user daily-use acceptance pass. No shared batch assumed approved. Explicit role/checkpoint/commit authority required; no automatic push/store publication.
- Worker/reviewer direct ADB permitted with privacy-safe fixtures and user-data preservation. Evidence root: `artifacts/sprint-10/`.

## Acceptance Criteria

- Versioned export/restore preserves categories, rules, exact ledger, drafts, source provenance, saved captures/events/links, user corrections, model-profile descriptors and review identity. Weights, credentials, OTP/unrelated notifications and transient prompts/reasoning are not backup contents.
- Corrupt/unsupported backups and failed migration cannot partially overwrite live data. Full replacement requires user confirmation and a recoverable pre-restore copy; restore cannot replay old agent mutations or re-confirm transactions.
- Privacy policy matches actual storage/permissions/logs/endpoints and Android OS backup/data-extraction configuration. No cloud inference or automatic cloud backup of financial data.
- A stable same-package/same-key release update preserves the populated previous release/schema. No uninstall of the user's only data copy to hide signature/migration failures.
- Four screens and the complete note/notification/Laya/agent/review/dashboard workflow run on the actual phone, offline after provisioning, with honest resource/language/capability limits and manual fallback.
- Measured workflow effort and inference latency/resources are reported with dataset, cold/warm state and sampling limitations. No promises of statistical accuracy, calibrated confidence or battery performance without supporting evidence.

## Ordered Atomic Tasks

- [ ] **S10.1 — Implement versioned complete-state export.** Use the Android picker to export relationally consistent exact-VND state, provenance/captures/events/links, corrections, review flags and selected profile descriptors. Exclude weights/secrets/transient traces and disclose sensitive unencrypted content unless real encryption is implemented/verified. Acceptance: populated export validates references and preserves saved user context without leaking forbidden notification or model-runtime buffers.
- [ ] **S10.2 — Implement validated atomic restore.** Validate schema/type/ranges/references/ownership/version and file size before replacing data. Require explicit preview/confirmation, preserve recoverable previous state and prevent accidental duplicate merge. Mark restored pending work for revalidation instead of replaying old commands or applying obsolete inference. Acceptance: corrupt/unsupported input has zero partial effects; replacement preserves exact confirmed totals and can recover from interruption.
- [ ] **S10.3 — Prove previous-schema migration and recovery.** Restore known populated exports into a separate clean app-data environment; compare records/totals/source links/rules/draft decisions. Exercise an actual accepted pre-Sprint-5 schema fixture and a later schema update, failed migration and pre-restore recovery. Acceptance: source/legacy-origin semantics, stable IDs and financial history survive; do not merely reopen the original database or wipe it on failure.
- [ ] **S10.4 — Audit privacy, retention and permissions.** Verify notification allowlist/filtering, capture retention/deletion, source excerpts, agent context minimums, diagnostic logs, model-download endpoints and Android automatic-backup/data-extraction settings. Acceptance: financial data remains app-private/off cloud, deletion behavior is honest, and no raw banking/chat bodies or unrelated notifications leak through logs/exports/OS backup.
- [ ] **S10.5 — Verify cross-feature lifecycle and concurrency.** Exercise lock, backgrounding/process recreation, notification-access revoke/regrant, note edits/category changes during inference, missing/corrupt model, cancelled generation and restored pending work. Acceptance: drafts/captures persist, stale work cannot mutate current state, intake is independent and manual/dashboard/review remain usable. Document actual force-stop/OEM limits without claiming impossible always-on operation.
- [ ] **S10.6 — Measure and optimize demonstrated phone bottlenecks.** Measure app startup, dashboard/query/queue responsiveness, Laya cold/warm and agent first-result/prefill/decode, process memory, repeated-use heat/battery observations and storage/low-space behavior. Use fixed workloads and metric labels; optimize only measured bottlenecks while preserving semantics. Acceptance: resource admission prevents avoidable competing resident sessions and model/download failure cannot corrupt financial state.
- [ ] **S10.7 — Establish stable private release signing.** Generate/select a dedicated private key, keep credentials outside Git, document key backup and produce the APK through existing CLI tooling. Prove same-key release-to-release update over populated data; handle debug/release key mismatch with explicit safe migration strategy, never uninstall the user's only copy. Acceptance: package/key/version and exact preserved state are evidenced.
- [ ] **S10.8 — Complete four-screen and agent daily-use technical proof.** On the phone, exercise dashboard filters/charts/drill-down, manual entry/category management, bank draft intake, quick capture/manual fallback, multi-event agent matching, automatic Laya and confidence queue, explicit confirmation/undo boundaries and offline operation. Compare exact VND results against the fixture. Include unmatched/ambiguous context and unsupported-profile cases, not only the happy path.
  Acceptance: each supported path completes with actual model/device behavior, scope/error states are visible, no uncertain draft bypasses user confirmation and offline exact totals reconcile after corrections/restart.
- [ ] **S10.9 — Deliver user/developer release instructions.** Document installing/updating, signing-key precautions, notification permissions/bank limits, model selection/provisioning/latency, capture retention, matching review/undo, confidence meaning, backup/restore and offline/manual paths. Include version, actual measurements and known capability/quality limits; exclude raw financial data, binaries, credentials and temporary probes from repository delivery.
  Acceptance: instructions match the observed release/package/model capabilities and a reproducible recovery/update walkthrough; sensitive financial text, secrets, weights and probes are absent from intended release records.
- [ ] **S10.10 — Prepare the final acceptance package.** Assemble the consolidated daily-use scenario, prior technical evidence, release artifacts and recovery instructions; verify the package is readable and records known limits honestly. Acceptance: all prior required task gates/checkpoints have passed, expected outputs for the user walkthrough are explicit, and no core criterion is relabelled optional. After this task's own evidence/checkpoint, run the assigned end-of-sprint differential review, correct/review/checkpoint any findings, then perform the single user acceptance pass and close release status. Those are sprint closure gates, not prerequisites for this task's own checkpoint.

## Required Verification

Export known populated state → restore in an isolated clean environment → compare exact relational/financial state → attempt corrupt restore and observe no mutation → recover the pre-restore copy. Install a same-key release update over a previous populated release and prove schema/state preservation. Run the full supported-bank + multi-event capture + Laya queue + explicit confirm + dashboard workflow and manual fallback offline. Observe actual rendered UI/error states; fixtures/tests alone do not establish device delivery.

## Decisions / Boundaries

- Backup/release is not reduced because agent work expanded. Existing data and private signing remain essential.
- No public distribution, cloud sync or automatic push. Durable captures are intentional financial records; a claim of no raw chat logs must not conceal their actual storage/backup behavior.
- Parent: [Project Plan](../PLAN.md). Previous: [Sprint 9](sprint-9.md).
